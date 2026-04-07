package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.service.CodeSnippetLifecycleService;
import com.erzbir.halo.injector.util.CodeSnippetValidationException;
import com.erzbir.halo.injector.util.CodeSnippetValidator;
import com.erzbir.halo.injector.util.OptimisticConcurrencyGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
    private final CodeSnippetLifecycleService lifecycleService;
    private final InjectionRuleManager ruleManager;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/codeSnippets"), this::createSnippet)
                .andRoute(PUT("/codeSnippets/{name}"), this::updateSnippet)
                .andRoute(PUT("/codeSnippets/{name}/enabled"), this::updateSnippetEnabled)
                .andRoute(DELETE("/codeSnippets/{name}"), this::deleteSnippet);
    }

    /**
     * why: 代码块已经不再承担关系真源；创建接口只负责校验并写入代码块本体，
     * 不再暗中补写规则，避免再次把关系写复杂。
     */
    private Mono<ServerResponse> createSnippet(ServerRequest request) {
        return request.bodyToMono(CodeSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .map(lifecycleService::prepareForPersist)
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
                    CodeSnippet existing = tuple.getT1();
                    CodeSnippet snippet = tuple.getT2();
                    if (snippet.getMetadata() == null
                            || snippet.getMetadata().getName() == null
                            || !Objects.equals(snippet.getMetadata().getName(), name)) {
                        throw new CodeSnippetValidationException("metadata.name 与路径参数不一致");
                    }
                    OptimisticConcurrencyGuard.requireMatchingVersion(
                            existing.getMetadata(),
                            snippet.getMetadata(),
                            "代码块"
                    );
                    return lifecycleService.prepareForPersist(snippet);
                })
                .flatMap(validator::validateForWrite)
                .flatMap(client::update)
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 启停只应修改 `enabled` 本身，不能顺手带上整份编辑草稿；
     * 因此这里提供独立写口，显式表达“仅切换启停状态”的语义。
     */
    private Mono<ServerResponse> updateSnippetEnabled(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(EnabledPayload.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(payload -> updateSnippetEnabled(name, payload))
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 删除代码块改为走 Halo finalizer 生命周期；
     * endpoint 只负责把资源送入 deleting 状态，后续摘引用与最终删除由后台 reconciler 完成。
     */
    private Mono<ServerResponse> deleteSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(CodeSnippet.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的代码块")))
                .flatMap(lifecycleService::markForDeletion)
                .doOnSuccess(ignored -> ruleManager.invalidateAndWarmUpAsync())
                .then(ServerResponse.noContent().build());
    }

    /**
     * why: 当用户只是切换启停时，后端必须基于已保存资源本体处理，
     * 不能让前端未保存草稿混进来，避免“启用偷偷保存 / 停用偷偷丢稿”。
     */
    Mono<CodeSnippet> updateSnippetEnabled(String name, EnabledPayload payload) {
        Boolean enabled = payload.enabled;
        if (enabled == null) {
            return Mono.error(new ServerWebInputException("enabled 不能为空"));
        }
        if (!StringUtils.hasText(name)) {
            return Mono.error(new ServerWebInputException("未找到要更新的代码块"));
        }
        return client.fetch(CodeSnippet.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的代码块")))
                .flatMap(snippet -> {
                    OptimisticConcurrencyGuard.requireMatchingVersion(
                            snippet.getMetadata(),
                            payload.metadata,
                            "代码块"
                    );
                    lifecycleService.prepareForPersist(snippet);
                    snippet.setEnabled(enabled);
                    return enabled ? validator.validateForWrite(snippet) : Mono.just(snippet);
                })
                .flatMap(client::update)
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync());
    }

    @lombok.Data
    static final class EnabledPayload {
        private Boolean enabled;
        private run.halo.app.extension.Metadata metadata;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
