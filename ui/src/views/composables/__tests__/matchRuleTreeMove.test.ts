import { describe, expect, it } from 'vitest'
import { makeMatchRuleGroup, makePathMatchRule, makeTemplateMatchRule } from '@/types'
import { canMoveMatchRuleNode, moveMatchRuleNode } from '../matchRuleTreeMove'

describe('matchRuleTreeMove', () => {
  // why: 同层拖拽是最基础的排序语义；前面节点移到后面时，目标索引必须在删除后正确回算。
  it('moves a sibling leaf after a later sibling', () => {
    const root = makeMatchRuleGroup({
      children: [
        makePathMatchRule({ value: '/a' }),
        makePathMatchRule({ value: '/b' }),
        makePathMatchRule({ value: '/c' }),
      ],
    })

    const moved = moveMatchRuleNode(root, [0], [2], 'after')

    expect(moved?.children?.map((child) => child.value)).toEqual(['/b', '/c', '/a'])
  })

  // why: 组拖拽最重要的语义是“可放入别的组”，这样才能真正重构树，而不是只能做平面排序。
  it('moves a leaf into another group', () => {
    const targetGroup = makeMatchRuleGroup({
      children: [makeTemplateMatchRule({ value: 'page' })],
    })
    const root = makeMatchRuleGroup({
      children: [makePathMatchRule({ value: '/a' }), targetGroup],
    })

    const moved = moveMatchRuleNode(root, [0], [1], 'inside')

    expect(moved?.children?.[0].type).toBe('GROUP')
    expect(moved?.children?.[0].children?.map((child) => child.value)).toEqual(['page', '/a'])
  })

  // why: 组节点绝不能拖进自己的子树里，否则树会形成循环语义；
  // 这里要显式拒绝，而不是让 UI 走到一半再出怪异结果。
  it('rejects moving a group into its own descendant', () => {
    const nestedGroup = makeMatchRuleGroup({
      children: [makePathMatchRule({ value: '/nested' })],
    })
    const root = makeMatchRuleGroup({
      children: [
        makeMatchRuleGroup({ children: [nestedGroup] }),
        makeTemplateMatchRule({ value: 'page' }),
      ],
    })

    expect(canMoveMatchRuleNode(root, [0], [0, 0], 'inside')).toBe(false)
    expect(moveMatchRuleNode(root, [0], [0, 0], 'inside')).toBeNull()
  })

  // why: 组拖拽到另一个组前后也必须合法；这里只锁一条跨层 before，避免后续回归成“只能 inside”。
  it('moves a nested group before another top-level node', () => {
    const nestedGroup = makeMatchRuleGroup({
      children: [makePathMatchRule({ value: '/nested' })],
    })
    const root = makeMatchRuleGroup({
      children: [
        makeMatchRuleGroup({ children: [nestedGroup] }),
        makeTemplateMatchRule({ value: 'page' }),
      ],
    })

    const moved = moveMatchRuleNode(root, [0, 0], [1], 'before')

    expect(moved?.children?.[0].type).toBe('GROUP')
    expect(moved?.children?.[0].children).toEqual([])
    expect(moved?.children?.[1].type).toBe('GROUP')
    expect(moved?.children?.[1].children?.[0].value).toBe('/nested')
  })
})
