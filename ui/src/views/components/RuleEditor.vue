<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import type {
  CodeSnippet,
  EditableInjectionRule,
  InjectionRule,
  MatchRuleEditorMode,
} from '@/types'
import { MODE_OPTIONS, POSITION_OPTIONS } from '@/types'
import {
  formatMatchRule,
  getDomRulePerformanceWarning,
  persistMatchRuleEditorState,
} from '@/views/composables/matchRule'
import ItemPicker from './ItemPicker.vue'
import EditorToolbar from './EditorToolbar.vue'
import EditorFooter from './EditorFooter.vue'
import ExportJsonFallbackModal from './ExportJsonFallbackModal.vue'
import FormField from './FormField.vue'
import MatchRuleEditor from './MatchRuleEditor.vue'
import { sortSelectedFirst } from '@/views/composables/util.ts'
import { updateSelectByWheel } from '@/views/composables/selectWheel.ts'
import FieldUndoButton from './FieldUndoButton.vue'
import { useFieldUndo } from '@/views/composables/useFieldUndo.ts'
import {
  buildRuleTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from '@/views/composables/transfer.ts'

const props = defineProps<{
  rule: EditableInjectionRule | null
  snippets: CodeSnippet[]
  selectedSnippetIds: string[]
  saving: boolean
  dirty: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
  (e: 'delete'): void
  (e: 'export'): void
  (e: 'toggle-enabled'): void
  (e: 'replace-snippet-ids', snippetIds: string[]): void
  (e: 'toggle-snippet', snippetId: string): void
  (e: 'field-change'): void
  (e: 'update:rule', rule: EditableInjectionRule): void
}>()

const sortedSnippets = computed(() => sortSelectedFirst(props.snippets, props.selectedSnippetIds))
const pendingRule = ref<EditableInjectionRule | null>(null)
const currentRule = computed(() => pendingRule.value ?? props.rule)
const exportFallback = ref<TransferFileDraft | null>(null)

const needsTarget = computed(
  () => currentRule.value?.mode === 'ID' || currentRule.value?.mode === 'SELECTOR',
)
const needsSnippets = computed(() => currentRule.value?.position !== 'REMOVE')
const needsWrapMarker = computed(() => currentRule.value?.position !== 'REMOVE')
const matchFieldError = computed(() => {
  if (!needsTarget.value || !currentRule.value) {
    return null
  }
  return currentRule.value.match.trim() ? null : '请填写匹配内容'
})
const performanceWarning = computed(() =>
  currentRule.value ? getDomRulePerformanceWarning(currentRule.value) : null,
)
const undo = useFieldUndo()

watch(
  () => props.rule,
  () => {
    pendingRule.value = null
  },
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
      matchRule: {
        matchRule: currentRule.value.matchRule,
        matchRuleDraft: currentRule.value.matchRuleDraft ?? '',
        matchRuleEditorMode: currentRule.value.matchRuleEditorMode ?? 'SIMPLE',
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
function updateField<K extends keyof InjectionRule>(
  key: K,
  value: InjectionRule[K],
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

function updateRuleSnapshot(next: EditableInjectionRule) {
  pendingRule.value = next
  emit('update:rule', next)
  emit('field-change')
}

function currentMatchRuleSnapshot() {
  const formattedDraft = currentRule.value ? formatMatchRule(currentRule.value.matchRule) : ''
  const currentDraft = currentRule.value?.matchRuleDraft?.trim()
  const hasCustomDraft = !!currentDraft && currentDraft !== formattedDraft

  return {
    matchRule: currentRule.value?.matchRule,
    matchRuleDraft: hasCustomDraft ? currentDraft : '',
    matchRuleEditorMode: hasCustomDraft ? (currentRule.value?.matchRuleEditorMode ?? 'JSON') : '',
  }
}

function updateMatchRuleField(patch: Partial<EditableInjectionRule>) {
  if (!currentRule.value) return
  const previous = currentMatchRuleSnapshot()
  const next = {
    ...currentRule.value,
    ...patch,
  }
  const formattedDraft = formatMatchRule(next.matchRule)
  const nextDraft = next.matchRuleDraft?.trim()
  const hasCustomDraft = !!nextDraft && nextDraft !== formattedDraft
  const after = {
    matchRule: next.matchRule,
    matchRuleDraft: hasCustomDraft ? nextDraft : '',
    matchRuleEditorMode: hasCustomDraft ? (next.matchRuleEditorMode ?? 'JSON') : '',
  }
  undo.trackChange('matchRule', previous, after)
  persistMatchRuleEditorState({
    id: next.id,
    matchRuleDraft: next.matchRuleDraft,
    matchRuleEditorMode: next.matchRuleEditorMode,
  })
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

function canUndo(
  field:
    | 'name'
    | 'description'
    | 'mode'
    | 'match'
    | 'position'
    | 'wrapMarker'
    | 'matchRule'
    | 'snippetIds',
) {
  if (!currentRule.value) return false
  const current =
    field === 'position'
      ? {
          position: currentRule.value.position,
          wrapMarker: currentRule.value.wrapMarker,
        }
      : field === 'matchRule'
        ? {
            matchRule: currentRule.value.matchRule,
            matchRuleDraft: currentRule.value.matchRuleDraft ?? '',
            matchRuleEditorMode: currentRule.value.matchRuleEditorMode ?? 'SIMPLE',
          }
        : field === 'snippetIds'
          ? props.selectedSnippetIds
          : currentRule.value[field]
  return undo.isModified(field, current)
}

function undoField(
  field:
    | 'name'
    | 'description'
    | 'mode'
    | 'match'
    | 'position'
    | 'wrapMarker'
    | 'matchRule'
    | 'snippetIds',
) {
  if (!currentRule.value) return
  const current =
    field === 'position'
      ? {
          position: currentRule.value.position,
          wrapMarker: currentRule.value.wrapMarker,
        }
      : field === 'matchRule'
        ? {
            matchRule: currentRule.value.matchRule,
            matchRuleDraft: currentRule.value.matchRuleDraft ?? '',
            matchRuleEditorMode: currentRule.value.matchRuleEditorMode ?? 'SIMPLE',
          }
        : field === 'snippetIds'
          ? props.selectedSnippetIds
          : currentRule.value[field]
  const previous = undo.undo(field, current)
  if (previous === undefined) return

  if (field === 'position') {
    const snapshot = previous as { position: InjectionRule['position']; wrapMarker: boolean }
    updateRuleSnapshot({
      ...currentRule.value,
      position: snapshot.position,
      wrapMarker: snapshot.wrapMarker,
    })
    return
  }

  if (field === 'matchRule') {
    const snapshot = previous as {
      matchRule: InjectionRule['matchRule']
      matchRuleDraft: string
      matchRuleEditorMode: MatchRuleEditorMode | ''
    }
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleDraft: snapshot.matchRuleDraft || formatMatchRule(snapshot.matchRule),
      matchRuleEditorMode:
        snapshot.matchRuleDraft && snapshot.matchRuleEditorMode
          ? snapshot.matchRuleEditorMode
          : currentRule.value.matchRuleEditorMode,
    }
    persistMatchRuleEditorState(next)
    updateRuleSnapshot(next)
    return
  }

  if (field === 'snippetIds') {
    emit('replace-snippet-ids', previous as string[])
    emit('field-change')
    return
  }

  updateField(field, previous as InjectionRule[typeof field], { trackHistory: false })
}

function resetField(
  field:
    | 'name'
    | 'description'
    | 'mode'
    | 'match'
    | 'position'
    | 'wrapMarker'
    | 'matchRule'
    | 'snippetIds',
) {
  if (!currentRule.value) return
  const baseline = undo.reset(field)
  if (baseline === undefined) return

  if (field === 'position') {
    const snapshot = baseline as { position: InjectionRule['position']; wrapMarker: boolean }
    updateRuleSnapshot({
      ...currentRule.value,
      position: snapshot.position,
      wrapMarker: snapshot.wrapMarker,
    })
    return
  }

  if (field === 'matchRule') {
    const snapshot = baseline as {
      matchRule: InjectionRule['matchRule']
      matchRuleDraft: string
      matchRuleEditorMode: MatchRuleEditorMode | ''
    }
    const next = {
      ...currentRule.value,
      matchRule: snapshot.matchRule,
      matchRuleDraft: snapshot.matchRuleDraft || formatMatchRule(snapshot.matchRule),
      matchRuleEditorMode:
        snapshot.matchRuleDraft && snapshot.matchRuleEditorMode
          ? snapshot.matchRuleEditorMode
          : currentRule.value.matchRuleEditorMode,
    }
    persistMatchRuleEditorState(next)
    updateRuleSnapshot(next)
    return
  }

  if (field === 'snippetIds') {
    emit('replace-snippet-ids', baseline as string[])
    emit('field-change')
    return
  }

  updateField(field, baseline as InjectionRule[typeof field], { trackHistory: false })
}

async function exportRule() {
  if (!currentRule.value) {
    return
  }
  exportFallback.value = createTransferFileDraft(
    buildRuleTransfer(currentRule.value),
    currentRule.value.name || currentRule.value.id || 'injection-rule',
  )
  emit('export')
}
</script>

<template>
  <div class=":uno: h-full flex flex-col injector-editor-container">
    <ExportJsonFallbackModal
      v-if="exportFallback"
      :content="exportFallback.content"
      :file-name="exportFallback.fileName"
      @close="exportFallback = null"
    />

    <EditorToolbar
      :enabled="currentRule?.enabled"
      :show-export="!!currentRule"
      :show-actions="!!currentRule"
      :title="currentRule ? '编辑规则' : '注入规则'"
      @delete="emit('delete')"
      @export="exportRule"
      @toggle-enabled="emit('toggle-enabled')"
    />

    <div v-if="!currentRule" class=":uno: flex flex-1 items-center justify-center">
      <span class=":uno: text-sm text-gray-500">从左侧选择规则进行编辑</span>
    </div>

    <form v-else class=":uno: min-h-0 flex flex-1 flex-col" @submit.prevent="emit('save')">
      <div class=":uno: min-h-0 flex-1 overflow-y-auto px-4 py-4 space-y-4">
        <FormField v-slot="{ inputId }" label="ID">
          <input
            :id="inputId"
            :value="currentRule.id"
            class=":uno: w-full rounded-md border border-gray-200 bg-gray-50 px-3 py-1.5 text-xs font-mono text-gray-400 cursor-default"
            readonly
          />
        </FormField>

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
                  ($event.target as HTMLSelectElement).value as InjectionRule['mode'],
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
          <FormField :label="currentRule.mode === 'SELECTOR' ? 'CSS 选择器' : '元素 ID'" required>
            <template v-if="canUndo('match')" #actions>
              <FieldUndoButton @reset="resetField('match')" @undo="undoField('match')" />
            </template>
            <template #default="{ inputId }">
              <div class=":uno: space-y-1">
                <input
                  :id="inputId"
                  :aria-invalid="!!matchFieldError"
                  :placeholder="
                    currentRule.mode === 'SELECTOR' ? 'div[class=content]' : 'main-content'
                  "
                  :value="currentRule.match"
                  :class="
                    matchFieldError
                      ? ':uno: border-red-300 focus:border-red-500'
                      : ':uno: border-gray-200 focus:border-primary'
                  "
                  class=":uno: w-full rounded-md border px-3 py-1.5 text-sm font-mono focus:outline-none"
                  @change="updateField('match', ($event.target as HTMLInputElement).value)"
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
                    ($event.target as HTMLSelectElement).value as InjectionRule['position'],
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
                :draft="currentRule.matchRuleDraft"
                :editor-mode="currentRule.matchRuleEditorMode"
                :model-value="currentRule.matchRule"
                @change="emit('field-change')"
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

      <EditorFooter :dirty="dirty" :saving="saving" @save="emit('save')" />
    </form>
  </div>
</template>
