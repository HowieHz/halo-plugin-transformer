package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.service.ResourceRelationWriteService;
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
    private final ResourceRelationWriteService relationWriteService;
    private final InjectionRuleManager ruleManager;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/codeSnippets"), this::createSnippet)
                .andRoute(PUT("/codeSnippets/{name}"), this::updateSnippet)
                .andRoute(DELETE("/codeSnippets/{name}"), this::deleteSnippet);
    }

    /**
     * why: 代码块创建也统一走 console 写接口，保证控制台导入、表单提交和脚本调用共享同一套落库校验。
     */
    private Mono<ServerResponse> createSnippet(ServerRequest request) {
        return request.bodyToMono(CodeSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(validator::validateForWrite)
                .flatMap(relationWriteService::createSnippetWithRelations)
                .doOnSuccess(created -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(created -> ServerResponse.created(URI.create("/apis/" + READ_API_VERSION + "/codeSnippets/"
                                + created.getMetadata().getName()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(created));
    }

    /**
     * why: 更新接口同样要强制 metadata.name 与路径参数一致，
     * 避免客户端借 update 把内容写到另一条资源上。
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
                    return tuple;
                })
                .flatMap(tuple -> validator.validateForWrite(tuple.getT2())
                        .then(relationWriteService.updateSnippetWithRelations(tuple.getT1(), tuple.getT2())))
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 删除代码块也必须同步清掉规则侧残留引用；
     * 否则前端重新拉取规则时，仍会看到已删除代码块挂在某些规则上。
     */
    private Mono<ServerResponse> deleteSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(CodeSnippet.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的代码块")))
                .flatMap(relationWriteService::deleteSnippetWithRelations)
                .doOnSuccess(ignored -> ruleManager.invalidateAndWarmUpAsync())
                .then(ServerResponse.noContent().build());
    }

    @Override
    public GroupVersion groupVersion() {
        // 代码块写接口与排序接口统一挂到 console 分组下，目的是在落库前插入插件自定义校验；
        // 读路径仍然走扩展资源标准接口，避免影响现有查询链路。
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
