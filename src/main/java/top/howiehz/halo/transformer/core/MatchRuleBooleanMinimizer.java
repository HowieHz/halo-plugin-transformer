package top.howiehz.halo.transformer.core;

import java.util.*;

/**
 * why: 布尔规则最小化只服务于运行期与分析期，避免把同义但更冗长的树直接带进
 * 运行时快照后重复做无效判断；同时不改写用户编辑态原树，防止控制台出现“我写的结构被偷偷改掉”的心智错位。
 */
public final class MatchRuleBooleanMinimizer {
    private static final String MATCH_ALL_PATH = "/**";
    private static final AnalysisExpression CONST_TRUE = new ConstantExpression(true);
    private static final AnalysisExpression CONST_FALSE = new ConstantExpression(false);

    private MatchRuleBooleanMinimizer() {
    }

    public static MatchRule minimizeForRuntime(MatchRule rule) {
        return toMatchRule(simplify(buildExpression(rule)));
    }

    static String minimizedSummary(MatchRule rule) {
        return formatExpression(simplify(buildExpression(rule)), true);
    }

    private static AnalysisExpression buildExpression(MatchRule rule) {
        if (rule == null || rule.getType() == null) {
            return CONST_FALSE;
        }

        AnalysisExpression base = switch (rule.getType()) {
            case GROUP -> new GroupExpression(
                rule.getOperator() == MatchRule.Operator.OR ? MatchRule.Operator.OR
                    : MatchRule.Operator.AND,
                rule.getChildren() == null
                    ? List.of()
                    : rule.getChildren().stream().map(MatchRuleBooleanMinimizer::buildExpression)
                        .toList()
            );
            case PATH -> new LeafExpression(
                MatchRule.Type.PATH,
                rule.getMatcher() == MatchRule.Matcher.REGEX
                    || rule.getMatcher() == MatchRule.Matcher.EXACT
                    ? rule.getMatcher()
                    : MatchRule.Matcher.ANT,
                rule.getValue() == null ? "" : rule.getValue().trim()
            );
            case TEMPLATE_ID -> new LeafExpression(
                MatchRule.Type.TEMPLATE_ID,
                rule.getMatcher() == MatchRule.Matcher.REGEX ? MatchRule.Matcher.REGEX
                    : MatchRule.Matcher.EXACT,
                rule.getValue() == null ? "" : rule.getValue().trim()
            );
        };

        return Boolean.TRUE.equals(rule.getNegate()) ? new NotExpression(base) : base;
    }

    private static MatchRule toMatchRule(AnalysisExpression expression) {
        return switch (expression) {
            case ConstantExpression constantExpression -> constantExpression.value()
                ? MatchRule.pathRule(MatchRule.Matcher.ANT, MATCH_ALL_PATH)
                : negated(MatchRule.pathRule(MatchRule.Matcher.ANT, MATCH_ALL_PATH));
            case LeafExpression leafExpression -> leafExpression.type() == MatchRule.Type.PATH
                ? MatchRule.pathRule(leafExpression.matcher(), leafExpression.value())
                : MatchRule.templateRule(leafExpression.matcher(), leafExpression.value());
            case NotExpression notExpression -> negated(toMatchRule(notExpression.child()));
            case GroupExpression groupExpression -> {
                MatchRule groupRule = new MatchRule();
                groupRule.setType(MatchRule.Type.GROUP);
                groupRule.setNegate(false);
                groupRule.setOperator(groupExpression.operator());
                groupRule.setChildren(groupExpression.children().stream()
                    .map(MatchRuleBooleanMinimizer::toMatchRule)
                    .toList());
                yield groupRule;
            }
        };
    }

    private static MatchRule negated(MatchRule rule) {
        rule.setNegate(!Boolean.TRUE.equals(rule.getNegate()));
        return rule;
    }

    private static AnalysisExpression simplify(AnalysisExpression expression) {
        return switch (expression) {
            case ConstantExpression constantExpression -> constantExpression;
            case LeafExpression leafExpression -> leafExpression;
            case NotExpression notExpression ->
                simplifyNot(new NotExpression(simplify(notExpression.child())));
            case GroupExpression groupExpression -> simplifyGroup(new GroupExpression(
                groupExpression.operator(),
                groupExpression.children().stream().map(MatchRuleBooleanMinimizer::simplify)
                    .toList()
            ));
        };
    }

    private static AnalysisExpression simplifyNot(NotExpression expression) {
        AnalysisExpression child = expression.child();
        if (child instanceof ConstantExpression(boolean value)) {
            return value ? CONST_FALSE : CONST_TRUE;
        }
        if (child instanceof NotExpression(AnalysisExpression child1)) {
            return simplify(child1);
        }
        return expression;
    }

    private static AnalysisExpression simplifyGroup(GroupExpression expression) {
        AnalysisExpression current = expression;
        while (current instanceof GroupExpression(
            MatchRule.Operator operator1, List<AnalysisExpression> children1
        )) {
            List<AnalysisExpression> flattenedChildren = children1.stream()
                .flatMap(child -> child instanceof GroupExpression(
                    MatchRule.Operator operator, List<AnalysisExpression> children
                )
                    && operator == operator1
                    ? children.stream()
                    : java.util.stream.Stream.of(child))
                .toList();
            GroupExpression flattened = new GroupExpression(
                operator1,
                sortExpressions(deduplicateExpressions(flattenedChildren))
            );
            AnalysisExpression next = simplifyGroupOnce(flattened);
            if (expressionKey(next).equals(expressionKey(current))) {
                return next;
            }
            current = simplify(next);
        }
        return current;
    }

    private static AnalysisExpression simplifyGroupOnce(GroupExpression expression) {
        AnalysisExpression deMorganCandidate = buildReverseDeMorganCandidate(expression);
        if (shouldPrefer(expression, deMorganCandidate)) {
            return deMorganCandidate;
        }

        AnalysisExpression factorizedCandidate = buildFactorizedCandidate(expression);
        if (shouldPrefer(expression, factorizedCandidate)) {
            return factorizedCandidate;
        }

        AnalysisExpression absorbedCandidate = buildAbsorbedCandidate(expression);
        if (shouldPrefer(expression, absorbedCandidate)) {
            return absorbedCandidate;
        }

        AnalysisExpression complementedCandidate = foldComplement(expression);
        if (shouldPrefer(expression, complementedCandidate)) {
            return complementedCandidate;
        }

        return foldConstants(expression);
    }

    private static AnalysisExpression buildReverseDeMorganCandidate(GroupExpression expression) {
        if (expression.children().size() < 2
            || expression.children().stream()
            .anyMatch(child -> !(child instanceof NotExpression))) {
            return null;
        }
        List<AnalysisExpression> unwrappedChildren = expression.children().stream()
            .map(child -> ((NotExpression) child).child())
            .toList();
        return new NotExpression(new GroupExpression(
            expression.operator() == MatchRule.Operator.AND ? MatchRule.Operator.OR
                : MatchRule.Operator.AND,
            unwrappedChildren
        ));
    }

    private static AnalysisExpression buildFactorizedCandidate(GroupExpression expression) {
        if (expression.operator() != MatchRule.Operator.OR || expression.children().size() < 2) {
            return null;
        }

        List<List<AnalysisExpression>> factorizedTerms = expression.children().stream()
            .map(child -> child instanceof GroupExpression(
                MatchRule.Operator operator, List<AnalysisExpression> children
            )
                && operator == MatchRule.Operator.AND
                ? children
                : List.of(child))
            .toList();
        List<String> commonFactorKeys = intersectExpressionKeys(factorizedTerms);
        if (commonFactorKeys.isEmpty()) {
            return null;
        }

        List<AnalysisExpression> commonFactors = factorizedTerms.getFirst().stream()
            .filter(term -> commonFactorKeys.contains(expressionKey(term)))
            .toList();
        List<AnalysisExpression> residualTerms = factorizedTerms.stream()
            .map(termFactors -> buildResidualExpression(termFactors, commonFactorKeys))
            .toList();

        List<AnalysisExpression> factoredChildren = new ArrayList<>(commonFactors);
        factoredChildren.add(new GroupExpression(MatchRule.Operator.OR, residualTerms));
        return new GroupExpression(MatchRule.Operator.AND, factoredChildren);
    }

    private static AnalysisExpression buildResidualExpression(List<AnalysisExpression> termFactors,
        List<String> commonFactorKeys) {
        List<AnalysisExpression> residualFactors = termFactors.stream()
            .filter(term -> !commonFactorKeys.contains(expressionKey(term)))
            .toList();
        if (residualFactors.isEmpty()) {
            return CONST_TRUE;
        }
        if (residualFactors.size() == 1) {
            return residualFactors.getFirst();
        }
        return new GroupExpression(MatchRule.Operator.AND, residualFactors);
    }

    private static AnalysisExpression buildAbsorbedCandidate(GroupExpression expression) {
        if (expression.operator() != MatchRule.Operator.AND) {
            return null;
        }

        Set<String> directChildKeys = expression.children().stream()
            .filter(child -> !(child instanceof GroupExpression childGroup
                && childGroup.operator() == MatchRule.Operator.OR))
            .map(MatchRuleBooleanMinimizer::expressionKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<AnalysisExpression> filteredChildren = expression.children().stream()
            .filter(child -> !(child instanceof GroupExpression(
                MatchRule.Operator operator, List<AnalysisExpression> children
            )
                && operator == MatchRule.Operator.OR
                && children.stream().anyMatch(option ->
                directChildKeys.contains(expressionKey(option)))))
            .toList();

        if (filteredChildren.size() == expression.children().size()) {
            return null;
        }
        return new GroupExpression(MatchRule.Operator.AND, filteredChildren);
    }

    private static AnalysisExpression foldComplement(GroupExpression expression) {
        Set<String> childKeys = expression.children().stream()
            .map(MatchRuleBooleanMinimizer::expressionKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (AnalysisExpression child : expression.children()) {
            if (child instanceof NotExpression(AnalysisExpression child1)
                && childKeys.contains(expressionKey(child1))) {
                return expression.operator() == MatchRule.Operator.OR ? CONST_TRUE : CONST_FALSE;
            }
        }
        return null;
    }

    private static AnalysisExpression foldConstants(GroupExpression expression) {
        if (expression.operator() == MatchRule.Operator.AND) {
            if (expression.children().stream()
                .anyMatch(child -> child instanceof ConstantExpression(boolean value)
                    && !value)) {
                return CONST_FALSE;
            }
            List<AnalysisExpression> nonTrueChildren = expression.children().stream()
                .filter(child -> !(child instanceof ConstantExpression(boolean value)
                    && value))
                .toList();
            if (nonTrueChildren.isEmpty()) {
                return CONST_TRUE;
            }
            if (nonTrueChildren.size() == 1) {
                return nonTrueChildren.getFirst();
            }
            return new GroupExpression(MatchRule.Operator.AND, nonTrueChildren);
        }

        if (expression.children().stream()
            .anyMatch(child -> child instanceof ConstantExpression(boolean value)
                && value)) {
            return CONST_TRUE;
        }
        List<AnalysisExpression> nonFalseChildren = expression.children().stream()
            .filter(child -> !(child instanceof ConstantExpression(boolean value)
                && !value))
            .toList();
        if (nonFalseChildren.isEmpty()) {
            return CONST_FALSE;
        }
        if (nonFalseChildren.size() == 1) {
            return nonFalseChildren.getFirst();
        }
        return new GroupExpression(MatchRule.Operator.OR, nonFalseChildren);
    }

    private static List<AnalysisExpression> deduplicateExpressions(
        List<AnalysisExpression> expressions) {
        Map<String, AnalysisExpression> deduplicated = new LinkedHashMap<>();
        for (AnalysisExpression expression : expressions) {
            deduplicated.put(expressionKey(expression), expression);
        }
        return List.copyOf(deduplicated.values());
    }

    private static List<AnalysisExpression> sortExpressions(List<AnalysisExpression> expressions) {
        return expressions.stream()
            .sorted(MatchRuleBooleanMinimizer::compareExpressions)
            .toList();
    }

    private static int compareExpressions(AnalysisExpression left, AnalysisExpression right) {
        int rankDifference = Integer.compare(expressionSortRank(left), expressionSortRank(right));
        if (rankDifference != 0) {
            return rankDifference;
        }
        return expressionKey(left).compareTo(expressionKey(right));
    }

    private static int expressionSortRank(AnalysisExpression expression) {
        return switch (expression) {
            case ConstantExpression ignored -> 0;
            case LeafExpression ignored -> 1;
            case NotExpression ignored -> 2;
            case GroupExpression ignored -> 3;
        };
    }

    private static List<String> intersectExpressionKeys(List<List<AnalysisExpression>> terms) {
        if (terms.isEmpty()) {
            return List.of();
        }
        Set<String> sharedKeys = new LinkedHashSet<>(terms.getFirst().stream()
            .map(MatchRuleBooleanMinimizer::expressionKey)
            .toList());
        for (List<AnalysisExpression> termFactors : terms.subList(1, terms.size())) {
            Set<String> currentKeys = termFactors.stream()
                .map(MatchRuleBooleanMinimizer::expressionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            sharedKeys.removeIf(key -> !currentKeys.contains(key));
        }
        return sharedKeys.stream().sorted().toList();
    }

    private static boolean shouldPrefer(AnalysisExpression current, AnalysisExpression candidate) {
        return candidate != null && expressionComplexity(candidate) < expressionComplexity(current);
    }

    private static int expressionComplexity(AnalysisExpression expression) {
        return switch (expression) {
            case ConstantExpression ignored -> 0;
            case LeafExpression ignored -> 1;
            case NotExpression notExpression -> 1 + expressionComplexity(notExpression.child());
            case GroupExpression groupExpression -> 1 + groupExpression.children().stream()
                .mapToInt(MatchRuleBooleanMinimizer::expressionComplexity)
                .sum();
        };
    }

    private static String expressionKey(AnalysisExpression expression) {
        return switch (expression) {
            case ConstantExpression constantExpression ->
                constantExpression.value() ? "TRUE" : "FALSE";
            case LeafExpression leafExpression ->
                leafExpression.type() + ":" + leafExpression.matcher() + ":"
                    + leafExpression.value();
            case NotExpression notExpression -> "!" + expressionKey(notExpression.child());
            case GroupExpression groupExpression -> groupExpression.operator() + "("
                + sortExpressions(groupExpression.children()).stream()
                .map(MatchRuleBooleanMinimizer::expressionKey)
                .collect(java.util.stream.Collectors.joining(","))
                + ")";
        };
    }

    private static String formatExpression(AnalysisExpression expression, boolean root) {
        return switch (expression) {
            case ConstantExpression constantExpression ->
                constantExpression.value() ? "TRUE" : "FALSE";
            case LeafExpression leafExpression -> {
                String subject = leafExpression.type() == MatchRule.Type.PATH ? "path" : "id";
                String matcher = switch (leafExpression.matcher()) {
                    case REGEX -> "re";
                    case EXACT -> "=";
                    case ANT -> "ant";
                };
                yield subject + ":" + matcher + ":" + leafExpression.value();
            }
            case NotExpression notExpression -> {
                String child = formatExpression(notExpression.child(), true);
                yield notExpression.child() instanceof GroupExpression ? "!(" + child + ")"
                    : "!" + child;
            }
            case GroupExpression groupExpression -> {
                String operator =
                    groupExpression.operator() == MatchRule.Operator.OR ? " | " : " & ";
                String content = groupExpression.children().stream()
                    .map(child -> formatExpression(child, false))
                    .collect(java.util.stream.Collectors.joining(operator));
                yield root ? content : "(" + content + ")";
            }
        };
    }

    sealed interface AnalysisExpression
        permits ConstantExpression, LeafExpression, NotExpression, GroupExpression {
    }

    private record ConstantExpression(boolean value) implements AnalysisExpression {
    }

    private record LeafExpression(MatchRule.Type type, MatchRule.Matcher matcher,
                                  String value) implements AnalysisExpression {
    }

    private record NotExpression(AnalysisExpression child) implements AnalysisExpression {
    }

    private record GroupExpression(MatchRule.Operator operator,
                                   List<AnalysisExpression> children)
        implements AnalysisExpression {
    }
}
