import type { TransformationRuleEditorDraft, TransformationRuleWritePayload } from "@/types";

type RuleSemanticFields = Pick<TransformationRuleEditorDraft, "mode" | "position">;

export interface RuleCapabilities {
  isSelectorMode: boolean;
  isSelectorRemove: boolean;
  showsTargetField: boolean;
  showsPositionField: boolean;
  showsSnippetPicker: boolean;
  allowsWrapMarker: boolean;
}

/**
 * why: `mode` 是规则语义的第一真源；`position` 只在 `SELECTOR` 模式下参与行为判断。
 * 先把这层能力模型收口，create / edit / validate / payload 才不会各自猜一套条件。
 */
export function getRuleCapabilities(rule: RuleSemanticFields): RuleCapabilities {
  const isSelectorMode = rule.mode === "SELECTOR";
  const isSelectorRemove = isSelectorMode && rule.position === "REMOVE";

  return {
    isSelectorMode,
    isSelectorRemove,
    showsTargetField: isSelectorMode,
    showsPositionField: isSelectorMode,
    showsSnippetPicker: !isSelectorRemove,
    allowsWrapMarker: !isSelectorRemove,
  };
}

/**
 * why: 写入层需要消费一份已经按规则能力收紧过的 payload 语义，
 * 避免旧的 `position=REMOVE` 在切到 `HEAD/FOOTER` 后继续偷偷污染持久化结果。
 */
export function normalizeRuleWriteFields(
  rule: TransformationRuleEditorDraft,
): Pick<TransformationRuleWritePayload, "match" | "position" | "snippetIds" | "wrapMarker"> {
  const capabilities = getRuleCapabilities(rule);

  return {
    match: capabilities.isSelectorMode ? rule.match.trim() : "",
    position: capabilities.showsPositionField ? rule.position : "APPEND",
    snippetIds: capabilities.showsSnippetPicker ? [...rule.snippetIds] : [],
    wrapMarker: capabilities.allowsWrapMarker ? rule.wrapMarker : false,
  };
}
