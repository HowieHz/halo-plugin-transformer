// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { makeRule } from '@/types'
import {
  getDomRulePerformanceWarning,
  hydrateRuleForEditor,
  parseMatchRuleDraft,
  resolveRuleMatchRule,
  validateSimpleMatchRuleTree,
} from '../matchRule'

describe('matchRule editor state', () => {
  // why: 刷新页面应回到已保存内容；不应再恢复本地未保存草稿或编辑模式。
  it('hydrates editor from saved rule only', () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a', generateName: 'InjectionRule-' },
    })

    const hydrated = hydrateRuleForEditor(savedRule)

    expect(hydrated.matchRuleSource).toMatchObject({
      kind: 'RULE_TREE',
    })
  })

  // why: `type` 写成布尔值时，应先明确提示“必须是字符串”，避免用户误以为只是枚举值写错。
  it('reports type field type errors before enum errors', () => {
    const result = parseMatchRuleDraft(
      '{ "type": false, "negate": false, "operator": "AND", "children": [] }',
    )

    expect(result.error?.path).toBe('$.type')
    expect(result.error?.message).toBe('必须是字符串；仅支持 "GROUP"、"PATH"、"TEMPLATE_ID"')
  })

  // why: 连 `type` 都没写时，应该先明确提示补 `type`，而不是过早按某个字段去猜。
  it('reports missing type before other required fields', () => {
    const result = parseMatchRuleDraft('{ "negate": false }')

    expect(result.error?.path).toBe('$.type')
    expect(result.error?.message).toBe(
      '缺少必填字段 "type"；该字段可选值为 "GROUP"、"PATH"、"TEMPLATE_ID"',
    )
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

  // why: 已经给出 `type: GROUP` 后，缺少的必填字段应按 GROUP 结构明确提示，而不是只说笼统的“缺少必填字段”。
  it('reports missing group children with group-specific guidance', () => {
    const result = parseMatchRuleDraft('{ "type": "GROUP", "negate": false, "operator": "AND" }')

    expect(result.error?.path).toBe('$.children')
    expect(result.error?.message).toBe('条件组缺少必填字段 "children"')
  })

  // why: `negate` 语义上也属于结构字段；显式写出 true / false，才能避免“省略就默认 false”的隐式歧义。
  it('requires explicit negate field', () => {
    const result = parseMatchRuleDraft('{ "type": "GROUP", "operator": "AND", "children": [] }')

    expect(result.error?.path).toBe('$.negate')
    expect(result.error?.message).toBe('缺少必填字段 "negate"；该字段可选值为 true、false')
  })

  // why: 枚举型必填字段缺失时，提示“该字段可选值”为语义更自然，也更方便用户直接补值。
  it('reports missing operator with field options wording', () => {
    const result = parseMatchRuleDraft('{ "type": "GROUP", "negate": false, "children": [] }')

    expect(result.error?.path).toBe('$.operator')
    expect(result.error?.message).toBe('条件组缺少必填字段 "operator"；该字段可选值为 "AND"、"OR"')
  })

  // why: 简单模式保存时必须只信当前规则树来源，不能被高级模式草稿心智反向污染。
  it('resolves rule tree source when current source is simple', () => {
    const rule = makeRule({
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
      },
      matchRuleSource: {
        kind: 'RULE_TREE',
        data: {
          type: 'GROUP',
          negate: false,
          operator: 'AND',
          children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
        },
      },
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

  // why: 简单模式应一次收集所有可定位错误，避免用户修完一个后才看到同层或子层的下一个错误。
  it('collects multiple simple mode errors at the same time', () => {
    const result = validateSimpleMatchRuleTree({
      type: 'GROUP',
      negate: false,
      operator: 'AND',
      children: [
        { type: 'PATH', negate: false, matcher: 'ANT', value: '' },
        {
          type: 'GROUP',
          negate: false,
          operator: 'AND',
          children: [],
        },
      ],
    })

    expect(result.errors).toEqual(
      expect.arrayContaining([
        { path: '$.children[0].value', message: '必须是非空字符串' },
        { path: '$.children[1].children', message: '不能有空组' },
      ]),
    )
  })
})
