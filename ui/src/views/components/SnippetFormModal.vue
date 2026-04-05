<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import type { CodeSnippet, InjectionRule } from '@/types'
import { makeSnippet } from '@/types'
import BaseFormModal from './BaseFormModal.vue'
import ItemPicker from './ItemPicker.vue'
import FormField from './FormField.vue'
import { rulePreview } from '@/views/composables/util'

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

onMounted(reset)

function reset() {
  snippet.value = makeSnippet()
  selectedRuleIds.value = []
}

function toggleRule(id: string) {
  const ids = snippet.value.ruleIds ?? []
  const idx = ids.indexOf(id)
  if (idx === -1) selectedRuleIds.value.push(id)
  else selectedRuleIds.value.splice(idx, 1)
}

function handleSubmit() {
  emit('submit', snippet.value, selectedRuleIds.value)
}

const selectableRules = computed(() => props.rules.filter((rule) => rule.position !== 'REMOVE'))
</script>

<template>
  <BaseFormModal :saving="saving" title="新建代码块" @close="emit('close')" @submit="handleSubmit">
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
        <textarea
          :id="inputId"
          v-model="snippet.code"
          autofocus
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-2 text-xs font-mono focus:border-primary focus:outline-none resize-none"
          placeholder="输入 HTML 代码"
          rows="12"
          spellcheck="false"
        />
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
