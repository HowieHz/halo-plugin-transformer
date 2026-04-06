import type {
  InjectionRuleEditorDraft,
  InjectionRuleReadModel,
  InjectionRuleWritePayload,
} from '@/types'
import { normalizeMatchRule, resolveRuleMatchRule, makeRuleTreeSource } from './matchRule'

/**
 * why: 规则读模型进入编辑器前，需要显式补齐规则树与来源状态；
 * 否则简单模式、JSON 模式、撤销与导入导出会围绕不同状态源漂移。
 */
export function hydrateRuleEditorDraft(rule: InjectionRuleReadModel): InjectionRuleEditorDraft {
  const matchRule = normalizeMatchRule(rule.matchRule)
  return {
    ...rule,
    matchRule,
    matchRuleSource: makeRuleTreeSource(matchRule),
  }
}

/**
 * why: 规则写模型必须只包含后端真正接受的持久化字段；
 * 这里统一把编辑态收敛成 `InjectionRuleWritePayload`，避免 UI 草稿细节泄漏到 API。
 */
export function buildRuleWritePayload(
  rule: InjectionRuleEditorDraft,
  snippetIds: string[],
): InjectionRuleWritePayload | null {
  const result = resolveRuleMatchRule(rule)
  if (!result.rule) {
    return null
  }

  const normalizedSnippetIds = rule.position === 'REMOVE' ? [] : snippetIds
  const normalizedWrapMarker = rule.position === 'REMOVE' ? false : rule.wrapMarker

  return {
    apiVersion: rule.apiVersion,
    kind: rule.kind,
    metadata: rule.metadata,
    name: rule.name,
    description: rule.description,
    enabled: rule.enabled,
    mode: rule.mode,
    match: rule.match.trim(),
    matchRule: result.rule,
    position: rule.position,
    wrapMarker: normalizedWrapMarker,
    snippetIds: normalizedSnippetIds,
  }
}
