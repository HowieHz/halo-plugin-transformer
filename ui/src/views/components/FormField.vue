<script lang="ts" setup>
import { computed, useId } from "vue";

const props = defineProps<{
  label?: string;
  labelTitle?: string;
  required?: boolean;
  invalid?: boolean;
  labelSemantics?: "native" | "group";
}>();

const fieldId = useId();
const inputId = computed(() => `field-input-${fieldId}`);
const labelId = computed(() => `field-label-${fieldId}`);
</script>

<template>
  <div class=":uno: space-y-1">
    <div
      v-if="label || $slots.actions"
      class=":uno: flex min-h-6 items-center justify-between gap-2"
    >
      <label
        v-if="label && props.labelSemantics !== 'group'"
        :for="inputId"
        :id="labelId"
        :title="labelTitle"
        :class="props.invalid ? ':uno: text-red-600' : ':uno: text-gray-600'"
        class=":uno: text-xs font-medium"
      >
        {{ props.label }}
        <span v-if="required" class=":uno: text-red-500">*</span>
      </label>
      <span
        v-else-if="label"
        :id="labelId"
        :title="labelTitle"
        :class="props.invalid ? ':uno: text-red-600' : ':uno: text-gray-600'"
        class=":uno: text-xs font-medium"
      >
        {{ props.label }}
        <span v-if="required" class=":uno: text-red-500">*</span>
      </span>
      <slot name="actions" />
    </div>
    <slot :input-id="inputId" :label-id="labelId" :required="required" />
  </div>
</template>
