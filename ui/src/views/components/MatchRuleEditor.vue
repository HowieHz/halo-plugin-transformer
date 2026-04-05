<script lang="ts" setup>
import { computed, ref, useId, watch } from 'vue'
import { Dialog, VButton } from '@halo-dev/components'
import type { MatchRule, MatchRuleEditorMode } from '@/types'
import { makeMatchRuleGroup } from '@/types'
import {
  formatMatchRule,
  formatMatchRuleError,
  type MatchRuleValidationError,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateMatchRuleTree,
} from '@/views/composables/matchRule'
import MatchRuleNodeEditor from './MatchRuleNodeEditor.vue'

const props = defineProps<{
  modelValue: MatchRule
  draft?: string
  editorMode?: MatchRuleEditorMode
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: MatchRule): void
  (e: 'update:draft', value: string): void
  (e: 'update:editorMode', value: MatchRuleEditorMode): void
  (
    e: 'update:state',
    value: Partial<{
      matchRule: MatchRule
      matchRuleDraft: string
      matchRuleEditorMode: MatchRuleEditorMode
    }>,
  ): void
  (e: 'change'): void
}>()

const jsonDraft = ref(props.draft || formatMatchRule(props.modelValue))
const editorId = useId()
const simplePanelId = `match-rule-simple-${editorId}`
const jsonPanelId = `match-rule-json-${editorId}`
const jsonErrorId = `match-rule-json-error-${editorId}`
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const jsonScrollTop = ref(0)
const jsonLineHeight = 24
const jsonVerticalPadding = 8
const jsonHighlightInset = 3

watch(
  () => props.draft,
  (value) => {
    if (typeof value === 'string' && value !== jsonDraft.value) {
      jsonDraft.value = value
    }
  },
)

watch(
  () => props.modelValue,
  (value) => {
    if (!props.draft) {
      jsonDraft.value = formatMatchRule(value)
    }
  },
  { deep: true },
)

const currentMode = computed(() => props.editorMode ?? 'SIMPLE')
const parseResult = computed(() => parseMatchRuleDraft(jsonDraft.value))
const parseError = computed(() => formatMatchRuleError(parseResult.value.error))
const jsonLines = computed(() => jsonDraft.value.split('\n'))
const simpleValidateResult = computed(() =>
  validateMatchRuleTree(normalizeMatchRule(props.modelValue)),
)
const simpleValidationError = computed<MatchRuleValidationError | null>(
  () => simpleValidateResult.value.error,
)
const jsonActionLabel = computed(() => (parseResult.value.error ? '重建 JSON' : '格式化 JSON'))
const jsonActionTitle = computed(() =>
  parseResult.value.error
    ? '当前 JSON 有误，将按当前简单模式配置重新生成 JSON'
    : '整理当前 JSON 的缩进与格式',
)
const jsonErrorLine = computed(() => {
  const error = parseResult.value.error
  if (!error) return null
  if (typeof error.line === 'number') return error.line
  return locateJsonPathLine(jsonDraft.value, error.path)
})
const jsonHighlightStyle = computed(() => {
  if (!jsonErrorLine.value) return null
  return {
    height: `${jsonLineHeight - jsonHighlightInset * 2}px`,
    transform: `translateY(${jsonVerticalPadding + (jsonErrorLine.value - 1) * jsonLineHeight + jsonHighlightInset - jsonScrollTop.value}px)`,
  }
})
const jsonLineNumberStyle = computed(() => ({
  transform: `translateY(-${jsonScrollTop.value}px)`,
}))

function isEqualValue(a: unknown, b: unknown) {
  return JSON.stringify(a) === JSON.stringify(b)
}

function emitStatePatch(
  patch: Partial<{
    matchRule: MatchRule
    matchRuleDraft: string
    matchRuleEditorMode: MatchRuleEditorMode
  }>,
) {
  const hasChanges = Object.entries(patch).some(([key, value]) => {
    if (key === 'matchRule') {
      return !isEqualValue(props.modelValue, value)
    }
    if (key === 'matchRuleDraft') {
      return (props.draft ?? '') !== value
    }
    if (key === 'matchRuleEditorMode') {
      return (props.editorMode ?? 'SIMPLE') !== value
    }
    return false
  })

  if (!hasChanges) {
    return
  }

  emit('update:state', patch)
  emit('change')
}

function switchMode(mode: MatchRuleEditorMode) {
  const warningConfig = getModeSwitchWarning(mode)
  if (warningConfig) {
    Dialog.warning({
      title: warningConfig.title,
      description: warningConfig.description,
      confirmType: warningConfig.confirmType,
      onConfirm() {
        applyModeSwitch(mode, warningConfig.overwriteDraft)
      },
    })
    return
  }
  applyModeSwitch(mode, false)
}

function getModeSwitchWarning(mode: MatchRuleEditorMode) {
  if (currentMode.value === 'JSON' && mode === 'SIMPLE' && parseResult.value.error) {
    return {
      title: '切换到简单模式',
      description: `已检测到当前高级模式有错误。
如果继续切换，会回到可视化简单模式。
你当前这份未保存的 JSON 内容会被覆盖。
确认继续吗？`,
      confirmType: 'danger' as const,
      overwriteDraft: true,
    }
  }

  if (currentMode.value === 'SIMPLE' && mode === 'JSON' && simpleValidateResult.value.error) {
    return {
      title: '切换到高级模式',
      description: `已检测到当前简单模式有错误。
如果继续切换，高级模式会用当前简单模式内容重新生成 JSON。
你会直接看到这份带错误的 JSON。
这也会替换你之前在高级模式里未保存的 JSON 草稿。
确认继续吗？`,
      confirmType: 'danger' as const,
      overwriteDraft: false,
    }
  }

  return null
}

function applyModeSwitch(mode: MatchRuleEditorMode, overwriteDraft: boolean) {
  const patch: Partial<{
    matchRule: MatchRule
    matchRuleDraft: string
    matchRuleEditorMode: MatchRuleEditorMode
  }> = {
    matchRuleEditorMode: mode,
  }
  if (mode === 'JSON' || overwriteDraft) {
    const text = formatMatchRule(props.modelValue)
    jsonDraft.value = text
    patch.matchRuleDraft = text
  }
  emitStatePatch(patch)
}

function updateSimple(value: MatchRule) {
  const normalized = normalizeMatchRule(value)
  const draft = formatMatchRule(normalized)
  emitStatePatch({
    matchRule: normalized,
    matchRuleDraft: draft,
    matchRuleEditorMode: 'SIMPLE',
  })
}

function updateJsonDraft(value: string) {
  jsonDraft.value = value
  const patch: Partial<{
    matchRule: MatchRule
    matchRuleDraft: string
    matchRuleEditorMode: MatchRuleEditorMode
  }> = {
    matchRuleDraft: value,
    matchRuleEditorMode: 'JSON',
  }
  if (parseResult.value.rule) {
    patch.matchRule = parseResult.value.rule
  }
  emitStatePatch(patch)
}

function formatJson() {
  const parsed = parseResult.value.rule ?? normalizeMatchRule(props.modelValue)
  const next = formatMatchRule(parsed || makeMatchRuleGroup())
  jsonDraft.value = next
  emitStatePatch({
    matchRule: parsed || makeMatchRuleGroup(),
    matchRuleDraft: next,
    matchRuleEditorMode: 'JSON',
  })
}

function syncJsonScroll(event: Event) {
  jsonScrollTop.value = (event.target as HTMLTextAreaElement).scrollTop
}

function locateJsonPathLine(text: string, path: string) {
  if (!path || path === '$') return null
  const pathMap = buildJsonPathLineMap(text)
  return pathMap.get(path) ?? null
}

function buildJsonPathLineMap(text: string) {
  const tokens = tokenizeJson(text)
  const pathMap = new Map<string, number>()
  let index = 0

  function current() {
    return tokens[index]
  }

  function consume(expected?: string) {
    const token = tokens[index]
    if (!token || (expected && token.type !== expected)) {
      throw new Error('invalid-json')
    }
    index += 1
    return token
  }

  function parseValue(path: string) {
    const token = current()
    if (!token) {
      throw new Error('invalid-json')
    }
    if (token.type === '{') {
      parseObject(path)
      return
    }
    if (token.type === '[') {
      parseArray(path)
      return
    }
    consume()
  }

  function parseObject(path: string) {
    consume('{')
    if (current()?.type === '}') {
      consume('}')
      return
    }
    while (true) {
      const keyToken = consume('string')
      consume(':')
      const childPath = `${path}.${keyToken.value}`
      pathMap.set(childPath, keyToken.line)
      parseValue(childPath)
      if (current()?.type === ',') {
        consume(',')
        continue
      }
      consume('}')
      return
    }
  }

  function parseArray(path: string) {
    consume('[')
    if (current()?.type === ']') {
      consume(']')
      return
    }
    let itemIndex = 0
    while (true) {
      parseValue(`${path}[${itemIndex}]`)
      itemIndex += 1
      if (current()?.type === ',') {
        consume(',')
        continue
      }
      consume(']')
      return
    }
  }

  try {
    parseValue('$')
  } catch {
    return pathMap
  }
  return pathMap
}

function tokenizeJson(text: string) {
  const tokens: Array<{ type: string; value?: string; line: number }> = []
  let index = 0
  let line = 1

  while (index < text.length) {
    const char = text[index]
    if (char === '\n') {
      line += 1
      index += 1
      continue
    }
    if (/\s/.test(char)) {
      index += 1
      continue
    }
    if ('{}[]:,'.includes(char)) {
      tokens.push({ type: char, line })
      index += 1
      continue
    }
    if (char === '"') {
      const startLine = line
      index += 1
      let value = ''
      while (index < text.length) {
        const currentChar = text[index]
        if (currentChar === '\\') {
          value += currentChar
          index += 1
          if (index < text.length) {
            value += text[index]
            index += 1
          }
          continue
        }
        if (currentChar === '"') {
          index += 1
          break
        }
        if (currentChar === '\n') {
          line += 1
        }
        value += currentChar
        index += 1
      }
      tokens.push({ type: 'string', value, line: startLine })
      continue
    }
    const startLine = line
    let literal = ''
    while (index < text.length && !/[\s{}[\]:,]/.test(text[index])) {
      literal += text[index]
      index += 1
    }
    tokens.push({ type: 'literal', value: literal, line: startLine })
  }

  return tokens
}
</script>

<template>
  <div class=":uno: space-y-3">
    <div class=":uno: flex flex-wrap items-center justify-between gap-2">
      <div
        aria-label="匹配规则编辑模式"
        class=":uno: inline-flex rounded-md border border-gray-200 bg-gray-50 p-0.5"
        role="tablist"
      >
        <button
          :aria-controls="simplePanelId"
          :aria-pressed="currentMode === 'SIMPLE'"
          :id="`${simplePanelId}-tab`"
          :tabindex="currentMode === 'SIMPLE' ? 0 : -1"
          :class="
            currentMode === 'SIMPLE'
              ? ':uno: bg-white text-primary shadow-sm'
              : ':uno: text-gray-500'
          "
          class=":uno: rounded px-3 py-1 text-sm transition-colors"
          role="tab"
          type="button"
          @click="switchMode('SIMPLE')"
        >
          简单模式
        </button>
        <button
          :aria-controls="jsonPanelId"
          :aria-pressed="currentMode === 'JSON'"
          :id="`${jsonPanelId}-tab`"
          :tabindex="currentMode === 'JSON' ? 0 : -1"
          :class="
            currentMode === 'JSON' ? ':uno: bg-white text-primary shadow-sm' : ':uno: text-gray-500'
          "
          class=":uno: rounded px-3 py-1 text-sm transition-colors"
          role="tab"
          type="button"
          @click="switchMode('JSON')"
        >
          高级模式
        </button>
      </div>

      <VButton
        v-if="currentMode === 'JSON'"
        :aria-label="jsonActionLabel"
        :title="jsonActionTitle"
        size="sm"
        type="secondary"
        @click="formatJson"
      >
        {{ jsonActionLabel }}
      </VButton>
    </div>

    <template v-if="currentMode === 'SIMPLE'">
      <MatchRuleNodeEditor
        :id="simplePanelId"
        :aria-labelledby="`${simplePanelId}-tab`"
        :model-value="normalizeMatchRule(modelValue)"
        :validation-error="simpleValidationError"
        root
        role="tabpanel"
        @change="emit('change')"
        @update:model-value="updateSimple"
      />
    </template>

    <div
      v-else
      :id="jsonPanelId"
      :aria-labelledby="`${jsonPanelId}-tab`"
      class=":uno: space-y-2"
      role="tabpanel"
    >
      <div
        :class="
          parseError
            ? ':uno: border-red-300 focus-within:border-red-500'
            : ':uno: border-gray-200 focus-within:border-primary'
        "
        class=":uno: relative h-72 min-h-72 resize-y overflow-hidden rounded-md border bg-white"
      >
        <div
          v-if="jsonHighlightStyle"
          class=":uno: pointer-events-none absolute left-0 right-0 z-0 bg-red-50"
          :style="jsonHighlightStyle"
        />

        <div class=":uno: relative z-1 h-full flex">
          <div
            aria-hidden="true"
            class=":uno: relative h-full overflow-hidden select-none border-r border-gray-100 bg-gray-50 px-2 py-2 text-right text-xs text-gray-400"
          >
            <div :style="jsonLineNumberStyle">
              <div
                v-for="lineNumber in jsonLines.length"
                :key="lineNumber"
                :class="lineNumber === jsonErrorLine ? ':uno: font-medium text-red-500' : ''"
                class=":uno: leading-6"
                style="height: 24px"
              >
                {{ lineNumber }}
              </div>
            </div>
          </div>

          <textarea
            ref="textareaRef"
            :value="jsonDraft"
            :aria-describedby="parseError ? jsonErrorId : undefined"
            :aria-invalid="!!parseError"
            aria-label="匹配规则 JSON 编辑器"
            class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 py-2 text-sm font-mono leading-6 focus:outline-none"
            spellcheck="false"
            @input="updateJsonDraft(($event.target as HTMLTextAreaElement).value)"
            @scroll="syncJsonScroll"
          />
        </div>
      </div>
      <p
        v-if="parseError"
        :id="jsonErrorId"
        aria-live="polite"
        class=":uno: text-xs text-red-500"
        role="alert"
      >
        {{ parseError }}
      </p>
    </div>
  </div>
</template>
