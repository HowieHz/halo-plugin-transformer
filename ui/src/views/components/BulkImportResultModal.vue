<script lang="ts" setup>
import { VButton, VModal, VSpace } from "@halo-dev/components";

defineProps<{
  importedCount: number;
  resourceLabel: string;
}>();

const emit = defineEmits<{
  (e: "continue"): void;
  (e: "close"): void;
}>();
</script>

<template>
  <VModal title="批量导入完成" :width="520" @close="emit('close')">
    <div class=":uno: text-sm leading-6 text-gray-700">
      已完成导入 {{ importedCount }} 个{{
        resourceLabel
      }}。你可以继续导入更多内容，或直接返回批量模式。
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton type="secondary" @click="emit('continue')">继续导入</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
