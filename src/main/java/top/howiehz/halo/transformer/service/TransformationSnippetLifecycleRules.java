package top.howiehz.halo.transformer.service;

import java.util.LinkedHashSet;
import java.util.Set;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

public final class TransformationSnippetLifecycleRules {
    public static final String DELETION_FINALIZER =
        "transformer.howiehz.top/code-snippet-reference-cleanup";

    private TransformationSnippetLifecycleRules() {
    }

    public static boolean hasDeletionFinalizer(TransformationSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null
            || snippet.getMetadata().getFinalizers() == null) {
            return false;
        }
        return snippet.getMetadata().getFinalizers().contains(DELETION_FINALIZER);
    }

    public static boolean isDeletionPendingCleanup(TransformationSnippet snippet) {
        return snippet != null
            && ExtensionUtil.isDeleted(snippet)
            && hasDeletionFinalizer(snippet);
    }

    static void removeDeletionFinalizer(TransformationSnippet snippet) {
        ExtensionUtil.removeFinalizers(snippet.getMetadata(), Set.of(DELETION_FINALIZER));
        if (snippet.getMetadata().getFinalizers() == null) {
            return;
        }
        snippet.getMetadata()
            .setFinalizers(new LinkedHashSet<>(snippet.getMetadata().getFinalizers()));
    }

    /**
     * why: 删除收敛建立在 Halo finalizer 生命周期之上；
     * 因此任何会把 snippet 持久化为“当前权威资源”的写入口，都要先补齐这条生命周期约束。
     */
    public static TransformationSnippet prepareForPersist(TransformationSnippet snippet) {
        ensureMetadata(snippet);
        ExtensionUtil.addFinalizers(snippet.getMetadata(), Set.of(DELETION_FINALIZER));
        return snippet;
    }

    private static void ensureMetadata(TransformationSnippet snippet) {
        if (snippet.getMetadata() == null) {
            snippet.setMetadata(new Metadata());
        }
    }
}
