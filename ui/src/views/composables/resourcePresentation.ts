import { MODE_OPTIONS, POSITION_OPTIONS, type TransformationRuleReadModel } from "@/types";

export function modeLabel(mode: string) {
  return MODE_OPTIONS.find((option) => option.value === mode)?.label ?? mode;
}

export function positionLabel(position?: string) {
  if (!position) {
    return "";
  }
  return POSITION_OPTIONS.find((option) => option.value === position)?.label ?? position;
}

export function rulePreview(rule: TransformationRuleReadModel) {
  return `${modeLabel(rule.mode)} · ${positionLabel(rule.position)}`;
}

export function codePreview(code: string) {
  const singleLineCode = code.replace(/\s+/g, " ").trim();
  return singleLineCode.length > 55 ? `${singleLineCode.slice(0, 55)}...` : singleLineCode;
}
