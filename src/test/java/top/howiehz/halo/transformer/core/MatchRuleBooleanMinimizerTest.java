package top.howiehz.halo.transformer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRuleBooleanMinimizerTest {
    @Test
    void shouldMapRuntimeTrueToMatchAllPathRule() {
        MatchRule minimized = MatchRuleBooleanMinimizer.minimizeForRuntime(group(
                MatchRule.Operator.OR,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                negated(MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"))
        ));

        // why: 运行时没有布尔常量节点；最小化结果若为 true，必须落到一个稳定、可执行的
        // match-rule 叶子表达式，而不是再额外引入一套“只给运行时用”的存储模型。
        assertEquals(MatchRule.Type.PATH, minimized.getType());
        assertEquals(MatchRule.Matcher.ANT, minimized.getMatcher());
        assertEquals("/**", minimized.getValue());
        assertFalse(Boolean.TRUE.equals(minimized.getNegate()));
    }

    @Test
    void shouldMapRuntimeFalseToNegatedMatchAllPathRule() {
        MatchRule minimized = MatchRuleBooleanMinimizer.minimizeForRuntime(group(
                MatchRule.Operator.AND,
                MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                negated(MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"))
        ));

        // why: false 也需要映射成现有 runtime 能直接执行的规则树，
        // 避免运行时快照再为“常量节点”补一条特殊分支。
        assertEquals(MatchRule.Type.PATH, minimized.getType());
        assertEquals(MatchRule.Matcher.ANT, minimized.getMatcher());
        assertEquals("/**", minimized.getValue());
        assertTrue(Boolean.TRUE.equals(minimized.getNegate()));
    }

    @Test
    void shouldKeepFactorizedStructureExecutableForRuntime() {
        MatchRule minimized = MatchRuleBooleanMinimizer.minimizeForRuntime(group(
                MatchRule.Operator.OR,
                group(
                        MatchRule.Operator.AND,
                        MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                        MatchRule.templateRule(MatchRule.Matcher.EXACT, "post")
                ),
                group(
                        MatchRule.Operator.AND,
                        MatchRule.pathRule(MatchRule.Matcher.ANT, "/posts/**"),
                        MatchRule.templateRule(MatchRule.Matcher.EXACT, "page")
                )
        ));

        // why: 共享 contract 已经锁住“表达式长什么样”，
        // 这里额外锁运行时输出仍是一棵合法、可执行的 match-rule 树。
        assertEquals(MatchRule.Type.GROUP, minimized.getType());
        assertEquals(MatchRule.Operator.AND, minimized.getOperator());
        assertNotNull(minimized.getChildren());
        assertEquals(2, minimized.getChildren().size());
        assertEquals(MatchRule.Type.PATH, minimized.getChildren().getFirst().getType());
        assertEquals(MatchRule.Type.GROUP, minimized.getChildren().get(1).getType());
        assertEquals(MatchRule.Operator.OR, minimized.getChildren().get(1).getOperator());
    }

    private static MatchRule group(MatchRule.Operator operator, MatchRule... children) {
        MatchRule groupRule = new MatchRule();
        groupRule.setType(MatchRule.Type.GROUP);
        groupRule.setNegate(false);
        groupRule.setOperator(operator);
        groupRule.setChildren(List.of(children));
        return groupRule;
    }

    private static MatchRule negated(MatchRule rule) {
        rule.setNegate(true);
        return rule;
    }
}
