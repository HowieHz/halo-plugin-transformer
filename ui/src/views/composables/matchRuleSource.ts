import type {
  InjectionRuleEditorDraft,
  InjectionRuleEditorState,
  MatchRuleSource,
  MatchRule,
  MatchRuleEditorMode,
} from '@/types'
import {
  formatMatchRule,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleTree,
  type MatchRuleParseResult,
} from './matchRuleValidation'

/**
 * why: 编辑器需要“可随意修改的草稿副本”，避免直接改动响应式源对象时把未保存状态提前污染到列表数据。
 */
export function cloneMatchRule(rule: MatchRule): MatchRule {
  return JSON.parse(JSON.stringify(rule)) as MatchRule
}

/**
 * why: 匹配规则来源状态既会参与撤销/重置，也会参与导入导出；
 * 这里统一深拷贝，避免字符串草稿与规则树在多个编辑副本之间相互串改。
 */
export function cloneMatchRuleSource(source: MatchRuleSource): MatchRuleSource {
  return source.kind === 'JSON_DRAFT'
    ? { kind: 'JSON_DRAFT', data: String(source.data ?? '') }
    : { kind: 'RULE_TREE', data: cloneMatchRule(normalizeMatchRule(source.data)) }
}

/**
 * why: 简单模式以规则树为主数据源；切回简单模式时要把来源状态也收敛成规则树。
 */
export function makeRuleTreeSource(rule: MatchRule): MatchRuleSource {
  return {
    kind: 'RULE_TREE',
    data: cloneMatchRule(normalizeMatchRule(rule)),
  }
}

/**
 * why: 高级模式以 JSON 草稿为主数据源；即使当前草稿有错，也必须原样保留下来供继续修正。
 */
export function makeJsonDraftSource(draft: string): MatchRuleSource {
  return {
    kind: 'JSON_DRAFT',
    data: draft,
  }
}

/**
 * why: 模式切换的核心语义应当只由“目标模式”决定：
 * 切到简单模式一定收敛成规则树，切到高级模式一定生成新的 JSON 草稿，
 * 避免再用额外布尔值把“切换模式和是否覆盖草稿”两层语义搅在一起。
 */
export function buildMatchRuleEditorSourceForMode(
  mode: MatchRuleEditorMode,
  rule: MatchRule,
): { matchRule: MatchRule; matchRuleSource: MatchRuleSource; jsonDraft: string } {
  const normalized = normalizeMatchRule(rule)
  const jsonDraft = formatMatchRule(normalized)

  return {
    matchRule: normalized,
    matchRuleSource:
      mode === 'JSON' ? makeJsonDraftSource(jsonDraft) : makeRuleTreeSource(normalized),
    jsonDraft,
  }
}

export function resolveRuleMatchRule(
  rule: Pick<InjectionRuleEditorDraft, 'matchRule'> & Partial<InjectionRuleEditorState>,
): MatchRuleParseResult {
  if (rule.matchRuleSource?.kind === 'JSON_DRAFT') {
    return parseMatchRuleDraft(String(rule.matchRuleSource.data ?? ''))
  }
  if (rule.matchRuleSource?.kind === 'RULE_TREE') {
    return validateMatchRuleTree(normalizeMatchRule(rule.matchRuleSource.data))
  }
  const normalized = normalizeMatchRule(rule.matchRule)
  return validateMatchRuleTree(normalized)
}
