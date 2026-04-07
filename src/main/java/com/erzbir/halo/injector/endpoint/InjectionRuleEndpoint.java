package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.service.SnippetReferenceService;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import com.erzbir.halo.injector.util.InjectionRuleValidator;
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

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Component
@RequiredArgsConstructor
public class InjectionRuleEndpoint implements CustomEndpoint {
    private static final String READ_API_VERSION = "injector.erzbir.com/v1alpha1";

    private final ReactiveExtensionClient client;
    private final InjectionRuleValidator validator;
    private final InjectionRuleManager ruleManager;
    private final SnippetReferenceService snippetReferenceService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/injectionRules"), this::createRule)
                .andRoute(PUT("/injectionRules/{name}"), this::updateRule)
                .andRoute(PUT("/injectionRules/{name}/enabled"), this::updateRuleEnabled)
                .andRoute(DELETE("/injectionRules/{name}"), this::deleteRule);
    }

    /**
     * why: 既然规则侧已成为唯一关系真源，创建时就必须把 `snippetIds` 的归一化与引用校验
     * 收敛到这一条写链路里，避免不同入口各自拼装出不同语义。
     */
    private Mono<ServerResponse> createRule(ServerRequest request) {
        return request.bodyToMono(InjectionRule.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(validator::validateForWrite)
                .flatMap(this::normalizeAndValidateSnippetReferences)
                .flatMap(client::create)
                .doOnSuccess(created -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(created -> ServerResponse.created(URI.create("/apis/" + READ_API_VERSION + "/injectionRules/"
                                + created.getMetadata().getName()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(created));
    }

    /**
     * why: 更新规则时也必须沿用同一套 snippet 引用校验，并强制资源名稳定，
     * 避免“改名式更新”破坏资源定位语义。
     */
    private Mono<ServerResponse> updateRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(InjectionRule.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的规则")))
                .zipWhen(existing -> request.bodyToMono(InjectionRule.class)
                        .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空"))))
                .map(tuple -> {
                    InjectionRule rule = tuple.getT2();
                    if (rule.getMetadata() == null
                            || !StringUtils.hasText(rule.getMetadata().getName())
                            || !Objects.equals(rule.getMetadata().getName(), name)) {
                        throw new InjectionRuleValidationException("metadata.name 与路径参数不一致");
                    }
                    return tuple;
                })
                .flatMap(tuple -> validator.validateForWrite(tuple.getT2())
                        .flatMap(ignored -> normalizeAndValidateAddedSnippetReferences(tuple.getT1(), tuple.getT2())))
                .flatMap(client::update)
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 启停是资源级动作，不该复用整份规则 update；
     * 否则前端当前草稿会被误当成一次完整保存，语义会和“保存”混在一起。
     */
    private Mono<ServerResponse> updateRuleEnabled(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(EnabledPayload.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(payload -> updateRuleEnabled(name, payload.enabled))
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 删除规则后不需要再反向改写代码块；
     * 因为代码块侧已经不再持久化关系，删除只需失效运行时缓存即可。
     */
    private Mono<ServerResponse> deleteRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(InjectionRule.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的规则")))
                .flatMap(client::delete)
                .doOnSuccess(ignored -> ruleManager.invalidateAndWarmUpAsync())
                .then(ServerResponse.noContent().build());
    }

    /**
     * why: 规则启用时必须基于已保存规则重新做完整可运行校验；
     * 这样即使前端当前有未保存草稿，启停语义也始终只围绕已持久化资源展开。
     */
    Mono<InjectionRule> updateRuleEnabled(String name, Boolean enabled) {
        if (enabled == null) {
            return Mono.error(new ServerWebInputException("enabled 不能为空"));
        }
        if (!StringUtils.hasText(name)) {
            return Mono.error(new ServerWebInputException("未找到要更新的规则"));
        }
        return client.fetch(InjectionRule.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要更新的规则")))
                .flatMap(rule -> {
                    rule.setEnabled(enabled);
                    if (!enabled) {
                        return Mono.just(rule);
                    }
                    return validator.validateForWrite(rule)
                            .flatMap(ignored -> normalizeAndValidateSnippetReferences(rule));
                })
                .flatMap(client::update)
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync());
    }

    /**
     * why: 规则写入前统一把 snippet 关联收敛成规范的 `LinkedHashSet`，
     * 保证顺序稳定、去重稳定，也保证后续比较和导出行为一致。
     */
    private Mono<InjectionRule> normalizeAndValidateSnippetReferences(InjectionRule rule) {
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
    private Mono<InjectionRule> normalizeAndValidateAddedSnippetReferences(InjectionRule existingRule,
                                                                           InjectionRule nextRule) {
        return snippetReferenceService.normalizeAndValidateAddedSnippetIds(existingRule.getSnippetIds(),
                        nextRule.getSnippetIds())
                .map(snippetIds -> {
                    nextRule.setSnippetIds(new LinkedHashSet<>(snippetIds));
                    return nextRule;
                });
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }

    @lombok.Data
    static final class EnabledPayload {
        private Boolean enabled;
    }
}
