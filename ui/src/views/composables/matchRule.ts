import {
  type EditableInjectionRule,
  type InjectionRuleViewModel,
  type InjectionRuleEditorState,
  type InjectionRuleWritePayload,
  type MatchRuleSource,
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

export interface MatchRuleValidationSummary {
  errors: MatchRuleValidationError[]
  rule: MatchRule | null
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
 * why: 简单模式需要把所有可定位到字段的错误同时标出来，
 * 用户才能一次看全空组、空值、非法正则等问题，而不是修完一个才看到下一个。
 */
export function validateSimpleMatchRuleTree(
  rule: MatchRule | null | undefined,
): MatchRuleValidationSummary {
  if (!rule) {
    return {
      rule: null,
      errors: [{ path: '$', message: '请完善匹配规则' }],
    }
  }
  const normalized = normalizeMatchRule(rule)
  const errors: MatchRuleValidationError[] = []
  collectSimpleMatchRuleErrors(normalized, '$', errors)
  return {
    rule: normalized,
    errors,
  }
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
  return {
    ...rule,
    matchRule,
    matchRuleSource: makeRuleTreeSource(matchRule),
  }
}

export function resolveRuleMatchRule(
  rule: InjectionRuleViewModel & Partial<InjectionRuleEditorState>,
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
  if (validateMatchRuleTree(normalizeMatchRule(rule.matchRule)).error) {
    return null
  }
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
    return invalid(
      `${path}.type`,
      '缺少必填字段 "type"；该字段可选值为 "GROUP"、"PATH"、"TEMPLATE_ID"',
    )
  }
  if (input.type !== undefined && typeof input.type !== 'string') {
    return invalid(`${path}.type`, '必须是字符串；仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  }
  if (type !== 'GROUP' && type !== 'PATH' && type !== 'TEMPLATE_ID') {
    return invalid(`${path}.type`, '仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  }

  if (!hasOwnKey(input, 'negate') && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.negate`, '缺少必填字段 "negate"；该字段可选值为 true、false')
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
      return invalid(
        `${path}.operator`,
        '条件组缺少必填字段 "operator"；该字段可选值为 "AND"、"OR"',
      )
    }
    if (!hasOwnKey(input, 'children') && !options.allowMissingRequiredKeys) {
      return invalid(`${path}.children`, '条件组缺少必填字段 "children"')
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
    const rawChildren = Array.isArray(input.children)
      ? input.children
      : options.allowMissingRequiredKeys
        ? []
        : [makePathMatchRule()]
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
        ? '页面路径条件缺少必填字段 "matcher"；该字段可选值为 "ANT"、"REGEX"、"EXACT"'
        : '模板 ID 条件缺少必填字段 "matcher"；该字段可选值为 "REGEX"、"EXACT"',
    )
  }
  if (!hasOwnKey(input, 'value') && !options.allowMissingRequiredKeys) {
    return invalid(
      `${path}.value`,
      type === 'PATH' ? '页面路径条件缺少必填字段 "value"' : '模板 ID 条件缺少必填字段 "value"',
    )
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

function collectSimpleMatchRuleErrors(
  rule: MatchRule,
  path: string,
  errors: MatchRuleValidationError[],
) {
  if (rule.type === 'GROUP') {
    const children = rule.children ?? []
    if (!children.length) {
      errors.push({ path: `${path}.children`, message: '不能有空组' })
      return
    }
    children.forEach((child, index) => {
      collectSimpleMatchRuleErrors(child, `${path}.children[${index}]`, errors)
    })
    return
  }

  const value = rule.value?.trim() ?? ''
  if (!value) {
    errors.push({ path: `${path}.value`, message: '必须是非空字符串' })
    return
  }

  if (rule.matcher === 'REGEX') {
    const regexError = validateRegexValue(value, `${path}.value`)
    if (regexError?.error) {
      errors.push(regexError.error)
    }
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
