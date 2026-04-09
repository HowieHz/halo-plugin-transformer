import type { TransformationRuleEditorDraft } from "@/types";

import { formatMatchRuleError, isValidMatchRule, resolveRuleMatchRule } from "./matchRule";

/**
 * why: 规则前端校验必须只有一份 authoritative source；
 * 否则 create / edit / import 各自复制一套条件后，错误文案和边界会持续漂移。
 */
export function validateRuleDraft(rule: TransformationRuleEditorDraft): string | null {
  if (rule.mode === "SELECTOR" && !rule.match.trim()) {
    return "请填写匹配内容";
  }
  const result = resolveRuleMatchRule(rule);
  if (result.error) {
    return `匹配规则有误：${formatMatchRuleError(result.error)}`;
  }
  if (!isValidMatchRule(result.rule)) {
    return "请完善匹配规则";
  }
  return null;
}
