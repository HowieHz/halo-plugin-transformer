<script lang="ts" setup>
import { computed, provide, ref, useId, watch } from 'vue'
import { Dialog, VButton } from '@halo-dev/components'
import type { MatchRule, MatchRuleEditorMode, MatchRuleSource } from '@/types'
import { makeMatchRuleGroup } from '@/types'
import {
  buildMatchRuleEditorSourceForMode,
  formatMatchRule,
  formatMatchRuleError,
  type MatchRuleValidationError,
  makeJsonDraftSource,
  makeRuleTreeSource,
  normalizeMatchRule,
  parseMatchRuleDraft,
  validateSimpleMatchRuleTree,
} from '@/views/composables/matchRule'
import {
  canMoveMatchRuleNode,
  MATCH_RULE_DRAG_CONTEXT_KEY,
  moveMatchRuleNode,
  type MatchRuleDropPlacement,
  type MatchRuleNodePath,
} from '@/views/composables/matchRuleTreeMove'
import MatchRuleNodeEditor from './MatchRuleNodeEditor.vue'

const props = defineProps<{
  modelValue: MatchRule
  source?: MatchRuleSource
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: MatchRule): void
  (e: 'update:source', value: MatchRuleSource): void
  (
    e: 'update:state',
    value: Partial<{
      matchRule: MatchRule
      matchRuleSource: MatchRuleSource
    }>,
  ): void
  (e: 'change'): void
  (e: 'drag-state-change', active: boolean): void
}>()

const jsonDraft = ref(
  props.source?.kind === 'JSON_DRAFT'
    ? String(props.source.data ?? '')
    : formatMatchRule(props.modelValue),
)
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
  () => props.source,
  (value) => {
    if (value?.kind === 'JSON_DRAFT') {
      const nextDraft = String(value.data ?? '')
      if (nextDraft !== jsonDraft.value) {
        jsonDraft.value = nextDraft
      }
      return
    }
    const nextDraft = formatMatchRule(props.modelValue)
    if (nextDraft !== jsonDraft.value) {
      jsonDraft.value = nextDraft
    }
  },
  { deep: true },
)

watch(
  () => props.modelValue,
  (value) => {
    if (props.source?.kind !== 'JSON_DRAFT') {
      jsonDraft.value = formatMatchRule(value)
    }
  },
  { deep: true },
)

const currentMode = computed<MatchRuleEditorMode>(() =>
  props.source?.kind === 'JSON_DRAFT' ? 'JSON' : 'SIMPLE',
)
const parseResult = computed(() => parseMatchRuleDraft(jsonDraft.value))
const parseError = computed(() => formatMatchRuleError(parseResult.value.error))
const jsonLines = computed(() => jsonDraft.value.split('\n'))
const simpleValidationErrors = computed<MatchRuleValidationError[]>(
  () => validateSimpleMatchRuleTree(normalizeMatchRule(props.modelValue)).errors,
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
const draggingPath = ref<MatchRuleNodePath | null>(null)
const dropTargetPath = ref<MatchRuleNodePath | null>(null)
const dropPlacement = ref<MatchRuleDropPlacement | null>(null)

function isEqualValue(a: unknown, b: unknown) {
  return JSON.stringify(a) === JSON.stringify(b)
}

function emitStatePatch(
  patch: Partial<{
    matchRule: MatchRule
    matchRuleSource: MatchRuleSource
  }>,
) {
  const hasChanges = Object.entries(patch).some(([key, value]) => {
    if (key === 'matchRule') {
      return !isEqualValue(props.modelValue, value)
    }
    if (key === 'matchRuleSource') {
      return !isEqualValue(props.source ?? makeRuleTreeSource(props.modelValue), value)
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
        applyModeSwitch(mode)
      },
    })
    return
  }
  applyModeSwitch(mode)
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
    }
  }

  if (currentMode.value === 'SIMPLE' && mode === 'JSON' && simpleValidationErrors.value.length) {
    return {
      title: '切换到高级模式',
      description: `已检测到当前简单模式有错误。
如果继续切换，高级模式会用当前简单模式内容重新生成 JSON。
你会直接看到这份带错误的 JSON。
这也会替换你之前在高级模式里未保存的 JSON 草稿。
确认继续吗？`,
      confirmType: 'danger' as const,
    }
  }

  return null
}

function applyModeSwitch(mode: MatchRuleEditorMode) {
  const nextState = buildMatchRuleEditorSourceForMode(mode, props.modelValue)
  jsonDraft.value = nextState.jsonDraft
  emitStatePatch({
    matchRule: nextState.matchRule,
    matchRuleSource: nextState.matchRuleSource,
  })
}

function updateSimple(value: MatchRule) {
  const normalized = normalizeMatchRule(value)
  clearDragState()
  emitStatePatch({
    matchRule: normalized,
    matchRuleSource: makeRuleTreeSource(normalized),
  })
}

function updateJsonDraft(value: string) {
  clearDragState()
  jsonDraft.value = value
  const patch: Partial<{ matchRule: MatchRule; matchRuleSource: MatchRuleSource }> = {
    matchRuleSource: makeJsonDraftSource(value),
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
  clearDragState()
  emitStatePatch({
    matchRule: parsed || makeMatchRuleGroup(),
    matchRuleSource: makeJsonDraftSource(next),
  })
}

function clearDragState() {
  draggingPath.value = null
  dropTargetPath.value = null
  dropPlacement.value = null
  emit('drag-state-change', false)
}

function startDrag(path: MatchRuleNodePath, event: DragEvent) {
  draggingPath.value = [...path]
  dropTargetPath.value = null
  dropPlacement.value = null
  emit('drag-state-change', true)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', path.join('.'))
  }
}

function setDropTarget(path: MatchRuleNodePath | null, placement: MatchRuleDropPlacement | null) {
  dropTargetPath.value = path ? [...path] : null
  dropPlacement.value = placement
}

function canDrop(
  sourcePath: MatchRuleNodePath,
  targetPath: MatchRuleNodePath,
  placement: MatchRuleDropPlacement,
) {
  return canMoveMatchRuleNode(
    normalizeMatchRule(props.modelValue),
    sourcePath,
    targetPath,
    placement,
  )
}

function moveNode(
  sourcePath: MatchRuleNodePath,
  targetPath: MatchRuleNodePath,
  placement: MatchRuleDropPlacement,
) {
  const nextRule = moveMatchRuleNode(
    normalizeMatchRule(props.modelValue),
    sourcePath,
    targetPath,
    placement,
  )
  clearDragState()
  if (!nextRule) {
    return
  }
  updateSimple(nextRule)
}

function normalizeDropTarget(targetPath: MatchRuleNodePath, placement: MatchRuleDropPlacement) {
  if (placement !== 'after') {
    return { path: targetPath, placement }
  }

  const root = normalizeMatchRule(props.modelValue)
  const parentPath = targetPath.slice(0, -1)
  const siblingIndex = targetPath[targetPath.length - 1]
  if (siblingIndex === undefined) {
    return { path: targetPath, placement }
  }
  const parent = resolveGroupAtPath(root, parentPath)
  const nextSibling = parent?.children?.[siblingIndex + 1]

  if (!nextSibling) {
    return { path: targetPath, placement }
  }

  return {
    path: [...parentPath, siblingIndex + 1],
    placement: 'before' as const,
  }
}

function resolveGroupAtPath(root: MatchRule, path: MatchRuleNodePath) {
  let current: MatchRule = root
  for (const segment of path) {
    const next = current.children?.[segment]
    if (!next || next.type !== 'GROUP') {
      return null
    }
    current = next
  }
  return current.type === 'GROUP' ? current : null
}

provide(MATCH_RULE_DRAG_CONTEXT_KEY, {
  draggingPath,
  dropTargetPath,
  dropPlacement,
  startDrag,
  setDropTarget,
  clearDragState,
  canDrop,
  moveNode,
  normalizeDropTarget,
})

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
        :node-path="[]"
        :validation-errors="simpleValidationErrors"
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
            class=":uno: relative h-full overflow-hidden select-none border-r border-gray-100 bg-gray-50 px-2 pt-2 pb-0 text-right text-xs text-gray-400"
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
            class=":uno: h-full min-h-0 w-full flex-1 resize-none border-0 bg-transparent px-3 pt-2 pb-0 text-sm font-mono leading-6 focus:outline-none"
            spellcheck="false"
            wrap="off"
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
