package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.manager.InjectionRuleRuntimeStore;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSnippetDeletionReconcilerTest {
    private ExtensionClient client;
    private InjectionRuleRuntimeStore ruleRuntimeStore;
    private CodeSnippetDeletionReconciler reconciler;

    @BeforeEach
    void setUp() {
        client = mock(ExtensionClient.class);
        ruleRuntimeStore = mock(InjectionRuleRuntimeStore.class);
        reconciler = new CodeSnippetDeletionReconciler(client, ruleRuntimeStore);
    }

    // why: finalizer 协调器的核心职责，就是在 Halo 标记 deleting 后先摘掉规则真源里的引用，
    // 再移除 finalizer 让平台完成最终删除；这条主链路必须被测试锁住。
    @Test
    void shouldDetachReferencingRulesBeforeRemovingSnippetFinalizer() {
        CodeSnippet deletingSnippet = deletingSnippet("snippet-a");
        InjectionRule listedRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        InjectionRule latestRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        latestRule.setDescription("latest description");

        when(client.fetch(CodeSnippet.class, "snippet-a"))
                .thenReturn(Optional.of(deletingSnippet))
                .thenReturn(Optional.of(deletingSnippet("snippet-a")));
        when(client.list(eq(InjectionRule.class), any(), eq(null))).thenReturn(List.of(listedRule));
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Optional.of(latestRule));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Extension.class);
        verify(client, atLeastOnce()).update(updateCaptor.capture());
        List<Extension> updatedResources = updateCaptor.getAllValues();

        InjectionRule updatedRule = assertInstanceOf(InjectionRule.class, updatedResources.get(0));
        assertEquals(new LinkedHashSet<>(Set.of("snippet-b")), updatedRule.getSnippetIds());
        assertEquals("latest description", updatedRule.getDescription());

        CodeSnippet finalizedSnippet = assertInstanceOf(CodeSnippet.class, updatedResources.get(1));
        assertTrue(finalizedSnippet.getMetadata().getFinalizers() == null
                || !finalizedSnippet.getMetadata().getFinalizers()
                .contains(CodeSnippetLifecycleService.DELETION_FINALIZER));
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
        verify(client).fetch(InjectionRule.class, "rule-a");
    }

    // why: 若资源已不存在，说明删除已经完成或被别处清理；协调器应直接退出，
    // 不能把“最终一致已达成”的情况误判成失败重试。
    @Test
    void shouldIgnoreMissingSnippetDuringReconcile() {
        when(client.fetch(CodeSnippet.class, "snippet-a")).thenReturn(Optional.empty());

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        verify(client, never()).list(eq(InjectionRule.class), any(), eq(null));
        verify(client, never()).update(any(CodeSnippet.class));
    }

    // why: 删除协调器也属于后台写路径；若规则在摘引用过程中发生版本冲突，
    // 必须显式 refetch 最新资源再重试，而不是把正确性隐含押给底层最后写入行为。
    @Test
    void shouldRefetchLatestRuleWhenDetachingSnippetHitsConflict() {
        CodeSnippet deletingSnippet = deletingSnippet("snippet-a");
        InjectionRule listedRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        InjectionRule staleRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        staleRule.getMetadata().setVersion(2L);
        InjectionRule latestRule = rule("rule-a", Set.of("snippet-a", "snippet-b", "snippet-c"));
        latestRule.getMetadata().setVersion(3L);
        CodeSnippet latestSnippet = deletingSnippet("snippet-a");

        when(client.fetch(CodeSnippet.class, "snippet-a"))
                .thenReturn(Optional.of(deletingSnippet))
                .thenReturn(Optional.of(latestSnippet));
        when(client.list(eq(InjectionRule.class), any(), eq(null))).thenReturn(List.of(listedRule));
        when(client.fetch(InjectionRule.class, "rule-a"))
                .thenReturn(Optional.of(staleRule))
                .thenReturn(Optional.of(latestRule));
        doThrow(conflict())
                .doAnswer(invocation -> invocation.getArgument(0))
                .when(client).update(any(InjectionRule.class));
        doAnswer(invocation -> invocation.getArgument(0))
                .when(client).update(any(CodeSnippet.class));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(InjectionRule.class);
        verify(client, times(2)).update(updateCaptor.capture());
        List<InjectionRule> updatedRules = updateCaptor.getAllValues();

        assertEquals(new LinkedHashSet<>(Set.of("snippet-b")), updatedRules.get(0).getSnippetIds());
        assertEquals(new LinkedHashSet<>(Set.of("snippet-b", "snippet-c")), updatedRules.get(1).getSnippetIds());
        verify(client, times(2)).fetch(InjectionRule.class, "rule-a");
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
    }

    private CodeSnippet deletingSnippet(String id) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        metadata.setDeletionTimestamp(Instant.now());
        metadata.setFinalizers(new LinkedHashSet<>(Set.of(CodeSnippetLifecycleService.DELETION_FINALIZER)));
        snippet.setMetadata(metadata);
        return snippet;
    }

    private InjectionRule rule(String id, Set<String> snippetIds) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setSnippetIds(new LinkedHashSet<>(snippetIds));
        return rule;
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "conflict");
    }
}
