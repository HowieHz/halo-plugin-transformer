package top.howiehz.halo.transformer.endpoint;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.extension.TransformationSnippet;
import top.howiehz.halo.transformer.runtime.store.TransformationSnippetRuntimeStore;
import top.howiehz.halo.transformer.service.TransformationSnippetLifecycleService;
import top.howiehz.halo.transformer.support.OptimisticConcurrencyGuard;
import top.howiehz.halo.transformer.validation.TransformationSnippetValidationException;
import top.howiehz.halo.transformer.validation.TransformationSnippetValidator;

@Component
@RequiredArgsConstructor
public class TransformationSnippetEndpoint implements CustomEndpoint {
    private static final String CONSOLE_API_VERSION =
        "console.api.transformer.howiehz.top/v1alpha1";
    private static final String SNIPPET_SNAPSHOT_PATH = "/transformationSnippets/-/snapshot";

    private final ReactiveExtensionClient client;
    private final TransformationSnippetLifecycleService lifecycleService;
    private final TransformationSnippetRuntimeStore snippetRuntimeStore;
    private final ResourceOrderService resourceOrderService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET(SNIPPET_SNAPSHOT_PATH), this::getSnippetSnapshot)
            .andRoute(GET("/transformationSnippets/{name}"), this::getSnippet)
            .andRoute(POST("/transformationSnippets"), this::createSnippet)
            .andRoute(PUT("/transformationSnippets/{name}"), this::updateSnippet)
            .andRoute(PUT("/transformationSnippets/{name}/enabled"), this::updateSnippetEnabled)
            .andRoute(DELETE("/transformationSnippets/{name}"), this::deleteSnippet);
    }

    /**
     * why: 控制台左侧顺序由“资源列表 + 排序映射版本”共同决定；
     * 这里把它们聚合成一次读快照返回，避免前端刷新时再拼出“新列表 + 旧排序”的裂脑状态。
     */
    private Mono<ServerResponse> getSnippetSnapshot(ServerRequest request) {
        return resourceOrderService.buildCollectionSnapshot(ResourceOrderService.SNIPPET_ORDER_NAME,
                snippetRuntimeStore::listVisibleSnippets, TransformationSnippet::getName)
            .map(snapshot -> ConsoleReadModelMapper.toSnippetSnapshot(snapshot.resources(),
                snapshot.orders(), snapshot.orderVersion()))
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    private Mono<ServerResponse> getSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return fetchVisibleSnippet(name, "未找到代码片段")
            .map(ConsoleReadModelMapper::toReadModel)
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    /**
     * why: 代码片段已经不再承担关系真源；创建接口只负责校验并写入代码片段本体，
     * 不再暗中补写规则，避免再次把关系写复杂。
     */
    private Mono<ServerResponse> createSnippet(ServerRequest request) {
        return request.bodyToMono(TransformationSnippet.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .map(lifecycleService::prepareForPersist)
            .flatMap(TransformationSnippetValidator::validateForWrite)
            .flatMap(client::create)
            .doOnSuccess(created -> {
                snippetRuntimeStore.applyPersistedSnippet(created);
                snippetRuntimeStore.invalidateAndWarmUpAsync();
            })
            .flatMap(created -> ServerResponse.created(
                    URI.create("/apis/" + CONSOLE_API_VERSION + "/transformationSnippets/"
                        + created.getMetadata().getName()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ConsoleReadModelMapper.toReadModel(created)));
    }

    /**
     * why: 更新接口同样只处理代码片段本体；同时强制 `metadata.name` 与路径参数一致，
     * 避免客户端借 update 接口把内容写进另一条资源。
     */
    private Mono<ServerResponse> updateSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return fetchVisibleSnippet(name, "未找到要更新的代码片段")
            .zipWhen(existing -> request.bodyToMono(TransformationSnippet.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空"))))
            .map(tuple -> {
                TransformationSnippet existing = tuple.getT1();
                TransformationSnippet snippet = tuple.getT2();
                if (snippet.getMetadata() == null
                    || snippet.getMetadata().getName() == null
                    || !Objects.equals(snippet.getMetadata().getName(), name)) {
                    throw new TransformationSnippetValidationException(
                        "metadata.name 与路径参数不一致");
                }
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    existing.getMetadata(),
                    snippet.getMetadata(),
                    "代码片段"
                );
                return lifecycleService.prepareForPersist(snippet);
            })
            .flatMap(TransformationSnippetValidator::validateForWrite)
            .flatMap(client::update)
            .doOnSuccess(updated -> {
                snippetRuntimeStore.applyPersistedSnippet(updated);
                snippetRuntimeStore.invalidateAndWarmUpAsync();
            })
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ConsoleReadModelMapper.toReadModel(updated)));
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
            .map(ConsoleReadModelMapper::toReadModel)
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updated));
    }

    /**
     * why: 删除代码片段改为走 Halo finalizer 生命周期；
     * 接口端（endpoint）只负责把资源送入“删除中”状态，后续摘引用与最终删除由后台 reconciler 完成。
     */
    private Mono<ServerResponse> deleteSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(DeletePayload.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .flatMap(payload -> deleteSnippet(name, payload))
            .then(ServerResponse.noContent().build());
    }

    /**
     * why: 删除也是写路径的一部分，必须和其它 mutation 一样复用 `metadata.version`；
     * 否则 stale 页面仍能把更晚的管理员操作静默覆盖。
     */
    Mono<Void> deleteSnippet(String name, DeletePayload payload) {
        return fetchVisibleSnippet(name, "未找到要删除的代码片段")
            .flatMap(snippet -> {
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    snippet.getMetadata(),
                    payload.metadata,
                    "代码片段"
                );
                return lifecycleService.markForDeletion(snippet)
                    .thenReturn(markDeletionPendingInLocalSnapshot(snippet));
            })
            .doOnSuccess(deletingSnippet -> {
                snippetRuntimeStore.applyPersistedSnippet(deletingSnippet);
                snippetRuntimeStore.invalidateAndWarmUpAsync();
            })
            .then();
    }

    /**
     * why: 当用户只是切换启停时，后端必须基于已保存资源本体处理，
     * 不能让前端未保存草稿混进来，避免“启用偷偷保存 / 停用偷偷丢稿”。
     */
    Mono<TransformationSnippet> updateSnippetEnabled(String name, EnabledPayload payload) {
        Boolean enabled = payload.enabled;
        if (enabled == null) {
            return Mono.error(new ServerWebInputException("enabled 不能为空"));
        }
        if (!StringUtils.hasText(name)) {
            return Mono.error(new ServerWebInputException("未找到要更新的代码片段"));
        }
        return fetchVisibleSnippet(name, "未找到要更新的代码片段")
            .flatMap(snippet -> {
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    snippet.getMetadata(),
                    payload.metadata,
                    "代码片段"
                );
                lifecycleService.prepareForPersist(snippet);
                snippet.setEnabled(enabled);
                return enabled ? TransformationSnippetValidator.validateForWrite(snippet)
                    : Mono.just(snippet);
            })
            .flatMap(client::update)
            .doOnSuccess(updated -> {
                snippetRuntimeStore.applyPersistedSnippet(updated);
                snippetRuntimeStore.invalidateAndWarmUpAsync();
            });
    }

    private Mono<TransformationSnippet> fetchVisibleSnippet(String name, String notFoundReason) {
        return client.fetch(TransformationSnippet.class, name)
            .filter(snippet -> !ExtensionUtil.isDeleted(snippet))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                notFoundReason)));
    }

    /**
     * why: delete 请求一旦成功，控制台和运行时都应立即看到“同一条资源已进入 deleting 生命周期”；
     * 这样可见列表会立刻隐藏它，而 runtime 仍能沿用同一份快照继续兜住待清理引用的输出。
     */
    private TransformationSnippet markDeletionPendingInLocalSnapshot(
        TransformationSnippet snippet) {
        snippet.getMetadata().setDeletionTimestamp(Instant.now());
        return snippet;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(CONSOLE_API_VERSION);
    }

    @lombok.Data
    static final class EnabledPayload {
        private Boolean enabled;
        private Metadata metadata;
    }

    @lombok.Data
    static final class DeletePayload {
        private Metadata metadata;
    }
}
