<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'
import { clampRuntimeOrder, formatRuntimeOrder } from '@/views/composables/runtimeOrder'

const props = defineProps<{
  modelValue: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const manualMode = ref(false)
const manualDraft = ref(String(props.modelValue))

watch(
  () => props.modelValue,
  (value) => {
    manualDraft.value = String(value)
  },
  { immediate: true },
)

const currentStepLabel = computed(() => {
  const exact = RUNTIME_ORDER_STEPS.find((step) => step.value === props.modelValue)
  if (exact) {
    return `${exact.label}（${formatRuntimeOrder(exact.value)}）`
  }
  return `自定义 ${formatRuntimeOrder(props.modelValue)}`
})

function updateRuntimeOrder(value: number) {
  emit('update:modelValue', clampRuntimeOrder(value))
}

function updateRuntimeOrderPreset(index: number) {
  const preset = RUNTIME_ORDER_STEPS[index]
  if (!preset) {
    return
  }
  updateRuntimeOrder(preset.value)
}

function stepPositionPercent(value: number) {
  return `${(value / RUNTIME_ORDER_MAX) * 100}%`
}

function stepTransform(index: number) {
  if (index === 0) {
    return 'translateX(0)'
  }
  if (index === RUNTIME_ORDER_STEPS.length - 1) {
    return 'translateX(-100%)'
  }
  return 'translateX(-50%)'
}

function handleManualInput(event: Event) {
  manualDraft.value = (event.target as HTMLInputElement).value
}

function commitManualDraft() {
  const trimmed = manualDraft.value.trim()
  if (!trimmed) {
    manualDraft.value = String(props.modelValue)
    return
  }
  updateRuntimeOrder(Number(trimmed))
}

function toggleEditMode() {
  manualMode.value = !manualMode.value
  manualDraft.value = String(props.modelValue)
}
</script>

<template>
  <div class=":uno: space-y-2">
    <div class=":uno: flex items-center justify-between gap-3">
      <p class=":uno: text-xs leading-5 text-gray-500">
        只影响同一种注入模式下的规则顺序。数字越小，越先执行；数字相同时，先按规则名称排序，再按规则
        ID 排序。
      </p>
      <button
        class=":uno: shrink-0 bg-transparent p-0 text-xs text-gray-400 hover:text-gray-600"
        type="button"
        @click="toggleEditMode"
      >
        {{ manualMode ? '使用滑条' : '精确输入' }}
      </button>
    </div>

    <template v-if="manualMode">
      <input
        :max="RUNTIME_ORDER_MAX"
        min="0"
        :value="manualDraft"
        class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm font-mono focus:border-primary focus:outline-none"
        type="number"
        @input="handleManualInput"
        @blur="commitManualDraft"
        @keydown.enter.prevent="commitManualDraft"
      />
    </template>
    <template v-else>
      <input
        :aria-valuetext="currentStepLabel"
        :max="RUNTIME_ORDER_MAX"
        min="0"
        step="1"
        :value="modelValue"
        class=":uno: w-full accent-primary"
        type="range"
        @input="updateRuntimeOrder(Number(($event.target as HTMLInputElement).value))"
      />
      <div class=":uno: relative h-4 text-[11px] text-gray-400">
        <button
          v-for="(step, index) in RUNTIME_ORDER_STEPS"
          :key="step.value"
          :style="{
            left: stepPositionPercent(step.value),
            transform: stepTransform(index),
          }"
          :class="
            step.value === modelValue
              ? ':uno: text-primary'
              : ':uno: text-gray-400 hover:text-gray-600'
          "
          class=":uno: absolute top-0 bg-transparent p-0 text-[11px]"
          type="button"
          @click="updateRuntimeOrderPreset(index)"
        >
          {{ step.label }}
        </button>
      </div>
    </template>

    <p v-if="manualMode" class=":uno: text-xs text-gray-500">当前：{{ currentStepLabel }}</p>
  </div>
</template>
