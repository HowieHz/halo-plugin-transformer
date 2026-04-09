import type { MatchRuleSource, TransformationRuleEditorDraft } from "@/types";

import { cloneMatchRule, cloneMatchRuleSource, makeRuleTreeSource } from "./matchRule";

export type UndoableRuleField =
  | "name"
  | "description"
  | "mode"
  | "match"
  | "position"
  | "wrapMarker"
  | "runtimeOrder"
  | "matchRule"
  | "snippetIds";

/**
 * why: 字段级撤销的 authoritative source 应该是“每个字段自己的语义值”；
 * 不能把 `position` 和 `wrapMarker` 这类相邻字段偷偷打包成复合快照，否则 UI 会把独立修改误判成联动修改。
 */
export function buildRuleUndoBaselineSnapshot(rule: TransformationRuleEditorDraft) {
  return {
    name: rule.name,
    description: rule.description,
    mode: rule.mode,
    match: rule.match,
    position: rule.position,
    wrapMarker: rule.wrapMarker,
    runtimeOrder: rule.runtimeOrder,
    matchRule: {
      matchRule: cloneMatchRule(rule.matchRule),
      matchRuleSource: cloneRuleMatchRuleSource(rule),
    },
    snippetIds: [...rule.snippetIds],
  };
}

export function resolveRuleUndoFieldCurrentValue(
  field: UndoableRuleField,
  rule: TransformationRuleEditorDraft,
) {
  return field === "matchRule"
    ? {
        matchRule: cloneMatchRule(rule.matchRule),
        matchRuleSource: cloneRuleMatchRuleSource(rule),
      }
    : field === "snippetIds"
      ? [...rule.snippetIds]
      : rule[field];
}

function cloneRuleMatchRuleSource(rule: TransformationRuleEditorDraft): MatchRuleSource {
  return cloneMatchRuleSource(rule.matchRuleSource ?? makeRuleTreeSource(rule.matchRule));
}
