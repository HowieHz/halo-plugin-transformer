import { computed, type Readonly, type Ref } from "vue";

import type { TransformationRuleEditorDraft } from "@/types";

import { getDomRulePerformanceWarning } from "./matchRule";
import { getEmptySnippetAssociationWarning, getRuleCapabilities } from "./ruleCapabilities";

interface UseRuleFormSemanticsOptions {
  rule: Readonly<Ref<TransformationRuleEditorDraft | null>>;
  matchValue?: Readonly<Ref<string>>;
}

/**
 * why: create / edit 两条规则表单路径需要共享同一份前端展示语义；
 * 这样 `REMOVE`、空关联 warning、显示哪些字段这些判断就不会在两个组件里各包一层胶水再慢慢漂移。
 */
export function useRuleFormSemantics(options: UseRuleFormSemanticsOptions) {
  const ruleCapabilities = computed(() =>
    options.rule.value ? getRuleCapabilities(options.rule.value) : null,
  );
  const needsTarget = computed(() => ruleCapabilities.value?.showsTargetField ?? false);
  const needsSnippets = computed(() => ruleCapabilities.value?.showsSnippetPicker ?? false);
  const needsWrapMarker = computed(() => ruleCapabilities.value?.allowsWrapMarker ?? false);
  const matchFieldError = computed(() => {
    if (!needsTarget.value) {
      return null;
    }

    const matchValue = options.matchValue?.value ?? options.rule.value?.match ?? "";
    return matchValue.trim() ? null : "请填写匹配内容";
  });
  const performanceWarning = computed(() =>
    options.rule.value ? getDomRulePerformanceWarning(options.rule.value) : null,
  );
  const emptySnippetAssociationWarning = computed(() =>
    options.rule.value ? getEmptySnippetAssociationWarning(options.rule.value) : null,
  );

  function buildToggledSnippetIds(snippetId: string) {
    const currentSnippetIds = options.rule.value?.snippetIds ?? [];
    return currentSnippetIds.includes(snippetId)
      ? currentSnippetIds.filter((currentId) => currentId !== snippetId)
      : [...currentSnippetIds, snippetId];
  }

  return {
    buildToggledSnippetIds,
    emptySnippetAssociationWarning,
    matchFieldError,
    needsSnippets,
    needsTarget,
    needsWrapMarker,
    performanceWarning,
    ruleCapabilities,
  };
}
