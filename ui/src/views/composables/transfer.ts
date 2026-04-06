import {
  makeRule,
  makeSnippet,
  type CodeSnippet,
  type EditableInjectionRule,
  type MatchRuleSource,
} from '@/types'
import {
  makeJsonDraftSource,
  makeRuleTreeSource,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleObject,
} from './matchRule'

type TransferResourceType = 'snippet' | 'rule'

interface TransferEnvelope<TType extends TransferResourceType, TData> {
  format: 'halo-plugin-injector'
  version: 1
  resourceType: TType
  data: TData
}

interface SnippetTransferData {
  enabled: boolean
  name: string
  description: string
  code: string
}

interface RuleTransferData {
  enabled: boolean
  name: string
  description: string
  mode: EditableInjectionRule['mode']
  match: string
  position: EditableInjectionRule['position']
  wrapMarker: boolean
  matchRuleSource: MatchRuleSource
}

type SnippetTransferEnvelope = TransferEnvelope<'snippet', SnippetTransferData>
type RuleTransferEnvelope = TransferEnvelope<'rule', RuleTransferData>

/**
 * why: 导入导出只面向用户可编辑内容；像 id、排序、metadata、关联关系这类系统字段
 * 不应跟着资源模板流转，避免“导入即复制脏状态”。
 */
export function buildSnippetTransfer(snippet: CodeSnippet): SnippetTransferEnvelope {
  return {
    format: 'halo-plugin-injector',
    version: 1,
    resourceType: 'snippet',
    data: {
      enabled: snippet.enabled,
      name: snippet.name,
      description: snippet.description,
      code: snippet.code,
    },
  }
}

/**
 * why: 规则导出保留当前编辑器看到的规则内容与编辑模式，
 * 这样用户再导入时，既能得到同一条规则，也不会把关联代码块一并复制过去。
 */
export function buildRuleTransfer(rule: EditableInjectionRule): RuleTransferEnvelope {
  const matchRuleSource = buildRuleTransferMatchRuleSource(rule)
  return {
    format: 'halo-plugin-injector',
    version: 1,
    resourceType: 'rule',
    data: {
      enabled: rule.enabled,
      name: rule.name,
      description: rule.description,
      mode: rule.mode,
      match: rule.match,
      position: rule.position,
      wrapMarker: rule.wrapMarker,
      matchRuleSource,
    },
  }
}

interface SaveFilePickerOptionsLike {
  suggestedName?: string
  types?: Array<{
    description?: string
    accept: Record<string, string[]>
  }>
}

interface SaveFilePickerHandleLike {
  createWritable(): Promise<{
    write(data: string): Promise<void>
    close(): Promise<void>
  }>
}

type WindowWithSaveFilePicker = Window & {
  showSaveFilePicker?: (options?: SaveFilePickerOptionsLike) => Promise<SaveFilePickerHandleLike>
}

export interface TransferFileDraft {
  fileName: string
  content: string
}

export function createTransferFileDraft(
  payload: SnippetTransferEnvelope | RuleTransferEnvelope,
  name: string,
): TransferFileDraft {
  const fileName = `${sanitizeFileName(name)}.json`
  const content = JSON.stringify(payload, null, 2)
  return {
    fileName,
    content,
  }
}

/**
 * why: 导出优先走系统“另存为”，失败时由上层决定如何兜底，
 * 这样既能覆盖浏览器不支持、非安全上下文等场景，也能给用户保留手动复制窗口。
 */
export async function downloadTransferFile(draft: TransferFileDraft) {
  const saveFilePicker = (window as WindowWithSaveFilePicker).showSaveFilePicker

  if (typeof saveFilePicker !== 'function') {
    throw new Error('当前环境暂时无法直接保存为文件')
  }
  const handle = await saveFilePicker({
    suggestedName: draft.fileName,
    types: [
      {
        description: 'JSON 文件',
        accept: {
          'application/json': ['.json'],
        },
      },
    ],
  })
  const writable = await handle.createWritable()
  await writable.write(draft.content)
  await writable.close()
}

export function parseSnippetTransfer(raw: string): CodeSnippet {
  const envelope = parseEnvelope<SnippetTransferEnvelope>(raw, 'snippet')
  const data = envelope.data
  ensureAllowedFields(data, ['enabled', 'name', 'description', 'code'], '代码块')
  if (data.enabled !== undefined && typeof data.enabled !== 'boolean') {
    throw new Error('导入失败：`enabled` 必须是布尔值；仅支持 true 或 false')
  }
  if (data.name !== undefined && typeof data.name !== 'string') {
    throw new Error('导入失败：`name` 必须是字符串')
  }
  if (data.description !== undefined && typeof data.description !== 'string') {
    throw new Error('导入失败：`description` 必须是字符串')
  }
  if (data.code !== undefined && typeof data.code !== 'string') {
    throw new Error('导入失败：`code` 必须是字符串')
  }
  return makeSnippet({
    enabled: typeof data.enabled === 'boolean' ? data.enabled : true,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : '',
    code: typeof data.code === 'string' ? data.code : '',
  })
}

export function parseRuleTransfer(raw: string): EditableInjectionRule {
  const envelope = parseEnvelope<RuleTransferEnvelope>(raw, 'rule')
  const data = envelope.data
  ensureAllowedFields(
    data,
    [
      'enabled',
      'name',
      'description',
      'mode',
      'match',
      'position',
      'wrapMarker',
      'matchRuleSource',
    ],
    '注入规则',
  )
  if (typeof data.enabled !== 'boolean') {
    throw new Error('导入失败：`enabled` 必须是布尔值；仅支持 true 或 false')
  }
  if (typeof data.name !== 'string') {
    throw new Error('导入失败：`name` 必须是字符串')
  }
  if (typeof data.description !== 'string') {
    throw new Error('导入失败：`description` 必须是字符串')
  }
  validateEnumField('mode', data.mode, ['HEAD', 'FOOTER', 'ID', 'SELECTOR'])
  if (typeof data.match !== 'string') {
    throw new Error('导入失败：`match` 必须是字符串')
  }
  validateEnumField('position', data.position, [
    'APPEND',
    'PREPEND',
    'BEFORE',
    'AFTER',
    'REPLACE',
    'REMOVE',
  ])
  if (typeof data.wrapMarker !== 'boolean') {
    throw new Error('导入失败：`wrapMarker` 必须是布尔值；仅支持 true 或 false')
  }
  const matchRuleState = parseImportedMatchRuleSource(data.matchRuleSource)
  return makeRule({
    enabled: data.enabled,
    name: data.name,
    description: data.description,
    mode: data.mode,
    match: data.match,
    matchRule: matchRuleState.matchRule,
    position: data.position,
    wrapMarker: data.wrapMarker,
    matchRuleSource: matchRuleState.matchRuleSource,
  })
}

function buildRuleTransferMatchRuleSource(rule: EditableInjectionRule): MatchRuleSource {
  const source = rule.matchRuleSource ?? makeRuleTreeSource(rule.matchRule)
  if (source.kind !== 'JSON_DRAFT') {
    return makeRuleTreeSource(rule.matchRule)
  }

  const draft = String(source.data ?? '')
  const parsed = parseMatchRuleDraft(draft)
  if (parsed.rule) {
    return makeRuleTreeSource(parsed.rule)
  }

  return makeJsonDraftSource(draft)
}

function parseImportedMatchRuleSource(source: unknown): {
  matchRule: EditableInjectionRule['matchRule']
  matchRuleSource: MatchRuleSource
} {
  if (!isPlainObject(source)) {
    throw new Error('导入失败：`matchRuleSource` 必须是对象')
  }

  ensureAllowedFields(source, ['kind', 'data'], '匹配规则来源')
  validateEnumField('matchRuleSource.kind', source.kind, ['RULE_TREE', 'JSON_DRAFT'])

  if (!Object.prototype.hasOwnProperty.call(source, 'data')) {
    throw new Error('导入失败：`matchRuleSource.data` 缺少必填字段')
  }

  if (source.kind === 'JSON_DRAFT') {
    if (typeof source.data !== 'string') {
      throw new Error('导入失败：`matchRuleSource.data` 必须是字符串')
    }
    const parsed = parseMatchRuleDraft(source.data)
    return {
      matchRule:
        parsed.rule ??
        normalizeMatchRule({ type: 'GROUP', negate: false, operator: 'AND', children: [] }),
      matchRuleSource: makeJsonDraftSource(source.data),
    }
  }

  if (!isPlainObject(source.data)) {
    throw new Error('导入失败：`matchRuleSource.data` 必须是对象')
  }

  const matchRuleResult = validateMatchRuleObject(source.data, 'data.matchRuleSource.data')
  if (matchRuleResult.error) {
    return {
      matchRule: normalizeMatchRule(source.data),
      matchRuleSource: makeJsonDraftSource(JSON.stringify(source.data, null, 2)),
    }
  }

  const matchRule = matchRuleResult.rule ?? normalizeMatchRule(source.data)
  return {
    matchRule,
    matchRuleSource: makeRuleTreeSource(matchRule),
  }
}

function parseEnvelope<
  T extends { format: string; version: number; resourceType: string; data: unknown },
>(raw: string, expectedType: TransferResourceType) {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new Error('导入失败：文件不是合法的 JSON')
  }
  if (!isPlainObject(parsed)) {
    throw new Error('导入失败：根节点必须是对象')
  }
  if (parsed.format !== 'halo-plugin-injector') {
    throw new Error('导入失败：不是 Injector 导出的 JSON')
  }
  if (parsed.version !== 1) {
    throw new Error('导入失败：暂不支持这个导出版本')
  }
  if (parsed.resourceType !== expectedType) {
    throw new Error(
      expectedType === 'snippet'
        ? '导入失败：当前只能导入代码块 JSON'
        : '导入失败：当前只能导入注入规则 JSON',
    )
  }
  if (!('data' in parsed) || !isPlainObject(parsed.data)) {
    throw new Error('导入失败：缺少 `data` 对象')
  }
  return parsed as T
}

function sanitizeFileName(name: string) {
  const trimmed = name.trim()
  if (!trimmed) {
    return 'injector-export'
  }
  return trimmed.replace(/[\\/:*?"<>|]+/g, '-')
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function validateEnumField(
  fieldName: string,
  value: unknown,
  allowedValues: readonly string[],
  options: { required?: boolean } = {},
) {
  const quotedAllowedValues = allowedValues.map((item) => `"${item}"`).join('、')
  const required = options.required ?? true

  if (value === undefined) {
    if (required) {
      throw new Error(`导入失败：\`${fieldName}\` 缺少必填字段；仅支持 ${quotedAllowedValues}`)
    }
    return
  }

  if (typeof value !== 'string') {
    throw new Error(`导入失败：\`${fieldName}\` 必须是字符串；仅支持 ${quotedAllowedValues}`)
  }

  if (!allowedValues.includes(value)) {
    throw new Error(`导入失败：\`${fieldName}\` 仅支持 ${quotedAllowedValues}`)
  }
}

function ensureAllowedFields(
  data: object,
  allowedFields: readonly string[],
  resourceLabel: string,
) {
  const invalidField = Object.keys(data).find((key) => !allowedFields.includes(key))
  if (!invalidField) {
    return
  }
  const quotedAllowedFields = allowedFields.map((field) => `"${field}"`).join('、')
  throw new Error(
    `导入失败：\`${invalidField}\` 不支持；${resourceLabel}仅支持 ${quotedAllowedFields}`,
  )
}
