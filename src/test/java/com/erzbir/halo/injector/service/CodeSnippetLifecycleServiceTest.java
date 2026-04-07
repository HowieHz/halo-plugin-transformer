package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSnippetLifecycleServiceTest {
    private ReactiveExtensionClient client;
    private CodeSnippetLifecycleService service;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        service = new CodeSnippetLifecycleService(client);
    }

    // why: 只有带着 finalizer 进入 delete，Halo 才会先标记 deleting 再交给 reconciler 清理引用；
    // 旧数据若缺 finalizer，删除入口必须先补齐，不能退回同步补偿写。
    @Test
    void shouldAddFinalizerBeforeRequestingDeletion() {
        CodeSnippet snippet = snippet("snippet-a");
        when(client.update(any(CodeSnippet.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.delete(any(CodeSnippet.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.markForDeletion(snippet).block();

        verify(client).update(any(CodeSnippet.class));
        verify(client).delete(any(CodeSnippet.class));
    }

    // why: 新建和更新写口都应把删除 finalizer 当成资源生命周期的一部分；
    // 这样即使未来走别的删除入口，也仍能复用同一套 finalizer 清理流程。
    @Test
    void shouldAttachFinalizerDuringPrepareForPersist() {
        CodeSnippet snippet = snippet("snippet-a");

        CodeSnippet prepared = service.prepareForPersist(snippet);

        assertTrue(prepared.getMetadata().getFinalizers().contains(CodeSnippetLifecycleService.DELETION_FINALIZER));
    }

    // why: 已经处于 deleting 状态的代码块，不需要重复发起 delete；
    // 否则重复点击删除会制造多余写请求，却不会带来更强的一致性。
    @Test
    void shouldSkipDuplicateDeleteRequestForDeletingSnippet() {
        CodeSnippet snippet = snippet("snippet-a");
        snippet.getMetadata().setDeletionTimestamp(java.time.Instant.now());

        service.markForDeletion(snippet).block();

        verify(client, never()).update(any(CodeSnippet.class));
        verify(client, never()).delete(any(CodeSnippet.class));
    }

    private CodeSnippet snippet(String id) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        return snippet;
    }
}
