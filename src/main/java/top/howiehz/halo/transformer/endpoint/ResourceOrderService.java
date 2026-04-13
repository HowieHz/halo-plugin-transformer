package top.howiehz.halo.transformer.endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.extension.ResourceOrder;
import top.howiehz.halo.transformer.support.OptimisticConcurrencyGuard;

@Component
@RequiredArgsConstructor
public class ResourceOrderService {
    static final String SNIPPET_ORDER_NAME = "snippet-order";
    static final String RULE_ORDER_NAME = "rule-order";

    private final ReactiveExtensionClient client;

    /**
     * why: 控制台左侧的权威读模型现在由“watch-driven 资源快照 + 精确 fetch 的排序资源”组成；
     * 请求线程只做装配，不再自己回源 list 全表，避免把控制台热点重新退化成线性扫描。
     */
    <T extends AbstractExtension> Mono<ResourceCollectionSnapshot<T>> buildCollectionSnapshot(
        String orderName, Supplier<List<T>> visibleResourcesSupplier,
        Function<T, String> displayNameGetter) {
        return Mono.zip(
                Mono.fromSupplier(visibleResourcesSupplier),
                findStoredOrder(orderName).defaultIfEmpty(new ResourceOrder())
            )
            .map(tuple -> {
                List<T> resources = tuple.getT1();
                ResourceOrder storedOrder = tuple.getT2();
                Map<String, Integer> sourceOrders =
                    storedOrder.getOrders() == null ? Map.of() : storedOrder.getOrders();
                return new ResourceCollectionSnapshot<>(
                    listVisibleResources(resources),
                    sanitizeOrders(sourceOrders, resources, displayNameGetter),
                    storedOrder.getMetadata() == null ? null
                        : storedOrder.getMetadata().getVersion()
                );
            });
    }

    <T extends AbstractExtension> Mono<OrderState> saveOrder(
        ResourceOrderEndpoint.OrderPayload payload,
        String orderName, String resourceLabel, Supplier<List<T>> visibleResourcesSupplier,
        Function<T, String> displayNameGetter) {
        return Mono.fromSupplier(visibleResourcesSupplier)
            .flatMap(resources -> {
                Map<String, Integer> sanitizedOrders =
                    sanitizePayload(payload, resourceLabel, resources, displayNameGetter);
                return findStoredOrder(orderName)
                    .defaultIfEmpty(newResourceOrder(orderName))
                    .flatMap(order -> {
                        if (order.getMetadata() != null
                            && order.getMetadata().getVersion() != null) {
                            OptimisticConcurrencyGuard.requireMatchingVersion(
                                order.getMetadata(),
                                payload.getMetadata(),
                                resourceLabel + "排序配置"
                            );
                        }
                        order.setOrders(sanitizedOrders);
                        return persistOrder(order);
                    })
                    .map(saved -> new OrderState(
                        saved.getOrders() == null ? Map.of() : saved.getOrders(),
                        saved.getMetadata() == null ? null : saved.getMetadata().getVersion()
                    ));
            });
    }

    Mono<ResourceOrder> persistOrder(ResourceOrder order) {
        if (order.getMetadata() != null && order.getMetadata().getVersion() != null) {
            return client.update(order);
        }
        return client.create(order);
    }

    /**
     * why: 排序资源本身就是按固定 `metadata.name` 寻址；
     * 这里直接 `fetch` 比“先 list 再过滤”更贴合资源模型，也避免把读取语义写得像一次全表扫描。
     */
    Mono<ResourceOrder> findStoredOrder(String orderName) {
        return client.fetch(ResourceOrder.class, orderName);
    }

    ResourceOrder newResourceOrder(String orderName) {
        ResourceOrder order = new ResourceOrder();
        order.setApiVersion("transformer.howiehz.top/v1alpha1");
        order.setKind("ResourceOrder");
        Metadata metadata = new Metadata();
        metadata.setName(orderName);
        order.setMetadata(metadata);
        order.setOrders(new LinkedHashMap<>());
        return order;
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizePayload(
        ResourceOrderEndpoint.OrderPayload payload,
        String resourceLabel,
        List<T> resources,
        Function<T, String> displayNameGetter) {
        if (payload.getOrders() == null) {
            throw new ServerWebInputException(resourceLabel + "排序映射不能为空");
        }
        return sanitizeOrders(validateIncomingOrders(payload.getOrders(), resourceLabel), resources,
            displayNameGetter);
    }

    Map<String, Integer> validateIncomingOrders(Map<String, Integer> incomingOrders,
        String resourceLabel) {
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
                throw new ServerWebInputException(
                    resourceLabel + "排序项 " + id + " 的值不能小于 0");
            }
            if (orderValue == 0) {
                continue;
            }
            sanitized.put(id, orderValue);
        }
        return sanitized;
    }

    <T extends AbstractExtension> List<T> listVisibleResources(List<T> resources) {
        return resources.stream()
            .filter(Objects::nonNull)
            .filter(resource -> !ExtensionUtil.isDeleted(resource))
            .filter(resource -> resource.getMetadata() != null
                && StringUtils.hasText(resource.getMetadata().getName()))
            .toList();
    }

    <T extends AbstractExtension> Map<String, Integer> sanitizeOrders(
        Map<String, Integer> sourceOrders,
        List<T> resources,
        Function<T, String> displayNameGetter) {
        Map<String, T> resourceById = listVisibleResources(resources).stream()
            .collect(Collectors.toMap(resource -> resource.getMetadata().getName(),
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
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
            .thenComparing(
                entry -> resolveDisplayName(resourceById.get(entry.getKey()), displayNameGetter),
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> sanitized = new LinkedHashMap<>();
        for (int index = 0; index < validEntries.size(); index++) {
            sanitized.put(validEntries.get(index).getKey(), index + 1);
        }
        return sanitized;
    }

    <T extends AbstractExtension> String resolveDisplayName(T resource,
        Function<T, String> displayNameGetter) {
        if (resource == null) {
            return "";
        }
        String displayName = displayNameGetter.apply(resource);
        if (StringUtils.hasText(displayName)) {
            return displayName;
        }
        return resource.getMetadata() != null ? resource.getMetadata().getName() : "";
    }

    record OrderState(Map<String, Integer> orders, Long version) {
    }

    record ResourceCollectionSnapshot<T>(List<T> resources, Map<String, Integer> orders,
                                         Long orderVersion) {
    }
}
