<script lang="ts" setup>
import { VButton } from '@halo-dev/components'

defineProps<{
  dirty: boolean
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'save'): void
}>()
</script>

<template>
  <div
    class=":uno: flex items-center justify-between border-t border-gray-100 bg-white px-4 pt-3 pb-2"
  >
    <span aria-live="polite" class=":uno: text-xs" role="status">
      <span v-if="dirty" class=":uno: text-yellow-600">有未保存的修改</span>
    </span>
    <VButton
      :aria-label="saving ? '正在保存当前修改' : '保存当前修改'"
      :disabled="saving"
      type="secondary"
      @click="emit('save')"
    >
      {{ saving ? '保存中...' : '保存' }}
    </VButton>
  </div>
</template>
