<script lang="ts" setup>
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    previewStartMs?: number
    resetPressMs?: number
  }>(),
  {
    previewStartMs: 500,
    resetPressMs: 3000,
  },
)

const emit = defineEmits<{
  (e: 'undo'): void
  (e: 'reset'): void
}>()

const pressing = ref(false)
const progress = ref(0)
const suppressClick = ref(false)
const resetTriggered = ref(false)
let animationFrameId: number | null = null
let pressStartedAt = 0

function stopProgress(resetProgress = true) {
  if (animationFrameId !== null) {
    cancelAnimationFrame(animationFrameId)
    animationFrameId = null
  }
  pressing.value = false
  if (resetProgress) {
    progress.value = 0
  }
}

function tickProgress() {
  if (!pressing.value) {
    return
  }
  const elapsed = performance.now() - pressStartedAt
  if (elapsed <= props.previewStartMs) {
    progress.value = 0
  } else {
    progress.value = Math.min(
      100,
      ((elapsed - props.previewStartMs) / (props.resetPressMs - props.previewStartMs)) * 100,
    )
  }
  if (!resetTriggered.value && elapsed >= props.resetPressMs) {
    resetTriggered.value = true
    suppressClick.value = true
    progress.value = 100
    emit('reset')
    return
  }
  if (progress.value < 100) {
    animationFrameId = requestAnimationFrame(tickProgress)
  }
}

function startPress() {
  stopProgress()
  suppressClick.value = false
  resetTriggered.value = false
  pressing.value = true
  pressStartedAt = performance.now()
  progress.value = 0
  animationFrameId = requestAnimationFrame(tickProgress)
}

function finishPress(triggerAction: boolean) {
  if (!pressing.value) {
    return
  }
  const elapsed = performance.now() - pressStartedAt
  suppressClick.value = true
  if (triggerAction && !resetTriggered.value) {
    if (elapsed >= props.resetPressMs) {
      emit('reset')
    } else {
      emit('undo')
    }
  }
  stopProgress()
}

function handleClick() {
  if (suppressClick.value) {
    suppressClick.value = false
    return
  }
  emit('undo')
}

const buttonStateClass = computed(() =>
  progress.value >= 100 ? ':uno: border-red-600 text-white' : ':uno: border-gray-200 text-gray-500',
)

const buttonText = computed(() => {
  if (!pressing.value || progress.value === 0) {
    return '撤销修改'
  }
  return '撤销全部'
})

const buttonStyle = computed(() => {
  if (progress.value <= 0) {
    return {
      background: 'rgb(255 255 255)',
    }
  }
  return {
    background: `linear-gradient(90deg, rgb(220 38 38) 0%, rgb(220 38 38) ${progress.value}%, rgb(255 255 255) ${progress.value}%, rgb(255 255 255) 100%)`,
  }
})
</script>

<template>
  <button
    :aria-label="
      pressing && progress > 0
        ? '继续长按可撤销全部修改'
        : '撤销本字段的上一步修改，长按可撤销全部修改'
    "
    :class="buttonStateClass"
    :style="buttonStyle"
    class=":uno: relative overflow-hidden rounded border px-2 py-0.5 text-xs transition-colors hover:border-gray-300 hover:text-gray-700"
    title="单击，或按住 0.5 秒内松开：撤销上一步；继续按到 3 秒：自动恢复初始值"
    type="button"
    @click="handleClick"
    @pointercancel="finishPress(false)"
    @pointerdown="startPress"
    @pointerleave="finishPress(false)"
    @pointerup="finishPress(true)"
  >
    <span class=":uno: relative z-1">{{ buttonText }}</span>
  </button>
</template>
