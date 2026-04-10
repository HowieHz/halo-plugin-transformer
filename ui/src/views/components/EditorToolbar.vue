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
  <div class=":uno: sticky top-0 z-10 flex h-12 shrink-0 items-center gap-3 border-b bg-white px-4">
    <div
      class=":uno: group/title flex min-w-0 flex-1 items-center gap-2 overflow-hidden focus-within:outline-none"
      :tabindex="idText ? 0 : undefined"
    >
      <h2 class=":uno: shrink-0 text-sm font-semibold text-gray-900">{{ title }}</h2>
      <span v-if="idText" class=":uno: sr-only"> ID: {{ idText }} </span>
      <span
        v-if="idText"
        aria-hidden="true"
        class=":uno: pointer-events-none min-w-0 truncate font-mono text-xs text-gray-500 opacity-0 transition-opacity duration-150 group-focus-within/title:opacity-100 group-hover/title:opacity-100"
        :title="`ID: ${idText}`"
      >
        ID: {{ idText }}
      </span>
    </div>
    <VSpace v-if="showActions || !!$slots.actions" class=":uno: shrink-0">
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
