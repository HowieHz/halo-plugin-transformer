package top.howiehz.halo.transformer.manager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;

/**
 * why: rule/snippet runtime store 的快照结构不同，但 watch 生命周期、自愈重连、refresh loop
 * 是同一套一致性基础设施。把这层抽成单一骨架后，后续修复 reconnect/retry 语义时只需要改一处，
 * 不会再出现两个 runtime store 逐步漂移。
 */
abstract class AbstractWatchDrivenExtensionStore<R extends Extension, S> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ReactiveExtensionClient client;
    private final Class<R> resourceType;
    private final GroupVersionKind resourceGroupVersionKind;
    private final String resourceDisplayName;
    private final String snapshotItemLabelPlural;
    private final String watchSupervisorThreadName;
    private final Object refreshMonitor = new Object();
    private final Object watchMonitor = new Object();
    private final long watchReconnectBaseDelayMillis;
    private final long watchReconnectMaxDelayMillis;
    private volatile boolean refreshRequested;
    private volatile boolean refreshRunning;
    private volatile boolean watching;
    private volatile RuntimeStoreWatcher watcher;
    private volatile ScheduledExecutorService watchSupervisor;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> refreshRetryTask;
    private volatile int reconnectFailureCount;
    private volatile int refreshFailureCount;

    AbstractWatchDrivenExtensionStore(ReactiveExtensionClient client, Class<R> resourceType,
        GroupVersionKind resourceGroupVersionKind, String resourceDisplayName,
        String snapshotItemLabelPlural, String watchSupervisorThreadName,
        long watchReconnectBaseDelayMillis, long watchReconnectMaxDelayMillis) {
        this.client = client;
        this.resourceType = resourceType;
        this.resourceGroupVersionKind = resourceGroupVersionKind;
        this.resourceDisplayName = resourceDisplayName;
        this.snapshotItemLabelPlural = snapshotItemLabelPlural;
        this.watchSupervisorThreadName = watchSupervisorThreadName;
        this.watchReconnectBaseDelayMillis = watchReconnectBaseDelayMillis;
        this.watchReconnectMaxDelayMillis = watchReconnectMaxDelayMillis;
    }

    public final void startWatching() {
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

    public final void stopWatching() {
        RuntimeStoreWatcher currentWatcher;
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

    public final void invalidateAndWarmUpAsync() {
        requestRefreshAsync();
    }

    protected final ReactiveExtensionClient client() {
        return client;
    }

    protected final void requestRefreshAsync() {
        synchronized (refreshMonitor) {
            refreshRequested = true;
            if (refreshRunning) {
                return;
            }
            refreshRunning = true;
        }
        runRefreshLoopAsync();
    }

    protected abstract Mono<S> refreshSnapshot();

    protected abstract void applyWatchEvent(WatchEventType eventType, R resource);

    protected abstract int snapshotSize(S snapshot);

    protected enum WatchEventType {
        ADD,
        UPDATE,
        DELETE
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
                        log.info("Recovered {} snapshot refresh after {} failed attempt(s)",
                            resourceDisplayName, recoveredFailures);
                    }
                    log.debug("Refreshed {} snapshot with {} total {}", resourceDisplayName,
                        snapshotSize(snapshot), snapshotItemLabelPlural);
                },
                error -> {
                    int failureCount = incrementRefreshFailureCount();
                    long delayMillis = computeBackoffDelayMillis(failureCount);
                    log.warn("Failed to refresh {} snapshot; retrying in {} ms (attempt {})",
                        resourceDisplayName, delayMillis, failureCount, error);
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

        RuntimeStoreWatcher nextWatcher = new RuntimeStoreWatcher();
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
            log.info("Recovered {} watch after {} failed reconnect attempt(s)",
                resourceDisplayName, recoveredFailures);
        } else {
            log.info("Started {} watch", resourceDisplayName);
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
        log.warn("Failed to connect {} watch during {}; retrying in {} ms (attempt {})",
            resourceDisplayName, reason, delayMillis, failureCount, error);
        scheduleReconnectTask(delayMillis);
    }

    private void handleUnexpectedWatchDispose(RuntimeStoreWatcher disconnectedWatcher) {
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
        log.warn("{} watch disconnected; retrying in {} ms (attempt {})",
            resourceDisplayName, delayMillis, failureCount);
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
            Thread thread = new Thread(runnable, watchSupervisorThreadName);
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

    private final class RuntimeStoreWatcher implements Watcher {
        private volatile boolean disposed;
        private volatile boolean disposedByManager;
        private Runnable disposeHook;

        @Override
        public void onAdd(Extension extension) {
            handleEvent(WatchEventType.ADD, extension);
        }

        @Override
        public void onUpdate(Extension oldExtension, Extension newExtension) {
            handleEvent(WatchEventType.UPDATE, newExtension);
        }

        @Override
        public void onDelete(Extension extension) {
            handleEvent(WatchEventType.DELETE, extension);
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

        private void handleEvent(WatchEventType eventType, Extension extension) {
            if (disposed || extension == null) {
                return;
            }
            if (resourceType.isInstance(extension)) {
                applyWatchEvent(eventType, resourceType.cast(extension));
                return;
            }
            if (!resourceGroupVersionKind.equals(extension.groupVersionKind())) {
                return;
            }
            requestRefreshAsync();
        }
    }
}
