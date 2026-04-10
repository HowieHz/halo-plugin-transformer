<script lang="ts" setup>
import type { DragAutoScrollDirection } from "@/views/composables/useDragAutoScroll";

defineProps<{
  active: boolean;
  activeDirection: DragAutoScrollDirection | null;
  canScrollUp: boolean;
  canScrollDown: boolean;
  topZoneHeight?: number;
  bottomZoneHeight?: number;
}>();
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
              ? ':uno: from-primary/22 via-primary/8 to-transparent opacity-100'
              : ':uno: from-amber-300/35 via-amber-200/12 to-transparent opacity-100'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute inset-x-0 top-0 h-full bg-gradient-to-b transition-opacity"
      />
      <div
        :class="
          activeDirection === 'up'
            ? canScrollUp
              ? ':uno: bg-primary/45 opacity-100'
              : ':uno: opacity-0'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute top-1 right-4 left-4 h-0.5 rounded-full transition-opacity"
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
              ? ':uno: via-primary/8 to-primary/22 from-transparent opacity-100'
              : ':uno: from-transparent via-amber-200/12 to-amber-300/35 opacity-100'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute inset-x-0 bottom-0 h-full bg-gradient-to-b transition-opacity"
      />
      <div
        :class="
          activeDirection === 'down'
            ? canScrollDown
              ? ':uno: bg-primary/45 opacity-100'
              : ':uno: opacity-0'
            : ':uno: opacity-0'
        "
        class=":uno: pointer-events-none absolute right-4 bottom-1 left-4 h-0.5 rounded-full transition-opacity"
      />
    </div>
  </template>
</template>
