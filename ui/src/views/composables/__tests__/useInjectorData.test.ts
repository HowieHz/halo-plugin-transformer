import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { makeRule, makeSnippet, type ItemList } from '@/types'

const { toast, dialog, snippetApi, ruleApi } = vi.hoisted(() => ({
  toast: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
  dialog: {
    warning: vi.fn(),
  },
  snippetApi: {
    list: vi.fn(),
    add: vi.fn(),
    update: vi.fn(),
    getOrder: vi.fn(),
    updateOrder: vi.fn(),
    delete: vi.fn(),
  },
  ruleApi: {
    list: vi.fn(),
    add: vi.fn(),
    update: vi.fn(),
    getOrder: vi.fn(),
    updateOrder: vi.fn(),
    delete: vi.fn(),
  },
}))

vi.mock('@halo-dev/components', () => ({
  Dialog: dialog,
  Toast: toast,
}))

vi.mock('@/apis', () => ({
  snippetApi,
  ruleApi,
}))

import { useInjectorData } from '../useInjectorData'

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

describe('useInjectorData', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // why: 第一个 P0 收口后，前端保存规则时不应再去二次改写代码块；双向关联应交给后端单接口完成。
  it('saves rules without issuing secondary snippet update requests', async () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a' },
      snippetIds: ['snippet-a'],
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [
          {
            type: 'PATH',
            negate: false,
            matcher: 'ANT',
            value: '/**',
          },
        ],
      },
    })
    const savedSnippet = makeSnippet({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      ruleIds: ['rule-a'],
      code: '<div>ok</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: {} })
    ruleApi.list.mockResolvedValue({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: {} })
    ruleApi.update.mockResolvedValue({ data: savedRule })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    await store.saveRule()

    expect(ruleApi.update).toHaveBeenCalledTimes(1)
    expect(snippetApi.update).not.toHaveBeenCalled()
  })
})
