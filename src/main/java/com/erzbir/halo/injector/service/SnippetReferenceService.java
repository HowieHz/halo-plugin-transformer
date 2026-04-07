package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SnippetReferenceService {
    private final ReactiveExtensionClient client;

    public SnippetReferenceService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * why: 既然 `InjectionRule.snippetIds` 已经成为唯一真源，规则保存前就要在这里统一做
     * “去重/去空 + 目标存在性 + 目标可用性” 校验，避免把坏引用写进规则。
     */
    public Mono<LinkedHashSet<String>> normalizeAndValidateSnippetIds(Set<String> snippetIds) {
        LinkedHashSet<String> normalized = normalizeIds(snippetIds);
        if (normalized.isEmpty()) {
            return Mono.just(normalized);
        }
        return client.list(CodeSnippet.class, null, null)
                .collectList()
                .map(allSnippets -> {
                    validateSnippetTargets(normalized, allSnippets);
                    return normalized;
                });
    }

    /**
     * why: 更新规则时不能因为历史坏关联就把“改名称/描述/启用状态”这种无关写入也一并拦住；
     * 因此这里只校验“新增关联”，已存在的旧关联允许先保留，并由显式清理路径来修复。
     */
    public Mono<LinkedHashSet<String>> normalizeAndValidateAddedSnippetIds(Set<String> existingSnippetIds,
                                                                           Set<String> nextSnippetIds) {
        LinkedHashSet<String> normalizedExisting = normalizeIds(existingSnippetIds);
        LinkedHashSet<String> normalizedNext = normalizeIds(nextSnippetIds);
        LinkedHashSet<String> addedSnippetIds = new LinkedHashSet<>(normalizedNext);
        addedSnippetIds.removeAll(normalizedExisting);
        if (addedSnippetIds.isEmpty()) {
            return Mono.just(normalizedNext);
        }
        return client.list(CodeSnippet.class, null, null)
                .collectList()
                .map(allSnippets -> {
                    validateSnippetTargets(addedSnippetIds, allSnippets);
                    return normalizedNext;
                });
    }

    private void validateSnippetTargets(Set<String> nextSnippetIds, List<CodeSnippet> allSnippets) {
        Set<String> existingIds = allSnippets.stream()
                .map(CodeSnippet::getId)
                .collect(Collectors.toSet());
        LinkedHashSet<String> missingIds = new LinkedHashSet<>(nextSnippetIds);
        missingIds.removeAll(existingIds);
        if (!missingIds.isEmpty()) {
            throw new InjectionRuleValidationException(
                    "snippetIds：包含不存在的代码块：" + String.join("、", missingIds));
        }
        allSnippets.stream()
                .filter(snippet -> nextSnippetIds.contains(snippet.getId()))
                .filter(snippet -> ExtensionUtil.isDeleted(snippet) || !snippet.isValid())
                .findFirst()
                .ifPresent(snippet -> {
                    throw new InjectionRuleValidationException(
                            "snippetIds：代码块 " + snippet.getId() + " 当前无法关联");
                });
    }

    private LinkedHashSet<String> normalizeIds(Set<String> ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids == null) {
            return normalized;
        }
        ids.stream().filter(id -> id != null && !id.isBlank()).forEach(normalized::add);
        return normalized;
    }

}
