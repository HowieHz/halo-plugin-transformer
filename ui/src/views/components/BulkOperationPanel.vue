<script lang="ts" setup>
import { VButton } from "@halo-dev/components";
import { computed } from "vue";

import type { ActiveTab } from "@/types";

import EditorToolbar from "./EditorToolbar.vue";

const props = defineProps<{
  tab: ActiveTab;
  selectedCount: number;
  processing: boolean;
  canEnable: boolean;
  canDisable: boolean;
}>();

const emit = defineEmits<{
  (e: "exit"): void;
  (e: "import"): void;
  (e: "export"): void;
  (e: "enable"): void;
  (e: "disable"): void;
  (e: "delete"): void;
}>();

const resourceLabel = computed(() => (props.tab === "snippets" ? "代码片段" : "转换规则"));
const hasSelection = computed(() => props.selectedCount > 0);
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
      </template>
    </EditorToolbar>

    <div class=":uno: flex flex-1 items-center justify-center px-6">
      <div class=":uno: max-w-md space-y-3 text-center">
        <h3 class=":uno: text-lg font-semibold text-gray-900">
          已选择 {{ selectedCount }} 个{{ resourceLabel }}
        </h3>
        <p aria-atomic="true" aria-live="polite" class=":uno: sr-only">
          当前已选择 {{ selectedCount }} 个{{ resourceLabel }}
        </p>
        <p class=":uno: text-sm leading-6 text-gray-500">
          批量模式下不会打开单项编辑器；当前已勾选的项目就是本次批量操作目标。
        </p>
      </div>
    </div>
  </div>
</template>
