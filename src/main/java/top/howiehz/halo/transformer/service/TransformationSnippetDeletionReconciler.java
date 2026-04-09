package top.howiehz.halo.transformer.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.index.query.Queries;
import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationRule;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.util.TransformationSnippetReferenceIds;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransformationSnippetDeletionReconciler implements Reconciler<Reconciler.Request> {
    private static final int MAX_CONFLICT_RETRIES = 3;

    private final ExtensionClient client;
    private final TransformationRuleRuntimeStore ruleRuntimeStore;

    /**
     * why: 一旦代码片段进入 Halo “删除中”状态，就要由单一后端协调器负责摘掉所有规则引用，
     * 再移除 finalizer 交还给平台完成真正删除，避免前端或多个 service 各自拼删除流程。
     */
    @Override
    public Result reconcile(Request request) {
        var snippetOptional = client.fetch(TransformationSnippet.class, request.name());
        if (snippetOptional.isEmpty()) {
            return Result.doNotRetry();
        }
        TransformationSnippet snippet = snippetOptional.get();
        if (!TransformationSnippetLifecycleService.isDeletionPendingCleanup(snippet)) {
            return Result.doNotRetry();
        }

        boolean detachedAnyRule =
            detachSnippetFromReferencingRules(snippet.getMetadata().getName());
        if (detachedAnyRule) {
            ruleRuntimeStore.invalidateAndWarmUpAsync();
        }

        removeSnippetDeletionFinalizerWithRetry(request.name());
        log.info("已完成处于“删除中”状态的代码片段 [{}] 的 finalizer 清理", request.name());
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new TransformationSnippet())
            .syncAllListOptions(ListOptions.builder()
                .fieldQuery(Queries.not(Queries.isNull("metadata.deletionTimestamp")))
                .build())
            .onAddMatcher(ExtensionUtil::isDeleted)
            .onUpdateMatcher(ExtensionUtil::isDeleted)
            .build();
    }

    private boolean detachSnippetFromReferencingRules(String snippetId) {
        String normalizedSnippetId = TransformationSnippetReferenceIds.normalizeSingle(snippetId);
        if (normalizedSnippetId == null) {
            return false;
        }
        boolean detachedAnyRule = false;
        List<String> referencingRuleNames = client.list(
                TransformationRule.class,
                rule -> isVisibleRule(rule)
                    && TransformationSnippetReferenceIds.normalize(rule.getSnippetIds())
                    .contains(normalizedSnippetId),
                null
            ).stream()
            .map(rule -> rule.getMetadata() == null ? null : rule.getMetadata().getName())
            .filter(name -> name != null && !name.isBlank())
            .toList();
        for (String ruleName : referencingRuleNames) {
            if (detachSnippetReferenceWithRetry(ruleName, normalizedSnippetId)) {
                detachedAnyRule = true;
            }
        }
        return detachedAnyRule;
    }

    /**
     * why: reconciler 同样是写路径，不能把正确性暗押给底层“也许会做版本保护”；
     * 这里显式建模 `conflict -> refetch -> retry`，把最终一致过程写清楚。
     */
    private boolean detachSnippetReferenceWithRetry(String ruleName, String snippetId) {
        return retryOnConflictOrVisibilityLoss(
            "从规则 [" + ruleName + "] 中摘除处于“删除中”状态的代码片段 [" + snippetId + "]",
            () -> {
                var latestRuleOptional = fetchVisibleRule(ruleName);
                if (latestRuleOptional.isEmpty()) {
                    return false;
                }
                TransformationRule latestRule = latestRuleOptional.get();
                LinkedHashSet<String> nextSnippetIds =
                    TransformationSnippetReferenceIds.normalize(latestRule.getSnippetIds());
                if (!nextSnippetIds.remove(snippetId)) {
                    return false;
                }
                latestRule.setSnippetIds(nextSnippetIds);
                client.update(latestRule);
                return true;
            }
        );
    }

    private Optional<TransformationRule> fetchVisibleRule(String ruleName) {
        return client.fetch(TransformationRule.class, ruleName)
            .filter(this::isVisibleRule);
    }

    private boolean isVisibleRule(TransformationRule rule) {
        return rule != null && !ExtensionUtil.isDeleted(rule);
    }

    private void removeSnippetDeletionFinalizerWithRetry(String snippetName) {
        retryOnConflict(
            "remove deletion finalizer from snippet [" + snippetName + "]",
            () -> {
                var latestSnippetOptional = client.fetch(TransformationSnippet.class, snippetName);
                if (latestSnippetOptional.isEmpty()) {
                    return false;
                }
                TransformationSnippet latestSnippet = latestSnippetOptional.get();
                if (!TransformationSnippetLifecycleService.isDeletionPendingCleanup(
                    latestSnippet)) {
                    return false;
                }
                TransformationSnippetLifecycleService.removeDeletionFinalizer(latestSnippet);
                client.update(latestSnippet);
                return true;
            }
        );
    }

    private boolean retryOnConflict(String operationLabel, ConflictRetriableOperation operation) {
        ResponseStatusException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_CONFLICT_RETRIES; attempt++) {
            try {
                return operation.run();
            } catch (ResponseStatusException exception) {
                if (!HttpStatus.CONFLICT.equals(exception.getStatusCode())) {
                    throw exception;
                }
                lastConflict = exception;
                log.info(
                    "Conflict while attempting to {}. Retrying with latest resource state ({}/{}).",
                    operationLabel, attempt, MAX_CONFLICT_RETRIES);
            }
        }
        throw lastConflict == null
            ? new IllegalStateException(
            "Conflict retry exhausted without captured conflict for " + operationLabel)
            : lastConflict;
    }

    private boolean retryOnConflictOrVisibilityLoss(String operationLabel,
        ConflictRetriableOperation operation) {
        try {
            return retryOnConflict(operationLabel, operation);
        } catch (ResponseStatusException exception) {
            if (isVisibilityLoss(exception)) {
                log.info("Skipping {} because the target resource is no longer visible: {}",
                    operationLabel, exception.getReason());
                return false;
            }
            throw exception;
        }
    }

    private boolean isVisibilityLoss(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return HttpStatus.NOT_FOUND.equals(status) || HttpStatus.GONE.equals(status);
    }

    @FunctionalInterface
    private interface ConflictRetriableOperation {
        boolean run();
    }
}
