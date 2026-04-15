package top.howiehz.halo.transformer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.extension.TransformationSnippet;
import top.howiehz.halo.transformer.runtime.store.TransformationRuleRuntimeStore;

class TransformationSnippetDeletionReconcilerTest {
    private ExtensionClient client;
    private TransformationRuleRuntimeStore ruleRuntimeStore;
    private TransformationSnippetDeletionReconciler reconciler;

    @BeforeEach
    void setUp() {
        client = mock(ExtensionClient.class);
        ruleRuntimeStore = mock(TransformationRuleRuntimeStore.class);
        reconciler = new TransformationSnippetDeletionReconciler(client, ruleRuntimeStore);
        when(ruleRuntimeStore.isReadyForReferenceReads()).thenReturn(true);
    }

    // why: finalizer 协调器的核心职责，就是在 Halo 标记“删除中”后先摘掉规则真源里的引用，
    // 再移除 finalizer 让平台完成最终删除；这条主链路必须被测试锁住。
    @Test
    void shouldDetachReferencingRulesBeforeRemovingSnippetFinalizer() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationRule latestRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        latestRule.setDescription("latest description");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(deletingSnippet("snippet-a")));
        when(ruleRuntimeStore.listVisibleRuleNamesReferencingSnippet("snippet-a"))
            .thenReturn(List.of("rule-a"));
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
            .contains(TransformationSnippetLifecycleRules.DELETION_FINALIZER));
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
        verify(client).fetch(TransformationRule.class, "rule-a");
    }

    // why: 若资源已不存在，说明删除已经完成或被别处清理；协调器应直接退出，
    // 不能把“最终一致已达成”的情况误判成失败重试。
    @Test
    void shouldIgnoreMissingSnippetDuringReconcile() {
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Optional.empty());

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        verify(ruleRuntimeStore, never()).listVisibleRuleNamesReferencingSnippet("snippet-a");
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    // why: watch-driven rule快照在启动期尚未完成首轮 warm-up 时，
    // 空快照只表示“暂未就绪”，不能被误当成“已无引用”去提前移除 snippet finalizer。
    @Test
    void shouldRequeueWhenRuleRuntimeSnapshotIsNotReady() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet));
        when(ruleRuntimeStore.isReadyForReferenceReads()).thenReturn(false);

        Reconciler.Result result = reconciler.reconcile(new Reconciler.Request("snippet-a"));

        assertTrue(result.reEnqueue());
        assertEquals(Duration.ofMillis(250), result.retryAfter());
        verify(ruleRuntimeStore, never()).listVisibleRuleNamesReferencingSnippet("snippet-a");
        verify(client, never()).update(any(TransformationRule.class));
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    // why: 删除协调器也属于后台写路径；若规则在摘引用过程中发生版本冲突，
    // 必须显式 refetch 最新资源再重试，而不是把正确性隐含押给底层最后写入行为。
    @Test
    void shouldRefetchLatestRuleWhenDetachingSnippetHitsConflict() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationRule staleRule = rule("rule-a", Set.of("snippet-a", "snippet-b"));
        staleRule.getMetadata().setVersion(2L);
        TransformationRule latestRule =
            rule("rule-a", Set.of("snippet-a", "snippet-b", "snippet-c"));
        latestRule.getMetadata().setVersion(3L);
        TransformationSnippet latestSnippet = deletingSnippet("snippet-a");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(latestSnippet));
        when(ruleRuntimeStore.listVisibleRuleNamesReferencingSnippet("snippet-a"))
            .thenReturn(List.of("rule-a"));
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

    // why: 历史规则里即使残留了带空格的 snippet 引用，删除协调器也必须沿用后端同一份规范化语义，
    // 否则“删除中”代码片段会因为字符串脏形状不同而摘不掉引用。
    @Test
    void shouldDetachWhitespacePaddedHistoricalSnippetReference() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationRule listedRule =
            rule("rule-a", new LinkedHashSet<>(List.of(" snippet-a ", "snippet-b")));
        TransformationRule latestRule =
            rule("rule-a", new LinkedHashSet<>(List.of(" snippet-a ", "snippet-b")));

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(deletingSnippet("snippet-a")));
        when(ruleRuntimeStore.listVisibleRuleNamesReferencingSnippet("snippet-a"))
            .thenReturn(List.of("rule-a"));
        when(client.fetch(TransformationRule.class, "rule-a")).thenReturn(Optional.of(latestRule));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Extension.class);
        verify(client, atLeastOnce()).update(updateCaptor.capture());
        TransformationRule updatedRule =
            assertInstanceOf(TransformationRule.class, updateCaptor.getAllValues().get(0));

        assertEquals(new LinkedHashSet<>(Set.of("snippet-b")), updatedRule.getSnippetIds());
    }

    // why: 规则一旦进入“删除中”生命周期，就已经退出当前可写资源集合；
    // 协调器不应再尝试改写它的 snippet 引用，否则 finalizer 清理会被无意义的写失败打断。
    @Test
    void shouldIgnoreRulesThatAreAlreadyDeletingWhenDetachingReferences() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(deletingSnippet("snippet-a")));
        when(ruleRuntimeStore.listVisibleRuleNamesReferencingSnippet("snippet-a"))
            .thenReturn(List.of());

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        verify(client, never()).fetch(TransformationRule.class, "rule-a");
        verify(client, never()).update(any(TransformationRule.class));
        verify(client).update(any(TransformationSnippet.class));
        verify(ruleRuntimeStore, never()).invalidateAndWarmUpAsync();
    }

    // why: 扫描到规则名后，它也可能在真正 update 前被别处删除；
    // 这种“目标已不可见”的状态不该卡住 snippet finalizer，而应被视为引用已无需再清理。
    @Test
    void shouldIgnoreMissingRuleDuringDetachUpdateAndStillClearSnippetFinalizer() {
        TransformationSnippet deletingSnippet = deletingSnippet("snippet-a");
        TransformationSnippet latestSnippet = deletingSnippet("snippet-a");

        when(client.fetch(TransformationSnippet.class, "snippet-a"))
            .thenReturn(Optional.of(deletingSnippet))
            .thenReturn(Optional.of(latestSnippet));
        when(ruleRuntimeStore.listVisibleRuleNamesReferencingSnippet("snippet-a"))
            .thenReturn(List.of("rule-a"));
        when(client.fetch(TransformationRule.class, "rule-a"))
            .thenReturn(Optional.of(rule("rule-a", Set.of("snippet-a", "snippet-b"))));
        doThrow(notFound())
            .when(client).update(any(TransformationRule.class));
        doAnswer(invocation -> invocation.getArgument(0))
            .when(client).update(any(TransformationSnippet.class));

        reconciler.reconcile(new Reconciler.Request("snippet-a"));

        verify(client).update(any(TransformationRule.class));
        verify(client).update(any(TransformationSnippet.class));
        verify(ruleRuntimeStore, never()).invalidateAndWarmUpAsync();
    }

    private TransformationSnippet deletingSnippet(String id) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        metadata.setDeletionTimestamp(Instant.now());
        metadata.setFinalizers(
            new LinkedHashSet<>(Set.of(TransformationSnippetLifecycleRules.DELETION_FINALIZER)));
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

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }
}
