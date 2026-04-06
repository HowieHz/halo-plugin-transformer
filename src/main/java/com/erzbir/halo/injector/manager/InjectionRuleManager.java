package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.scheme.InjectionRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InjectionRuleManager {
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(2);

    private final ReactiveExtensionClient client;
    private final Object cacheMonitor = new Object();
    private volatile RuleSnapshot cachedSnapshot = RuleSnapshot.empty();
    private volatile long snapshotExpiresAtNanos = 0L;
    private volatile Mono<RuleSnapshot> refreshSnapshotMono;

    public InjectionRuleManager(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Flux<InjectionRule> list() {
        return currentSnapshot().flatMapMany(snapshot -> Flux.fromIterable(snapshot.allRules()));
    }

    /**
     * why: 运行时只关心“当前可参与注入”的规则；
     * 这里把按 mode、enabled、valid 过滤后的结果做成短 TTL 快照，避免每个请求都回源并重复筛选整批规则。
     */
    public Flux<InjectionRule> listActiveByMode(InjectionRule.Mode mode) {
        return currentSnapshot().flatMapMany(snapshot -> Flux.fromIterable(snapshot.activeRules(mode)));
    }

    /**
     * why: 控制台写接口成功后可主动让缓存失效，尽快反映启停、更新和删除；
     * 同时保留短 TTL，兜住绕过 console 写接口的外部改动。
     */
    public void invalidateCache() {
        snapshotExpiresAtNanos = 0L;
    }

    Mono<RuleSnapshot> currentSnapshot() {
        long now = System.nanoTime();
        if (now < snapshotExpiresAtNanos) {
            return Mono.just(cachedSnapshot);
        }

        Mono<RuleSnapshot> inFlight = refreshSnapshotMono;
        if (inFlight != null) {
            return inFlight;
        }

        synchronized (cacheMonitor) {
            long recheckNow = System.nanoTime();
            if (recheckNow < snapshotExpiresAtNanos) {
                return Mono.just(cachedSnapshot);
            }
            if (refreshSnapshotMono == null) {
                refreshSnapshotMono = client.list(InjectionRule.class, null, null)
                        .collectList()
                        .map(this::buildSnapshot)
                        .doOnNext(snapshot -> {
                            cachedSnapshot = snapshot;
                            snapshotExpiresAtNanos = System.nanoTime() + SNAPSHOT_TTL.toNanos();
                        })
                        .doOnError(e -> log.error("Failed to fetch InjectionRules", e))
                        .onErrorResume(e -> Mono.just(cachedSnapshot))
                        .doFinally(signalType -> refreshSnapshotMono = null)
                        .cache();
            }
            return refreshSnapshotMono;
        }
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
            immutableByMode.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new RuleSnapshot(allRules, immutableByMode);
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
}
