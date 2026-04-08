<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { VButton } from '@halo-dev/components'
import type { CodeSnippetReadModel, InjectionRuleEditorDraft, MatchRuleSource } from '@/types'
import { MODE_OPTIONS, POSITION_OPTIONS } from '@/types'
import {
  cloneMatchRule,
  cloneMatchRuleSource,
  getDomRulePerformanceWarning,
  makeRuleTreeSource,
} from '@/views/composables/matchRule'
import ItemPicker from './ItemPicker.vue'
import EditorToolbar from './EditorToolbar.vue'
import EditorFooter from './EditorFooter.vue'
import ExportContentModal from './ExportContentModal.vue'
import FormField from './FormField.vue'
import MatchRuleEditor from './MatchRuleEditor.vue'
import RuleRuntimeOrderField from './RuleRuntimeOrderField.vue'
import { sortSelectedFirst } from '@/views/composables/util.ts'
import { updateSelectByWheel } from '@/views/composables/selectWheel.ts'
import FieldUndoButton from './FieldUndoButton.vue'
import { useFieldUndo } from '@/views/composables/useFieldUndo.ts'
import {
  buildRuleTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from '@/views/composables/transfer.ts'
import DragAutoScrollOverlay from './DragAutoScrollOverlay.vue'
import { useDragAutoScroll } from '@/views/composables/useDragAutoScroll'

const props = defineProps<{
  rule: InjectionRuleEditorDraft | null
  snippets: CodeSnippetReadModel[]
  selectedSnippetIds: string[]
  saving: boolean
  dirty: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
  (e: 'delete'): void
  (e: 'toggle-enabled'): void
  (e: 'toggle-bulk-mode'): void
  (e: 'replace-snippet-ids', snippetIds: string[]): void
  (e: 'toggle-snippet', snippetId: string): void
  (e: 'field-change'): void
  (e: 'update:rule', rule: InjectionRuleEditorDraft): void
}>()

const sortedSnippets = computed(() => sortSelectedFirst(props.snippets, props.selectedSnippetIds))
const pendingRule = ref<InjectionRuleEditorDraft | null>(null)
const currentRule = computed(() => pendingRule.value ?? props.rule)
const exportFallback = ref<TransferFileDraft | null>(null)
const editorScrollContainer = ref<HTMLElement | null>(null)
const editorToolbarShell = ref<HTMLElement | null>(null)
const editorFooterShell = ref<HTMLElement | null>(null)
const matchRuleEditorRef = ref<{
  commitPendingDrop: () => void
} | null>(null)
const RULE_EDITOR_EDGE_OVERLAP_PX = 8
const dragOverlayTopHeight = ref(48 + RULE_EDITOR_EDGE_OVERLAP_PX)
const dragOverlayBottomHeight = ref(52 + RULE_EDITOR_EDGE_OVERLAP_PX)
const autoScroll = useDragAutoScroll(editorScrollContainer)
let dragOverlayResizeObserver: ResizeObserver | null = null

const needsTarget = computed(() => currentRule.value?.mode === 'SELECTOR')
const needsSnippets = computed(() => currentRule.value?.position !== 'REMOVE')
const needsWrapMarker = computed(() => currentRule.value?.position !== 'REMOVE')
const matchDraft = ref('')
const matchInitialValue = ref('')
const matchFieldError = computed(() => {
  if (!needsTarget.value || !currentRule.value) {
    return null
  }
  return matchDraft.value.trim() ? null : '请填写匹配内容'
})
const performanceWarning = computed(() =>
  currentRule.value ? getDomRulePerformanceWarning(currentRule.value) : null,
)
const undo = useFieldUndo()
type UndoableRuleField =
  | 'name'
  | 'description'
  | 'mode'
  | 'match'
  | 'position'
  | 'wrapMarker'
  | 'runtimeOrder'
  | 'matchRule'
  | 'snippetIds'

function updateDragOverlayHeights() {
  dragOverlayTopHeight.value =
    (editorToolbarShell.value?.offsetHeight ?? 48) + RULE_EDITOR_EDGE_OVERLAP_PX
  dragOverlayBottomHeight.value =
    (editorFooterShell.value?.offsetHeight ?? 52) + RULE_EDITOR_EDGE_OVERLAP_PX
}

function handleEditorContainerDragOver(event: DragEvent) {
  autoScroll.handleContainerDragOver(event, {
    topZoneHeight: dragOverlayTopHeight.value,
    bottomZoneHeight: dragOverlayBottomHeight.value,
  })
}

function handleEditorContainerDragLeave(event: DragEvent) {
  autoScroll.handleContainerDragLeave(event)
}

function handleEditorContainerDropCapture() {
  matchRuleEditorRef.value?.commitPendingDrop()
}

function observeDragOverlayShell() {
  dragOverlayResizeObserver?.disconnect()
  if (typeof ResizeObserver === 'undefined') {
    updateDragOverlayHeights()
    return
  }

  dragOverlayResizeObserver = new ResizeObserver(() => {
    updateDragOverlayHeights()
  })

  if (editorToolbarShell.value) {
    dragOverlayResizeObserver.observe(editorToolbarShell.value)
  }
  if (editorFooterShell.value) {
    dragOverlayResizeObserver.observe(editorFooterShell.value)
  }

  updateDragOverlayHeights()
}

watch(
  () => props.rule,
  () => {
    pendingRule.value = null
  },
)

watch(
  () => currentRule.value?.id ?? null,
  async () => {
    await nextTick()
    observeDragOverlayShell()
  },
  { immediate: true },
)

watch(
  () => currentRule.value?.match,
  (value) => {
    matchDraft.value = value ?? ''
  },
  { immediate: true },
)

watch(
  () => [currentRule.value?.id, props.dirty],
  () => {
    if (!currentRule.value || props.dirty) {
      return
    }
    undo.resetBaseline({
      name: currentRule.value.name,
      description: currentRule.value.description,
      mode: currentRule.value.mode,
      match: currentRule.value.match,
      position: {
        position: currentRule.value.position,
        wrapMarker: currentRule.value.wrapMarker,
      },
      wrapMarker: currentRule.value.wrapMarker,
      runtimeOrder: currentRule.value.runtimeOrder,
      matchRule: {
        matchRule: cloneMatchRule(currentRule.value.matchRule),
        matchRuleSource: cloneCurrentMatchRuleSource(),
      },
      snippetIds: props.selectedSnippetIds,
    })
  },
  { immediate: true },
)

/**
 * why: 正常编辑需要记录撤销历史，但撤销/重置本身不能再次入栈，
 * 否则会把“当前值”重新压回历史，导致连续撤销时来回跳动。
 */
function updateField<K extends keyof InjectionRuleEditorDraft>(
  key: K,
  value: InjectionRuleEditorDraft[K],
  options?: { trackHistory?: boolean },
) {
  if (!currentRule.value) return
  const trackHistory = options?.trackHistory ?? true
  const next = { ...currentRule.value, [key]: value }
  if (trackHistory && key !== 'matchRule') {
    undo.trackChange(
      key === 'position' ? 'position' : String(key),
      key === 'position'
        ? {
            position: currentRule.value.position,
            wrapMarker: currentRule.value.wrapMarker,
          }
        : currentRule.value[key],
      key === 'position'
        ? {
            position: next.position,
            wrapMarker: next.wrapMarker,
          }
        : next[key],
    )
  }
  pendingRule.value = next
  emit('update:rule', next)
  emit('field-change')
}

function updateRuleSnapshot(next: InjectionRuleEditorDraft) {
  pendingRule.value = next
  emit('update:rule', next)
  emit('field-change')
}

function currentMatchRuleSnapshot() {
  return {
    matchRule: currentRule.value ? cloneMatchRule(currentRule.value.matchRule) : undefined,
    matchRuleSource: currentRule.value ? cloneCurrentMatchRuleSource() : undefined,
  }
}

function cloneCurrentMatchRuleSource() {
  if (!currentRule.value) {
    return makeRuleTreeSource({ type: 'GROUP', negate: false, operator: 'AND', children: [] })
  }
  return cloneMatchRuleSource(
    currentRule.value.matchRuleSource ?? makeRuleTreeSource(currentRule.value.matchRule),
  )
}

function updateMatchRuleField(patch: Partial<InjectionRuleEditorDraft>) {
  if (!currentRule.value) return
  const previous = currentMatchRuleSnapshot()
  const next = {
    ...currentRule.value,
    ...patch,
  }
  const after = {
    matchRule: cloneMatchRule(next.matchRule),
    matchRuleSource: cloneMatchRuleSource(
      next.matchRuleSource ?? makeRuleTreeSource(next.matchRule),
    ),
  }
  undo.trackChange('matchRule', previous, after)
  updateRuleSnapshot(next)
}

function handleToggleSnippet(snippetId: string) {
  const previous = props.selectedSnippetIds
  const next = previous.includes(snippetId)
    ? previous.filter((id) => id !== snippetId)
    : [...previous, snippetId]
  undo.trackChange('snippetIds', previous, next)
  emit('toggle-snippet', snippetId)
}

function beginMatchEdit() {
  matchInitialValue.value = currentRule.value?.match ?? matchDraft.value
}

function handleMatchInput(event: Event) {
  const value = (event.target as HTMLInputElement).value
  matchDraft.value = value
  updateField('match', value as InjectionRuleEditorDraft['match'], { trackHistory: false })
}

function commitMatchDraft() {
  if (matchInitialValue.value === matchDraft.value) {
    return
  }
  undo.trackChange('match', matchInitialValue.value, matchDraft.value)
}

function canUndo(field: UndoableRuleField) {
  if (!currentRule.value) return false
  return undo.isModified(field, resolveUndoFieldCurrentValue(field))
}

function undoField(field: UndoableRuleField) {
  if (!currentRule.value) return
  const previous = undo.undo(field, resolveUndoFieldCurrentValue(field))
  if (previous === undefined) return
  applyUndoFieldState(field, previous)
}

function applyUndoFieldState(field: UndoableRuleField, value: unknown) {
  if (!currentRule.value) return

  if (field === 'position') {
    const snapshot = value as {
      position: InjectionRuleEditorDraft['position']
      wrapMarker: boolean
    }
    updateRuleSnapshot({
      ...currentRule.value,
      position: snapshot.position,
      wrapMarker: snapshot.wrapMarker,
    })
    return
  }

  if (field === 'matchRule') {
    const snapshot = value as {
      matchRule: InjectionRuleEditorDraft['matchRule']
      matchRuleSource?: MatchRuleSource
    }
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleSource: snapshot.matchRuleSource ?? makeRuleTreeSource(snapshot.matchRule),
    }
    updateRuleSnapshot(next)
    return
  }

  if (field === 'snippetIds') {
    emit('replace-snippet-ids', value as string[])
    emit('field-change')
    return
  }

  updateField(field, value as InjectionRuleEditorDraft[typeof field], { trackHistory: false })
}

function resolveUndoFieldCurrentValue(field: UndoableRuleField) {
  if (!currentRule.value) return undefined
  return field === 'position'
    ? {
        position: currentRule.value.position,
        wrapMarker: currentRule.value.wrapMarker,
      }
    : field === 'matchRule'
      ? {
          matchRule: cloneMatchRule(currentRule.value.matchRule),
          matchRuleSource: cloneCurrentMatchRuleSource(),
        }
      : field === 'snippetIds'
        ? props.selectedSnippetIds
        : currentRule.value[field]
}

function resetField(field: UndoableRuleField) {
  if (!currentRule.value) return
  const baseline = undo.reset(field)
  if (baseline === undefined) return

  if (field === 'position') {
    const snapshot = baseline as {
      position: InjectionRuleEditorDraft['position']
      wrapMarker: boolean
    }
    updateRuleSnapshot({
      ...currentRule.value,
      position: snapshot.position,
      wrapMarker: snapshot.wrapMarker,
    })
    return
  }

  if (field === 'matchRule') {
    const snapshot = baseline as {
      matchRule: InjectionRuleEditorDraft['matchRule']
      matchRuleSource?: MatchRuleSource
    }
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleSource: snapshot.matchRuleSource ?? makeRuleTreeSource(snapshot.matchRule),
    }
    updateRuleSnapshot(next)
    return
  }

  if (field === 'snippetIds') {
    emit('replace-snippet-ids', baseline as string[])
    emit('field-change')
    return
  }

  updateField(field, baseline as InjectionRuleEditorDraft[typeof field], {
    trackHistory: false,
  })
}

async function exportRule() {
  if (!currentRule.value) {
    return
  }
  exportFallback.value = createTransferFileDraft(
    buildRuleTransfer(currentRule.value),
    currentRule.value.name || currentRule.value.id || 'injection-rule',
  )
}

onMounted(async () => {
  await nextTick()
  observeDragOverlayShell()
})

onBeforeUnmount(() => {
  dragOverlayResizeObserver?.disconnect()
})
</script>

<template>
  <div
    class=":uno: relative h-full flex flex-col injector-editor-container"
    @dragover.capture="handleEditorContainerDragOver"
    @dragleave.capture="handleEditorContainerDragLeave"
    @drop.capture="handleEditorContainerDropCapture"
  >
    <ExportContentModal
      v-if="exportFallback"
      :content="exportFallback.content"
      :file-name="exportFallback.fileName"
      @close="exportFallback = null"
    />

    <div ref="editorToolbarShell">
      <EditorToolbar
        :enabled="currentRule?.enabled"
        :id-text="currentRule?.id"
        :show-export="!!currentRule"
        :show-actions="!!currentRule"
        :show-default-actions="true"
        :title="currentRule ? '编辑规则' : '注入规则'"
        @delete="emit('delete')"
        @export="exportRule"
        @toggle-enabled="emit('toggle-enabled')"
      >
        <template #actions>
          <VButton size="sm" @click="emit('toggle-bulk-mode')">批量操作</VButton>
        </template>
      </EditorToolbar>
    </div>

    <DragAutoScrollOverlay
      :active="autoScroll.isDragActive.value"
      :active-direction="autoScroll.activeDirection.value"
      :can-scroll-up="autoScroll.canScrollUp.value"
      :can-scroll-down="autoScroll.canScrollDown.value"
      :top-zone-height="dragOverlayTopHeight"
      :bottom-zone-height="dragOverlayBottomHeight"
    />

    <div v-if="!currentRule" class=":uno: flex flex-1 items-center justify-center">
      <span class=":uno: text-sm text-gray-500">从左侧选择规则进行编辑</span>
    </div>

    <form v-else class=":uno: min-h-0 flex flex-1 flex-col" @submit.prevent="emit('save')">
      <div
        ref="editorScrollContainer"
        class=":uno: relative min-h-0 flex-1 overflow-y-auto px-4 py-4 space-y-4"
        @scroll="autoScroll.handleContainerScroll"
      >
        <FormField label="名称">
          <template v-if="canUndo('name')" #actions>
            <FieldUndoButton @reset="resetField('name')" @undo="undoField('name')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="currentRule.name"
              class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
              placeholder="不填默认为 ID"
              @change="updateField('name', ($event.target as HTMLInputElement).value)"
            />
          </template>
        </FormField>

        <FormField label="描述">
          <template v-if="canUndo('description')" #actions>
            <FieldUndoButton @reset="resetField('description')" @undo="undoField('description')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="currentRule.description"
              class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
              placeholder="说明此规则的用途"
              @change="updateField('description', ($event.target as HTMLInputElement).value)"
            />
          </template>
        </FormField>

        <FormField label="运行顺序">
          <template v-if="canUndo('runtimeOrder')" #actions>
            <FieldUndoButton
              @reset="resetField('runtimeOrder')"
              @undo="undoField('runtimeOrder')"
            />
          </template>
          <template #default="{ inputId, labelId }">
            <div :id="inputId" :aria-labelledby="labelId">
              <RuleRuntimeOrderField
                :model-value="currentRule.runtimeOrder"
                @update:model-value="updateField('runtimeOrder', $event)"
              />
            </div>
          </template>
        </FormField>

        <FormField label="注入模式" required>
          <template v-if="canUndo('mode')" #actions>
            <FieldUndoButton @reset="resetField('mode')" @undo="undoField('mode')" />
          </template>
          <template #default="{ inputId }">
            <select
              :id="inputId"
              :value="currentRule.mode"
              class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none bg-white"
              @wheel="updateSelectByWheel"
              @change="
                updateField(
                  'mode',
                  ($event.target as HTMLSelectElement).value as InjectionRuleEditorDraft['mode'],
                )
              "
            >
              <option v-for="o in MODE_OPTIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
          </template>
        </FormField>

        <template v-if="needsTarget">
          <FormField :invalid="!!matchFieldError" label="CSS 选择器" required>
            <template v-if="canUndo('match')" #actions>
              <FieldUndoButton @reset="resetField('match')" @undo="undoField('match')" />
            </template>
            <template #default="{ inputId }">
              <div class=":uno: space-y-1">
                <input
                  :id="inputId"
                  :aria-invalid="!!matchFieldError"
                  placeholder="例如：#main-content、.post-card、div[data-role=banner]"
                  :value="matchDraft"
                  :class="
                    matchFieldError
                      ? ':uno: border-red-300 bg-red-50/40 focus:border-red-500'
                      : ':uno: border-gray-200 focus:border-primary'
                  "
                  class=":uno: w-full rounded-md border px-3 py-1.5 text-sm font-mono focus:outline-none"
                  @focus="beginMatchEdit"
                  @input="handleMatchInput"
                  @change="commitMatchDraft"
                />
                <p
                  v-if="matchFieldError"
                  aria-live="polite"
                  class=":uno: text-xs text-red-500"
                  role="alert"
                >
                  {{ matchFieldError }}
                </p>
              </div>
            </template>
          </FormField>

          <FormField label="插入位置">
            <template v-if="canUndo('position')" #actions>
              <FieldUndoButton @reset="resetField('position')" @undo="undoField('position')" />
            </template>
            <template #default="{ inputId }">
              <select
                :id="inputId"
                :value="currentRule.position"
                class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none bg-white"
                @wheel="updateSelectByWheel"
                @change="
                  updateField(
                    'position',
                    ($event.target as HTMLSelectElement)
                      .value as InjectionRuleEditorDraft['position'],
                  )
                "
              >
                <option v-for="o in POSITION_OPTIONS" :key="o.value" :value="o.value">
                  {{ o.label }}
                </option>
              </select>
            </template>
          </FormField>
        </template>

        <FormField v-if="needsWrapMarker">
          <template v-if="canUndo('wrapMarker')" #actions>
            <FieldUndoButton @reset="resetField('wrapMarker')" @undo="undoField('wrapMarker')" />
          </template>
          <template #default="{ inputId }">
            <label class=":uno: inline-flex items-center gap-2 text-sm text-gray-700">
              <input
                :id="inputId"
                :checked="currentRule.wrapMarker"
                type="checkbox"
                @change="updateField('wrapMarker', ($event.target as HTMLInputElement).checked)"
              />
              输出注释标记
            </label>
          </template>
        </FormField>

        <FormField v-if="needsSnippets" label="关联代码块">
          <template #actions>
            <div class=":uno: flex items-center gap-2">
              <span aria-live="polite" class=":uno: text-xs text-gray-400">
                {{ selectedSnippetIds.length }} 个已选
              </span>
              <FieldUndoButton
                v-if="canUndo('snippetIds')"
                @reset="resetField('snippetIds')"
                @undo="undoField('snippetIds')"
              />
            </div>
          </template>
          <template #default>
            <ItemPicker
              :items="sortedSnippets"
              label="关联代码块选择列表"
              :selected-ids="selectedSnippetIds"
              empty-text="暂无代码块, 请先创建"
              @toggle="handleToggleSnippet"
            />
          </template>
        </FormField>

        <FormField label="匹配规则" required>
          <template v-if="canUndo('matchRule')" #actions>
            <FieldUndoButton @reset="resetField('matchRule')" @undo="undoField('matchRule')" />
          </template>
          <template #default="{ inputId, labelId }">
            <div :id="inputId" :aria-labelledby="labelId">
              <MatchRuleEditor
                ref="matchRuleEditorRef"
                :model-value="currentRule.matchRule"
                :source="currentRule.matchRuleSource"
                @change="emit('field-change')"
                @drag-state-change="autoScroll.setDragActive"
                @update:state="updateMatchRuleField($event)"
              />
            </div>
            <div
              v-if="performanceWarning"
              aria-live="polite"
              class=":uno: mt-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
            >
              {{ performanceWarning }}
            </div>
          </template>
        </FormField>
      </div>

      <div ref="editorFooterShell">
        <EditorFooter :dirty="dirty" :saving="saving" @save="emit('save')" />
      </div>
    </form>
  </div>
</template>
