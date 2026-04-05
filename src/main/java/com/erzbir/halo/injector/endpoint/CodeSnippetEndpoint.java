package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.scheme.CodeSnippet;
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

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@RequiredArgsConstructor
public class CodeSnippetEndpoint implements CustomEndpoint {
    private static final String READ_API_VERSION = "injector.erzbir.com/v1alpha1";

    private final ReactiveExtensionClient client;
    private final CodeSnippetValidator validator;
    private final SortOrderEndpointSupport sortOrderEndpointSupport;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/codeSnippets"), this::createSnippet)
                .andRoute(PUT("/codeSnippets/reorder"), request ->
                        sortOrderEndpointSupport.reorder(request, CodeSnippet.class, "代码块", CodeSnippet::setSortOrder))
                .andRoute(PUT("/codeSnippets/{name}"), this::updateSnippet);
    }

    /**
     * why: 代码块创建也统一走 console 写接口，保证控制台导入、表单提交和脚本调用共享同一套落库校验。
     */
    private Mono<ServerResponse> createSnippet(ServerRequest request) {
        return request.bodyToMono(CodeSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(validator::validateForWrite)
                .flatMap(client::create)
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
        return request.bodyToMono(CodeSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .filter(snippet -> snippet.getMetadata() != null
                        && snippet.getMetadata().getName() != null
                        && Objects.equals(snippet.getMetadata().getName(), name))
                .switchIfEmpty(Mono.error(new CodeSnippetValidationException("metadata.name 与路径参数不一致")))
                .flatMap(validator::validateForWrite)
                .flatMap(client::update)
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    @Override
    public GroupVersion groupVersion() {
        // 代码块写接口与排序接口统一挂到 console 分组下，目的是在落库前插入插件自定义校验；
        // 读路径仍然走扩展资源标准接口，避免影响现有查询链路。
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
