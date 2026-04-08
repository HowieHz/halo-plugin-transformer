<script lang="ts" setup>
import type { DragAutoScrollDirection } from '@/views/composables/useDragAutoScroll'

defineProps<{
  active: boolean
  activeDirection: DragAutoScrollDirection | null
  canScrollUp: boolean
  canScrollDown: boolean
}>()

const emit = defineEmits<{
  (e: 'zone-dragover', direction: DragAutoScrollDirection, event: DragEvent): void
  (e: 'zone-dragleave', direction: DragAutoScrollDirection): void
}>()

function zoneHint(direction: DragAutoScrollDirection, canScroll: boolean, isHot: boolean) {
  if (!isHot) {
    return direction === 'up' ? '拖到这里向上滚动' : '拖到这里向下滚动'
  }
  if (!canScroll) {
    return direction === 'up' ? '已经到顶部' : '已经到底部'
  }
  return direction === 'up' ? '继续拖住以向上滚动' : '继续拖住以向下滚动'
}
</script>

<template>
  <template v-if="active">
    <div
      aria-hidden="true"
      :class="[
        activeDirection === 'up' && canScrollUp
          ? ':uno: bg-primary/8 text-primary'
          : activeDirection === 'up' && !canScrollUp
            ? ':uno: bg-amber-50/95 text-amber-600'
            : ':uno: bg-white/82 text-gray-400',
      ]"
      class=":uno: absolute left-3 right-3 top-2 z-20 flex h-11 items-center justify-center rounded-md border border-white/80 px-3 shadow-sm backdrop-blur-sm transition-colors"
      @dragover="emit('zone-dragover', 'up', $event)"
      @dragleave="emit('zone-dragleave', 'up')"
    >
      <div class=":uno: pointer-events-none flex items-center gap-2 text-xs font-medium">
        <span class=":uno: text-sm leading-none">↑</span>
        <span>{{ zoneHint('up', canScrollUp, activeDirection === 'up') }}</span>
      </div>
    </div>

    <div
      aria-hidden="true"
      :class="[
        activeDirection === 'down' && canScrollDown
          ? ':uno: bg-primary/8 text-primary'
          : activeDirection === 'down' && !canScrollDown
            ? ':uno: bg-amber-50/95 text-amber-600'
            : ':uno: bg-white/82 text-gray-400',
      ]"
      class=":uno: absolute left-3 right-3 bottom-2 z-20 flex h-11 items-center justify-center rounded-md border border-white/80 px-3 shadow-sm backdrop-blur-sm transition-colors"
      @dragover="emit('zone-dragover', 'down', $event)"
      @dragleave="emit('zone-dragleave', 'down')"
    >
      <div class=":uno: pointer-events-none flex items-center gap-2 text-xs font-medium">
        <span class=":uno: text-sm leading-none">↓</span>
        <span>{{ zoneHint('down', canScrollDown, activeDirection === 'down') }}</span>
      </div>
    </div>
  </template>
</template>
