package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.CodeSnippetValidationException;
import com.erzbir.halo.injector.util.InjectionRuleValidationException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ResourceRelationWriteService {
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    /**
     * why: 代码块与规则之间是双向持久化关联；创建时必须在服务端一次写链路里同步规则侧，
     * 避免前端再发第二轮补写请求，导致“主资源成功、反向关联失败”的半成功状态。
     */
    public Mono<CodeSnippet> createSnippetWithRelations(CodeSnippet snippet) {
        String snippetId = requireSnippetId(snippet);
        snippet.setRuleIds(normalizeIds(snippet.getRuleIds()));
        return prepareRuleSyncPlan(snippetId, snippet.getRuleIds())
                .flatMap(plan -> client.create(snippet)
                        .flatMap(created -> applyRuleSync(plan)
                                .thenReturn(created)
                                .onErrorResume(error -> rollbackRules(plan.originalRules())
                                        .then(rollbackCreatedSnippet(created))
                                        .then(Mono.error(error)))));
    }

    /**
     * why: 更新代码块时若规则侧同步失败，要把规则和代码块都回滚，
     * 否则 UI 会看到“当前代码块保存失败”，但数据库里其实已经落了一半数据。
     */
    public Mono<CodeSnippet> updateSnippetWithRelations(CodeSnippet existingSnippet,
                                                        CodeSnippet snippet) {
        String snippetId = requireSnippetId(snippet);
        snippet.setRuleIds(normalizeIds(snippet.getRuleIds()));
        CodeSnippet snapshot = copy(existingSnippet, CodeSnippet.class);
        return prepareRuleSyncPlan(snippetId, snippet.getRuleIds())
                .flatMap(plan -> client.update(snippet)
                        .flatMap(updated -> applyRuleSync(plan)
                                .thenReturn(updated)
                                .onErrorResume(error -> rollbackRules(plan.originalRules())
                                        .then(rollbackUpdatedSnippet(snapshot))
                                        .then(Mono.error(error)))));
    }

    /**
     * why: 删除代码块前要先把规则上的残留引用清掉；
     * 这样就算刷新列表或进入关系面板，也不会继续看到已经删除的 snippet id。
     */
    public Mono<Void> deleteSnippetWithRelations(CodeSnippet snippet) {
        String snippetId = requireSnippetId(snippet);
        return prepareRuleSyncPlan(snippetId, Set.of())
                .flatMap(plan -> applyRuleSync(plan)
                        .then(client.delete(snippet))
                        .then()
                        .onErrorResume(error -> rollbackRules(plan.originalRules())
                                .then(Mono.error(error))));
    }

    /**
     * why: 规则创建时也要同步代码块侧的 `ruleIds`；
     * 双向关联由服务端统一完成后，前端只需提交一次规则保存请求即可。
     */
    public Mono<InjectionRule> createRuleWithRelations(InjectionRule rule) {
        String ruleId = requireRuleId(rule);
        rule.setSnippetIds(normalizeIds(rule.getSnippetIds()));
        return prepareSnippetSyncPlan(ruleId, rule.getSnippetIds())
                .flatMap(plan -> client.create(rule)
                        .flatMap(created -> applySnippetSync(plan)
                                .thenReturn(created)
                                .onErrorResume(error -> rollbackSnippets(plan.originalSnippets())
                                        .then(rollbackCreatedRule(created))
                                        .then(Mono.error(error)))));
    }

    /**
     * why: 规则更新和代码块反向关联必须视为一次整体写入；
     * 只要代码块侧补写失败，就要把当前规则一起回滚，保证两边始终一致。
     */
    public Mono<InjectionRule> updateRuleWithRelations(InjectionRule existingRule,
                                                       InjectionRule rule) {
        String ruleId = requireRuleId(rule);
        rule.setSnippetIds(normalizeIds(rule.getSnippetIds()));
        InjectionRule snapshot = copy(existingRule, InjectionRule.class);
        return prepareSnippetSyncPlan(ruleId, rule.getSnippetIds())
                .flatMap(plan -> client.update(rule)
                        .flatMap(updated -> applySnippetSync(plan)
                                .thenReturn(updated)
                                .onErrorResume(error -> rollbackSnippets(plan.originalSnippets())
                                        .then(rollbackUpdatedRule(snapshot))
                                        .then(Mono.error(error)))));
    }

    /**
     * why: 删除规则时要同步清掉代码块上的反向引用；
     * 否则代码块侧列表与关联面板会继续保留一个已经不存在的规则 id。
     */
    public Mono<Void> deleteRuleWithRelations(InjectionRule rule) {
        String ruleId = requireRuleId(rule);
        return prepareSnippetSyncPlan(ruleId, Set.of())
                .flatMap(plan -> applySnippetSync(plan)
                        .then(client.delete(rule))
                        .then()
                        .onErrorResume(error -> rollbackSnippets(plan.originalSnippets())
                                .then(Mono.error(error))));
    }

    Mono<RuleSyncPlan> prepareRuleSyncPlan(String snippetId, Set<String> nextRuleIds) {
        return client.list(InjectionRule.class, null, null)
                .collectList()
                .map(allRules -> {
                    validateRuleTargets(nextRuleIds, allRules);
                    List<InjectionRule> originalRules = allRules.stream()
                            .filter(rule -> nextRuleIds.contains(rule.getId())
                                    || rule.getSnippetIds().contains(snippetId))
                            .map(rule -> copy(rule, InjectionRule.class))
                            .toList();
                    return new RuleSyncPlan(snippetId, nextRuleIds, originalRules);
                });
    }

    Mono<SnippetSyncPlan> prepareSnippetSyncPlan(String ruleId, Set<String> nextSnippetIds) {
        return client.list(CodeSnippet.class, null, null)
                .collectList()
                .map(allSnippets -> {
                    validateSnippetTargets(nextSnippetIds, allSnippets);
                    List<CodeSnippet> originalSnippets = allSnippets.stream()
                            .filter(snippet -> nextSnippetIds.contains(snippet.getId())
                                    || snippet.getRuleIds().contains(ruleId))
                            .map(snippet -> copy(snippet, CodeSnippet.class))
                            .toList();
                    return new SnippetSyncPlan(ruleId, nextSnippetIds, originalSnippets);
                });
    }

    Mono<Void> applyRuleSync(RuleSyncPlan plan) {
        return Flux.fromIterable(plan.originalRules())
                .concatMap(rule -> {
                    boolean shouldHave = plan.nextRuleIds().contains(rule.getId());
                    boolean has = rule.getSnippetIds().contains(plan.snippetId());
                    if (shouldHave == has) {
                        return Mono.empty();
                    }
                    InjectionRule updatedRule = copy(rule, InjectionRule.class);
                    LinkedHashSet<String> snippetIds = normalizeIds(updatedRule.getSnippetIds());
                    if (shouldHave) {
                        snippetIds.add(plan.snippetId());
                    } else {
                        snippetIds.remove(plan.snippetId());
                    }
                    updatedRule.setSnippetIds(snippetIds);
                    return client.update(updatedRule).then();
                })
                .then();
    }

    Mono<Void> applySnippetSync(SnippetSyncPlan plan) {
        return Flux.fromIterable(plan.originalSnippets())
                .concatMap(snippet -> {
                    boolean shouldHave = plan.nextSnippetIds().contains(snippet.getId());
                    boolean has = snippet.getRuleIds().contains(plan.ruleId());
                    if (shouldHave == has) {
                        return Mono.empty();
                    }
                    CodeSnippet updatedSnippet = copy(snippet, CodeSnippet.class);
                    LinkedHashSet<String> ruleIds = normalizeIds(updatedSnippet.getRuleIds());
                    if (shouldHave) {
                        ruleIds.add(plan.ruleId());
                    } else {
                        ruleIds.remove(plan.ruleId());
                    }
                    updatedSnippet.setRuleIds(ruleIds);
                    return client.update(updatedSnippet).then();
                })
                .then();
    }

    Mono<Void> rollbackRules(List<InjectionRule> originalRules) {
        return Flux.fromIterable(originalRules)
                .concatMap(rule -> client.update(copy(rule, InjectionRule.class))
                        .onErrorResume(error -> {
                            log.warn("Failed to rollback rule [{}]", rule.getId(), error);
                            return Mono.empty();
                        }))
                .then();
    }

    Mono<Void> rollbackSnippets(List<CodeSnippet> originalSnippets) {
        return Flux.fromIterable(originalSnippets)
                .concatMap(snippet -> client.update(copy(snippet, CodeSnippet.class))
                        .onErrorResume(error -> {
                            log.warn("Failed to rollback snippet [{}]", snippet.getId(), error);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> rollbackCreatedSnippet(CodeSnippet snippet) {
        return client.delete(snippet)
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to rollback created snippet [{}]", snippet.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> rollbackUpdatedSnippet(CodeSnippet snippet) {
        return client.update(copy(snippet, CodeSnippet.class))
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to rollback updated snippet [{}]", snippet.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> rollbackCreatedRule(InjectionRule rule) {
        return client.delete(rule)
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to rollback created rule [{}]", rule.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> rollbackUpdatedRule(InjectionRule rule) {
        return client.update(copy(rule, InjectionRule.class))
                .then()
                .onErrorResume(error -> {
                    log.warn("Failed to rollback updated rule [{}]", rule.getId(), error);
                    return Mono.empty();
                });
    }

    private void validateRuleTargets(Set<String> nextRuleIds, List<InjectionRule> allRules) {
        Set<String> existingIds = allRules.stream()
                .map(InjectionRule::getId)
                .collect(Collectors.toSet());
        LinkedHashSet<String> missingIds = new LinkedHashSet<>(nextRuleIds);
        missingIds.removeAll(existingIds);
        if (!missingIds.isEmpty()) {
            throw new CodeSnippetValidationException("ruleIds：包含不存在的规则：" + String.join("、", missingIds));
        }
        allRules.stream()
                .filter(rule -> nextRuleIds.contains(rule.getId()))
                .filter(rule -> InjectionRule.Position.REMOVE.equals(rule.getPosition()) || !rule.isValid())
                .findFirst()
                .ifPresent(rule -> {
                    throw new CodeSnippetValidationException(
                            "ruleIds：规则 " + rule.getId() + " 当前无法关联代码块");
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

    private CodeSnippet copy(CodeSnippet snippet, Class<CodeSnippet> type) {
        return copyValue(snippet, type);
    }

    private InjectionRule copy(InjectionRule rule, Class<InjectionRule> type) {
        return copyValue(rule, type);
    }

    private <T> T copyValue(T source, Class<T> type) {
        try {
            return objectMapper.readerFor(type)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(objectMapper.writeValueAsBytes(source));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to copy " + type.getSimpleName(), e);
        }
    }

    private String requireSnippetId(CodeSnippet snippet) {
        if (snippet == null || snippet.getMetadata() == null || snippet.getMetadata().getName() == null
                || snippet.getMetadata().getName().isBlank()) {
            throw new CodeSnippetValidationException("metadata.name 不能为空");
        }
        return snippet.getMetadata().getName();
    }

    private String requireRuleId(InjectionRule rule) {
        if (rule == null || rule.getMetadata() == null || rule.getMetadata().getName() == null
                || rule.getMetadata().getName().isBlank()) {
            throw new InjectionRuleValidationException("metadata.name 不能为空");
        }
        return rule.getMetadata().getName();
    }

    record RuleSyncPlan(String snippetId, Set<String> nextRuleIds, List<InjectionRule> originalRules) {
    }

    record SnippetSyncPlan(String ruleId, Set<String> nextSnippetIds, List<CodeSnippet> originalSnippets) {
    }
}
