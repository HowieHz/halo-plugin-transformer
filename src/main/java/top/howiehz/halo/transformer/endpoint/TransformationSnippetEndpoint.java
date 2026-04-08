package top.howiehz.halo.transformer.endpoint;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import java.util.Objects;
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
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.service.TransformationSnippetLifecycleService;
import top.howiehz.halo.transformer.util.OptimisticConcurrencyGuard;
import top.howiehz.halo.transformer.util.TransformationSnippetValidationException;
import top.howiehz.halo.transformer.util.TransformationSnippetValidator;

@Component
@RequiredArgsConstructor
public class TransformationSnippetEndpoint implements CustomEndpoint {
    private static final String CONSOLE_API_VERSION =
        "console.api.transformer.howiehz.top/v1alpha1";

    private final ReactiveExtensionClient client;
    private final TransformationSnippetValidator validator;
    private final TransformationSnippetLifecycleService lifecycleService;
    private final TransformationRuleRuntimeStore ruleRuntimeStore;
    private final ConsoleReadModelMapper readModelMapper;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET("/transformationSnippets"), this::listSnippets)
            .andRoute(GET("/transformationSnippets/{name}"), this::getSnippet)
            .andRoute(POST("/transformationSnippets"), this::createSnippet)
            .andRoute(PUT("/transformationSnippets/{name}"), this::updateSnippet)
            .andRoute(PUT("/transformationSnippets/{name}/enabled"), this::updateSnippetEnabled)
            .andRoute(DELETE("/transformationSnippets/{name}"), this::deleteSnippet);
    }

    /**
     * why: 控制台列表应读取显式 read model projection，而不是把存储实体直接暴露给 UI；
     * 这样 `id` 等派生字段就收敛在响应层，不再反向污染持久化模型。
     */
    private Mono<ServerResponse> listSnippets(ServerRequest request) {
        return client.list(TransformationSnippet.class, null, null)
            .collectList()
            .map(readModelMapper::toSnippetList)
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    private Mono<ServerResponse> getSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(TransformationSnippet.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("未找到代码片段")))
            .map(readModelMapper::toReadModel)
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
            .flatMap(validator::validateForWrite)
            .flatMap(client::create)
            .doOnSuccess(created -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .flatMap(created -> ServerResponse.created(
                    URI.create("/apis/" + CONSOLE_API_VERSION + "/transformationSnippets/"
                        + created.getMetadata().getName()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(readModelMapper.toReadModel(created)));
    }

    /**
     * why: 更新接口同样只处理代码片段本体；同时强制 `metadata.name` 与路径参数一致，
     * 避免客户端借 update 接口把内容写进另一条资源。
     */
    private Mono<ServerResponse> updateSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(TransformationSnippet.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的代码片段")))
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
            .flatMap(validator::validateForWrite)
            .flatMap(client::update)
            .doOnSuccess(updated -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(readModelMapper.toReadModel(updated)));
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
            .map(readModelMapper::toReadModel)
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updated));
    }

    /**
     * why: 删除代码片段改为走 Halo finalizer 生命周期；
     * endpoint 只负责把资源送入 deleting 状态，后续摘引用与最终删除由后台 reconciler 完成。
     */
    private Mono<ServerResponse> deleteSnippet(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(TransformationSnippet.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的代码片段")))
            .flatMap(lifecycleService::markForDeletion)
            .doOnSuccess(ignored -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .then(ServerResponse.noContent().build());
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
        return client.fetch(TransformationSnippet.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的代码片段")))
            .flatMap(snippet -> {
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    snippet.getMetadata(),
                    payload.metadata,
                    "代码片段"
                );
                lifecycleService.prepareForPersist(snippet);
                snippet.setEnabled(enabled);
                return enabled ? validator.validateForWrite(snippet) : Mono.just(snippet);
            })
            .flatMap(client::update)
            .doOnSuccess(updated -> ruleRuntimeStore.invalidateAndWarmUpAsync());
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(CONSOLE_API_VERSION);
    }

    @lombok.Data
    static final class EnabledPayload {
        private Boolean enabled;
        private run.halo.app.extension.Metadata metadata;
    }
}

