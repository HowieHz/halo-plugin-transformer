<script lang="ts" setup>
import { ref } from 'vue'
import type { DragAutoScrollDirection } from '@/views/composables/useDragAutoScroll'

defineProps<{
  active: boolean
  activeDirection: DragAutoScrollDirection | null
  canScrollUp: boolean
  canScrollDown: boolean
  topZoneHeight?: number
  bottomZoneHeight?: number
}>()

const emit = defineEmits<{
  (e: 'zone-dragover', direction: DragAutoScrollDirection, event: DragEvent): void
  (e: 'zone-dragleave', direction: DragAutoScrollDirection): void
}>()

const topZone = ref<HTMLElement | null>(null)
const bottomZone = ref<HTMLElement | null>(null)

/**
 * why: 边缘热区只负责自动滚动与到顶/到底提示，不应该抢走真正的放置动作；
 * 否则用户已经看到绿色插入线，松手却因为 drop 落在热区层而排序失败。
 */
function forwardDropToUnderlyingTarget(event: DragEvent, zoneElement: HTMLElement | null) {
  if (!zoneElement || typeof document === 'undefined') {
    return
  }

  const previousPointerEvents = zoneElement.style.pointerEvents
  zoneElement.style.pointerEvents = 'none'
  const dropTarget = document.elementFromPoint(event.clientX, event.clientY)
  zoneElement.style.pointerEvents = previousPointerEvents

  if (!dropTarget || dropTarget === zoneElement) {
    return
  }

  const forwardedDropEvent = new DragEvent('drop', {
    bubbles: true,
    cancelable: true,
    composed: true,
    clientX: event.clientX,
    clientY: event.clientY,
    screenX: event.screenX,
    screenY: event.screenY,
    dataTransfer: event.dataTransfer ?? undefined,
  })
  dropTarget.dispatchEvent(forwardedDropEvent)
}

function handleZoneDrop(direction: DragAutoScrollDirection, event: DragEvent) {
  event.preventDefault()
  event.stopPropagation()
  emit('zone-dragleave', direction)
  forwardDropToUnderlyingTarget(event, direction === 'up' ? topZone.value : bottomZone.value)
}
</script>

<template>
  <template v-if="active">
    <div
      ref="topZone"
      aria-hidden="true"
      :style="{ height: `${topZoneHeight ?? 64}px` }"
      class=":uno: absolute inset-x-0 top-0 z-20"
      @drop="handleZoneDrop('up', $event)"
      @dragover="emit('zone-dragover', 'up', $event)"
      @dragleave="emit('zone-dragleave', 'up')"
    >
      <div
        :class="
          activeDirection === 'up'
            ? canScrollUp
              ? ':uno: opacity-100 from-primary/22 via-primary/8 to-transparent'
              : ':uno: opacity-100 from-amber-300/35 via-amber-200/12 to-transparent'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute inset-x-0 top-0 h-full bg-gradient-to-b transition-opacity"
      />
      <div
        :class="
          activeDirection === 'up'
            ? canScrollUp
              ? ':uno: opacity-100 bg-primary/45'
              : ':uno: opacity-100 bg-amber-400/60'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute left-4 right-4 top-1 h-0.5 rounded-full transition-opacity"
      />
    </div>

    <div
      ref="bottomZone"
      aria-hidden="true"
      :style="{ height: `${bottomZoneHeight ?? 64}px` }"
      class=":uno: absolute inset-x-0 bottom-0 z-20"
      @drop="handleZoneDrop('down', $event)"
      @dragover="emit('zone-dragover', 'down', $event)"
      @dragleave="emit('zone-dragleave', 'down')"
    >
      <div
        :class="
          activeDirection === 'down'
            ? canScrollDown
              ? ':uno: opacity-100 from-transparent via-primary/8 to-primary/22'
              : ':uno: opacity-100 from-transparent via-amber-200/12 to-amber-300/35'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute inset-x-0 bottom-0 h-full bg-gradient-to-b transition-opacity"
      />
      <div
        :class="
          activeDirection === 'down'
            ? canScrollDown
              ? ':uno: opacity-100 bg-primary/45'
              : ':uno: opacity-100 bg-amber-400/60'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute left-4 right-4 bottom-1 h-0.5 rounded-full transition-opacity"
      />
    </div>
  </template>
</template>
