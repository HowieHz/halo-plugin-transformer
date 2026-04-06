package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.service.SnippetReferenceService;
import com.erzbir.halo.injector.util.CodeSnippetValidationException;
import com.erzbir.halo.injector.util.CodeSnippetValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

import java.net.URI;
import java.util.Objects;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@RequiredArgsConstructor
public class CodeSnippetEndpoint implements CustomEndpoint {
    private static final String READ_API_VERSION = "injector.erzbir.com/v1alpha1";

    private final ReactiveExtensionClient client;
    private final CodeSnippetValidator validator;
    private final SnippetReferenceService snippetReferenceService;
    private final InjectionRuleManager ruleManager;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/codeSnippets"), this::createSnippet)
                .andRoute(PUT("/codeSnippets/{name}"), this::updateSnippet)
                .andRoute(DELETE("/codeSnippets/{name}"), this::deleteSnippet);
    }

    /**
     * why: 代码块已经不再承担关系真源；创建接口只负责校验并写入代码块本体，
     * 不再暗中补写规则，避免再次把关系写复杂。
     */
    private Mono<ServerResponse> createSnippet(ServerRequest request) {
        return request.bodyToMono(CodeSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(validator::validateForWrite)
                .flatMap(client::create)
                .doOnSuccess(created -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(created -> ServerResponse.created(URI.create("/apis/" + READ_API_VERSION + "/codeSnippets/"
                                + created.getMetadata().getName()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(created));
    }

    /**
     * why: 更新接口同样只处理代码块本体；同时强制 `metadata.name` 与路径参数一致，
     * 避免客户端借 update 接口把内容写进另一条资源。
     */
    private Mono<ServerResponse> updateSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(CodeSnippet.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的代码块")))
                .zipWhen(existing -> request.bodyToMono(CodeSnippet.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空"))))
                .map(tuple -> {
                    CodeSnippet snippet = tuple.getT2();
                    if (snippet.getMetadata() == null
                            || snippet.getMetadata().getName() == null
                            || !Objects.equals(snippet.getMetadata().getName(), name)) {
                        throw new CodeSnippetValidationException("metadata.name 与路径参数不一致");
                    }
                    return snippet;
                })
                .flatMap(validator::validateForWrite)
                .flatMap(client::update)
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 唯一真源已经转到规则侧，删除代码块时必须先摘掉规则里的 `snippetIds`，
     * 保证读模型、运行时和控制台都不会再看到悬挂引用。
     */
    private Mono<ServerResponse> deleteSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(CodeSnippet.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的代码块")))
                .flatMap(snippetReferenceService::deleteSnippetAndDetachRules)
                .doOnSuccess(ignored -> ruleManager.invalidateAndWarmUpAsync())
                .then(ServerResponse.noContent().build());
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
