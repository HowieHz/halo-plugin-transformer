import {
  type CodeSnippetEditorDraft,
  type InjectionRuleEditorDraft,
  type MatchRuleSource,
} from '@/types'
import { makeJsonDraftSource, makeRuleTreeSource, parseMatchRuleDraft } from './matchRule'
import { TRANSFER_SCHEMA_URL, type TransferEnvelope } from './transferEnvelope'

export interface SnippetTransferData {
  enabled: boolean
  name: string
  description: string
  code: string
}

export interface RuleTransferData {
  enabled: boolean
  name: string
  description: string
  mode: InjectionRuleEditorDraft['mode']
  match: string
  position: InjectionRuleEditorDraft['position']
  wrapMarker: boolean
  runtimeOrder: number
  matchRuleSource: MatchRuleSource
}

export type SnippetTransferEnvelope = TransferEnvelope<'snippet', SnippetTransferData>
export type RuleTransferEnvelope = TransferEnvelope<'rule', RuleTransferData>
export type SnippetBatchTransferEnvelope = TransferEnvelope<
  'snippet-batch',
  { items: SnippetTransferData[] }
>
export type RuleBatchTransferEnvelope = TransferEnvelope<
  'rule-batch',
  { items: RuleTransferData[] }
>

/**
 * why: 导入导出只面向用户可编辑内容；像 id、排序、metadata、关联关系这类系统字段
 * 不应跟着资源模板流转，避免“导入即复制脏状态”。
 */
export function buildSnippetTransfer(snippet: CodeSnippetEditorDraft): SnippetTransferEnvelope {
  return {
    $schema: TRANSFER_SCHEMA_URL,
    version: 1,
    resourceType: 'snippet',
    data: buildSnippetTransferData(snippet),
  }
}

/**
 * why: 规则导出保留当前编辑器可见的业务内容，但不会把编辑器内部状态原样透传；
 * 对可成功解析的 `JSON_DRAFT`，这里会收敛成规范的 `RULE_TREE`，只有仍然无效的 JSON 草稿才继续按 `JSON_DRAFT` 导出。
 */
export function buildRuleTransfer(rule: InjectionRuleEditorDraft): RuleTransferEnvelope {
  return {
    $schema: TRANSFER_SCHEMA_URL,
    version: 1,
    resourceType: 'rule',
    data: buildRuleTransferData(rule),
  }
}

/**
 * why: 批量导出不应重新发明另一套 item 结构；
 * 这里直接复用单资源 transfer data，确保单个导出与批量导出共享同一条契约。
 */
export function buildSnippetBatchTransfer(
  snippets: CodeSnippetEditorDraft[],
): SnippetBatchTransferEnvelope {
  return {
    $schema: TRANSFER_SCHEMA_URL,
    version: 1,
    resourceType: 'snippet-batch',
    data: {
      items: snippets.map(buildSnippetTransferData),
    },
  }
}

/**
 * why: 规则批量导出要和单条规则导出保持完全同构；
 * 这样导入校验、schema 与未来迁移都只维护一套规则 item 语义。
 */
export function buildRuleBatchTransfer(
  rules: InjectionRuleEditorDraft[],
): RuleBatchTransferEnvelope {
  return {
    $schema: TRANSFER_SCHEMA_URL,
    version: 1,
    resourceType: 'rule-batch',
    data: {
      items: rules.map(buildRuleTransferData),
    },
  }
}

export interface TransferFileDraft {
  fileName: string
  content: string
}

export function createTransferFileDraft(
  payload:
    | SnippetTransferEnvelope
    | RuleTransferEnvelope
    | SnippetBatchTransferEnvelope
    | RuleBatchTransferEnvelope,
  name: string,
): TransferFileDraft {
  const fileName = `${sanitizeFileName(name)}.json`
  const content = JSON.stringify(payload, null, 2)
  return {
    fileName,
    content,
  }
}

function buildSnippetTransferData(snippet: CodeSnippetEditorDraft): SnippetTransferData {
  return {
    enabled: snippet.enabled,
    name: snippet.name,
    description: snippet.description,
    code: snippet.code,
  }
}

function buildRuleTransferData(rule: InjectionRuleEditorDraft): RuleTransferData {
  const matchRuleSource = buildRuleTransferMatchRuleSource(rule)
  return {
    enabled: rule.enabled,
    name: rule.name,
    description: rule.description,
    mode: rule.mode,
    match: rule.match,
    position: rule.position,
    wrapMarker: rule.wrapMarker,
    runtimeOrder: rule.runtimeOrder,
    matchRuleSource,
  }
}

function buildRuleTransferMatchRuleSource(rule: InjectionRuleEditorDraft): MatchRuleSource {
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

function sanitizeFileName(name: string) {
  const trimmed = name.trim()
  if (!trimmed) {
    return 'injector-export'
  }
  return trimmed.replace(/[\\/:*?"<>|]+/g, '-')
}
