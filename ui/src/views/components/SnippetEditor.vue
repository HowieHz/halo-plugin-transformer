<script lang="ts" setup>
import type { CodeSnippetEditorDraft } from '@/types'
import EditorToolbar from './EditorToolbar.vue'
import EditorFooter from './EditorFooter.vue'
import ExportJsonFallbackModal from './ExportJsonFallbackModal.vue'
import FormField from './FormField.vue'
import FieldUndoButton from './FieldUndoButton.vue'
import { useFieldUndo } from '@/views/composables/useFieldUndo'
import { computed, ref, watch } from 'vue'
import {
  buildSnippetTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from '@/views/composables/transfer'

const props = defineProps<{
  snippet: CodeSnippetEditorDraft | null
  saving: boolean
  dirty: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
  (e: 'delete'): void
  (e: 'toggle-enabled'): void
  (e: 'field-change'): void
  (e: 'update:snippet', snippet: CodeSnippetEditorDraft): void
}>()

const undo = useFieldUndo()
const exportFallback = ref<TransferFileDraft | null>(null)
const codeDraft = ref('')
const codeScrollTop = ref(0)
type UndoableSnippetField = 'name' | 'description' | 'code'

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

function updateField<K extends keyof CodeSnippetEditorDraft>(
  key: K,
  value: CodeSnippetEditorDraft[K],
  options?: { trackHistory?: boolean },
) {
  if (!props.snippet) return
  if (options?.trackHistory ?? true) {
    undo.trackChange(String(key), props.snippet[key], value)
  }
  emit('update:snippet', { ...props.snippet, [key]: value })
  emit('field-change')
}

function canUndo(field: UndoableSnippetField) {
  if (!props.snippet) return false
  return undo.isModified(field, resolveUndoFieldCurrentValue(field))
}

function undoField(field: UndoableSnippetField) {
  if (!props.snippet) return
  const previous = undo.undo(field, resolveUndoFieldCurrentValue(field))
  if (previous === undefined) return
  applyUndoFieldState(field, previous)
}

function resetField(field: UndoableSnippetField) {
  if (!props.snippet) return
  const baseline = undo.reset(field)
  if (baseline === undefined) return
  updateField(field, baseline as CodeSnippetEditorDraft[typeof field], {
    trackHistory: false,
  })
}

function resolveUndoFieldCurrentValue(field: UndoableSnippetField) {
  if (!props.snippet) {
    return undefined
  }
  return field === 'name'
    ? props.snippet.name
    : field === 'description'
      ? props.snippet.description
      : props.snippet.code
}

function applyUndoFieldState(field: UndoableSnippetField, value: unknown) {
  updateField(field, value as CodeSnippetEditorDraft[typeof field], { trackHistory: false })
}

function handleEditorKeydown(event: KeyboardEvent) {
  if (!props.snippet || event.altKey) {
    return
  }
  const key = event.key.toLowerCase()
  const modifierPressed = event.ctrlKey || event.metaKey
  const isUndoShortcut = modifierPressed && !event.shiftKey && key === 'z'
  const isRedoShortcut =
    (modifierPressed && event.shiftKey && key === 'z') ||
    (event.ctrlKey && !event.metaKey && key === 'y')

  if (!isUndoShortcut && !isRedoShortcut) {
    return
  }

  const snapshot = (isUndoShortcut ? undo.undoLatest : undo.redoLatest)((field) =>
    resolveUndoFieldCurrentValue(field as UndoableSnippetField),
  )
  if (!snapshot) {
    return
  }

  event.preventDefault()
  applyUndoFieldState(snapshot.field as UndoableSnippetField, snapshot.value)
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
      :id-text="snippet?.id"
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

    <form
      v-else
      class=":uno: min-h-0 flex flex-1 flex-col"
      @keydown.capture="handleEditorKeydown"
      @submit.prevent="emit('save')"
    >
      <div class=":uno: min-h-0 flex-1 overflow-y-auto px-4 py-4 space-y-4">
        <FormField label="名称">
          <template v-if="canUndo('name')" #actions>
            <FieldUndoButton @reset="resetField('name')" @undo="undoField('name')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="snippet.name"
              class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
              placeholder="不填默认显示为 ID"
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
