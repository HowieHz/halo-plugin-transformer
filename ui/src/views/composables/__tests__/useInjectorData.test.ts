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

  // why: 用户只是想停用规则时，不应因为当前右侧存在未保存的坏草稿而被拦住；停用应基于已保存规则快照完成。
  it('disables invalid draft rules by using the persisted rule snapshot', async () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a' },
      enabled: true,
      mode: 'FOOTER',
      match: '',
      snippetIds: [],
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
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource

    snippetApi.list.mockResolvedValue({ data: listOf([]) })
    snippetApi.getOrder.mockResolvedValue({ data: {} })
    ruleApi.list.mockResolvedValue({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: {} })
    ruleApi.update.mockResolvedValue({ data: { ...savedRule, enabled: false } })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    store.editRule.value = {
      ...store.editRule.value!,
      mode: 'ID',
      match: '',
    }

    await store.toggleRuleEnabled()

    expect(ruleApi.update).toHaveBeenCalledTimes(1)
    const [, payload] = ruleApi.update.mock.calls[0]
    expect(payload.enabled).toBe(false)
    expect(payload.mode).toBe('FOOTER')
    expect(toast.error).not.toHaveBeenCalled()
  })

  // why: 停用代码块同理也不应被空代码草稿拦住；应直接基于已保存代码块完成停用。
  it('disables invalid draft snippets by using the persisted snippet snapshot', async () => {
    const savedSnippet = makeSnippet({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      enabled: true,
      code: '<div>ok</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: {} })
    snippetApi.update.mockResolvedValue({ data: { ...savedSnippet, enabled: false } })
    ruleApi.list.mockResolvedValue({ data: listOf([]) })
    ruleApi.getOrder.mockResolvedValue({ data: {} })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedSnippetId.value = 'snippet-a'
    await nextTick()

    store.editSnippet.value = {
      ...store.editSnippet.value!,
      code: '',
    }

    await store.toggleSnippetEnabled()

    expect(snippetApi.update).toHaveBeenCalledTimes(1)
    const [, payload] = snippetApi.update.mock.calls[0]
    expect(payload.enabled).toBe(false)
    expect(payload.code).toBe('<div>ok</div>')
    expect(toast.error).not.toHaveBeenCalled()
  })

  // why: 前端保存规则时不应再去二次改写代码块；双向关联应交给后端单接口完成。
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
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource
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

  // why: 左侧排序保存失败时，只应回拉排序映射；右侧未保存的规则草稿不能被整页重载冲掉。
  it('keeps unsaved rule editor state when rule reorder persistence fails', async () => {
    const ruleA = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a' },
      name: 'Rule A',
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [
          {
            type: 'PATH',
            negate: false,
            matcher: 'ANT',
            value: '/a/**',
          },
        ],
      },
    })
    const ruleB = makeRule({
      id: 'rule-b',
      metadata: { name: 'rule-b' },
      name: 'Rule B',
      matchRule: {
        type: 'GROUP',
        negate: false,
        operator: 'AND',
        children: [
          {
            type: 'PATH',
            negate: false,
            matcher: 'ANT',
            value: '/b/**',
          },
        ],
      },
    })
    delete (ruleA as { matchRuleSource?: unknown }).matchRuleSource
    delete (ruleB as { matchRuleSource?: unknown }).matchRuleSource

    snippetApi.list.mockResolvedValue({ data: listOf([]) })
    snippetApi.getOrder.mockResolvedValue({ data: {} })
    ruleApi.list.mockResolvedValue({ data: listOf([ruleA, ruleB]) })
    ruleApi.getOrder
      .mockResolvedValueOnce({ data: { 'rule-a': 10, 'rule-b': 20 } })
      .mockResolvedValueOnce({ data: { 'rule-a': 10, 'rule-b': 20 } })
    ruleApi.updateOrder.mockRejectedValue(new Error('boom'))

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    store.editRule.value = {
      ...store.editRule.value!,
      name: 'draft rule name',
    }
    store.editDirty.value = true

    await store.reorderRule({
      sourceId: 'rule-a',
      targetId: 'rule-b',
      placement: 'after',
    })

    expect(store.editRule.value?.name).toBe('draft rule name')
    expect(store.editDirty.value).toBe(true)
    expect(ruleApi.list).toHaveBeenCalledTimes(1)
    expect(snippetApi.list).toHaveBeenCalledTimes(1)
    expect(ruleApi.getOrder).toHaveBeenCalledTimes(2)
  })

  // why: 代码块排序失败同理只能恢复左侧顺序，不能把右侧未保存代码内容覆盖掉。
  it('keeps unsaved snippet editor state when snippet reorder persistence fails', async () => {
    const snippetA = makeSnippet({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      name: 'Snippet A',
      code: '<div>a</div>',
    })
    const snippetB = makeSnippet({
      id: 'snippet-b',
      metadata: { name: 'snippet-b' },
      name: 'Snippet B',
      code: '<div>b</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([snippetA, snippetB]) })
    snippetApi.getOrder
      .mockResolvedValueOnce({ data: { 'snippet-a': 10, 'snippet-b': 20 } })
      .mockResolvedValueOnce({ data: { 'snippet-a': 10, 'snippet-b': 20 } })
    snippetApi.updateOrder.mockRejectedValue(new Error('boom'))
    ruleApi.list.mockResolvedValue({ data: listOf([]) })
    ruleApi.getOrder.mockResolvedValue({ data: {} })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedSnippetId.value = 'snippet-a'
    await nextTick()

    store.editSnippet.value = {
      ...store.editSnippet.value!,
      code: '<div>draft</div>',
    }
    store.editDirty.value = true

    await store.reorderSnippet({
      sourceId: 'snippet-a',
      targetId: 'snippet-b',
      placement: 'after',
    })

    expect(store.editSnippet.value?.code).toBe('<div>draft</div>')
    expect(store.editDirty.value).toBe(true)
    expect(snippetApi.list).toHaveBeenCalledTimes(1)
    expect(ruleApi.list).toHaveBeenCalledTimes(1)
    expect(snippetApi.getOrder).toHaveBeenCalledTimes(2)
  })
})
