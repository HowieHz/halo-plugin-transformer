package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Extension;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSnippetDeletionReconcilerTest {
    private ExtensionClient client;
    private InjectionRuleManager ruleManager;
    private CodeSnippetDeletionReconciler reconciler;

    @BeforeEach
    void setUp() {
        client = mock(ExtensionClient.class);
        ruleManager = mock(InjectionRuleManager.class);
        reconciler = new CodeSnippetDeletionReconciler(client, ruleManager);
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
        verify(ruleManager).invalidateAndWarmUpAsync();
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
}
