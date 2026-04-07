<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'
import {
  clampRuntimeOrder,
  describeRuntimeOrderRange,
  formatRuntimeOrder,
  snapRuntimeOrderToPreset,
} from '@/views/composables/runtimeOrder'

const RUNTIME_ORDER_HORIZONTAL_INSET_PX = 12

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
const runtimeOrderHelpText = computed(() =>
  manualMode.value
    ? '数字越小，越先执行；数字相同时，先按规则名称排序，再按规则 ID 排序。'
    : '优先级越高，越先执行；后面的挡位依次更晚执行。',
)

function updateRuntimeOrder(value: number) {
  emit('update:modelValue', clampRuntimeOrder(value))
}

function updateRuntimeOrderFromSlider(value: number) {
  sliderDraft.value = clampRuntimeOrder(value)
}

/**
 * why: 滑条拖动会连续触发 input；如果每一步都向外提交，
 * 字段级撤销就会退成很多个细碎小步，违背“一次拖拽 = 一次编辑”的语义。
 */
function commitRuntimeOrderFromSlider() {
  const snapped = snapRuntimeOrderToPreset(sliderDraft.value)
  sliderDraft.value = snapped
  emit('update:modelValue', snapped)
}

function updateRuntimeOrderPresetValue(value: number) {
  updateRuntimeOrder(value)
}

function stepTrackPositionStyle(value: number) {
  return `calc(${RUNTIME_ORDER_HORIZONTAL_INSET_PX}px + (100% - ${
    RUNTIME_ORDER_HORIZONTAL_INSET_PX * 2
  }px) * ${value / RUNTIME_ORDER_MAX})`
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
        只影响同一种注入模式下的规则顺序。{{ runtimeOrderHelpText }}
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
      <div class="runtime-order-slider-shell">
        <input
          :aria-valuetext="currentStepLabel"
          :max="RUNTIME_ORDER_MAX"
          min="0"
          step="1"
          :value="sliderDraft"
          class="runtime-order-input"
          type="range"
          @input="updateRuntimeOrderFromSlider(Number(($event.target as HTMLInputElement).value))"
          @change="commitRuntimeOrderFromSlider"
        />
        <div aria-hidden="true" class="runtime-order-visual">
          <div class="runtime-order-track" />
          <div
            :style="{
              left: stepTrackPositionStyle(previewRuntimeOrder),
            }"
            class="runtime-order-thumb"
          />
        </div>
      </div>
      <div
        class="runtime-order-labels :uno: relative h-4 overflow-visible text-[11px] text-gray-400"
      >
        <button
          v-for="step in RUNTIME_ORDER_STEPS"
          :key="step.value"
          :style="{
            left: stepTrackPositionStyle(step.value),
          }"
          :class="
            step.value === previewRuntimeOrder
              ? ':uno: text-primary'
              : ':uno: text-gray-400 hover:text-gray-600'
          "
          class="runtime-order-label :uno: absolute top-0 whitespace-nowrap bg-transparent p-0 text-[11px]"
          type="button"
          @click="updateRuntimeOrderPresetValue(step.value)"
        >
          {{ step.label }}
        </button>
      </div>
    </template>

    <p v-if="manualMode" class=":uno: text-xs text-gray-500">{{ currentRangeHint }}</p>
  </div>
</template>

<style scoped>
.runtime-order-slider-shell {
  --runtime-order-track-height: 0.25rem;
  --runtime-order-thumb-size: 0.875rem;
  --runtime-order-horizontal-inset: 8px;
  position: relative;
  height: 1.5rem;
}

.runtime-order-input {
  position: absolute;
  inset: 0;
  z-index: 2;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

.runtime-order-visual {
  position: absolute;
  inset: 0;
}

.runtime-order-track {
  position: absolute;
  top: 50%;
  left: var(--runtime-order-horizontal-inset);
  right: var(--runtime-order-horizontal-inset);
  height: var(--runtime-order-track-height);
  transform: translateY(-50%);
  border-radius: 9999px;
  background: rgb(209 213 219);
}

.runtime-order-thumb {
  position: absolute;
  top: 50%;
  width: var(--runtime-order-thumb-size);
  height: var(--runtime-order-thumb-size);
  transform: translate(-50%, -50%);
  border: 2px solid rgb(255 255 255);
  border-radius: 9999px;
  background: rgb(59 130 246);
  box-shadow: 0 1px 3px rgb(15 23 42 / 0.24);
  transition:
    transform 120ms ease,
    box-shadow 120ms ease;
}

.runtime-order-input:active + .runtime-order-visual .runtime-order-thumb,
.runtime-order-input:focus-visible + .runtime-order-visual .runtime-order-thumb {
  transform: translate(-50%, -50%) scale(1.18);
  box-shadow: 0 2px 6px rgb(15 23 42 / 0.32);
}

.runtime-order-labels {
  margin-top: -0.375rem;
}

.runtime-order-label {
  transform: translateX(-50%);
}
</style>
