import {
  type InjectionRuleEditorDraft,
  type InjectionRuleEditorState,
  type MatchRuleSource,
  type MatchRule,
  makeMatchRuleGroup,
  makePathMatchRule,
  makeTemplateMatchRule,
  type MatchRuleEditorMode,
} from '@/types'
import {
  allowedFieldsFor,
  formatInvalidBooleanFieldMessage,
  formatInvalidEnumFieldTypeMessage,
  formatInvalidEnumFieldValueMessage,
  formatMissingEnumFieldMessage,
  formatUnsupportedFieldMessage,
} from './matchRuleContract.generated'

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

const GROUP_ALLOWED_KEYS = allowedFieldsFor('GROUP')
const PATH_ALLOWED_KEYS = allowedFieldsFor('PATH')
const TEMPLATE_ALLOWED_KEYS = allowedFieldsFor('TEMPLATE_ID')

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
 * 避免再用额外布尔值把“切换模式”和“是否覆盖草稿”两层语义搅在一起。
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

export function isValidMatchRule(rule: MatchRule | null): boolean {
  if (!rule) return false
  if (rule.type === 'GROUP') {
    return !!rule.children?.length && rule.children.every((child) => isValidMatchRule(child))
  }
  return !!rule.value?.trim()
}

export function supportsDomPathPrecheck(rule: MatchRule | null): boolean {
  return analyzePathPrecheckKind(minimizeMatchRuleForAnalysis(rule)) === 'PATH_SCOPED'
}

/**
 * why: 性能提示也必须和保存/export 一样基于当前 editor source 解析结果，
 * 不能在 JSON_DRAFT 已经变坏时还继续读取旧的 `matchRule`，否则就会出现状态错位。
 */
export function getDomRulePerformanceWarning(
  rule: Pick<InjectionRuleEditorDraft, 'mode' | 'matchRule'> & Partial<InjectionRuleEditorState>,
): string | null {
  const result = resolveRuleMatchRule(rule)
  if (!result.rule || result.error) {
    return null
  }
  if (rule.mode !== 'SELECTOR' || supportsDomPathPrecheck(result.rule)) {
    return null
  }
  return '⚠ 当前规则还不能先按页面路径缩小范围。建议在“全部满足（AND）”里先加入“页面路径匹配”，再按需叠加模板 ID 等条件。否则 CSS 选择器模式会先处理所有页面，再继续判断其它条件，因此会多一些处理开销。'
}

export function matchRuleSummary(rule: MatchRule): string {
  return formatAnalysisExpression(minimizeMatchRuleForAnalysis(rule), true)
}

type AnalysisExpression =
  | { kind: 'CONST'; value: boolean }
  | {
      kind: 'LEAF'
      type: 'PATH' | 'TEMPLATE_ID'
      matcher: 'ANT' | 'REGEX' | 'EXACT'
      value: string
    }
  | { kind: 'NOT'; child: AnalysisExpression }
  | { kind: 'GROUP'; operator: 'AND' | 'OR'; children: AnalysisExpression[] }

type PathPrecheckKind = 'PATH_SCOPED' | 'TEMPLATE_ONLY' | 'UNSUPPORTED'

function isGroupExpression(
  expression: AnalysisExpression,
): expression is Extract<AnalysisExpression, { kind: 'GROUP' }> {
  return expression.kind === 'GROUP'
}

function isNotExpression(
  expression: AnalysisExpression,
): expression is Extract<AnalysisExpression, { kind: 'NOT' }> {
  return expression.kind === 'NOT'
}

/**
 * why: 这套布尔最小化只服务于分析期语义：性能提示、路径预筛能力判断和表达式展示。
 * 编辑器草稿仍保留用户原始结构，避免出现“界面里写的是 A，系统偷偷改成了 B”的心智错位。
 */
function minimizeMatchRuleForAnalysis(rule: MatchRule | null): AnalysisExpression {
  return simplifyAnalysisExpression(buildAnalysisExpression(rule))
}

function buildAnalysisExpression(rule: MatchRule | null): AnalysisExpression {
  if (!rule || !rule.type) {
    return { kind: 'CONST', value: false }
  }

  const base: AnalysisExpression =
    rule.type === 'GROUP'
      ? {
          kind: 'GROUP',
          operator: rule.operator === 'OR' ? 'OR' : 'AND',
          children: (rule.children ?? []).map((child) => buildAnalysisExpression(child)),
        }
      : {
          kind: 'LEAF',
          type: rule.type,
          matcher:
            rule.type === 'PATH'
              ? rule.matcher === 'REGEX' || rule.matcher === 'EXACT'
                ? rule.matcher
                : 'ANT'
              : rule.matcher === 'REGEX'
                ? 'REGEX'
                : 'EXACT',
          value: rule.value?.trim() ?? '',
        }

  return rule.negate ? { kind: 'NOT', child: base } : base
}

function simplifyAnalysisExpression(expression: AnalysisExpression): AnalysisExpression {
  switch (expression.kind) {
    case 'CONST':
    case 'LEAF':
      return expression
    case 'NOT':
      return simplifyNotExpression({
        kind: 'NOT',
        child: simplifyAnalysisExpression(expression.child),
      })
    case 'GROUP':
      return simplifyGroupExpression({
        kind: 'GROUP',
        operator: expression.operator,
        children: expression.children.map((child) => simplifyAnalysisExpression(child)),
      })
  }
}

function simplifyNotExpression(
  expression: Extract<AnalysisExpression, { kind: 'NOT' }>,
): AnalysisExpression {
  const child = expression.child
  if (child.kind === 'CONST') {
    return { kind: 'CONST', value: !child.value }
  }
  if (child.kind === 'NOT') {
    return simplifyAnalysisExpression(child.child)
  }
  return expression
}

function simplifyGroupExpression(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression {
  let current: AnalysisExpression = expression

  while (current.kind === 'GROUP') {
    const currentGroup = current
    const flattenedChildren = currentGroup.children.flatMap((child) =>
      isGroupExpression(child) && child.operator === currentGroup.operator
        ? child.children
        : [child],
    )
    const deduplicatedChildren = deduplicateExpressions(flattenedChildren)
    const next = simplifyGroupOnce({
      kind: 'GROUP',
      operator: currentGroup.operator,
      children: sortExpressions(deduplicatedChildren),
    })

    if (analysisExpressionKey(next) === analysisExpressionKey(currentGroup)) {
      return next
    }
    current = simplifyAnalysisExpression(next)
  }

  return current
}

function simplifyGroupOnce(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression {
  const deMorganCandidate = buildReverseDeMorganCandidate(expression)
  if (shouldPreferCandidate(expression, deMorganCandidate)) {
    return deMorganCandidate
  }

  const factorizedCandidate = buildFactorizedCandidate(expression)
  if (shouldPreferCandidate(expression, factorizedCandidate)) {
    return factorizedCandidate
  }

  const absorbedCandidate = buildAbsorbedCandidate(expression)
  if (shouldPreferCandidate(expression, absorbedCandidate)) {
    return absorbedCandidate
  }

  const complementedCandidate = foldComplementedExpression(expression)
  if (shouldPreferCandidate(expression, complementedCandidate)) {
    return complementedCandidate
  }

  return foldConstantExpression(expression)
}

function buildReverseDeMorganCandidate(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression | null {
  if (expression.children.length < 2 || expression.children.some((child) => child.kind !== 'NOT')) {
    return null
  }

  return {
    kind: 'NOT',
    child: {
      kind: 'GROUP',
      operator: expression.operator === 'AND' ? 'OR' : 'AND',
      children: expression.children.map(
        (child) => (child as Extract<AnalysisExpression, { kind: 'NOT' }>).child,
      ),
    },
  }
}

function buildFactorizedCandidate(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression | null {
  if (expression.operator !== 'OR' || expression.children.length < 2) {
    return null
  }

  const factorizedTerms = expression.children.map((child) =>
    isGroupExpression(child) && child.operator === 'AND' ? child.children : [child],
  )
  const commonFactorKeys = intersectExpressionKeys(factorizedTerms)
  if (commonFactorKeys.length === 0) {
    return null
  }

  const commonFactors = factorizedTerms[0].filter((term) =>
    commonFactorKeys.includes(analysisExpressionKey(term)),
  )
  const residualTerms = factorizedTerms.map((termFactors) => {
    const residualFactors = termFactors.filter(
      (term) => !commonFactorKeys.includes(analysisExpressionKey(term)),
    )
    if (residualFactors.length === 0) {
      return { kind: 'CONST', value: true } satisfies AnalysisExpression
    }
    if (residualFactors.length === 1) {
      return residualFactors[0]
    }
    return {
      kind: 'GROUP',
      operator: 'AND',
      children: residualFactors,
    } satisfies AnalysisExpression
  })

  return {
    kind: 'GROUP',
    operator: 'AND',
    children: [
      ...commonFactors,
      {
        kind: 'GROUP',
        operator: 'OR',
        children: residualTerms,
      },
    ],
  }
}

function buildAbsorbedCandidate(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression | null {
  if (expression.operator !== 'AND') {
    return null
  }

  const directChildKeys = new Set(
    expression.children
      .filter((child) => !isGroupExpression(child) || child.operator !== 'OR')
      .map((child) => analysisExpressionKey(child)),
  )
  const filteredChildren = expression.children.filter((child) => {
    if (!isGroupExpression(child) || child.operator !== 'OR') {
      return true
    }
    return !child.children.some((option) => directChildKeys.has(analysisExpressionKey(option)))
  })

  if (filteredChildren.length === expression.children.length) {
    return null
  }

  return {
    kind: 'GROUP',
    operator: 'AND',
    children: filteredChildren,
  }
}

function foldComplementedExpression(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression | null {
  const childKeys = new Set(expression.children.map((child) => analysisExpressionKey(child)))
  for (const child of expression.children) {
    if (!isNotExpression(child)) {
      continue
    }
    if (childKeys.has(analysisExpressionKey(child.child))) {
      return {
        kind: 'CONST',
        value: expression.operator === 'OR',
      }
    }
  }
  return null
}

function foldConstantExpression(
  expression: Extract<AnalysisExpression, { kind: 'GROUP' }>,
): AnalysisExpression {
  if (expression.operator === 'AND') {
    if (expression.children.some((child) => child.kind === 'CONST' && child.value === false)) {
      return { kind: 'CONST', value: false }
    }
    const nonTrueChildren = expression.children.filter(
      (child) => child.kind !== 'CONST' || child.value !== true,
    )
    if (nonTrueChildren.length === 0) {
      return { kind: 'CONST', value: true }
    }
    if (nonTrueChildren.length === 1) {
      return nonTrueChildren[0]
    }
    return {
      kind: 'GROUP',
      operator: 'AND',
      children: nonTrueChildren,
    }
  }

  if (expression.children.some((child) => child.kind === 'CONST' && child.value === true)) {
    return { kind: 'CONST', value: true }
  }
  const nonFalseChildren = expression.children.filter(
    (child) => child.kind !== 'CONST' || child.value !== false,
  )
  if (nonFalseChildren.length === 0) {
    return { kind: 'CONST', value: false }
  }
  if (nonFalseChildren.length === 1) {
    return nonFalseChildren[0]
  }
  return {
    kind: 'GROUP',
    operator: 'OR',
    children: nonFalseChildren,
  }
}

function deduplicateExpressions(expressions: AnalysisExpression[]) {
  const deduplicated = new Map<string, AnalysisExpression>()
  for (const expression of expressions) {
    deduplicated.set(analysisExpressionKey(expression), expression)
  }
  return [...deduplicated.values()]
}

function sortExpressions(expressions: AnalysisExpression[]) {
  return [...expressions].sort(compareAnalysisExpressions)
}

function intersectExpressionKeys(terms: AnalysisExpression[][]) {
  if (terms.length === 0) {
    return []
  }

  let sharedKeys = new Set(terms[0].map((term) => analysisExpressionKey(term)))
  for (const termFactors of terms.slice(1)) {
    const currentKeys = new Set(termFactors.map((term) => analysisExpressionKey(term)))
    sharedKeys = new Set([...sharedKeys].filter((key) => currentKeys.has(key)))
  }
  return [...sharedKeys].sort()
}

function shouldPreferCandidate(
  current: AnalysisExpression,
  candidate: AnalysisExpression | null,
): candidate is AnalysisExpression {
  return (
    candidate !== null &&
    analysisExpressionComplexity(candidate) < analysisExpressionComplexity(current)
  )
}

function analysisExpressionComplexity(expression: AnalysisExpression): number {
  switch (expression.kind) {
    case 'CONST':
      return 0
    case 'LEAF':
      return 1
    case 'NOT':
      return 1 + analysisExpressionComplexity(expression.child)
    case 'GROUP':
      return (
        1 + expression.children.reduce((sum, child) => sum + analysisExpressionComplexity(child), 0)
      )
  }
}

function compareAnalysisExpressions(left: AnalysisExpression, right: AnalysisExpression): number {
  const rankDifference = analysisExpressionSortRank(left) - analysisExpressionSortRank(right)
  if (rankDifference !== 0) {
    return rankDifference
  }
  return analysisExpressionKey(left).localeCompare(analysisExpressionKey(right))
}

function analysisExpressionSortRank(expression: AnalysisExpression): number {
  switch (expression.kind) {
    case 'CONST':
      return 0
    case 'LEAF':
      return 1
    case 'NOT':
      return 2
    case 'GROUP':
      return 3
  }
}

function analysisExpressionKey(expression: AnalysisExpression): string {
  switch (expression.kind) {
    case 'CONST':
      return expression.value ? 'TRUE' : 'FALSE'
    case 'LEAF':
      return `${expression.type}:${expression.matcher}:${expression.value}`
    case 'NOT':
      return `!${analysisExpressionKey(expression.child)}`
    case 'GROUP':
      return `${expression.operator}(${sortExpressions(expression.children)
        .map((child) => analysisExpressionKey(child))
        .join(',')})`
  }
}

function formatAnalysisExpression(expression: AnalysisExpression, root = false): string {
  switch (expression.kind) {
    case 'CONST':
      return expression.value ? 'TRUE' : 'FALSE'
    case 'LEAF': {
      const subject = expression.type === 'PATH' ? 'path' : 'id'
      const matcher =
        expression.matcher === 'REGEX' ? 're' : expression.matcher === 'EXACT' ? '=' : 'ant'
      return `${subject}:${matcher}:${expression.value}`
    }
    case 'NOT': {
      const needsGrouping = isGroupExpression(expression.child)
      const child = needsGrouping
        ? formatAnalysisExpression(expression.child, true)
        : formatAnalysisExpression(expression.child)
      return needsGrouping ? `!(${child})` : `!${child}`
    }
    case 'GROUP': {
      const operator = expression.operator === 'OR' ? ' | ' : ' & '
      const content = expression.children
        .map((child) => formatAnalysisExpression(child))
        .join(operator)
      return root ? content : `(${content})`
    }
  }
}

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
    return invalid(`${path}.type`, formatMissingEnumFieldMessage('type', 'TYPE'))
  }
  if (input.type !== undefined && typeof input.type !== 'string') {
    return invalid(`${path}.type`, formatInvalidEnumFieldTypeMessage('TYPE'))
  }
  if (type !== 'GROUP' && type !== 'PATH' && type !== 'TEMPLATE_ID') {
    return invalid(`${path}.type`, formatInvalidEnumFieldValueMessage('TYPE'))
  }

  if (!hasOwnKey(input, 'negate') && !options.allowMissingRequiredKeys) {
    return invalid(`${path}.negate`, formatMissingEnumFieldMessage('negate', 'BOOLEAN'))
  }
  if (input.negate !== undefined && typeof input.negate !== 'boolean') {
    return invalid(`${path}.negate`, formatInvalidBooleanFieldMessage())
  }

  if (options.requireGroupRoot && type !== 'GROUP') {
    return invalid(`${path}.type`, '根节点必须是 GROUP')
  }

  if (type === 'GROUP') {
    const unknownKey = findUnknownKey(input, GROUP_ALLOWED_KEYS)
    if (unknownKey && !options.allowUnknownKeys) {
      return invalid(`${path}.${unknownKey}`, formatUnsupportedFieldMessage('GROUP'))
    }
    if (!hasOwnKey(input, 'operator') && !options.allowMissingRequiredKeys) {
      return invalid(
        `${path}.operator`,
        formatMissingEnumFieldMessage('operator', 'OPERATOR', '条件组'),
      )
    }
    if (!hasOwnKey(input, 'children') && !options.allowMissingRequiredKeys) {
      return invalid(`${path}.children`, '条件组缺少必填字段 "children"')
    }
    if (input.operator !== undefined && typeof input.operator !== 'string') {
      return invalid(`${path}.operator`, formatInvalidEnumFieldTypeMessage('OPERATOR'))
    }
    if (input.operator !== undefined && input.operator !== 'AND' && input.operator !== 'OR') {
      return invalid(`${path}.operator`, formatInvalidEnumFieldValueMessage('OPERATOR'))
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
    return invalid(`${path}.operator`, formatUnsupportedFieldMessage(type))
  }
  if (input.children !== undefined) {
    return invalid(`${path}.children`, formatUnsupportedFieldMessage(type))
  }
  const allowedKeys = type === 'PATH' ? PATH_ALLOWED_KEYS : TEMPLATE_ALLOWED_KEYS
  const unknownKey = findUnknownKey(input, allowedKeys)
  if (unknownKey && !options.allowUnknownKeys) {
    return invalid(`${path}.${unknownKey}`, formatUnsupportedFieldMessage(type))
  }
  if (!hasOwnKey(input, 'matcher') && !options.allowMissingRequiredKeys) {
    return invalid(
      `${path}.matcher`,
      type === 'PATH'
        ? formatMissingEnumFieldMessage('matcher', 'PATH_MATCHER', '页面路径条件')
        : formatMissingEnumFieldMessage('matcher', 'TEMPLATE_MATCHER', '模板 ID 条件'),
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
      return invalid(`${path}.matcher`, formatInvalidEnumFieldTypeMessage('PATH_MATCHER'))
    }
    if (
      input.matcher !== undefined &&
      input.matcher !== 'ANT' &&
      input.matcher !== 'REGEX' &&
      input.matcher !== 'EXACT'
    ) {
      if (!options.allowIncompatibleMatcher) {
        return invalid(`${path}.matcher`, formatInvalidEnumFieldValueMessage('PATH_MATCHER'))
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
    return invalid(`${path}.matcher`, formatInvalidEnumFieldTypeMessage('TEMPLATE_MATCHER'))
  }
  if (input.matcher !== undefined && input.matcher !== 'REGEX' && input.matcher !== 'EXACT') {
    if (!options.allowIncompatibleMatcher) {
      return invalid(
        `${path}.matcher`,
        `模板 ID ${formatInvalidEnumFieldValueMessage('TEMPLATE_MATCHER')}`,
      )
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
function analyzePathPrecheckKind(expression: AnalysisExpression): PathPrecheckKind {
  if (expression.kind === 'CONST') {
    return 'PATH_SCOPED'
  }
  if (expression.kind === 'LEAF') {
    return expression.type === 'PATH' ? 'PATH_SCOPED' : 'TEMPLATE_ONLY'
  }
  if (expression.kind === 'NOT') {
    return containsTemplateExpression(expression.child) ? 'UNSUPPORTED' : 'PATH_SCOPED'
  }
  const children = expression.children
  if (!children.length) return 'PATH_SCOPED'
  if (expression.operator === 'OR') {
    return analyzeOrPathPrecheckKind(children)
  }
  return analyzeAndPathPrecheckKind(children)
}

function analyzeAndPathPrecheckKind(children: AnalysisExpression[]): PathPrecheckKind {
  let hasPathScoped = false
  for (const child of children) {
    const kind = analyzePathPrecheckKind(child)
    if (kind === 'UNSUPPORTED') return 'UNSUPPORTED'
    if (kind === 'PATH_SCOPED') hasPathScoped = true
  }
  return hasPathScoped ? 'PATH_SCOPED' : 'TEMPLATE_ONLY'
}

function analyzeOrPathPrecheckKind(children: AnalysisExpression[]): PathPrecheckKind {
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

function containsTemplateExpression(expression: AnalysisExpression): boolean {
  if (expression.kind === 'LEAF') {
    return expression.type === 'TEMPLATE_ID'
  }
  if (expression.kind === 'NOT') {
    return containsTemplateExpression(expression.child)
  }
  if (expression.kind === 'GROUP') {
    return expression.children.some((child) => containsTemplateExpression(child))
  }
  return false
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
