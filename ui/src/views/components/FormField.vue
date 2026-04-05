<script lang="ts" setup>
import { computed, useId } from 'vue'

const props = defineProps<{
  label?: string
  required?: boolean
}>()

const fieldId = useId()
const inputId = computed(() => `field-input-${fieldId}`)
const labelId = computed(() => `field-label-${fieldId}`)
</script>

<template>
  <div class=":uno: space-y-1">
    <div
      v-if="label || $slots.actions"
      class=":uno: flex min-h-6 items-center justify-between gap-2"
    >
      <label
        v-if="label"
        :for="inputId"
        :id="labelId"
        class=":uno: text-xs font-medium text-gray-600"
      >
        {{ props.label }}
        <span v-if="required" class=":uno: text-red-500">*</span>
      </label>
      <slot name="actions" />
    </div>
    <slot :input-id="inputId" :label-id="labelId" />
  </div>
</template>
