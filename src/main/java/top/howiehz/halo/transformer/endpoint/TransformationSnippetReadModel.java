package top.howiehz.halo.transformer.endpoint;

public record TransformationSnippetReadModel(
    String apiVersion,
    String kind,
    ConsoleResourceMetadata metadata,
    String id,
    String name,
    String code,
    String description,
    boolean enabled
) {
}
