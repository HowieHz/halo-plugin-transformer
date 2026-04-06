package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.manager.CodeSnippetManager;
import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjectHelperTest {
    @Mock
    private InjectionRuleManager ruleManager;

    @Mock
    private CodeSnippetManager snippetManager;

    private InjectHelper injectHelper;

    @BeforeEach
    void setUp() {
        injectHelper = new InjectHelper(ruleManager, snippetManager);
    }

    // why: 基础命中链路必须同时满足路径和模板 ID，保证组合条件按 AND 正常工作。
    @Test
    void shouldMatchRuleWhenPathAndTemplateMatch() {
        InjectionRule rule = createRule(group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        List<InjectionRule> rules = injectHelper
                .getMatchedRules("/posts/demo", "post", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of(rule), rules);
    }

    // why: 模板 ID 不匹配时必须淘汰规则，避免把仅路径命中的规则错误放行。
    @Test
    void shouldSkipRuleWhenTemplateDoesNotMatch() {
        InjectionRule rule = createRule(group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        List<InjectionRule> rules = injectHelper
                .getMatchedRules("/posts/demo", "page", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertTrue(rules.isEmpty());
    }

    // why: 预筛阶段拿不到模板 ID 时，应保留“路径已命中但模板待定”的规则，避免误删候选规则。
    @Test
    void shouldKeepRuleDuringPathPrecheckWhenTemplateIsUnknown() {
        InjectionRule rule = createRule(group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        List<InjectionRule> rules = injectHelper
                .getPathMatchedRules("/posts/demo", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of(rule), rules);
    }

    // why: 路径已明确不命中时应尽早淘汰规则，减少后续无意义处理。
    @Test
    void shouldSkipRuleDuringPathPrecheckWhenPathDefinitelyMisses() {
        InjectionRule rule = createRule(group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        List<InjectionRule> rules = injectHelper
                .getPathMatchedRules("/archives/demo", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertTrue(rules.isEmpty());
    }

    // why: 对 DOM 注入来说，“路径 OR 模板”虽然允许保存，但会退化成全站 HTML 处理；运行时不能把它当坏规则丢掉。
    @Test
    void shouldTreatUnsupportedDomRuleAsRuntimeValid() {
        InjectionRule rule = new InjectionRule();
        rule.setEnabled(true);
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");
        setMatchRuleDirectly(rule, group(MatchRule.Operator.OR,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));

        assertTrue(rule.isValid());
    }

    // why: 无法先按路径缩小范围的 DOM 规则会迫使 WebFilter 对所有 HTML 页面先进入处理链路。
    @Test
    void shouldTreatUnsupportedDomRuleAsGlobalProcessCandidate() {
        InjectionRule rule = new InjectionRule();
        rule.setEnabled(true);
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");
        setMatchRuleDirectly(rule, group(MatchRule.Operator.OR,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        Boolean shouldProcess = injectHelper
                .hasDomProcessCandidate("/archives/demo", InjectionRule.Mode.SELECTOR)
                .block();

        assertTrue(Boolean.TRUE.equals(shouldProcess));
    }

    // why: 同一 regex 在运行期应复用已编译结果，避免每次请求重复 Pattern.compile 带来额外开销。
    @Test
    void shouldCompileSameRegexOnlyOnceAtRuntime() {
        CountingInjectHelper helper = new CountingInjectHelper(ruleManager, snippetManager);
        InjectionRule rule = createRule(group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.REGEX, "^/posts/\\d+$")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.SELECTOR)).thenReturn(Flux.just(rule));

        List<InjectionRule> first = helper
                .getMatchedRules("/posts/1", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();
        List<InjectionRule> second = helper
                .getMatchedRules("/posts/2", InjectionRule.Mode.SELECTOR)
                .collectList()
                .block();

        assertEquals(List.of(rule), first);
        assertEquals(List.of(rule), second);
        assertEquals(1, helper.compileCount.get());
    }

    // why: 历史脏数据里的非法 regex 也要缓存失败结果，避免请求期反复抛异常和重复编译。
    @Test
    void shouldCacheInvalidRegexFailureToAvoidRepeatedCompile() {
        CountingInjectHelper helper = new CountingInjectHelper(ruleManager, snippetManager);
        InjectionRule rule = new InjectionRule();
        rule.setEnabled(true);
        rule.setMode(InjectionRule.Mode.FOOTER);
        setMatchRuleDirectly(rule, group(MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.REGEX, "[")));
        when(ruleManager.listActiveByMode(InjectionRule.Mode.FOOTER)).thenReturn(Flux.just(rule));

        List<InjectionRule> first = helper
                .getMatchedRules("/posts/1", InjectionRule.Mode.FOOTER)
                .collectList()
                .block();
        List<InjectionRule> second = helper
                .getMatchedRules("/posts/2", InjectionRule.Mode.FOOTER)
                .collectList()
                .block();

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertEquals(1, helper.compileCount.get());
    }

    private InjectionRule createRule(MatchRule matchRule) {
        InjectionRule rule = new InjectionRule();
        rule.setEnabled(true);
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");
        rule.setMatchRule(matchRule);
        return rule;
    }

    private MatchRule group(MatchRule.Operator operator, MatchRule... children) {
        MatchRule rule = new MatchRule();
        rule.setType(MatchRule.Type.GROUP);
        rule.setNegate(false);
        rule.setOperator(operator);
        rule.setChildren(List.of(children));
        return rule;
    }

    private void setMatchRuleDirectly(InjectionRule rule, MatchRule matchRule) {
        try {
            var field = InjectionRule.class.getDeclaredField("matchRule");
            field.setAccessible(true);
            field.set(rule, matchRule);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static class CountingInjectHelper extends InjectHelper {
        private final AtomicInteger compileCount = new AtomicInteger();

        CountingInjectHelper(InjectionRuleManager ruleManager, CodeSnippetManager snippetManager) {
            super(ruleManager, snippetManager);
        }

        @Override
        protected Pattern compileRegexPattern(String pattern) {
            compileCount.incrementAndGet();
            return super.compileRegexPattern(pattern);
        }
    }
}
