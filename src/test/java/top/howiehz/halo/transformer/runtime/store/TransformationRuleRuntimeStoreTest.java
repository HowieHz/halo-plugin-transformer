package top.howiehz.halo.transformer.runtime.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.rule.MatchRule;
import top.howiehz.halo.transformer.runtime.RuntimeTransformationRule;

@ExtendWith(MockitoExtension.class)
class TransformationRuleRuntimeStoreTest {
    @Mock
    private ReactiveExtensionClient client;

    private TransformationRuleRuntimeStore manager;

    @BeforeEach
    void setUp() {
        manager = new TransformationRuleRuntimeStore(client, 10, 40);
    }

    @AfterEach
    void tearDown() {
        manager.stopWatching();
    }

    @Test
    void shouldOnlyMarkReferenceReadsReadyAfterInitialSnapshotRefreshSucceeds() {
        TransformationRule persistedRule =
            rule("rule-a", TransformationRule.Mode.FOOTER, false, "");
        when(client.list(TransformationRule.class, null, null))
            .thenReturn(Flux.just(persistedRule));

        assertFalse(manager.isReadyForReferenceReads());

        manager.applyPersistedRule(persistedRule);
        assertFalse(manager.isReadyForReferenceReads());

        manager.startWatching();
        waitUntil(manager::isReadyForReferenceReads);
        assertTrue(manager.isReadyForReferenceReads());

        manager.stopWatching();
        assertFalse(manager.isReadyForReferenceReads());
    }

    // why: watch-driven cache 启动后应先主动回源装载一次快照；
    // 否则运行时第一批请求会看到空规则集，而不是当前已保存状态。
    @Test
    void shouldLoadSnapshotWhenStartingWatch() {
        when(client.list(TransformationRule.class, null, null))
            .thenReturn(Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main")));

        manager.startWatching();

        waitUntil(() -> !manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .isEmpty());
        List<RuntimeTransformationRule> rules =
            manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of("rule-a"),
            rules.stream().map(RuntimeTransformationRule::resourceName).toList());
        verify(client).watch(any(Watcher.class));
    }

    // why: 首轮 warm-up 是运行时缓存的独立职责，不能被 watch 握手是否成功绑死；
    // 即使启动期 watch 暂时连不上，也必须先把已保存快照装进内存，避免请求先看到空规则集。
    @Test
    void shouldWarmUpSnapshotEvenWhenInitialWatchConnectionFails() {
        when(client.list(TransformationRule.class, null, null))
            .thenReturn(Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main")));
        doThrow(new IllegalStateException("watch unavailable"))
            .when(client).watch(any(Watcher.class));

        manager.startWatching();

        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::resourceName)
            .toList()
            .equals(List.of("rule-a")));
    }

    // why: 当前快照应由 Halo watch 事件直接增量更新；
    // 规则更新后不需要再整表 list，自身事件就应把新快照推到运行时读路径。
    @Test
    void shouldApplyWatchEventsWithoutFullReload() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                ? Flux.just(
                namedRule("rule-a", "Before", TransformationRule.Mode.SELECTOR, true, ".before"))
                : Flux.just(
                    namedRule("rule-a", "After", TransformationRule.Mode.SELECTOR, true, ".after"));
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());

        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::match)
            .toList()
            .equals(List.of(".before")));

        watcherCaptor.getValue().onUpdate(
            namedRule("rule-a", "Before", TransformationRule.Mode.SELECTOR, true, ".before"),
            namedRule("rule-a", "After", TransformationRule.Mode.SELECTOR, true, ".after")
        );

        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::match)
            .toList()
            .equals(List.of(".after")));

        assertEquals(1, fetchCount.get());
    }

    // why: enabled 但暂时非法的规则不应污染运行时快照；
    // 一旦同名规则被后续 watch 事件修正，缓存也应在不整表重载的前提下立刻恢复。
    @Test
    void shouldRecoverSkippedEnabledRuleFromSubsequentWatchUpdate() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            return Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, ""));
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());

        waitUntil(() -> manager.skippedEnabledRules().equals(
            List.of(new TransformationRuleRuntimeStore.SkippedEnabledRule(
                "rule-a",
                "blank_selector_match",
                "CSS 选择器模式要求非空 match"
            )))
            && manager.listVisibleRules().stream()
            .map(rule -> rule.getMetadata().getName())
            .toList()
            .equals(List.of("rule-a"))
            && manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .isEmpty());

        watcherCaptor.getValue().onUpdate(
            rule("rule-a", TransformationRule.Mode.SELECTOR, true, ""),
            rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main")
        );

        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::resourceName)
            .toList()
            .equals(List.of("rule-a")));

        assertEquals(1, fetchCount.get());
    }

    // why: 运行时若跳过了一条已启用规则，诊断里必须带上稳定的原因码与说明；
    // 否则控制台外部写入的坏数据只会表现成“悄悄不生效”，排查成本太高。
    @Test
    void shouldExposeReasonedDiagnosticsForSkippedEnabledRules() {
        TransformationRule selectorRule =
            rule("rule-a", TransformationRule.Mode.SELECTOR, true, "");

        manager.buildSnapshot(List.of(selectorRule));

        assertEquals(
            List.of(new TransformationRuleRuntimeStore.SkippedEnabledRule(
                "rule-a",
                "blank_selector_match",
                "CSS 选择器模式要求非空 match"
            )),
            manager.skippedEnabledRules()
        );
    }

    // why: 控制台、接口端和排序层都已经把“删除中”的资源视为不可见；
    // 运行时快照也必须沿用同一条生命周期语义，避免“正在删除的规则”继续执行。
    @Test
    void shouldSkipDeletingRulesFromRuntimeSnapshot() {
        TransformationRule deletingRule =
            rule("rule-a", TransformationRule.Mode.SELECTOR, true, ".main");
        deletingRule.getMetadata().setDeletionTimestamp(Instant.now());

        TransformationRuleRuntimeStore.RuleSnapshot snapshot =
            manager.buildSnapshot(List.of(deletingRule));

        assertTrue(snapshot.activeRules(TransformationRule.Mode.SELECTOR).isEmpty());
        assertEquals(
            List.of(new TransformationRuleRuntimeStore.SkippedEnabledRule(
                "rule-a",
                "deleting_resource",
                "资源已进入“删除中”生命周期，不应继续参与运行时执行"
            )),
            manager.skippedEnabledRules()
        );
    }

    // why: 控制台列表与排序保存和运行时执行应共享同一份 watch-driven 规则真源；
    // 可见规则快照必须自动排除 deleting 资源，避免控制台请求路径再自己扫表过滤一次。
    @Test
    void shouldExposeVisibleRulesForConsoleFromWatchDrivenSnapshot() {
        TransformationRule visibleRule =
            rule("rule-a", TransformationRule.Mode.FOOTER, false, "");
        TransformationRule deletingRule =
            rule("rule-b", TransformationRule.Mode.FOOTER, false, "");
        deletingRule.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.list(TransformationRule.class, null, null))
            .thenReturn(Flux.just(visibleRule, deletingRule));

        manager.startWatching();

        waitUntil(() -> manager.listVisibleRules().stream()
            .map(rule -> rule.getMetadata().getName())
            .toList()
            .equals(List.of("rule-a")));
    }

    // why: snippet 删除协调器需要的是“当前哪些可见规则引用了它”，
    // 这条查询语义必须和控制台可见性、历史脏引用规范化共用同一份 watch-driven 快照。
    @Test
    void shouldExposeVisibleRuleNamesReferencingSnippetFromSnapshot() {
        TransformationRule matchingRule =
            rule("rule-a", TransformationRule.Mode.FOOTER, false, "");
        matchingRule.setSnippetIds(new java.util.LinkedHashSet<>(List.of(" snippet-a ")));
        TransformationRule unrelatedRule =
            rule("rule-b", TransformationRule.Mode.FOOTER, false, "");
        unrelatedRule.setSnippetIds(new java.util.LinkedHashSet<>(List.of("snippet-b")));
        TransformationRule deletingRule =
            rule("rule-c", TransformationRule.Mode.FOOTER, false, "");
        deletingRule.setSnippetIds(new java.util.LinkedHashSet<>(List.of("snippet-a")));
        deletingRule.getMetadata().setDeletionTimestamp(Instant.now());

        manager.applyPersistedRule(matchingRule);
        manager.applyPersistedRule(unrelatedRule);
        manager.applyPersistedRule(deletingRule);

        assertEquals(
            List.of("rule-a"),
            manager.listVisibleRuleNamesReferencingSnippet("snippet-a")
        );
    }

    // why: 控制台读取在当前写请求之后必须立刻看到新规则；
    // 否则“创建成功后立刻刷新并保存顺序”会先读到旧快照，再把新规则静默排除在排序映射之外。
    @Test
    void shouldExposePersistedRuleImmediatelyBeforeAsyncWarmUpCompletes() {
        TransformationRule persistedRule =
            rule("rule-a", TransformationRule.Mode.FOOTER, false, "");

        manager.applyPersistedRule(persistedRule);

        assertEquals(List.of("rule-a"),
            manager.listVisibleRules().stream()
                .map(rule -> rule.getMetadata().getName())
                .toList());
    }

    // why: 规则删除成功后，控制台快照也必须同步摘除；
    // 不能等后台 watch 自愈后才反映，否则删除后紧接着的刷新/排序保存仍会带着旧规则。
    @Test
    void shouldRemoveRuleImmediatelyFromVisibleSnapshot() {
        TransformationRule persistedRule =
            rule("rule-a", TransformationRule.Mode.FOOTER, false, "");

        manager.applyPersistedRule(persistedRule);
        manager.removeRule("rule-a");

        assertTrue(manager.listVisibleRules().isEmpty());
    }

    // why: watch 不是永不掉线的“神链路”；一旦底层 watch 被释放，管理器应自动按退避策略重连，
    // 而不是让运行时快照永久停留在一条已经失效的订阅上。
    @Test
    void shouldReconnectWatchAfterUnexpectedDispose() {
        when(client.list(TransformationRule.class, null, null))
            .thenReturn(Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main")));
        AtomicInteger watchCount = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            watchCount.incrementAndGet();
            return null;
        }).when(client).watch(any(Watcher.class));

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());

        Watcher initialWatcher = watcherCaptor.getValue();
        initialWatcher.dispose();

        waitUntil(() -> watchCount.get() >= 2);
    }

    // why: 删除协调器依赖的“规则引用关系快照”只在当前 live watch 仍托底时才可信；
    // watch 意外断开后，即使历史上 warm-up 过，也必须重新等待重连后的新快照落地。
    @Test
    void shouldResetReferenceReadinessAfterUnexpectedDisposeUntilReconnectRefreshCompletes() {
        AtomicInteger listCount = new AtomicInteger();
        AtomicReference<reactor.core.publisher.FluxSink<TransformationRule>> reconnectRefreshSink =
            new AtomicReference<>();
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            int round = listCount.incrementAndGet();
            return round == 1
                ? Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main"))
                : Flux.create(reconnectRefreshSink::set);
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());
        waitUntil(manager::isReadyForReferenceReads);

        watcherCaptor.getValue().dispose();

        waitUntil(() -> listCount.get() >= 2);
        assertFalse(manager.isReadyForReferenceReads());

        waitUntil(() -> reconnectRefreshSink.get() != null);
        reconnectRefreshSink.get().next(
            rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main"));
        reconnectRefreshSink.get().complete();

        waitUntil(manager::isReadyForReferenceReads);
    }

    // why: 自愈不能只靠 watch 事件；如果启动时那次全量加载短暂失败，又迟迟没有后续事件，
    // 管理器也应按退避策略自行补一次刷新，把缓存从空状态拉回已保存快照。
    @Test
    void shouldRetrySnapshotRefreshAfterInitialFailure() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                ? Flux.error(new IllegalStateException("temporary failure"))
                : Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main"));
        });

        manager.startWatching();

        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::resourceName)
            .toList()
            .equals(List.of("rule-a")));

        assertTrue(fetchCount.get() >= 2);
    }

    // why: refresh 期间如果已经收到更晚的 watch 事件，这次整表结果就只能算旧视图；
    // 它必须被丢弃并再刷一次，不能把当前快照回滚到更早状态。
    @Test
    void shouldDiscardStaleRefreshResultThatStartedBeforeNewerWatchEvent() {
        AtomicInteger listCount = new AtomicInteger();
        AtomicReference<reactor.core.publisher.FluxSink<TransformationRule>> firstRefreshSink =
            new AtomicReference<>();
        TransformationRule staleRule =
            namedRule("rule-a", "Before", TransformationRule.Mode.SELECTOR, true, ".before");
        TransformationRule latestRule =
            namedRule("rule-a", "After", TransformationRule.Mode.SELECTOR, true, ".after");
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            int round = listCount.incrementAndGet();
            return round == 1 ? Flux.create(firstRefreshSink::set) : Flux.just(latestRule);
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());

        watcherCaptor.getValue().onAdd(latestRule);
        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::match)
            .toList()
            .equals(List.of(".after")));

        waitUntil(() -> firstRefreshSink.get() != null);
        firstRefreshSink.get().next(staleRule);
        firstRefreshSink.get().complete();

        waitUntil(() -> listCount.get() >= 2);
        waitUntil(() -> manager.listActiveByMode(TransformationRule.Mode.SELECTOR)
            .collectList()
            .block()
            .stream()
            .map(RuntimeTransformationRule::match)
            .toList()
            .equals(List.of(".after")));
    }

    // why: 写接口成功后仍会主动请求一次 refresh；即使多个 refresh 紧挨着到来，
    // 管理器也应把它们合并成有限次整表重建，而不是每次调用都并发起一个新的 list。
    @Test
    void shouldCoalesceOverlappingRefreshRequests() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(TransformationRule.class, null, null)).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Flux.just(rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main"));
        });

        manager.invalidateAndWarmUpAsync();
        manager.invalidateAndWarmUpAsync();
        manager.invalidateAndWarmUpAsync();

        waitUntil(() -> fetchCount.get() >= 1);
        assertTrue(fetchCount.get() <= 2);
    }

    // why: 停止 watch 后，管理器不应再继续持有活动 watcher；
    // 否则插件 stop 后仍可能收到平台事件，破坏生命周期边界。
    @Test
    void shouldStopWatchingIdempotently() {
        when(client.list(TransformationRule.class, null, null)).thenReturn(Flux.empty());

        manager.startWatching();
        manager.stopWatching();
        manager.stopWatching();

        assertFalse(
            manager.listActiveByMode(TransformationRule.Mode.SELECTOR).hasElements().block());
    }

    // why: 运行时顺序仍是同一执行阶段内的显式契约；
    // watch-driven cache 只改变刷新方式，不能改变 runtimeOrder 排序语义。
    @Test
    void shouldSortActiveRulesByRuntimeOrderThenNameThenIdWithinMode() {
        TransformationRule highZ = rule("rule-z", TransformationRule.Mode.SELECTOR, true, "main");
        highZ.setName("Zulu");
        highZ.setRuntimeOrder(0);
        TransformationRule highA = rule("rule-a", TransformationRule.Mode.SELECTOR, true, "main");
        highA.setName("Alpha");
        highA.setRuntimeOrder(0);
        TransformationRule highFallback =
            rule("rule-b", TransformationRule.Mode.SELECTOR, true, "main");
        highFallback.setRuntimeOrder(0);
        TransformationRule low = rule("rule-low", TransformationRule.Mode.SELECTOR, true, "main");
        low.setName("Later");
        low.setRuntimeOrder(2147483645);

        TransformationRuleRuntimeStore.RuleSnapshot snapshot =
            manager.buildSnapshot(List.of(low, highZ, highFallback, highA));

        assertEquals(
            List.of("rule-a", "rule-b", "rule-z", "rule-low"),
            snapshot.activeRules(TransformationRule.Mode.SELECTOR).stream()
                .map(RuntimeTransformationRule::resourceName)
                .toList()
        );
    }

    private void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Condition was not satisfied in time");
    }

    private TransformationRule rule(String id, TransformationRule.Mode mode, boolean enabled,
        String match) {
        return namedRule(id, "", mode, enabled, match);
    }

    private TransformationRule namedRule(String id, String name, TransformationRule.Mode mode,
        boolean enabled, String match) {
        TransformationRule rule = new TransformationRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setName(name);
        rule.setEnabled(enabled);
        rule.setMode(mode);
        rule.setMatch(match);
        rule.setMatchRule(MatchRule.defaultRule());
        return rule;
    }
}
