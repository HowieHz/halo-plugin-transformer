package top.howiehz.halo.transformer.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.RouteMatcher;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPatternRouteMatcher;
import org.springframework.web.util.pattern.PatternParseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.howiehz.halo.transformer.rule.MatchRule;
import top.howiehz.halo.transformer.runtime.store.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.runtime.store.TransformationSnippetRuntimeStore;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.extension.TransformationSnippet;

/**
 * @author HowieHz
 * @since 1.0.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RuntimeRuleResolver {
    protected final TransformationRuleRuntimeStore ruleRuntimeStore;
    protected final TransformationSnippetRuntimeStore snippetRuntimeStore;
    protected final RouteMatcher routeMatcher = createRouteMatcher();
    protected final Map<String, RegexPatternHolder> regexPatternCache = new ConcurrentHashMap<>();

    public Flux<RuntimeTransformationRule> getRulesByMode(TransformationRule.Mode mode) {
        return ruleRuntimeStore.listActiveByMode(mode);
    }

    public Flux<RuntimeTransformationRule> getMatchedRules(String targetPath,
        TransformationRule.Mode mode) {
        return getMatchedRules(targetPath, "", mode);
    }

    /**
     * why: 模板注入和 DOM 注入最终都走同一套规则求值，
     * 这样路径、模板 ID、取反与分组语义只维护一份，避免前后两条链路出现行为漂移。
     */
    public Flux<RuntimeTransformationRule> getMatchedRules(String targetPath,
        String templateId,
        TransformationRule.Mode mode) {
        MatchContext context =
            new MatchContext(targetPath, templateId, parseCurrentPathRoute(targetPath));
        return getRulesByMode(mode)
            .filter(rule -> evaluate(rule.matchRule(), context) == MatchState.MATCH)
            .onErrorResume(e -> {
                log.error("Failed to get matched rules for mode: {}", mode, e);
                return Flux.empty();
            });
    }

    /**
     * why: WebFilter 还拿不到模板上下文，先做一次“仅按路径”的低成本预筛，
     * 只有可能命中的 DOM 规则才值得进入整页 HTML 缓冲链路。
     */
    public Flux<RuntimeTransformationRule> getPathMatchedRules(String targetPath,
        TransformationRule.Mode mode) {
        if (targetPath.isEmpty()) {
            return Flux.empty();
        }
        RouteMatcher.Route currentRoute = parseCurrentPathRoute(targetPath);

        return getRulesByMode(mode)
            .filter(rule -> MatchRule.supportsDomPathPrecheck(rule.matchRule()))
            .filter(rule -> pathPrecheckMatches(rule.matchRule(), targetPath, currentRoute))
            .onErrorResume(e -> {
                log.error("Failed to get matched rules for mode: {}", mode, e);
                return Flux.empty();
            });
    }

    /**
     * why: 一旦 DOM 规则无法先靠页面路径缩小范围，WebFilter 就只能保守地包裹所有 HTML 页面，
     * 再等模板上下文就绪后做完整匹配；这里返回“当前请求是否需要进入这条慢路径”。
     */
    public Mono<Boolean> hasDomProcessCandidate(String targetPath,
        TransformationRule.Mode mode) {
        if (targetPath.isEmpty()) {
            return Mono.just(false);
        }
        RouteMatcher.Route currentRoute = parseCurrentPathRoute(targetPath);

        return getRulesByMode(mode)
            .any(rule -> !MatchRule.supportsDomPathPrecheck(rule.matchRule())
                || pathPrecheckMatches(rule.matchRule(), targetPath, currentRoute))
            .defaultIfEmpty(false);
    }

    /**
     * why: 同一次注入流程里，多条规则可能复用同一个代码片段；
     * 这里先按唯一 snippetId 批量拉取，再按各规则自己的顺序回拼，避免重复 `get` 同一条代码片段。
     */
    public Mono<List<ResolvedRuleCode>> resolveRuleCodes(List<RuntimeTransformationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Mono.just(List.of());
        }
        return loadSnippetsByIds(collectUniqueSnippetIds(rules))
            .map(snippetsById -> rules.stream()
                .map(rule -> new ResolvedRuleCode(rule, concatCode(rule, snippetsById)))
                .toList());
    }

    Mono<Map<String, TransformationSnippet>> loadSnippetsByIds(Collection<String> snippetIds) {
        return snippetRuntimeStore.getByIds(snippetIds)
            .map(LinkedHashMap::new);
    }

    List<String> collectUniqueSnippetIds(List<RuntimeTransformationRule> rules) {
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (RuntimeTransformationRule rule : rules) {
            if (rule == null || rule.snippetIds() == null || rule.snippetIds().isEmpty()) {
                continue;
            }
            uniqueIds.addAll(rule.snippetIds());
        }
        return List.copyOf(uniqueIds);
    }

    String concatCode(RuntimeTransformationRule rule,
        Map<String, TransformationSnippet> snippetsById) {
        if (rule == null || rule.snippetIds() == null || rule.snippetIds().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String snippetId : rule.snippetIds()) {
            TransformationSnippet snippet = snippetsById.get(snippetId);
            if (snippet == null || !snippet.isEnabled() || !snippet.isValid()) {
                continue;
            }
            builder.append(snippet.getCode());
        }
        return builder.toString();
    }

    private MatchState evaluate(MatchRule rule, MatchContext context) {
        if (rule == null || rule.getType() == null) {
            return MatchState.NO_MATCH;
        }
        MatchState state = switch (rule.getType()) {
            case GROUP -> evaluateGroup(rule, context);
            case PATH -> evaluatePath(rule, context.path(), context.route());
            case TEMPLATE_ID -> evaluateTemplateId(rule, context.templateId());
        };
        if (Boolean.TRUE.equals(rule.getNegate())) {
            return negate(state);
        }
        return state;
    }

    private MatchState evaluateGroup(MatchRule rule, MatchContext context) {
        if (rule.getChildren() == null || rule.getChildren().isEmpty()) {
            return MatchState.NO_MATCH;
        }
        MatchRule.Operator operator =
            rule.getOperator() == null ? MatchRule.Operator.AND : rule.getOperator();
        return switch (operator) {
            case AND -> evaluateAnd(rule, context);
            case OR -> evaluateOr(rule, context);
        };
    }

    private MatchState evaluateAnd(MatchRule rule, MatchContext context) {
        boolean hasUnknown = false;
        for (MatchRule child : rule.getChildren()) {
            MatchState state = evaluate(child, context);
            if (state == MatchState.NO_MATCH) {
                return MatchState.NO_MATCH;
            }
            if (state == MatchState.UNKNOWN) {
                hasUnknown = true;
            }
        }
        return hasUnknown ? MatchState.UNKNOWN : MatchState.MATCH;
    }

    private MatchState evaluateOr(MatchRule rule, MatchContext context) {
        boolean hasUnknown = false;
        for (MatchRule child : rule.getChildren()) {
            MatchState state = evaluate(child, context);
            if (state == MatchState.MATCH) {
                return MatchState.MATCH;
            }
            if (state == MatchState.UNKNOWN) {
                hasUnknown = true;
            }
        }
        return hasUnknown ? MatchState.UNKNOWN : MatchState.NO_MATCH;
    }

    private MatchState evaluatePath(MatchRule rule, String currentPath,
        RouteMatcher.Route currentRoute) {
        if (currentPath == null || currentPath.isBlank()) {
            return MatchState.NO_MATCH;
        }
        String value = rule.getValue() == null ? "" : rule.getValue().trim();
        if (value.isBlank()) {
            return MatchState.NO_MATCH;
        }
        MatchRule.Matcher matcher =
            rule.getMatcher() == null ? MatchRule.Matcher.ANT : rule.getMatcher();
        boolean matched = switch (matcher) {
            case EXACT -> value.equals(currentPath);
            case REGEX -> matchesRegex(value, currentPath);
            case ANT -> matchesAntPath(value, currentPath, currentRoute);
        };
        return matched ? MatchState.MATCH : MatchState.NO_MATCH;
    }

    private MatchState evaluateTemplateId(MatchRule rule, String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return MatchState.UNKNOWN;
        }
        String value = rule.getValue() == null ? "" : rule.getValue().trim();
        if (value.isBlank()) {
            return MatchState.NO_MATCH;
        }
        MatchRule.Matcher matcher =
            rule.getMatcher() == null ? MatchRule.Matcher.EXACT : rule.getMatcher();
        boolean matched = switch (matcher) {
            case EXACT -> value.equals(templateId.trim());
            case REGEX -> matchesRegex(value, templateId.trim());
            case ANT -> false;
        };
        return matched ? MatchState.MATCH : MatchState.NO_MATCH;
    }

    private boolean matchesAntPath(String pattern, String currentPath,
        RouteMatcher.Route currentRoute) {
        if (currentRoute == null) {
            return false;
        }
        try {
            return routeMatcher.match(pattern, currentRoute);
        } catch (PatternParseException e) {
            log.warn("Parse route pattern [{}] failed for path [{}]", pattern, currentPath, e);
            return false;
        }
    }

    private boolean matchesRegex(String pattern, String value) {
        RegexPatternHolder holder =
            regexPatternCache.computeIfAbsent(pattern, this::compileRegexHolder);
        if (holder.pattern() == null) {
            log.warn("Parse regex [{}] failed for value [{}]: {}", pattern, value,
                holder.errorMessage());
            return false;
        }
        return holder.pattern().matcher(value).matches();
    }

    private RegexPatternHolder compileRegexHolder(String pattern) {
        try {
            // 规则写入时已做合法性校验；这里缓存编译结果，避免请求期重复 Pattern.compile。
            return new RegexPatternHolder(compileRegexPattern(pattern), null);
        } catch (PatternSyntaxException e) {
            // 仍保留兜底，兼容历史坏数据或绕过写接口的脏数据；同时把失败结果也缓存，避免重复编译。
            return new RegexPatternHolder(null, e.getDescription());
        }
    }

    protected Pattern compileRegexPattern(String pattern) {
        return Pattern.compile(pattern);
    }

    /**
     * why: WebFilter 阶段还拿不到最终模板上下文，只能根据路径先判断“是否值得包裹响应体”。
     * 对能先按路径缩小范围的规则，这里可提前跳过大部分页面；
     * 对不能缩小范围的规则，则会退化成“所有 HTML 页面都先进入处理，再在后面看模板 ID”等完整条件。
     */
    private boolean pathPrecheckMatches(MatchRule rule, String currentPath,
        RouteMatcher.Route currentRoute) {
        if (rule == null || rule.getType() == null) {
            return false;
        }
        boolean matched = switch (rule.getType()) {
            case GROUP -> pathPrecheckGroupMatches(rule, currentPath, currentRoute);
            case PATH -> evaluatePath(rule, currentPath, currentRoute) == MatchState.MATCH;
            case TEMPLATE_ID -> true;
        };
        return Boolean.TRUE.equals(rule.getNegate()) ^ matched;
    }

    private boolean pathPrecheckGroupMatches(MatchRule rule, String currentPath,
        RouteMatcher.Route currentRoute) {
        if (rule.getChildren() == null || rule.getChildren().isEmpty()) {
            return false;
        }
        MatchRule.Operator operator =
            rule.getOperator() == null ? MatchRule.Operator.AND : rule.getOperator();
        return switch (operator) {
            case AND -> rule.getChildren().stream()
                .allMatch(child -> pathPrecheckMatches(child, currentPath, currentRoute));
            case OR -> rule.getChildren().stream()
                .anyMatch(child -> pathPrecheckMatches(child, currentPath, currentRoute));
        };
    }

    /**
     * why: 同一次规则求值里，页面路径本身不会变化；
     * 因此先把当前路径解析成 Route，再在多条 PATH/ANT 条件之间复用，避免重复 parseRoute。
     */
    protected RouteMatcher.Route parseCurrentPathRoute(String currentPath) {
        if (currentPath == null || currentPath.isBlank()) {
            return null;
        }
        return routeMatcher.parseRoute(currentPath);
    }

    private MatchState negate(MatchState state) {
        return switch (state) {
            case MATCH -> MatchState.NO_MATCH;
            case NO_MATCH -> MatchState.MATCH;
            case UNKNOWN -> MatchState.UNKNOWN;
        };
    }

    private RouteMatcher createRouteMatcher() {
        var parser = new PathPatternParser();
        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
        return new PathPatternRouteMatcher(parser);
    }

    private enum MatchState {
        MATCH, NO_MATCH, UNKNOWN
    }

    private record MatchContext(String path, String templateId, RouteMatcher.Route route) {
    }

    private record RegexPatternHolder(Pattern pattern, String errorMessage) {
    }

    public record ResolvedRuleCode(RuntimeTransformationRule rule, String code) {
    }
}
