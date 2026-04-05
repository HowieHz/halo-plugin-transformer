import { describe, expect, it } from 'vitest'
import { parseRuleTransfer, parseSnippetTransfer } from '../transfer'

describe('parseRuleTransfer', () => {
  // why: 导入时若只是把字段名写错，应丢弃错键并回退到该类型的默认匹配方式，避免整份 JSON 直接失效。
  it('drops unknown matchRule keys and falls back to default matcher', () => {
    const raw = JSON.stringify({
      format: 'halo-plugin-injector',
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
        matchRule: {
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
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRule.children?.[0]).toMatchObject({
      type: 'PATH',
      matcher: 'ANT',
      value: '/**',
    })
  })

  // why: 导入时若缺少可安全补全的字段，应自动补默认值，让用户能先导入再继续编辑。
  it('fills missing matchRule fields with defaults during import', () => {
    const raw = JSON.stringify({
      format: 'halo-plugin-injector',
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
        matchRule: {
          type: 'GROUP',
          negate: false,
          children: [
            {
              type: 'PATH',
            },
          ],
        },
      },
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRule).toMatchObject({
      type: 'GROUP',
      operator: 'AND',
      children: [
        {
          type: 'PATH',
          matcher: 'ANT',
          value: '/**',
        },
      ],
    })
  })

  // why: `type` 无法安全补默认值；导入时应保留原始 JSON 草稿并切到高级模式，让用户继续修正。
  it('falls back to json mode when imported matchRule is missing type', () => {
    const raw = JSON.stringify({
      format: 'halo-plugin-injector',
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
        matchRule: {
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
    })

    const rule = parseRuleTransfer(raw)

    expect(rule.matchRuleEditorMode).toBe('JSON')
    expect(rule.matchRuleDraft).toContain('"matcher": "ANT"')
    expect(rule.matchRule.type).toBe('GROUP')
    expect(rule.matchRule.children?.length).toBeGreaterThan(0)
  })

  // why: 导入枚举字段若传了非字符串值，应先提示“必须是字符串”，而不是笼统的“不合法”。
  it('reports enum type errors before invalid enum values during import', () => {
    const raw = JSON.stringify({
      format: 'halo-plugin-injector',
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
        matchRule: {
          type: 'GROUP',
          negate: false,
          operator: 'AND',
          children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
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
      format: 'halo-plugin-injector',
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
        matchRule: {
          type: 'GROUP',
          negate: false,
          operator: 'AND',
          children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
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
      format: 'halo-plugin-injector',
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
        matchRule: {
          type: 'GROUP',
          negate: false,
          operator: 'AND',
          children: [{ type: 'PATH', negate: false, matcher: 'ANT', value: '/**' }],
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
      format: 'halo-plugin-injector',
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
      format: 'halo-plugin-injector',
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
      format: 'halo-plugin-injector',
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
})
