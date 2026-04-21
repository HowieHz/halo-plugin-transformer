<script lang="ts" setup>
import { VButton } from "@halo-dev/components";
import { computed } from "vue";

import type { ActiveTab, RuleCompatibilityStatus, RuleCompatibilityStepView } from "@/types";

import EditorToolbar from "./EditorToolbar.vue";

const props = defineProps<{
  tab: ActiveTab;
  selectedCount: number;
  processing: boolean;
  canEnable: boolean;
  canDisable: boolean;
  ruleCompatibilityStatus?: RuleCompatibilityStatus;
  ruleCompatibilityStep?: RuleCompatibilityStepView | null;
}>();

const emit = defineEmits<{
  (e: "exit"): void;
  (e: "import"): void;
  (e: "export"): void;
  (e: "enable"): void;
  (e: "disable"): void;
  (e: "delete"): void;
  (e: "compatibility-start"): void;
  (e: "compatibility-answer", hasIssue: boolean): void;
  (e: "compatibility-undo"): void;
  (e: "compatibility-stop"): void;
}>();

const resourceLabel = computed(() => (props.tab === "snippets" ? "代码片段" : "转换规则"));
const hasSelection = computed(() => props.selectedCount > 0);
const isRuleBulkMode = computed(() => props.tab === "rules");
const isCompatibilityActive = computed(
  () =>
    isRuleBulkMode.value &&
    props.ruleCompatibilityStatus !== undefined &&
    props.ruleCompatibilityStatus !== "idle",
);
const isCompatibilityTesting = computed(
  () =>
    props.ruleCompatibilityStatus === "testing" &&
    props.ruleCompatibilityStep?.status === "testing",
);
const isCompatibilityComplete = computed(
  () =>
    props.ruleCompatibilityStatus === "complete" &&
    props.ruleCompatibilityStep?.status === "complete",
);
const compatibilityTargetLabel = computed(
  () => props.ruleCompatibilityStep?.targets.map((target) => target.name).join("、") || "无",
);
</script>

<template>
  <div class=":uno: transformer-editor-container flex h-full flex-col">
    <EditorToolbar :show-actions="false" :show-default-actions="false" title="批量操作">
      <template #actions>
        <VButton size="sm" @click="emit('exit')">退出批量操作</VButton>
        <VButton :disabled="processing" size="sm" @click="emit('import')">批量导入</VButton>
        <VButton :disabled="processing || !hasSelection" size="sm" @click="emit('export')">
          批量导出
        </VButton>
        <VButton
          :disabled="processing || !hasSelection || !canEnable"
          size="sm"
          @click="emit('enable')"
        >
          启用
        </VButton>
        <VButton
          :disabled="processing || !hasSelection || !canDisable"
          size="sm"
          @click="emit('disable')"
        >
          禁用
        </VButton>
        <VButton
          :disabled="processing || !hasSelection"
          size="sm"
          type="danger"
          @click="emit('delete')"
        >
          删除
        </VButton>
        <VButton
          v-if="isRuleBulkMode"
          :disabled="processing || !hasSelection || isCompatibilityActive"
          size="sm"
          type="secondary"
          @click="emit('compatibility-start')"
        >
          兼容性排查
        </VButton>
      </template>
    </EditorToolbar>

    <div class=":uno: flex flex-1 items-center justify-center px-6">
      <div v-if="isCompatibilityActive" class=":uno: max-w-xl space-y-4 text-center">
        <h3 class=":uno: text-lg font-semibold text-gray-900">
          {{
            isCompatibilityComplete
              ? "兼容性排查完成"
              : ruleCompatibilityStatus === "restoring"
                ? "正在恢复规则启用状态"
                : "正在进行兼容性排查"
          }}
        </h3>
        <p aria-atomic="true" aria-live="polite" class=":uno: sr-only">
          当前测试的规则：{{ compatibilityTargetLabel }}
        </p>
        <p v-if="isCompatibilityTesting" class=":uno: text-sm leading-6 text-gray-500">
          当前只启用了下面这些规则。请在前台确认问题是否还会出现。
        </p>
        <p v-else-if="isCompatibilityComplete" class=":uno: text-sm leading-6 text-gray-500">
          疑似问题规则：{{ compatibilityTargetLabel }}。已恢复到排查前的启用状态。
        </p>
        <p v-else class=":uno: text-sm leading-6 text-gray-500">正在恢复到排查前的启用状态。</p>
        <div
          v-if="ruleCompatibilityStep?.targets.length"
          class=":uno: rounded border border-gray-200 bg-gray-50 px-4 py-3 text-left text-sm text-gray-700"
        >
          <div class=":uno: mb-2 font-medium text-gray-900">
            {{
              isCompatibilityComplete
                ? `疑似问题规则（${ruleCompatibilityStep.targets.length}）`
                : `本轮启用规则（${ruleCompatibilityStep.targets.length}）`
            }}
          </div>
          <ul class=":uno: max-h-48 list-disc space-y-1 overflow-auto pl-5">
            <li v-for="(target, index) in ruleCompatibilityStep.targets" :key="target.id">
              #{{ ruleCompatibilityStep.targetNumbers[index] }} {{ target.name }}
            </li>
          </ul>
        </div>
        <div class=":uno: flex flex-wrap justify-center gap-2">
          <VButton
            v-if="isCompatibilityTesting"
            :disabled="processing"
            size="sm"
            type="danger"
            @click="emit('compatibility-answer', true)"
          >
            问题还会出现
          </VButton>
          <VButton
            v-if="isCompatibilityTesting"
            :disabled="processing"
            size="sm"
            type="secondary"
            @click="emit('compatibility-answer', false)"
          >
            问题不再出现
          </VButton>
          <VButton
            v-if="isCompatibilityTesting"
            :disabled="processing"
            size="sm"
            @click="emit('compatibility-undo')"
          >
            撤销上一步
          </VButton>
          <VButton :disabled="processing" size="sm" @click="emit('compatibility-stop')">
            {{ isCompatibilityComplete ? "关闭结果" : "结束并恢复" }}
          </VButton>
        </div>
      </div>
      <div v-else class=":uno: max-w-md space-y-3 text-center">
        <h3 class=":uno: text-lg font-semibold text-gray-900">
          已选择 {{ selectedCount }} 个{{ resourceLabel }}
        </h3>
        <p aria-atomic="true" aria-live="polite" class=":uno: sr-only">
          当前已选择 {{ selectedCount }} 个{{ resourceLabel }}
        </p>
        <p class=":uno: text-sm leading-6 text-gray-500">
          {{
            isRuleBulkMode
              ? "批量模式下不会打开单项编辑器；可以启停、导出勾选规则，也可以用它们做兼容性排查。"
              : "批量模式下不会打开单项编辑器；勾选项就是本次批量操作对象。"
          }}
        </p>
      </div>
    </div>
  </div>
</template>
