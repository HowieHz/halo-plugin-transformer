import { describe, expect, it } from 'vitest'
import { buildExplicitOrderMap, sortByOrderMap } from '../util'

describe('sortByOrderMap', () => {
  // why: 未显式排序的资源默认按 0 处理并排在最前面，这样新增项不需要先保存 order map 也能自然浮到顶部。
  it('puts implicit zero-order items before explicit orders', () => {
    const items = [
      { id: 'rule-b', name: 'Bravo' },
      { id: 'rule-a', name: 'Alpha' },
      { id: 'rule-c', name: 'Charlie' },
    ]

    const sorted = sortByOrderMap(items, { 'rule-c': 2, 'rule-b': 1 })

    expect(sorted.map((item) => item.id)).toEqual(['rule-a', 'rule-b', 'rule-c'])
  })

  // why: 当多个资源都还处于默认 0 时，列表顺序必须稳定且可预期；这里按显示名称字符序排，名称为空时再回退到 ID。
  it('sorts same-order items by display name', () => {
    const items = [
      { id: 'rule-b', name: 'Bravo' },
      { id: 'rule-a', name: 'Alpha' },
      { id: 'rule-c', name: '' },
    ]

    const sorted = sortByOrderMap(items, {})

    expect(sorted.map((item) => item.id)).toEqual(['rule-a', 'rule-b', 'rule-c'])
  })
})

describe('buildExplicitOrderMap', () => {
  // why: 用户一旦拖拽过顺序，就应把当前整组列表固化成 1..n；后续新增项仍可继续凭默认 0 插到前面。
  it('builds ascending explicit order values from current list order', () => {
    const orders = buildExplicitOrderMap([{ id: 'a' }, { id: 'b' }, { id: 'c' }])

    expect(orders).toEqual({
      a: 1,
      b: 2,
      c: 3,
    })
  })
})
