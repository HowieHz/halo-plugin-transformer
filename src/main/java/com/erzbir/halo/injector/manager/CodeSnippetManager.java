package com.erzbir.halo.injector.manager;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ExtensionUtil;

@Slf4j
@Component
public class CodeSnippetManager {

    private final ReactiveExtensionClient client;

    public CodeSnippetManager(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<CodeSnippet> get(String id) {
        return client.fetch(CodeSnippet.class, id)
                .filter(snippet -> !ExtensionUtil.isDeleted(snippet));
    }
}
