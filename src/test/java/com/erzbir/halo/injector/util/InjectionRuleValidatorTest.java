package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InjectionRuleValidatorTest {
    private final InjectionRuleValidator validator = new InjectionRuleValidator();

    // why: 合法 regex 必须能顺利落库，避免后端校验误伤正常规则。
    @Test
    void shouldAllowValidRegexDuringWriteValidation() {
        InjectionRule rule = makeRule();
        rule.setMatchRule(makeGroup(MatchRule.pathRule(MatchRule.Matcher.REGEX, "^/posts/\\d+$")));

        assertDoesNotThrow(() -> validator.validateForWrite(rule).block());
    }

    // why: 非法 regex 要在写入期被明确拦下，不能等运行时才以“不生效”形式暴露问题。
    @Test
    void shouldRejectInvalidRegexDuringWriteValidation() {
        InjectionRule rule = makeRule();
        setMatchRuleDirectly(rule, makeGroup(MatchRule.pathRule(MatchRule.Matcher.REGEX, "[")));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.children[0].value：正则表达式无效，Unclosed character class", error.getReason());
    }

    // why: 模板 ID 规则只允许 EXACT/REGEX，写入期必须兜底拦截前后端约束不一致的数据。
    @Test
    void shouldRejectTemplateIdAntMatcherDuringWriteValidation() {
        InjectionRule rule = makeRule();
        MatchRule child = MatchRule.templateRule(MatchRule.Matcher.EXACT, "post");
        child.setMatcher(MatchRule.Matcher.ANT);
        setMatchRuleDirectly(rule, makeGroup(child));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.children[0].matcher：模板 ID 仅支持 \"REGEX\" 或 \"EXACT\"", error.getReason());
    }

    // why: 根节点固定为 GROUP，便于简单模式/JSON 模式共享统一的规则树结构。
    @Test
    void shouldRequireGroupAsRootNode() {
        InjectionRule rule = makeRule();
        setMatchRuleDirectly(rule, MatchRule.pathRule(MatchRule.Matcher.ANT, "/**"));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.type：根节点必须是 GROUP", error.getReason());
    }

    // why: 后端也要拒绝“叶子节点带条件组字段”这类语义错误，避免只靠前端校验。
    @Test
    void shouldRejectChildrenOnLeafRule() {
        InjectionRule rule = makeRule();
        MatchRule child = MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**");
        child.setChildren(java.util.List.of(MatchRule.pathRule(MatchRule.Matcher.ANT, "/ignored")));
        setMatchRuleDirectly(rule, makeGroup(child));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.children[0].children：仅条件组可使用 children", error.getReason());
    }

    // why: 对愿意接受性能退化的用户，“路径 OR 模板”这类规则仍应允许保存，由配置页给出警告即可。
    @Test
    void shouldAllowUnsupportedDomPathPrecheckRule() {
        InjectionRule rule = makeRule();
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");

        MatchRule templateBranch = MatchRule.templateRule(MatchRule.Matcher.EXACT, "post");
        MatchRule pathBranch = MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**");
        MatchRule orGroup = new MatchRule();
        orGroup.setType(MatchRule.Type.GROUP);
        orGroup.setOperator(MatchRule.Operator.OR);
        orGroup.setChildren(java.util.List.of(pathBranch, templateBranch));
        setMatchRuleDirectly(rule, makeGroup(orGroup));

        assertDoesNotThrow(() -> validator.validateForWrite(rule).block());
    }

    // why: REMOVE 不会消费代码内容；写入期必须拒绝仍携带 snippetIds 的规则，避免产生误导性脏数据。
    @Test
    void shouldRejectSnippetIdsWhenPositionIsRemove() {
        InjectionRule rule = makeRule();
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");
        rule.setPosition(InjectionRule.Position.REMOVE);
        setSnippetIdsDirectly(rule, java.util.Set.of("snippet-a"));

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("snippetIds：REMOVE 模式下无需关联代码块", error.getReason());
    }

    // why: REMOVE 会直接删除元素，写入期必须拒绝仍要求输出注释标记的规则，避免产生无意义配置。
    @Test
    void shouldRejectWrapMarkerWhenPositionIsRemove() {
        InjectionRule rule = makeRule();
        rule.setMode(InjectionRule.Mode.SELECTOR);
        rule.setMatch("main");
        rule.setPosition(InjectionRule.Position.REMOVE);
        setWrapMarkerDirectly(rule, true);

        InjectionRuleValidationException error = assertThrows(
                InjectionRuleValidationException.class,
                () -> validator.validateForWrite(rule).block()
        );

        assertEquals("wrapMarker：REMOVE 模式下无需输出注释标记", error.getReason());
    }

    private InjectionRule makeRule() {
        InjectionRule rule = new InjectionRule();
        rule.setMatchRule(MatchRule.defaultRule());
        return rule;
    }

    private MatchRule makeGroup(MatchRule child) {
        MatchRule group = new MatchRule();
        group.setType(MatchRule.Type.GROUP);
        group.setOperator(MatchRule.Operator.AND);
        group.setChildren(java.util.List.of(child));
        return group;
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

    private void setSnippetIdsDirectly(InjectionRule rule, java.util.Set<String> snippetIds) {
        try {
            var field = InjectionRule.class.getDeclaredField("snippetIds");
            field.setAccessible(true);
            field.set(rule, snippetIds);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setWrapMarkerDirectly(InjectionRule rule, boolean wrapMarker) {
        try {
            var field = InjectionRule.class.getDeclaredField("wrapMarker");
            field.setAccessible(true);
            field.set(rule, wrapMarker);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
