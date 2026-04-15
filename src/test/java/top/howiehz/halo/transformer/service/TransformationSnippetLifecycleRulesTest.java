package top.howiehz.halo.transformer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

class TransformationSnippetLifecycleRulesTest {
    // why: 新建和更新写口都应把删除 finalizer 当成资源生命周期约束的一部分；
    // 纯规则类需要直接锁住这条约束，避免以后又把它漂回某个 service 的副作用里。
    @Test
    void shouldAttachFinalizerDuringPrepareForPersist() {
        TransformationSnippet snippet = snippet("snippet-a");

        TransformationSnippet prepared = TransformationSnippetLifecycleRules.prepareForPersist(
            snippet);

        assertTrue(prepared.getMetadata().getFinalizers()
            .contains(TransformationSnippetLifecycleRules.DELETION_FINALIZER));
    }

    // why: 只有“已进入 deleting 且仍带 finalizer”的资源，才表示后台清理尚未完成；
    // 这个判定是多个调用点共享的领域规则，必须独立测试。
    @Test
    void shouldRecognizeDeletionPendingCleanupSnippet() {
        TransformationSnippet snippet = snippet("snippet-a");
        snippet.getMetadata().setDeletionTimestamp(Instant.now());
        snippet.getMetadata().setFinalizers(
            new LinkedHashSet<>(Set.of(TransformationSnippetLifecycleRules.DELETION_FINALIZER)));

        assertTrue(TransformationSnippetLifecycleRules.isDeletionPendingCleanup(snippet));
    }

    // why: 只带 deletionTimestamp 而没有 finalizer 的资源，表示不需要再走这条清理收敛链；
    // 不能把所有 deleting 资源都误判成“待清理”。
    @Test
    void shouldRejectDeletingSnippetWithoutLifecycleFinalizer() {
        TransformationSnippet snippet = snippet("snippet-a");
        snippet.getMetadata().setDeletionTimestamp(Instant.now());

        assertFalse(TransformationSnippetLifecycleRules.isDeletionPendingCleanup(snippet));
    }

    private TransformationSnippet snippet(String id) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        return snippet;
    }
}
