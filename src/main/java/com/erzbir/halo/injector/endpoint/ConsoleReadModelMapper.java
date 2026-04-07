package com.erzbir.halo.injector.endpoint;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.springframework.stereotype.Component;
import run.halo.app.extension.MetadataOperator;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class ConsoleReadModelMapper {
    static final String API_VERSION = "injector.erzbir.com/v1alpha1";

    /**
     * why: 存储实体只保留持久化语义；控制台需要的 `id` 等派生字段由 projection 统一生成，
     * 避免以后每多一个只读展示字段，都再长回实体层或字段清洗补丁。
     */
    public CodeSnippetReadModel toReadModel(CodeSnippet snippet) {
        return new CodeSnippetReadModel(
                API_VERSION,
                "CodeSnippet",
                toConsoleMetadata(snippet.getMetadata()),
                snippet.getMetadata() == null ? "" : snippet.getMetadata().getName(),
                snippet.getName(),
                snippet.getCode(),
                snippet.getDescription(),
                Boolean.TRUE.equals(snippet.getEnabled())
        );
    }

    public InjectionRuleReadModel toReadModel(InjectionRule rule) {
        return new InjectionRuleReadModel(
                API_VERSION,
                "InjectionRule",
                toConsoleMetadata(rule.getMetadata()),
                rule.getMetadata() == null ? "" : rule.getMetadata().getName(),
                rule.getName(),
                rule.getDescription(),
                Boolean.TRUE.equals(rule.getEnabled()),
                rule.getMode(),
                rule.getMatch(),
                rule.getMatchRule(),
                rule.getPosition(),
                rule.getWrapMarker(),
                rule.getRuntimeOrder(),
                rule.getSnippetIds() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(rule.getSnippetIds())
        );
    }

    public ConsoleItemList<CodeSnippetReadModel> toSnippetList(List<CodeSnippet> snippets) {
        return ConsoleItemList.of(snippets.stream().map(this::toReadModel).toList());
    }

    public ConsoleItemList<InjectionRuleReadModel> toRuleList(List<InjectionRule> rules) {
        return ConsoleItemList.of(rules.stream().map(this::toReadModel).toList());
    }

    private ConsoleResourceMetadata toConsoleMetadata(MetadataOperator metadata) {
        if (metadata == null) {
            return null;
        }
        return new ConsoleResourceMetadata(metadata.getName(), metadata.getVersion());
    }
}
