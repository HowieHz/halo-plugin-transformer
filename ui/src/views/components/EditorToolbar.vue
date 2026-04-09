<script lang="ts" setup>
import { VButton, VSpace } from "@halo-dev/components";

withDefaults(
  defineProps<{
    title: string;
    idText?: string;
    enabled?: boolean;
    showActions?: boolean;
    showDefaultActions?: boolean;
    showExport?: boolean;
  }>(),
  {
    showDefaultActions: true,
  },
);

const emit = defineEmits<{
  (e: "export"): void;
  (e: "toggle-enabled"): void;
  (e: "delete"): void;
}>();
</script>

<template>
  <div
    class=":uno: sticky top-0 z-10 flex h-12 shrink-0 items-center justify-between border-b bg-white px-4"
  >
    <div class=":uno: flex min-w-0 items-center gap-2">
      <h2 class=":uno: shrink-0 text-sm font-semibold text-gray-900">{{ title }}</h2>
      <span v-if="idText" class=":uno: min-w-0 truncate font-mono text-xs text-gray-500">
        ID: {{ idText }}
      </span>
    </div>
    <VSpace v-if="showActions || !!$slots.actions">
      <slot name="actions" />
      <VButton
        v-if="showActions && showDefaultActions !== false && showExport"
        aria-label="导出当前内容"
        size="sm"
        title="导出当前内容"
        @click="emit('export')"
      >
        导出
      </VButton>
      <VButton
        v-if="showActions && showDefaultActions !== false"
        :aria-label="enabled ? '禁用当前内容' : '启用当前内容'"
        :aria-pressed="enabled"
        :title="enabled ? '当前已启用，点击后禁用' : '当前已停用，点击后启用'"
        size="sm"
        @click="emit('toggle-enabled')"
      >
        {{ enabled ? "禁用" : "启用" }}
      </VButton>
      <VButton
        v-if="showActions && showDefaultActions !== false"
        aria-label="删除当前内容"
        size="sm"
        title="删除当前内容"
        type="danger"
        @click="emit('delete')"
      >
        删除
      </VButton>
    </VSpace>
  </div>
</template>
