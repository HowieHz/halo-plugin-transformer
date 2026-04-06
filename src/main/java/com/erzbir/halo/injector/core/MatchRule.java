package com.erzbir.halo.injector.core;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Data
public class MatchRule {
    private Type type = Type.GROUP;
    private Boolean negate;
    private Operator operator;
    private Matcher matcher;
    private String value;
    private List<MatchRule> children = new ArrayList<>();
    @JsonIgnore
    private boolean negateDefined;
    @JsonIgnore
    private boolean operatorDefined;
    @JsonIgnore
    private boolean matcherDefined;
    @JsonIgnore
    private boolean valueDefined;
    @JsonIgnore
    private boolean childrenDefined;
    @JsonIgnore
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public static MatchRule defaultRule() {
        MatchRule root = new MatchRule();
        root.setNegate(false);
        root.setOperator(Operator.AND);
        root.getChildren().add(pathRule(Matcher.ANT, "/**"));
        return root;
    }

    public static MatchRule pathRule(Matcher matcher, String value) {
        MatchRule rule = new MatchRule();
        rule.setType(Type.PATH);
        rule.setNegate(false);
        rule.setMatcher(matcher);
        rule.setValue(value);
        return rule;
    }

    public static MatchRule templateRule(Matcher matcher, String value) {
        MatchRule rule = new MatchRule();
        rule.setType(Type.TEMPLATE_ID);
        rule.setNegate(false);
        rule.setMatcher(matcher);
        rule.setValue(value);
        return rule;
    }

    /**
     * why: 写入期要区分“显式写了 false”和“完全没传 negate”，
     * 因此这里额外记录字段是否出现过，避免 Jackson 用默认值把“缺失”悄悄吞掉。
     */
    public void setNegate(Boolean negate) {
        this.negate = negate;
        this.negateDefined = true;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
        this.operatorDefined = true;
    }

    public void setMatcher(Matcher matcher) {
        this.matcher = matcher;
        this.matcherDefined = true;
    }

    public void setValue(String value) {
        this.value = value;
        this.valueDefined = true;
    }

    public void setChildren(List<MatchRule> children) {
        this.children = children == null ? null : new ArrayList<>(children);
        this.childrenDefined = true;
    }

    /**
     * why: 这是运行期的结构有效性快速判断，不应被 Jackson 暴露成 `valid` 字段；
     * 否则会污染注入规则的 JSON 返回值，并在前端回写时触发未知字段校验。
     */
    @JsonIgnore
    public boolean isValid() {
        if (type == null) {
            return false;
        }
        // 这里只保留运行时快速判定，避免每次请求都重复编译正则；
        // 非法正则由写入期校验兜底，防止把坏数据持久化进去。
        return switch (type) {
            case GROUP -> children != null
                    && !children.isEmpty()
                    && (operator == Operator.AND || operator == Operator.OR)
                    && children.stream().allMatch(child -> child != null && child.isValid());
            case PATH -> supportsPathMatcher(matcher) && StringUtils.hasText(value);
            case TEMPLATE_ID -> supportsTemplateMatcher(matcher) && StringUtils.hasText(value);
        };
    }

    /**
     * why: 写接口只接受“结构完整且语义明确”的规则树，
     * 这样前后端都能围绕同一份模型约束工作，避免半合法对象进入存储层后再在运行时兜底。
     */
    public static void validateForWrite(MatchRule rule) {
        validateForWrite(rule, "matchRule", true);
    }

    @JsonAnySetter
    public void recordUnknownField(String key, Object ignoredValue) {
        unknownFields.add(key);
    }

    /**
     * why: DOM 注入在 WebFilter 阶段只能先看到页面路径；
     * 这里用于判断规则能否先按路径缩小范围，从而避免退化成“所有 HTML 页面都先进入处理链路”。
     */
    public static boolean supportsDomPathPrecheck(MatchRule rule) {
        return pathPrecheckKind(rule) == PathPrecheckKind.PATH_SCOPED;
    }

    private static void validateForWrite(MatchRule rule, String path, boolean requireGroupRoot) {
        if (rule == null) {
            throw new IllegalArgumentException(path + "：不能为空");
        }
        if (!rule.getUnknownFields().isEmpty()) {
            String field = rule.getUnknownFields().iterator().next();
            throw new IllegalArgumentException(path + "." + field + "：" + unknownFieldMessage(rule.getType()));
        }
        if (rule.getType() == null) {
            throw new IllegalArgumentException(path + ".type：不能为空");
        }
        if (requireGroupRoot && rule.getType() != Type.GROUP) {
            throw new IllegalArgumentException(path + ".type：根节点必须是 GROUP");
        }
        if (!rule.isNegateDefined()) {
            throw new IllegalArgumentException(path + ".negate：缺少必填字段 \"negate\"；该字段可选值为 true、false");
        }
        if (rule.getNegate() == null) {
            throw new IllegalArgumentException(path + ".negate：必须是布尔值；仅支持 true 或 false");
        }

        switch (rule.getType()) {
            case GROUP -> validateGroupRule(rule, path);
            case PATH -> validatePathRule(rule, path);
            case TEMPLATE_ID -> validateTemplateRule(rule, path);
        }
    }

    private static void validateGroupRule(MatchRule rule, String path) {
        if (rule.isMatcherDefined()) {
            throw new IllegalArgumentException(path + ".matcher：仅叶子条件可使用 matcher");
        }
        if (rule.isValueDefined()) {
            throw new IllegalArgumentException(path + ".value：仅叶子条件可使用 value");
        }
        List<MatchRule> children = rule.getChildren();
        if (children == null || children.isEmpty()) {
            throw new IllegalArgumentException(path + ".children：不能有空组");
        }
        if (rule.getOperator() == null) {
            throw new IllegalArgumentException(path + ".operator：缺少必填字段 \"operator\"；该字段可选值为 \"AND\"、\"OR\"");
        }
        if (rule.getOperator() != Operator.AND && rule.getOperator() != Operator.OR) {
            throw new IllegalArgumentException(path + ".operator：仅支持 \"AND\" 或 \"OR\"");
        }
        for (int index = 0; index < children.size(); index++) {
            validateForWrite(children.get(index), path + ".children[" + index + "]", false);
        }
    }

    private static void validatePathRule(MatchRule rule, String path) {
        if (rule.isOperatorDefined()) {
            throw new IllegalArgumentException(path + ".operator：仅条件组可使用 operator");
        }
        if (rule.isChildrenDefined()) {
            throw new IllegalArgumentException(path + ".children：仅条件组可使用 children");
        }
        if (rule.getMatcher() == null) {
            throw new IllegalArgumentException(path + ".matcher：缺少必填字段 \"matcher\"；该字段可选值为 \"ANT\"、\"REGEX\"、\"EXACT\"");
        }
        if (!rule.supportsPathMatcher(rule.getMatcher())) {
            throw new IllegalArgumentException(path + ".matcher：路径规则仅支持 \"ANT\"、\"REGEX\"、\"EXACT\"");
        }
        if (!StringUtils.hasText(rule.getValue())) {
            throw new IllegalArgumentException(path + ".value：必须是非空字符串");
        }
        validateRegexIfNeeded(rule.getMatcher(), rule.getValue(), path + ".value");
    }

    private static void validateTemplateRule(MatchRule rule, String path) {
        if (rule.isOperatorDefined()) {
            throw new IllegalArgumentException(path + ".operator：仅条件组可使用 operator");
        }
        if (rule.isChildrenDefined()) {
            throw new IllegalArgumentException(path + ".children：仅条件组可使用 children");
        }
        if (rule.getMatcher() == null) {
            throw new IllegalArgumentException(path + ".matcher：缺少必填字段 \"matcher\"；该字段可选值为 \"REGEX\"、\"EXACT\"");
        }
        if (!rule.supportsTemplateMatcher(rule.getMatcher())) {
            throw new IllegalArgumentException(path + ".matcher：模板 ID 仅支持 \"REGEX\" 或 \"EXACT\"");
        }
        if (!StringUtils.hasText(rule.getValue())) {
            throw new IllegalArgumentException(path + ".value：必须是非空字符串");
        }
        validateRegexIfNeeded(rule.getMatcher(), rule.getValue(), path + ".value");
    }

    private static void validateRegexIfNeeded(Matcher matcher, String value, String path) {
        if (matcher != Matcher.REGEX) {
            return;
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(path + "：正则表达式无效，" + e.getDescription(), e);
        }
    }

    private static String unknownFieldMessage(Type type) {
        if (type == Type.GROUP) {
            return "不支持该字段；条件组仅支持 \"type\"、\"negate\"、\"operator\"、\"children\"";
        }
        if (type == Type.PATH) {
            return "不支持该字段；页面路径条件仅支持 \"type\"、\"negate\"、\"matcher\"、\"value\"";
        }
        if (type == Type.TEMPLATE_ID) {
            return "不支持该字段；模板 ID 条件仅支持 \"type\"、\"negate\"、\"matcher\"、\"value\"";
        }
        return "不支持该字段";
    }

    /**
     * why: 这里不是在做完整匹配，而是在做“这棵树能否仅依赖路径预筛”的静态分类，
     * 供 DOM 注入在写入期和运行期快速识别是否会退化成“先处理所有页面，再继续判断其它条件”。
     */
    private static PathPrecheckKind pathPrecheckKind(MatchRule rule) {
        if (rule == null || rule.getType() == null) {
            return PathPrecheckKind.UNSUPPORTED;
        }
        if (Boolean.TRUE.equals(rule.getNegate())) {
            return pathPrecheckKindForNegated(rule);
        }
        return switch (rule.getType()) {
            case PATH -> PathPrecheckKind.PATH_SCOPED;
            case TEMPLATE_ID -> PathPrecheckKind.TEMPLATE_ONLY;
            case GROUP -> pathPrecheckKindForGroup(rule);
        };
    }

    /**
     * why: 否定模板条件无法在未知模板 ID 的 WebFilter 阶段安全下结论，
     * 一旦放开就会把“是否需要缓冲 HTML”拖成全站兜底，因此直接判为不支持。
     */
    private static PathPrecheckKind pathPrecheckKindForNegated(MatchRule rule) {
        return switch (rule.getType()) {
            case PATH -> PathPrecheckKind.PATH_SCOPED;
            case TEMPLATE_ID -> PathPrecheckKind.UNSUPPORTED;
            case GROUP -> containsTemplateRule(rule)
                    ? PathPrecheckKind.UNSUPPORTED
                    : pathPrecheckKindForGroupWithoutNegate(rule);
        };
    }

    private static PathPrecheckKind pathPrecheckKindForGroup(MatchRule rule) {
        return pathPrecheckKindForGroupWithoutNegate(rule);
    }

    /**
     * why: 组节点的预筛能力取决于子节点组合方式：
     * AND 可把模板约束挂在路径命中之后，OR 则必须避免“路径分支 / 模板分支”混合短路。
     */
    private static PathPrecheckKind pathPrecheckKindForGroupWithoutNegate(MatchRule rule) {
        List<MatchRule> children = rule.getChildren();
        if (children == null || children.isEmpty()) {
            return PathPrecheckKind.UNSUPPORTED;
        }
        return switch (rule.getOperator() == null ? Operator.AND : rule.getOperator()) {
            case AND -> pathPrecheckKindForAnd(children);
            case OR -> pathPrecheckKindForOr(children);
        };
    }

    /**
     * why: AND 中只要存在一个路径条件，模板条件就只是附加收窄；
     * 但若任何子节点本身已无法安全预筛，整棵树也必须一并拒绝。
     */
    private static PathPrecheckKind pathPrecheckKindForAnd(List<MatchRule> children) {
        boolean hasPathScoped = false;
        for (MatchRule child : children) {
            PathPrecheckKind kind = pathPrecheckKind(child);
            if (kind == PathPrecheckKind.UNSUPPORTED) {
                return PathPrecheckKind.UNSUPPORTED;
            }
            if (kind == PathPrecheckKind.PATH_SCOPED) {
                hasPathScoped = true;
            }
        }
        return hasPathScoped ? PathPrecheckKind.PATH_SCOPED : PathPrecheckKind.TEMPLATE_ONLY;
    }

    /**
     * why: OR 只要混入“纯模板分支”和“路径分支”，就无法仅凭路径判断是否需要缓冲响应体；
     * 这种结构对 DOM 注入来说会退化成全站处理，因此标记为不支持。
     */
    private static PathPrecheckKind pathPrecheckKindForOr(List<MatchRule> children) {
        boolean hasPathScoped = false;
        boolean hasTemplateOnly = false;
        for (MatchRule child : children) {
            PathPrecheckKind kind = pathPrecheckKind(child);
            if (kind == PathPrecheckKind.UNSUPPORTED) {
                return PathPrecheckKind.UNSUPPORTED;
            }
            if (kind == PathPrecheckKind.PATH_SCOPED) {
                hasPathScoped = true;
            }
            if (kind == PathPrecheckKind.TEMPLATE_ONLY) {
                hasTemplateOnly = true;
            }
        }
        if (hasPathScoped && hasTemplateOnly) {
            return PathPrecheckKind.UNSUPPORTED;
        }
        if (hasPathScoped) {
            return PathPrecheckKind.PATH_SCOPED;
        }
        return PathPrecheckKind.TEMPLATE_ONLY;
    }

    private static boolean containsTemplateRule(MatchRule rule) {
        if (rule == null || rule.getType() == null) {
            return false;
        }
        return switch (rule.getType()) {
            case TEMPLATE_ID -> true;
            case PATH -> false;
            case GROUP -> rule.getChildren() != null
                    && rule.getChildren().stream().anyMatch(MatchRule::containsTemplateRule);
        };
    }

    private boolean supportsPathMatcher(Matcher matcher) {
        return matcher == Matcher.ANT || matcher == Matcher.REGEX || matcher == Matcher.EXACT;
    }

    private boolean supportsTemplateMatcher(Matcher matcher) {
        return matcher == Matcher.EXACT || matcher == Matcher.REGEX;
    }

    public enum Type {
        GROUP, PATH, TEMPLATE_ID
    }

    public enum Operator {
        AND, OR
    }

    public enum Matcher {
        ANT, REGEX, EXACT
    }

    private enum PathPrecheckKind {
        PATH_SCOPED,
        TEMPLATE_ONLY,
        UNSUPPORTED
    }
}
