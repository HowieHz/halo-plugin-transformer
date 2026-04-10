import { computed, ref } from "vue";

import type { TransformationRuleEditorDraft } from "@/types";
import { makeRuleEditorDraft } from "@/types";

import { cloneMatchRule, cloneMatchRuleSource } from "./matchRule";
import { validateRuleDraft } from "./ruleValidation";
import { parseRuleTransfer } from "./transfer";

interface RuleCreateDraftSnapshot {
  enabled: boolean;
  name: string;
  description: string;
  mode: TransformationRuleEditorDraft["mode"];
  match: string;
  matchRule: TransformationRuleEditorDraft["matchRule"];
  position: TransformationRuleEditorDraft["position"];
  wrapMarker: boolean;
  runtimeOrder: number;
  matchRuleSource: TransformationRuleEditorDraft["matchRuleSource"];
  snippetIds: string[];
}

/**
 * why: 规则新建弹窗不应自己维护第二套 dirty/payload 语义；
 * 统一用 composable 管理 create draft，才能让 create/import/save 长期共享一份领域约束。
 */
export function useRuleCreateDraft() {
  const draft = ref<TransformationRuleEditorDraft>(makeRuleEditorDraft());
  const baseline = buildComparableSnapshot(makeRuleEditorDraft());

  const validationError = computed(() => validateRuleDraft(draft.value));

  function reset() {
    draft.value = makeRuleEditorDraft();
  }

  function hasUnsavedChanges() {
    return !areSnapshotsEqual(buildComparableSnapshot(draft.value), baseline);
  }

  function getSubmitPayload() {
    return {
      rule: cloneRuleDraft(draft.value),
    };
  }

  function importFromTransfer(raw: string) {
    draft.value = parseRuleTransfer(raw);
    return draft.value;
  }

  return {
    draft,
    validationError,
    reset,
    hasUnsavedChanges,
    getSubmitPayload,
    importFromTransfer,
  };
}

function cloneRuleDraft(rule: TransformationRuleEditorDraft): TransformationRuleEditorDraft {
  return {
    ...rule,
    metadata: { ...rule.metadata },
    snippetIds: [...rule.snippetIds],
    matchRule: cloneMatchRule(rule.matchRule),
    matchRuleSource: rule.matchRuleSource ? cloneMatchRuleSource(rule.matchRuleSource) : undefined,
  };
}

function buildComparableSnapshot(rule: TransformationRuleEditorDraft): RuleCreateDraftSnapshot {
  return {
    enabled: rule.enabled,
    name: rule.name,
    description: rule.description,
    mode: rule.mode,
    match: rule.match,
    matchRule: cloneMatchRule(rule.matchRule),
    position: rule.position,
    wrapMarker: rule.wrapMarker,
    runtimeOrder: rule.runtimeOrder,
    matchRuleSource: rule.matchRuleSource ? cloneMatchRuleSource(rule.matchRuleSource) : undefined,
    snippetIds: [...rule.snippetIds],
  };
}

function areSnapshotsEqual(left: RuleCreateDraftSnapshot, right: RuleCreateDraftSnapshot) {
  return JSON.stringify(left) === JSON.stringify(right);
}
