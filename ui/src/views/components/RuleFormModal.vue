<script lang="ts" setup>
import { Toast, VButton } from "@halo-dev/components";
import { computed, nextTick, onMounted, ref } from "vue";

import {
  type TransformationSnippetReadModel,
  type TransformationRuleEditorDraft,
  makeRuleEditorDraft,
  MODE_OPTIONS,
  POSITION_OPTIONS,
} from "@/types";
import {
  formatMatchRuleError,
  getDomRulePerformanceWarning,
  isValidMatchRule,
  resolveRuleMatchRule,
} from "@/views/composables/matchRule";
import { updateSelectByWheel } from "@/views/composables/selectWheel.ts";
import { parseRuleTransfer } from "@/views/composables/transfer.ts";

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

const rule = ref<TransformationRuleEditorDraft>(makeRuleEditorDraft());
const fileInput = ref<HTMLInputElement | null>(null);
const importSourceVisible = ref(false);
const initialRule = makeRuleEditorDraft();

onMounted(reset);

function reset() {
  rule.value = makeRuleEditorDraft();
}

const needsTarget = computed(() => rule.value.mode === "SELECTOR");
const needsSnippets = computed(() => rule.value.position !== "REMOVE");
const needsWrapMarker = computed(() => rule.value.position !== "REMOVE");
const matchFieldError = computed(() => {
  if (!needsTarget.value) {
    return null;
  }
  return rule.value.match.trim() ? null : "请填写匹配内容";
});
const performanceWarning = computed(() => getDomRulePerformanceWarning(rule.value));

function toggleSnippet(id: string) {
  const currentSnippetIds = rule.value.snippetIds;
  rule.value.snippetIds = currentSnippetIds.includes(id)
    ? currentSnippetIds.filter((snippetId) => snippetId !== id)
    : [...currentSnippetIds, id];
}

function handleSubmit() {
  emit("submit", rule.value);
}

function openImportSourceModal() {
  importSourceVisible.value = true;
}

function closeImportSourceModal() {
  importSourceVisible.value = false;
}

async function applyImportedRule(raw: string, sourceLabel: "剪贴板" | "文件") {
  rule.value = parseRuleTransfer(raw);
  const importedValidationError = resolveImportedRuleValidationError(rule.value);
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
  if (importedRule.mode === "SELECTOR" && !importedRule.match.trim()) {
    return "请填写匹配内容";
  }
  const result = resolveRuleMatchRule(importedRule);
  if (result.error) {
    return `匹配规则有误：${formatMatchRuleError(result.error)}`;
  }
  if (!isValidMatchRule(result.rule)) {
    return "请完善匹配规则";
  }
  return null;
}

const validationError = computed(() => {
  return resolveImportedRuleValidationError(rule.value);
});

const dirty = computed(() => {
  return (
    JSON.stringify({
      enabled: rule.value.enabled,
      name: rule.value.name,
      description: rule.value.description,
      mode: rule.value.mode,
      match: rule.value.match,
      matchRule: rule.value.matchRule,
      position: rule.value.position,
      wrapMarker: rule.value.wrapMarker,
      runtimeOrder: rule.value.runtimeOrder,
      matchRuleSource: rule.value.matchRuleSource,
      snippetIds: rule.value.snippetIds,
    }) !==
    JSON.stringify({
      enabled: initialRule.enabled,
      name: initialRule.name,
      description: initialRule.description,
      mode: initialRule.mode,
      match: initialRule.match,
      matchRule: initialRule.matchRule,
      position: initialRule.position,
      wrapMarker: initialRule.wrapMarker,
      runtimeOrder: initialRule.runtimeOrder,
      matchRuleSource: initialRule.matchRuleSource,
      snippetIds: [],
    })
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
    rule: {
      ...rule.value,
      matchRule: JSON.parse(JSON.stringify(rule.value.matchRule)),
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
          :enabled="rule.enabled"
          label="切换新建转换规则的启用状态"
          title-when-disabled="当前新建后会保持禁用，点击改为启用"
          title-when-enabled="当前新建后会直接启用，点击改为禁用"
          @toggle="rule.enabled = !rule.enabled"
        />
        <VButton size="sm" type="secondary" @click="openImportSourceModal">导入</VButton>
      </div>
    </template>

    <template #form>
      <FormField v-slot="{ inputId }" label="名称">
        <input
          :id="inputId"
          v-model="rule.name"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="不填默认为 ID"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="描述">
        <input
          :id="inputId"
          v-model="rule.description"
          class=":uno: focus:border-primary w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:outline-none"
          placeholder="说明此规则的用途"
        />
      </FormField>

      <FormField v-slot="{ inputId, labelId }" label="运行顺序">
        <div :id="inputId" :aria-labelledby="labelId">
          <RuleRuntimeOrderField v-model="rule.runtimeOrder" />
        </div>
      </FormField>

      <FormField v-slot="{ inputId }" label="注入模式" required>
        <select
          :id="inputId"
          v-model="rule.mode"
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
              v-model="rule.match"
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
            v-model="rule.position"
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
          <input :id="inputId" v-model="rule.wrapMarker" type="checkbox" />
          输出注释标记
        </label>
      </FormField>

      <FormField v-slot="{ inputId, labelId }" label="匹配规则" required>
        <div :id="inputId" :aria-labelledby="labelId">
          <MatchRuleEditor
            :model-value="rule.matchRule"
            :source="rule.matchRuleSource"
            @change="void 0"
            @update:state="Object.assign(rule, $event)"
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
      <div class=":uno: flex items-center justify-between">
        <label class=":uno: text-xs font-medium text-gray-600">关联代码片段</label>
        <span class=":uno: text-xs text-gray-400"> {{ rule.snippetIds.length }} 个已选 </span>
      </div>
      <ItemPicker
        :items="snippets"
        label="关联代码片段选择列表"
        :selected-ids="rule.snippetIds"
        empty-text="暂无代码片段, 请先创建"
        @toggle="toggleSnippet"
      />
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
