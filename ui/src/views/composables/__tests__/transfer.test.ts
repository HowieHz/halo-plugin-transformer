import { describe, expect, it } from 'vitest'
import { parseRuleTransfer } from '../transfer'

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
})
