package top.howiehz.halo.transformer.endpoint;

import java.util.Set;
import top.howiehz.halo.transformer.core.ITransformationRule;
import top.howiehz.halo.transformer.core.MatchRule;

public record TransformationRuleReadModel(
    String apiVersion,
    String kind,
    ConsoleResourceMetadata metadata,
    String id,
    String name,
    String description,
    boolean enabled,
    ITransformationRule.Mode mode,
    String match,
    MatchRule matchRule,
    ITransformationRule.Position position,
    boolean wrapMarker,
    int runtimeOrder,
    Set<String> snippetIds
) {
}
