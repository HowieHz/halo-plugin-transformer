package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.core.IInjectionRule;
import com.erzbir.halo.injector.core.MatchRule;

import java.util.Set;

public record InjectionRuleReadModel(
        String apiVersion,
        String kind,
        ConsoleResourceMetadata metadata,
        String id,
        String name,
        String description,
        boolean enabled,
        IInjectionRule.Mode mode,
        String match,
        MatchRule matchRule,
        IInjectionRule.Position position,
        boolean wrapMarker,
        int runtimeOrder,
        Set<String> snippetIds
) {
}
