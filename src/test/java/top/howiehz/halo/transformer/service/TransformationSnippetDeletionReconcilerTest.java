package top.howiehz.halo.transformer.service;

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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

class TransformationSnippetDeletionReconcilerTest {
    private ExtensionClient client;
    private TransformationRuleRuntimeStore ruleRuntimeStore;
    private TransformationSnippetDeletionReconciler reconciler;

    @BeforeEach
    void setUp() {
        client = mock(ExtensionClient.class);
        ruleRuntimeStore = mock(TransformationRuleRuntimeStore.class);
        reconciler = new TransformationSnippetDeletionReconciler(client, ruleRuntimeStore);
    }

    // why: finalizer 协调器的核心职责，就是在 Halo 标记“删除中”后先摘掉规则真源里的引用，
    // 再移除 finalizer 让平台完成最终删除；这条主链路必须被测试锁住。
    @Test
    void shouldDetachReferencingRulesBeforeRemovingSnippetFinalizer() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationRule listedRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        TransformationRule latestRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        latestRule.setDescription("latest description");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(deletingSnippet("snippet-a")));
        when(client.list(eq(TransformationRule.class), any(), eq(null))).thenReturn(
            List.of(listedRule));
        when(client.fetch(TransformationRule.class, "rule-a")).thenReturn(Optional.of(latestRule));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Extension.class);
        verify(client, atLeastOnce()).update(updateCaptor.capture());
        List<Extension> updatedResources = updateCaptor.getAllValues();

        TransformationRule updatedRule =
            assertInstanceOf(TransformationRule.class, updatedResources.get(0));
        assertEquals(new LinkedHashSet<>(Set.of("snippet-b")), updatedRule.getSnippetIds());
        assertEquals("latest description", updatedRule.getDescription());

        TransformationSnippet finalizedSnippet =
            assertInstanceOf(TransformationSnippet.class, updatedResources.get(1));
        assertTrue(finalizedSnippet.getMetadata().getFinalizers() == null
            || !finalizedSnippet.getMetadata().getFinalizers()
            .contains(TransformationSnippetLifecycleService.DELETION_FINALIZER));
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
        verify(client).fetch(TransformationRule.class, "rule-a");
    }

    // why: 若资源已不存在，说明删除已经完成或被别处清理；协调器应直接退出，
    // 不能把“最终一致已达成”的情况误判成失败重试。
    @Test
    void shouldIgnoreMissingSnippetDuringReconcile() {
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Optional.empty());

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        verify(client, never()).list(eq(TransformationRule.class), any(), eq(null));
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    // why: 删除协调器也属于后台写路径；若规则在摘引用过程中发生版本冲突，
    // 必须显式 refetch 最新资源再重试，而不是把正确性隐含押给底层最后写入行为。
    @Test
    void shouldRefetchLatestRuleWhenDetachingSnippetHitsConflict() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationRule listedRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        TransformationRule staleRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        staleRule.getMetadata().setVersion(2L);
        TransformationRule latestRule =
            rule("rule-a", Set.of("snippet-a", "snippet-b", "snippet-c"));
        latestRule.getMetadata().setVersion(3L);
        TransformationSnippet latestSnippet = deletingSnippet("snippet-a");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(latestSnippet));
        when(client.list(eq(TransformationRule.class), any(), eq(null))).thenReturn(
            List.of(listedRule));
        when(client.fetch(TransformationRule.class, "rule-a"))
            .thenReturn(Optional.of(staleRule))
            .thenReturn(Optional.of(latestRule));
        doThrow(conflict())
            .doAnswer(invocation -> invocation.getArgument(0))
            .when(client).update(any(TransformationRule.class));
        doAnswer(invocation -> invocation.getArgument(0))
            .when(client).update(any(TransformationSnippet.class));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(TransformationRule.class);
        verify(client, times(2)).update(updateCaptor.capture());
        List<TransformationRule> updatedRules = updateCaptor.getAllValues();

        assertEquals(new LinkedHashSet<>(Set.of("snippet-b")), updatedRules.get(0).getSnippetIds());
        assertEquals(new LinkedHashSet<>(Set.of("snippet-b", "snippet-c")),
            updatedRules.get(1).getSnippetIds());
        verify(client, times(2)).fetch(TransformationRule.class, "rule-a");
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
    }

    private TransformationSnippet deletingSnippet(String id) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        metadata.setDeletionTimestamp(Instant.now());
        metadata.setFinalizers(
            new LinkedHashSet<>(Set.of(TransformationSnippetLifecycleService.DELETION_FINALIZER)));
        snippet.setMetadata(metadata);
        return snippet;
    }

    private TransformationRule rule(String id, Set<String> snippetIds) {
        TransformationRule rule = new TransformationRule();
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
