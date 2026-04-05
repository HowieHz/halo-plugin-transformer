package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.manager.CodeSnippetManager;
import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @author Erzbir
 * @since 1.0.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InjectHelper {
    protected final InjectionRuleManager ruleManager;
    protected final CodeSnippetManager snippetManager;
    protected final RouteMatcher routeMatcher = createRouteMatcher();
    protected final Map<String, RegexPatternHolder> regexPatternCache = new ConcurrentHashMap<>();

    public Flux<InjectionRule> getRulesByMode(InjectionRule.Mode mode) {
        return ruleManager.list()
                .filter(rule -> mode.equals(rule.getMode()));
    }

    public Flux<InjectionRule> getMatchedRules(String targetPath,
                                               InjectionRule.Mode mode) {
        return getMatchedRules(targetPath, "", mode);
    }

    /**
     * why: 模板注入和 DOM 注入最终都走同一套规则求值，
     * 这样路径、模板 ID、取反与分组语义只维护一份，避免前后两条链路出现行为漂移。
     */
    public Flux<InjectionRule> getMatchedRules(String targetPath,
                                               String templateId,
                                               InjectionRule.Mode mode) {
        MatchContext context = new MatchContext(targetPath, templateId);
        return getRulesByMode(mode)
                .filter(rule -> rule.isEnabled() && rule.isValid())
                .filter(rule -> evaluate(rule.getMatchRule(), context) == MatchState.MATCH)
                .onErrorResume(e -> {
                    log.error("Failed to get matched rules for mode: {}", mode, e);
                    return Flux.empty();
                });
    }

    /**
     * why: WebFilter 还拿不到模板上下文，先做一次“仅按路径”的低成本预筛，
     * 只有可能命中的 DOM 规则才值得进入整页 HTML 缓冲链路。
     */
    public Flux<InjectionRule> getPathMatchedRules(String targetPath,
                                                   InjectionRule.Mode mode) {
        if (targetPath.isEmpty()) {
            return Flux.empty();
        }

        return getRulesByMode(mode)
                .filter(rule -> rule.isEnabled() && rule.isValid())
                .filter(rule -> MatchRule.supportsDomPathPrecheck(rule.getMatchRule()))
                .filter(rule -> pathPrecheckMatches(rule.getMatchRule(), targetPath))
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
                                                InjectionRule.Mode mode) {
        if (targetPath.isEmpty()) {
            return Mono.just(false);
        }

        return getRulesByMode(mode)
                .filter(rule -> rule.isEnabled() && rule.isValid())
                .any(rule -> !MatchRule.supportsDomPathPrecheck(rule.getMatchRule())
                        || pathPrecheckMatches(rule.getMatchRule(), targetPath))
                .defaultIfEmpty(false);
    }

    /**
     * why: 代码块在规则维度按关联顺序拼接，保持用户配置的组合结果稳定；
     * REMOVE 规则会在更上游被清空 snippetIds，这里无需再分叉特殊逻辑。
     */
    public Mono<String> getConcatCode(InjectionRule rule) {
        return Flux.fromIterable(rule.getSnippetIds())
                .flatMap(snippetManager::get)
                .filter(CodeSnippet::isValid)
                .filter(CodeSnippet::isEnabled)
                .map(CodeSnippet::getCode)
                .reduce("", String::concat);
    }

    private MatchState evaluate(MatchRule rule, MatchContext context) {
        if (rule == null || rule.getType() == null) {
            return MatchState.NO_MATCH;
        }
        MatchState state = switch (rule.getType()) {
            case GROUP -> evaluateGroup(rule, context);
            case PATH -> evaluatePath(rule, context.path());
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
        MatchRule.Operator operator = rule.getOperator() == null ? MatchRule.Operator.AND : rule.getOperator();
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

    private MatchState evaluatePath(MatchRule rule, String currentPath) {
        if (currentPath == null || currentPath.isBlank()) {
            return MatchState.NO_MATCH;
        }
        String value = rule.getValue() == null ? "" : rule.getValue().trim();
        if (value.isBlank()) {
            return MatchState.NO_MATCH;
        }
        MatchRule.Matcher matcher = rule.getMatcher() == null ? MatchRule.Matcher.ANT : rule.getMatcher();
        boolean matched = switch (matcher) {
            case EXACT -> value.equals(currentPath);
            case REGEX -> matchesRegex(value, currentPath);
            case ANT -> matchesAntPath(value, currentPath);
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
        MatchRule.Matcher matcher = rule.getMatcher() == null ? MatchRule.Matcher.EXACT : rule.getMatcher();
        boolean matched = switch (matcher) {
            case EXACT -> value.equals(templateId.trim());
            case REGEX -> matchesRegex(value, templateId.trim());
            case ANT -> false;
        };
        return matched ? MatchState.MATCH : MatchState.NO_MATCH;
    }

    private boolean matchesAntPath(String pattern, String currentPath) {
        try {
            RouteMatcher.Route requestRoute = routeMatcher.parseRoute(currentPath);
            return routeMatcher.match(pattern, requestRoute);
        } catch (PatternParseException e) {
            log.warn("Parse route pattern [{}] failed for path [{}]", pattern, currentPath, e);
            return false;
        }
    }

    private boolean matchesRegex(String pattern, String value) {
        RegexPatternHolder holder = regexPatternCache.computeIfAbsent(pattern, this::compileRegexHolder);
        if (holder.pattern() == null) {
            log.warn("Parse regex [{}] failed for value [{}]: {}", pattern, value, holder.errorMessage());
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
    private boolean pathPrecheckMatches(MatchRule rule, String currentPath) {
        if (rule == null || rule.getType() == null) {
            return false;
        }
        boolean matched = switch (rule.getType()) {
            case GROUP -> pathPrecheckGroupMatches(rule, currentPath);
            case PATH -> evaluatePath(rule, currentPath) == MatchState.MATCH;
            case TEMPLATE_ID -> true;
        };
        return Boolean.TRUE.equals(rule.getNegate()) ^ matched;
    }

    private boolean pathPrecheckGroupMatches(MatchRule rule, String currentPath) {
        if (rule.getChildren() == null || rule.getChildren().isEmpty()) {
            return false;
        }
        MatchRule.Operator operator = rule.getOperator() == null ? MatchRule.Operator.AND : rule.getOperator();
        return switch (operator) {
            case AND -> rule.getChildren().stream().allMatch(child -> pathPrecheckMatches(child, currentPath));
            case OR -> rule.getChildren().stream().anyMatch(child -> pathPrecheckMatches(child, currentPath));
        };
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

    private record MatchContext(String path, String templateId) {
    }

    private record RegexPatternHolder(Pattern pattern, String errorMessage) {
    }

    private enum MatchState {
        MATCH, NO_MATCH, UNKNOWN
    }
}
