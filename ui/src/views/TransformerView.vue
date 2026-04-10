<script lang="ts" setup>
import { Toast, VButton, VCard, VLoading, VModal, VPageHeader, VSpace } from "@halo-dev/components";
import { computed, nextTick, onMounted, ref, useId, watch } from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
  type LocationQueryRaw,
} from "vue-router";

import PluginLogoIcon from "@/components/PluginLogoIcon.vue";
import type {
  ActiveTab,
  TransformationSnippetEditorDraft,
  TransformationRuleEditorDraft,
} from "@/types";

import BulkImportOptionsModal from "./components/BulkImportOptionsModal.vue";
import BulkImportResultModal from "./components/BulkImportResultModal.vue";
import BulkModeSidePanel from "./components/BulkModeSidePanel.vue";
import BulkOperationPanel from "./components/BulkOperationPanel.vue";
import DragAutoScrollOverlay from "./components/DragAutoScrollOverlay.vue";
import ExportContentModal from "./components/ExportContentModal.vue";
import ImportSourceModal from "./components/ImportSourceModal.vue";
import MobileDrawerPanel from "./components/MobileDrawerPanel.vue";
import RelationPanel from "./components/RelationPanel.vue";
import ResourceList from "./components/ResourceList.vue";
import RuleEditor from "./components/RuleEditor.vue";
import RuleFormModal from "./components/RuleFormModal.vue";
import SnippetEditor from "./components/SnippetEditor.vue";
import SnippetFormModal from "./components/SnippetFormModal.vue";
import type { EditorEmptyStateLayout } from "./composables/editorEmptyState";
import { matchRuleSummary } from "./composables/matchRule.ts";
import { hydrateRuleEditorDraft } from "./composables/ruleDraft";
import { hydrateSnippetEditorDraft } from "./composables/snippetDraft";
import {
  buildRuleBatchTransfer,
  buildSnippetBatchTransfer,
  createTransferFileDraft,
  parseRuleBatchTransfer,
  parseSnippetBatchTransfer,
  type TransferFileDraft,
} from "./composables/transfer";
import {
  buildTransformerRouteQuery,
  isSameTransformerRouteState,
  parseTransformerRouteState,
} from "./composables/transformerRouteState";
import {
  useBulkImportFlowState,
  type BulkImportPayload,
} from "./composables/useBulkImportFlowState";
import { useBulkSelectionState } from "./composables/useBulkSelectionState";
import {
  useCreateSessionState,
  type CreateFormController,
} from "./composables/useCreateSessionState";
import { useDragAutoScroll } from "./composables/useDragAutoScroll";
import { useLeaveConfirmation } from "./composables/useLeaveConfirmation";
import { useMobileDrawerState, type MobileDrawerSide } from "./composables/useMobileDrawerState";
import { useTransformerData } from "./composables/useTransformerData.ts";
import {
  useTransformerViewSessionState,
  type TransformerPageMode,
} from "./composables/useTransformerViewSessionState";
import { rulePreview } from "./composables/util.ts";

const activeTab = ref<ActiveTab>("snippets");
const route = useRoute();
const router = useRouter();

const syncingQuery = ref(false);
const queryStateHydrated = ref(false);
const bulkExportFallback = ref<TransferFileDraft | null>(null);
const bulkImportFileInput = ref<HTMLInputElement | null>(null);
const bulkImportFlow = useBulkImportFlowState();
const transformerViewMode = ref<TransformerPageMode>("single");
const snippetFormRef = ref<CreateFormController<{
  snippet: TransformationSnippetEditorDraft;
}> | null>(null);
const ruleFormRef = ref<CreateFormController<{ rule: TransformationRuleEditorDraft }> | null>(null);
const resourceListRef = ref<{
  getScrollContainer: () => HTMLElement | null;
  commitPendingDrop: () => void;
} | null>(null);
const resourceListScrollContainer = ref<HTMLElement | null>(null);
const leftPaneAutoScroll = useDragAutoScroll(resourceListScrollContainer);
const LEFT_PANE_EDGE_OVERLAP_PX = 5;
const tabGroupId = useId();
const mobileLeftDrawerId = `transformer-mobile-left-${tabGroupId}`;
const mobileRightDrawerId = `transformer-mobile-right-${tabGroupId}`;
const mobileLeftDrawerTitleId = `transformer-mobile-left-title-${tabGroupId}`;
const mobileRightDrawerTitleId = `transformer-mobile-right-title-${tabGroupId}`;
const mobileLeftDrawerDescriptionId = `transformer-mobile-left-description-${tabGroupId}`;
const mobileRightDrawerDescriptionId = `transformer-mobile-right-description-${tabGroupId}`;
const mobileLeftDrawerToggleId = `transformer-mobile-left-toggle-${tabGroupId}`;
const mobileRightDrawerToggleId = `transformer-mobile-right-toggle-${tabGroupId}`;
const TAB_DEFINITIONS = [
  { key: "snippets", label: "代码片段" },
  { key: "rules", label: "转换规则" },
] as const satisfies ReadonlyArray<{ key: ActiveTab; label: string }>;
const mobileDrawer = useMobileDrawerState();

const {
  loading,
  creating,
  savingEditor,
  processingBulk,
  snippets,
  rules,
  selectedSnippetId,
  selectedRuleId,
  editSnippet,
  editRule,
  editDirty,
  snippetEditorError,
  ruleEditorError,
  rulesUsingSnippet,
  snippetsInRule,
  fetchAll,
  addSnippet,
  importSnippets,
  saveSnippet,
  toggleSnippetEnabled,
  setSnippetsEnabled,
  confirmDeleteSnippet,
  confirmDeleteSnippets,
  discardSnippetEdit,
  reorderSnippet,
  addRule,
  importRules,
  saveRule,
  toggleRuleEnabled,
  setRulesEnabled,
  confirmDeleteRule,
  confirmDeleteRules,
  discardRuleEdit,
  reorderRule,
} = useTransformerData(activeTab);

const viewSession = useTransformerViewSessionState({
  activeTab,
  selectedSnippetId,
  selectedRuleId,
  viewMode: transformerViewMode,
});
const { createModalTab, isBulkMode } = viewSession;
const createSession = useCreateSessionState({
  createModalTab,
  snippetFormRef,
  ruleFormRef,
});
const bulkSelectionState = useBulkSelectionState({
  activeTab,
  snippets,
  rules,
});
const selectedBulkResources = computed(() => {
  if (activeTab.value === "snippets") {
    const selectedIds = new Set(bulkSelectionState.currentBulkIds.value);
    return snippets.value.filter((item) => selectedIds.has(item.id));
  }
  const selectedIds = new Set(bulkSelectionState.currentBulkIds.value);
  return rules.value.filter((item) => selectedIds.has(item.id));
});
const canBulkEnable = computed(() => selectedBulkResources.value.some((item) => !item.enabled));
const canBulkDisable = computed(() => selectedBulkResources.value.some((item) => item.enabled));
const mobileLeftDrawerLabel = computed(() => "选择列表");
const mobileRightDrawerLabel = computed(() => (isBulkMode.value ? "批量信息" : "关联关系"));
const mobileMainLabel = computed(() =>
  isBulkMode.value
    ? "批量操作"
    : activeTab.value === "snippets"
      ? "代码片段编辑区"
      : "转换规则编辑区",
);
const isCompactDrawerModalActive = computed(() => mobileDrawer.backdropVisible.value);
const editorEmptyStateLayout = computed<EditorEmptyStateLayout>(() =>
  mobileDrawer.isMobileViewport.value ? "compact" : "split-pane",
);

onMounted(fetchAll);

const postCreatePrompt = ref<null | { tab: ActiveTab; id: string }>(null);

function tabButtonId(tab: ActiveTab) {
  return `transformer-tab-${tabGroupId}-${tab}`;
}

function tabPanelId(tab: ActiveTab) {
  return `transformer-tabpanel-${tabGroupId}-${tab}`;
}

function focusTabButton(tab: ActiveTab) {
  document.getElementById(tabButtonId(tab))?.focus();
}

function drawerToggleButtonId(side: MobileDrawerSide) {
  return side === "left" ? mobileLeftDrawerToggleId : mobileRightDrawerToggleId;
}

function focusDrawerToggleButton(side: MobileDrawerSide) {
  document.getElementById(drawerToggleButtonId(side))?.focus();
}

function closeMobileDrawer() {
  mobileDrawer.closeDrawer();
}

function handleLeftPaneDragOver(event: DragEvent) {
  leftPaneAutoScroll.handleContainerDragOver(event, {
    topZoneHeight: 48 + LEFT_PANE_EDGE_OVERLAP_PX,
    bottomZoneHeight: 48 + LEFT_PANE_EDGE_OVERLAP_PX,
  });
}

function handleLeftPaneDragLeave(event: DragEvent) {
  leftPaneAutoScroll.handleContainerDragLeave(event);
}

function handleLeftPaneDropCapture() {
  resourceListRef.value?.commitPendingDrop();
}

watch(
  () => ({
    activeDrawer: mobileDrawer.activeDrawer.value,
    isCompact: mobileDrawer.isMobileViewport.value,
  }),
  (nextState, previousState) => {
    if (
      !previousState ||
      previousState.activeDrawer === "none" ||
      !nextState.isCompact ||
      nextState.activeDrawer !== "none"
    ) {
      return;
    }

    const previousActiveDrawer: MobileDrawerSide = previousState.activeDrawer;
    nextTick(() => {
      focusDrawerToggleButton(previousActiveDrawer);
    });
  },
);

function currentSelectedId(tab: ActiveTab) {
  return viewSession.currentSelectedId(tab);
}

function applyRouteState() {
  viewSession.applyRouteState(parseTransformerRouteState(route.query));
  queryStateHydrated.value = true;
}

function applyQueryState() {
  applyRouteState();
}

function syncQueryState() {
  const nextState = viewSession.currentRouteState();
  const currentState = parseTransformerRouteState(route.query);

  if (isSameTransformerRouteState(currentState, nextState)) {
    return;
  }

  const nextQuery: LocationQueryRaw = buildTransformerRouteQuery(route.query, nextState);

  syncingQuery.value = true;
  void router
    .replace({
      query: nextQuery,
    })
    .finally(() => {
      syncingQuery.value = false;
    });
}

/**
 * why: 编辑保护不能只拦住“组件内部按钮”，浏览器前进/后退、外部 query 变更也必须走同一条离开确认路径；
 * 否则 URL 仍会成为绕过草稿保护的后门。
 */
onBeforeRouteUpdate((to) => {
  if (syncingQuery.value || !queryStateHydrated.value) {
    return true;
  }

  const nextState = parseTransformerRouteState(to.query);
  if (isSameTransformerRouteState(viewSession.currentRouteState(), nextState)) {
    return true;
  }
  return requestNavigationLeave();
});

/**
 * why: 只拦住页内 query 变化还不够；如果整页导航能直接离开，
 * 草稿一样会静默丢失，因此 route leave 也必须复用同一套离开确认。
 */
onBeforeRouteLeave(() => {
  if (syncingQuery.value || !queryStateHydrated.value) {
    return true;
  }
  return requestNavigationLeave();
});

function openCreateModal(tab: ActiveTab) {
  viewSession.openCreate(tab);
}

function closeSnippetModal() {
  viewSession.closeCreate("snippets");
}

function closeRuleModal() {
  viewSession.closeCreate("rules");
}

function hasUnsavedCreateChanges() {
  return createSession.hasUnsavedChanges();
}

function hasUnsavedEditorChanges() {
  return editDirty.value && !!(activeTab.value === "snippets" ? editSnippet.value : editRule.value);
}

function hasUnsavedChanges() {
  return hasUnsavedCreateChanges() || hasUnsavedEditorChanges();
}

function currentValidationError() {
  if (createModalTab.value) {
    return createSession.getValidationError();
  }
  return activeTab.value === "snippets" ? snippetEditorError.value : ruleEditorError.value;
}

function discardCurrentChanges() {
  if (createModalTab.value) {
    createSession.discardCurrentSession();
    return;
  }
  if (activeTab.value === "snippets") {
    discardSnippetEdit();
    return;
  }
  discardRuleEdit();
}

function closePostCreatePrompt() {
  postCreatePrompt.value = null;
}

async function saveCurrentChanges() {
  let saved = false;
  if (createModalTab.value === "snippets") {
    const payload = snippetFormRef.value?.getSubmitPayload();
    saved = payload ? !!(await addSnippet(payload.snippet)) : false;
    if (saved) {
      createSession.close("snippets");
    }
  } else if (createModalTab.value === "rules") {
    const payload = ruleFormRef.value?.getSubmitPayload();
    saved = payload ? !!(await addRule(payload.rule)) : false;
    if (saved) {
      createSession.close("rules");
    }
  } else {
    saved = activeTab.value === "snippets" ? await saveSnippet() : await saveRule();
  }
  if (!saved) {
    return false;
  }
  return true;
}

const {
  leaveConfirmVisible,
  leaveConfirmCanSave,
  requestActionLeave: requestEditorLeave,
  requestNavigationLeave,
  closeLeaveConfirm,
  confirmDiscardAndLeave,
  confirmSaveAndLeave,
} = useLeaveConfirmation({
  hasUnsavedChanges,
  hasValidationError: () => !!currentValidationError(),
  discardChanges: discardCurrentChanges,
  saveChanges: saveCurrentChanges,
});

function resetCreateForm(tab: ActiveTab) {
  createSession.resetForm(tab);
}

function keepCreatingCreatedResource() {
  if (!postCreatePrompt.value) {
    return;
  }
  resetCreateForm(postCreatePrompt.value.tab);
  closePostCreatePrompt();
}

function focusCreatedResource() {
  const prompt = postCreatePrompt.value;
  if (!prompt) {
    return;
  }
  resetCreateForm(prompt.tab);
  viewSession.selectResource(prompt.tab, prompt.id);
  closePostCreatePrompt();
}

function handleTabSwitch(tab: ActiveTab) {
  if (activeTab.value === tab) {
    return;
  }
  requestEditorLeave(() => {
    viewSession.switchTab(tab);
  });
}

/**
 * why: 左侧页签不能只靠鼠标点击；补上方向键 / Home / End 后，
 * 键盘用户才可以按标准页签习惯切换资源类型。
 */
function handleTabKeydown(event: KeyboardEvent, currentTab: ActiveTab) {
  const currentIndex = TAB_DEFINITIONS.findIndex((tab) => tab.key === currentTab);
  if (currentIndex === -1) {
    return;
  }

  let nextTab: ActiveTab | null = null;
  if (event.key === "ArrowLeft") {
    nextTab =
      TAB_DEFINITIONS[(currentIndex - 1 + TAB_DEFINITIONS.length) % TAB_DEFINITIONS.length].key;
  } else if (event.key === "ArrowRight") {
    nextTab = TAB_DEFINITIONS[(currentIndex + 1) % TAB_DEFINITIONS.length].key;
  } else if (event.key === "Home") {
    nextTab = TAB_DEFINITIONS[0].key;
  } else if (event.key === "End") {
    nextTab = TAB_DEFINITIONS[TAB_DEFINITIONS.length - 1].key;
  }

  if (!nextTab || nextTab === currentTab) {
    return;
  }

  event.preventDefault();
  handleTabSwitch(nextTab);
  nextTick(() => {
    focusTabButton(activeTab.value);
  });
}

function handleSnippetSelect(id: string) {
  if (
    activeTab.value === "snippets" &&
    createModalTab.value !== "snippets" &&
    selectedSnippetId.value === id
  ) {
    return;
  }
  requestEditorLeave(() => {
    viewSession.selectResource("snippets", id);
    mobileDrawer.closeDrawer();
  });
}

function handleRuleSelect(id: string) {
  if (
    activeTab.value === "rules" &&
    createModalTab.value !== "rules" &&
    selectedRuleId.value === id
  ) {
    return;
  }
  requestEditorLeave(() => {
    viewSession.selectResource("rules", id);
    mobileDrawer.closeDrawer();
  });
}

function handleOpenCreateModal(tab: ActiveTab) {
  requestEditorLeave(() => {
    openCreateModal(tab);
    mobileDrawer.closeDrawer();
  });
}

function enterBulkMode() {
  requestEditorLeave(() => {
    viewSession.enterBulkMode();
    mobileDrawer.closeDrawer();
  });
}

function exitBulkMode() {
  viewSession.exitBulkMode();
  mobileDrawer.closeDrawer();
}

function handleBulkItemToggle(id: string) {
  bulkSelectionState.toggleCurrentBulkItem(id);
}

function handleBulkToggleAll() {
  bulkSelectionState.toggleCurrentBulkSelectAll();
}

function openBulkImportSourceModal() {
  bulkImportFlow.openSource();
}

function closeBulkImportFlow() {
  bulkImportFlow.close();
}

async function handleBulkImportFromClipboard() {
  let text = "";
  try {
    text = await navigator.clipboard.readText();
  } catch {
    Toast.error("读取剪贴板失败，请检查浏览器权限后重试");
    return;
  }

  if (!text.trim()) {
    Toast.warning("剪贴板里没有可导入的 JSON");
    return;
  }

  try {
    applyBulkImportSource(text);
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "导入失败");
  }
}

async function handleBulkImportFromFile() {
  bulkImportFlow.close();
  await nextTick();
  bulkImportFileInput.value?.click();
}

async function handleBulkImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }

  try {
    applyBulkImportSource(await file.text());
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "导入失败");
  } finally {
    input.value = "";
  }
}

function applyBulkImportSource(raw: string) {
  const pendingImport: BulkImportPayload =
    activeTab.value === "snippets"
      ? { tab: "snippets", items: parseSnippetBatchTransfer(raw) }
      : { tab: "rules", items: parseRuleBatchTransfer(raw) };
  bulkImportFlow.openOptions(pendingImport);
}

async function submitBulkImport(enabled: boolean) {
  const pendingImport = bulkImportFlow.pendingImport.value;
  if (!pendingImport) {
    return;
  }

  const importedIds =
    pendingImport.tab === "snippets"
      ? await importSnippets(pendingImport.items, enabled)
      : await importRules(pendingImport.items, enabled);

  if (importedIds.length > 0) {
    if (pendingImport.tab === activeTab.value) {
      bulkSelectionState.appendCurrentBulkSelection(importedIds);
    }
    bulkImportFlow.openResult({
      count: importedIds.length,
      tab: pendingImport.tab,
    });
  } else {
    bulkImportFlow.close();
  }
}

function continueBulkImport() {
  bulkImportFlow.continueImport();
}

function handleBulkExport() {
  if (activeTab.value === "snippets") {
    const selectedItems = snippets.value.filter((item) =>
      bulkSelectionState.currentBulkIds.value.includes(item.id),
    );
    if (!selectedItems.length) {
      return;
    }
    bulkExportFallback.value = createTransferFileDraft(
      buildSnippetBatchTransfer(selectedItems.map(hydrateSnippetEditorDraft)),
      "transformer-snippets-batch",
    );
    return;
  }

  const selectedItems = rules.value.filter((item) =>
    bulkSelectionState.currentBulkIds.value.includes(item.id),
  );
  if (!selectedItems.length) {
    return;
  }
  bulkExportFallback.value = createTransferFileDraft(
    buildRuleBatchTransfer(selectedItems.map(hydrateRuleEditorDraft)),
    "transformer-rules-batch",
  );
}

function handleBulkEnable() {
  if (activeTab.value === "snippets") {
    void setSnippetsEnabled(bulkSelectionState.currentBulkIds.value, true);
    return;
  }
  void setRulesEnabled(bulkSelectionState.currentBulkIds.value, true);
}

function handleBulkDisable() {
  if (activeTab.value === "snippets") {
    void setSnippetsEnabled(bulkSelectionState.currentBulkIds.value, false);
    return;
  }
  void setRulesEnabled(bulkSelectionState.currentBulkIds.value, false);
}

function handleBulkDelete() {
  if (activeTab.value === "snippets") {
    confirmDeleteSnippets(bulkSelectionState.currentBulkIds.value);
    return;
  }
  confirmDeleteRules(bulkSelectionState.currentBulkIds.value);
}

watch(
  () => [route.query.tab, route.query.id, route.query.action, route.query.mode],
  () => {
    applyQueryState();
  },
  { immediate: true },
);

watch([activeTab, selectedSnippetId, selectedRuleId, transformerViewMode], () => {
  if (!queryStateHydrated.value || syncingQuery.value) return;
  syncQueryState();
});

watch([activeTab, loading], async () => {
  await nextTick();
  resourceListScrollContainer.value = resourceListRef.value?.getScrollContainer() ?? null;
});

watch(activeTab, async (tab) => {
  await nextTick();
  focusTabButton(tab);
});

async function handleAddSnippet(...args: Parameters<typeof addSnippet>) {
  const id = await addSnippet(...args);
  if (id) {
    if (isBulkMode.value) {
      bulkSelectionState.appendCurrentBulkSelection([id]);
      return;
    }
    postCreatePrompt.value = { tab: "snippets", id };
  }
}

async function handleAddRule(...args: Parameters<typeof addRule>) {
  const id = await addRule(...args);
  if (id) {
    if (isBulkMode.value) {
      bulkSelectionState.appendCurrentBulkSelection([id]);
      return;
    }
    postCreatePrompt.value = { tab: "rules", id };
  }
}

function jumpToRule(id: string) {
  requestEditorLeave(() => {
    viewSession.selectResource("rules", id);
    mobileDrawer.closeDrawer();
  });
}

function jumpToSnippet(id: string) {
  requestEditorLeave(() => {
    viewSession.selectResource("snippets", id);
    mobileDrawer.closeDrawer();
  });
}
</script>

<template>
  <div id="transformer-view">
    <ExportContentModal
      v-if="bulkExportFallback"
      :content="bulkExportFallback.content"
      :file-name="bulkExportFallback.fileName"
      @close="bulkExportFallback = null"
    />
    <ImportSourceModal
      v-if="bulkImportFlow.sourceVisible.value"
      :resource-label="activeTab === 'snippets' ? '批量代码片段' : '批量转换规则'"
      @close="closeBulkImportFlow"
      @import-from-clipboard="handleBulkImportFromClipboard"
      @import-from-file="handleBulkImportFromFile"
    />
    <BulkImportOptionsModal
      v-if="bulkImportFlow.pendingImport.value"
      :item-count="bulkImportFlow.pendingImport.value.items.length"
      :resource-label="
        bulkImportFlow.pendingImport.value.tab === 'snippets' ? '代码片段' : '转换规则'
      "
      :submitting="processingBulk"
      @close="closeBulkImportFlow"
      @submit="submitBulkImport"
    />
    <BulkImportResultModal
      v-if="bulkImportFlow.importResult.value"
      :imported-count="bulkImportFlow.importResult.value.count"
      :resource-label="
        bulkImportFlow.importResult.value.tab === 'snippets' ? '代码片段' : '转换规则'
      "
      @close="closeBulkImportFlow"
      @continue="continueBulkImport"
    />
    <SnippetFormModal
      v-if="createModalTab === 'snippets'"
      ref="snippetFormRef"
      :saving="creating"
      @close="requestEditorLeave(closeSnippetModal)"
      @submit="handleAddSnippet"
    />
    <RuleFormModal
      v-if="createModalTab === 'rules'"
      ref="ruleFormRef"
      :saving="creating"
      :snippets="snippets"
      @close="requestEditorLeave(closeRuleModal)"
      @submit="handleAddRule"
    />

    <VModal v-if="leaveConfirmVisible" title="离开当前编辑" :width="520" @close="closeLeaveConfirm">
      <div class=":uno: space-y-3 px-1 py-1 text-sm leading-6 text-gray-700">
        <p>当前有未保存的修改，继续切换后不会自动保存。</p>
        <p v-if="leaveConfirmCanSave">你可以先保存，再继续切换。</p>
        <p v-else class=":uno: text-red-600">
          当前修改有错误，无法直接保存；如需继续，请放弃这些修改。
        </p>
      </div>

      <template #footer>
        <VSpace>
          <VButton :disabled="savingEditor" @click="closeLeaveConfirm">取消</VButton>
          <VButton :disabled="savingEditor" type="danger" @click="confirmDiscardAndLeave">
            放弃
          </VButton>
          <VButton
            v-if="leaveConfirmCanSave"
            :disabled="savingEditor"
            type="secondary"
            @click="confirmSaveAndLeave"
          >
            {{ savingEditor ? "保存中..." : "保存" }}
          </VButton>
        </VSpace>
      </template>
    </VModal>

    <VModal
      v-if="postCreatePrompt"
      :title="postCreatePrompt.tab === 'snippets' ? '代码片段已创建' : '转换规则已创建'"
      :width="460"
      @close="focusCreatedResource"
    >
      <div class=":uno: px-1 py-1 text-sm leading-6 text-gray-700">
        {{
          postCreatePrompt.tab === "snippets"
            ? "是否继续创建代码片段？如果不继续，页面会切换到刚创建的代码片段。"
            : "是否继续创建转换规则？如果不继续，页面会切换到刚创建的转换规则。"
        }}
      </div>

      <template #footer>
        <VSpace>
          <VButton @click="focusCreatedResource">
            {{
              postCreatePrompt.tab === "snippets" ? "查看刚创建的代码片段" : "查看刚创建的转换规则"
            }}
          </VButton>
          <VButton type="secondary" @click="keepCreatingCreatedResource">继续创建</VButton>
        </VSpace>
      </template>
    </VModal>

    <VPageHeader title="页面转换器">
      <template #icon><PluginLogoIcon /></template>
    </VPageHeader>

    <div class=":uno: m-0 md:m-4">
      <input
        ref="bulkImportFileInput"
        accept="application/json,.json"
        class=":uno: hidden"
        type="file"
        @change="handleBulkImportFileChange"
      />

      <VCard class="transformer-view-card" :body-class="['transformer-view-card-body']">
        <div class="transformer-workspace :uno: relative h-full">
          <div
            :aria-hidden="isCompactDrawerModalActive ? 'true' : undefined"
            :inert="isCompactDrawerModalActive ? true : undefined"
            class="mobile-drawer-toolbar"
          >
            <VButton
              :id="mobileLeftDrawerToggleId"
              :aria-controls="mobileLeftDrawerId"
              :aria-expanded="mobileDrawer.showLeftDrawer.value"
              :aria-haspopup="mobileDrawer.isMobileViewport.value ? 'dialog' : undefined"
              :aria-label="`${mobileDrawer.showLeftDrawer.value ? '关闭' : '打开'}${mobileLeftDrawerLabel}`"
              size="sm"
              type="secondary"
              @click="mobileDrawer.toggleDrawer('left')"
            >
              {{ mobileLeftDrawerLabel }}
            </VButton>
            <span class="mobile-drawer-toolbar-title">{{ mobileMainLabel }}</span>
            <VButton
              :id="mobileRightDrawerToggleId"
              :aria-controls="mobileRightDrawerId"
              :aria-expanded="mobileDrawer.showRightDrawer.value"
              :aria-haspopup="mobileDrawer.isMobileViewport.value ? 'dialog' : undefined"
              :aria-label="`${mobileDrawer.showRightDrawer.value ? '关闭' : '打开'}${mobileRightDrawerLabel}`"
              size="sm"
              type="secondary"
              @click="mobileDrawer.toggleDrawer('right')"
            >
              {{ mobileRightDrawerLabel }}
            </VButton>
          </div>

          <div class="transformer-layout :uno: flex h-full divide-x divide-gray-100">
            <button
              :aria-hidden="mobileDrawer.backdropVisible.value ? undefined : 'true'"
              aria-label="关闭当前侧边栏"
              :class="{
                'mobile-drawer-backdrop-visible': mobileDrawer.backdropVisible.value,
              }"
              class="mobile-drawer-backdrop"
              :tabindex="mobileDrawer.backdropVisible.value ? 0 : -1"
              type="button"
              @click="closeMobileDrawer()"
            />

            <MobileDrawerPanel
              :compact="mobileDrawer.isMobileViewport.value"
              :description-id="mobileLeftDrawerDescriptionId"
              :drawer-id="mobileLeftDrawerId"
              :open="mobileDrawer.showLeftDrawer.value"
              side="left"
              :title="mobileLeftDrawerLabel"
              :title-id="mobileLeftDrawerTitleId"
              @close="closeMobileDrawer"
              @dragover.capture="handleLeftPaneDragOver"
              @dragleave.capture="handleLeftPaneDragLeave"
              @drop.capture="handleLeftPaneDropCapture"
            >
              <div
                aria-label="资源类型"
                class=":uno: sticky top-0 z-10 flex h-12 shrink-0 items-center gap-4 border-b bg-white px-4"
                role="tablist"
              >
                <button
                  v-for="tab in TAB_DEFINITIONS.map((tab) => ({
                    ...tab,
                    count: tab.key === 'snippets' ? snippets.length : rules.length,
                  }))"
                  :key="tab.key"
                  :id="tabButtonId(tab.key)"
                  :aria-controls="tabPanelId(tab.key)"
                  :aria-selected="activeTab === tab.key"
                  :class="
                    activeTab === tab.key
                      ? ':uno: text-primary'
                      : ':uno: text-gray-500 hover:text-gray-800'
                  "
                  :data-transformer-tab="tab.key"
                  class=":uno: text-sm font-medium whitespace-nowrap transition-colors"
                  role="tab"
                  :tabindex="activeTab === tab.key ? 0 : -1"
                  type="button"
                  @click="handleTabSwitch(tab.key as ActiveTab)"
                  @keydown="handleTabKeydown($event, tab.key as ActiveTab)"
                >
                  {{ tab.label }}
                  <span class=":uno: ml-0.5 text-xs">({{ tab.count }})</span>
                </button>
              </div>

              <DragAutoScrollOverlay
                :active="leftPaneAutoScroll.isDragActive.value"
                :active-direction="leftPaneAutoScroll.activeDirection.value"
                :can-scroll-up="leftPaneAutoScroll.canScrollUp.value"
                :can-scroll-down="leftPaneAutoScroll.canScrollDown.value"
                :top-zone-height="48 + LEFT_PANE_EDGE_OVERLAP_PX"
                :bottom-zone-height="48 + LEFT_PANE_EDGE_OVERLAP_PX"
              />

              <VLoading v-if="loading" />

              <ResourceList
                v-else-if="activeTab === 'snippets'"
                ref="resourceListRef"
                :bulk-mode="isBulkMode"
                :bulk-selected-ids="bulkSelectionState.bulkSnippetIds.value"
                :items="snippets"
                list-label="代码片段列表"
                :panel-id="tabPanelId('snippets')"
                :reorderable="!isBulkMode"
                :selected-id="currentSelectedId('snippets')"
                :stretch="true"
                :tab-labelledby="tabButtonId('snippets')"
                empty-text="暂无代码片段"
                @drag-state-change="leftPaneAutoScroll.setDragActive"
                @reorder="reorderSnippet"
                @scroll-container="leftPaneAutoScroll.handleContainerScroll"
                @create="handleOpenCreateModal('snippets')"
                @select="handleSnippetSelect"
                @toggle-bulk-all="handleBulkToggleAll"
                @toggle-bulk-item="handleBulkItemToggle"
              />

              <ResourceList
                v-else
                ref="resourceListRef"
                :bulk-mode="isBulkMode"
                :bulk-selected-ids="bulkSelectionState.bulkRuleIds.value"
                :items="rules"
                list-label="转换规则列表"
                :panel-id="tabPanelId('rules')"
                :reorderable="!isBulkMode"
                :selected-id="currentSelectedId('rules')"
                :stretch="true"
                :tab-labelledby="tabButtonId('rules')"
                empty-text="暂无转换规则"
                @drag-state-change="leftPaneAutoScroll.setDragActive"
                @reorder="reorderRule"
                @scroll-container="leftPaneAutoScroll.handleContainerScroll"
                @create="handleOpenCreateModal('rules')"
                @select="handleRuleSelect"
                @toggle-bulk-all="handleBulkToggleAll"
                @toggle-bulk-item="handleBulkItemToggle"
              >
                <template #meta="{ item: r }">
                  <span class=":uno: text-xs text-gray-500">{{ rulePreview(r) }}</span>
                  <span
                    class=":uno: mt-0.5 block overflow-hidden text-xs text-ellipsis whitespace-nowrap text-gray-400"
                    :title="matchRuleSummary(r.matchRule)"
                  >
                    {{ matchRuleSummary(r.matchRule) }}
                  </span>
                </template>
              </ResourceList>

              <div
                v-if="!isBulkMode"
                class="transformer-mobile-pane-footer :uno: flex shrink-0 items-center justify-center border-t bg-white px-4 py-2"
              >
                <VButton size="sm" type="secondary" @click="handleOpenCreateModal(activeTab)">
                  {{ activeTab === "snippets" ? "新建代码片段" : "新建转换规则" }}
                </VButton>
              </div>
            </MobileDrawerPanel>

            <div
              :aria-hidden="isCompactDrawerModalActive ? 'true' : undefined"
              :inert="isCompactDrawerModalActive ? true : undefined"
              class=":uno: main flex h-full flex-none flex-col overflow-hidden"
            >
              <BulkOperationPanel
                v-if="isBulkMode"
                :can-disable="canBulkDisable"
                :can-enable="canBulkEnable"
                :processing="processingBulk"
                :selected-count="bulkSelectionState.currentBulkSelectionCount.value"
                :tab="activeTab"
                @delete="handleBulkDelete"
                @disable="handleBulkDisable"
                @enable="handleBulkEnable"
                @exit="exitBulkMode"
                @export="handleBulkExport"
                @import="openBulkImportSourceModal"
              />
              <SnippetEditor
                v-else-if="activeTab === 'snippets'"
                :dirty="editDirty"
                :empty-state-layout="editorEmptyStateLayout"
                :saving="savingEditor"
                :snippet="editSnippet"
                @delete="confirmDeleteSnippet"
                @save="saveSnippet"
                @field-change="editDirty = true"
                @toggle-bulk-mode="enterBulkMode"
                @toggle-enabled="toggleSnippetEnabled"
                @update:snippet="editSnippet = $event"
              />
              <RuleEditor
                v-else
                :dirty="editDirty"
                :empty-state-layout="editorEmptyStateLayout"
                :rule="editRule"
                :saving="savingEditor"
                :snippets="snippets"
                @delete="confirmDeleteRule"
                @save="saveRule"
                @field-change="editDirty = true"
                @toggle-bulk-mode="enterBulkMode"
                @toggle-enabled="toggleRuleEnabled"
                @update:rule="editRule = $event"
              />
            </div>

            <MobileDrawerPanel
              :compact="mobileDrawer.isMobileViewport.value"
              :description-id="mobileRightDrawerDescriptionId"
              :drawer-id="mobileRightDrawerId"
              :open="mobileDrawer.showRightDrawer.value"
              side="right"
              :title="mobileRightDrawerLabel"
              :title-id="mobileRightDrawerTitleId"
              @close="closeMobileDrawer"
            >
              <BulkModeSidePanel
                v-if="isBulkMode"
                :selected-count="bulkSelectionState.currentBulkSelectionCount.value"
                :tab="activeTab"
              />
              <RelationPanel
                v-else
                :mode="activeTab"
                :rules-using-snippet="rulesUsingSnippet"
                :selected-rule-id="currentSelectedId('rules')"
                :selected-rule-position="editRule?.position ?? null"
                :selected-snippet-id="currentSelectedId('snippets')"
                :snippets-in-rule="snippetsInRule"
                @jump-to-rule="jumpToRule"
                @jump-to-snippet="jumpToSnippet"
              />
              <div
                v-if="!isBulkMode"
                aria-hidden="true"
                class="transformer-mobile-pane-footer transformer-mobile-pane-footer-spacer :uno: border-t bg-white"
              />
            </MobileDrawerPanel>
          </div>
        </div>
      </VCard>
    </div>
  </div>
</template>
