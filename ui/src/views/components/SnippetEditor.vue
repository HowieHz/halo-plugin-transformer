<script lang="ts" setup>
import { VButton } from "@halo-dev/components";
import { computed, ref, watch } from "vue";

import type { TransformationSnippetEditorDraft } from "@/types";
import {
  buildSnippetTransfer,
  createTransferFileDraft,
  type TransferFileDraft,
} from "@/views/composables/transfer";
import { useFieldUndo } from "@/views/composables/useFieldUndo";

import EditorFooter from "./EditorFooter.vue";
import EditorToolbar from "./EditorToolbar.vue";
import ExportContentModal from "./ExportContentModal.vue";
import FieldUndoButton from "./FieldUndoButton.vue";
import FormField from "./FormField.vue";

const props = defineProps<{
  snippet: TransformationSnippetEditorDraft | null;
  saving: boolean;
  dirty: boolean;
}>();

const emit = defineEmits<{
  (e: "save"): void;
  (e: "delete"): void;
  (e: "toggle-enabled"): void;
  (e: "toggle-bulk-mode"): void;
  (e: "field-change"): void;
  (e: "update:snippet", snippet: TransformationSnippetEditorDraft): void;
}>();

const undo = useFieldUndo();
const exportFallback = ref<TransferFileDraft | null>(null);
const codeScrollTop = ref(0);
type UndoableSnippetField = "name" | "description" | "code";
const fieldInitialValue = ref<Record<UndoableSnippetField, string>>({
  name: "",
  description: "",
  code: "",
});

const codeLines = computed(() => {
  const content = (props.snippet?.code ?? "").replace(/\r\n/g, "\n");
  return content.split("\n").length;
});

const codeLineNumberStyle = computed(() => ({
  transform: `translateY(-${codeScrollTop.value}px)`,
}));
const codeFieldError = computed(() => (props.snippet?.code.trim() ? null : "代码内容不能为空"));

watch(
  () => [props.snippet?.id, props.dirty],
  () => {
    if (!props.snippet || props.dirty) {
      return;
    }
    undo.resetBaseline({
      name: props.snippet.name,
      description: props.snippet.description,
      code: props.snippet.code,
    });
  },
  { immediate: true },
);

function updateField<K extends keyof TransformationSnippetEditorDraft>(
  key: K,
  value: TransformationSnippetEditorDraft[K],
  options?: { trackHistory?: boolean },
) {
  if (!props.snippet) return;
  if (options?.trackHistory ?? true) {
    undo.trackChange(String(key), props.snippet[key], value);
  }
  emit("update:snippet", { ...props.snippet, [key]: value });
  emit("field-change");
}

function canUndo(field: UndoableSnippetField) {
  if (!props.snippet) return false;
  return undo.isModified(field, resolveUndoFieldCurrentValue(field));
}

function undoField(field: UndoableSnippetField) {
  if (!props.snippet) return;
  const previous = undo.undo(field, resolveUndoFieldCurrentValue(field));
  if (previous === undefined) return;
  applyUndoFieldState(field, previous);
}

function resetField(field: UndoableSnippetField) {
  if (!props.snippet) return;
  const baseline = undo.reset(field);
  if (baseline === undefined) return;
  updateField(field, baseline as TransformationSnippetEditorDraft[typeof field], {
    trackHistory: false,
  });
}

function resolveUndoFieldCurrentValue(field: UndoableSnippetField) {
  if (!props.snippet) {
    return undefined;
  }
  return field === "name"
    ? props.snippet.name
    : field === "description"
      ? props.snippet.description
      : props.snippet.code;
}

function applyUndoFieldState(field: UndoableSnippetField, value: unknown) {
  updateField(field, value as TransformationSnippetEditorDraft[typeof field], {
    trackHistory: false,
  });
}

function syncCodeScroll(event: Event) {
  codeScrollTop.value = (event.target as HTMLTextAreaElement).scrollTop;
}

function beginFieldEdit(field: UndoableSnippetField) {
  fieldInitialValue.value = {
    ...fieldInitialValue.value,
    [field]: resolveUndoFieldCurrentValue(field) ?? "",
  };
}

function handleFieldInput(field: UndoableSnippetField, value: string) {
  updateField(field, value as TransformationSnippetEditorDraft[typeof field], {
    trackHistory: false,
  });
}

function commitFieldDraft(field: UndoableSnippetField) {
  const currentValue = resolveUndoFieldCurrentValue(field);
  if (currentValue === undefined || fieldInitialValue.value[field] === currentValue) {
    return;
  }
  undo.trackChange(field, fieldInitialValue.value[field], currentValue);
}

async function exportSnippet() {
  if (!props.snippet) {
    return;
  }
  exportFallback.value = createTransferFileDraft(
    buildSnippetTransfer(props.snippet),
    props.snippet.name || props.snippet.id || "code-snippet",
  );
}
</script>

<template>
  <div class=":uno: transformer-editor-container flex h-full flex-col">
    <ExportContentModal
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
      :show-default-actions="true"
      :title="snippet ? '编辑代码片段' : '代码片段'"
      @delete="emit('delete')"
      @export="exportSnippet"
      @toggle-enabled="emit('toggle-enabled')"
    >
      <template #actions>
        <VButton size="sm" @click="emit('toggle-bulk-mode')">批量操作</VButton>
      </template>
    </EditorToolbar>

    <div v-if="!snippet" class=":uno: flex flex-1 items-center justify-center">
      <span class=":uno: text-sm text-gray-500">从左侧选择代码片段进行编辑</span>
    </div>

    <form v-else class=":uno: flex min-h-0 flex-1 flex-col" @submit.prevent="emit('save')">
      <div class=":uno: min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4">
        <FormField label="名称">
          <template v-if="canUndo('name')" #actions>
            <FieldUndoButton @reset="resetField('name')" @undo="undoField('name')" />
          </template>
          <template #default="{ inputId }">
            <input
              :id="inputId"
              :value="snippet.name"
              class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
              placeholder="不填默认显示为 ID"
              @focus="beginFieldEdit('name')"
              @input="handleFieldInput('name', ($event.target as HTMLInputElement).value)"
              @change="commitFieldDraft('name')"
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
              class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
              placeholder="说明此代码片段的用途"
              @focus="beginFieldEdit('description')"
              @input="handleFieldInput('description', ($event.target as HTMLInputElement).value)"
              @change="commitFieldDraft('description')"
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
                    : ':uno: focus-within:border-primary border-gray-200'
                "
                class=":uno: relative h-60 min-h-60 resize-y overflow-hidden rounded-md border bg-white"
              >
                <div class=":uno: relative z-1 flex h-full">
                  <div
                    aria-hidden="true"
                    class=":uno: relative h-full overflow-hidden border-r border-gray-100 bg-gray-50 px-2 pt-2 pb-0 text-right text-xs text-gray-400 select-none"
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
                    :value="snippet.code"
                    class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 pt-2 pb-0 font-mono text-sm leading-6 focus:outline-none"
                    placeholder="输入 HTML 代码"
                    spellcheck="false"
                    wrap="off"
                    @focus="beginFieldEdit('code')"
                    @change="commitFieldDraft('code')"
                    @input="handleFieldInput('code', ($event.target as HTMLTextAreaElement).value)"
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
