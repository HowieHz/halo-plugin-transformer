package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.core.MatchRule;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import run.halo.app.extension.Extension;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjectionRuleManagerTest {
    @Mock
    private ReactiveExtensionClient client;

    private InjectionRuleManager manager;

    @BeforeEach
    void setUp() {
        manager = new InjectionRuleManager(client, 10, 40);
    }

    // why: watch-driven cache 启动后应先主动回源装载一次快照；
    // 否则运行时第一批请求会看到空规则集，而不是当前已保存状态。
    @Test
    void shouldLoadSnapshotWhenStartingWatch() {
        when(client.list(InjectionRule.class, null, null))
                .thenReturn(Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main")));

        manager.startWatching();

        waitUntil(() -> !manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block()
                .isEmpty());
        List<InjectionRule> rules = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of("rule-a"), rules.stream().map(InjectionRule::getId).toList());
        verify(client).watch(any(Watcher.class));
    }

    // why: 当前快照应由 Halo watch 事件驱动刷新；
    // 规则更新后不需要等 TTL，自身事件就应把新快照推到运行时读路径。
    @Test
    void shouldRefreshSnapshotFromWatchEvents() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                    ? Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"))
                    : Flux.just(rule("rule-b", InjectionRule.Mode.SELECTOR, true, "main"));
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        manager.startWatching();
        verify(client).watch(watcherCaptor.capture());

        waitUntil(() -> manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block()
                .stream()
                .map(InjectionRule::getId)
                .toList()
                .equals(List.of("rule-a")));

        watcherCaptor.getValue().onUpdate(
                rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"),
                rule("rule-b", InjectionRule.Mode.SELECTOR, true, "main")
        );

        waitUntil(() -> manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block()
                .stream()
                .map(InjectionRule::getId)
                .toList()
                .equals(List.of("rule-b")));

        assertEquals(2, fetchCount.get());
    }

    // why: watch 不是永不掉线的“神链路”；一旦底层 watch 被释放，管理器应自动按退避策略重连，
    // 而不是让运行时快照永久停留在一条已经失效的订阅上。
    @Test
    void shouldReconnectWatchAfterUnexpectedDispose() {
        when(client.list(InjectionRule.class, null, null))
                .thenReturn(Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main")));
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

    // why: 自愈不能只靠 watch 事件；如果启动时那次全量加载短暂失败，又迟迟没有后续事件，
    // 管理器也应按退避策略自行补一次刷新，把缓存从空状态拉回已保存快照。
    @Test
    void shouldRetrySnapshotRefreshAfterInitialFailure() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                    ? Flux.error(new IllegalStateException("temporary failure"))
                    : Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"));
        });

        manager.startWatching();

        waitUntil(() -> manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block()
                .stream()
                .map(InjectionRule::getId)
                .toList()
                .equals(List.of("rule-a")));

        assertTrue(fetchCount.get() >= 2);
    }

    // why: 写接口成功后仍会主动请求一次 refresh；即使多个 refresh 紧挨着到来，
    // 管理器也应把它们合并成有限次整表重建，而不是每次调用都并发起一个新的 list。
    @Test
    void shouldCoalesceOverlappingRefreshRequests() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"));
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
        when(client.list(InjectionRule.class, null, null)).thenReturn(Flux.empty());

        manager.startWatching();
        manager.stopWatching();
        manager.stopWatching();

        assertFalse(manager.listActiveByMode(InjectionRule.Mode.SELECTOR).hasElements().block());
    }

    // why: 运行时顺序仍是同一执行阶段内的显式契约；
    // watch-driven cache 只改变刷新方式，不能改变 runtimeOrder 排序语义。
    @Test
    void shouldSortActiveRulesByRuntimeOrderThenNameThenIdWithinMode() {
        InjectionRule highZ = rule("rule-z", InjectionRule.Mode.SELECTOR, true, "main");
        highZ.setName("Zulu");
        highZ.setRuntimeOrder(0);
        InjectionRule highA = rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main");
        highA.setName("Alpha");
        highA.setRuntimeOrder(0);
        InjectionRule highFallback = rule("rule-b", InjectionRule.Mode.SELECTOR, true, "main");
        highFallback.setRuntimeOrder(0);
        InjectionRule low = rule("rule-low", InjectionRule.Mode.SELECTOR, true, "main");
        low.setName("Later");
        low.setRuntimeOrder(2147483645);

        InjectionRuleManager.RuleSnapshot snapshot =
                manager.buildSnapshot(List.of(low, highZ, highFallback, highA));

        assertEquals(
                List.of("rule-a", "rule-b", "rule-z", "rule-low"),
                snapshot.activeRules(InjectionRule.Mode.SELECTOR).stream().map(InjectionRule::getId).toList()
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

    private InjectionRule rule(String id, InjectionRule.Mode mode, boolean enabled, String match) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setEnabled(enabled);
        rule.setMode(mode);
        rule.setMatch(match);
        rule.setMatchRule(MatchRule.defaultRule());
        return rule;
    }
}
