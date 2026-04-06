package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.service.ResourceRelationWriteService;
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
    private final ResourceRelationWriteService relationWriteService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route(POST("/injectionRules"), this::createRule)
                .andRoute(PUT("/injectionRules/{name}"), this::updateRule)
                .andRoute(DELETE("/injectionRules/{name}"), this::deleteRule);
    }

    /**
     * why: 创建入口统一走插件自定义写入校验，
     * 保证控制台表单、脚本调用、以及未来其他写入方都不会绕过规则树与 REMOVE 约束。
     */
    private Mono<ServerResponse> createRule(ServerRequest request) {
        return request.bodyToMono(InjectionRule.class)
                .switchIfEmpty(Mono.error(new ServerWebInputException("请求体不能为空")))
                .flatMap(validator::validateForWrite)
                .flatMap(relationWriteService::createRuleWithRelations)
                .doOnSuccess(created -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(created -> ServerResponse.created(URI.create("/apis/" + READ_API_VERSION + "/injectionRules/"
                                + created.getMetadata().getName()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(created));
    }

    /**
     * why: 更新时除了复用写入校验，还要强制 metadata.name 与路径参数一致，
     * 防止客户端借更新接口“改名写入”到另一条资源上，破坏资源定位语义。
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
                        .then(relationWriteService.updateRuleWithRelations(tuple.getT1(), tuple.getT2())))
                .doOnSuccess(updated -> ruleManager.invalidateAndWarmUpAsync())
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(updated));
    }

    /**
     * why: 规则删除也走 console 写接口，确保运行时规则快照能立刻失效；
     * 否则即便有短 TTL，刚删除的规则也可能在极短时间内继续参与匹配。
     */
    private Mono<ServerResponse> deleteRule(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(InjectionRule.class, name)
                .switchIfEmpty(Mono.error(new ServerWebInputException("未找到要删除的规则")))
                .flatMap(relationWriteService::deleteRuleWithRelations)
                .doOnSuccess(ignored -> ruleManager.invalidateAndWarmUpAsync())
                .then(ServerResponse.noContent().build());
    }

    @Override
    public GroupVersion groupVersion() {
        // 写接口单独挂到 console 分组下，目的是在落库前插入插件自定义校验；
        // 读路径仍然走扩展资源的标准接口，避免影响现有查询链路。
        return GroupVersion.parseAPIVersion("console.api.injector.erzbir.com/v1alpha1");
    }
}
