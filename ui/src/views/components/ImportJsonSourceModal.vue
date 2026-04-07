<script lang="ts" setup>
import { VButton, VModal, VSpace } from '@halo-dev/components'
import { IMPORT_JSON_SOURCE_ACTIONS, type ImportJsonSourceAction } from './importJsonSource'

defineProps<{
  resourceLabel: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'import-from-clipboard'): void
  (e: 'import-from-file'): void
}>()

function emitAction(action: ImportJsonSourceAction) {
  switch (action) {
    case 'close':
      emit('close')
      return
    case 'import-from-clipboard':
      emit('import-from-clipboard')
      return
    case 'import-from-file':
      emit('import-from-file')
  }
}
</script>

<template>
  <VModal title="导入" :width="520" @close="emit('close')">
    <div class=":uno: space-y-3">
      <p class=":uno: text-sm leading-6 text-gray-700">
        你可以直接读取当前剪贴板，或从本地 .json 文件导入。
      </p>
    </div>

    <template #footer>
      <VSpace>
        <VButton
          v-for="item in IMPORT_JSON_SOURCE_ACTIONS"
          :key="item.action"
          :type="item.secondary ? 'secondary' : undefined"
          @click="emitAction(item.action)"
        >
          {{ item.label }}
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
