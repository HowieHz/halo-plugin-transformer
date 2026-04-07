package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.CodeSnippetValidationException;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
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

    /**
     * why: 删除代码块时，规则侧的 `snippetIds` 仍是唯一真源；
     * 因此要先从所有引用它的规则里摘掉，再删除代码块本身，避免留下悬挂引用。
     */
    public Mono<Void> deleteSnippetAndDetachRules(CodeSnippet snippet) {
        String snippetId = requireSnippetId(snippet);
        return loadRulesReferencingSnippet(snippetId)
                .flatMap(ruleSnapshots -> detachSnippetFromRules(snippetId, ruleSnapshots)
                        .then(client.delete(snippet))
                        .then()
                        .onErrorResume(error -> rollbackRules(ruleSnapshots)
                                .then(Mono.error(error))));
    }

    /**
     * why: 删除补偿真正需要的只是“哪条规则曾引用它”和“原始 snippetIds 是什么”；
     * 显式快照最小字段比深拷贝整条规则更直白，也不会绕开应用统一序列化配置。
     */
    private Mono<List<RuleSnippetReferenceSnapshot>> loadRulesReferencingSnippet(String snippetId) {
        return client.list(InjectionRule.class, null, null)
                .filter(rule -> normalizeIds(rule.getSnippetIds()).contains(snippetId))
                .map(this::snapshotRuleSnippetReferences)
                .collectList();
    }

    private Mono<Void> detachSnippetFromRules(String snippetId,
                                              List<RuleSnippetReferenceSnapshot> ruleSnapshots) {
        return Flux.fromIterable(ruleSnapshots)
                .concatMap(ruleSnapshot -> fetchRule(ruleSnapshot.ruleName())
                        .flatMap(rule -> {
                    LinkedHashSet<String> snippetIds = normalizeIds(rule.getSnippetIds());
                    if (!snippetIds.remove(snippetId)) {
                        return Mono.empty();
                    }
                    rule.setSnippetIds(snippetIds);
                    return client.update(rule).then();
                }))
                .then();
    }

    /**
     * why: 代码块删除属于多写场景；若删前摘引用或最终删除中途失败，
     * 要按删除前快照回滚规则，避免把“删除失败”做成“引用已部分丢失”的半成功状态。
     */
    private Mono<Void> rollbackRules(List<RuleSnippetReferenceSnapshot> ruleSnapshots) {
        return Flux.fromIterable(ruleSnapshots)
                .concatMap(ruleSnapshot -> fetchRule(ruleSnapshot.ruleName())
                        .flatMap(rule -> {
                            rule.setSnippetIds(new LinkedHashSet<>(ruleSnapshot.snippetIds()));
                            return client.update(rule);
                        })
                        .onErrorResume(error -> {
                            log.warn("Failed to rollback rule [{}]", ruleSnapshot.ruleName(), error);
                            return Mono.empty();
                        }))
                .then();
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
                .filter(snippet -> !snippet.isValid())
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

    private RuleSnippetReferenceSnapshot snapshotRuleSnippetReferences(InjectionRule rule) {
        return new RuleSnippetReferenceSnapshot(
                requireRuleName(rule),
                normalizeIds(rule.getSnippetIds())
        );
    }

    private Mono<InjectionRule> fetchRule(String ruleName) {
        return client.fetch(InjectionRule.class, ruleName)
                .switchIfEmpty(Mono.error(new IllegalStateException("未找到要更新的规则：" + ruleName)));
    }

    private String requireRuleName(InjectionRule rule) {
        if (rule == null || rule.getMetadata() == null || rule.getMetadata().getName() == null
                || rule.getMetadata().getName().isBlank()) {
            throw new InjectionRuleValidationException("metadata.name 不能为空");
        }
        return rule.getMetadata().getName();
    }

    private String requireSnippetId(CodeSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null || snippet.getMetadata().getName() == null
                || snippet.getMetadata().getName().isBlank()) {
            throw new CodeSnippetValidationException("metadata.name 不能为空");
        }
        return snippet.getMetadata().getName();
    }

    private record RuleSnippetReferenceSnapshot(String ruleName, LinkedHashSet<String> snippetIds) {
    }
}
