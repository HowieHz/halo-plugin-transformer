import {
  type CodeSnippetEditorDraft,
  type InjectionRuleEditorDraft,
  type MatchRuleSource,
} from '@/types'
import { makeJsonDraftSource, makeRuleTreeSource, parseMatchRuleDraft } from './matchRule'
import { TRANSFER_SCHEMA_URL, type TransferEnvelope } from './transferEnvelope'

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
  mode: InjectionRuleEditorDraft['mode']
  match: string
  position: InjectionRuleEditorDraft['position']
  wrapMarker: boolean
  matchRuleSource: MatchRuleSource
}

export type SnippetTransferEnvelope = TransferEnvelope<'snippet', SnippetTransferData>
export type RuleTransferEnvelope = TransferEnvelope<'rule', RuleTransferData>

/**
 * why: 导入导出只面向用户可编辑内容；像 id、排序、metadata、关联关系这类系统字段
 * 不应跟着资源模板流转，避免“导入即复制脏状态”。
 */
export function buildSnippetTransfer(snippet: CodeSnippetEditorDraft): SnippetTransferEnvelope {
  return {
    $schema: TRANSFER_SCHEMA_URL,
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
export function buildRuleTransfer(rule: InjectionRuleEditorDraft): RuleTransferEnvelope {
  const matchRuleSource = buildRuleTransferMatchRuleSource(rule)
  return {
    $schema: TRANSFER_SCHEMA_URL,
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
