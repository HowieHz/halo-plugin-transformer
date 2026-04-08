import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { makeRuleEditorDraft, makeSnippetEditorDraft, type ItemList } from '@/types'

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
    updateEnabled: vi.fn(),
    getOrder: vi.fn(),
    updateOrder: vi.fn(),
    delete: vi.fn(),
  },
  ruleApi: {
    list: vi.fn(),
    add: vi.fn(),
    update: vi.fn(),
    updateEnabled: vi.fn(),
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
    vi.resetAllMocks()
  })

  // why: 启停现在应走独立写口，并只改已保存规则的 enabled；
  // 当前右侧的未保存草稿既不该被顺手保存，也不该被整页重载冲掉。
  it('toggles rule enabled without saving or discarding other draft fields', async () => {
    const savedRule = makeRuleEditorDraft({
      id: 'rule-a',
      metadata: { name: 'rule-a', version: 1 },
      enabled: false,
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
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.list.mockResolvedValue({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.updateEnabled.mockResolvedValue({
      data: {
        ...savedRule,
        enabled: true,
        metadata: { ...savedRule.metadata, version: 2 },
      },
    })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    store.editRule.value = {
      ...store.editRule.value!,
      mode: 'SELECTOR',
      match: '',
      name: 'draft name',
    }
    store.editDirty.value = true

    await store.toggleRuleEnabled()

    expect(ruleApi.updateEnabled).toHaveBeenCalledTimes(1)
    expect(ruleApi.update).not.toHaveBeenCalled()
    expect(ruleApi.list).toHaveBeenCalledTimes(1)
    expect(store.editRule.value?.enabled).toBe(true)
    expect(store.editRule.value?.mode).toBe('SELECTOR')
    expect(store.editRule.value?.name).toBe('draft name')
    expect(store.editRule.value?.metadata.version).toBe(2)
    expect(store.editDirty.value).toBe(true)
    expect(toast.error).not.toHaveBeenCalled()
  })

  // why: 代码块启停也应只切 enabled 本身；
  // 其它未保存编辑必须继续留在右侧，而不是被 fetchAll 覆盖掉。
  it('toggles snippet enabled without saving or discarding other draft fields', async () => {
    const savedSnippet = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a', version: 1 },
      enabled: false,
      code: '<div>ok</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    snippetApi.updateEnabled.mockResolvedValue({
      data: {
        ...savedSnippet,
        enabled: true,
        metadata: { ...savedSnippet.metadata, version: 2 },
      },
    })
    ruleApi.list.mockResolvedValue({ data: listOf([]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedSnippetId.value = 'snippet-a'
    await nextTick()

    store.editSnippet.value = {
      ...store.editSnippet.value!,
      code: '',
      name: 'draft snippet',
    }
    store.editDirty.value = true

    await store.toggleSnippetEnabled()

    expect(snippetApi.updateEnabled).toHaveBeenCalledTimes(1)
    expect(snippetApi.update).not.toHaveBeenCalled()
    expect(snippetApi.list).toHaveBeenCalledTimes(1)
    expect(store.editSnippet.value?.enabled).toBe(true)
    expect(store.editSnippet.value?.code).toBe('')
    expect(store.editSnippet.value?.name).toBe('draft snippet')
    expect(store.editSnippet.value?.metadata.version).toBe(2)
    expect(store.editDirty.value).toBe(true)
    expect(toast.error).not.toHaveBeenCalled()
  })

  // why: 前端保存规则时不应再去二次改写代码块；双向关联应交给后端单接口完成。
  it('saves rules without issuing secondary snippet update requests', async () => {
    const savedRule = makeRuleEditorDraft({
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
    const savedSnippet = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      code: '<div>ok</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.list.mockResolvedValue({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.update.mockResolvedValue({ data: savedRule })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    await store.saveRule()

    expect(ruleApi.update).toHaveBeenCalledTimes(1)
    expect(snippetApi.update).not.toHaveBeenCalled()
  })

  // why: 规则保存只应刷新规则上下文；不该再顺手把代码块列表和代码块顺序也整页回拉。
  it('refreshes only rules after saving a rule', async () => {
    const savedRule = makeRuleEditorDraft({
      id: 'rule-a',
      metadata: { name: 'rule-a', version: 1 },
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
            value: '/**',
          },
        ],
      },
    })
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource
    const savedSnippet = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      code: '<div>ok</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.list
      .mockResolvedValueOnce({ data: listOf([savedRule]) })
      .mockResolvedValueOnce({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.update.mockResolvedValue({ data: savedRule })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    await store.saveRule()

    expect(ruleApi.list).toHaveBeenCalledTimes(2)
    expect(ruleApi.getOrder).toHaveBeenCalledTimes(1)
    expect(snippetApi.list).toHaveBeenCalledTimes(1)
    expect(snippetApi.getOrder).toHaveBeenCalledTimes(1)
  })

  // why: 代码块保存只应刷新代码块上下文；规则列表和规则顺序不该因为一次 snippet 保存被整页重拉。
  it('refreshes only snippets after saving a snippet', async () => {
    const savedSnippet = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a', version: 1 },
      name: 'Snippet A',
      code: '<div>ok</div>',
    })
    const savedRule = makeRuleEditorDraft({
      id: 'rule-a',
      metadata: { name: 'rule-a' },
    })
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource

    snippetApi.list
      .mockResolvedValueOnce({ data: listOf([savedSnippet]) })
      .mockResolvedValueOnce({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    snippetApi.update.mockResolvedValue({ data: savedSnippet })
    ruleApi.list.mockResolvedValue({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedSnippetId.value = 'snippet-a'
    await nextTick()

    await store.saveSnippet()

    expect(snippetApi.list).toHaveBeenCalledTimes(2)
    expect(snippetApi.getOrder).toHaveBeenCalledTimes(1)
    expect(ruleApi.list).toHaveBeenCalledTimes(1)
    expect(ruleApi.getOrder).toHaveBeenCalledTimes(1)
  })

  // why: snippet 删除和 rule 引用清理是最终一致；在后端完成收敛前，前端编辑器也不应继续回传已失效的 snippet id。
  it('prunes missing snippet ids from the rule editor selection state', async () => {
    const savedRule = makeRuleEditorDraft({
      id: 'rule-a',
      metadata: { name: 'rule-a', version: 1 },
      snippetIds: ['snippet-missing'],
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
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.list
      .mockResolvedValueOnce({ data: listOf([savedRule]) })
      .mockResolvedValueOnce({ data: listOf([savedRule]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.update.mockResolvedValue({ data: { ...savedRule, snippetIds: [] } })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    expect(store.editRuleSnippetIds.value).toEqual([])
    expect(store.editRule.value?.snippetIds).toEqual([])

    await store.saveRule()

    expect(ruleApi.update).toHaveBeenCalledWith(
      'rule-a',
      expect.objectContaining({
        snippetIds: [],
      }),
    )
  })

  // why: 删除一个资源后，页面上两侧列表和关联面板都可能受影响；删除路径应刷新整页资源快照，而不是只刷当前标签页。
  it('refreshes both snippet and rule lists after deleting a rule', async () => {
    const savedRule = makeRuleEditorDraft({
      id: 'rule-a',
      metadata: { name: 'rule-a', version: 1 },
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
            value: '/**',
          },
        ],
      },
    })
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource
    const savedSnippet = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a', version: 1 },
      code: '<div>ok</div>',
    })

    snippetApi.list
      .mockResolvedValueOnce({ data: listOf([savedSnippet]) })
      .mockResolvedValueOnce({ data: listOf([savedSnippet]) })
    snippetApi.getOrder.mockResolvedValue({ data: { orders: { 'snippet-a': 1 }, version: 1 } })
    ruleApi.list
      .mockResolvedValueOnce({ data: listOf([savedRule]) })
      .mockResolvedValueOnce({ data: listOf([]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: { 'rule-a': 1 }, version: 1 } })
    ruleApi.delete.mockResolvedValue({})
    ruleApi.updateOrder.mockResolvedValue({ data: { orders: {}, version: 2 } })

    const store = useInjectorData()
    await store.fetchAll()
    store.selectedRuleId.value = 'rule-a'
    await nextTick()

    store.confirmDeleteRule()
    const deleteDialogConfig = vi.mocked(dialog.warning).mock.calls[0]?.[0]
    expect(deleteDialogConfig).toBeTruthy()

    await deleteDialogConfig?.onConfirm?.()

    expect(ruleApi.list).toHaveBeenCalledTimes(2)
    expect(snippetApi.list).toHaveBeenCalledTimes(2)
  })

  // why: 左侧排序保存失败时，只应回拉排序映射；右侧未保存的规则草稿不能被整页重载冲掉。
  it('keeps unsaved rule editor state when rule reorder persistence fails', async () => {
    const ruleA = makeRuleEditorDraft({
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
    const ruleB = makeRuleEditorDraft({
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
    snippetApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })
    ruleApi.list.mockResolvedValue({ data: listOf([ruleA, ruleB]) })
    ruleApi.getOrder
      .mockResolvedValueOnce({ data: { orders: { 'rule-a': 10, 'rule-b': 20 }, version: 1 } })
      .mockResolvedValueOnce({ data: { orders: { 'rule-a': 10, 'rule-b': 20 }, version: 1 } })
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
    const snippetA = makeSnippetEditorDraft({
      id: 'snippet-a',
      metadata: { name: 'snippet-a' },
      name: 'Snippet A',
      code: '<div>a</div>',
    })
    const snippetB = makeSnippetEditorDraft({
      id: 'snippet-b',
      metadata: { name: 'snippet-b' },
      name: 'Snippet B',
      code: '<div>b</div>',
    })

    snippetApi.list.mockResolvedValue({ data: listOf([snippetA, snippetB]) })
    snippetApi.getOrder
      .mockResolvedValueOnce({
        data: { orders: { 'snippet-a': 10, 'snippet-b': 20 }, version: 1 },
      })
      .mockResolvedValueOnce({
        data: { orders: { 'snippet-a': 10, 'snippet-b': 20 }, version: 1 },
      })
    snippetApi.updateOrder.mockRejectedValue(new Error('boom'))
    ruleApi.list.mockResolvedValue({ data: listOf([]) })
    ruleApi.getOrder.mockResolvedValue({ data: { orders: {}, version: 1 } })

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
