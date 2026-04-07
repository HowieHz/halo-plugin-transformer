package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.service.SnippetReferenceService;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import com.erzbir.halo.injector.util.InjectionRuleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
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
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        when(validator.validateForWrite(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(snippetReferenceService.normalizeAndValidateSnippetIds(eq(rule.getSnippetIds())))
                .thenReturn(Mono.just(new LinkedHashSet<>(rule.getSnippetIds())));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        InjectionRule updated = endpoint.updateRuleEnabled("rule-a", true).block();

        assertEquals(true, updated.isEnabled());
        verify(client).update(any(InjectionRule.class));
        verify(ruleManager).invalidateAndWarmUpAsync();
    }

    // why: 停用只需要把规则移出运行时；
    // 即使历史上已经有坏规则，也不应因此阻止管理员先停用它。
    @Test
    void shouldDisableRuleWithoutRevalidatingIt() {
        InjectionRule rule = rule("rule-a", true);
        when(client.fetch(InjectionRule.class, "rule-a")).thenReturn(Mono.just(rule));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        InjectionRule updated = endpoint.updateRuleEnabled("rule-a", false).block();

        assertEquals(false, updated.isEnabled());
        verify(validator, never()).validateForWrite(any(InjectionRule.class));
        verify(snippetReferenceService, never()).normalizeAndValidateSnippetIds(any());
    }

    // why: 启用必须重新做完整后端校验；
    // 否则一个历史坏规则可以绕过编辑器直接重新进入运行时。
    @Test
    void shouldRejectEnablingInvalidPersistedRule() {
        InjectionRule rule = rule("rule-a", false);
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
                () -> endpointWithRealValidator.updateRuleEnabled("rule-a", true).block()
        );
        assertEquals("match：请填写匹配内容", validationError.getReason());
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
}
