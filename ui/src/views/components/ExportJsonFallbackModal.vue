<script lang="ts" setup>
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import { nextTick, ref, watch } from 'vue'
import { downloadTransferFile } from '@/views/composables/transfer'

const props = defineProps<{
  fileName: string
  content: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)

watch(
  () => props.content,
  async () => {
    await nextTick()
    textareaRef.value?.focus()
    textareaRef.value?.select()
  },
  { immediate: true },
)

async function saveAs() {
  try {
    await downloadTransferFile({
      fileName: props.fileName,
      content: props.content,
    })
    Toast.success('导出完成')
    emit('close')
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      return
    }
    Toast.warning('当前环境暂时无法直接保存为文件，请先复制下面的 JSON')
  }
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(props.content)
    Toast.success('已复制导出内容')
    return
  } catch {
    textareaRef.value?.focus()
    textareaRef.value?.select()
    Toast.warning('没有成功自动复制，请手动复制下面的 JSON')
  }
}
</script>

<template>
  <VModal title="导出 JSON" :width="860" @close="emit('close')">
    <div class=":uno: space-y-3">
      <p class=":uno: text-sm leading-6 text-gray-700">
        你可以预览下面这份 JSON，并选择“复制全部”或“另存为”。 建议文件名为
        <span class=":uno: font-mono text-xs text-gray-500">{{ fileName }}</span>
        。
      </p>
      <textarea
        ref="textareaRef"
        :value="content"
        aria-label="可手动复制的导出 JSON"
        class=":uno: min-h-96 w-full rounded-md border border-gray-200 px-3 py-2 text-xs font-mono focus:border-primary focus:outline-none"
        readonly
        spellcheck="false"
      />
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="emit('close')">关闭</VButton>
        <VButton type="secondary" @click="copyAll">复制全部</VButton>
        <VButton type="secondary" @click="saveAs">另存为</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
