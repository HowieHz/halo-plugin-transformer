package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.core.MatchRule;
import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.service.SnippetReferenceService;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import com.erzbir.halo.injector.util.InjectionRuleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InjectionRuleEndpointTest {
    private ReactiveExtensionClient client;
    private InjectionRuleValidator validator;
    private InjectionRuleManager ruleManager;
    private SnippetReferenceService snippetReferenceService;
    private InjectionRuleEndpoint endpoint;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        validator = mock(InjectionRuleValidator.class);
        ruleManager = mock(InjectionRuleManager.class);
        snippetReferenceService = mock(SnippetReferenceService.class);
        endpoint = new InjectionRuleEndpoint(client, validator, ruleManager, snippetReferenceService);
    }

    // why: 规则启停必须只围绕已保存规则本体切换 enabled，
    // 不能再让前端当前未保存草稿混进一次完整 update。
    @Test
    void shouldToggleRuleEnabledUsingPersistedRuleOnly() {
        InjectionRule rule = rule("rule-a", false);
        rule.getMetadata().setVersion(9L);
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        when(validator.validateForWrite(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(snippetReferenceService.normalizeAndValidateSnippetIds(eq(rule.getSnippetIds())))
                .thenReturn(Mono.just(new LinkedHashSet<>(rule.getSnippetIds())));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        InjectionRule updated = endpoint.updateRuleEnabled("rule-a", enabledPayload(true, 9L)).block();

        assertEquals(true, updated.isEnabled());
        verify(client).update(any(InjectionRule.class));
        verify(ruleManager).invalidateAndWarmUpAsync();
    }

    // why: 停用只需要把规则移出运行时；
    // 即使历史上已经有坏规则，也不应因此阻止管理员先停用它。
    @Test
    void shouldDisableRuleWithoutRevalidatingIt() {
        InjectionRule rule = rule("rule-a", true);
        rule.getMetadata().setVersion(4L);
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        InjectionRule updated = endpoint.updateRuleEnabled("rule-a", enabledPayload(false, 4L)).block();

        assertEquals(false, updated.isEnabled());
        verify(validator, never()).validateForWrite(any(InjectionRule.class));
        verify(snippetReferenceService, never()).normalizeAndValidateSnippetIds(any());
    }

    // why: 启用必须重新做完整后端校验；
    // 否则一个历史坏规则可以绕过编辑器直接重新进入运行时。
    @Test
    void shouldRejectEnablingInvalidPersistedRule() {
        InjectionRule rule = rule("rule-a", false);
        rule.getMetadata().setVersion(11L);
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("");
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        InjectionRuleEndpoint endpointWithRealValidator = new InjectionRuleEndpoint(
                client,
                new InjectionRuleValidator(),
                ruleManager,
                snippetReferenceService
        );

        InjectionRuleValidationException validationError = assertThrows(
                InjectionRuleValidationException.class,
                () -> endpointWithRealValidator.updateRuleEnabled("rule-a", enabledPayload(true, 11L)).block()
        );
        assertEquals("match：请填写匹配内容", validationError.getReason());
        verify(client, never()).update(any(InjectionRule.class));
    }

    // why: 旧数据或服务端默认序列化会把叶子节点空 `children` 落库；
    // 启用时应先收敛成规范持久化形状，再做严格校验，避免管理员被“系统自己写进去的脏形状”反向卡住。
    @Test
    void shouldCanonicalizePersistedMatchRuleBeforeEnabling() {
        InjectionRule rule = rule("rule-a", false);
        rule.getMetadata().setVersion(12L);
        rule.setMatchRule(dirtyPersistedMatchRule());
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        when(snippetReferenceService.normalizeAndValidateSnippetIds(eq(rule.getSnippetIds())))
                .thenReturn(Mono.just(new LinkedHashSet<>(rule.getSnippetIds())));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        InjectionRuleEndpoint endpointWithRealValidator = new InjectionRuleEndpoint(
                client,
                new InjectionRuleValidator(),
                ruleManager,
                snippetReferenceService
        );

        InjectionRule updated = endpointWithRealValidator.updateRuleEnabled("rule-a", enabledPayload(true, 12L)).block();

        assertEquals(true, updated.isEnabled());
        assertEquals(null, updated.getMatchRule().getChildren().get(0).getChildren());
        verify(client).update(any(InjectionRule.class));
    }

    // why: 规则启停也必须带最新版本；否则旧页面可以把别人刚保存的 enabled 状态无提示覆盖掉。
    @Test
    void shouldRejectTogglingRuleEnabledWithStaleVersion() {
        InjectionRule rule = rule("rule-a", false);
        rule.getMetadata().setVersion(8L);
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> endpoint.updateRuleEnabled("rule-a", enabledPayload(true, 7L)).block()
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("注入规则已被其他人修改，请刷新后重试", error.getReason());
        verify(client, never()).update(any(InjectionRule.class));
    }

    private InjectionRule rule(String id, boolean enabled) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setEnabled(enabled);
        rule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));
        rule.setMode(InjectionRule.Mode.FOOTER);
        rule.setPosition(InjectionRule.Position.APPEND);
        rule.setWrapMarker(true);
        rule.setMatchRule(com.erzbir.halo.injector.core.MatchRule.defaultRule());
        rule.setMatch("");
        return rule;
    }

    private InjectionRuleEndpoint.EnabledPayload enabledPayload(boolean enabled, long version) {
        InjectionRuleEndpoint.EnabledPayload payload = new InjectionRuleEndpoint.EnabledPayload();
        payload.setEnabled(enabled);
        Metadata metadata = new Metadata();
        metadata.setVersion(version);
        payload.setMetadata(metadata);
        return payload;
    }

    private MatchRule dirtyPersistedMatchRule() {
        MatchRule leaf = MatchRule.pathRule(MatchRule.Matcher.ANT, "/**");
        leaf.setChildren(List.of());
        MatchRule root = new MatchRule();
        root.setNegate(false);
        root.setOperator(MatchRule.Operator.AND);
        root.setChildren(List.of(leaf));
        return root;
    }
}
