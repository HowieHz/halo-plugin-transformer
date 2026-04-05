// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { makeRule } from '@/types'
import {
  getDomRulePerformanceWarning,
  hydrateRuleForEditor,
  parseMatchRuleDraft,
  resolveRuleMatchRule,
} from '../matchRule'

describe('matchRule editor state', () => {
  // why: 刷新页面应回到已保存内容；不应再恢复本地未保存草稿或编辑模式。
  it('hydrates editor from saved rule only', () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a', generateName: 'InjectionRule-' },
    })

    const hydrated = hydrateRuleForEditor(savedRule)

    expect(hydrated.matchRuleEditorMode).toBe('SIMPLE')
    expect(hydrated.matchRuleDraft).toContain('"type": "GROUP"')
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
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
      },
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

  // why: 新建注入规则时，首个匹配条件应留空等待用户填写，而不是偷偷预填默认路径。
  it('starts new rules with an empty first match value', () => {
    const rule = makeRule()

    expect(rule.matchRule.children?.[0]).toMatchObject({
      type: 'PATH',
      matcher: 'ANT',
      value: '',
    })
  })

  // why: 空组或空值这类“当前还不合法”的编辑中间态，应优先显示校验错误，不该再叠加性能提示干扰判断。
  it('hides dom performance warning when match rule is currently invalid', () => {
    const rule = makeRule({
      mode: 'ID',
      match: 'main-content',
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [],
      },
    })

    expect(getDomRulePerformanceWarning(rule)).toBeNull()
  })
})
