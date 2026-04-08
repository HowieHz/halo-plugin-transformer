import { Toast } from '@halo-dev/components'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { ItemList } from '@/types'
import { useResourceOrderState } from '../useResourceOrderState'

vi.mock('@halo-dev/components', () => ({
  Toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

function listOf<T>(items: T[]): ItemList<T> {
  return {
    first: true,
    hasNext: false,
    hasPrevious: false,
    last: true,
    page: 0,
    size: items.length,
    totalPages: 1,
    items,
    total: items.length,
  }
}

describe('useResourceOrderState', () => {
  // why: 创建/删除后会走一次“补写显式顺序”；如果这次保存失败，
  // 左侧列表必须回到服务端权威顺序，而不是停留在本地乐观态直到整页刷新。
  it('reloads authoritative orders when saveOrderMap fails', async () => {
    const itemsResp = ref(
      listOf([
        { id: 'rule-a', name: 'Rule A' },
        { id: 'rule-b', name: 'Rule B' },
      ]),
    )
    const api = {
      getOrder: vi.fn().mockResolvedValue({
        data: {
          orders: { 'rule-a': 10, 'rule-b': 20 },
          version: 3,
        },
      }),
      updateOrder: vi.fn().mockRejectedValue(new Error('boom')),
    }

    const state = useResourceOrderState({
      itemsResp,
      api,
      resourceLabel: '转换规则',
    })
    state.applyOrderSnapshot({
      orders: { 'rule-a': 1, 'rule-b': 2 },
      version: 2,
    })

    const result = await state.saveOrderMap([
      { id: 'rule-b', name: 'Rule B' },
      { id: 'rule-a', name: 'Rule A' },
    ])

    expect(result).toBe('转换规则顺序保存失败')
    expect(api.getOrder).toHaveBeenCalledTimes(1)
    expect(state.orders.value).toEqual({ 'rule-a': 10, 'rule-b': 20 })
    expect(state.orderVersion.value).toBe(3)
  })

  // why: 拖拽重排和创建/删除后的补写，本质上是同一条排序持久化语义；
  // 拖拽失败时也必须回到服务端权威顺序，不能和 saveOrderMap 漂移成两套恢复逻辑。
  it('reloads authoritative orders when reorder persistence fails', async () => {
    const itemsResp = ref(
      listOf([
        { id: 'rule-a', name: 'Rule A' },
        { id: 'rule-b', name: 'Rule B' },
      ]),
    )
    const api = {
      getOrder: vi.fn().mockResolvedValue({
        data: {
          orders: { 'rule-a': 10, 'rule-b': 20 },
          version: 3,
        },
      }),
      updateOrder: vi.fn().mockRejectedValue(new Error('boom')),
    }

    const state = useResourceOrderState({
      itemsResp,
      api,
      resourceLabel: '转换规则',
    })
    state.applyOrderSnapshot({
      orders: { 'rule-a': 1, 'rule-b': 2 },
      version: 2,
    })

    await state.reorder({
      sourceId: 'rule-b',
      targetId: 'rule-a',
      placement: 'before',
    })

    expect(api.getOrder).toHaveBeenCalledTimes(1)
    expect(state.orders.value).toEqual({ 'rule-a': 10, 'rule-b': 20 })
    expect(state.orderVersion.value).toBe(3)
    expect(Toast.error).toHaveBeenCalledWith('更新顺序失败')
  })
})
