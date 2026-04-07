import { ref } from 'vue'
import { Toast } from '@halo-dev/components'
import { ruleApi, snippetApi } from '@/apis'
import type { CodeSnippetReadModel, InjectionRuleReadModel } from '@/types'
import { emptyList, getErrorMessage } from './injectorShared'
import { useEditorSelectionState } from './useEditorSelectionState'
import { useResourceOrderState } from './useResourceOrderState'
import { useRuleState } from './useRuleState'
import { useSnippetState } from './useSnippetState'

/**
 * why: `useInjectorData` 现在只保留页面级编排职责：
 * 装载共享资源快照，并把 snippet / rule / order / editor-selection 这几个 bounded context 组合起来。
 */
export function useInjectorData() {
  const loading = ref(false)
  const creating = ref(false)
  const savingEditor = ref(false)

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
    snippetsResp,
    rulesResp,
    snippets: snippetOrderState.items,
    rules: ruleOrderState.items,
  })

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

  const snippetState = useSnippetState({
    creating,
    savingEditor,
    snippets: snippetOrderState.items,
    editSnippet: editorSelectionState.editSnippet,
    editDirty: editorSelectionState.editDirty,
    selectedSnippetId: editorSelectionState.selectedSnippetId,
    fetchAll,
    saveSnippetOrderMap: snippetOrderState.saveOrderMap,
    applySavedSnippetSnapshot: editorSelectionState.applySavedSnippetSnapshot,
  })

  const ruleState = useRuleState({
    creating,
    savingEditor,
    rules: ruleOrderState.items,
    editRule: editorSelectionState.editRule,
    editRuleSnippetIds: editorSelectionState.editRuleSnippetIds,
    editDirty: editorSelectionState.editDirty,
    selectedRuleId: editorSelectionState.selectedRuleId,
    fetchAll,
    saveRuleOrderMap: ruleOrderState.saveOrderMap,
    applySavedRuleSnapshot: editorSelectionState.applySavedRuleSnapshot,
  })

  return {
    loading,
    creating,
    savingEditor,
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
    saveSnippet: snippetState.saveSnippet,
    toggleSnippetEnabled: snippetState.toggleSnippetEnabled,
    confirmDeleteSnippet: snippetState.confirmDeleteSnippet,
    discardSnippetEdit: editorSelectionState.discardSnippetEdit,
    reorderSnippet: snippetOrderState.reorder,
    addRule: ruleState.addRule,
    saveRule: ruleState.saveRule,
    toggleRuleEnabled: ruleState.toggleRuleEnabled,
    confirmDeleteRule: ruleState.confirmDeleteRule,
    discardRuleEdit: editorSelectionState.discardRuleEdit,
    toggleSnippetInRuleEditor: editorSelectionState.toggleSnippetInRuleEditor,
    reorderRule: ruleOrderState.reorder,
  }
}
