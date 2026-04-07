package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class CodeSnippetLifecycleService {
    static final String DELETION_FINALIZER = "injector.erzbir.com/code-snippet-reference-cleanup";

    private final ReactiveExtensionClient client;

    public CodeSnippetLifecycleService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * why: 删除清理依赖 Halo finalizer 才能把“先标记删除，再异步摘引用，最后真正删除”
     * 收敛成平台原生生命周期；因此代码块在创建/更新落库前就要补齐 finalizer。
     */
    public CodeSnippet prepareForPersist(CodeSnippet snippet) {
        ensureMetadata(snippet);
        addDeletionFinalizer(snippet);
        return snippet;
    }

    /**
     * why: 删除请求本身只负责让资源进入 Halo deleting 生命周期；
     * 真正的摘引用和最终删除由后端 reconciler 接手，避免再次回到脆弱的同步补偿写。
     */
    public Mono<Void> markForDeletion(CodeSnippet snippet) {
        ensureMetadata(snippet);
        if (ExtensionUtil.isDeleted(snippet)) {
            return Mono.empty();
        }
        Mono<CodeSnippet> snippetToDelete = hasDeletionFinalizer(snippet)
                ? Mono.just(snippet)
                : client.update(prepareForPersist(snippet));
        return snippetToDelete.flatMap(client::delete).then();
    }

    static boolean hasDeletionFinalizer(CodeSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null || snippet.getMetadata().getFinalizers() == null) {
            return false;
        }
        return snippet.getMetadata().getFinalizers().contains(DELETION_FINALIZER);
    }

    static boolean isDeletionPendingCleanup(CodeSnippet snippet) {
        return snippet != null
                && ExtensionUtil.isDeleted(snippet)
                && hasDeletionFinalizer(snippet);
    }

    static void addDeletionFinalizer(CodeSnippet snippet) {
        ExtensionUtil.addFinalizers(snippet.getMetadata(), Set.of(DELETION_FINALIZER));
    }

    static void removeDeletionFinalizer(CodeSnippet snippet) {
        ExtensionUtil.removeFinalizers(snippet.getMetadata(), Set.of(DELETION_FINALIZER));
        if (snippet.getMetadata().getFinalizers() == null) {
            return;
        }
        snippet.getMetadata().setFinalizers(new LinkedHashSet<>(snippet.getMetadata().getFinalizers()));
    }

    private void ensureMetadata(CodeSnippet snippet) {
        if (snippet.getMetadata() == null) {
            snippet.setMetadata(new Metadata());
        }
    }
}
