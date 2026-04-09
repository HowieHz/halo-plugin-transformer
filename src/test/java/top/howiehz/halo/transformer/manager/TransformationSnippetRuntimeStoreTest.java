package top.howiehz.halo.transformer.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

@ExtendWith(MockitoExtension.class)
class TransformationSnippetRuntimeStoreTest {
    @Mock
    private ReactiveExtensionClient client;

    private TransformationSnippetRuntimeStore store;

    @BeforeEach
    void setUp() {
        store = new TransformationSnippetRuntimeStore(client, 10, 40);
    }

    // why: 代码拼接热路径只应读取本地快照；
    // 启动 watch 时必须先装入已保存代码片段，避免第一批请求看到空内容。
    @Test
    void shouldLoadSnapshotWhenStartingWatch() {
        when(client.list(TransformationSnippet.class, null, null))
            .thenReturn(Flux.just(snippet("snippet-a", "before")));

        store.startWatching();

        waitUntil(() -> store.getByIds(List.of("snippet-a"))
            .block()
            .containsKey("snippet-a"));

        verify(client).watch(any(Watcher.class));
    }

    // why: 首轮 warm-up 是独立职责，不能被 watch 握手成败绑死；
    // 即使 watch 暂时不可用，也要先把已保存 snippet 装进运行时快照。
    @Test
    void shouldWarmUpSnapshotEvenWhenInitialWatchConnectionFails() {
        when(client.list(TransformationSnippet.class, null, null))
            .thenReturn(Flux.just(snippet("snippet-a", "before")));
        doThrow(new IllegalStateException("watch unavailable"))
            .when(client).watch(any(Watcher.class));

        store.startWatching();

        waitUntil(() -> store.getByIds(List.of("snippet-a"))
            .block()
            .containsKey("snippet-a"));
    }

    // why: snippet snapshot 要和 rule snapshot 一样由 watch 事件直接增量更新；
    // 内容变更后不应再靠整表 reload 才能让运行时看到最新代码。
    @Test
    void shouldApplyWatchUpdatesWithoutFullReload() {
        AtomicInteger listCount = new AtomicInteger();
        when(client.list(TransformationSnippet.class, null, null)).thenAnswer(invocation -> {
            listCount.incrementAndGet();
            return Flux.just(snippet("snippet-a", "before"));
        });

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        store.startWatching();
        verify(client).watch(watcherCaptor.capture());

        waitUntil(() -> "before".equals(store.getByIds(List.of("snippet-a"))
            .block()
            .get("snippet-a")
            .getCode()));

        watcherCaptor.getValue().onUpdate(
            snippet("snippet-a", "before"),
            snippet("snippet-a", "after")
        );

        waitUntil(() -> "after".equals(store.getByIds(List.of("snippet-a"))
            .block()
            .get("snippet-a")
            .getCode()));

        assertEquals(1, listCount.get());
    }

    // why: delete 事件的语义是“立即从运行时真源移除”，
    // 不能赌平台一定会把 deletion flag 带进事件体，否则会把已删 snippet 重新塞回快照。
    @Test
    void shouldRemoveSnippetOnDeleteEventEvenWithoutDeletionFlag() {
        when(client.list(TransformationSnippet.class, null, null))
            .thenReturn(Flux.just(snippet("snippet-a", "before")));

        ArgumentCaptor<Watcher> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);
        store.startWatching();
        verify(client).watch(watcherCaptor.capture());

        waitUntil(() -> store.getByIds(List.of("snippet-a"))
            .block()
            .containsKey("snippet-a"));

        watcherCaptor.getValue().onDelete(snippet("snippet-a", "before"));

        waitUntil(() -> store.getByIds(List.of("snippet-a")).block().isEmpty());
    }

    // why: 进入“删除中”生命周期的代码片段已经退出当前运行时可见集合；
    // 全量重建快照时也必须沿用同一条过滤语义。
    @Test
    void shouldSkipDeletingSnippetsFromSnapshot() {
        TransformationSnippet deletingSnippet = snippet("snippet-a", "before");
        deletingSnippet.getMetadata().setDeletionTimestamp(Instant.now());

        Map<String, TransformationSnippet> snapshot = store.buildSnapshot(List.of(deletingSnippet));

        assertTrue(snapshot.isEmpty());
    }

    // why: 控制台读列表和拖拽排序都应直接消费这份 watch-driven snippet 快照；
    // 若这里还拿不到已过滤后的可见资源，endpoint 就会继续被迫在请求路径整表 list。
    @Test
    void shouldExposeVisibleSnippetsForConsoleFromWatchDrivenSnapshot() {
        TransformationSnippet visibleSnippet = snippet("snippet-a", "before");
        TransformationSnippet deletingSnippet = snippet("snippet-b", "after");
        deletingSnippet.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.list(TransformationSnippet.class, null, null))
            .thenReturn(Flux.just(visibleSnippet, deletingSnippet));

        store.startWatching();

        waitUntil(() -> store.listVisibleSnippets().stream()
            .map(snippet -> snippet.getMetadata().getName())
            .toList()
            .equals(List.of("snippet-a")));
    }

    // why: watch 自愈之外，还要兜住首轮全量加载失败的场景；
    // 否则既没连上 watch、又没后续事件时，snippet snapshot 会永久停在空状态。
    @Test
    void shouldRetrySnapshotRefreshAfterInitialFailure() {
        AtomicInteger listCount = new AtomicInteger();
        when(client.list(TransformationSnippet.class, null, null)).thenAnswer(invocation -> {
            int round = listCount.incrementAndGet();
            return round == 1
                ? Flux.error(new IllegalStateException("temporary failure"))
                : Flux.just(snippet("snippet-a", "after"));
        });

        store.startWatching();

        waitUntil(() -> store.getByIds(List.of("snippet-a"))
            .block()
            .containsKey("snippet-a"));

        assertTrue(listCount.get() >= 2);
    }

    // why: 运行时解析不该依赖“调用方一定已经 trim 过 snippetIds”这种隐式前提；
    // 只要规则真源经过后端规范化，快照读取也必须沿用同一语义，才能兜住历史脏引用。
    @Test
    void shouldResolveSnippetsUsingTrimmedCanonicalIds() {
        when(client.list(TransformationSnippet.class, null, null))
            .thenReturn(Flux.just(snippet("snippet-a", "before")));

        store.startWatching();

        waitUntil(() -> store.getByIds(List.of(" snippet-a ")).block().containsKey("snippet-a"));

        Map<String, TransformationSnippet> resolved =
            store.getByIds(List.of(" snippet-a ", "", "snippet-a")).block();

        assertEquals(List.of("snippet-a"), List.copyOf(resolved.keySet()));
        assertEquals("before", resolved.get("snippet-a").getCode());
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

    private TransformationSnippet snippet(String id, String code) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setCode(code);
        return snippet;
    }
}
