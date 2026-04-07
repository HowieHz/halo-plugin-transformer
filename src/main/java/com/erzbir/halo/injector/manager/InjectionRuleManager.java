package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.scheme.InjectionRule;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InjectionRuleManager {
    private static final GroupVersionKind RULE_GVK = GroupVersionKind.fromExtension(InjectionRule.class);

    private final ReactiveExtensionClient client;
    private final Object refreshMonitor = new Object();
    private volatile RuleSnapshot cachedSnapshot = RuleSnapshot.empty();
    private volatile boolean refreshRequested;
    private volatile boolean refreshRunning;
    private volatile RuleWatcher watcher;

    public InjectionRuleManager(ReactiveExtensionClient client) {
        this.client = client;
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
     */
    public void startWatching() {
        synchronized (refreshMonitor) {
            if (watcher != null && !watcher.isDisposed()) {
                return;
            }
            watcher = new RuleWatcher();
            client.watch(watcher);
        }
        refreshSnapshot()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        snapshot -> log.debug("Loaded initial rule snapshot with {} total rules", snapshot.allRules().size()),
                        error -> log.warn("Failed to load initial rule snapshot", error)
                );
    }

    public void stopWatching() {
        synchronized (refreshMonitor) {
            if (watcher == null) {
                return;
            }
            watcher.dispose();
            watcher = null;
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
                .doOnNext(snapshot -> cachedSnapshot = snapshot)
                .doOnError(error -> log.error("Failed to refresh InjectionRule snapshot", error))
                .onErrorResume(error -> Mono.just(cachedSnapshot));
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
                        snapshot -> log.debug("Refreshed rule snapshot with {} total rules", snapshot.allRules().size()),
                        error -> log.warn("Failed to refresh rule snapshot from watch event", error)
                );
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
            disposed = true;
            if (disposeHook != null) {
                disposeHook.run();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }

        private void handleRuleEvent(Extension extension) {
            if (disposed || extension == null || !RULE_GVK.equals(extension.groupVersionKind())) {
                return;
            }
            requestRefreshAsync();
        }
    }
}
