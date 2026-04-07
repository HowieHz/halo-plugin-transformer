package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        return fetchAndValidateSnippetTargets(normalized).thenReturn(normalized);
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
        return fetchAndValidateSnippetTargets(addedSnippetIds).thenReturn(normalizedNext);
    }

    /**
     * why: 这里拿到的是一组精确的 snippet 资源名，最直白的校验方式就是按 name 点查；
     * 相比全表 `list + filter`，它更贴近 Halo 的资源模型，也避免一次小范围关联校验触发整表扫描。
     */
    private Mono<Void> fetchAndValidateSnippetTargets(Set<String> snippetIds) {
        return Flux.fromIterable(snippetIds)
                .concatMap(this::fetchSnippetTarget)
                .collectList()
                .flatMap(this::validateFetchedSnippetTargets);
    }

    private Mono<SnippetTargetLookup> fetchSnippetTarget(String snippetId) {
        return client.fetch(CodeSnippet.class, snippetId)
                .map(snippet -> SnippetTargetLookup.found(snippetId, snippet))
                .switchIfEmpty(Mono.just(SnippetTargetLookup.missing(snippetId)));
    }

    private Mono<Void> validateFetchedSnippetTargets(List<SnippetTargetLookup> lookups) {
        LinkedHashSet<String> missingIds = new LinkedHashSet<>();
        for (SnippetTargetLookup lookup : lookups) {
            if (lookup.isMissing()) {
                missingIds.add(lookup.snippetId());
                continue;
            }
            CodeSnippet snippet = lookup.snippet();
            if (snippet != null && (ExtensionUtil.isDeleted(snippet) || !snippet.isValid())) {
                return Mono.error(new InjectionRuleValidationException(
                        "snippetIds：代码块 " + snippet.getId() + " 当前无法关联"));
            }
        }
        if (!missingIds.isEmpty()) {
            return Mono.error(new InjectionRuleValidationException(
                    "snippetIds：包含不存在的代码块：" + String.join("、", missingIds)));
        }
        return Mono.empty();
    }

    private LinkedHashSet<String> normalizeIds(Set<String> ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids == null) {
            return normalized;
        }
        ids.stream().filter(id -> id != null && !id.isBlank()).forEach(normalized::add);
        return normalized;
    }

    private record SnippetTargetLookup(String snippetId, CodeSnippet snippet) {
        private static SnippetTargetLookup missing(String snippetId) {
            return new SnippetTargetLookup(snippetId, null);
        }

        private static SnippetTargetLookup found(String snippetId, CodeSnippet snippet) {
            return new SnippetTargetLookup(snippetId, snippet);
        }

        private boolean isMissing() {
            return snippet == null;
        }
    }
}
