package top.howiehz.halo.transformer.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;
import top.howiehz.halo.transformer.core.MatchRuleBooleanMinimizer;
import top.howiehz.halo.transformer.core.RuntimeTransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationRule;

@Slf4j
@Component
public class TransformationRuleRuntimeStore {
    private static final GroupVersionKind RULE_GVK =
        GroupVersionKind.fromExtension(TransformationRule.class);
    private static final long WATCH_RECONNECT_BASE_DELAY_MILLIS = 1_000L;
    private static final long WATCH_RECONNECT_MAX_DELAY_MILLIS = 30_000L;

    private final ReactiveExtensionClient client;
    private final Object refreshMonitor = new Object();
    private final Object snapshotMonitor = new Object();
    private final Object watchMonitor = new Object();
    private final long watchReconnectBaseDelayMillis;
    private final long watchReconnectMaxDelayMillis;
    private volatile RuleSnapshot cachedSnapshot = RuleSnapshot.empty();
    private volatile List<SkippedEnabledRule> lastSkippedEnabledRules = List.of();
    private volatile boolean refreshRequested;
    private volatile boolean refreshRunning;
    private volatile boolean watching;
    private volatile RuleWatcher watcher;
    private volatile ScheduledExecutorService watchSupervisor;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> refreshRetryTask;
    private volatile int reconnectFailureCount;
    private volatile int refreshFailureCount;

    /**
     * why: 生产 bean 需要明确主构造器；本类额外保留了一个仅供测试覆盖退避参数的构造器，
     * 若不显式标记，Spring 会把它当成“多构造器 bean”并退回去找无参构造，最终导致插件启动失败。
     */
    @Autowired
    public TransformationRuleRuntimeStore(ReactiveExtensionClient client) {
        this(client, WATCH_RECONNECT_BASE_DELAY_MILLIS, WATCH_RECONNECT_MAX_DELAY_MILLIS);
    }

    /**
     * why: 测试需要把退避窗口缩短到毫秒级，才能稳定覆盖重连与 refresh retry 语义；
     * 因此保留一个包级构造器注入测试参数，但不暴露给 Spring 作为候选主构造器。
     */
    TransformationRuleRuntimeStore(ReactiveExtensionClient client,
        long watchReconnectBaseDelayMillis,
        long watchReconnectMaxDelayMillis) {
        this.client = client;
        this.watchReconnectBaseDelayMillis = watchReconnectBaseDelayMillis;
        this.watchReconnectMaxDelayMillis = watchReconnectMaxDelayMillis;
    }

    /**
     * why: 运行时读路径应该只消费内存里的不可变快照；
     * 快照刷新由 Halo watch 事件和显式 refresh 触发，而不是请求线程自己回源拉整表。
     */
    public Flux<RuntimeTransformationRule> listActiveByMode(TransformationRule.Mode mode) {
        return Flux.fromIterable(cachedSnapshot.activeRules(mode));
    }

    /**
     * why: TransformationRule 自身就是 Halo extension 资源；让 Halo watch 驱动缓存刷新，
     * 比“请求命中 TTL 再自己回源”更贴近平台原生能力，也能更快反映控制台外部改动。
     * 同时保留自动重连和退避重试，避免把缓存一致性完全押在单条 watch 链路上。
     */
    public void startWatching() {
        synchronized (watchMonitor) {
            if (watching) {
                return;
            }
            watching = true;
            ensureWatchSupervisorLocked();
            cancelReconnectTaskLocked();
            cancelRefreshRetryTaskLocked();
            reconnectFailureCount = 0;
            refreshFailureCount = 0;
        }
        requestRefreshAsync();
        connectWatch("startup", false);
    }

    public void stopWatching() {
        RuleWatcher currentWatcher;
        ScheduledExecutorService currentSupervisor;
        synchronized (watchMonitor) {
            if (!watching && watcher == null && watchSupervisor == null) {
                return;
            }
            watching = false;
            cancelReconnectTaskLocked();
            cancelRefreshRetryTaskLocked();
            reconnectFailureCount = 0;
            refreshFailureCount = 0;
            currentWatcher = watcher;
            watcher = null;
            currentSupervisor = watchSupervisor;
            watchSupervisor = null;
        }
        if (currentWatcher != null) {
            currentWatcher.disposeFromManager();
        }
        if (currentSupervisor != null) {
            currentSupervisor.shutdownNow();
        }
    }

    /**
     * why: 控制台写成功后仍可主动触发一次 refresh，缩短 watch 事件传递到本地快照的传播时间；
     * 但真实权威触发源已经切到 Halo watch，而不是 TTL 过期。
     */
    public void invalidateAndWarmUpAsync() {
        requestRefreshAsync();
    }

    void requestRefreshAsync() {
        synchronized (refreshMonitor) {
            refreshRequested = true;
            if (refreshRunning) {
                return;
            }
            refreshRunning = true;
        }
        runRefreshLoopAsync();
    }

    Mono<RuleSnapshot> refreshSnapshot() {
        return client.list(TransformationRule.class, null, null)
            .collectList()
            .map(this::replaceSnapshot);
    }

    RuleSnapshot buildSnapshot(List<TransformationRule> rules) {
        Map<String, TransformationRule> rulesById = new LinkedHashMap<>();
        Map<TransformationRule.Mode, List<TransformationRule>> activeSourceRulesByMode =
            new EnumMap<>(TransformationRule.Mode.class);
        Map<String, SkippedEnabledRule> skippedEnabledRulesById = new LinkedHashMap<>();
        for (TransformationRule.Mode mode : TransformationRule.Mode.values()) {
            activeSourceRulesByMode.put(mode, new ArrayList<>());
        }
        for (TransformationRule rule : rules) {
            if (rule == null) {
                continue;
            }
            String ruleId = describeRuleId(rule);
            RuntimeSkipReason skipReason = resolveRuntimeSkipReason(rule);
            if (ruleId != null) {
                rulesById.put(ruleId, rule);
            }
            if (!rule.isEnabled()) {
                continue;
            }
            if (skipReason != null) {
                skippedEnabledRulesById.put(describeRule(rule), new SkippedEnabledRule(
                    describeRule(rule),
                    skipReason.code(),
                    skipReason.detail()
                ));
                continue;
            }
            activeSourceRulesByMode.get(rule.getMode()).add(rule);
        }
        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> immutableByMode =
            new EnumMap<>(TransformationRule.Mode.class);
        for (var entry : activeSourceRulesByMode.entrySet()) {
            entry.getValue().sort(runtimeOrderComparator());
            immutableByMode.put(entry.getKey(),
                entry.getValue().stream().map(this::toRuntimeRule).toList());
        }
        RuleSnapshot snapshot = new RuleSnapshot(
            new LinkedHashMap<>(rulesById),
            immutableByMode,
            new LinkedHashMap<>(skippedEnabledRulesById)
        );
        logSkippedEnabledRules(snapshot.skippedEnabledRules());
        return snapshot;
    }

    /**
     * why: 控制台左侧 `rule-order` 只是展示顺序；运行时只承诺同一执行阶段内按显式
     * `runtimeOrder` 升序执行；同值时先按规则名称字符序，再用稳定资源 id 兜底，
     * 避免继续依赖底层 list 返回顺序，同时让执行顺序更贴近用户看到的规则名称。
     */
    private Comparator<TransformationRule> runtimeOrderComparator() {
        return Comparator
            .comparingInt(TransformationRule::getRuntimeOrder)
            .thenComparing(TransformationRule::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TransformationRule::getId, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * why: watch 事件已经带着变更后的资源本体；直接在内存快照上做最小 upsert/remove，
     * 比每收到一次事件就重新 list 全表更贴近 watch-driven cache，也能减少不必要 IO。
     */
    private void applyRuleEventSnapshot(RuleEventType eventType, TransformationRule rule) {
        if (rule == null) {
            requestRefreshAsync();
            return;
        }
        String ruleId = describeRuleId(rule);
        if (ruleId == null) {
            log.debug(
                "Falling back to full TransformationRule refresh because watch event had no "
                    + "resource name");
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            cachedSnapshot = applyIncrementalSnapshotEvent(cachedSnapshot, eventType, ruleId, rule);
        }
    }

    private RuleSnapshot replaceSnapshot(List<TransformationRule> rules) {
        synchronized (snapshotMonitor) {
            RuleSnapshot snapshot = buildSnapshot(rules);
            cachedSnapshot = snapshot;
            return snapshot;
        }
    }

    private RuleSnapshot applyIncrementalSnapshotEvent(RuleSnapshot currentSnapshot,
        RuleEventType eventType,
        String ruleId, TransformationRule rule) {
        Map<String, TransformationRule> nextRulesById =
            new LinkedHashMap<>(currentSnapshot.rulesById());
        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> nextRulesByMode =
            copyRulesByMode(currentSnapshot.rulesByMode());
        Map<String, SkippedEnabledRule> nextSkippedEnabledRulesById =
            new LinkedHashMap<>(currentSnapshot.skippedEnabledRulesById());
        Set<TransformationRule.Mode> affectedModes = new LinkedHashSet<>();

        TransformationRule previousRule = nextRulesById.get(ruleId);
        TransformationRule.Mode previousActiveMode = activeMode(previousRule);
        if (previousActiveMode != null) {
            affectedModes.add(previousActiveMode);
        }
        nextSkippedEnabledRulesById.remove(ruleId);

        if (eventType == RuleEventType.DELETE) {
            nextRulesById.remove(ruleId);
        } else {
            nextRulesById.put(ruleId, rule);
            RuntimeSkipReason nextSkipReason = resolveRuntimeSkipReason(rule);
            if (rule.isEnabled() && nextSkipReason != null) {
                nextSkippedEnabledRulesById.put(ruleId, new SkippedEnabledRule(
                    ruleId,
                    nextSkipReason.code(),
                    nextSkipReason.detail()
                ));
            }
            TransformationRule.Mode nextActiveMode = activeMode(rule);
            if (nextActiveMode != null) {
                affectedModes.add(nextActiveMode);
            }
        }

        for (TransformationRule.Mode mode : affectedModes) {
            nextRulesByMode.put(mode, rebuildActiveRulesForMode(nextRulesById.values(), mode));
        }

        RuleSnapshot snapshot =
            new RuleSnapshot(nextRulesById, nextRulesByMode, nextSkippedEnabledRulesById);
        logSkippedEnabledRules(snapshot.skippedEnabledRules());
        return snapshot;
    }

    private Map<TransformationRule.Mode, List<RuntimeTransformationRule>> copyRulesByMode(
        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> sourceRulesByMode) {
        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> copied =
            new EnumMap<>(TransformationRule.Mode.class);
        for (TransformationRule.Mode mode : TransformationRule.Mode.values()) {
            copied.put(mode, new ArrayList<>(sourceRulesByMode.getOrDefault(mode, List.of())));
        }
        return copied;
    }

    private List<RuntimeTransformationRule> rebuildActiveRulesForMode(
        Iterable<TransformationRule> rules,
        TransformationRule.Mode mode) {
        List<TransformationRule> matchingRules = new ArrayList<>();
        for (TransformationRule rule : rules) {
            if (activeMode(rule) == mode) {
                matchingRules.add(rule);
            }
        }
        matchingRules.sort(runtimeOrderComparator());
        return matchingRules.stream()
            .map(this::toRuntimeRule)
            .toList();
    }

    /**
     * why: 运行时快照不必携带用户原始树里的冗余布尔结构；
     * 这里单独生成一份 runtime copy，把最小化后的 `matchRule` 放进内存快照，
     * 既避免请求期重复化简，也不污染持久化层和控制台编辑态。
     */
    private RuntimeTransformationRule toRuntimeRule(TransformationRule rule) {
        return RuntimeTransformationRule.fromStoredRule(
            rule,
            MatchRuleBooleanMinimizer.minimizeForRuntime(rule.getMatchRule())
        );
    }

    List<SkippedEnabledRule> skippedEnabledRules() {
        return lastSkippedEnabledRules;
    }

    private RuntimeSkipReason resolveRuntimeSkipReason(TransformationRule rule) {
        if (rule == null) {
            return RuntimeSkipReason.MISSING_RESOURCE_NAME;
        }
        if (describeRuleId(rule) == null) {
            return RuntimeSkipReason.MISSING_RESOURCE_NAME;
        }
        if (ExtensionUtil.isDeleted(rule)) {
            return RuntimeSkipReason.DELETING_RESOURCE;
        }
        if (rule.getMode() == null) {
            return RuntimeSkipReason.MISSING_MODE;
        }
        if (TransformationRule.Mode.SELECTOR.equals(rule.getMode())
            && (rule.getMatch() == null || rule.getMatch().isBlank())) {
            return RuntimeSkipReason.BLANK_SELECTOR_MATCH;
        }
        if (rule.getMatchRule() == null) {
            return RuntimeSkipReason.MISSING_MATCH_RULE;
        }
        if (!rule.getMatchRule().isValid()) {
            return RuntimeSkipReason.INVALID_MATCH_RULE;
        }
        return null;
    }

    private TransformationRule.Mode activeMode(TransformationRule rule) {
        if (rule == null || !rule.isEnabled()) {
            return null;
        }
        return resolveRuntimeSkipReason(rule) == null ? rule.getMode() : null;
    }

    private void logSkippedEnabledRules(List<SkippedEnabledRule> skippedEnabledRules) {
        List<SkippedEnabledRule> currentSkippedRules = List.copyOf(skippedEnabledRules);
        List<SkippedEnabledRule> previousSkippedRules = lastSkippedEnabledRules;
        if (previousSkippedRules.equals(currentSkippedRules)) {
            return;
        }
        lastSkippedEnabledRules = currentSkippedRules;
        if (currentSkippedRules.isEmpty()) {
            if (!previousSkippedRules.isEmpty()) {
                log.info(
                    "All previously skipped enabled TransformationRule resources are back in the "
                        + "runtime snapshot");
            }
            return;
        }
        log.warn(
            "Skipped {} enabled TransformationRule resource(s) from the runtime snapshot because "
                + "they are invalid or incomplete: {}",
            currentSkippedRules.size(),
            currentSkippedRules
        );
    }

    private String describeRule(TransformationRule rule) {
        String ruleId = describeRuleId(rule);
        return ruleId == null ? "<unnamed>" : ruleId;
    }

    private String describeRuleId(TransformationRule rule) {
        if (rule == null || rule.getMetadata() == null) {
            return null;
        }
        return rule.getMetadata().getName();
    }

    private void runRefreshLoopAsync() {
        synchronized (refreshMonitor) {
            refreshRequested = false;
        }
        refreshSnapshot()
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signalType -> {
                boolean rerun;
                synchronized (refreshMonitor) {
                    rerun = refreshRequested;
                    if (!rerun) {
                        refreshRunning = false;
                    }
                }
                if (rerun) {
                    runRefreshLoopAsync();
                }
            })
            .subscribe(
                snapshot -> {
                    int recoveredFailures = resetRefreshFailureCount();
                    if (recoveredFailures > 0) {
                        log.info(
                            "Recovered TransformationRule snapshot refresh after {} failed "
                                + "attempt(s)",
                            recoveredFailures);
                    }
                    log.debug("Refreshed rule snapshot with {} total rules",
                        snapshot.allRules().size());
                },
                error -> {
                    int failureCount = incrementRefreshFailureCount();
                    long delayMillis = computeBackoffDelayMillis(failureCount);
                    log.warn(
                        "Failed to refresh TransformationRule snapshot; retrying in {} ms "
                            + "(attempt {})",
                        delayMillis,
                        failureCount,
                        error
                    );
                    scheduleRefreshRetry(delayMillis);
                }
            );
    }

    private void connectWatch(String reason, boolean refreshOnConnect) {
        synchronized (watchMonitor) {
            if (!watching || (watcher != null && !watcher.isDisposed())) {
                return;
            }
        }

        RuleWatcher nextWatcher = new RuleWatcher();
        try {
            client.watch(nextWatcher);
        } catch (RuntimeException error) {
            scheduleWatchReconnect(reason, error);
            return;
        }

        int recoveredFailures;
        synchronized (watchMonitor) {
            if (!watching) {
                nextWatcher.disposeFromManager();
                return;
            }
            watcher = nextWatcher;
            cancelReconnectTaskLocked();
            recoveredFailures = reconnectFailureCount;
            reconnectFailureCount = 0;
        }
        if (recoveredFailures > 0) {
            log.info("Recovered TransformationRule watch after {} failed reconnect attempt(s)",
                recoveredFailures);
        } else {
            log.info("Started TransformationRule watch");
        }
        if (refreshOnConnect) {
            requestRefreshAsync();
        }
    }

    private void scheduleWatchReconnect(String reason, RuntimeException error) {
        int failureCount;
        long delayMillis;
        synchronized (watchMonitor) {
            if (!watching) {
                return;
            }
            failureCount = ++reconnectFailureCount;
            delayMillis = computeBackoffDelayMillis(failureCount);
        }
        log.warn(
            "Failed to connect TransformationRule watch during {}; retrying in {} ms (attempt {})",
            reason,
            delayMillis,
            failureCount,
            error
        );
        scheduleReconnectTask(delayMillis);
    }

    private void handleUnexpectedWatchDispose(RuleWatcher disconnectedWatcher) {
        int failureCount;
        long delayMillis;
        synchronized (watchMonitor) {
            if (watcher != disconnectedWatcher) {
                return;
            }
            watcher = null;
            if (!watching || disconnectedWatcher.wasDisposedByManager()) {
                return;
            }
            failureCount = ++reconnectFailureCount;
            delayMillis = computeBackoffDelayMillis(failureCount);
        }
        log.warn("TransformationRule watch disconnected; retrying in {} ms (attempt {})",
            delayMillis, failureCount);
        scheduleReconnectTask(delayMillis);
    }

    private void scheduleReconnectTask(long delayMillis) {
        synchronized (watchMonitor) {
            if (!watching) {
                return;
            }
            if (reconnectTask != null && !reconnectTask.isDone()) {
                return;
            }
            ScheduledExecutorService supervisor = ensureWatchSupervisorLocked();
            reconnectTask = supervisor.schedule(() -> {
                synchronized (watchMonitor) {
                    reconnectTask = null;
                }
                connectWatch("reconnect", true);
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleRefreshRetry(long delayMillis) {
        synchronized (watchMonitor) {
            if (!watching) {
                return;
            }
            if (refreshRetryTask != null && !refreshRetryTask.isDone()) {
                return;
            }
            ScheduledExecutorService supervisor = ensureWatchSupervisorLocked();
            refreshRetryTask = supervisor.schedule(() -> {
                synchronized (watchMonitor) {
                    refreshRetryTask = null;
                }
                requestRefreshAsync();
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private int incrementRefreshFailureCount() {
        synchronized (watchMonitor) {
            return ++refreshFailureCount;
        }
    }

    private int resetRefreshFailureCount() {
        synchronized (watchMonitor) {
            cancelRefreshRetryTaskLocked();
            int failureCount = refreshFailureCount;
            refreshFailureCount = 0;
            return failureCount;
        }
    }

    private long computeBackoffDelayMillis(int failureCount) {
        long delayMillis = watchReconnectBaseDelayMillis;
        for (int attempt = 1; attempt < failureCount; attempt++) {
            if (delayMillis >= watchReconnectMaxDelayMillis) {
                return watchReconnectMaxDelayMillis;
            }
            delayMillis = Math.min(delayMillis * 2, watchReconnectMaxDelayMillis);
        }
        return delayMillis;
    }

    private ScheduledExecutorService ensureWatchSupervisorLocked() {
        if (watchSupervisor != null && !watchSupervisor.isShutdown()) {
            return watchSupervisor;
        }
        watchSupervisor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "transformation-rule-watch-supervisor");
            thread.setDaemon(true);
            return thread;
        });
        return watchSupervisor;
    }

    private void cancelReconnectTaskLocked() {
        if (reconnectTask == null) {
            return;
        }
        reconnectTask.cancel(false);
        reconnectTask = null;
    }

    private void cancelRefreshRetryTaskLocked() {
        if (refreshRetryTask == null) {
            return;
        }
        refreshRetryTask.cancel(false);
        refreshRetryTask = null;
    }

    private enum RuleEventType {
        ADD,
        UPDATE,
        DELETE
    }

    private enum RuntimeSkipReason {
        DELETING_RESOURCE("deleting_resource", "资源已进入 deleting 生命周期，不应继续参与运行时执行"),
        MISSING_RESOURCE_NAME("missing_resource_name", "缺少 metadata.name，无法建立稳定运行时主键"),
        MISSING_MODE("missing_mode", "运行时需要明确的执行阶段"),
        BLANK_SELECTOR_MATCH("blank_selector_match", "CSS 选择器模式要求非空 match"),
        MISSING_MATCH_RULE("missing_match_rule", "缺少 matchRule"),
        INVALID_MATCH_RULE("invalid_match_rule", "matchRule 不满足运行时结构约束");

        private final String code;
        private final String detail;

        RuntimeSkipReason(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        String code() {
            return code;
        }

        String detail() {
            return detail;
        }
    }

    record RuleSnapshot(Map<String, TransformationRule> rulesById,
                        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> rulesByMode,
                        Map<String, SkippedEnabledRule> skippedEnabledRulesById) {
        static RuleSnapshot empty() {
            Map<TransformationRule.Mode, List<RuntimeTransformationRule>> emptyByMode =
                new EnumMap<>(TransformationRule.Mode.class);
            for (TransformationRule.Mode mode : TransformationRule.Mode.values()) {
                emptyByMode.put(mode, List.of());
            }
            return new RuleSnapshot(new LinkedHashMap<>(), emptyByMode, new LinkedHashMap<>());
        }

        List<TransformationRule> allRules() {
            return List.copyOf(rulesById.values());
        }

        List<RuntimeTransformationRule> activeRules(TransformationRule.Mode mode) {
            return rulesByMode.getOrDefault(mode, List.of());
        }

        List<SkippedEnabledRule> skippedEnabledRules() {
            return List.copyOf(skippedEnabledRulesById.values());
        }
    }

    record SkippedEnabledRule(String ruleId, String reasonCode, String reasonDetail) {
    }

    private final class RuleWatcher implements Watcher {
        private volatile boolean disposed;
        private volatile boolean disposedByManager;
        private Runnable disposeHook;

        @Override
        public void onAdd(Extension extension) {
            handleRuleEvent(RuleEventType.ADD, extension);
        }

        @Override
        public void onUpdate(Extension oldExtension, Extension newExtension) {
            handleRuleEvent(RuleEventType.UPDATE, newExtension);
        }

        @Override
        public void onDelete(Extension extension) {
            handleRuleEvent(RuleEventType.DELETE, extension);
        }

        @Override
        public void registerDisposeHook(Runnable dispose) {
            this.disposeHook = dispose;
        }

        @Override
        public void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            if (disposeHook != null) {
                disposeHook.run();
            }
            handleUnexpectedWatchDispose(this);
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }

        void disposeFromManager() {
            disposedByManager = true;
            dispose();
        }

        boolean wasDisposedByManager() {
            return disposedByManager;
        }

        private void handleRuleEvent(RuleEventType eventType, Extension extension) {
            if (disposed || extension == null) {
                return;
            }
            if (extension instanceof TransformationRule rule) {
                applyRuleEventSnapshot(eventType, rule);
                return;
            }
            if (!RULE_GVK.equals(extension.groupVersionKind())) {
                return;
            }
            requestRefreshAsync();
        }
    }
}
