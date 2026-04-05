import {
  type EditableInjectionRule,
  type InjectionRuleViewModel,
  type InjectionRuleEditorState,
  type InjectionRuleWritePayload,
  type MatchRuleEditorMode,
  type MatchRule,
  makeMatchRuleGroup,
  makePathMatchRule,
  makeTemplateMatchRule,
} from '@/types'

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

export interface MatchRuleValidationError {
  path: string
  message: string
  line?: number
  column?: number
}

export interface MatchRuleParseResult {
  rule: MatchRule | null
  error: MatchRuleValidationError | null
}

interface MatchRuleValidationOptions {
  requireGroupRoot: boolean
  allowEmptyGroup: boolean
  allowEmptyValue: boolean
  allowInvalidRegex: boolean
  allowIncompatibleMatcher: boolean
  allowUnknownKeys: boolean
  allowMissingRequiredKeys: boolean
}

const GROUP_ALLOWED_KEYS = ['type', 'negate', 'operator', 'children'] as const
const PATH_ALLOWED_KEYS = ['type', 'negate', 'matcher', 'value'] as const
const TEMPLATE_ALLOWED_KEYS = ['type', 'negate', 'matcher', 'value'] as const

const MATCH_RULE_EDITOR_STATE_KEY = 'plugin-injector:match-rule-editor-state'

interface StoredMatchRuleEditorState {
  draft?: string
  editorMode?: MatchRuleEditorMode
}

/**
 * why: 编辑器需要“可随意修改的草稿副本”，避免直接改动响应式源对象时把未保存状态提前污染到列表数据。
 */
export function cloneMatchRule(rule: MatchRule): MatchRule {
  return JSON.parse(JSON.stringify(rule)) as MatchRule
}

/**
 * why: 只对“形状正确”的对象做归一化，保证简单模式、JSON 模式和后端入参围绕同一份稳定结构工作。
 * 同时保留编辑过程中的“空条件组”中间态，让用户先删空再继续补条件；是否允许保存，交给校验层决定。
 */
export function normalizeMatchRule(input: unknown): MatchRule {
  if (!isObject(input)) {
    return makeMatchRuleGroup()
  }

  const type = input.type
  const negate = input.negate === true
  const inputChildren = Array.isArray(input.children) ? input.children : null

  if (type === 'PATH') {
    return makePathMatchRule({
      negate,
      matcher: input.matcher === 'REGEX' || input.matcher === 'EXACT' ? input.matcher : 'ANT',
      value: typeof input.value === 'string' ? input.value : '/**',
    })
  }

  if (type === 'TEMPLATE_ID') {
    return makeTemplateMatchRule({
      negate,
      matcher: input.matcher === 'REGEX' ? 'REGEX' : 'EXACT',
      value: typeof input.value === 'string' ? input.value : 'post',
    })
  }

  const hasExplicitChildren = Array.isArray(inputChildren)
  const children = hasExplicitChildren
    ? inputChildren.map((child) => normalizeMatchRule(child))
    : [makePathMatchRule()]

  return makeMatchRuleGroup({
    negate,
    operator: input.operator === 'OR' ? 'OR' : 'AND',
    children: hasExplicitChildren ? children : [makePathMatchRule()],
  })
}

export function formatMatchRule(rule: MatchRule): string {
  return JSON.stringify(normalizeMatchRule(rule), null, 2)
}

export function parseMatchRuleDraft(draft?: string | null): MatchRuleParseResult {
  if (!draft || !draft.trim()) {
    return {
      rule: null,
      error: {
        path: '$',
        message: '请输入匹配规则 JSON',
      },
    }
  }
  try {
    return validateMatchRuleInput(JSON.parse(draft), '$', {
      requireGroupRoot: true,
      allowEmptyGroup: false,
      allowEmptyValue: false,
      allowInvalidRegex: false,
      allowIncompatibleMatcher: false,
      allowUnknownKeys: false,
      allowMissingRequiredKeys: false,
    })
  } catch (error) {
    return {
      rule: null,
      error: buildJsonSyntaxError(draft, error),
    }
  }
}

export function validateMatchRuleTree(rule: MatchRule | null | undefined): MatchRuleParseResult {
  if (!rule) {
    return {
      rule: null,
      error: {
        path: '$',
        message: '请完善匹配规则',
      },
    }
  }
  return validateMatchRuleInput(JSON.parse(JSON.stringify(rule)) as unknown, '$', {
    requireGroupRoot: true,
    allowEmptyGroup: false,
    allowEmptyValue: false,
    allowInvalidRegex: false,
    allowIncompatibleMatcher: false,
    allowUnknownKeys: false,
    allowMissingRequiredKeys: false,
  })
}

/**
 * why: 导入场景需要拦住会破坏编辑器结构的坏数据，
 * 同时对“写错字段名”与“漏填可补默认值的字段”做宽松归一化：
 * 错键直接丢弃，缺键补默认值；但像非法根节点类型这类会破坏结构的问题，仍然直接拒绝导入。
 */
export function validateMatchRuleObject(input: unknown, path = 'matchRule'): MatchRuleParseResult {
  return validateMatchRuleInput(input, path, {
    requireGroupRoot: true,
    allowEmptyGroup: true,
    allowEmptyValue: true,
    allowInvalidRegex: true,
    allowIncompatibleMatcher: true,
    allowUnknownKeys: true,
    allowMissingRequiredKeys: true,
  })
}

export function hydrateRuleForEditor(rule: InjectionRuleViewModel): EditableInjectionRule {
  const matchRule = normalizeMatchRule(rule.matchRule)
  const storedState = readStoredMatchRuleEditorState(rule.id)
  const draft =
    typeof storedState?.draft === 'string' && storedState.draft.trim()
      ? storedState.draft
      : formatMatchRule(matchRule)
  return {
    ...rule,
    matchRule,
    matchRuleDraft: draft,
    matchRuleEditorMode: storedState?.editorMode ?? 'SIMPLE',
  }
}

/**
 * why: 匹配规则的简单/高级模式和 JSON 草稿是纯前端编辑态，
 * 不应写入后端模型；这里按规则 ID 落在本地，保证刷新后仍能恢复用户刚才的编辑视图。
 */
export function persistMatchRuleEditorState(
  rule: Pick<InjectionRuleViewModel, 'id'> & InjectionRuleEditorState,
) {
  if (!rule.id || typeof window === 'undefined') {
    return
  }

  const stateMap = readStoredMatchRuleEditorStateMap()
  stateMap[rule.id] = {
    draft: rule.matchRuleDraft,
    editorMode: rule.matchRuleEditorMode ?? 'SIMPLE',
  }
  window.localStorage.setItem(MATCH_RULE_EDITOR_STATE_KEY, JSON.stringify(stateMap))
}

/**
 * why: “放弃未保存修改”后应回到已保存内容；这里只清空本地 JSON 草稿，
 * 同时保留当前编辑模式，避免用户回来看时仍被旧的错误草稿覆盖。
 */
export function clearPersistedMatchRuleDraft(
  ruleId: string,
  editorMode: MatchRuleEditorMode = 'SIMPLE',
) {
  if (!ruleId || typeof window === 'undefined') {
    return
  }

  const stateMap = readStoredMatchRuleEditorStateMap()
  stateMap[ruleId] = {
    draft: '',
    editorMode,
  }
  window.localStorage.setItem(MATCH_RULE_EDITOR_STATE_KEY, JSON.stringify(stateMap))
}

export function resolveRuleMatchRule(
  rule: InjectionRuleViewModel & Partial<InjectionRuleEditorState>,
): MatchRuleParseResult {
  if (rule.matchRuleEditorMode === 'JSON' && rule.matchRuleDraft?.trim()) {
    return parseMatchRuleDraft(rule.matchRuleDraft)
  }
  const normalized = normalizeMatchRule(rule.matchRule)
  return validateMatchRuleTree(normalized)
}

export function isValidMatchRule(rule: MatchRule | null): boolean {
  if (!rule) return false
  if (rule.type === 'GROUP') {
    return !!rule.children?.length && rule.children.every((child) => isValidMatchRule(child))
  }
  return !!rule.value?.trim()
}

export function supportsDomPathPrecheck(rule: MatchRule | null): boolean {
  return analyzePathPrecheckKind(rule) === 'PATH_SCOPED'
}

export function getDomRulePerformanceWarning(
  rule: Pick<InjectionRuleViewModel, 'mode' | 'matchRule'>,
): string | null {
  if ((rule.mode !== 'SELECTOR' && rule.mode !== 'ID') || supportsDomPathPrecheck(rule.matchRule)) {
    return null
  }
  return '⚠ 当前规则还不能先按页面路径缩小范围。建议在“全部满足（AND）”里先加入“页面路径匹配”，再按需叠加模板 ID 等条件。否则元素 ID / CSS 选择器模式会先处理所有页面，再继续判断其它条件，因此会多一些处理开销。'
}

/**
 * why: 发送到后端前统一收敛编辑态字段，避免 JSON 草稿、REMOVE 的空代码块关联等 UI 细节污染持久化模型。
 */
export function makeRulePayload(
  rule: InjectionRuleViewModel & Partial<InjectionRuleEditorState>,
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
    sortOrder: rule.sortOrder,
    mode: rule.mode,
    match: rule.match.trim(),
    matchRule: result.rule,
    position: rule.position,
    wrapMarker: normalizedWrapMarker,
    snippetIds: normalizedSnippetIds,
  }
}

export function matchRuleChips(rule: MatchRule, limit = 4): string[] {
  const chips: string[] = []
  collectMatchRuleChips(normalizeMatchRule(rule), chips, limit)
  return chips
}

export function matchRuleExpression(rule: MatchRule): string {
  return formatMatchRuleExpression(normalizeMatchRule(rule), true)
}

function collectMatchRuleChips(rule: MatchRule, chips: string[], limit: number) {
  if (chips.length >= limit) return
  if (rule.type === 'GROUP') {
    rule.children?.forEach((child) => collectMatchRuleChips(child, chips, limit))
    return
  }
  chips.push(describeMatchRule(rule))
}

function describeMatchRule(rule: MatchRule): string {
  const prefix = rule.negate ? '不满足本项（NOT） · ' : ''
  if (rule.type === 'PATH') {
    const matcherLabel =
      rule.matcher === 'REGEX' ? '正则表达式' : rule.matcher === 'EXACT' ? '精确匹配' : 'Ant 风格'
    return `${prefix}页面路径 · ${matcherLabel}: ${rule.value ?? ''}`
  }
  const matcherLabel = rule.matcher === 'REGEX' ? '正则表达式' : '精确匹配'
  return `${prefix}模板 ID · ${matcherLabel}: ${rule.value ?? ''}`
}

function formatMatchRuleExpression(rule: MatchRule, root = false): string {
  if (rule.type === 'GROUP') {
    const operator = rule.operator === 'OR' ? ' | ' : ' & '
    const content = (rule.children ?? [])
      .map((child) => formatMatchRuleExpression(child))
      .join(operator)
    const grouped = root ? content : `(${content})`
    return rule.negate ? `!${grouped}` : grouped
  }

  const subject = rule.type === 'PATH' ? 'path' : 'template_id'
  const matcher = rule.matcher === 'REGEX' ? 'regex' : rule.matcher === 'EXACT' ? '=' : 'ant'
  const leaf = `${subject}:${matcher}:${rule.value ?? ''}`
  return rule.negate ? `!${leaf}` : leaf
}

type PathPrecheckKind = 'PATH_SCOPED' | 'TEMPLATE_ONLY' | 'UNSUPPORTED'

export function formatMatchRuleError(error: MatchRuleValidationError | null): string {
  if (!error) return ''
  const location =
    typeof error.line === 'number' && typeof error.column === 'number'
      ? `第 ${error.line} 行，第 ${error.column} 列`
      : error.path
  return `${location}：${error.message}`
}

function validateMatchRuleInput(
  input: unknown,
  path: string,
  options: MatchRuleValidationOptions,
): MatchRuleParseResult {
  if (!isObject(input)) {
    return invalid(path, '必须是对象')
  }

  const type = input.type
  if (!hasOwnKey(input, 'type') && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.type`, '缺少必填字段；仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  }
  if (input.type !== undefined && typeof input.type !== 'string') {
    return invalid(`${path}.type`, '必须是字符串；仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  }
  if (type !== 'GROUP' && type !== 'PATH' && type !== 'TEMPLATE_ID') {
    return invalid(`${path}.type`, '仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  }

  if (input.negate !== undefined && typeof input.negate !== 'boolean') {
    return invalid(`${path}.negate`, '必须是布尔值；仅支持 true 或 false')
  }

  if (options.requireGroupRoot && type !== 'GROUP') {
    return invalid(`${path}.type`, '根节点必须是 GROUP')
  }

  if (type === 'GROUP') {
    const unknownKey = findUnknownKey(input, GROUP_ALLOWED_KEYS)
    if (unknownKey && !options.allowUnknownKeys) {
      return invalid(
        `${path}.${unknownKey}`,
        '不支持该字段；条件组仅支持 "type"、"negate"、"operator"、"children"',
      )
    }
    if (!hasOwnKey(input, 'operator') && !options.allowMissingRequiredKeys) {
      return invalid(`${path}.operator`, '缺少必填字段；仅支持 "AND" 或 "OR"')
    }
    if (!hasOwnKey(input, 'children') && !options.allowMissingRequiredKeys) {
      return invalid(`${path}.children`, '缺少必填字段')
    }
    if (input.operator !== undefined && typeof input.operator !== 'string') {
      return invalid(`${path}.operator`, '必须是字符串；仅支持 "AND" 或 "OR"')
    }
    if (input.operator !== undefined && input.operator !== 'AND' && input.operator !== 'OR') {
      return invalid(`${path}.operator`, '仅支持 "AND" 或 "OR"')
    }
    if (hasOwnKey(input, 'children') && !Array.isArray(input.children)) {
      return invalid(`${path}.children`, '必须是数组')
    }
    const rawChildren = Array.isArray(input.children) ? input.children : [makePathMatchRule()]
    if (!rawChildren.length && !options.allowEmptyGroup) {
      return invalid(`${path}.children`, '不能有空组')
    }

    const children: MatchRule[] = []
    for (let index = 0; index < rawChildren.length; index += 1) {
      const childResult = validateMatchRuleInput(rawChildren[index], `${path}.children[${index}]`, {
        ...options,
        requireGroupRoot: false,
      })
      if (childResult.error) {
        return childResult
      }
      children.push(childResult.rule as MatchRule)
    }

    return {
      rule: makeMatchRuleGroup({
        negate: input.negate === true,
        operator: input.operator === 'OR' ? 'OR' : 'AND',
        children,
      }),
      error: null,
    }
  }

  if (input.operator !== undefined) {
    return invalid(`${path}.operator`, '仅条件组可使用 operator')
  }
  if (input.children !== undefined) {
    return invalid(`${path}.children`, '仅条件组可使用 children')
  }
  const allowedKeys = type === 'PATH' ? PATH_ALLOWED_KEYS : TEMPLATE_ALLOWED_KEYS
  const unknownKey = findUnknownKey(input, allowedKeys)
  if (unknownKey && !options.allowUnknownKeys) {
    return invalid(
      `${path}.${unknownKey}`,
      type === 'PATH'
        ? '不支持该字段；页面路径条件仅支持 "type"、"negate"、"matcher"、"value"'
        : '不支持该字段；模板 ID 条件仅支持 "type"、"negate"、"matcher"、"value"',
    )
  }
  if (!hasOwnKey(input, 'matcher') && !options.allowMissingRequiredKeys) {
    return invalid(
      `${path}.matcher`,
      type === 'PATH'
        ? '缺少必填字段；仅支持 "ANT"、"REGEX"、"EXACT"'
        : '缺少必填字段；仅支持 "REGEX" 或 "EXACT"',
    )
  }
  if (!hasOwnKey(input, 'value') && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.value`, '缺少必填字段')
  }

  if (hasOwnKey(input, 'value') && typeof input.value !== 'string') {
    return invalid(`${path}.value`, '必须是字符串')
  }
  if (typeof input.value === 'string' && !input.value.trim() && !options.allowEmptyValue) {
    return invalid(`${path}.value`, '必须是非空字符串')
  }

  if (type === 'PATH') {
    const normalizedValue = typeof input.value === 'string' ? input.value.trim() : '/**'

    if (input.matcher !== undefined && typeof input.matcher !== 'string') {
      return invalid(`${path}.matcher`, '必须是字符串；仅支持 "ANT"、"REGEX"、"EXACT"')
    }
    if (
      input.matcher !== undefined &&
      input.matcher !== 'ANT' &&
      input.matcher !== 'REGEX' &&
      input.matcher !== 'EXACT'
    ) {
      if (!options.allowIncompatibleMatcher) {
        return invalid(`${path}.matcher`, '仅支持 "ANT"、"REGEX"、"EXACT"')
      }
    }
    if (input.matcher === 'REGEX' && !options.allowInvalidRegex) {
      const regexError = validateRegexValue(normalizedValue, `${path}.value`)
      if (regexError) return regexError
    }
    return {
      rule: makePathMatchRule({
        negate: input.negate === true,
        matcher: input.matcher === 'REGEX' || input.matcher === 'EXACT' ? input.matcher : 'ANT',
        value: normalizedValue,
      }),
      error: null,
    }
  }

  const normalizedValue = typeof input.value === 'string' ? input.value.trim() : 'post'

  if (input.matcher !== undefined && typeof input.matcher !== 'string') {
    return invalid(`${path}.matcher`, '必须是字符串；仅支持 "REGEX" 或 "EXACT"')
  }
  if (input.matcher !== undefined && input.matcher !== 'REGEX' && input.matcher !== 'EXACT') {
    if (!options.allowIncompatibleMatcher) {
      return invalid(`${path}.matcher`, '模板 ID 仅支持 "REGEX" 或 "EXACT"')
    }
  }
  if (input.matcher === 'REGEX' && !options.allowInvalidRegex) {
    const regexError = validateRegexValue(normalizedValue, `${path}.value`)
    if (regexError) return regexError
  }

  return {
    rule: makeTemplateMatchRule({
      negate: input.negate === true,
      matcher: input.matcher === 'REGEX' ? 'REGEX' : 'EXACT',
      value: normalizedValue,
    }),
    error: null,
  }
}

/**
 * why: 前端与后端共用同一套“路径预筛能力”判定思路，
 * 用来识别 DOM 注入是否能先按页面路径缩小范围，并在配置页给出准确的性能提示。
 */
function analyzePathPrecheckKind(rule: MatchRule | null): PathPrecheckKind {
  if (!rule) return 'UNSUPPORTED'

  if (rule.negate) {
    return analyzeNegatedPathPrecheckKind(rule)
  }

  if (rule.type === 'PATH') {
    return 'PATH_SCOPED'
  }

  if (rule.type === 'TEMPLATE_ID') {
    return 'TEMPLATE_ONLY'
  }

  const children = rule.children ?? []
  if (!children.length) return 'UNSUPPORTED'

  if (rule.operator === 'OR') {
    return analyzeOrPathPrecheckKind(children)
  }

  return analyzeAndPathPrecheckKind(children)
}

function analyzeNegatedPathPrecheckKind(rule: MatchRule): PathPrecheckKind {
  if (rule.type === 'PATH') {
    return 'PATH_SCOPED'
  }
  if (rule.type === 'TEMPLATE_ID') {
    return 'UNSUPPORTED'
  }
  return containsTemplateRule(rule) ? 'UNSUPPORTED' : analyzeGroupPathPrecheckKind(rule)
}

function analyzeGroupPathPrecheckKind(rule: MatchRule): PathPrecheckKind {
  const children = rule.children ?? []
  if (!children.length) return 'UNSUPPORTED'
  return rule.operator === 'OR'
    ? analyzeOrPathPrecheckKind(children)
    : analyzeAndPathPrecheckKind(children)
}

function analyzeAndPathPrecheckKind(children: MatchRule[]): PathPrecheckKind {
  let hasPathScoped = false
  for (const child of children) {
    const kind = analyzePathPrecheckKind(child)
    if (kind === 'UNSUPPORTED') return 'UNSUPPORTED'
    if (kind === 'PATH_SCOPED') hasPathScoped = true
  }
  return hasPathScoped ? 'PATH_SCOPED' : 'TEMPLATE_ONLY'
}

function analyzeOrPathPrecheckKind(children: MatchRule[]): PathPrecheckKind {
  let hasPathScoped = false
  let hasTemplateOnly = false
  for (const child of children) {
    const kind = analyzePathPrecheckKind(child)
    if (kind === 'UNSUPPORTED') return 'UNSUPPORTED'
    if (kind === 'PATH_SCOPED') hasPathScoped = true
    if (kind === 'TEMPLATE_ONLY') hasTemplateOnly = true
  }
  if (hasPathScoped && hasTemplateOnly) return 'UNSUPPORTED'
  if (hasPathScoped) return 'PATH_SCOPED'
  return 'TEMPLATE_ONLY'
}

function containsTemplateRule(rule: MatchRule): boolean {
  if (rule.type === 'TEMPLATE_ID') return true
  if (rule.type !== 'GROUP') return false
  return (rule.children ?? []).some((child) => containsTemplateRule(child))
}

function invalid(path: string, message: string): MatchRuleParseResult {
  return {
    rule: null,
    error: { path, message },
  }
}

function hasOwnKey(input: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(input, key)
}

function findUnknownKey(
  input: Record<string, unknown>,
  allowedKeys: readonly string[],
): string | null {
  for (const key of Object.keys(input)) {
    if (!allowedKeys.includes(key)) {
      return key
    }
  }
  return null
}

function validateRegexValue(value: string, path: string): MatchRuleParseResult | null {
  try {
    // 前端提前编译一次，尽早把错误定位到具体字段，避免用户保存后才发现规则无法生效。
    new RegExp(value)
    return null
  } catch (error) {
    const message = error instanceof Error ? error.message : '正则表达式无效'
    return invalid(path, `正则表达式无效：${message}`)
  }
}

function buildJsonSyntaxError(draft: string, error: unknown): MatchRuleValidationError {
  const message = error instanceof Error ? error.message : 'JSON 格式无效'
  const positionMatch = message.match(/position\s+(\d+)/i)
  if (!positionMatch) {
    return { path: '$', message }
  }

  const position = Number(positionMatch[1])
  const prefix = draft.slice(0, position)
  const lines = prefix.split('\n')
  return {
    path: '$',
    message,
    line: lines.length,
    column: lines[lines.length - 1].length + 1,
  }
}

function readStoredMatchRuleEditorState(ruleId: string): StoredMatchRuleEditorState | null {
  if (!ruleId) {
    return null
  }
  const stateMap = readStoredMatchRuleEditorStateMap()
  return stateMap[ruleId] ?? null
}

function readStoredMatchRuleEditorStateMap(): Record<string, StoredMatchRuleEditorState> {
  if (typeof window === 'undefined') {
    return {}
  }

  try {
    const raw = window.localStorage.getItem(MATCH_RULE_EDITOR_STATE_KEY)
    if (!raw) {
      return {}
    }
    const parsed = JSON.parse(raw)
    return isObject(parsed) ? (parsed as Record<string, StoredMatchRuleEditorState>) : {}
  } catch {
    return {}
  }
}
