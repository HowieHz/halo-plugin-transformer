<script lang="ts" setup>
import { Toast, VButton } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import {
  type CodeSnippet,
  type EditableInjectionRule,
  makeRule,
  MODE_OPTIONS,
  POSITION_OPTIONS,
} from '@/types'
import {
  formatMatchRuleError,
  getDomRulePerformanceWarning,
  isValidMatchRule,
  resolveRuleMatchRule,
} from '@/views/composables/matchRule'
import BaseFormModal from './BaseFormModal.vue'
import ItemPicker from './ItemPicker.vue'
import FormField from './FormField.vue'
import MatchRuleEditor from './MatchRuleEditor.vue'
import { updateSelectByWheel } from '@/views/composables/selectWheel.ts'
import { parseRuleTransfer } from '@/views/composables/transfer.ts'

defineProps<{
  snippets: CodeSnippet[]
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit', rule: EditableInjectionRule, snippetIds: string[]): void
}>()

const rule = ref<EditableInjectionRule>(makeRule())
const selectedSnippetIds = ref<string[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const initialRule = makeRule()

onMounted(reset)

function reset() {
  rule.value = makeRule()
  selectedSnippetIds.value = []
}

const needsTarget = computed(() => rule.value.mode === 'ID' || rule.value.mode === 'SELECTOR')
const needsSnippets = computed(() => rule.value.position !== 'REMOVE')
const needsWrapMarker = computed(() => rule.value.position !== 'REMOVE')
const matchFieldError = computed(() => {
  if (!needsTarget.value) {
    return null
  }
  return rule.value.match.trim() ? null : '请填写匹配内容'
})
const performanceWarning = computed(() => getDomRulePerformanceWarning(rule.value))

function toggleSnippet(id: string) {
  const idx = selectedSnippetIds.value.indexOf(id)
  if (idx === -1) selectedSnippetIds.value.push(id)
  else selectedSnippetIds.value.splice(idx, 1)
}

function handleSubmit() {
  emit('submit', rule.value, selectedSnippetIds.value)
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
    rule.value = parseRuleTransfer(await file.text())
    selectedSnippetIds.value = []
    const importedValidationError = resolveImportedRuleValidationError(rule.value)
    if (importedValidationError) {
      Toast.warning(`已导入注入规则 JSON，但当前内容仍有错误：${importedValidationError}`)
    } else {
      Toast.success('已导入注入规则 JSON')
    }
  } catch (error) {
    Toast.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    input.value = ''
  }
}

function resolveImportedRuleValidationError(importedRule: EditableInjectionRule) {
  if (
    (importedRule.mode === 'SELECTOR' || importedRule.mode === 'ID') &&
    !importedRule.match.trim()
  ) {
    return '请填写匹配内容'
  }
  const result = resolveRuleMatchRule(importedRule)
  if (result.error) {
    return `匹配规则有误：${formatMatchRuleError(result.error)}`
  }
  if (!isValidMatchRule(result.rule)) {
    return '请完善匹配规则'
  }
  return null
}

const validationError = computed(() => {
  return resolveImportedRuleValidationError(rule.value)
})

const dirty = computed(() => {
  return (
    JSON.stringify({
      enabled: rule.value.enabled,
      name: rule.value.name,
      description: rule.value.description,
      mode: rule.value.mode,
      match: rule.value.match,
      matchRule: rule.value.matchRule,
      position: rule.value.position,
      wrapMarker: rule.value.wrapMarker,
      matchRuleSource: rule.value.matchRuleSource,
      snippetIds: selectedSnippetIds.value,
    }) !==
    JSON.stringify({
      enabled: initialRule.enabled,
      name: initialRule.name,
      description: initialRule.description,
      mode: initialRule.mode,
      match: initialRule.match,
      matchRule: initialRule.matchRule,
      position: initialRule.position,
      wrapMarker: initialRule.wrapMarker,
      matchRuleSource: initialRule.matchRuleSource,
      snippetIds: [],
    })
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
    rule: {
      ...rule.value,
      matchRule: JSON.parse(JSON.stringify(rule.value.matchRule)),
    },
    snippetIds: [...selectedSnippetIds.value],
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
    :show-picker="needsSnippets"
    title="新建注入规则"
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
          v-model="rule.name"
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
          placeholder="不填默认为 ID"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="描述">
        <input
          :id="inputId"
          v-model="rule.description"
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none"
          placeholder="说明此规则的用途"
        />
      </FormField>

      <FormField v-slot="{ inputId }" label="注入模式" required>
        <select
          :id="inputId"
          v-model="rule.mode"
          class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none bg-white"
          @wheel="updateSelectByWheel"
        >
          <option v-for="o in MODE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
      </FormField>

      <template v-if="needsTarget">
        <FormField
          v-slot="{ inputId }"
          :invalid="!!matchFieldError"
          :label="rule.mode === 'SELECTOR' ? 'CSS 选择器' : '元素 ID'"
          required
        >
          <div class=":uno: space-y-1">
            <input
              :id="inputId"
              v-model="rule.match"
              :aria-invalid="!!matchFieldError"
              :placeholder="rule.mode === 'SELECTOR' ? 'div[class=content]' : 'main-content'"
              :class="
                matchFieldError
                  ? ':uno: border-red-300 bg-red-50/40 focus:border-red-500'
                  : ':uno: border-gray-200 focus:border-primary'
              "
              class=":uno: w-full rounded-md border px-3 py-1.5 text-sm font-mono focus:outline-none"
            />
            <p
              v-if="matchFieldError"
              aria-live="polite"
              class=":uno: text-xs text-red-500"
              role="alert"
            >
              {{ matchFieldError }}
            </p>
          </div>
        </FormField>

        <FormField v-slot="{ inputId }" label="插入位置">
          <select
            :id="inputId"
            v-model="rule.position"
            class=":uno: w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-primary focus:outline-none bg-white"
            @wheel="updateSelectByWheel"
          >
            <option v-for="o in POSITION_OPTIONS" :key="o.value" :value="o.value">
              {{ o.label }}
            </option>
          </select>
        </FormField>
      </template>

      <FormField v-if="needsWrapMarker" v-slot="{ inputId }">
        <label class=":uno: inline-flex items-center gap-2 text-sm text-gray-700">
          <input :id="inputId" v-model="rule.wrapMarker" type="checkbox" />
          输出注释标记
        </label>
      </FormField>

      <FormField v-slot="{ inputId, labelId }" label="匹配规则" required>
        <div :id="inputId" :aria-labelledby="labelId">
          <MatchRuleEditor
            :model-value="rule.matchRule"
            :source="rule.matchRuleSource"
            @change="void 0"
            @update:state="Object.assign(rule, $event)"
          />
        </div>
        <div
          v-if="performanceWarning"
          aria-live="polite"
          class=":uno: mt-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800"
        >
          {{ performanceWarning }}
        </div>
      </FormField>
    </template>

    <template v-if="needsSnippets" #picker>
      <div class=":uno: flex items-center justify-between">
        <label class=":uno: text-xs font-medium text-gray-600">关联代码块</label>
        <span class=":uno: text-xs text-gray-400"> {{ selectedSnippetIds.length }} 个已选 </span>
      </div>
      <ItemPicker
        :items="snippets"
        label="关联代码块选择列表"
        :selected-ids="selectedSnippetIds"
        empty-text="暂无代码块, 请先创建"
        @toggle="toggleSnippet"
      />
    </template>
  </BaseFormModal>
</template>
