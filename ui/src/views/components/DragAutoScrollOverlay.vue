<script lang="ts" setup>
import type { DragAutoScrollDirection } from '@/views/composables/useDragAutoScroll'

defineProps<{
  active: boolean
  activeDirection: DragAutoScrollDirection | null
  canScrollUp: boolean
  canScrollDown: boolean
  topZoneHeight?: number
  bottomZoneHeight?: number
}>()
</script>

<template>
  <template v-if="active">
    <div
      aria-hidden="true"
      :style="{ height: `${topZoneHeight ?? 64}px` }"
      class=":uno: pointer-events-none absolute inset-x-0 top-0 z-20"
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
      aria-hidden="true"
      :style="{ height: `${bottomZoneHeight ?? 64}px` }"
      class=":uno: pointer-events-none absolute inset-x-0 bottom-0 z-20"
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
