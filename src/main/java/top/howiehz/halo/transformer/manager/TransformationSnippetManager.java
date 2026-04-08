package top.howiehz.halo.transformer.manager;

import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ExtensionUtil;

@Slf4j
@Component
public class TransformationSnippetManager {

    private final ReactiveExtensionClient client;

    public TransformationSnippetManager(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<TransformationSnippet> get(String id) {
        return client.fetch(TransformationSnippet.class, id)
                .filter(snippet -> !ExtensionUtil.isDeleted(snippet));
    }
}
