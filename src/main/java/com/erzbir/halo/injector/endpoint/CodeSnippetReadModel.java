package com.erzbir.halo.injector.endpoint;

import run.halo.app.extension.Metadata;

public record CodeSnippetReadModel(
        String apiVersion,
        String kind,
        Metadata metadata,
        String id,
        String name,
        String code,
        String description,
        boolean enabled
) {
}
