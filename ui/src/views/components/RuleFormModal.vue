<script lang="ts" setup>
import { Toast, VButton } from "@halo-dev/components";
import { computed, nextTick, ref, useId } from "vue";

import {
  type TransformationSnippetReadModel,
  type TransformationRuleEditorDraft,
  MODE_OPTIONS,
  POSITION_OPTIONS,
} from "@/types";
import { getDomRulePerformanceWarning } from "@/views/composables/matchRule";
import {
  getEmptySnippetAssociationWarning,
  getRuleCapabilities,
} from "@/views/composables/ruleCapabilities";
import { validateRuleDraft } from "@/views/composables/ruleValidation";
import { updateSelectByWheel } from "@/views/composables/selectWheel.ts";
import { useRuleCreateDraft } from "@/views/composables/useRuleCreateDraft";

import BaseFormModal from "./BaseFormModal.vue";
import EnabledSwitch from "./EnabledSwitch.vue";
import FormField from "./FormField.vue";
import ImportSourceModal from "./ImportSourceModal.vue";
import ItemPicker from "./ItemPicker.vue";
import MatchRuleEditor from "./MatchRuleEditor.vue";
import RuleRuntimeOrderField from "./RuleRuntimeOrderField.vue";

defineProps<{
  snippets: TransformationSnippetReadModel[];
  saving: boolean;
}>();

const emit = defineEmits<{
  (e: "close"): void;
  (e: "submit", rule: TransformationRuleEditorDraft): void;
}>();

const createDraft = useRuleCreateDraft();
const fileInput = ref<HTMLInputElement | null>(null);
const importSourceVisible = ref(false);
const formId = useId();
const matchFieldErrorId = `rule-form-match-error-${formId}`;

function reset() {
  createDraft.reset();
}

const ruleCapabilities = computed(() => getRuleCapabilities(createDraft.draft.value));
const needsTarget = computed(() => ruleCapabilities.value.showsTargetField);
const needsSnippets = computed(() => ruleCapabilities.value.showsSnippetPicker);
const needsWrapMarker = computed(() => ruleCapabilities.value.allowsWrapMarker);
const matchFieldError = computed(() => {
  if (!needsTarget.value) {
    return null;
  }
  return createDraft.draft.value.match.trim() ? null : "请填写匹配内容";
});
const performanceWarning = computed(() => getDomRulePerformanceWarning(createDraft.draft.value));
const emptySnippetAssociationWarning = computed(() =>
  getEmptySnippetAssociationWarning(createDraft.draft.value),
);

function toggleSnippet(id: string) {
  const currentSnippetIds = createDraft.draft.value.snippetIds;
  createDraft.draft.value.snippetIds = currentSnippetIds.includes(id)
    ? currentSnippetIds.filter((snippetId) => snippetId !== id)
    : [...currentSnippetIds, id];
}

function handleSubmit() {
  emit("submit", createDraft.draft.value);
}

function openImportSourceModal() {
  importSourceVisible.value = true;
}

function closeImportSourceModal() {
  importSourceVisible.value = false;
}

async function applyImportedRule(raw: string, sourceLabel: "剪贴板" | "文件") {
  createDraft.importFromTransfer(raw);
  const importedValidationError = resolveImportedRuleValidationError(createDraft.draft.value);
  if (importedValidationError) {
    Toast.warning(
      `已从${sourceLabel}导入转换规则 JSON，但当前内容仍有错误：${importedValidationError}`,
    );
  } else {
    Toast.success(`已从${sourceLabel}导入转换规则 JSON`);
  }
}

async function importFromClipboard() {
  try {
    const text = await navigator.clipboard.readText();
    if (!text.trim()) {
      Toast.warning("剪贴板里没有可导入的 JSON");
      return;
    }
    await applyImportedRule(text, "剪贴板");
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
    await applyImportedRule(await file.text(), "文件");
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : "导入失败");
  } finally {
    input.value = "";
  }
}

function resolveImportedRuleValidationError(importedRule: TransformationRuleEditorDraft) {
  return validateRuleDraft(importedRule);
}

function hasUnsavedChanges() {
  return createDraft.hasUnsavedChanges();
}

function getValidationError() {
  return createDraft.validationError.value;
}

function getSubmitPayload() {
  return createDraft.getSubmitPayload();
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
    :saving="saving"
    :show-picker="needsSnippets"
    title="新建转换规则"
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
          :enabled="createDraft.draft.value.enabled"
          label="切换新建转换规则的启用状态"
          title-when-disabled="当前新建后会保持禁用，点击改为启用"
          title-when-enabled="当前新建后会直接启用，点击改为禁用"
          @toggle="createDraft.draft.value.enabled = !createDraft.draft.value.enabled"
        />
        <VButton size="sm" type="secondary" @click="openImportSourceModal">导入</VButton>
      </div>
    </template>

    <template #form>
      <FormField v-slot="{ inputId }" label="名称">
        <input
          :id="inputId"
          v-model="createDraft.draft.value.name"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="不填默认为 ID"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="描述">
        <input
          :id="inputId"
          v-model="createDraft.draft.value.description"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="说明此规则的用途"
        />
      </FormField>

      <FormField v-slot="{ inputId, labelId }" label="运行顺序">
        <div :id="inputId" :aria-labelledby="labelId">
          <RuleRuntimeOrderField v-model="createDraft.draft.value.runtimeOrder" />
        </div>
      </FormField>

      <FormField v-slot="{ inputId }" label="注入模式" required>
        <select
          :id="inputId"
          v-model="createDraft.draft.value.mode"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 bg-white px-3 py-1.5 text-sm focus:outline-none"
          @wheel="updateSelectByWheel"
        >
          <option v-for="o in MODE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
      </FormField>

      <template v-if="needsTarget">
        <FormField v-slot="{ inputId }" :invalid="!!matchFieldError" label="CSS 选择器" required>
          <div class=":uno: space-y-1">
            <input
              :id="inputId"
              :aria-describedby="matchFieldError ? matchFieldErrorId : undefined"
              v-model="createDraft.draft.value.match"
              :aria-invalid="!!matchFieldError"
              placeholder="例如：#main-content、.post-card、div[data-role=banner]"
              :class="
                matchFieldError
                  ? ':uno: border-red-300 bg-red-50/40 focus:border-red-500'
                  : ':uno: focus:border-primary border-gray-200'
              "
              class=":uno: w-full rounded-md border px-3 py-1.5 font-mono text-sm focus:outline-none"
            />
            <p
              v-if="matchFieldError"
              :id="matchFieldErrorId"
              aria-live="polite"
              class=":uno: text-xs text-red-500"
              role="alert"
            >
              {{ matchFieldError }}
            </p>
          </div>
        </FormField>

        <FormField v-slot="{ inputId }" label="插入位置">
          <select
            :id="inputId"
            v-model="createDraft.draft.value.position"
            class=":uno: focus:border-primary w-full rounded-md border border-gray-200 bg-white px-3 py-1.5 text-sm focus:outline-none"
            @wheel="updateSelectByWheel"
          >
            <option v-for="o in POSITION_OPTIONS" :key="o.value" :value="o.value">
              {{ o.label }}
            </option>
          </select>
        </FormField>
      </template>

      <FormField v-if="needsWrapMarker" v-slot="{ inputId }">
        <label class=":uno: inline-flex items-center gap-2 text-sm text-gray-700">
          <input :id="inputId" v-model="createDraft.draft.value.wrapMarker" type="checkbox" />
          输出注释标记
        </label>
      </FormField>

      <FormField v-slot="{ inputId, labelId }" label="匹配规则" required>
        <div :id="inputId" :aria-labelledby="labelId">
          <MatchRuleEditor
            :model-value="createDraft.draft.value.matchRule"
            :source="createDraft.draft.value.matchRuleSource"
            @change="void 0"
            @update:state="Object.assign(createDraft.draft.value, $event)"
          />
        </div>
        <div
          v-if="performanceWarning"
          aria-live="polite"
          class=":uno: mt-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
        >
          {{ performanceWarning }}
        </div>
      </FormField>
    </template>

    <template v-if="needsSnippets" #picker>
      <div class=":uno: space-y-2">
        <div class=":uno: flex items-center justify-between">
          <label class=":uno: text-xs font-medium text-gray-600">关联代码片段</label>
          <span class=":uno: text-xs text-gray-400">
            {{ createDraft.draft.value.snippetIds.length }} 个已选
          </span>
        </div>
        <ItemPicker
          :items="snippets"
          label="关联代码片段选择列表"
          :selected-ids="createDraft.draft.value.snippetIds"
          empty-text="暂无代码片段, 请先创建"
          @toggle="toggleSnippet"
        />
        <div
          v-if="emptySnippetAssociationWarning"
          aria-live="polite"
          class=":uno: rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
        >
          {{ emptySnippetAssociationWarning }}
        </div>
      </div>
    </template>
  </BaseFormModal>

  <ImportSourceModal
    v-if="importSourceVisible"
    resource-label="转换规则"
    @close="closeImportSourceModal"
    @import-from-clipboard="importFromClipboard"
    @import-from-file="importFromFile"
  />
</template>
