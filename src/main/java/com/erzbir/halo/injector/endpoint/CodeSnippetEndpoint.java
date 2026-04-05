package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@RequiredArgsConstructor
public class CodeSnippetEndpoint implements CustomEndpoint {
    private final SortOrderEndpointSupport sortOrderEndpointSupport;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(PUT("/codeSnippets/reorder"), request ->
                sortOrderEndpointSupport.reorder(request, CodeSnippet.class, "代码块", CodeSnippet::setSortOrder));
    }

    @Override
    public GroupVersion groupVersion() {
        // 排序接口挂到 console 分组下，目的是把“左侧排序”收敛成最小写入面，避免影响普通读写链路。
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
