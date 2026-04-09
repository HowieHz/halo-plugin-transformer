<script lang="ts" setup>
import { nextTick, ref, watch } from "vue";

import type { MobileDrawerSide } from "@/views/composables/useMobileDrawerState";

const props = defineProps<{
  compact: boolean;
  descriptionId: string;
  drawerId: string;
  open: boolean;
  side: MobileDrawerSide;
  title: string;
  titleId: string;
}>();

const emit = defineEmits<{
  (e: "close"): void;
}>();

const panelRef = ref<HTMLElement | null>(null);
const FOCUSABLE_SELECTOR = [
  "button:not([disabled])",
  "[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(", ");

function focusDrawerContent() {
  const panel = panelRef.value;
  if (!panel) {
    return;
  }

  const firstFocusableElement = panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
  (firstFocusableElement ?? panel).focus();
}

watch(
  () => [props.compact, props.open] as const,
  async ([compact, open]) => {
    if (!compact || !open) {
      return;
    }

    await nextTick();
    focusDrawerContent();
  },
  { immediate: true },
);

function handleEscape() {
  if (!props.compact || !props.open) {
    return;
  }
  emit("close");
}
</script>

<template>
  <div
    ref="panelRef"
    :id="drawerId"
    :aria-describedby="compact ? descriptionId : undefined"
    :aria-hidden="compact ? !open : undefined"
    :aria-labelledby="compact ? titleId : undefined"
    :aria-modal="compact && open ? 'true' : undefined"
    :class="[side === 'left' ? 'left-aside' : 'right-aside', { 'mobile-drawer-open': open }]"
    :role="compact ? 'dialog' : undefined"
    :tabindex="compact ? -1 : undefined"
    class=":uno: aside relative flex h-full flex-none flex-col overflow-hidden"
    @keydown.esc.prevent.stop="handleEscape"
  >
    <h2 v-if="compact" :id="titleId" class=":uno: sr-only">{{ title }}</h2>
    <p v-if="compact" :id="descriptionId" class=":uno: sr-only">
      按 Esc 关闭当前侧边栏并返回切换按钮。
    </p>
    <slot />
  </div>
</template>
