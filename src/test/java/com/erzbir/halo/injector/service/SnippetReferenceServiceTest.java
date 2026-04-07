package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnippetReferenceServiceTest {
    private ReactiveExtensionClient client;
    private SnippetReferenceService service;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ReactiveExtensionClient.class);
        service = new SnippetReferenceService(client);
    }

    // why: 没有关联代码块时不应额外查询或报错，规则保存应允许空关联稳定通过。
    @Test
    void shouldAllowEmptySnippetIds() {
        assertEquals(new LinkedHashSet<>(), service.normalizeAndValidateSnippetIds(Set.of()).block());
    }

    // why: 规则现在是唯一真源，因此写入前必须明确拒绝不存在的代码块 id，避免悬挂引用落库。
    @Test
    void shouldRejectMissingSnippetIds() {
        CodeSnippet existingSnippet = snippet("snippet-a");
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(existingSnippet));
        when(client.fetch(CodeSnippet.class, "snippet-b")).thenReturn(Mono.empty());

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateSnippetIds(Set.of("snippet-a", "snippet-b")).block()
        );

        assertEquals("snippetIds：包含不存在的代码块：snippet-b", error.getReason());
        verify(client, never()).list(any(), any(), any());
    }

    // why: 即使代码块存在，只要其本体已经无效，也不应允许继续被规则引用。
    @Test
    void shouldRejectInvalidSnippetReference() {
        CodeSnippet invalidSnippet = snippet("snippet-a");
        invalidSnippet.setCode("");
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(invalidSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateSnippetIds(Set.of("snippet-a")).block()
        );

        assertEquals("snippetIds：代码块 snippet-a 当前无法关联", error.getReason());
        verify(client, never()).list(any(), any(), any());
    }

    // why: 更新规则时若只是保留历史坏关联，不应把“改名称/描述/启用状态”这类无关更新一并拦住；
    // 只有新增关联才需要重新校验。
    @Test
    void shouldAllowKeepingExistingInvalidSnippetReferenceDuringUpdate() {
        CodeSnippet invalidSnippet = snippet("snippet-a");
        invalidSnippet.setCode("");
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(invalidSnippet));

        LinkedHashSet<String> result = service.normalizeAndValidateAddedSnippetIds(
                Set.of("snippet-a"),
                Set.of("snippet-a")
        ).block();

        assertEquals(new LinkedHashSet<>(Set.of("snippet-a")), result);
        verify(client, never()).fetch(eq(CodeSnippet.class), eq("snippet-a"));
    }

    // why: 即使规则本身带着历史坏关联，新增一个新的坏关联也必须被明确拦下，
    // 避免“兼容历史脏数据”演变成“继续写入新的脏数据”。
    @Test
    void shouldRejectNewInvalidSnippetReferenceDuringUpdate() {
        CodeSnippet validSnippet = snippet("snippet-a");
        CodeSnippet invalidSnippet = snippet("snippet-b");
        invalidSnippet.setCode("");
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(validSnippet));
        when(client.fetch(CodeSnippet.class, "snippet-b")).thenReturn(Mono.just(invalidSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateAddedSnippetIds(
                        Set.of("snippet-a"),
                        Set.of("snippet-a", "snippet-b")
                ).block()
        );

        assertEquals("snippetIds：代码块 snippet-b 当前无法关联", error.getReason());
        verify(client, never()).list(any(), any(), any());
    }

    // why: deleting 中的代码块已经进入最终删除流程，不应再允许被新规则引用。
    @Test
    void shouldRejectDeletingSnippetReference() {
        CodeSnippet deletingSnippet = snippet("snippet-a");
        deletingSnippet.getMetadata().setDeletionTimestamp(java.time.Instant.now());

        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(deletingSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateSnippetIds(Set.of("snippet-a")).block()
        );

        assertEquals("snippetIds：代码块 snippet-a 当前无法关联", error.getReason());
        verify(client, never()).list(any(), any(), any());
    }

    // why: 这里校验的是精确资源名集合，最佳实现应是按 name 点查；
    // 这样避免一次小范围关联校验回源扫描整表 snippet。
    @Test
    void shouldFetchOnlyReferencedSnippetIdsInsteadOfListingAllSnippets() {
        CodeSnippet snippetA = snippet("snippet-a");
        CodeSnippet snippetB = snippet("snippet-b");
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Mono.just(snippetA));
        when(client.fetch(CodeSnippet.class, "snippet-b")).thenReturn(Mono.just(snippetB));

        LinkedHashSet<String> result = service.normalizeAndValidateSnippetIds(Set.of("snippet-a", "snippet-b"))
                .block();

        assertEquals(new LinkedHashSet<>(Set.of("snippet-a", "snippet-b")), result);
        verify(client).fetch(CodeSnippet.class, "snippet-a");
        verify(client).fetch(CodeSnippet.class, "snippet-b");
        verify(client, never()).list(any(), any(), any());
    }

    private CodeSnippet snippet(String id) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");
        return snippet;
    }
}
