<script lang="ts" setup>
defineProps<{
  enabled: boolean
  label: string
  titleWhenEnabled: string
  titleWhenDisabled: string
}>()

const emit = defineEmits<{
  (e: 'toggle'): void
}>()
</script>

<template>
  <button
    :aria-checked="enabled"
    :aria-label="label"
    :title="enabled ? titleWhenEnabled : titleWhenDisabled"
    class=":uno: relative h-6 w-11 inline-flex items-center rounded-full border border-gray-200 bg-gray-200 px-0.5 transition-colors duration-200 ease-out focus:outline-none focus:ring-2 focus:ring-primary/25"
    role="switch"
    type="button"
    @click="emit('toggle')"
  >
    <span
      :class="enabled ? ':uno: bg-primary translate-x-5' : ':uno: bg-white translate-x-0'"
      class=":uno: absolute left-0.5 h-4.5 w-4.5 rounded-full shadow-sm transition-all duration-200 ease-out"
    />
    <span
      :class="enabled ? ':uno: bg-primary/20' : ':uno: bg-transparent'"
      class=":uno: absolute inset-0 rounded-full transition-colors duration-200 ease-out"
    />
  </button>
</template>
