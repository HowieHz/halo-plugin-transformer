<script lang="ts" setup>
import { VButton, VSpace } from '@halo-dev/components'

defineProps<{
  title: string
  idText?: string
  enabled?: boolean
  showActions?: boolean
  showExport?: boolean
}>()

const emit = defineEmits<{
  (e: 'export'): void
  (e: 'toggle-enabled'): void
  (e: 'delete'): void
}>()
</script>

<template>
  <div
    class=":uno: sticky top-0 z-10 h-12 flex items-center justify-between border-b bg-white px-4 shrink-0"
  >
    <div class=":uno: min-w-0 flex items-center gap-2">
      <h2 class=":uno: shrink-0 text-gray-900 font-semibold text-sm">{{ title }}</h2>
      <span v-if="idText" class=":uno: min-w-0 truncate text-xs text-gray-500 font-mono">
        ID: {{ idText }}
      </span>
    </div>
    <VSpace v-if="showActions">
      <VButton
        v-if="showExport"
        aria-label="导出当前内容为 JSON"
        size="sm"
        title="导出当前内容为 JSON"
        @click="emit('export')"
      >
        导出 JSON
      </VButton>
      <VButton
        :aria-label="enabled ? '禁用当前内容' : '启用当前内容'"
        :aria-pressed="enabled"
        :title="enabled ? '当前已启用，点击后禁用' : '当前已停用，点击后启用'"
        size="sm"
        @click="emit('toggle-enabled')"
      >
        {{ enabled ? '禁用' : '启用' }}
      </VButton>
      <VButton
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
