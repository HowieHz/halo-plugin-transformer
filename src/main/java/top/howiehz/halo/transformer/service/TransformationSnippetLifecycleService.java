package top.howiehz.halo.transformer.service;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

@Component
public class TransformationSnippetLifecycleService {
    private final ReactiveExtensionClient client;

    public TransformationSnippetLifecycleService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * why: 删除请求本身只负责让资源进入 Halo “删除中”生命周期；
     * 真正的摘引用和最终删除由后端 reconciler 接手，避免再次回到脆弱的同步补偿写。
     */
    public Mono<Void> markForDeletion(TransformationSnippet snippet) {
        if (ExtensionUtil.isDeleted(snippet)) {
            return Mono.empty();
        }
        Mono<TransformationSnippet> snippetToDelete =
            TransformationSnippetLifecycleRules.hasDeletionFinalizer(snippet)
                ? Mono.just(snippet)
                : client.update(TransformationSnippetLifecycleRules.prepareForPersist(snippet));
        return snippetToDelete.flatMap(client::delete).then();
    }
}
