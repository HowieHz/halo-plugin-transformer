package top.howiehz.halo.transformer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

class TransformationSnippetLifecycleServiceTest {
    private ReactiveExtensionClient client;
    private TransformationSnippetLifecycleService service;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        service = new TransformationSnippetLifecycleService(client);
    }

    // why: 只有带着 finalizer 进入 delete，Halo 才会先标记“删除中”，再交给 reconciler 清理引用；
    // 旧数据若缺 finalizer，删除入口必须先补齐，不能退回同步补偿写。
    @Test
    void shouldAddFinalizerBeforeRequestingDeletion() {
        TransformationSnippet snippet = snippet("snippet-a");
        when(client.update(any(TransformationSnippet.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));
        when(client.delete(any(TransformationSnippet.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        service.markForDeletion(snippet).block();

        verify(client).update(any(TransformationSnippet.class));
        verify(client).delete(any(TransformationSnippet.class));
    }

    // why: 已经处于“删除中”状态的代码片段，不需要重复发起 delete；
    // 否则重复点击删除会制造多余写请求，却不会带来更强的一致性。
    @Test
    void shouldSkipDuplicateDeleteRequestForDeletingSnippet() {
        TransformationSnippet snippet = snippet("snippet-a");
        snippet.getMetadata().setDeletionTimestamp(java.time.Instant.now());

        service.markForDeletion(snippet).block();

        verify(client, never()).update(any(TransformationSnippet.class));
        verify(client, never()).delete(any(TransformationSnippet.class));
    }

    private TransformationSnippet snippet(String id) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        return snippet;
    }
}
