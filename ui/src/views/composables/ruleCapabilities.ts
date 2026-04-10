import type { TransformationRuleEditorDraft, TransformationRuleWritePayload } from "@/types";

type RuleSemanticFields = Pick<TransformationRuleEditorDraft, "mode" | "position">;
type RuleAssociationFields = Pick<
  TransformationRuleEditorDraft,
  "mode" | "position" | "snippetIds"
>;

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

/**
 * why: 空关联是允许保存的草稿态，但用户仍应被明确告知“当前不会输出任何内容”；
 * 这样既不误伤先建规则的工作流，也不会让 no-op 配置看起来像已经完整生效。
 */
export function getEmptySnippetAssociationWarning(rule: RuleAssociationFields): string | null {
  const capabilities = getRuleCapabilities(rule);
  if (!capabilities.showsSnippetPicker || rule.snippetIds.length > 0) {
    return null;
  }
  return "当前规则暂未关联代码片段；保存后不会输出内容。你可以先保存，稍后再回来补关联。";
}
