import { ref, type Ref } from 'vue'
import { Toast } from '@halo-dev/components'
import { ruleApi, snippetApi } from '@/apis'
import type { ActiveTab, CodeSnippetReadModel, InjectionRuleReadModel } from '@/types'
import { emptyList, getErrorMessage } from './injectorShared'
import { useEditorSelectionState } from './useEditorSelectionState'
import { useResourceOrderState } from './useResourceOrderState'
import { useRuleState } from './useRuleState'
import { useSnippetState } from './useSnippetState'

/**
 * why: `useInjectorData` 现在只保留页面级编排职责：
 * 装载共享资源快照，并把 snippet / rule / order / editor-selection 这几个 bounded context 组合起来。
 */
export function useInjectorData(activeTab: Ref<ActiveTab>) {
  const loading = ref(false)
  const creating = ref(false)
  const savingEditor = ref(false)
  const processingBulk = ref(false)

  const snippetsResp = ref(emptyList<CodeSnippetReadModel>())
  const rulesResp = ref(emptyList<InjectionRuleReadModel>())

  const snippetOrderState = useResourceOrderState({
    itemsResp: snippetsResp,
    api: snippetApi,
    resourceLabel: '代码块',
  })

  const ruleOrderState = useResourceOrderState({
    itemsResp: rulesResp,
    api: ruleApi,
    resourceLabel: '注入规则',
  })

  const editorSelectionState = useEditorSelectionState({
    activeTab,
    snippetsResp,
    rulesResp,
    snippets: snippetOrderState.items,
    rules: ruleOrderState.items,
  })

  async function refreshSnippetList() {
    const response = await snippetApi.list()
    snippetsResp.value = response.data
    editorSelectionState.hydrateSelectedSnippetDraft()
  }

  async function refreshRuleList() {
    const response = await ruleApi.list()
    rulesResp.value = response.data
    editorSelectionState.hydrateSelectedRuleDraft()
  }

  async function fetchAll() {
    loading.value = true
    try {
      const [snippetResponse, ruleResponse, snippetOrderResponse, ruleOrderResponse] =
        await Promise.all([
          snippetApi.list(),
          ruleApi.list(),
          snippetApi.getOrder(),
          ruleApi.getOrder(),
        ])

      snippetsResp.value = snippetResponse.data
      rulesResp.value = ruleResponse.data
      snippetOrderState.applyOrderSnapshot(snippetOrderResponse.data)
      ruleOrderState.applyOrderSnapshot(ruleOrderResponse.data)
      editorSelectionState.hydrateSelectedSnippetDraft()
      editorSelectionState.hydrateSelectedRuleDraft()
    } catch (error) {
      Toast.error(getErrorMessage(error, '加载数据失败'))
    } finally {
      loading.value = false
    }
  }

  /**
   * why: 删除会影响当前资源列表，也可能影响另一侧的关联展示与计数；
   * 这里显式提供一个整页快照刷新入口，避免各个 delete 路径各自猜“要不要顺手刷新另一侧”。
   */
  async function refreshAllResources() {
    await Promise.all([refreshSnippetList(), refreshRuleList()])
  }

  const snippetState = useSnippetState({
    creating,
    savingEditor,
    processingBulk,
    snippets: snippetOrderState.items,
    editSnippet: editorSelectionState.editSnippet,
    editDirty: editorSelectionState.editDirty,
    selectedSnippetId: editorSelectionState.selectedSnippetId,
    refreshSnippetList,
    refreshAllResources,
    saveSnippetOrderMap: snippetOrderState.saveOrderMap,
    applySavedSnippetSnapshot: editorSelectionState.applySavedSnippetSnapshot,
  })

  const ruleState = useRuleState({
    creating,
    savingEditor,
    processingBulk,
    rules: ruleOrderState.items,
    editRule: editorSelectionState.editRule,
    editRuleSnippetIds: editorSelectionState.editRuleSnippetIds,
    editDirty: editorSelectionState.editDirty,
    selectedRuleId: editorSelectionState.selectedRuleId,
    refreshRuleList,
    refreshAllResources,
    saveRuleOrderMap: ruleOrderState.saveOrderMap,
    applySavedRuleSnapshot: editorSelectionState.applySavedRuleSnapshot,
  })

  return {
    loading,
    creating,
    savingEditor,
    processingBulk,
    snippets: snippetOrderState.items,
    rules: ruleOrderState.items,
    selectedSnippetId: editorSelectionState.selectedSnippetId,
    selectedRuleId: editorSelectionState.selectedRuleId,
    editSnippet: editorSelectionState.editSnippet,
    editRule: editorSelectionState.editRule,
    editRuleSnippetIds: editorSelectionState.editRuleSnippetIds,
    editDirty: editorSelectionState.editDirty,
    snippetEditorError: snippetState.snippetEditorError,
    ruleEditorError: ruleState.ruleEditorError,
    rulesUsingSnippet: editorSelectionState.rulesUsingSnippet,
    snippetsInRule: editorSelectionState.snippetsInRule,
    fetchAll,
    addSnippet: snippetState.addSnippet,
    importSnippets: snippetState.importSnippets,
    saveSnippet: snippetState.saveSnippet,
    toggleSnippetEnabled: snippetState.toggleSnippetEnabled,
    setSnippetsEnabled: snippetState.setSnippetsEnabled,
    confirmDeleteSnippet: snippetState.confirmDeleteSnippet,
    confirmDeleteSnippets: snippetState.confirmDeleteSnippets,
    discardSnippetEdit: editorSelectionState.discardSnippetEdit,
    reorderSnippet: snippetOrderState.reorder,
    addRule: ruleState.addRule,
    importRules: ruleState.importRules,
    saveRule: ruleState.saveRule,
    toggleRuleEnabled: ruleState.toggleRuleEnabled,
    setRulesEnabled: ruleState.setRulesEnabled,
    confirmDeleteRule: ruleState.confirmDeleteRule,
    confirmDeleteRules: ruleState.confirmDeleteRules,
    discardRuleEdit: editorSelectionState.discardRuleEdit,
    toggleSnippetInRuleEditor: editorSelectionState.toggleSnippetInRuleEditor,
    reorderRule: ruleOrderState.reorder,
  }
}
