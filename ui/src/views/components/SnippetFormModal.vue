<script lang="ts" setup>
import { Toast, VButton } from "@halo-dev/components";
import { computed, nextTick, onMounted, ref } from "vue";

import type { TransformationSnippetEditorDraft } from "@/types";
import { makeSnippetEditorDraft } from "@/types";
import { parseSnippetTransfer } from "@/views/composables/transfer";

import BaseFormModal from "./BaseFormModal.vue";
import EnabledSwitch from "./EnabledSwitch.vue";
import FormField from "./FormField.vue";
import ImportSourceModal from "./ImportSourceModal.vue";

const props = defineProps<{
  saving: boolean;
}>();

const emit = defineEmits<{
  (e: "close"): void;
  (e: "submit", snippet: TransformationSnippetEditorDraft): void;
}>();

const snippet = ref<TransformationSnippetEditorDraft>(makeSnippetEditorDraft());
const fileInput = ref<HTMLInputElement | null>(null);
const importSourceVisible = ref(false);
const initialSnippet = makeSnippetEditorDraft();
const codeScrollTop = ref(0);

const codeLines = computed(() => {
  const content = snippet.value.code.replace(/\r\n/g, "\n");
  return content.split("\n").length;
});

const codeLineNumberStyle = computed(() => ({
  transform: `translateY(-${codeScrollTop.value}px)`,
}));
const codeFieldError = computed(() => (!snippet.value.code.trim() ? "代码内容不能为空" : null));

onMounted(reset);

function reset() {
  snippet.value = makeSnippetEditorDraft();
}

function handleSubmit() {
  emit("submit", snippet.value);
}

function syncCodeScroll(event: Event) {
  codeScrollTop.value = (event.target as HTMLTextAreaElement).scrollTop;
}

function openImportSourceModal() {
  importSourceVisible.value = true;
}

function closeImportSourceModal() {
  importSourceVisible.value = false;
}

async function applyImportedSnippet(raw: string, sourceLabel: "剪贴板" | "文件") {
  snippet.value = parseSnippetTransfer(raw);
  if (!snippet.value.code.trim()) {
    Toast.warning(`已从${sourceLabel}导入代码片段 JSON，但当前内容仍有错误：代码内容不能为空`);
  } else {
    Toast.success(`已从${sourceLabel}导入代码片段 JSON`);
  }
}

async function importFromClipboard() {
  try {
    const text = await navigator.clipboard.readText();
    if (!text.trim()) {
      Toast.warning("剪贴板里没有可导入的 JSON");
      return;
    }
    await applyImportedSnippet(text, "剪贴板");
    importSourceVisible.value = false;
  } catch {
    Toast.error("读取剪贴板失败，请检查浏览器权限后重试");
  }
}

async function importFromFile() {
  importSourceVisible.value = false;
  await nextTick();
  fileInput.value?.click();
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  try {
    await applyImportedSnippet(await file.text(), "文件");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "导入失败");
  } finally {
    input.value = "";
  }
}

const validationError = computed(() => codeFieldError.value);
const dirty = computed(() => {
  return (
    snippet.value.enabled !== initialSnippet.enabled ||
    snippet.value.name !== initialSnippet.name ||
    snippet.value.description !== initialSnippet.description ||
    snippet.value.code !== initialSnippet.code
  );
});

function hasUnsavedChanges() {
  return dirty.value;
}

function getValidationError() {
  return validationError.value;
}

function getSubmitPayload() {
  return {
    snippet: {
      ...snippet.value,
    },
  };
}

defineExpose({
  reset,
  hasUnsavedChanges,
  getValidationError,
  getSubmitPayload,
});
</script>

<template>
  <BaseFormModal
    hide-default-title
    :saving="props.saving"
    :show-picker="false"
    title="新建代码片段"
    @close="emit('close')"
    @submit="handleSubmit"
  >
    <template #actions>
      <div class=":uno: flex items-center justify-end gap-2">
        <input
          ref="fileInput"
          accept="application/json,.json"
          class=":uno: hidden"
          type="file"
          @change="handleImportFile"
        />
        <EnabledSwitch
          :enabled="snippet.enabled"
          label="切换新建代码片段的启用状态"
          title-when-disabled="当前新建后会保持禁用，点击改为启用"
          title-when-enabled="当前新建后会直接启用，点击改为禁用"
          @toggle="snippet.enabled = !snippet.enabled"
        />
        <VButton size="sm" type="secondary" @click="openImportSourceModal">导入</VButton>
      </div>
    </template>

    <template #form>
      <FormField v-slot="{ inputId }" label="名称">
        <input
          :id="inputId"
          v-model="snippet.name"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="不填默认显示为 ID"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="描述">
        <input
          :id="inputId"
          v-model="snippet.description"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="说明此代码片段的用途"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="代码内容" required>
        <div class=":uno: space-y-1">
          <div
            :class="
              codeFieldError
                ? ':uno: border-red-300 focus-within:border-red-500'
                : ':uno: focus-within:border-primary border-gray-200'
            "
            class=":uno: relative h-72 min-h-72 resize-y overflow-hidden rounded-md border bg-white"
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
                v-model="snippet.code"
                :aria-invalid="!!codeFieldError"
                autofocus
                class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 pt-2 pb-0 font-mono text-sm leading-6 focus:outline-none"
                placeholder="输入 HTML 代码"
                spellcheck="false"
                wrap="off"
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
      </FormField>
    </template>
  </BaseFormModal>

  <ImportSourceModal
    v-if="importSourceVisible"
    resource-label="代码片段"
    @close="closeImportSourceModal"
    @import-from-clipboard="importFromClipboard"
    @import-from-file="importFromFile"
  />
</template>
