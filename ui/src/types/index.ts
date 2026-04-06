import type { Metadata } from '@halo-dev/api-client'

/**
 * why: 写接口只应承载后端真正接受的持久化字段；
 * 像 `id` 这类前端展示态派生字段，不应混进写入模型。
 */
export interface CodeSnippetWritePayload {
  apiVersion: 'injector.erzbir.com/v1alpha1'
  kind: 'CodeSnippet'
  metadata: Metadata
  name: string
  code: string
  description: string
  enabled: boolean
  ruleIds: string[]
}

export interface CodeSnippetViewModel extends CodeSnippetWritePayload {
  id: string
}

export type InjectionMode = 'HEAD' | 'FOOTER' | 'ID' | 'SELECTOR'
export type InjectionPosition = 'APPEND' | 'PREPEND' | 'BEFORE' | 'AFTER' | 'REPLACE' | 'REMOVE'
export type MatchRuleType = 'GROUP' | 'PATH' | 'TEMPLATE_ID'
export type MatchRuleOperator = 'AND' | 'OR'
export type MatchRuleMatcher = 'ANT' | 'REGEX' | 'EXACT'
export type MatchRuleEditorMode = 'SIMPLE' | 'JSON'
export type MatchRuleSourceKind = 'RULE_TREE' | 'JSON_DRAFT'

export interface MatchRule {
  type: MatchRuleType
  negate: boolean
  operator?: MatchRuleOperator
  matcher?: MatchRuleMatcher
  value?: string
  children?: MatchRule[]
}

export interface MatchRuleSource {
  kind: MatchRuleSourceKind
  data: MatchRule | string
}

export interface InjectionRuleWritePayload {
  apiVersion: 'injector.erzbir.com/v1alpha1'
  kind: 'InjectionRule'
  metadata: Metadata
  name: string
  description: string
  enabled: boolean
  mode: InjectionMode
  match: string
  matchRule: MatchRule
  position: InjectionPosition
  wrapMarker: boolean
  snippetIds: string[]
}

export interface InjectionRuleViewModel extends InjectionRuleWritePayload {
  id: string
}

export interface InjectionRuleEditorState {
  matchRuleSource?: MatchRuleSource
}

export type CodeSnippet = CodeSnippetViewModel
export type InjectionRule = InjectionRuleViewModel
export type EditableInjectionRule = InjectionRuleViewModel & InjectionRuleEditorState

export interface ItemList<T> {
  page: number
  size: number
  total: number
  items: Array<T>
  first: boolean
  last: boolean
  hasNext: boolean
  hasPrevious: boolean
  totalPages: number
}

export type ActiveTab = 'snippets' | 'rules'

export const MODE_OPTIONS: { value: InjectionMode; label: string }[] = [
  { value: 'HEAD', label: '<head>' },
  { value: 'FOOTER', label: '<footer>' },
  { value: 'ID', label: '元素 ID' },
  { value: 'SELECTOR', label: 'CSS 选择器' },
]

export const POSITION_OPTIONS: { value: InjectionPosition; label: string }[] = [
  { value: 'APPEND', label: '内部末尾 (append)' },
  { value: 'PREPEND', label: '内部开头 (prepend)' },
  { value: 'BEFORE', label: '元素之前 (before)' },
  { value: 'AFTER', label: '元素之后 (after)' },
  { value: 'REPLACE', label: '替换元素 (replace)' },
  { value: 'REMOVE', label: '移除元素 (remove)' },
]

export const MATCH_RULE_GROUP_OPTIONS: { value: MatchRuleOperator; label: string }[] = [
  { value: 'AND', label: '全部满足 (AND)' },
  { value: 'OR', label: '任一满足 (OR)' },
]

export const PATH_MATCHER_OPTIONS: { value: MatchRuleMatcher; label: string }[] = [
  { value: 'ANT', label: 'Ant 风格' },
  { value: 'REGEX', label: '正则表达式' },
  { value: 'EXACT', label: '精确匹配' },
]

export const TEMPLATE_MATCHER_OPTIONS: { value: MatchRuleMatcher; label: string }[] = [
  { value: 'EXACT', label: '精确匹配' },
  { value: 'REGEX', label: '正则表达式' },
]

export function makePathMatchRule(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: 'PATH',
    negate: false,
    matcher: 'ANT',
    value: '/**',
    ...override,
  }
}

export function makeTemplateMatchRule(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: 'TEMPLATE_ID',
    negate: false,
    matcher: 'EXACT',
    value: 'post',
    ...override,
  }
}

export function makeMatchRuleGroup(override: Partial<MatchRule> = {}): MatchRule {
  return {
    type: 'GROUP',
    negate: false,
    operator: 'AND',
    children: [makePathMatchRule()],
    ...override,
  }
}

export function makeSnippet(override: Partial<CodeSnippetViewModel> = {}): CodeSnippetViewModel {
  return {
    apiVersion: 'injector.erzbir.com/v1alpha1',
    kind: 'CodeSnippet',
    metadata: { name: '', generateName: 'CodeSnippet-' },
    id: '',
    name: '',
    code: '',
    description: '',
    enabled: true,
    ruleIds: [],
    ...override,
  }
}

export function makeRule(override: Partial<EditableInjectionRule> = {}): EditableInjectionRule {
  const matchRule = makeMatchRuleGroup({
    children: [makePathMatchRule({ value: '' })],
  })
  return {
    apiVersion: 'injector.erzbir.com/v1alpha1',
    kind: 'InjectionRule',
    metadata: { name: '', generateName: 'InjectionRule-' },
    id: '',
    name: '',
    description: '',
    enabled: true,
    mode: 'FOOTER',
    match: '',
    matchRule,
    position: 'APPEND',
    wrapMarker: true,
    snippetIds: [],
    matchRuleSource: {
      kind: 'RULE_TREE',
      data: JSON.parse(JSON.stringify(matchRule)) as MatchRule,
    },
    ...override,
  }
}
