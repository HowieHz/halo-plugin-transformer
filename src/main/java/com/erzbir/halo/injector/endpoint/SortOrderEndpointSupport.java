package com.erzbir.halo.injector.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
public class SortOrderEndpointSupport {
    private final ReactiveExtensionClient client;

    /**
     * why: 排序请求只更新 sortOrder，避免左侧列表排序被右侧未保存、未校验通过的编辑内容牵连。
     */
    public <T extends AbstractExtension> Mono<ServerResponse> reorder(ServerRequest request, Class<T> type,
                                                                      String resourceLabel,
                                                                      BiConsumer<T, Integer> sortOrderSetter) {
        return request.bodyToMono(SortOrderUpdateRequest.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(body -> validate(body, resourceLabel))
                .flatMapMany(validBody -> Flux.fromIterable(validBody.getItems()))
                .concatMap(item -> client.get(type, item.getId())
                        .switchIfEmpty(Mono.error(
                                new ServerWebInputException(resourceLabel + "不存在：" + item.getId())))
                        .flatMap(resource -> {
                            sortOrderSetter.accept(resource, item.getSortOrder());
                            return client.update(resource);
                        }))
                .collectList()
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    Mono<SortOrderUpdateRequest> validate(SortOrderUpdateRequest request, String resourceLabel) {
        List<SortOrderUpdateRequest.Item> items = request.getItems();
        if (items == null || items.isEmpty()) {
            return Mono.error(new ServerWebInputException(resourceLabel + "排序项不能为空"));
        }

        Set<String> ids = new HashSet<>();
        Set<Integer> sortOrders = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            SortOrderUpdateRequest.Item item = items.get(index);
            if (item == null) {
                return Mono.error(new ServerWebInputException("items[" + index + "] 不能为空"));
            }
            if (!StringUtils.hasText(item.getId())) {
                return Mono.error(new ServerWebInputException("items[" + index + "].id 不能为空"));
            }
            if (item.getSortOrder() == null || item.getSortOrder() < 1) {
                return Mono.error(new ServerWebInputException("items[" + index + "].sortOrder 必须大于 0"));
            }
            if (!ids.add(item.getId())) {
                return Mono.error(new ServerWebInputException("items[" + index + "].id 重复：" + item.getId()));
            }
            if (!sortOrders.add(item.getSortOrder())) {
                return Mono.error(new ServerWebInputException(
                        "items[" + index + "].sortOrder 重复：" + item.getSortOrder()));
            }
        }
        return Mono.just(request);
    }
}
