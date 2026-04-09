import { Toast } from "@halo-dev/components";
import { ref, type Ref } from "vue";

import { ruleApi, snippetApi } from "@/apis";
import type {
  ActiveTab,
  TransformationSnippetReadModel,
  TransformationRuleReadModel,
} from "@/types";

import { getErrorMessage } from "./transformerShared";
import { useEditorSelectionState } from "./useEditorSelectionState";
import { useResourceSnapshotState } from "./useResourceSnapshotState";
import { useRuleState } from "./useRuleState";
import { useSnippetState } from "./useSnippetState";

/**
 * why: `useTransformerData` 现在只保留页面级编排职责：
 * 装载共享资源快照，并把 snippet / rule / order / editor-selection 这几个 bounded context 组合起来。
 */
export function useTransformerData(activeTab: Ref<ActiveTab>) {
  const loading = ref(false);
  const creating = ref(false);
  const savingEditor = ref(false);
  const processingBulk = ref(false);

  const snippetSnapshotState = useResourceSnapshotState<TransformationSnippetReadModel>({
    api: snippetApi,
    resourceLabel: "代码片段",
  });

  const ruleSnapshotState = useResourceSnapshotState<TransformationRuleReadModel>({
    api: ruleApi,
    resourceLabel: "转换规则",
  });

  const editorSelectionState = useEditorSelectionState({
    activeTab,
    snippets: snippetSnapshotState.items,
    rules: ruleSnapshotState.items,
  });

  function applySavedSnippetSnapshot(snippet: TransformationSnippetReadModel) {
    snippetSnapshotState.replacePersistedItem(snippet);
    editorSelectionState.syncSavedSnippetDraft(snippet);
  }

  function applySavedRuleSnapshot(rule: TransformationRuleReadModel) {
    ruleSnapshotState.replacePersistedItem(rule);
    editorSelectionState.syncSavedRuleDraft(rule);
  }

  function applySnippetSnapshot(snapshot: typeof snippetSnapshotState.snapshot.value) {
    snippetSnapshotState.applySnapshot(snapshot);
    editorSelectionState.hydrateSelectedSnippetDraft();
  }

  function applyRuleSnapshot(snapshot: typeof ruleSnapshotState.snapshot.value) {
    ruleSnapshotState.applySnapshot(snapshot);
    editorSelectionState.hydrateSelectedRuleDraft();
  }

  async function refreshSnippetSnapshot() {
    const response = await snippetApi.getSnapshot();
    applySnippetSnapshot(response.data);
  }

  async function refreshRuleSnapshot() {
    const response = await ruleApi.getSnapshot();
    applyRuleSnapshot(response.data);
  }

  async function fetchAll() {
    loading.value = true;
    try {
      await Promise.all([refreshSnippetSnapshot(), refreshRuleSnapshot()]);
    } catch (error) {
      Toast.error(getErrorMessage(error, "加载数据失败"));
    } finally {
      loading.value = false;
    }
  }

  /**
   * why: 删除会影响当前资源列表，也可能影响另一侧的关联展示与计数；
   * 这里显式提供一个整页快照刷新入口，避免各个 delete 路径各自猜“要不要顺手刷新另一侧”。
   */
  async function refreshAllResources() {
    await Promise.all([refreshSnippetSnapshot(), refreshRuleSnapshot()]);
  }

  const snippetState = useSnippetState({
    creating,
    savingEditor,
    processingBulk,
    snippets: snippetSnapshotState.items,
    editSnippet: editorSelectionState.editSnippet,
    editDirty: editorSelectionState.editDirty,
    selectedSnippetId: editorSelectionState.selectedSnippetId,
    refreshSnippetSnapshot,
    refreshAllResources,
    saveSnippetOrderMap: snippetSnapshotState.saveOrderMap,
    applySavedSnippetSnapshot,
  });

  const ruleState = useRuleState({
    creating,
    savingEditor,
    processingBulk,
    rules: ruleSnapshotState.items,
    editRule: editorSelectionState.editRule,
    editDirty: editorSelectionState.editDirty,
    selectedRuleId: editorSelectionState.selectedRuleId,
    refreshRuleSnapshot,
    refreshAllResources,
    saveRuleOrderMap: ruleSnapshotState.saveOrderMap,
    applySavedRuleSnapshot,
  });

  return {
    loading,
    creating,
    savingEditor,
    processingBulk,
    snippets: snippetSnapshotState.items,
    rules: ruleSnapshotState.items,
    selectedSnippetId: editorSelectionState.selectedSnippetId,
    selectedRuleId: editorSelectionState.selectedRuleId,
    editSnippet: editorSelectionState.editSnippet,
    editRule: editorSelectionState.editRule,
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
    reorderSnippet: snippetSnapshotState.reorder,
    addRule: ruleState.addRule,
    importRules: ruleState.importRules,
    saveRule: ruleState.saveRule,
    toggleRuleEnabled: ruleState.toggleRuleEnabled,
    setRulesEnabled: ruleState.setRulesEnabled,
    confirmDeleteRule: ruleState.confirmDeleteRule,
    confirmDeleteRules: ruleState.confirmDeleteRules,
    discardRuleEdit: editorSelectionState.discardRuleEdit,
    reorderRule: ruleSnapshotState.reorder,
  };
}
