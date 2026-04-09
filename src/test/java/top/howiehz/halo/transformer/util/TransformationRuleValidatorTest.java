package top.howiehz.halo.transformer.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.howiehz.halo.transformer.core.MatchRule;
import top.howiehz.halo.transformer.scheme.TransformationRule;

class TransformationRuleValidatorTest {
    private final TransformationRuleValidator validator = new TransformationRuleValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: 合法 regex 必须能顺利落库，避免后端校验误伤正常规则。
    @Test
    void shouldAllowValidRegexDuringWriteValidation() {
        TransformationRule rule = makeRule();
        rule.setMatchRule(makeGroup(MatchRule.pathRule(MatchRule.Matcher.REGEX, "^/posts/\\d+$")));

        assertDoesNotThrow(() -> validator.validateForWrite(rule).block());
    }

    // why: 非法 regex 要在写入期被明确拦下，不能等运行时才以“不生效”形式暴露问题。
    @Test
    void shouldRejectInvalidRegexDuringWriteValidation() {
        TransformationRule rule = makeRule();
        setMatchRuleDirectly(rule, makeGroup(MatchRule.pathRule(MatchRule.Matcher.REGEX, "[")));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.children[0].value：正则表达式无效，Unclosed character class",
            error.getReason());
    }

    // why: 模板 ID 规则只允许 EXACT/REGEX，写入期必须兜底拦截前后端约束不一致的数据。
    @Test
    void shouldRejectTemplateIdAntMatcherDuringWriteValidation() {
        TransformationRule rule = makeRule();
        MatchRule child = MatchRule.templateRule(MatchRule.Matcher.EXACT, "post");
        child.setMatcher(MatchRule.Matcher.ANT);
        setMatchRuleDirectly(rule, makeGroup(child));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.children[0].matcher：模板 ID 仅支持 \"REGEX\" 或 \"EXACT\"",
            error.getReason());
    }

    // why: matcher 是叶子条件的必填键；即使其它字段齐全，缺少它也必须在写入期被拦下。
    @Test
    void shouldRejectLeafRuleWithoutMatcherDuringWriteValidation() {
        TransformationRule rule = makeRule();
        MatchRule child = MatchRule.pathRule(MatchRule.Matcher.ANT, "/**");
        child.setMatcher(null);
        setMatchRuleDirectly(rule, makeGroup(child));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.children[0].matcher：页面路径条件缺少必填字段 \"matcher\"；该字段可选值为 "
                + "\"ANT\"、\"REGEX\"、\"EXACT\"",
            error.getReason());
    }

    // why: `negate` 也要求显式给出 true/false，避免后端把“省略字段”静默吞成 false。
    @Test
    void shouldRejectMatchRuleWithoutExplicitNegateDuringWriteValidation() {
        TransformationRule rule = makeRule();
        MatchRule child = MatchRule.pathRule(MatchRule.Matcher.ANT, "/**");
        setNegateDirectly(child, null);
        setNegateDefinedDirectly(child, false);
        setMatchRuleDirectly(rule, makeGroup(child));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.children[0].negate：缺少必填字段 \"negate\"；该字段可选值为 true 或 false",
            error.getReason());
    }

    // why: JSON 里的错键不能被静默吞掉，否则像 `matcher` 拼错这类问题会在保存时被悄悄写成默认行为。
    @Test
    void shouldRejectUnknownMatchRuleFieldDuringWriteValidation() throws Exception {
        String raw = """
            {
              "matchRule": {
                "type": "GROUP",
                "negate": false,
                "operator": "AND",
                "children": [
                  {
                    "type": "PATH",
                    "negate": false,
                    " m a tc her": "ANT",
                    "value": "/**"
                  }
                ]
              }
            }
            """;
        TransformationRule rule = objectMapper.readValue(raw, TransformationRule.class);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.children[0]. m a tc her：不支持该字段；页面路径条件仅支持 "
                + "\"type\"、\"negate\"、\"matcher\"、\"value\"",
            error.getReason()
        );
    }

    // why: 根节点固定为 GROUP，便于简单模式/JSON 模式共享统一的规则树结构。
    @Test
    void shouldRequireGroupAsRootNode() {
        TransformationRule rule = makeRule();
        setMatchRuleDirectly(rule, MatchRule.pathRule(MatchRule.Matcher.ANT, "/**"));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("matchRule.type：根节点必须是 GROUP", error.getReason());
    }

    // why: 后端也要拒绝“叶子节点带条件组字段”这类语义错误，避免只靠前端校验。
    @Test
    void shouldRejectChildrenOnLeafRule() {
        TransformationRule rule = makeRule();
        MatchRule child = MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**");
        child.setChildren(java.util.List.of(MatchRule.pathRule(MatchRule.Matcher.ANT, "/ignored")));
        setMatchRuleDirectly(rule, makeGroup(child));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.children[0].children：不支持该字段；页面路径条件仅支持 "
                + "\"type\"、\"negate\"、\"matcher\"、\"value\"",
            error.getReason()
        );
    }

    // why: 条件组即使带空字符串 value 也属于脏字段，后端要拒绝，避免“看似没值其实已写脏结构”。
    @Test
    void shouldRejectValueFieldOnGroupRuleEvenWhenBlank() {
        TransformationRule rule = makeRule();
        MatchRule group = makeGroup(MatchRule.pathRule(MatchRule.Matcher.ANT, "/**"));
        group.setValue("");
        setMatchRuleDirectly(rule, group);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.value：不支持该字段；条件组仅支持 \"type\"、\"negate\"、\"operator\"、\"children\"",
            error.getReason()
        );
    }

    // why: GROUP 上误带 matcher 时，也应走和前端一致的“条件组允许字段”提示，避免两端错误文案分叉。
    @Test
    void shouldRejectMatcherFieldOnGroupRuleWithSharedMessage() {
        TransformationRule rule = makeRule();
        MatchRule group = makeGroup(MatchRule.pathRule(MatchRule.Matcher.ANT, "/**"));
        group.setMatcher(MatchRule.Matcher.ANT);
        setMatchRuleDirectly(rule, group);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.matcher：不支持该字段；条件组仅支持 \"type\"、\"negate\"、\"operator\"、\"children\"",
            error.getReason()
        );
    }

    // why: 叶子节点显式携带空 children 也说明结构写脏了，不能因为它是空数组就悄悄放过。
    @Test
    void shouldRejectEmptyChildrenFieldOnLeafRule() {
        TransformationRule rule = makeRule();
        MatchRule child = MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**");
        child.setChildren(java.util.List.of());
        setMatchRuleDirectly(rule, makeGroup(child));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "matchRule.children[0].children：不支持该字段；页面路径条件仅支持 "
                + "\"type\"、\"negate\"、\"matcher\"、\"value\"",
            error.getReason()
        );
    }

    // why: 对愿意接受性能退化的用户，“路径 OR 模板”这类规则仍应允许保存，由配置页给出警告即可。
    @Test
    void shouldAllowUnsupportedDomPathPrecheckRule() {
        TransformationRule rule = makeRule();
        rule.setMode(TransformationRule.Mode.SELECTOR);
        rule.setMatch("main");

        MatchRule templateBranch = MatchRule.templateRule(MatchRule.Matcher.EXACT, "post");
        MatchRule pathBranch = MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**");
        MatchRule orGroup = new MatchRule();
        orGroup.setType(MatchRule.Type.GROUP);
        orGroup.setNegate(false);
        orGroup.setOperator(MatchRule.Operator.OR);
        orGroup.setChildren(java.util.List.of(pathBranch, templateBranch));
        setMatchRuleDirectly(rule, makeGroup(orGroup));

        assertDoesNotThrow(() -> validator.validateForWrite(rule).block());
    }

    // why: REMOVE 不会消费代码内容；写入期必须拒绝仍携带 snippetIds 的规则，避免产生误导性脏数据。
    @Test
    void shouldRejectSnippetIdsWhenPositionIsRemove() {
        TransformationRule rule = makeRule();
        rule.setMode(TransformationRule.Mode.SELECTOR);
        rule.setMatch("main");
        rule.setPosition(TransformationRule.Position.REMOVE);
        setSnippetIdsDirectly(rule, java.util.Set.of("snippet-a"));

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("snippetIds：REMOVE 模式下无需关联代码片段", error.getReason());
    }

    // why: REMOVE 会直接删除元素，写入期必须拒绝仍要求输出注释标记的规则，避免产生无意义配置。
    @Test
    void shouldRejectWrapMarkerWhenPositionIsRemove() {
        TransformationRule rule = makeRule();
        rule.setMode(TransformationRule.Mode.SELECTOR);
        rule.setMatch("main");
        rule.setPosition(TransformationRule.Position.REMOVE);
        setSnippetIdsDirectly(rule, java.util.Set.of());
        setWrapMarkerDirectly(rule, true);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("wrapMarker：REMOVE 模式下无需输出注释标记", error.getReason());
    }

    // why: runtimeOrder 是同阶段运行优先级契约；负数会破坏“值越小越靠前”的非负范围约定，必须在写入期拒绝。
    @Test
    void shouldRejectNegativeRuntimeOrder() {
        TransformationRule rule = makeRule();
        rule.setRuntimeOrder(-1);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("runtimeOrder：不能小于 0", error.getReason());
    }

    // why: `enabled` 会直接进入运行时布尔判断；若允许 null 落库，后续快照刷新和执行路径都可能因拆箱而炸掉。
    @Test
    void shouldRejectNullEnabled() {
        TransformationRule rule = makeRule();
        rule.setEnabled(null);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("enabled：必须是布尔值；仅支持 true 或 false", error.getReason());
    }

    // why: 注入模式决定运行时路由到哪条执行链；null 模式不是“缺省值”，而是会污染持久化语义的坏数据。
    @Test
    void shouldRejectNullMode() {
        TransformationRule rule = makeRule();
        rule.setMode(null);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals("mode：仅支持 \"HEAD\"、\"FOOTER\"、\"SELECTOR\"", error.getReason());
    }

    // why: selector 注入最终会把 `position` 送进执行器；若允许 null 落库，运行时 switch 会直接失败。
    @Test
    void shouldRejectNullPosition() {
        TransformationRule rule = makeRule();
        rule.setPosition(null);

        TransformationRuleValidationException error = assertThrows(
            TransformationRuleValidationException.class,
            () -> validator.validateForWrite(rule).block()
        );

        assertEquals(
            "position：仅支持 \"APPEND\"、\"PREPEND\"、\"BEFORE\"、\"AFTER\"、\"REPLACE\"、\"REMOVE\"",
            error.getReason()
        );
    }

    private TransformationRule makeRule() {
        TransformationRule rule = new TransformationRule();
        rule.setMatchRule(MatchRule.defaultRule());
        rule.setSnippetIds(new java.util.LinkedHashSet<>(java.util.Set.of("snippet-a")));
        return rule;
    }

    private MatchRule makeGroup(MatchRule child) {
        MatchRule group = new MatchRule();
        group.setType(MatchRule.Type.GROUP);
        group.setNegate(false);
        group.setOperator(MatchRule.Operator.AND);
        group.setChildren(java.util.List.of(child));
        return group;
    }

    private void setMatchRuleDirectly(TransformationRule rule, MatchRule matchRule) {
        try {
            var field = TransformationRule.class.getDeclaredField("matchRule");
            field.setAccessible(true);
            field.set(rule, matchRule);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setSnippetIdsDirectly(TransformationRule rule, java.util.Set<String> snippetIds) {
        try {
            var field = TransformationRule.class.getDeclaredField("snippetIds");
            field.setAccessible(true);
            field.set(rule, snippetIds);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setWrapMarkerDirectly(TransformationRule rule, boolean wrapMarker) {
        try {
            var field = TransformationRule.class.getDeclaredField("wrapMarker");
            field.setAccessible(true);
            field.set(rule, wrapMarker);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setNegateDirectly(MatchRule rule, Boolean negate) {
        try {
            var field = MatchRule.class.getDeclaredField("negate");
            field.setAccessible(true);
            field.set(rule, negate);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setNegateDefinedDirectly(MatchRule rule, boolean negateDefined) {
        try {
            var field = MatchRule.class.getDeclaredField("negateDefined");
            field.setAccessible(true);
            field.set(rule, negateDefined);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
