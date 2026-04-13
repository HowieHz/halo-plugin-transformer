package top.howiehz.halo.transformer.endpoint;

import java.util.Set;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.rule.MatchRule;

public record TransformationRuleReadModel(
    String apiVersion,
    String kind,
    ConsoleResourceMetadata metadata,
    String id,
    String name,
    String description,
    boolean enabled,
    TransformationRule.Mode mode,
    String match,
    MatchRule matchRule,
    TransformationRule.Position position,
    boolean wrapMarker,
    int runtimeOrder,
    Set<String> snippetIds
) {
}
