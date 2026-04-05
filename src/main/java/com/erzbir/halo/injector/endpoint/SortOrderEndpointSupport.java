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
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
public class SortOrderEndpointSupport {
    private final ReactiveExtensionClient client;
    private final Map<String, Semaphore> reorderSemaphores = new ConcurrentHashMap<>();

    /**
     * why: 排序请求只更新 sortOrder，避免左侧列表排序被右侧未保存、未校验通过的编辑内容牵连。
     * 同时会先拿到当前资源全集快照并做完整校验，再进入写入阶段；
     * 再配合同类请求串行化，把当前 API 能力下的排序一致性尽量收敛到最稳。
     */
    public <T extends AbstractExtension> Mono<ServerResponse> reorder(ServerRequest request, Class<T> type,
                                                                      String resourceLabel,
                                                                      BiConsumer<T, Integer> sortOrderSetter) {
        return Mono.usingWhen(
                acquirePermit(type),
                ignored -> request.bodyToMono(SortOrderUpdateRequest.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                        .flatMap(body -> validate(body, resourceLabel))
                        .flatMap(validBody -> prepareReorderBatch(validBody, type, resourceLabel,
                                sortOrderSetter))
                        .flatMap(resources -> Flux.fromIterable(resources)
                                .concatMap(client::update)
                                .collectList())
                        .flatMap(updated -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(updated)),
                ignored -> releasePermit(type),
                (ignored, error) -> releasePermit(type),
                ignored -> releasePermit(type)
        );
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

    /**
     * why: 这里要求前端提交“当前资源全集”的完整排序，而不是只提交局部片段；
     * 这样能在写入前发现遗漏项、额外项和资源增删变化，避免旧顺序残留把结果拖成半新半旧。
     */
    <T extends AbstractExtension> Mono<List<T>> prepareReorderBatch(SortOrderUpdateRequest request, Class<T> type,
                                                                    String resourceLabel,
                                                                    BiConsumer<T, Integer> sortOrderSetter) {
        return client.list(type, null, null)
                .collectList()
                .flatMap(snapshot -> {
                    List<T> orderedResources = validateSnapshotAndArrange(snapshot, request, resourceLabel);
                    orderedResources.forEach(resource -> sortOrderSetter.accept(resource,
                            sortOrderById(request).get(resource.getMetadata().getName())));
                    return Mono.just(orderedResources);
                });
    }

    /**
     * why: 底层没有批量事务接口，这里至少把同类排序请求收敛成串行执行，
     * 避免插件自身的并发拖拽请求彼此穿插写入，进一步放大部分成功风险。
     */
    private <T extends AbstractExtension> Mono<Semaphore> acquirePermit(Class<T> type) {
        Semaphore semaphore = reorderSemaphores.computeIfAbsent(type.getName(), key -> new Semaphore(1));
        return Mono.fromCallable(() -> {
                    semaphore.acquire();
                    return semaphore;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private <T extends AbstractExtension> Mono<Void> releasePermit(Class<T> type) {
        Semaphore semaphore = reorderSemaphores.get(type.getName());
        if (semaphore == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(semaphore::release);
    }

    <T extends AbstractExtension> List<T> validateSnapshotAndArrange(List<T> snapshot, SortOrderUpdateRequest request,
                                                                     String resourceLabel) {
        Map<String, T> resourceById = new HashMap<>();
        for (T resource : snapshot) {
            if (resource == null || resource.getMetadata() == null
                    || !StringUtils.hasText(resource.getMetadata().getName())) {
                continue;
            }
            resourceById.put(resource.getMetadata().getName(), resource);
        }

        List<SortOrderUpdateRequest.Item> items = request.getItems();
        if (resourceById.size() != items.size()) {
            throw new ServerWebInputException(resourceLabel + "排序项必须覆盖当前全部资源");
        }

        List<T> orderedResources = new ArrayList<>(items.size());
        Set<String> requestedIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            SortOrderUpdateRequest.Item item = items.get(index);
            requestedIds.add(item.getId());
            T resource = resourceById.get(item.getId());
            if (resource == null) {
                throw new ServerWebInputException(resourceLabel + "不存在：" + item.getId());
            }
            orderedResources.add(resource);
        }

        if (!resourceById.keySet().equals(requestedIds)) {
            throw new ServerWebInputException(resourceLabel + "排序项必须覆盖当前全部资源");
        }

        return orderedResources;
    }

    private Map<String, Integer> sortOrderById(SortOrderUpdateRequest request) {
        Map<String, Integer> sortOrderById = new HashMap<>();
        for (SortOrderUpdateRequest.Item item : request.getItems()) {
            sortOrderById.put(item.getId(), item.getSortOrder());
        }
        return sortOrderById;
    }
}
