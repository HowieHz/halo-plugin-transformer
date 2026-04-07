import {
  makeRuleEditorDraft,
  makeSnippetEditorDraft,
  RUNTIME_ORDER_DEFAULT,
  RUNTIME_ORDER_MAX,
  type CodeSnippetEditorDraft,
  type InjectionRuleEditorDraft,
  type MatchRuleSource,
} from '@/types'
import {
  makeJsonDraftSource,
  makeRuleTreeSource,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleObject,
} from './matchRule'
import {
  ensureAllowedFields,
  isPlainObject,
  parseTransferEnvelope,
  validateEnumField,
} from './transferEnvelope'
import type { RuleTransferEnvelope, SnippetTransferEnvelope } from './transferExportBuilder'

export function parseSnippetTransfer(raw: string): CodeSnippetEditorDraft {
  const envelope = parseTransferEnvelope<SnippetTransferEnvelope>(raw, 'snippet')
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
  return makeSnippetEditorDraft({
    enabled: typeof data.enabled === 'boolean' ? data.enabled : true,
    name: typeof data.name === 'string' ? data.name : '',
    description: typeof data.description === 'string' ? data.description : '',
    code: typeof data.code === 'string' ? data.code : '',
  })
}

export function parseRuleTransfer(raw: string): InjectionRuleEditorDraft {
  const envelope = parseTransferEnvelope<RuleTransferEnvelope>(raw, 'rule')
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
      'runtimeOrder',
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
  validateEnumField('mode', data.mode, ['HEAD', 'FOOTER', 'SELECTOR'])
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
  const runtimeOrder = data.runtimeOrder ?? RUNTIME_ORDER_DEFAULT
  if (typeof runtimeOrder !== 'number' || !Number.isInteger(runtimeOrder)) {
    throw new Error('导入失败：`runtimeOrder` 必须是整数')
  }
  if (runtimeOrder < 0) {
    throw new Error('导入失败：`runtimeOrder` 不能小于 0')
  }
  if (runtimeOrder > RUNTIME_ORDER_MAX) {
    throw new Error(`导入失败：\`runtimeOrder\` 不能大于 ${RUNTIME_ORDER_MAX}`)
  }
  const matchRuleState = parseImportedMatchRuleSource(data.matchRuleSource)
  return makeRuleEditorDraft({
    enabled: data.enabled,
    name: data.name,
    description: data.description,
    mode: data.mode,
    match: data.match,
    matchRule: matchRuleState.matchRule,
    position: data.position,
    wrapMarker: data.wrapMarker,
    runtimeOrder,
    matchRuleSource: matchRuleState.matchRuleSource,
  })
}

function parseImportedMatchRuleSource(source: unknown): {
  matchRule: InjectionRuleEditorDraft['matchRule']
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
