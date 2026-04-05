// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { makeRule } from '@/types'
import {
  clearPersistedMatchRuleDraft,
  hydrateRuleForEditor,
  parseMatchRuleDraft,
  persistMatchRuleEditorState,
  resolveRuleMatchRule,
} from '../matchRule'

describe('matchRule editor state', () => {
  const storage = new Map<string, string>()

  beforeEach(() => {
    storage.clear()
    vi.stubGlobal('window', {
      localStorage: {
        getItem(key: string) {
          return storage.get(key) ?? null
        },
        setItem(key: string, value: string) {
          storage.set(key, value)
        },
        removeItem(key: string) {
          storage.delete(key)
        },
        clear() {
          storage.clear()
        },
      },
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // why: 用户点“放弃”后应回到已保存内容，不能继续被本地旧 JSON 草稿覆盖。
  it('clears persisted draft while keeping editor mode', () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a', generateName: 'InjectionRule-' },
    })

    persistMatchRuleEditorState({
      id: savedRule.id,
      matchRuleDraft: '{ invalid json',
      matchRuleEditorMode: 'JSON',
    })

    clearPersistedMatchRuleDraft(savedRule.id, 'JSON')

    const hydrated = hydrateRuleForEditor(savedRule)

    expect(hydrated.matchRuleEditorMode).toBe('JSON')
    expect(hydrated.matchRuleDraft).toContain('"type": "GROUP"')
    expect(hydrated.matchRuleDraft).not.toBe('{ invalid json')
  })

  // why: `type` 写成布尔值时，应先明确提示“必须是字符串”，避免用户误以为只是枚举值写错。
  it('reports type field type errors before enum errors', () => {
    const result = parseMatchRuleDraft(
      '{ "type": false, "negate": false, "operator": "AND", "children": [] }',
    )

    expect(result.error?.path).toBe('$.type')
    expect(result.error?.message).toBe('必须是字符串；仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  })

  // why: `type` 是字符串但值不合法时，提示里也应带上带引号的允许值，保持和其它枚举字段一致。
  it('reports quoted allowed type values for invalid enum values', () => {
    const result = parseMatchRuleDraft(
      '{ "type": "foo", "negate": false, "operator": "AND", "children": [] }',
    )

    expect(result.error?.path).toBe('$.type')
    expect(result.error?.message).toBe('仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  })

  // why: 布尔字段若给成其它类型，也应补充 true / false 提示，和枚举字段的“允许值”风格保持一致。
  it('reports allowed boolean values for negate type errors', () => {
    const result = parseMatchRuleDraft(
      '{ "type": "GROUP", "negate": "yes", "operator": "AND", "children": [] }',
    )

    expect(result.error?.path).toBe('$.negate')
    expect(result.error?.message).toBe('必须是布尔值；仅支持 true 或 false')
  })

  // why: 简单模式保存时必须只信当前可视化规则树，不能再被旧的 JSON 草稿反向污染。
  it('ignores stale json draft when current editor mode is simple', () => {
    const rule = makeRule({
      matchRuleEditorMode: 'SIMPLE',
      matchRuleDraft: `{
  "type": "GROUP",
  "negate": false,
  "operator": "AND",
  "children": [
    {
      "type": "PATH",
      "negate": false,
      "matcher": "REGEX",
      "value": "/**"
    }
  ]
}`,
    })

    const result = resolveRuleMatchRule(rule)

    expect(result.error).toBeNull()
    expect(result.rule?.children?.[0]).toMatchObject({
      type: 'PATH',
      matcher: 'ANT',
      value: '/**',
    })
  })
})
