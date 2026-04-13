package top.howiehz.halo.transformer.runtime.store;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.extension.TransformationSnippet;
import top.howiehz.halo.transformer.service.TransformationSnippetLifecycleService;
import top.howiehz.halo.transformer.support.TransformationSnippetReferenceIds;

@Component
public class TransformationSnippetRuntimeStore extends AbstractWatchDrivenExtensionStore<
    TransformationSnippet, Map<String, TransformationSnippet>> {
    private static final GroupVersionKind SNIPPET_GVK =
        GroupVersionKind.fromExtension(TransformationSnippet.class);
    private static final long WATCH_RECONNECT_BASE_DELAY_MILLIS = 1_000L;
    private static final long WATCH_RECONNECT_MAX_DELAY_MILLIS = 30_000L;

    private final Object snapshotMonitor = new Object();
    private volatile Map<String, TransformationSnippet> cachedSnippetsById = Map.of();

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
        super(
            client,
            TransformationSnippet.class,
            SNIPPET_GVK,
            "TransformationSnippet",
            "snippets",
            "transformation-snippet-watch-supervisor",
            watchReconnectBaseDelayMillis,
            watchReconnectMaxDelayMillis
        );
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
        for (String snippetId : TransformationSnippetReferenceIds.normalize(snippetIds)) {
            TransformationSnippet snippet = snapshot.get(snippetId);
            if (snippet != null) {
                resolved.put(snippetId, snippet);
            }
        }
        return Mono.just(resolved);
    }

    /**
     * why: 控制台列表只应展示“可见资源”，删除中的资源必须隐藏；
     * 运行时注入路径会继续从同一份快照里按 id 读取删除中且待清理引用的 snippet，避免提前退化输出。
     */
    public List<TransformationSnippet> listVisibleSnippets() {
        return cachedSnippetsById.values().stream()
            .filter(snippet -> !ExtensionUtil.isDeleted(snippet))
            .toList();
    }

    /**
     * why: 控制台写口在成功持久化后，需要立刻把最新已保存代码片段回灌进可见快照；
     * watch 仍负责最终一致自愈，但不能让“创建后立刻刷新”继续读到旧状态。
     */
    public void applyPersistedSnippet(TransformationSnippet snippet) {
        String snippetId = describeSnippetId(snippet);
        if (snippetId == null) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            Map<String, TransformationSnippet> next = new LinkedHashMap<>(cachedSnippetsById);
            if (shouldKeepInSnapshot(snippet)) {
                next.put(snippetId, snippet);
            } else {
                next.remove(snippetId);
            }
            cachedSnippetsById = next;
        }
    }

    /**
     * why: 代码片段一旦进入 deleting 生命周期，就应立刻退出控制台可见集合；
     * 这里提供同步摘除入口，避免删除成功后下一次 snapshot 仍短暂返回旧列表。
     */
    public void removeSnippet(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            Map<String, TransformationSnippet> next = new LinkedHashMap<>(cachedSnippetsById);
            next.remove(snippetId);
            cachedSnippetsById = next;
        }
    }

    @Override
    protected Mono<Map<String, TransformationSnippet>> refreshSnapshot() {
        return client().list(TransformationSnippet.class, null, null)
            .collectList()
            .map(this::buildSnapshot);
    }

    Map<String, TransformationSnippet> buildSnapshot(
        java.util.List<TransformationSnippet> snippets) {
        Map<String, TransformationSnippet> snapshot = new LinkedHashMap<>();
        for (TransformationSnippet snippet : snippets) {
            if (snippet == null || snippet.getMetadata() == null) {
                continue;
            }
            String snippetId = snippet.getMetadata().getName();
            if (snippetId == null || snippetId.isBlank() || !shouldKeepInSnapshot(snippet)) {
                continue;
            }
            snapshot.put(snippetId, snippet);
        }
        return snapshot;
    }

    @Override
    protected void replaceSnapshot(Map<String, TransformationSnippet> snapshot) {
        synchronized (snapshotMonitor) {
            cachedSnippetsById = snapshot;
        }
    }

    /**
     * why: snippet 解析现在也以 watch-driven snapshot 为单一真源；
     * 因此增删改事件要直接更新内存视图，而不是让请求线程再回源 fetch。
     */
    @Override
    protected void applyWatchEvent(WatchEventType eventType, TransformationSnippet snippet) {
        String snippetId = describeSnippetId(snippet);
        if (snippetId == null) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            cachedSnippetsById = applyIncrementalSnapshotEvent(cachedSnippetsById, eventType,
                snippetId, snippet);
        }
    }

    @Override
    protected int snapshotSize(Map<String, TransformationSnippet> snapshot) {
        return snapshot.size();
    }

    private Map<String, TransformationSnippet> applyIncrementalSnapshotEvent(
        Map<String, TransformationSnippet> currentSnapshot,
        WatchEventType eventType,
        String snippetId,
        TransformationSnippet snippet) {
        Map<String, TransformationSnippet> next = new LinkedHashMap<>(currentSnapshot);
        if (eventType == WatchEventType.DELETE || !shouldKeepInSnapshot(snippet)) {
            next.remove(snippetId);
        } else {
            next.put(snippetId, snippet);
        }
        return next;
    }

    private boolean shouldKeepInSnapshot(TransformationSnippet snippet) {
        return snippet != null && (!ExtensionUtil.isDeleted(snippet)
            || TransformationSnippetLifecycleService.isDeletionPendingCleanup(snippet));
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
}
