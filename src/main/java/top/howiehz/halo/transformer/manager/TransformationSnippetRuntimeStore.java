package top.howiehz.halo.transformer.manager;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.util.TransformationSnippetReferenceIds;

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
     * why: 控制台列表与排序保存也应消费同一份 watch-driven snippet 真源；
     * 这样请求线程只读内存快照，不再为了列表刷新或拖拽保存回源整表扫描。
     */
    public List<TransformationSnippet> listVisibleSnippets() {
        return List.copyOf(cachedSnippetsById.values());
    }

    @Override
    protected Mono<Map<String, TransformationSnippet>> refreshSnapshot() {
        return client().list(TransformationSnippet.class, null, null)
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
    @Override
    protected void applyWatchEvent(WatchEventType eventType, TransformationSnippet snippet) {
        String snippetId = describeSnippetId(snippet);
        if (snippetId == null) {
            requestRefreshAsync();
            return;
        }
        synchronized (snapshotMonitor) {
            Map<String, TransformationSnippet> next = new LinkedHashMap<>(cachedSnippetsById);
            if (eventType == WatchEventType.DELETE || ExtensionUtil.isDeleted(snippet)) {
                next.remove(snippetId);
            } else {
                next.put(snippetId, snippet);
            }
            cachedSnippetsById = next;
        }
    }

    @Override
    protected int snapshotSize(Map<String, TransformationSnippet> snapshot) {
        return snapshot.size();
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
