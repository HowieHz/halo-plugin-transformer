import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { ActiveTab } from '@/types'
import { uniqueStrings } from './util'

export type InjectorViewMode = 'single' | 'bulk'

interface BulkSelectableResource {
  id: string
}

interface UseBulkSelectionStateOptions {
  activeTab: Ref<ActiveTab>
  snippets: ComputedRef<BulkSelectableResource[]>
  rules: ComputedRef<BulkSelectableResource[]>
}

/**
 * why: 批量模式和批量选中态需要独立于“当前单个编辑对象”存在，
 * 否则会把单资源编辑 URL、草稿、批量勾选三套语义揉成一团。
 */
export function useBulkSelectionState(options: UseBulkSelectionStateOptions) {
  const viewMode = ref<InjectorViewMode>('single')
  const bulkSnippetIds = ref<string[]>([])
  const bulkRuleIds = ref<string[]>([])

  const currentBulkIds = computed(() =>
    options.activeTab.value === 'snippets' ? bulkSnippetIds.value : bulkRuleIds.value,
  )

  const currentBulkSelectionCount = computed(() => currentBulkIds.value.length)
  const isBulkMode = computed(() => viewMode.value === 'bulk')
  const allCurrentSelected = computed(() => {
    const resourceIds = resolveResourceIds(options.activeTab.value)
    return resourceIds.length > 0 && resourceIds.every((id) => currentBulkIds.value.includes(id))
  })
  const someCurrentSelected = computed(
    () => currentBulkSelectionCount.value > 0 && !allCurrentSelected.value,
  )

  function enterBulkMode() {
    viewMode.value = 'bulk'
  }

  function exitBulkMode() {
    viewMode.value = 'single'
  }

  function clearCurrentBulkSelection() {
    replaceCurrentBulkSelection([])
  }

  function replaceCurrentBulkSelection(ids: string[]) {
    const normalizedIds = filterExistingIds(options.activeTab.value, ids)
    if (options.activeTab.value === 'snippets') {
      bulkSnippetIds.value = normalizedIds
      return
    }
    bulkRuleIds.value = normalizedIds
  }

  function appendCurrentBulkSelection(ids: string[]) {
    replaceCurrentBulkSelection([...currentBulkIds.value, ...ids])
  }

  function toggleCurrentBulkItem(id: string) {
    replaceCurrentBulkSelection(
      currentBulkIds.value.includes(id)
        ? currentBulkIds.value.filter((currentId) => currentId !== id)
        : [...currentBulkIds.value, id],
    )
  }

  function toggleCurrentBulkSelectAll() {
    if (allCurrentSelected.value) {
      clearCurrentBulkSelection()
      return
    }
    replaceCurrentBulkSelection(resolveResourceIds(options.activeTab.value))
  }

  function isCurrentBulkSelected(id: string) {
    return currentBulkIds.value.includes(id)
  }

  function pruneSelection(tab: ActiveTab) {
    const nextIds = filterExistingIds(
      tab,
      tab === 'snippets' ? bulkSnippetIds.value : bulkRuleIds.value,
    )
    if (tab === 'snippets') {
      bulkSnippetIds.value = nextIds
      return
    }
    bulkRuleIds.value = nextIds
  }

  function resolveResourceIds(tab: ActiveTab) {
    return (tab === 'snippets' ? options.snippets.value : options.rules.value).map(
      (item) => item.id,
    )
  }

  function filterExistingIds(tab: ActiveTab, ids: string[]) {
    const allowedIds = new Set(resolveResourceIds(tab))
    return uniqueStrings(ids).filter((id) => allowedIds.has(id))
  }

  watch(options.snippets, () => pruneSelection('snippets'))
  watch(options.rules, () => pruneSelection('rules'))

  return {
    viewMode,
    isBulkMode,
    bulkSnippetIds,
    bulkRuleIds,
    currentBulkIds,
    currentBulkSelectionCount,
    allCurrentSelected,
    someCurrentSelected,
    enterBulkMode,
    exitBulkMode,
    clearCurrentBulkSelection,
    replaceCurrentBulkSelection,
    appendCurrentBulkSelection,
    toggleCurrentBulkItem,
    toggleCurrentBulkSelectAll,
    isCurrentBulkSelected,
  }
}
