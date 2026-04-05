<script
  generic="T extends { id: string; name: string; description?: string; enabled: boolean }"
  lang="ts"
  setup
>
import StatusDot from './StatusDot.vue'

defineProps<{
  items: T[]
  selectedIds: string[]
  emptyText?: string
  previewFn?: (item: T) => string
  label?: string
}>()

const emit = defineEmits<{
  (e: 'toggle', id: string): void
}>()
</script>

<template>
  <div
    :aria-label="label ?? '可多选列表'"
    class=":uno: h-30 min-h-30 resize-y overflow-hidden rounded-md border border-gray-200 bg-white"
    role="group"
  >
    <div class=":uno: h-full overflow-y-auto divide-y divide-gray-100">
      <div
        v-if="!items.length"
        class=":uno: flex h-14 items-center justify-center text-xs text-gray-400"
      >
        {{ emptyText ?? '暂无数据' }}
      </div>
      <label
        v-for="item in items"
        :key="item.id"
        :class="selectedIds.includes(item.id) ? ':uno: bg-primary/5' : ':uno: hover:bg-gray-50'"
        class=":uno: flex items-start gap-2 px-3 py-2 cursor-pointer transition-colors"
      >
        <input
          :aria-label="`选择 ${item.name || item.id}`"
          :checked="selectedIds.includes(item.id)"
          class=":uno: mt-1 shrink-0"
          type="checkbox"
          @change="emit('toggle', item.id)"
        />
        <div class=":uno: min-w-0 flex-1">
          <span class=":uno: block truncate text-sm text-gray-900 font-medium">
            {{ item.name || item.id }}
          </span>
          <span v-if="item.description" class=":uno: block truncate text-xs text-gray-500">
            {{ item.description }}
          </span>
          <span
            v-if="previewFn"
            class=":uno: mt-0.5 block truncate text-xs text-gray-400 font-mono"
          >
            {{ previewFn(item) }}
          </span>
        </div>
        <StatusDot :enabled="item.enabled" />
      </label>
    </div>
  </div>
</template>
