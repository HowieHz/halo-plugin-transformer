package top.howiehz.halo.transformer.endpoint;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.URI;
import java.util.LinkedHashSet;
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
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.core.MatchRule;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.service.TransformationSnippetReferenceService;
import top.howiehz.halo.transformer.util.OptimisticConcurrencyGuard;
import top.howiehz.halo.transformer.util.TransformationRuleValidationException;
import top.howiehz.halo.transformer.util.TransformationRuleValidator;

@Component
@RequiredArgsConstructor
public class TransformationRuleEndpoint implements CustomEndpoint {
    private static final String CONSOLE_API_VERSION =
        "console.api.transformer.howiehz.top/v1alpha1";
    private static final String RULE_SNAPSHOT_PATH = "/transformationRules/-/snapshot";

    private final ReactiveExtensionClient client;
    private final TransformationRuleValidator validator;
    private final TransformationRuleRuntimeStore ruleRuntimeStore;
    private final TransformationSnippetReferenceService snippetReferenceService;
    private final ConsoleReadModelMapper readModelMapper;
    private final ResourceOrderService resourceOrderService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(GET(RULE_SNAPSHOT_PATH), this::getRuleSnapshot)
            .andRoute(GET("/transformationRules"), this::listRules)
            .andRoute(GET("/transformationRules/{name}"), this::getRule)
            .andRoute(POST("/transformationRules"), this::createRule)
            .andRoute(PUT("/transformationRules/{name}"), this::updateRule)
            .andRoute(PUT("/transformationRules/{name}/enabled"), this::updateRuleEnabled)
            .andRoute(DELETE("/transformationRules/{name}"), this::deleteRule);
    }

    /**
     * why: 控制台读规则时应消费响应映射结果，而不是直接反序列化存储实体；
     * 这样 UI 特有的 `id` 读字段就不会再回流到写模型。
     */
    private Mono<ServerResponse> listRules(ServerRequest request) {
        return client.list(TransformationRule.class, null, null)
            .collectList()
            .map(readModelMapper::toRuleList)
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    /**
     * why: 规则列表刷新必须与 `rule-order` 的最新映射和 version 同步前进；
     * 否则跨管理员协作时，前端会在旧 version 上继续拖拽和保存顺序。
     */
    private Mono<ServerResponse> getRuleSnapshot(ServerRequest request) {
        return resourceOrderService.buildCollectionSnapshot(ResourceOrderService.RULE_ORDER_NAME,
                TransformationRule.class, TransformationRule::getName)
            .map(snapshot -> readModelMapper.toRuleSnapshot(snapshot.resources(),
                snapshot.orders(), snapshot.orderVersion()))
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    private Mono<ServerResponse> getRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return fetchVisibleRule(name, "未找到转换规则")
            .map(readModelMapper::toReadModel)
            .flatMap(response -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(response));
    }

    /**
     * why: 既然规则侧已成为唯一关系真源，创建时就必须把 `snippetIds` 的归一化与引用校验
     * 收敛到这一条写链路里，避免不同入口各自拼装出不同语义。
     */
    private Mono<ServerResponse> createRule(ServerRequest request) {
        return request.bodyToMono(TransformationRule.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .map(this::canonicalizeRuleForStorage)
            .flatMap(validator::validateForWrite)
            .flatMap(this::normalizeAndValidateSnippetReferences)
            .flatMap(client::create)
            .doOnSuccess(created -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .flatMap(created -> ServerResponse.created(
                    URI.create("/apis/" + CONSOLE_API_VERSION + "/transformationRules/"
                        + created.getMetadata().getName()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(readModelMapper.toReadModel(created)));
    }

    /**
     * why: 更新规则时也必须沿用同一套 snippet 引用校验，并强制资源名稳定，
     * 避免“改名式更新”破坏资源定位语义。
     */
    private Mono<ServerResponse> updateRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return fetchVisibleRule(name, "未找到要更新的规则")
            .zipWhen(existing -> request.bodyToMono(TransformationRule.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空"))))
            .map(tuple -> {
                TransformationRule existing = tuple.getT1();
                TransformationRule rule = tuple.getT2();
                if (rule.getMetadata() == null
                    || !StringUtils.hasText(rule.getMetadata().getName())
                    || !Objects.equals(rule.getMetadata().getName(), name)) {
                    throw new TransformationRuleValidationException(
                        "metadata.name 与路径参数不一致");
                }
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    existing.getMetadata(),
                    rule.getMetadata(),
                    "转换规则"
                );
                return tuple;
            })
            .map(tuple -> reactor.util.function.Tuples.of(tuple.getT1(),
                canonicalizeRuleForStorage(tuple.getT2())))
            .flatMap(tuple -> validator.validateForWrite(tuple.getT2())
                .flatMap(ignored -> normalizeAndValidateAddedSnippetReferences(tuple.getT1(),
                    tuple.getT2())))
            .flatMap(client::update)
            .doOnSuccess(updated -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(readModelMapper.toReadModel(updated)));
    }

    /**
     * why: 启停是资源级动作，不该复用整份规则 update；
     * 否则前端当前草稿会被误当成一次完整保存，语义会和“保存”混在一起。
     */
    private Mono<ServerResponse> updateRuleEnabled(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(EnabledPayload.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .flatMap(payload -> updateRuleEnabled(name, payload))
            .map(readModelMapper::toReadModel)
            .flatMap(updated -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updated));
    }

    /**
     * why: 删除规则后不需要再反向改写代码片段；
     * 因为代码片段侧已经不再持久化关系，删除只需失效运行时缓存即可。
     */
    private Mono<ServerResponse> deleteRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(DeletePayload.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
            .flatMap(payload -> deleteRule(name, payload))
            .then(ServerResponse.noContent().build());
    }

    /**
     * why: 删除同样属于写操作，也必须复用 `metadata.version`；
     * 否则前端其余写口都有并发保护，唯独删除还在“最后一次写入静默生效”。
     */
    Mono<Void> deleteRule(String name, DeletePayload payload) {
        return fetchVisibleRule(name, "未找到要删除的规则")
            .flatMap(rule -> {
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    rule.getMetadata(),
                    payload.metadata,
                    "转换规则"
                );
                return client.delete(rule);
            })
            .doOnSuccess(ignored -> ruleRuntimeStore.invalidateAndWarmUpAsync())
            .then();
    }

    /**
     * why: 规则启用时必须基于已保存规则重新做完整可运行校验；
     * 这样即使前端当前有未保存草稿，启停语义也始终只围绕已持久化资源展开。
     */
    Mono<TransformationRule> updateRuleEnabled(String name, EnabledPayload payload) {
        Boolean enabled = payload.enabled;
        if (enabled == null) {
            return Mono.error(new ServerWebInputException("enabled 不能为空"));
        }
        if (!StringUtils.hasText(name)) {
            return Mono.error(new ServerWebInputException("未找到要更新的规则"));
        }
        return fetchVisibleRule(name, "未找到要更新的规则")
            .flatMap(rule -> {
                OptimisticConcurrencyGuard.requireMatchingVersion(
                    rule.getMetadata(),
                    payload.metadata,
                    "转换规则"
                );
                rule.setEnabled(enabled);
                if (!enabled) {
                    return Mono.just(rule);
                }
                canonicalizeRuleForStorage(rule);
                return validator.validateForWrite(rule)
                    .flatMap(ignored -> normalizeAndValidateSnippetReferences(rule));
            })
            .flatMap(client::update)
            .doOnSuccess(updated -> ruleRuntimeStore.invalidateAndWarmUpAsync());
    }

    /**
     * why: 规则写入前统一把 snippet 关联收敛成规范的 `LinkedHashSet`，
     * 保证顺序稳定、去重稳定，也保证后续比较和导出行为一致。
     */
    private Mono<TransformationRule> normalizeAndValidateSnippetReferences(
        TransformationRule rule) {
        return snippetReferenceService.normalizeAndValidateSnippetIds(rule.getSnippetIds())
            .map(snippetIds -> {
                rule.setSnippetIds(new LinkedHashSet<>(snippetIds));
                return rule;
            });
    }

    /**
     * why: 更新规则时只校验新增的 `snippetIds`；
     * 这样历史坏关联不会把无关字段编辑也一并卡死，同时仍然阻止新的坏引用继续写入。
     */
    private Mono<TransformationRule> normalizeAndValidateAddedSnippetReferences(
        TransformationRule existingRule,
        TransformationRule nextRule) {
        return snippetReferenceService.normalizeAndValidateAddedSnippetIds(
                existingRule.getSnippetIds(),
                nextRule.getSnippetIds())
            .map(snippetIds -> {
                nextRule.setSnippetIds(new LinkedHashSet<>(snippetIds));
                return nextRule;
            });
    }

    /**
     * why: 规则写入前统一收敛成后端权威持久化形状；
     * 这样新数据不会继续把 Java 默认字段噪音写进存储，历史可恢复数据也能沿着下一次成功写操作自然自愈。
     */
    private TransformationRule canonicalizeRuleForStorage(TransformationRule rule) {
        rule.setMatchRule(MatchRule.canonicalizeForStorage(rule.getMatchRule()));
        rule.canonicalizeModeSpecificFieldsForStorage();
        return rule;
    }

    private Mono<TransformationRule> fetchVisibleRule(String name, String notFoundReason) {
        return client.fetch(TransformationRule.class, name)
            .filter(rule -> !ExtensionUtil.isDeleted(rule))
            .switchIfEmpty(Mono.error(new ServerWebInputException(notFoundReason)));
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
