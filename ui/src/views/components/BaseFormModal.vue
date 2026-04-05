<script lang="ts" setup>
import { VButton, VModal, VSpace } from '@halo-dev/components'

defineProps<{
  title: string
  saving: boolean
  submitLabel?: string
  showPicker?: boolean
  hideDefaultTitle?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit'): void
}>()
</script>

<template>
  <VModal :title="hideDefaultTitle ? '' : title" :width="1000" @close="emit('close')">
    <div
      class=":uno: flex injector-editor-container"
      :class="showPicker === false ? '' : 'divide-x divide-gray-100'"
      style="min-height: 400px"
    >
      <div
        class=":uno: px-5 py-4 space-y-4 overflow-y-auto"
        :class="showPicker === false ? 'flex-1' : 'flex-1'"
        :style="showPicker === false ? 'width: 100%' : 'width: 60%'"
      >
        <div
          v-if="hideDefaultTitle || $slots.actions"
          class=":uno: flex items-center justify-between gap-3"
        >
          <h2 v-if="hideDefaultTitle" class=":uno: text-base font-semibold text-gray-900">
            {{ title }}
          </h2>
          <div v-else />
          <slot name="actions" />
        </div>
        <slot name="form" />
      </div>

      <div
        v-if="showPicker !== false"
        class=":uno: flex-none px-4 py-4 space-y-2 overflow-y-auto"
        style="width: 40%"
      >
        <slot name="picker" />
      </div>
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton :disabled="saving" type="secondary" @click="emit('submit')">
          {{ saving ? `${submitLabel ?? '创建'}中...` : (submitLabel ?? '创建') }}
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
