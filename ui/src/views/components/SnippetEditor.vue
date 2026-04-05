<script lang="ts" setup>
import type { CodeSnippet, InjectionRule } from '@/types'
import ItemPicker from './ItemPicker.vue'
import EditorToolbar from './EditorToolbar.vue'
import EditorFooter from './EditorFooter.vue'
import ExportJsonFallbackModal from './ExportJsonFallbackModal.vue'
import FormField from './FormField.vue'
import { rulePreview, sortSelectedFirst } from '@/views/composables/util'
import FieldUndoButton from './FieldUndoButton.vue'
import { useFieldUndo } from '@/views/composables/useFieldUndo'
import { computed, ref, watch } from 'vue'
import {
  buildSnippetTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from '@/views/composables/transfer'

const props = defineProps<{
  snippet: CodeSnippet | null
  rules: InjectionRule[]
  selectedRuleIds: string[]
  saving: boolean
  dirty: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
  (e: 'delete'): void
  (e: 'export'): void
  (e: 'toggle-enabled'): void
  (e: 'replace-rule-ids', ruleIds: string[]): void
  (e: 'toggle-rule', ruleId: string): void
  (e: 'field-change'): void
  (e: 'update:snippet', snippet: CodeSnippet): void
}>()

const selectableRules = computed(() => props.rules.filter((rule) => rule.position !== 'REMOVE'))
const visibleSelectedRuleIds = computed(() =>
  props.selectedRuleIds.filter((ruleId) =>
    selectableRules.value.some((rule) => rule.id === ruleId),
  ),
)
const sortedRules = computed(() =>
  sortSelectedFirst(selectableRules.value, visibleSelectedRuleIds.value),
)
const undo = useFieldUndo()
const exportFallback = ref<TransferFileDraft | null>(null)
const codeDraft = ref('')
const codeScrollTop = ref(0)

const codeLines = computed(() => {
  const content = codeDraft.value.replace(/\r\n/g, '\n')
  return content.split('\n').length
})

const codeLineNumberStyle = computed(() => ({
  transform: `translateY(-${codeScrollTop.value}px)`,
}))
const codeFieldError = computed(() => (codeDraft.value.trim() ? null : '代码内容不能为空'))

watch(
  () => [props.snippet?.id, props.dirty],
  () => {
    if (!props.snippet || props.dirty) {
      return
    }
    undo.resetBaseline({
      name: props.snippet.name,
      description: props.snippet.description,
      ruleIds: visibleSelectedRuleIds.value,
      code: props.snippet.code,
    })
  },
  { immediate: true },
)

watch(
  () => props.snippet?.code,
  (value) => {
    codeDraft.value = value ?? ''
  },
  { immediate: true },
)

/**
 * why: 用户正常编辑时才记录撤销历史；撤销/重置写回旧值时必须跳过入栈，
 * 否则连续撤销会在新旧值之间反复横跳，无法稳定回退到更早状态。
 */
function updateField<K extends keyof CodeSnippet>(
  key: K,
  value: CodeSnippet[K],
  options?: { trackHistory?: boolean },
) {
  if (!props.snippet) return
  if (options?.trackHistory ?? true) {
    undo.trackChange(String(key), props.snippet[key], value)
  }
  emit('update:snippet', { ...props.snippet, [key]: value })
  emit('field-change')
}

function handleToggleRule(ruleId: string) {
  const previous = visibleSelectedRuleIds.value
  const next = previous.includes(ruleId)
    ? previous.filter((id) => id !== ruleId)
    : [...previous, ruleId]
  undo.trackChange('ruleIds', previous, next)
  emit('toggle-rule', ruleId)
}

function canUndo(field: 'name' | 'description' | 'ruleIds' | 'code') {
  if (!props.snippet) return false
  const current =
    field === 'ruleIds'
      ? visibleSelectedRuleIds.value
      : field === 'name'
        ? props.snippet.name
        : field === 'description'
          ? props.snippet.description
          : props.snippet.code
  return undo.isModified(field, current)
}

function undoField(field: 'name' | 'description' | 'ruleIds' | 'code') {
  if (!props.snippet) return
  const current =
    field === 'ruleIds'
      ? visibleSelectedRuleIds.value
      : field === 'name'
        ? props.snippet.name
        : field === 'description'
          ? props.snippet.description
          : props.snippet.code
  const previous = undo.undo(field, current)
  if (previous === undefined) return

  if (field === 'ruleIds') {
    emit('replace-rule-ids', previous as string[])
    return
  }

  updateField(field, previous as CodeSnippet[typeof field], { trackHistory: false })
}

function resetField(field: 'name' | 'description' | 'ruleIds' | 'code') {
  if (!props.snippet) return
  const baseline = undo.reset(field)
  if (baseline === undefined) return

  if (field === 'ruleIds') {
    emit('replace-rule-ids', baseline as string[])
    emit('field-change')
    return
  }

  updateField(field, baseline as CodeSnippet[typeof field], { trackHistory: false })
}

function syncCodeScroll(event: Event) {
  codeScrollTop.value = (event.target as HTMLTextAreaElement).scrollTop
}

function handleCodeInput(event: Event) {
  codeDraft.value = (event.target as HTMLTextAreaElement).value
}

function commitCodeDraft() {
  updateField('code', codeDraft.value)
}

async function exportSnippet() {
  if (!props.snippet) {
    return
  }
  exportFallback.value = createTransferFileDraft(
    buildSnippetTransfer(props.snippet),
    props.snippet.name || props.snippet.id || 'code-snippet',
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
      :enabled="snippet?.enabled"
      :show-export="!!snippet"
      :show-actions="!!snippet"
      :title="snippet ? '编辑代码块' : '代码块'"
      @delete="emit('delete')"
      @export="exportSnippet"
      @toggle-enabled="emit('toggle-enabled')"
    />

    <div v-if="!snippet" class=":uno: flex flex-1 items-center justify-center">
      <span class=":uno: text-sm text-gray-500">从左侧选择代码块进行编辑</span>
    </div>

    <form v-else class=":uno: min-h-0 flex flex-1 flex-col" @submit.prevent="emit('save')">
      <div class=":uno: min-h-0 flex-1 overflow-y-auto px-4 py-4 space-y-4">
        <FormField v-slot="{ inputId }" label="ID">
          <input
            :id="inputId"
            :value="snippet.id"
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
              :value="snippet.name"
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
              :value="snippet.description"
              class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
              placeholder="说明此代码块的用途"
              @change="updateField('description', ($event.target as HTMLInputElement).value)"
            />
          </template>
        </FormField>

        <FormField label="关联规则">
          <template #actions>
            <div class=":uno: flex items-center gap-2">
              <span aria-live="polite" class=":uno: text-xs text-gray-400">
                {{ visibleSelectedRuleIds.length }} 个已选
              </span>
              <FieldUndoButton
                v-if="canUndo('ruleIds')"
                @reset="resetField('ruleIds')"
                @undo="undoField('ruleIds')"
              />
            </div>
          </template>
          <template #default>
            <ItemPicker
              :items="sortedRules"
              label="关联规则选择列表"
              :preview-fn="rulePreview"
              :selected-ids="visibleSelectedRuleIds"
              empty-text="暂无规则, 请先创建"
              @toggle="handleToggleRule"
            />
          </template>
        </FormField>

        <FormField label="代码内容" required>
          <template v-if="canUndo('code')" #actions>
            <FieldUndoButton @reset="resetField('code')" @undo="undoField('code')" />
          </template>
          <template #default="{ inputId }">
            <div class=":uno: space-y-1">
              <div
                :class="
                  codeFieldError
                    ? ':uno: border-red-300 focus-within:border-red-500'
                    : ':uno: border-gray-200 focus-within:border-primary'
                "
                class=":uno: relative h-60 min-h-60 resize-y overflow-hidden rounded-md border bg-white"
              >
                <div class=":uno: relative z-1 h-full flex">
                  <div
                    aria-hidden="true"
                    class=":uno: relative h-full overflow-hidden select-none border-r border-gray-100 bg-gray-50 px-2 pt-2 pb-0 text-right text-xs text-gray-400"
                  >
                    <div :style="codeLineNumberStyle">
                      <div
                        v-for="lineNumber in codeLines"
                        :key="lineNumber"
                        class=":uno: leading-6"
                        style="height: 24px"
                      >
                        {{ lineNumber }}
                      </div>
                    </div>
                  </div>
                  <textarea
                    :id="inputId"
                    :aria-invalid="!!codeFieldError"
                    :value="codeDraft"
                    class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 pt-2 pb-0 text-sm font-mono leading-6 focus:outline-none"
                    placeholder="输入 HTML 代码"
                    spellcheck="false"
                    wrap="off"
                    @change="commitCodeDraft"
                    @input="handleCodeInput"
                    @scroll="syncCodeScroll"
                  />
                </div>
              </div>
              <p
                v-if="codeFieldError"
                aria-live="polite"
                class=":uno: text-xs text-red-500"
                role="alert"
              >
                {{ codeFieldError }}
              </p>
            </div>
          </template>
        </FormField>
      </div>

      <EditorFooter :dirty="dirty" :saving="saving" @save="emit('save')" />
    </form>
  </div>
</template>
