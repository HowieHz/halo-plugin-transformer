<script lang="ts" setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { IconPlug, VButton, VCard, VLoading, VPageHeader } from '@halo-dev/components'

import type { ActiveTab } from '@/types'
import { useInjectorData } from './composables/useInjectorData.ts'
import { rulePreview } from './composables/util.ts'
import { matchRuleExpression } from './composables/matchRule.ts'

import ItemListV from './components/ItemListV.vue'
import SnippetEditor from './components/SnippetEditor.vue'
import RuleEditor from './components/RuleEditor.vue'
import RelationPanel from './components/RelationPanel.vue'
import SnippetFormModal from './components/SnippetFormModal.vue'
import RuleFormModal from './components/RuleFormModal.vue'

const activeTab = ref<ActiveTab>('snippets')
const route = useRoute()
const router = useRouter()

const showSnippetModal = ref(false)
const showRuleModal = ref(false)
const syncingQuery = ref(false)

const {
  loading,
  creating,
  savingEditor,
  snippets,
  rules,
  selectedSnippetId,
  selectedRuleId,
  editSnippet,
  editSnippetRuleIds,
  editRule,
  editRuleSnippetIds,
  editDirty,
  rulesUsingSnippet,
  snippetsInRule,
  fetchAll,
  addSnippet,
  saveSnippet,
  toggleSnippetEnabled,
  confirmDeleteSnippet,
  toggleRuleInSnippetEditor,
  reorderSnippet,
  addRule,
  saveRule,
  toggleRuleEnabled,
  confirmDeleteRule,
  toggleSnippetInRuleEditor,
  reorderRule,
} = useInjectorData()

onMounted(fetchAll)

function normalizeTab(tab: unknown): ActiveTab {
  return tab === 'rules' ? 'rules' : 'snippets'
}

function normalizeAction(action: unknown): 'create' | null {
  return action === 'create' ? 'create' : null
}

function currentSelectedId(tab: ActiveTab) {
  return tab === 'snippets' ? selectedSnippetId.value : selectedRuleId.value
}

function currentAction(tab: ActiveTab) {
  if (tab === 'snippets' && showSnippetModal.value) return 'create'
  if (tab === 'rules' && showRuleModal.value) return 'create'
  return null
}

function applyQueryState() {
  const nextTab = normalizeTab(route.query.tab)
  const nextId = typeof route.query.id === 'string' ? route.query.id : null
  const nextAction = normalizeAction(route.query.action)

  activeTab.value = nextTab
  showSnippetModal.value = nextTab === 'snippets' && nextAction === 'create'
  showRuleModal.value = nextTab === 'rules' && nextAction === 'create'

  if (nextAction === 'create') {
    if (nextTab === 'snippets') {
      selectedSnippetId.value = null
    } else {
      selectedRuleId.value = null
    }
    return
  }

  if (nextTab === 'snippets') {
    selectedSnippetId.value = nextId
  } else {
    selectedRuleId.value = nextId
  }
}

function syncQueryState() {
  const tab = activeTab.value
  const id = currentSelectedId(tab)
  const action = currentAction(tab)
  const currentTab = normalizeTab(route.query.tab)
  const currentId = typeof route.query.id === 'string' ? route.query.id : null
  const currentActionValue = normalizeAction(route.query.action)

  if (currentTab === tab && currentId === id && currentActionValue === action) {
    return
  }

  const nextQuery: LocationQueryRaw = {
    ...route.query,
    tab,
  }

  if (action) {
    nextQuery.action = action
    delete nextQuery.id
  } else {
    delete nextQuery.action
    if (id) {
      nextQuery.id = id
    } else {
      delete nextQuery.id
    }
  }

  syncingQuery.value = true
  void router
    .replace({
      query: nextQuery,
    })
    .finally(() => {
      syncingQuery.value = false
    })
}

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

watch(
  () => [route.query.tab, route.query.id, route.query.action],
  () => {
    if (syncingQuery.value) return
    applyQueryState()
  },
  { immediate: true },
)

watch([activeTab, selectedSnippetId, selectedRuleId, showSnippetModal, showRuleModal], () => {
  if (syncingQuery.value) return
  syncQueryState()
})

watch([snippets, rules], () => {
  validateSelection()
})

async function handleAddSnippet(...args: Parameters<typeof addSnippet>) {
  const id = await addSnippet(...args)
  if (id) showSnippetModal.value = false
}

async function handleAddRule(...args: Parameters<typeof addRule>) {
  const id = await addRule(...args)
  if (id) showRuleModal.value = false
}

function jumpToRule(id: string) {
  activeTab.value = 'rules'
  showSnippetModal.value = false
  showRuleModal.value = false
  selectedRuleId.value = id
}

function jumpToSnippet(id: string) {
  activeTab.value = 'snippets'
  showSnippetModal.value = false
  showRuleModal.value = false
  selectedSnippetId.value = id
}
</script>

<template>
  <div id="injector-view">
    <SnippetFormModal
      v-if="showSnippetModal"
      :rules="rules"
      :saving="creating"
      @close="closeSnippetModal"
      @submit="handleAddSnippet"
    />
    <RuleFormModal
      v-if="showRuleModal"
      :saving="creating"
      :snippets="snippets"
      @close="closeRuleModal"
      @submit="handleAddRule"
    />

    <VPageHeader title="Injector">
      <template #icon><IconPlug /></template>
    </VPageHeader>

    <div class=":uno: m-0 md:m-4">
      <VCard :body-class="['injector-view-card-body']" style="height: calc(100vh - 5.5rem)">
        <div class=":uno: h-full flex divide-x divide-gray-100">
          <div class=":uno: aside h-full flex-none flex flex-col overflow-hidden">
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
                @click="activeTab = tab.key as ActiveTab"
              >
                {{ tab.label }}
                <span class=":uno: ml-0.5 text-xs">({{ tab.count }})</span>
              </button>
            </div>

            <VLoading v-if="loading" />

            <ItemListV
              v-else-if="activeTab === 'snippets'"
              :items="snippets"
              :reorderable="true"
              :selected-id="selectedSnippetId"
              empty-text="暂无代码块"
              @reorder="reorderSnippet"
              @create="openCreateModal('snippets')"
              @select="selectedSnippetId = $event"
            />

            <ItemListV
              v-else
              :items="rules"
              :reorderable="true"
              :selected-id="selectedRuleId"
              :stretch="true"
              empty-text="暂无注入规则"
              @reorder="reorderRule"
              @create="openCreateModal('rules')"
              @select="selectedRuleId = $event"
            >
              <template #meta="{ item: r }">
                <span class=":uno: text-xs text-gray-500">{{ rulePreview(r) }}</span>
                <span
                  class=":uno: mt-0.5 block overflow-hidden text-ellipsis whitespace-nowrap text-xs text-gray-400"
                  :title="matchRuleExpression(r.matchRule)"
                >
                  {{ matchRuleExpression(r.matchRule) }}
                </span>
              </template>
            </ItemListV>

            <div class=":uno: h-12 flex items-center justify-center border-t bg-white shrink-0">
              <VButton size="sm" type="secondary" @click="openCreateModal(activeTab)">
                {{ activeTab === 'snippets' ? '新建代码块' : '新建注入规则' }}
              </VButton>
            </div>
          </div>

          <div class=":uno: main h-full flex-none flex flex-col overflow-hidden">
            <SnippetEditor
              v-if="activeTab === 'snippets'"
              :dirty="editDirty"
              :rules="rules"
              :saving="savingEditor"
              :selected-rule-ids="editSnippetRuleIds"
              :snippet="editSnippet"
              @delete="confirmDeleteSnippet"
              @save="saveSnippet"
              @field-change="editDirty = true"
              @replace-rule-ids="editSnippetRuleIds = $event"
              @toggle-enabled="toggleSnippetEnabled"
              @toggle-rule="toggleRuleInSnippetEditor"
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
              @toggle-enabled="toggleRuleEnabled"
              @toggle-snippet="toggleSnippetInRuleEditor"
              @update:rule="editRule = $event"
            />
          </div>

          <div class=":uno: aside h-full flex-none flex flex-col overflow-hidden">
            <RelationPanel
              :mode="activeTab"
              :rules-using-snippet="rulesUsingSnippet"
              :selected-rule-id="selectedRuleId"
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
