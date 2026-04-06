package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(existingSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateSnippetIds(Set.of("snippet-a", "snippet-b")).block()
        );

        assertEquals("snippetIds：包含不存在的代码块：snippet-b", error.getReason());
    }

    // why: 即使代码块存在，只要其本体已经无效，也不应允许继续被规则引用。
    @Test
    void shouldRejectInvalidSnippetReference() {
        CodeSnippet invalidSnippet = snippet("snippet-a");
        invalidSnippet.setCode("");
        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(invalidSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateSnippetIds(Set.of("snippet-a")).block()
        );

        assertEquals("snippetIds：代码块 snippet-a 当前无法关联", error.getReason());
    }

    // why: 更新规则时若只是保留历史坏关联，不应把“改名称/描述/启用状态”这类无关更新一并拦住；
    // 只有新增关联才需要重新校验。
    @Test
    void shouldAllowKeepingExistingInvalidSnippetReferenceDuringUpdate() {
        CodeSnippet invalidSnippet = snippet("snippet-a");
        invalidSnippet.setCode("");
        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(invalidSnippet));

        LinkedHashSet<String> result = service.normalizeAndValidateAddedSnippetIds(
                Set.of("snippet-a"),
                Set.of("snippet-a")
        ).block();

        assertEquals(new LinkedHashSet<>(Set.of("snippet-a")), result);
    }

    // why: 即使规则本身带着历史坏关联，新增一个新的坏关联也必须被明确拦下，
    // 避免“兼容历史脏数据”演变成“继续写入新的脏数据”。
    @Test
    void shouldRejectNewInvalidSnippetReferenceDuringUpdate() {
        CodeSnippet validSnippet = snippet("snippet-a");
        CodeSnippet invalidSnippet = snippet("snippet-b");
        invalidSnippet.setCode("");
        when(client.list(eq(CodeSnippet.class), isNull(), isNull()))
                .thenReturn(Flux.just(validSnippet, invalidSnippet));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> service.normalizeAndValidateAddedSnippetIds(
                        Set.of("snippet-a"),
                        Set.of("snippet-a", "snippet-b")
                ).block()
        );

        assertEquals("snippetIds：代码块 snippet-b 当前无法关联", error.getReason());
    }

    // why: 删除代码块后必须从所有规则真源里摘掉它，确保不会残留失效 snippetId。
    @Test
    void shouldDetachDeletedSnippetFromRules() {
        CodeSnippet deletingSnippet = snippet("snippet-a");
        InjectionRule linkedRule = rule("rule-a");
        linkedRule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a", "snippet-b")));

        when(client.list(eq(InjectionRule.class), isNull(), isNull())).thenReturn(Flux.just(linkedRule));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.delete(any(CodeSnippet.class))).thenReturn(Mono.just(deletingSnippet));

        assertDoesNotThrow(() -> service.deleteSnippetAndDetachRules(deletingSnippet).block());

        verify(client).update(Mockito.argThat((InjectionRule updatedRule) ->
                "rule-a".equals(updatedRule.getId())
                        && updatedRule.getSnippetIds().equals(new LinkedHashSet<>(Set.of("snippet-b")))));
        verify(client).delete(deletingSnippet);
    }

    private CodeSnippet snippet(String id) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");
        return snippet;
    }

    private InjectionRule rule(String id) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        return rule;
    }
}
