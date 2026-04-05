import {
  makeRule,
  makeSnippet,
  type CodeSnippet,
  type EditableInjectionRule,
  type MatchRuleEditorMode,
} from '@/types'
import { cloneMatchRule, normalizeMatchRule } from './matchRule'

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
  matchRule: EditableInjectionRule['matchRule']
  position: EditableInjectionRule['position']
  wrapMarker: boolean
  matchRuleDraft?: string
  matchRuleEditorMode?: MatchRuleEditorMode
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
      matchRule: cloneMatchRule(rule.matchRule),
      position: rule.position,
      wrapMarker: rule.wrapMarker,
      matchRuleDraft: rule.matchRuleDraft,
      matchRuleEditorMode: rule.matchRuleEditorMode,
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

export async function downloadTransfer(
  payload: SnippetTransferEnvelope | RuleTransferEnvelope,
  name: string,
) {
  const fileName = `${sanitizeFileName(name)}.json`
  const content = JSON.stringify(payload, null, 2)
  const saveFilePicker = (window as WindowWithSaveFilePicker).showSaveFilePicker

  if (typeof saveFilePicker !== 'function') {
    throw new Error('当前浏览器不支持“另存为”导出，请改用支持 File System Access API 的浏览器')
  }
  const handle = await saveFilePicker({
    suggestedName: fileName,
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
  await writable.write(content)
  await writable.close()
}

export function parseSnippetTransfer(raw: string): CodeSnippet {
  const envelope = parseEnvelope<SnippetTransferEnvelope>(raw, 'snippet')
  const data = envelope.data
  if (typeof data.enabled !== 'boolean') {
    throw new Error('导入失败：`enabled` 必须是布尔值')
  }
  if (typeof data.name !== 'string') {
    throw new Error('导入失败：`name` 必须是字符串')
  }
  if (typeof data.description !== 'string') {
    throw new Error('导入失败：`description` 必须是字符串')
  }
  if (typeof data.code !== 'string') {
    throw new Error('导入失败：`code` 必须是字符串')
  }
  return makeSnippet({
    enabled: data.enabled,
    name: data.name,
    description: data.description,
    code: data.code,
  })
}

export function parseRuleTransfer(raw: string): EditableInjectionRule {
  const envelope = parseEnvelope<RuleTransferEnvelope>(raw, 'rule')
  const data = envelope.data
  if (typeof data.enabled !== 'boolean') {
    throw new Error('导入失败：`enabled` 必须是布尔值')
  }
  if (typeof data.name !== 'string') {
    throw new Error('导入失败：`name` 必须是字符串')
  }
  if (typeof data.description !== 'string') {
    throw new Error('导入失败：`description` 必须是字符串')
  }
  if (!['HEAD', 'FOOTER', 'ID', 'SELECTOR'].includes(data.mode)) {
    throw new Error('导入失败：`mode` 不合法')
  }
  if (typeof data.match !== 'string') {
    throw new Error('导入失败：`match` 必须是字符串')
  }
  if (!isPlainObject(data.matchRule)) {
    throw new Error('导入失败：`matchRule` 必须是对象')
  }
  if (!['APPEND', 'PREPEND', 'BEFORE', 'AFTER', 'REPLACE', 'REMOVE'].includes(data.position)) {
    throw new Error('导入失败：`position` 不合法')
  }
  if (typeof data.wrapMarker !== 'boolean') {
    throw new Error('导入失败：`wrapMarker` 必须是布尔值')
  }
  if (data.matchRuleDraft !== undefined && typeof data.matchRuleDraft !== 'string') {
    throw new Error('导入失败：`matchRuleDraft` 必须是字符串')
  }
  if (
    data.matchRuleEditorMode !== undefined &&
    !['SIMPLE', 'JSON'].includes(data.matchRuleEditorMode)
  ) {
    throw new Error('导入失败：`matchRuleEditorMode` 不合法')
  }
  return makeRule({
    enabled: data.enabled,
    name: data.name,
    description: data.description,
    mode: data.mode,
    match: data.match,
    matchRule: normalizeMatchRule(data.matchRule),
    position: data.position,
    wrapMarker: data.wrapMarker,
    matchRuleDraft: data.matchRuleDraft,
    matchRuleEditorMode: data.matchRuleEditorMode,
  })
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
