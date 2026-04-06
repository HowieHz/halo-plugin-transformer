package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.core.MatchRule;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjectionRuleManagerTest {
    @Mock
    private ReactiveExtensionClient client;

    private InjectionRuleManager manager;

    @BeforeEach
    void setUp() {
        manager = new InjectionRuleManager(client);
    }

    // why: 高频请求命中同一时间窗时，运行时应复用同一份规则快照，避免每次都回源拉整批规则。
    @Test
    void shouldReuseCachedSnapshotWithinTtl() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            return Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"));
        });

        List<InjectionRule> first = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();
        List<InjectionRule> second = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(1, fetchCount.get());
        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    // why: 控制台写接口会主动让缓存失效；失效后下一次读取必须立刻回源拿到最新规则。
    @Test
    void shouldRefreshSnapshotAfterInvalidate() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                    ? Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"))
                    : Flux.just(rule("rule-b", InjectionRule.Mode.ID, true, "root"));
        });

        List<InjectionRule> first = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();
        manager.invalidateCache();
        List<InjectionRule> second = manager.listActiveByMode(InjectionRule.Mode.ID)
                .collectList()
                .block();

        assertEquals(2, fetchCount.get());
        assertEquals(List.of("rule-a"), first.stream().map(InjectionRule::getId).toList());
        assertEquals(List.of("rule-b"), second.stream().map(InjectionRule::getId).toList());
    }

    // why: 启动预热会把首次读规则的冷加载提前完成；预热完成后，首个真实读取应直接命中已热好的快照。
    @Test
    void shouldWarmSnapshotBeforeFirstRead() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            return Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"));
        });

        manager.warmUpCache().block();
        List<InjectionRule> rules = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(1, fetchCount.get());
        assertEquals(List.of("rule-a"), rules.stream().map(InjectionRule::getId).toList());
    }

    // why: 写后预热要在失效之后立刻拉新快照，避免下一次请求再承担一次冷加载。
    @Test
    void shouldWarmNewSnapshotImmediatelyAfterInvalidate() {
        AtomicInteger fetchCount = new AtomicInteger();
        when(client.list(InjectionRule.class, null, null)).thenAnswer(invocation -> {
            int round = fetchCount.incrementAndGet();
            return round == 1
                    ? Flux.just(rule("rule-a", InjectionRule.Mode.SELECTOR, true, "main"))
                    : Flux.just(rule("rule-b", InjectionRule.Mode.SELECTOR, true, "main"));
        });

        manager.warmUpCache().block();
        manager.invalidateCache();
        manager.warmUpCache().block();
        List<InjectionRule> rules = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(2, fetchCount.get());
        assertEquals(List.of("rule-b"), rules.stream().map(InjectionRule::getId).toList());
    }

    // why: 快照里只应保留“当前真正可参与运行时匹配”的规则，避免每次请求再重复做 enabled/valid 过滤。
    @Test
    void shouldKeepOnlyEnabledAndValidRulesInActiveSnapshot() {
        when(client.list(InjectionRule.class, null, null)).thenReturn(Flux.just(
                rule("rule-enabled", InjectionRule.Mode.SELECTOR, true, "main"),
                rule("rule-disabled", InjectionRule.Mode.SELECTOR, false, "main"),
                rule("rule-invalid", InjectionRule.Mode.ID, true, "")
        ));

        List<InjectionRule> rules = manager.listActiveByMode(InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of("rule-enabled"), rules.stream().map(InjectionRule::getId).toList());
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
