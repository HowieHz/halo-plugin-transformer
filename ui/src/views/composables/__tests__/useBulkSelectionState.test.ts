import { computed, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ActiveTab } from '@/types'
import { useBulkSelectionState } from '../useBulkSelectionState'

describe('useBulkSelectionState', () => {
  // why: 批量勾选属于“按 tab 隔离的页面态”；切换 tab 时不能把另一侧的选择集冲掉。
  it('keeps independent bulk selections per tab', () => {
    const activeTab = ref<ActiveTab>('snippets')
    const snippets = computed(() => [{ id: 'snippet-a' }, { id: 'snippet-b' }])
    const rules = computed(() => [{ id: 'rule-a' }, { id: 'rule-b' }])

    const state = useBulkSelectionState({
      activeTab,
      snippets,
      rules,
    })

    state.enterBulkMode()
    state.toggleCurrentBulkItem('snippet-a')

    activeTab.value = 'rules'
    state.toggleCurrentBulkItem('rule-b')

    expect(state.bulkSnippetIds.value).toEqual(['snippet-a'])
    expect(state.bulkRuleIds.value).toEqual(['rule-b'])
  })

  // why: 列表刷新后，已不存在的资源不应继续留在批量选择集中，否则批量删除/启停会对着空气操作。
  it('prunes deleted ids from bulk selections after list refresh', async () => {
    const activeTab = ref<ActiveTab>('snippets')
    const snippetItems = ref([{ id: 'snippet-a' }, { id: 'snippet-b' }])
    const ruleItems = ref([{ id: 'rule-a' }])

    const state = useBulkSelectionState({
      activeTab,
      snippets: computed(() => snippetItems.value),
      rules: computed(() => ruleItems.value),
    })

    state.enterBulkMode()
    state.replaceCurrentBulkSelection(['snippet-a', 'snippet-b'])

    snippetItems.value = [{ id: 'snippet-b' }]
    await nextTick()

    expect(state.bulkSnippetIds.value).toEqual(['snippet-b'])
  })
})
