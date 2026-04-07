package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.manager.InjectionRuleManager;
import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.index.query.Queries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeSnippetDeletionReconciler implements Reconciler<Reconciler.Request> {
    private final ExtensionClient client;
    private final InjectionRuleManager ruleManager;

    /**
     * why: 一旦代码块进入 Halo deleting 状态，就要由单一后端协调器负责摘掉所有规则引用，
     * 再移除 finalizer 交还给平台完成真正删除，避免前端或多个 service 各自拼删除流程。
     */
    @Override
    public Result reconcile(Request request) {
        var snippetOptional = client.fetch(CodeSnippet.class, request.name());
        if (snippetOptional.isEmpty()) {
            return Result.doNotRetry();
        }
        CodeSnippet snippet = snippetOptional.get();
        if (!CodeSnippetDeletionService.isDeletionManaged(snippet)) {
            return Result.doNotRetry();
        }

        boolean detachedAnyRule = detachSnippetFromReferencingRules(snippet.getMetadata().getName());
        if (detachedAnyRule) {
            ruleManager.invalidateAndWarmUpAsync();
        }

        var latestSnippetOptional = client.fetch(CodeSnippet.class, request.name());
        if (latestSnippetOptional.isEmpty()) {
            return Result.doNotRetry();
        }
        CodeSnippet latestSnippet = latestSnippetOptional.get();
        if (!CodeSnippetDeletionService.isDeletionManaged(latestSnippet)) {
            return Result.doNotRetry();
        }

        CodeSnippetDeletionService.removeDeletionFinalizer(latestSnippet);
        client.update(latestSnippet);
        log.info("Completed finalizer cleanup for deleting snippet [{}]", request.name());
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new CodeSnippet())
                .syncAllListOptions(ListOptions.builder()
                        .fieldQuery(Queries.not(Queries.isNull("metadata.deletionTimestamp")))
                        .build())
                .onAddMatcher(ExtensionUtil::isDeleted)
                .onUpdateMatcher(ExtensionUtil::isDeleted)
                .build();
    }

    private boolean detachSnippetFromReferencingRules(String snippetId) {
        boolean detachedAnyRule = false;
        List<InjectionRule> referencingRules = client.list(
                InjectionRule.class,
                rule -> normalizeSnippetIds(rule.getSnippetIds()).contains(snippetId),
                null
        );
        for (InjectionRule rule : referencingRules) {
            LinkedHashSet<String> nextSnippetIds = normalizeSnippetIds(rule.getSnippetIds());
            if (!nextSnippetIds.remove(snippetId)) {
                continue;
            }
            rule.setSnippetIds(nextSnippetIds);
            client.update(rule);
            detachedAnyRule = true;
        }
        return detachedAnyRule;
    }

    private LinkedHashSet<String> normalizeSnippetIds(Set<String> snippetIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (snippetIds == null) {
            return normalized;
        }
        snippetIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .forEach(normalized::add);
        return normalized;
    }
}
