package top.howiehz.halo.transformer.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.core.MatchRuleBooleanMinimizer;
import top.howiehz.halo.transformer.core.RuntimeTransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.util.TransformationSnippetReferenceIds;

@Slf4j
@Component
public class TransformationRuleRuntimeStore extends AbstractWatchDrivenExtensionStore<
    TransformationRule, TransformationRuleRuntimeStore.RuleSnapshot> {
    private static final GroupVersionKind RULE_GVK =
        GroupVersionKind.fromExtension(TransformationRule.class);
    private static final long WATCH_RECONNECT_BASE_DELAY_MILLIS = 1_000L;
    private static final long WATCH_RECONNECT_MAX_DELAY_MILLIS = 30_000L;

    private final Object snapshotMonitor = new Object();
    private volatile RuleSnapshot cachedSnapshot = RuleSnapshot.empty();
    private volatile List<SkippedEnabledRule> lastSkippedEnabledRules = List.of();

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
        super(
            client,
            TransformationRule.class,
            RULE_GVK,
            "TransformationRule",
            "rules",
            "transformation-rule-watch-supervisor",
            watchReconnectBaseDelayMillis,
            watchReconnectMaxDelayMillis
        );
    }

    /**
     * why: 运行时读路径应该只消费内存里的不可变快照；
     * 快照刷新由 Halo watch 事件和显式 refresh 触发，而不是请求线程自己回源拉整表。
     */
    public Flux<RuntimeTransformationRule> listActiveByMode(TransformationRule.Mode mode) {
        return Flux.fromIterable(cachedSnapshot.activeRules(mode));
    }

    /**
     * why: 控制台左侧规则列表与排序写口也必须建立在同一份 watch-driven 真源上；
     * 这里暴露“当前控制台可见规则”快照，避免 endpoint 在请求路径再次回源 list 全表。
     */
    public List<TransformationRule> listVisibleRules() {
        return cachedSnapshot.visibleRules();
    }

    /**
     * why: snippet 删除协调器只需要“当前哪些可见规则还引用了它”这一条查询语义；
     * 把这层过滤也收口进 watch-driven store 后，后台写路径和控制台读路径才能共享同一份规则真源。
     */
    public List<String> listVisibleRuleNamesReferencingSnippet(String snippetId) {
        String normalizedSnippetId = TransformationSnippetReferenceIds.normalizeSingle(snippetId);
        if (normalizedSnippetId == null) {
            return List.of();
        }
        return cachedSnapshot.visibleRuleNamesReferencingSnippet(normalizedSnippetId);
    }

    /**
     * why: 删除协调器只有在首轮整表 warm-up 完成后，才能把这份 watch-driven 快照当成
     * “规则引用关系”的权威来源；否则空快照只代表“还没装载完”，不是“确实没有引用”。
     */
    public boolean isReadyForReferenceReads() {
        return hasCompletedInitialSnapshotRefresh();
    }

    /**
     * why: 控制台写口在成功持久化后，必须立刻把最新已保存规则回灌进可见快照；
     * watch 负责自愈与跨实例同步，但不应让当前写请求后的下一次读取继续看到旧规则集。
     */
    public void applyPersistedRule(TransformationRule rule) {
        if (rule == null) {
            requestRefreshAsync();
            return;
        }
        String ruleId = describeRuleId(rule);
        if (ruleId == null) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            cachedSnapshot = applyIncrementalSnapshotEvent(cachedSnapshot, WatchEventType.UPDATE,
                ruleId, rule);
        }
    }

    /**
     * why: 规则删除成功后，控制台列表与排序保存都应立即以“已移除”视图继续推进；
     * 否则前端紧接着刷新快照时，仍可能在 watch 自愈前短暂看到已删规则。
     */
    public void removeRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            cachedSnapshot = applyIncrementalSnapshotEvent(cachedSnapshot, WatchEventType.DELETE,
                ruleId, null);
        }
    }

    @Override
    protected Mono<RuleSnapshot> refreshSnapshot() {
        return client().list(TransformationRule.class, null, null)
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
        List<TransformationRule> visibleRules = rulesById.values().stream()
            .filter(this::isConsoleVisibleRule)
            .toList();
        RuleSnapshot snapshot = new RuleSnapshot(
            new LinkedHashMap<>(rulesById),
            visibleRules,
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
    @Override
    protected void applyWatchEvent(WatchEventType eventType, TransformationRule rule) {
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

    @Override
    protected int snapshotSize(RuleSnapshot snapshot) {
        return snapshot.allRules().size();
    }

    private RuleSnapshot replaceSnapshot(List<TransformationRule> rules) {
        synchronized (snapshotMonitor) {
            RuleSnapshot snapshot = buildSnapshot(rules);
            cachedSnapshot = snapshot;
            return snapshot;
        }
    }

    private RuleSnapshot applyIncrementalSnapshotEvent(RuleSnapshot currentSnapshot,
        WatchEventType eventType, String ruleId, TransformationRule rule) {
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

        if (eventType == WatchEventType.DELETE) {
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
        RuleSnapshot snapshot = new RuleSnapshot(
            nextRulesById,
            visibleRules(nextRulesById.values()),
            nextRulesByMode,
            nextSkippedEnabledRulesById
        );
        logSkippedEnabledRules(snapshot.skippedEnabledRules());
        return snapshot;
    }

    private List<TransformationRule> visibleRules(Iterable<TransformationRule> rules) {
        List<TransformationRule> visibleRules = new ArrayList<>();
        for (TransformationRule rule : rules) {
            if (isConsoleVisibleRule(rule)) {
                visibleRules.add(rule);
            }
        }
        return List.copyOf(visibleRules);
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

    private boolean isConsoleVisibleRule(TransformationRule rule) {
        return describeRuleId(rule) != null && !ExtensionUtil.isDeleted(rule);
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

    private enum RuntimeSkipReason {
        DELETING_RESOURCE("deleting_resource", "资源已进入“删除中”生命周期，不应继续参与运行时执行"),
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
                        List<TransformationRule> visibleRules,
                        Map<TransformationRule.Mode, List<RuntimeTransformationRule>> rulesByMode,
                        Map<String, SkippedEnabledRule> skippedEnabledRulesById) {
        static RuleSnapshot empty() {
            Map<TransformationRule.Mode, List<RuntimeTransformationRule>> emptyByMode =
                new EnumMap<>(TransformationRule.Mode.class);
            for (TransformationRule.Mode mode : TransformationRule.Mode.values()) {
                emptyByMode.put(mode, List.of());
            }
            return new RuleSnapshot(new LinkedHashMap<>(), List.of(), emptyByMode,
                new LinkedHashMap<>());
        }

        List<TransformationRule> allRules() {
            return List.copyOf(rulesById.values());
        }

        List<RuntimeTransformationRule> activeRules(TransformationRule.Mode mode) {
            return rulesByMode.getOrDefault(mode, List.of());
        }

        List<String> visibleRuleNamesReferencingSnippet(String snippetId) {
            List<String> ruleNames = new ArrayList<>();
            for (TransformationRule rule : visibleRules) {
                if (TransformationSnippetReferenceIds.normalize(rule.getSnippetIds())
                    .contains(snippetId)) {
                    ruleNames.add(rule.getMetadata().getName());
                }
            }
            return List.copyOf(ruleNames);
        }

        List<SkippedEnabledRule> skippedEnabledRules() {
            return List.copyOf(skippedEnabledRulesById.values());
        }
    }

    record SkippedEnabledRule(String ruleId, String reasonCode, String reasonDetail) {
    }
}
