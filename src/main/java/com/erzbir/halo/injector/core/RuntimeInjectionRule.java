package com.erzbir.halo.injector.core;

import com.erzbir.halo.injector.scheme.InjectionRule;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * why: 运行时缓存只需要消费“已经过校验/裁剪、专门服务执行链路”的最小投影，
 * 不应继续把 Halo extension 存储实体直接带进 filter / processor / helper。
 */
public record RuntimeInjectionRule(
        String resourceName,
        InjectionRule.Mode mode,
        String match,
        MatchRule matchRule,
        InjectionRule.Position position,
        boolean wrapMarker,
        Set<String> snippetIds
) {
    public RuntimeInjectionRule {
        resourceName = resourceName == null ? "" : resourceName;
        match = match == null ? "" : match;
        matchRule = matchRule == null ? MatchRule.defaultRule() : matchRule;
        position = position == null ? InjectionRule.Position.APPEND : position;
        snippetIds = snippetIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(snippetIds));
    }

    public static RuntimeInjectionRule fromStoredRule(InjectionRule rule, MatchRule runtimeMatchRule) {
        return new RuntimeInjectionRule(
                rule == null || rule.getMetadata() == null ? "" : rule.getMetadata().getName(),
                rule == null ? null : rule.getMode(),
                rule == null ? "" : rule.getMatch(),
                runtimeMatchRule,
                rule == null ? null : rule.getPosition(),
                rule != null && rule.getWrapMarker(),
                rule == null ? Set.of() : rule.getSnippetIds()
        );
    }
}
