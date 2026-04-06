package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.scheme.ResourceOrder;
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
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@RequiredArgsConstructor
public class ResourceOrderEndpoint implements CustomEndpoint {
    private static final String SNIPPET_ORDER_NAME = "snippet-order";
    private static final String RULE_ORDER_NAME = "rule-order";

    private final ReactiveExtensionClient client;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/snippet-order"), request ->
                getOrder(request, SNIPPET_ORDER_NAME, CodeSnippet.class, CodeSnippet::getName))
                .andRoute(PUT("/snippet-order"), request ->
                        putOrder(request, SNIPPET_ORDER_NAME, "代码块", CodeSnippet.class,
                                CodeSnippet::getName))
                .andRoute(GET("/rule-order"), request ->
                        getOrder(request, RULE_ORDER_NAME, InjectionRule.class, InjectionRule::getName))
                .andRoute(PUT("/rule-order"), request ->
                        putOrder(request, RULE_ORDER_NAME, "注入规则", InjectionRule.class,
                                InjectionRule::getName));
    }

    private <T extends AbstractExtension> Mono<ServerResponse> getOrder(ServerRequest request, String orderName,
                                                                        Class<T> resourceType,
                                                                        Function<T, String> displayNameGetter) {
        return buildEffectiveOrders(orderName, resourceType, displayNameGetter)
                .flatMap(orders -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(orders));
    }

    private <T extends AbstractExtension> Mono<ServerResponse> putOrder(ServerRequest request, String orderName,
                                                                        String resourceLabel, Class<T> resourceType,
                                                                        Function<T, String> displayNameGetter) {
        return request.bodyToMono(OrderPayload.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(payload -> saveOrder(payload, orderName, resourceLabel, resourceType,
                        displayNameGetter))
                .flatMap(orders -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(orders));
    }

    <T extends AbstractExtension> Mono<Map<String, Integer>> buildEffectiveOrders(String orderName,
                                                                                  Class<T> resourceType,
                                                                                  Function<T, String> displayNameGetter) {
        return Mono.zip(
                        client.list(resourceType, null, null).collectList(),
                        findStoredOrder(orderName).defaultIfEmpty(new ResourceOrder())
                )
                .map(tuple -> {
                    List<T> resources = tuple.getT1();
                    ResourceOrder storedOrder = tuple.getT2();
                    Map<String, Integer> sourceOrders =
                            storedOrder.getOrders() == null ? Map.of() : storedOrder.getOrders();
                    return sanitizeOrders(sourceOrders, resources, displayNameGetter);
                });
    }

    <T extends AbstractExtension> Mono<Map<String, Integer>> saveOrder(OrderPayload payload, String orderName,
                                                                       String resourceLabel, Class<T> resourceType,
                                                                       Function<T, String> displayNameGetter) {
        return client.list(resourceType, null, null)
                .collectList()
                .flatMap(resources -> {
                    Map<String, Integer> sanitizedOrders = sanitizePayload(payload, resourceLabel, resources,
                            displayNameGetter);
                    return findStoredOrder(orderName)
                            .defaultIfEmpty(newResourceOrder(orderName))
                            .flatMap(order -> {
                                order.setOrders(sanitizedOrders);
                                return persistOrder(order);
                            })
                            .thenReturn(sanitizedOrders);
                });
    }

    Mono<ResourceOrder> persistOrder(ResourceOrder order) {
        return findStoredOrder(order.getMetadata().getName())
                .flatMap(existing -> {
                    order.setMetadata(existing.getMetadata());
                    return client.update(order);
                })
                .switchIfEmpty(client.create(order));
    }

    Mono<ResourceOrder> findStoredOrder(String orderName) {
        return client.list(ResourceOrder.class, null, null)
                .filter(order -> order.getMetadata() != null
                        && Objects.equals(order.getMetadata().getName(), orderName))
                .next();
    }

    ResourceOrder newResourceOrder(String orderName) {
        ResourceOrder order = new ResourceOrder();
        order.setApiVersion("injector.erzbir.com/v1alpha1");
        order.setKind("ResourceOrder");
        Metadata metadata = new Metadata();
        metadata.setName(orderName);
        order.setMetadata(metadata);
        order.setOrders(new LinkedHashMap<>());
        return order;
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizePayload(OrderPayload payload,
                                                                       String resourceLabel,
                                                                       List<T> resources,
                                                                       Function<T, String> displayNameGetter) {
        if (payload.getOrders() == null) {
            throw new ServerWebInputException(resourceLabel + "排序映射不能为空");
        }
        return sanitizeOrders(validateIncomingOrders(payload.getOrders(), resourceLabel), resources,
                displayNameGetter);
    }

    Map<String, Integer> validateIncomingOrders(Map<String, Integer> incomingOrders, String resourceLabel) {
        Map<String, Integer> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : incomingOrders.entrySet()) {
            String id = entry.getKey();
            Integer orderValue = entry.getValue();
            if (!StringUtils.hasText(id)) {
                throw new ServerWebInputException(resourceLabel + "排序项的 id 不能为空");
            }
            if (orderValue == null) {
                throw new ServerWebInputException(resourceLabel + "排序项 " + id + " 的值不能为空");
            }
            if (orderValue < 0) {
                throw new ServerWebInputException(resourceLabel + "排序项 " + id + " 的值不能小于 0");
            }
            if (orderValue == 0) {
                continue;
            }
            sanitized.put(id, orderValue);
        }
        return sanitized;
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizeOrders(Map<String, Integer> sourceOrders,
                                                                      List<T> resources,
                                                                      Function<T, String> displayNameGetter) {
        Map<String, T> resourceById = resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> resource.getMetadata() != null
                        && StringUtils.hasText(resource.getMetadata().getName()))
                .collect(Collectors.toMap(resource -> resource.getMetadata().getName(), Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<Map.Entry<String, Integer>> validEntries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sourceOrders.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (!resourceById.containsKey(entry.getKey())) {
                continue;
            }
            validEntries.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        validEntries.sort(Comparator
                .comparingInt(Map.Entry<String, Integer>::getValue)
                .thenComparing(entry -> resolveDisplayName(resourceById.get(entry.getKey()), displayNameGetter),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> sanitized = new LinkedHashMap<>();
        validEntries.forEach(entry -> sanitized.put(entry.getKey(), entry.getValue()));
        return sanitized;
    }

    <T extends AbstractExtension> String resolveDisplayName(T resource, Function<T, String> displayNameGetter) {
        if (resource == null) {
            return "";
        }
        String displayName = displayNameGetter.apply(resource);
        if (StringUtils.hasText(displayName)) {
            return displayName;
        }
        return resource.getMetadata() != null ? resource.getMetadata().getName() : "";
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }

    @lombok.Data
    static class OrderPayload {
        private Map<String, Integer> orders = new LinkedHashMap<>();
    }
}
