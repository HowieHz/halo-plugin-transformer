<script lang="ts" setup>
import { ref } from 'vue'
import { VButton, VModal, VSpace } from '@halo-dev/components'
import EnabledSwitch from './EnabledSwitch.vue'

defineProps<{
  itemCount: number
  resourceLabel: string
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit', enabled: boolean): void
}>()

const enabled = ref(true)
</script>

<template>
  <VModal title="确认批量导入" :width="560" @close="emit('close')">
    <div class=":uno: space-y-4 text-sm leading-6 text-gray-700">
      <p>即将批量导入 {{ itemCount }} 个{{ resourceLabel }}。</p>
      <div
        class=":uno: flex items-center justify-between rounded-md border border-gray-200 px-4 py-3"
      >
        <div>
          <p class=":uno: font-medium text-gray-900">导入后启用状态</p>
          <p class=":uno: text-xs text-gray-500">
            关闭后，本次导入的全部{{ resourceLabel }}都会以禁用状态写入。
          </p>
        </div>
        <EnabledSwitch
          :enabled="enabled"
          :label="`切换批量导入${resourceLabel}的启用状态`"
          title-when-disabled="当前本次批量导入会全部保持禁用"
          title-when-enabled="当前本次批量导入会全部保持启用"
          @toggle="enabled = !enabled"
        />
      </div>
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">取消</VButton>
        <VButton :disabled="submitting" type="secondary" @click="emit('submit', enabled)">
          {{ submitting ? '导入中...' : '开始导入' }}
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
