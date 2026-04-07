package com.erzbir.halo.injector.endpoint;

public record CodeSnippetReadModel(
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
