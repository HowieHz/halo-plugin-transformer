<script lang="ts" setup>
import { Toast, VButton } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import type { CodeSnippet, InjectionRule } from '@/types'
import { makeSnippet } from '@/types'
import BaseFormModal from './BaseFormModal.vue'
import ItemPicker from './ItemPicker.vue'
import FormField from './FormField.vue'
import { rulePreview } from '@/views/composables/util'
import { parseSnippetTransfer } from '@/views/composables/transfer'

const props = defineProps<{
  rules: InjectionRule[]
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit', snippet: CodeSnippet, ruleIds: string[]): void
}>()

const snippet = ref<CodeSnippet>(makeSnippet())
const selectedRuleIds = ref<string[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const initialSnippet = makeSnippet()
const codeScrollTop = ref(0)

const codeLines = computed(() => {
  const content = snippet.value.code.replace(/\r\n/g, '\n')
  return content.split('\n').length
})

const codeLineNumberStyle = computed(() => ({
  transform: `translateY(-${codeScrollTop.value}px)`,
}))

onMounted(reset)

function reset() {
  snippet.value = makeSnippet()
  selectedRuleIds.value = []
}

function toggleRule(id: string) {
  const ids = selectedRuleIds.value
  const idx = ids.indexOf(id)
  if (idx === -1) selectedRuleIds.value.push(id)
  else selectedRuleIds.value.splice(idx, 1)
}

function handleSubmit() {
  emit('submit', snippet.value, selectedRuleIds.value)
}

function syncCodeScroll(event: Event) {
  codeScrollTop.value = (event.target as HTMLTextAreaElement).scrollTop
}

function openImportPicker() {
  fileInput.value?.click()
}

async function handleImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  try {
    snippet.value = parseSnippetTransfer(await file.text())
    selectedRuleIds.value = []
    if (!snippet.value.code.trim()) {
      Toast.warning('已导入代码块 JSON，但当前内容仍有错误：代码内容不能为空')
    } else {
      Toast.success('已导入代码块 JSON')
    }
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    input.value = ''
  }
}

const selectableRules = computed(() => props.rules.filter((rule) => rule.position !== 'REMOVE'))
const validationError = computed(() => (!snippet.value.code.trim() ? '代码内容不能为空' : null))
const dirty = computed(() => {
  return (
    snippet.value.enabled !== initialSnippet.enabled ||
    snippet.value.name !== initialSnippet.name ||
    snippet.value.description !== initialSnippet.description ||
    snippet.value.code !== initialSnippet.code ||
    selectedRuleIds.value.length > 0
  )
})

function hasUnsavedChanges() {
  return dirty.value
}

function getValidationError() {
  return validationError.value
}

function getSubmitPayload() {
  return {
    snippet: {
      ...snippet.value,
    },
    ruleIds: [...selectedRuleIds.value],
  }
}

defineExpose({
  reset,
  hasUnsavedChanges,
  getValidationError,
  getSubmitPayload,
})
</script>

<template>
  <BaseFormModal
    hide-default-title
    :saving="saving"
    title="新建代码块"
    @close="emit('close')"
    @submit="handleSubmit"
  >
    <template #actions>
      <div class=":uno: flex items-center justify-end">
        <input
          ref="fileInput"
          accept="application/json,.json"
          class=":uno: hidden"
          type="file"
          @change="handleImport"
        />
        <VButton size="sm" type="secondary" @click="openImportPicker">导入 JSON</VButton>
      </div>
    </template>

    <template #form>
      <FormField v-slot="{ inputId }" label="名称">
        <input
          :id="inputId"
          v-model="snippet.name"
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
          placeholder="不填默认为 ID"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="描述">
        <input
          :id="inputId"
          v-model="snippet.description"
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
          placeholder="说明此代码块的用途"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="代码内容" required>
        <div
          class=":uno: relative h-72 min-h-72 resize-y overflow-hidden rounded-md border border-gray-200 bg-white focus-within:border-primary"
        >
          <div class=":uno: relative z-1 h-full flex">
            <div
              aria-hidden="true"
              class=":uno: relative h-full overflow-hidden select-none border-r border-gray-100 bg-gray-50 px-2 py-2 text-right text-xs text-gray-400"
            >
              <div :style="codeLineNumberStyle">
                <div
                  v-for="lineNumber in codeLines"
                  :key="lineNumber"
                  class=":uno: leading-6"
                  style="height: 24px"
                >
                  {{ lineNumber }}
                </div>
              </div>
            </div>
            <textarea
              :id="inputId"
              v-model="snippet.code"
              autofocus
              class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 py-2 text-sm font-mono leading-6 focus:outline-none"
              placeholder="输入 HTML 代码"
              spellcheck="false"
              @scroll="syncCodeScroll"
            />
          </div>
        </div>
      </FormField>
    </template>

    <template #picker>
      <div class=":uno: flex items-center justify-between">
        <label class=":uno: text-xs font-medium text-gray-600">关联规则</label>
        <span class=":uno: text-xs text-gray-400">{{ selectedRuleIds.length }} 个已选</span>
      </div>
      <ItemPicker
        :items="selectableRules"
        label="关联规则选择列表"
        :preview-fn="rulePreview"
        :selected-ids="selectedRuleIds"
        empty-text="暂无规则, 请先创建"
        @toggle="toggleRule"
      />
    </template>
  </BaseFormModal>
</template>
