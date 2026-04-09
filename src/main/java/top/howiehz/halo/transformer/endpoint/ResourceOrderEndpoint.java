package top.howiehz.halo.transformer.endpoint;

import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.manager.TransformationSnippetRuntimeStore;
import top.howiehz.halo.transformer.scheme.ResourceOrder;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

@Component
@RequiredArgsConstructor
public class ResourceOrderEndpoint implements CustomEndpoint {
    private final ResourceOrderService resourceOrderService;
    private final TransformationSnippetRuntimeStore snippetRuntimeStore;
    private final TransformationRuleRuntimeStore ruleRuntimeStore;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(PUT("/snippet-order"), request ->
            putOrder(request, ResourceOrderService.SNIPPET_ORDER_NAME, "代码片段",
                snippetRuntimeStore::listVisibleSnippets,
                TransformationSnippet::getName))
            .andRoute(PUT("/rule-order"), request ->
                putOrder(request, ResourceOrderService.RULE_ORDER_NAME, "转换规则",
                    ruleRuntimeStore::listVisibleRules,
                    TransformationRule::getName));
    }

    private <T extends AbstractExtension> Mono<ServerResponse> putOrder(ServerRequest request,
        String orderName,
        String resourceLabel, Supplier<List<T>> visibleResourcesSupplier,
        Function<T, String> displayNameGetter) {
        return request.bodyToMono(OrderPayload.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .flatMap(payload -> resourceOrderService.saveOrder(payload, orderName, resourceLabel,
                visibleResourcesSupplier,
                displayNameGetter))
            .flatMap(orderState -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(orderState));
    }

    ResourceOrder newResourceOrder(String orderName) {
        return resourceOrderService.newResourceOrder(orderName);
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizePayload(OrderPayload payload,
        String resourceLabel,
        List<T> resources,
        Function<T, String> displayNameGetter) {
        return resourceOrderService.sanitizePayload(payload, resourceLabel, resources,
            displayNameGetter);
    }

    Map<String, Integer> validateIncomingOrders(Map<String, Integer> incomingOrders,
        String resourceLabel) {
        return resourceOrderService.validateIncomingOrders(incomingOrders, resourceLabel);
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizeOrders(
        Map<String, Integer> sourceOrders,
        List<T> resources,
        Function<T, String> displayNameGetter) {
        return resourceOrderService.sanitizeOrders(sourceOrders, resources, displayNameGetter);
    }

    <T extends AbstractExtension> String resolveDisplayName(T resource,
        Function<T, String> displayNameGetter) {
        return resourceOrderService.resolveDisplayName(resource, displayNameGetter);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.transformer.howiehz.top/v1alpha1");
    }

    @lombok.Data
    static class OrderPayload {
        private Map<String, Integer> orders = new LinkedHashMap<>();
        private Metadata metadata;
    }

}
