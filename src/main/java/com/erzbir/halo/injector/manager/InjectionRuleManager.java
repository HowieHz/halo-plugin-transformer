package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.scheme.InjectionRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class InjectionRuleManager {
    private static final GroupVersionKind RULE_GVK = GroupVersionKind.fromExtension(InjectionRule.class);
    private static final long WATCH_RECONNECT_BASE_DELAY_MILLIS = 1_000L;
    private static final long WATCH_RECONNECT_MAX_DELAY_MILLIS = 30_000L;

    private final ReactiveExtensionClient client;
    private final Object refreshMonitor = new Object();
    private final Object watchMonitor = new Object();
    private final long watchReconnectBaseDelayMillis;
    private final long watchReconnectMaxDelayMillis;
    private volatile RuleSnapshot cachedSnapshot = RuleSnapshot.empty();
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
    public InjectionRuleManager(ReactiveExtensionClient client) {
        this(client, WATCH_RECONNECT_BASE_DELAY_MILLIS, WATCH_RECONNECT_MAX_DELAY_MILLIS);
    }

    /**
     * why: 测试需要把退避窗口缩短到毫秒级，才能稳定覆盖重连与 refresh retry 语义；
     * 因此保留一个包级构造器注入测试参数，但不暴露给 Spring 作为候选主构造器。
     */
    InjectionRuleManager(ReactiveExtensionClient client, long watchReconnectBaseDelayMillis,
                         long watchReconnectMaxDelayMillis) {
        this.client = client;
        this.watchReconnectBaseDelayMillis = watchReconnectBaseDelayMillis;
        this.watchReconnectMaxDelayMillis = watchReconnectMaxDelayMillis;
    }

    /**
     * why: 运行时读路径应该只消费内存里的不可变快照；
     * 快照刷新由 Halo watch 事件和显式 refresh 触发，而不是请求线程自己回源拉整表。
     */
    public Flux<InjectionRule> listActiveByMode(InjectionRule.Mode mode) {
        return Flux.fromIterable(cachedSnapshot.activeRules(mode));
    }

    /**
     * why: InjectionRule 自身就是 Halo extension 资源；让 Halo watch 驱动缓存刷新，
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
        connectWatch("startup");
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
        return client.list(InjectionRule.class, null, null)
                .collectList()
                .map(this::buildSnapshot)
                .doOnNext(snapshot -> cachedSnapshot = snapshot);
    }

    RuleSnapshot buildSnapshot(List<InjectionRule> rules) {
        List<InjectionRule> allRules = List.copyOf(rules);
        Map<InjectionRule.Mode, List<InjectionRule>> rulesByMode =
                new EnumMap<>(InjectionRule.Mode.class);
        for (InjectionRule.Mode mode : InjectionRule.Mode.values()) {
            rulesByMode.put(mode, new ArrayList<>());
        }
        for (InjectionRule rule : allRules) {
            if (rule == null || rule.getMode() == null || !rule.isEnabled() || !rule.isValid()) {
                continue;
            }
            rulesByMode.get(rule.getMode()).add(rule);
        }
        Map<InjectionRule.Mode, List<InjectionRule>> immutableByMode =
                new EnumMap<>(InjectionRule.Mode.class);
        for (var entry : rulesByMode.entrySet()) {
            entry.getValue().sort(runtimeOrderComparator());
            immutableByMode.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new RuleSnapshot(allRules, immutableByMode);
    }

    /**
     * why: 控制台左侧 `rule-order` 只是展示顺序；运行时只承诺同一执行阶段内按显式
     * `runtimeOrder` 升序执行；同值时先按规则名称字符序，再用稳定资源 id 兜底，
     * 避免继续依赖底层 list 返回顺序，同时让执行顺序更贴近用户看到的规则名称。
     */
    private Comparator<InjectionRule> runtimeOrderComparator() {
        return Comparator
                .comparingInt(InjectionRule::getRuntimeOrder)
                .thenComparing(InjectionRule::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(InjectionRule::getId, String.CASE_INSENSITIVE_ORDER);
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
                                log.info("Recovered InjectionRule snapshot refresh after {} failed attempt(s)",
                                        recoveredFailures);
                            }
                            log.debug("Refreshed rule snapshot with {} total rules", snapshot.allRules().size());
                        },
                        error -> {
                            int failureCount = incrementRefreshFailureCount();
                            long delayMillis = computeBackoffDelayMillis(failureCount);
                            log.warn(
                                    "Failed to refresh InjectionRule snapshot; retrying in {} ms (attempt {})",
                                    delayMillis,
                                    failureCount,
                                    error
                            );
                            scheduleRefreshRetry(delayMillis);
                        }
                );
    }

    private void connectWatch(String reason) {
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
            log.info("Recovered InjectionRule watch after {} failed reconnect attempt(s)", recoveredFailures);
        } else {
            log.info("Started InjectionRule watch");
        }
        requestRefreshAsync();
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
                "Failed to connect InjectionRule watch during {}; retrying in {} ms (attempt {})",
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
        log.warn("InjectionRule watch disconnected; retrying in {} ms (attempt {})", delayMillis, failureCount);
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
                connectWatch("reconnect");
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
            Thread thread = new Thread(runnable, "injection-rule-watch-supervisor");
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

    record RuleSnapshot(List<InjectionRule> allRules, Map<InjectionRule.Mode, List<InjectionRule>> rulesByMode) {
        static RuleSnapshot empty() {
            Map<InjectionRule.Mode, List<InjectionRule>> emptyByMode =
                    new EnumMap<>(InjectionRule.Mode.class);
            for (InjectionRule.Mode mode : InjectionRule.Mode.values()) {
                emptyByMode.put(mode, List.of());
            }
            return new RuleSnapshot(List.of(), emptyByMode);
        }

        List<InjectionRule> activeRules(InjectionRule.Mode mode) {
            return rulesByMode.getOrDefault(mode, List.of());
        }
    }

    private final class RuleWatcher implements Watcher {
        private volatile boolean disposed;
        private volatile boolean disposedByManager;
        private Runnable disposeHook;

        @Override
        public void onAdd(Extension extension) {
            handleRuleEvent(extension);
        }

        @Override
        public void onUpdate(Extension oldExtension, Extension newExtension) {
            handleRuleEvent(newExtension);
        }

        @Override
        public void onDelete(Extension extension) {
            handleRuleEvent(extension);
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

        private void handleRuleEvent(Extension extension) {
            if (disposed || extension == null || !RULE_GVK.equals(extension.groupVersionKind())) {
                return;
            }
            requestRefreshAsync();
        }
    }
}
