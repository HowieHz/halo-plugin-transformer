package top.howiehz.halo.transformer.manager;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

@Slf4j
@Component
public class TransformationSnippetRuntimeStore {
    private static final GroupVersionKind SNIPPET_GVK =
        GroupVersionKind.fromExtension(TransformationSnippet.class);
    private static final long WATCH_RECONNECT_BASE_DELAY_MILLIS = 1_000L;
    private static final long WATCH_RECONNECT_MAX_DELAY_MILLIS = 30_000L;

    private final ReactiveExtensionClient client;
    private final Object refreshMonitor = new Object();
    private final Object snapshotMonitor = new Object();
    private final Object watchMonitor = new Object();
    private final long watchReconnectBaseDelayMillis;
    private final long watchReconnectMaxDelayMillis;
    private volatile Map<String, TransformationSnippet> cachedSnippetsById = Map.of();
    private volatile boolean refreshRequested;
    private volatile boolean refreshRunning;
    private volatile boolean watching;
    private volatile SnippetWatcher watcher;
    private volatile ScheduledExecutorService watchSupervisor;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> refreshRetryTask;
    private volatile int reconnectFailureCount;
    private volatile int refreshFailureCount;

    /**
     * why: 生产 bean 需要明确主构造器；本类同样保留了一个仅供测试缩短退避窗口的包级构造器，
     * 显式标记后才能避免 Spring 在多构造器场景下选错入口。
     */
    @Autowired
    public TransformationSnippetRuntimeStore(ReactiveExtensionClient client) {
        this(client, WATCH_RECONNECT_BASE_DELAY_MILLIS, WATCH_RECONNECT_MAX_DELAY_MILLIS);
    }

    /**
     * why: 测试需要把重连与 refresh retry 的退避窗口缩短到毫秒级，
     * 才能稳定覆盖 watch 自愈与 warm-up retry 语义。
     */
    TransformationSnippetRuntimeStore(ReactiveExtensionClient client,
        long watchReconnectBaseDelayMillis,
        long watchReconnectMaxDelayMillis) {
        this.client = client;
        this.watchReconnectBaseDelayMillis = watchReconnectBaseDelayMillis;
        this.watchReconnectMaxDelayMillis = watchReconnectMaxDelayMillis;
    }

    /**
     * why: 请求热路径应只从运行时快照读取代码片段；
     * 这样规则匹配后的代码拼接就不会再为每个请求回源 fetch snippet。
     */
    public Mono<Map<String, TransformationSnippet>> getByIds(Collection<String> snippetIds) {
        if (snippetIds == null || snippetIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        Map<String, TransformationSnippet> resolved = new LinkedHashMap<>();
        Map<String, TransformationSnippet> snapshot = cachedSnippetsById;
        for (String snippetId : snippetIds) {
            if (snippetId == null || snippetId.isBlank()) {
                continue;
            }
            TransformationSnippet snippet = snapshot.get(snippetId);
            if (snippet != null) {
                resolved.put(snippetId, snippet);
            }
        }
        return Mono.just(resolved);
    }

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
        SnippetWatcher currentWatcher;
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

    Mono<Map<String, TransformationSnippet>> refreshSnapshot() {
        return client.list(TransformationSnippet.class, null, null)
            .collectList()
            .map(this::replaceSnapshot);
    }

    Map<String, TransformationSnippet> buildSnapshot(
        java.util.List<TransformationSnippet> snippets) {
        Map<String, TransformationSnippet> snapshot = new LinkedHashMap<>();
        for (TransformationSnippet snippet : snippets) {
            if (snippet == null || snippet.getMetadata() == null) {
                continue;
            }
            String snippetId = snippet.getMetadata().getName();
            if (snippetId == null || snippetId.isBlank() || ExtensionUtil.isDeleted(snippet)) {
                continue;
            }
            snapshot.put(snippetId, snippet);
        }
        return snapshot;
    }

    private Map<String, TransformationSnippet> replaceSnapshot(
        java.util.List<TransformationSnippet> snippets) {
        synchronized (snapshotMonitor) {
            Map<String, TransformationSnippet> snapshot = buildSnapshot(snippets);
            cachedSnippetsById = snapshot;
            return snapshot;
        }
    }

    /**
     * why: snippet 解析现在也以 watch-driven snapshot 为单一真源；
     * 因此增删改事件要直接更新内存视图，而不是让请求线程再回源 fetch。
     */
    private void applySnippetEventSnapshot(SnippetEventType eventType,
        TransformationSnippet snippet) {
        String snippetId = describeSnippetId(snippet);
        if (snippetId == null) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            Map<String, TransformationSnippet> next = new LinkedHashMap<>(cachedSnippetsById);
            if (eventType == SnippetEventType.DELETE || ExtensionUtil.isDeleted(snippet)) {
                next.remove(snippetId);
            } else {
                next.put(snippetId, snippet);
            }
            cachedSnippetsById = next;
        }
    }

    private String describeSnippetId(TransformationSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null) {
            return null;
        }
        String snippetId = snippet.getMetadata().getName();
        if (snippetId == null || snippetId.isBlank()) {
            return null;
        }
        return snippetId;
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
                        log.info("Recovered TransformationSnippet snapshot refresh after {} "
                                + "failed attempt(s)", recoveredFailures);
                    }
                    log.debug("Refreshed snippet snapshot with {} total snippets", snapshot.size());
                },
                error -> {
                    int failureCount = incrementRefreshFailureCount();
                    long delayMillis = computeBackoffDelayMillis(failureCount);
                    log.warn("Failed to refresh TransformationSnippet snapshot; retrying in {} ms "
                            + "(attempt {})", delayMillis, failureCount, error);
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

        SnippetWatcher nextWatcher = new SnippetWatcher();
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
            log.info("Recovered TransformationSnippet watch after {} failed reconnect attempt(s)",
                recoveredFailures);
        } else {
            log.info("Started TransformationSnippet watch");
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
        log.warn("Failed to connect TransformationSnippet watch during {}; retrying in {} ms "
                + "(attempt {})", reason, delayMillis, failureCount, error);
        scheduleReconnectTask(delayMillis);
    }

    private void handleUnexpectedWatchDispose(SnippetWatcher disconnectedWatcher) {
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
        log.warn("TransformationSnippet watch disconnected; retrying in {} ms (attempt {})",
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
            Thread thread = new Thread(runnable, "transformation-snippet-watch-supervisor");
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

    private final class SnippetWatcher implements Watcher {
        private volatile boolean disposed;
        private volatile boolean disposedByManager;
        private Runnable disposeHook;

        @Override
        public void onAdd(Extension extension) {
            handleSnippetEvent(SnippetEventType.ADD, extension);
        }

        @Override
        public void onUpdate(Extension oldExtension, Extension newExtension) {
            handleSnippetEvent(SnippetEventType.UPDATE, newExtension);
        }

        @Override
        public void onDelete(Extension extension) {
            handleSnippetEvent(SnippetEventType.DELETE, extension);
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

        private void handleSnippetEvent(SnippetEventType eventType, Extension extension) {
            if (disposed || extension == null) {
                return;
            }
            if (extension instanceof TransformationSnippet snippet) {
                applySnippetEventSnapshot(eventType, snippet);
                return;
            }
            if (!SNIPPET_GVK.equals(extension.groupVersionKind())) {
                return;
            }
            requestRefreshAsync();
        }
    }

    private enum SnippetEventType {
        ADD,
        UPDATE,
        DELETE
    }
}
