import { describe, expect, it } from 'vitest'
import { makeRule } from '@/types'
import {
  buildRuleTransfer,
  parseRuleTransfer,
  parseSnippetTransfer,
  TRANSFER_SCHEMA_URL,
} from '../transfer'

describe('parseRuleTransfer', () => {
  // why: 导入时若只是把字段名写错，应丢弃错键并回退到该类型的默认匹配方式，避免整份 JSON 直接失效。
  it('drops unknown matchRule keys and falls back to default matcher', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: [
              {
                type: 'PATH',
                negate: false,
                ' m a tc her': 'REGEX',
                value: '/**',
              },
            ],
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRule.children?.[0]).toMatchObject({
      type: 'PATH',
      matcher: 'ANT',
      value: '/**',
    })
  })

  // why: 导入时若缺少可安全补全的字段，应自动补默认值；但 GROUP 缺少 children 时应补成空组，而不是偷偷塞一个默认子规则。
  it('fills missing matchRule fields with defaults during import', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRule).toMatchObject({
      type: 'GROUP',
      negate: false,
      operator: 'AND',
      children: [],
    })
  })

  // why: 叶子规则缺少 matcher / value 时，仍可按已知 type 安全补默认值，方便用户先导入再继续编辑。
  it('fills missing leaf matchRule fields during import', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: [
              {
                type: 'PATH',
              },
            ],
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRule.children?.[0]).toMatchObject({
      type: 'PATH',
      negate: false,
      matcher: 'ANT',
      value: '/**',
    })
  })

  // why: `type` 无法安全补默认值；导入时应保留原始 JSON 草稿并切到高级模式，让用户继续修正。
  it('falls back to json draft when imported rule tree is missing type', () => {
    const raw = JSON.stringify({
      $schema: TRANSFER_SCHEMA_URL,
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            negate: false,
            operator: 'AND',
            children: [
              {
                matcher: 'ANT',
                value: '/**',
              },
            ],
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRuleSource).toMatchObject({ kind: 'JSON_DRAFT' })
    expect(String(rule.matchRuleSource?.data)).toContain('"matcher": "ANT"')
    expect(rule.matchRule.type).toBe('GROUP')
    expect(rule.matchRule.children?.length).toBeGreaterThan(0)
  })

  // why: 只要 `matchRule` 根节点仍是对象，哪怕 children 类型写错，也应保留原始 JSON 并转高级模式继续修。
  it('falls back to json draft when imported children is not an array', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: {},
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRuleSource).toMatchObject({ kind: 'JSON_DRAFT' })
    expect(String(rule.matchRuleSource?.data)).toContain('"children": {}')
  })

  // why: 字段类型写错仍属于“对象树内部可修问题”，导入时应进入高级模式，而不是直接拦死。
  it('falls back to json draft when imported negate has wrong type', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: 'false',
            operator: 'AND',
            children: [],
          },
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRuleSource).toMatchObject({ kind: 'JSON_DRAFT' })
    expect(String(rule.matchRuleSource?.data)).toContain('"negate": "false"')
  })

  // why: 导入枚举字段若传了非字符串值，应先提示“必须是字符串”，而不是笼统的“不合法”。
  it('reports enum type errors before invalid enum values during import', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: false,
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
          },
        },
      },
    })

    expect(() => parseRuleTransfer(raw)).toThrow(
      '导入失败：`mode` 必须是字符串；仅支持 "HEAD"、"FOOTER"、"ID"、"SELECTOR"',
    )
  })

  // why: 导入枚举字段若本身就是字符串，只需提示允许值即可，不必重复强调类型。
  it('reports quoted allowed enum values for invalid import enums', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: true,
        name: 'demo',
        description: '',
        mode: 'UNKNOWN',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
          },
        },
      },
    })

    expect(() => parseRuleTransfer(raw)).toThrow(
      '导入失败：`mode` 仅支持 "HEAD"、"FOOTER"、"ID"、"SELECTOR"',
    )
  })

  // why: 导入里的布尔字段若不是 boolean，也应明确提示 true / false，避免只看到“类型不对”却不知道期望值。
  it('reports allowed boolean values for import boolean fields', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'rule',
      data: {
        enabled: 'yes',
        name: 'demo',
        description: '',
        mode: 'FOOTER',
        match: '',
        position: 'APPEND',
        wrapMarker: true,
        matchRuleSource: {
          kind: 'RULE_TREE',
          data: {
            type: 'GROUP',
            negate: false,
            operator: 'AND',
            children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
          },
        },
      },
    })

    expect(() => parseRuleTransfer(raw)).toThrow(
      '导入失败：`enabled` 必须是布尔值；仅支持 true 或 false',
    )
  })
})

describe('parseSnippetTransfer', () => {
  // why: 代码块导入若缺少可补默认值的字段，应直接补默认值，让用户先导入再继续编辑。
  it('fills missing snippet fields with defaults during import', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'snippet',
      data: {},
    })

    const snippet = parseSnippetTransfer(raw)

    expect(snippet).toMatchObject({
      enabled: true,
      name: '',
      description: '',
      code: '',
    })
  })

  // why: 代码块导入也不应静默吞掉未知字段，否则用户会以为自己导入成功了完整模板。
  it('rejects unknown snippet fields during import', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'snippet',
      data: {
        enabled: true,
        code: '<div></div>',
        extraField: 'ignored?',
      },
    })

    expect(() => parseSnippetTransfer(raw)).toThrow(
      '导入失败：`extraField` 不支持；代码块仅支持 "enabled"、"name"、"description"、"code"',
    )
  })

  // why: 代码块布尔字段也要和注入规则导入一样，给出 true / false 提示。
  it('reports allowed boolean values for snippet import booleans', () => {
    const raw = JSON.stringify({
      version: 1,
      resourceType: 'snippet',
      data: {
        enabled: 'yes',
      },
    })

    expect(() => parseSnippetTransfer(raw)).toThrow(
      '导入失败：`enabled` 必须是布尔值；仅支持 true 或 false',
    )
  })

  // why: `$schema` 只用于编辑器提示；若类型写错，也应在导入入口直接指出，而不是等后续结构校验时才报模糊错误。
  it('reports invalid schema field type for snippet imports', () => {
    const raw = JSON.stringify({
      $schema: 123,
      version: 1,
      resourceType: 'snippet',
      data: {},
    })

    expect(() => parseSnippetTransfer(raw)).toThrow('导入失败：`$schema` 必须是字符串')
  })
})

describe('buildRuleTransfer', () => {
  // why: 导出后的包裹层应只保留 version/resourceType/data，并自动附带 schema 提示，不再继续输出旧的 format 字段。
  it('exports schema url without legacy format field', () => {
    const payload = buildRuleTransfer(makeRule())

    expect(payload).toMatchObject({
      $schema: TRANSFER_SCHEMA_URL,
      version: 1,
      resourceType: 'rule',
    })
    expect('format' in payload).toBe(false)
  })

  // why: 高级模式里的 JSON 草稿若已经能稳定解析成规则树，导出时应优先收敛成 RULE_TREE，避免把无意义草稿继续传下去。
  it('exports valid json drafts as rule trees', () => {
    const rule = makeRule({
      matchRuleSource: {
        kind: 'JSON_DRAFT',
        data: `{
  "type": "GROUP",
  "negate": false,
  "operator": "AND",
  "children": [
    {
      "type": "PATH",
      "negate": false,
      "matcher": "ANT",
      "value": "/**"
    }
  ]
}`,
      },
    })

    const payload = buildRuleTransfer(rule)

    expect(payload.data.matchRuleSource).toMatchObject({
      kind: 'RULE_TREE',
    })
  })

  // why: 高级模式草稿仍有错误时，导出必须原样保留 JSON_DRAFT，避免把错误悄悄改写成另一份规则树。
  it('keeps invalid json drafts as json drafts during export', () => {
    const rule = makeRule({
      matchRuleSource: {
        kind: 'JSON_DRAFT',
        data: '{ "type": "GROUP", "negate": false, "operator": "AND", "children": [',
      },
    })

    const payload = buildRuleTransfer(rule)

    expect(payload.data.matchRuleSource).toEqual({
      kind: 'JSON_DRAFT',
      data: '{ "type": "GROUP", "negate": false, "operator": "AND", "children": [',
    })
  })
})
