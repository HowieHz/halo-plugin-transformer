<script lang="ts" setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
  type LocationQueryRaw,
} from 'vue-router'
import {
  IconPlug,
  Toast,
  VButton,
  VCard,
  VLoading,
  VModal,
  VPageHeader,
  VSpace,
} from '@halo-dev/components'

import type { ActiveTab, CodeSnippetEditorDraft, InjectionRuleEditorDraft } from '@/types'
import { useInjectorData } from './composables/useInjectorData.ts'
import { rulePreview } from './composables/util.ts'
import { matchRuleSummary } from './composables/matchRule.ts'
import { hydrateRuleEditorDraft } from './composables/ruleDraft'
import { hydrateSnippetEditorDraft } from './composables/snippetDraft'
import { useBulkSelectionState } from './composables/useBulkSelectionState'
import {
  buildRuleBatchTransfer,
  buildSnippetBatchTransfer,
  createTransferFileDraft,
  parseRuleBatchTransfer,
  parseSnippetBatchTransfer,
  type TransferFileDraft,
} from './composables/transfer'

import ResourceList from './components/ResourceList.vue'
import DragAutoScrollOverlay from './components/DragAutoScrollOverlay.vue'
import SnippetEditor from './components/SnippetEditor.vue'
import RuleEditor from './components/RuleEditor.vue'
import RelationPanel from './components/RelationPanel.vue'
import SnippetFormModal from './components/SnippetFormModal.vue'
import RuleFormModal from './components/RuleFormModal.vue'
import ImportSourceModal from './components/ImportSourceModal.vue'
import ExportContentModal from './components/ExportContentModal.vue'
import BulkOperationPanel from './components/BulkOperationPanel.vue'
import BulkModeSidePanel from './components/BulkModeSidePanel.vue'
import BulkImportOptionsModal from './components/BulkImportOptionsModal.vue'
import BulkImportResultModal from './components/BulkImportResultModal.vue'
import { useDragAutoScroll } from './composables/useDragAutoScroll'
import { useLeaveConfirmation } from './composables/useLeaveConfirmation'
import {
  buildInjectorRouteQuery,
  isSameInjectorRouteState,
  parseInjectorRouteState,
  type InjectorRouteState,
} from './composables/injectorRouteState'

const activeTab = ref<ActiveTab>('snippets')
const route = useRoute()
const router = useRouter()

const showSnippetModal = ref(false)
const showRuleModal = ref(false)
const syncingQuery = ref(false)
const queryStateHydrated = ref(false)
const bulkImportSourceVisible = ref(false)
const bulkImportOptionsVisible = ref(false)
const bulkImportResult = ref<null | { count: number; tab: ActiveTab }>(null)
const bulkExportFallback = ref<TransferFileDraft | null>(null)
const bulkImportFileInput = ref<HTMLInputElement | null>(null)
const pendingBulkImport = ref<
  | { tab: 'snippets'; items: CodeSnippetEditorDraft[] }
  | { tab: 'rules'; items: InjectionRuleEditorDraft[] }
  | null
>(null)
const snippetFormRef = ref<{
  reset: () => void
  hasUnsavedChanges: () => boolean
  getValidationError: () => string | null
  getSubmitPayload: () => { snippet: CodeSnippetEditorDraft }
} | null>(null)
const ruleFormRef = ref<{
  reset: () => void
  hasUnsavedChanges: () => boolean
  getValidationError: () => string | null
  getSubmitPayload: () => { rule: InjectionRuleEditorDraft; snippetIds: string[] }
} | null>(null)
const resourceListRef = ref<{
  getScrollContainer: () => HTMLElement | null
  commitPendingDrop: () => void
} | null>(null)
const resourceListScrollContainer = ref<HTMLElement | null>(null)
const leftPaneAutoScroll = useDragAutoScroll(resourceListScrollContainer)
const LEFT_PANE_EDGE_OVERLAP_PX = 5

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
  editRuleSnippetIds,
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
  toggleSnippetInRuleEditor,
  reorderRule,
} = useInjectorData(activeTab)

const bulkSelectionState = useBulkSelectionState({
  activeTab,
  snippets,
  rules,
})
const selectedBulkResources = computed(() => {
  if (activeTab.value === 'snippets') {
    const selectedIds = new Set(bulkSelectionState.currentBulkIds.value)
    return snippets.value.filter((item) => selectedIds.has(item.id))
  }
  const selectedIds = new Set(bulkSelectionState.currentBulkIds.value)
  return rules.value.filter((item) => selectedIds.has(item.id))
})
const canBulkEnable = computed(() => selectedBulkResources.value.some((item) => !item.enabled))
const canBulkDisable = computed(() => selectedBulkResources.value.some((item) => item.enabled))

onMounted(fetchAll)

const postCreatePrompt = ref<null | { tab: ActiveTab; id: string }>(null)

function handleLeftPaneDragOver(event: DragEvent) {
  leftPaneAutoScroll.handleContainerDragOver(event, {
    topZoneHeight: 48 + LEFT_PANE_EDGE_OVERLAP_PX,
    bottomZoneHeight: 48 + LEFT_PANE_EDGE_OVERLAP_PX,
  })
}

function handleLeftPaneDragLeave(event: DragEvent) {
  leftPaneAutoScroll.handleContainerDragLeave(event)
}

function handleLeftPaneDropCapture() {
  resourceListRef.value?.commitPendingDrop()
}

function currentSelectedId(tab: ActiveTab) {
  if (bulkSelectionState.isBulkMode.value) {
    return null
  }
  return tab === 'snippets' ? selectedSnippetId.value : selectedRuleId.value
}

function currentAction(tab: ActiveTab) {
  if (bulkSelectionState.isBulkMode.value) return null
  if (tab === 'snippets' && showSnippetModal.value) return 'create'
  if (tab === 'rules' && showRuleModal.value) return 'create'
  return null
}

function currentLocalRouteState(): InjectorRouteState {
  return {
    tab: activeTab.value,
    selectedId: currentSelectedId(activeTab.value),
    action: currentAction(activeTab.value),
    viewMode: bulkSelectionState.isBulkMode.value ? 'bulk' : 'single',
  }
}

function applyRouteState(nextState: InjectorRouteState) {
  activeTab.value = nextState.tab
  showSnippetModal.value =
    nextState.viewMode !== 'bulk' && nextState.tab === 'snippets' && nextState.action === 'create'
  showRuleModal.value =
    nextState.viewMode !== 'bulk' && nextState.tab === 'rules' && nextState.action === 'create'

  if (nextState.viewMode === 'bulk') {
    bulkSelectionState.enterBulkMode()
    selectedSnippetId.value = null
    selectedRuleId.value = null
    queryStateHydrated.value = true
    return
  }

  bulkSelectionState.exitBulkMode()

  if (nextState.action === 'create') {
    if (nextState.tab === 'snippets') {
      selectedSnippetId.value = null
    } else {
      selectedRuleId.value = null
    }
    queryStateHydrated.value = true
    return
  }

  if (nextState.tab === 'snippets') {
    selectedSnippetId.value = nextState.selectedId
  } else {
    selectedRuleId.value = nextState.selectedId
  }
  queryStateHydrated.value = true
}

function applyQueryState() {
  applyRouteState(parseInjectorRouteState(route.query))
}

function syncQueryState() {
  const nextState = currentLocalRouteState()
  const currentState = parseInjectorRouteState(route.query)

  if (isSameInjectorRouteState(currentState, nextState)) {
    return
  }

  const nextQuery: LocationQueryRaw = buildInjectorRouteQuery(route.query, nextState)

  syncingQuery.value = true
  void router
    .replace({
      query: nextQuery,
    })
    .finally(() => {
      syncingQuery.value = false
    })
}

/**
 * why: 编辑保护不能只拦住“组件内部按钮”，浏览器前进/后退、外部 query 变更也必须走同一条离开确认路径；
 * 否则 URL 仍会成为绕过草稿保护的后门。
 */
onBeforeRouteUpdate((to) => {
  if (syncingQuery.value || !queryStateHydrated.value) {
    return true
  }

  const nextState = parseInjectorRouteState(to.query)
  if (isSameInjectorRouteState(currentLocalRouteState(), nextState)) {
    return true
  }
  return requestNavigationLeave()
})

/**
 * why: 只拦住页内 query 变化还不够；如果整页导航能直接离开，
 * 草稿一样会静默丢失，因此 route leave 也必须复用同一套离开确认。
 */
onBeforeRouteLeave(() => {
  if (syncingQuery.value || !queryStateHydrated.value) {
    return true
  }
  return requestNavigationLeave()
})

function validateSelection() {
  const hasSnippet =
    !!selectedSnippetId.value && snippets.value.some((item) => item.id === selectedSnippetId.value)
  const hasRule =
    !!selectedRuleId.value && rules.value.some((item) => item.id === selectedRuleId.value)

  if (selectedSnippetId.value && !hasSnippet) {
    selectedSnippetId.value = null
  }
  if (selectedRuleId.value && !hasRule) {
    selectedRuleId.value = null
  }
}

function openCreateModal(tab: ActiveTab) {
  activeTab.value = tab
  bulkSelectionState.exitBulkMode()
  if (tab === 'snippets') {
    selectedSnippetId.value = null
    showSnippetModal.value = true
    showRuleModal.value = false
  } else {
    selectedRuleId.value = null
    showRuleModal.value = true
    showSnippetModal.value = false
  }
}

function closeSnippetModal() {
  showSnippetModal.value = false
}

function closeRuleModal() {
  showRuleModal.value = false
}

function currentCreateController() {
  if (showSnippetModal.value) return snippetFormRef.value
  if (showRuleModal.value) return ruleFormRef.value
  return null
}

function hasUnsavedCreateChanges() {
  return currentCreateController()?.hasUnsavedChanges() ?? false
}

function hasUnsavedEditorChanges() {
  return editDirty.value && !!(activeTab.value === 'snippets' ? editSnippet.value : editRule.value)
}

function hasUnsavedChanges() {
  return hasUnsavedCreateChanges() || hasUnsavedEditorChanges()
}

function currentValidationError() {
  if (showSnippetModal.value) {
    return snippetFormRef.value?.getValidationError() ?? null
  }
  if (showRuleModal.value) {
    return ruleFormRef.value?.getValidationError() ?? null
  }
  return activeTab.value === 'snippets' ? snippetEditorError.value : ruleEditorError.value
}

function discardCurrentChanges() {
  if (showSnippetModal.value) {
    snippetFormRef.value?.reset()
    return
  }
  if (showRuleModal.value) {
    ruleFormRef.value?.reset()
    return
  }
  if (activeTab.value === 'snippets') {
    discardSnippetEdit()
    return
  }
  discardRuleEdit()
}

function closePostCreatePrompt() {
  postCreatePrompt.value = null
}

async function saveCurrentChanges() {
  let saved = false
  if (showSnippetModal.value) {
    const payload = snippetFormRef.value?.getSubmitPayload()
    saved = payload ? !!(await addSnippet(payload.snippet)) : false
    if (saved) {
      showSnippetModal.value = false
    }
  } else if (showRuleModal.value) {
    const payload = ruleFormRef.value?.getSubmitPayload()
    saved = payload ? !!(await addRule(payload.rule, payload.snippetIds)) : false
    if (saved) {
      showRuleModal.value = false
    }
  } else {
    saved = activeTab.value === 'snippets' ? await saveSnippet() : await saveRule()
  }
  if (!saved) {
    return false
  }
  return true
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
})

function resetCreateForm(tab: ActiveTab) {
  if (tab === 'snippets') {
    snippetFormRef.value?.reset()
    return
  }
  ruleFormRef.value?.reset()
}

function keepCreatingCreatedResource() {
  if (!postCreatePrompt.value) {
    return
  }
  resetCreateForm(postCreatePrompt.value.tab)
  closePostCreatePrompt()
}

function focusCreatedResource() {
  const prompt = postCreatePrompt.value
  if (!prompt) {
    return
  }
  resetCreateForm(prompt.tab)
  if (prompt.tab === 'snippets') {
    showSnippetModal.value = false
    activeTab.value = 'snippets'
    selectedSnippetId.value = prompt.id
  } else {
    showRuleModal.value = false
    activeTab.value = 'rules'
    selectedRuleId.value = prompt.id
  }
  closePostCreatePrompt()
}

function handleTabSwitch(tab: ActiveTab) {
  if (activeTab.value === tab) {
    return
  }
  requestEditorLeave(() => {
    activeTab.value = tab
  })
}

function handleSnippetSelect(id: string) {
  if (activeTab.value === 'snippets' && selectedSnippetId.value === id) {
    return
  }
  requestEditorLeave(() => {
    selectedSnippetId.value = id
  })
}

function handleRuleSelect(id: string) {
  if (activeTab.value === 'rules' && selectedRuleId.value === id) {
    return
  }
  requestEditorLeave(() => {
    selectedRuleId.value = id
  })
}

function handleOpenCreateModal(tab: ActiveTab) {
  requestEditorLeave(() => {
    openCreateModal(tab)
  })
}

function enterBulkMode() {
  requestEditorLeave(() => {
    showSnippetModal.value = false
    showRuleModal.value = false
    if (activeTab.value === 'snippets') {
      selectedSnippetId.value = null
    } else {
      selectedRuleId.value = null
    }
    bulkSelectionState.enterBulkMode()
  })
}

function exitBulkMode() {
  bulkSelectionState.exitBulkMode()
}

function handleBulkItemToggle(id: string) {
  bulkSelectionState.toggleCurrentBulkItem(id)
}

function handleBulkToggleAll() {
  bulkSelectionState.toggleCurrentBulkSelectAll()
}

function openBulkImportSourceModal() {
  bulkImportSourceVisible.value = true
}

function closeBulkImportFlow() {
  bulkImportSourceVisible.value = false
  bulkImportOptionsVisible.value = false
  bulkImportResult.value = null
  pendingBulkImport.value = null
}

async function handleBulkImportFromClipboard() {
  let text = ''
  try {
    text = await navigator.clipboard.readText()
  } catch {
    Toast.error('读取剪贴板失败，请检查浏览器权限后重试')
    return
  }

  if (!text.trim()) {
    Toast.warning('剪贴板里没有可导入的 JSON')
    return
  }

  try {
    applyBulkImportSource(text)
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '导入失败')
  }
}

async function handleBulkImportFromFile() {
  bulkImportSourceVisible.value = false
  await nextTick()
  bulkImportFileInput.value?.click()
}

async function handleBulkImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }

  try {
    applyBulkImportSource(await file.text())
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    input.value = ''
  }
}

function applyBulkImportSource(raw: string) {
  pendingBulkImport.value =
    activeTab.value === 'snippets'
      ? { tab: 'snippets', items: parseSnippetBatchTransfer(raw) }
      : { tab: 'rules', items: parseRuleBatchTransfer(raw) }
  bulkImportSourceVisible.value = false
  bulkImportOptionsVisible.value = true
}

async function submitBulkImport(enabled: boolean) {
  if (!pendingBulkImport.value) {
    return
  }

  const importedIds =
    pendingBulkImport.value.tab === 'snippets'
      ? await importSnippets(pendingBulkImport.value.items, enabled)
      : await importRules(pendingBulkImport.value.items, enabled)

  bulkImportOptionsVisible.value = false

  if (importedIds.length > 0) {
    if (pendingBulkImport.value.tab === activeTab.value) {
      bulkSelectionState.appendCurrentBulkSelection(importedIds)
    }
    bulkImportResult.value = {
      count: importedIds.length,
      tab: pendingBulkImport.value.tab,
    }
  } else {
    pendingBulkImport.value = null
  }
}

function continueBulkImport() {
  bulkImportResult.value = null
  pendingBulkImport.value = null
  bulkImportSourceVisible.value = true
}

function handleBulkExport() {
  if (activeTab.value === 'snippets') {
    const selectedItems = snippets.value.filter((item) =>
      bulkSelectionState.currentBulkIds.value.includes(item.id),
    )
    if (!selectedItems.length) {
      return
    }
    bulkExportFallback.value = createTransferFileDraft(
      buildSnippetBatchTransfer(selectedItems.map(hydrateSnippetEditorDraft)),
      'injector-snippets-batch',
    )
    return
  }

  const selectedItems = rules.value.filter((item) =>
    bulkSelectionState.currentBulkIds.value.includes(item.id),
  )
  if (!selectedItems.length) {
    return
  }
  bulkExportFallback.value = createTransferFileDraft(
    buildRuleBatchTransfer(selectedItems.map(hydrateRuleEditorDraft)),
    'injector-rules-batch',
  )
}

function handleBulkEnable() {
  if (activeTab.value === 'snippets') {
    void setSnippetsEnabled(bulkSelectionState.currentBulkIds.value, true)
    return
  }
  void setRulesEnabled(bulkSelectionState.currentBulkIds.value, true)
}

function handleBulkDisable() {
  if (activeTab.value === 'snippets') {
    void setSnippetsEnabled(bulkSelectionState.currentBulkIds.value, false)
    return
  }
  void setRulesEnabled(bulkSelectionState.currentBulkIds.value, false)
}

function handleBulkDelete() {
  if (activeTab.value === 'snippets') {
    confirmDeleteSnippets(bulkSelectionState.currentBulkIds.value)
    return
  }
  confirmDeleteRules(bulkSelectionState.currentBulkIds.value)
}

watch(
  () => [route.query.tab, route.query.id, route.query.action, route.query.mode],
  () => {
    applyQueryState()
  },
  { immediate: true },
)

watch(
  [
    activeTab,
    selectedSnippetId,
    selectedRuleId,
    showSnippetModal,
    showRuleModal,
    bulkSelectionState.viewMode,
  ],
  () => {
    if (!queryStateHydrated.value || syncingQuery.value) return
    syncQueryState()
  },
)

watch([snippets, rules], () => {
  validateSelection()
})

watch([activeTab, loading], async () => {
  await nextTick()
  resourceListScrollContainer.value = resourceListRef.value?.getScrollContainer() ?? null
})

async function handleAddSnippet(...args: Parameters<typeof addSnippet>) {
  const id = await addSnippet(...args)
  if (id) {
    if (bulkSelectionState.isBulkMode.value) {
      bulkSelectionState.appendCurrentBulkSelection([id])
      return
    }
    postCreatePrompt.value = { tab: 'snippets', id }
  }
}

async function handleAddRule(...args: Parameters<typeof addRule>) {
  const id = await addRule(...args)
  if (id) {
    if (bulkSelectionState.isBulkMode.value) {
      bulkSelectionState.appendCurrentBulkSelection([id])
      return
    }
    postCreatePrompt.value = { tab: 'rules', id }
  }
}

function jumpToRule(id: string) {
  requestEditorLeave(() => {
    activeTab.value = 'rules'
    showSnippetModal.value = false
    showRuleModal.value = false
    selectedRuleId.value = id
  })
}

function jumpToSnippet(id: string) {
  requestEditorLeave(() => {
    activeTab.value = 'snippets'
    showSnippetModal.value = false
    showRuleModal.value = false
    selectedSnippetId.value = id
  })
}
</script>

<template>
  <div id="injector-view">
    <ExportContentModal
      v-if="bulkExportFallback"
      :content="bulkExportFallback.content"
      :file-name="bulkExportFallback.fileName"
      @close="bulkExportFallback = null"
    />
    <ImportSourceModal
      v-if="bulkImportSourceVisible"
      :resource-label="activeTab === 'snippets' ? '批量代码块' : '批量注入规则'"
      @close="closeBulkImportFlow"
      @import-from-clipboard="handleBulkImportFromClipboard"
      @import-from-file="handleBulkImportFromFile"
    />
    <BulkImportOptionsModal
      v-if="bulkImportOptionsVisible && pendingBulkImport"
      :item-count="pendingBulkImport.items.length"
      :resource-label="pendingBulkImport.tab === 'snippets' ? '代码块' : '注入规则'"
      :submitting="processingBulk"
      @close="closeBulkImportFlow"
      @submit="submitBulkImport"
    />
    <BulkImportResultModal
      v-if="bulkImportResult"
      :imported-count="bulkImportResult.count"
      :resource-label="bulkImportResult.tab === 'snippets' ? '代码块' : '注入规则'"
      @close="closeBulkImportFlow"
      @continue="continueBulkImport"
    />
    <SnippetFormModal
      v-if="showSnippetModal"
      ref="snippetFormRef"
      :saving="creating"
      @close="requestEditorLeave(closeSnippetModal)"
      @submit="handleAddSnippet"
    />
    <RuleFormModal
      v-if="showRuleModal"
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
            {{ savingEditor ? '保存中...' : '保存' }}
          </VButton>
        </VSpace>
      </template>
    </VModal>

    <VModal
      v-if="postCreatePrompt"
      :title="postCreatePrompt.tab === 'snippets' ? '代码块已创建' : '注入规则已创建'"
      :width="460"
      @close="focusCreatedResource"
    >
      <div class=":uno: px-1 py-1 text-sm leading-6 text-gray-700">
        {{
          postCreatePrompt.tab === 'snippets'
            ? '是否继续创建代码块？如果不继续，页面会切换到刚创建的代码块。'
            : '是否继续创建注入规则？如果不继续，页面会切换到刚创建的注入规则。'
        }}
      </div>

      <template #footer>
        <VSpace>
          <VButton @click="focusCreatedResource">
            {{
              postCreatePrompt.tab === 'snippets' ? '查看刚创建的代码块' : '查看刚创建的注入规则'
            }}
          </VButton>
          <VButton type="secondary" @click="keepCreatingCreatedResource">继续创建</VButton>
        </VSpace>
      </template>
    </VModal>

    <VPageHeader title="Injector">
      <template #icon><IconPlug /></template>
    </VPageHeader>

    <div class=":uno: m-0 md:m-4">
      <input
        ref="bulkImportFileInput"
        accept="application/json,.json"
        class=":uno: hidden"
        type="file"
        @change="handleBulkImportFileChange"
      />

      <VCard :body-class="['injector-view-card-body']" style="height: calc(100vh - 5.5rem)">
        <div class=":uno: h-full flex divide-x divide-gray-100">
          <div
            class=":uno: relative aside h-full flex-none flex flex-col overflow-hidden"
            @dragover.capture="handleLeftPaneDragOver"
            @dragleave.capture="handleLeftPaneDragLeave"
            @drop.capture="handleLeftPaneDropCapture"
          >
            <div
              class=":uno: sticky top-0 z-10 h-12 flex items-center gap-4 border-b bg-white px-4 shrink-0"
            >
              <button
                v-for="tab in [
                  { key: 'snippets', label: '代码块', count: snippets.length },
                  { key: 'rules', label: '注入规则', count: rules.length },
                ]"
                :key="tab.key"
                :class="
                  activeTab === tab.key
                    ? ':uno: text-primary'
                    : ':uno: text-gray-500 hover:text-gray-800'
                "
                class=":uno: text-sm font-medium transition-colors whitespace-nowrap"
                @click="handleTabSwitch(tab.key as ActiveTab)"
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
              :bulk-mode="bulkSelectionState.isBulkMode.value"
              :bulk-selected-ids="bulkSelectionState.bulkSnippetIds.value"
              :items="snippets"
              list-label="代码块列表"
              :reorderable="!bulkSelectionState.isBulkMode.value"
              :selected-id="selectedSnippetId"
              :stretch="true"
              empty-text="暂无代码块"
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
              :bulk-mode="bulkSelectionState.isBulkMode.value"
              :bulk-selected-ids="bulkSelectionState.bulkRuleIds.value"
              :items="rules"
              list-label="注入规则列表"
              :reorderable="!bulkSelectionState.isBulkMode.value"
              :selected-id="selectedRuleId"
              :stretch="true"
              empty-text="暂无注入规则"
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
                  class=":uno: mt-0.5 block overflow-hidden text-ellipsis whitespace-nowrap text-xs text-gray-400"
                  :title="matchRuleSummary(r.matchRule)"
                >
                  {{ matchRuleSummary(r.matchRule) }}
                </span>
              </template>
            </ResourceList>

            <div
              v-if="!bulkSelectionState.isBulkMode.value"
              class=":uno: h-12 flex items-center justify-center border-t bg-white shrink-0"
            >
              <VButton size="sm" type="secondary" @click="handleOpenCreateModal(activeTab)">
                {{ activeTab === 'snippets' ? '新建代码块' : '新建注入规则' }}
              </VButton>
            </div>
          </div>

          <div class=":uno: main h-full flex-none flex flex-col overflow-hidden">
            <BulkOperationPanel
              v-if="bulkSelectionState.isBulkMode.value"
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
              :rule="editRule"
              :saving="savingEditor"
              :selected-snippet-ids="editRuleSnippetIds"
              :snippets="snippets"
              @delete="confirmDeleteRule"
              @save="saveRule"
              @field-change="editDirty = true"
              @replace-snippet-ids="editRuleSnippetIds = $event"
              @toggle-bulk-mode="enterBulkMode"
              @toggle-enabled="toggleRuleEnabled"
              @toggle-snippet="toggleSnippetInRuleEditor"
              @update:rule="editRule = $event"
            />
          </div>

          <div class=":uno: aside h-full flex-none flex flex-col overflow-hidden">
            <BulkModeSidePanel
              v-if="bulkSelectionState.isBulkMode.value"
              :selected-count="bulkSelectionState.currentBulkSelectionCount.value"
              :tab="activeTab"
            />
            <RelationPanel
              v-else
              :mode="activeTab"
              :rules-using-snippet="rulesUsingSnippet"
              :selected-rule-id="selectedRuleId"
              :selected-rule-position="editRule?.position ?? null"
              :selected-snippet-id="selectedSnippetId"
              :snippets-in-rule="snippetsInRule"
              @jump-to-rule="jumpToRule"
              @jump-to-snippet="jumpToSnippet"
            />
          </div>
        </div>
      </VCard>
    </div>
  </div>
</template>
