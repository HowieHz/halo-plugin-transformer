<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'
import {
  clampRuntimeOrder,
  describeRuntimeOrderRange,
  formatRuntimeOrder,
  snapRuntimeOrderToPreset,
} from '@/views/composables/runtimeOrder'

const props = defineProps<{
  modelValue: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const manualMode = ref(false)
const manualDraft = ref(String(props.modelValue))
const sliderDraft = ref(props.modelValue)

watch(
  () => props.modelValue,
  (value) => {
    manualDraft.value = String(value)
    sliderDraft.value = value
  },
  { immediate: true },
)

const displayedRuntimeOrder = computed(() => {
  if (!manualMode.value) {
    return sliderDraft.value
  }
  const parsed = Number(manualDraft.value)
  return Number.isFinite(parsed) ? clampRuntimeOrder(parsed) : props.modelValue
})

const previewRuntimeOrder = computed(() =>
  manualMode.value ? displayedRuntimeOrder.value : snapRuntimeOrderToPreset(sliderDraft.value),
)

const currentStepLabel = computed(() => {
  const exact = RUNTIME_ORDER_STEPS.find((step) => step.value === previewRuntimeOrder.value)
  if (exact) {
    return `${exact.label}（${formatRuntimeOrder(exact.value)}）`
  }
  return `自定义 ${formatRuntimeOrder(previewRuntimeOrder.value)}`
})

const currentRangeHint = computed(() => describeRuntimeOrderRange(previewRuntimeOrder.value))
const interiorRuntimeOrderSteps = computed(() => RUNTIME_ORDER_STEPS.slice(1, -1))

function updateRuntimeOrder(value: number) {
  emit('update:modelValue', clampRuntimeOrder(value))
}

function updateRuntimeOrderFromSlider(value: number) {
  const normalized = clampRuntimeOrder(value)
  sliderDraft.value = normalized
  emit('update:modelValue', normalized)
}

function commitRuntimeOrderFromSlider() {
  const snapped = snapRuntimeOrderToPreset(sliderDraft.value)
  sliderDraft.value = snapped
  emit('update:modelValue', snapped)
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
  sliderDraft.value = props.modelValue
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
      <div class=":uno: relative">
        <div class=":uno: pointer-events-none absolute inset-x-0 top-1/2 h-4 -translate-y-1/2">
          <span
            v-for="step in interiorRuntimeOrderSteps"
            :key="`${step.value}-tick`"
            :style="{
              left: stepPositionPercent(step.value),
              transform: 'translateX(-50%)',
            }"
            class=":uno: absolute h-4 border-l border-dashed border-gray-400/70"
          />
        </div>
        <input
          :aria-valuetext="currentStepLabel"
          :max="RUNTIME_ORDER_MAX"
          min="0"
          step="1"
          :value="sliderDraft"
          class="runtime-order-slider :uno: relative z-1 w-full bg-transparent"
          type="range"
          @input="updateRuntimeOrderFromSlider(Number(($event.target as HTMLInputElement).value))"
          @change="commitRuntimeOrderFromSlider"
        />
      </div>
      <div class=":uno: relative -mt-1 h-4 text-[11px] text-gray-400">
        <button
          v-for="(step, index) in RUNTIME_ORDER_STEPS"
          :key="step.value"
          :style="{
            left: stepPositionPercent(step.value),
            transform: stepTransform(index),
          }"
          :class="
            step.value === previewRuntimeOrder
              ? ':uno: text-primary'
              : ':uno: text-gray-400 hover:text-gray-600'
          "
          class=":uno: absolute top-0 whitespace-nowrap bg-transparent p-0 text-[11px]"
          type="button"
          @click="updateRuntimeOrderPreset(index)"
        >
          {{ step.label }}
        </button>
      </div>
    </template>

    <p class=":uno: text-xs text-gray-500">{{ currentRangeHint }}</p>
  </div>
</template>

<style scoped>
.runtime-order-slider {
  appearance: none;
  height: 1.5rem;
}

.runtime-order-slider::-webkit-slider-runnable-track {
  height: 0.25rem;
  border-radius: 9999px;
  background: rgb(209 213 219);
}

.runtime-order-slider::-moz-range-track {
  height: 0.25rem;
  border-radius: 9999px;
  background: rgb(209 213 219);
}

.runtime-order-slider::-webkit-slider-thumb {
  appearance: none;
  width: 0.875rem;
  height: 0.875rem;
  margin-top: -0.3125rem;
  border: 2px solid rgb(255 255 255);
  border-radius: 9999px;
  background: rgb(59 130 246);
  box-shadow: 0 1px 3px rgb(15 23 42 / 0.24);
  transition:
    transform 120ms ease,
    box-shadow 120ms ease;
}

.runtime-order-slider::-moz-range-thumb {
  width: 0.875rem;
  height: 0.875rem;
  border: 2px solid rgb(255 255 255);
  border-radius: 9999px;
  background: rgb(59 130 246);
  box-shadow: 0 1px 3px rgb(15 23 42 / 0.24);
  transition:
    transform 120ms ease,
    box-shadow 120ms ease;
}

.runtime-order-slider:active::-webkit-slider-thumb {
  transform: scale(1.18);
  box-shadow: 0 2px 6px rgb(15 23 42 / 0.32);
}

.runtime-order-slider:active::-moz-range-thumb {
  transform: scale(1.18);
  box-shadow: 0 2px 6px rgb(15 23 42 / 0.32);
}
</style>
