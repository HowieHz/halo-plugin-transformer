package top.howiehz.halo.transformer.service;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

@Component
public class TransformationSnippetLifecycleService {
    static final String DELETION_FINALIZER =
        "transformer.howiehz.top/code-snippet-reference-cleanup";

    private final ReactiveExtensionClient client;

    public TransformationSnippetLifecycleService(ReactiveExtensionClient client) {
        this.client = client;
    }

    static boolean hasDeletionFinalizer(TransformationSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null
            || snippet.getMetadata().getFinalizers() == null) {
            return false;
        }
        return snippet.getMetadata().getFinalizers().contains(DELETION_FINALIZER);
    }

    static boolean isDeletionPendingCleanup(TransformationSnippet snippet) {
        return snippet != null
            && ExtensionUtil.isDeleted(snippet)
            && hasDeletionFinalizer(snippet);
    }

    static void addDeletionFinalizer(TransformationSnippet snippet) {
        ExtensionUtil.addFinalizers(snippet.getMetadata(), Set.of(DELETION_FINALIZER));
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
     * why: 删除清理依赖 Halo finalizer 才能把“先标记删除，再异步摘引用，最后真正删除”
     * 收敛成平台原生生命周期；因此代码片段在创建/更新落库前就要补齐 finalizer。
     */
    public TransformationSnippet prepareForPersist(TransformationSnippet snippet) {
        ensureMetadata(snippet);
        addDeletionFinalizer(snippet);
        return snippet;
    }

    /**
     * why: 删除请求本身只负责让资源进入 Halo deleting 生命周期；
     * 真正的摘引用和最终删除由后端 reconciler 接手，避免再次回到脆弱的同步补偿写。
     */
    public Mono<Void> markForDeletion(TransformationSnippet snippet) {
        ensureMetadata(snippet);
        if (ExtensionUtil.isDeleted(snippet)) {
            return Mono.empty();
        }
        Mono<TransformationSnippet> snippetToDelete = hasDeletionFinalizer(snippet)
            ? Mono.just(snippet)
            : client.update(prepareForPersist(snippet));
        return snippetToDelete.flatMap(client::delete).then();
    }

    private void ensureMetadata(TransformationSnippet snippet) {
        if (snippet.getMetadata() == null) {
            snippet.setMetadata(new Metadata());
        }
    }
}

