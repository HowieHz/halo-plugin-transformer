<script lang="ts" setup>
import { computed } from 'vue'
import { VButton } from '@halo-dev/components'
import type { MatchRule } from '@/types'
import type { MatchRuleValidationError } from '@/views/composables/matchRule'
import {
  MATCH_RULE_GROUP_OPTIONS,
  PATH_MATCHER_OPTIONS,
  TEMPLATE_MATCHER_OPTIONS,
  makeMatchRuleGroup,
  makePathMatchRule,
  makeTemplateMatchRule,
} from '@/types'
import { cloneMatchRule, normalizeMatchRule } from '@/views/composables/matchRule'
import { updateSelectByWheel } from '@/views/composables/selectWheel.ts'

defineOptions({
  name: 'MatchRuleNodeEditor',
})

const props = defineProps<{
  modelValue: MatchRule
  root?: boolean
  canRemove?: boolean
  canMoveUp?: boolean
  canMoveDown?: boolean
  path?: string
  validationErrors?: MatchRuleValidationError[] | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: MatchRule): void
  (e: 'remove'): void
  (e: 'move-up'): void
  (e: 'move-down'): void
  (e: 'change'): void
}>()

const rule = computed(() => normalizeMatchRule(props.modelValue))
const isGroup = computed(() => rule.value.type === 'GROUP')
const currentPath = computed(() => props.path ?? '$')
const matcherOptions = computed(() =>
  rule.value.type === 'TEMPLATE_ID' ? TEMPLATE_MATCHER_OPTIONS : PATH_MATCHER_OPTIONS,
)
const valuePlaceholder = computed(() => {
  if (rule.value.type === 'PATH') {
    if (rule.value.matcher === 'EXACT') {
      return '/'
    }
    if (rule.value.matcher === 'REGEX') {
      return '.*'
    }
    return '/**'
  }
  if (rule.value.matcher === 'REGEX') {
    return '^(post|page)$'
  }
  return 'index'
})
const ownErrors = computed(() => {
  const errors = props.validationErrors ?? []
  const prefix = `${currentPath.value}.`
  return errors.filter((error) => {
    const errorPath = error.path
    if (!errorPath) {
      return false
    }
    if (!errorPath.startsWith(prefix)) {
      return errorPath === currentPath.value
    }
    const suffix = errorPath.slice(prefix.length)
    return ['children', 'operator', 'negate', 'type', 'matcher', 'value'].includes(suffix)
  })
})
const ownErrorPaths = computed(() => new Set(ownErrors.value.map((error) => error.path)))
const ownErrorMessages = computed(() => ownErrors.value.map((error) => error.message))
const hasNodeError = computed(() => ownErrors.value.length > 0)

function updateRule(next: MatchRule) {
  emit('update:modelValue', next)
  emit('change')
}

function updateGroupField<K extends keyof MatchRule>(key: K, value: MatchRule[K]) {
  updateRule({ ...cloneMatchRule(rule.value), [key]: value })
}

function updateChild(index: number, child: MatchRule) {
  const next = cloneMatchRule(rule.value)
  const children = [...(next.children ?? [])]
  children[index] = child
  next.children = children
  updateRule(next)
}

/**
 * why: 条件组删到空，是用户编辑过程中的合法中间态；
 * 先保留空组并在编辑器下方提示错误，用户补完后再保存，比强行回填默认规则更符合预期。
 */
function removeChild(index: number) {
  const next = cloneMatchRule(rule.value)
  const children = (next.children ?? []).filter((_, idx) => idx !== index)
  next.children = children
  updateRule(next)
}

function moveChild(index: number, direction: -1 | 1) {
  const next = cloneMatchRule(rule.value)
  const children = [...(next.children ?? [])]
  const targetIndex = index + direction
  if (targetIndex < 0 || targetIndex >= children.length) return
  ;[children[index], children[targetIndex]] = [children[targetIndex], children[index]]
  next.children = children
  updateRule(next)
}

function addPathRule() {
  const next = cloneMatchRule(rule.value)
  next.children = [...(next.children ?? []), makePathMatchRule({ value: '' })]
  updateRule(next)
}

function addGroupRule() {
  const next = cloneMatchRule(rule.value)
  next.children = [...(next.children ?? []), makeMatchRuleGroup({ children: [] })]
  updateRule(next)
}

function resolveNextMatcher(type: 'PATH' | 'TEMPLATE_ID'): MatchRule['matcher'] {
  if (type === 'PATH') {
    return rule.value.matcher === 'EXACT' || rule.value.matcher === 'REGEX'
      ? rule.value.matcher
      : 'ANT'
  }
  return rule.value.matcher === 'REGEX' ? 'REGEX' : 'EXACT'
}

function switchLeafType(type: 'PATH' | 'TEMPLATE_ID') {
  if (rule.value.type === type) {
    return
  }

  const sharedFields: Partial<MatchRule> = {
    negate: rule.value.negate,
    matcher: resolveNextMatcher(type),
    value: rule.value.value ?? '',
  }
  const next =
    type === 'PATH' ? makePathMatchRule(sharedFields) : makeTemplateMatchRule(sharedFields)
  updateRule(next)
}

function hasFieldError(field: 'children' | 'operator' | 'negate' | 'type' | 'matcher' | 'value') {
  return ownErrorPaths.value.has(`${currentPath.value}.${field}`)
}
</script>

<template>
  <div
    :class="hasNodeError ? ':uno: border-red-300 bg-red-50/40' : ':uno: border-gray-200 bg-white'"
    :aria-invalid="hasNodeError"
    class=":uno: rounded-md border p-3 space-y-3"
    role="group"
  >
    <template v-if="isGroup">
      <div class=":uno: flex flex-wrap items-center gap-2">
        <span class=":uno: text-sm font-medium text-gray-700">
          {{ root ? '根条件组' : '条件组' }}
        </span>
        <select
          :value="rule.operator"
          :aria-invalid="hasFieldError('operator')"
          aria-label="条件组逻辑"
          :class="
            hasFieldError('operator')
              ? ':uno: border-red-300 focus:border-red-500'
              : ':uno: border-gray-200 focus:border-primary'
          "
          class=":uno: min-w-[7rem] shrink-0 rounded-md border bg-white px-2 py-1 pr-8 text-sm focus:outline-none"
          @wheel="updateSelectByWheel"
          @change="
            updateGroupField(
              'operator',
              ($event.target as HTMLSelectElement).value as MatchRule['operator'],
            )
          "
        >
          <option
            v-for="option in MATCH_RULE_GROUP_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
        <label
          :class="hasFieldError('negate') ? ':uno: text-red-600' : ':uno: text-gray-700'"
          class=":uno: inline-flex items-center gap-2 text-sm"
        >
          <input
            :checked="rule.negate"
            type="checkbox"
            @change="updateGroupField('negate', ($event.target as HTMLInputElement).checked)"
          />
          不满足本组（NOT）
        </label>
        <div v-if="!root" class=":uno: inline-flex items-center gap-1">
          <VButton
            v-if="canMoveUp"
            aria-label="上移当前条件组"
            class=":uno: min-w-0 px-2"
            size="sm"
            title="上移"
            @click="emit('move-up')"
          >
            ↑
          </VButton>
          <VButton
            v-if="canMoveDown"
            aria-label="下移当前条件组"
            class=":uno: min-w-0 px-2"
            size="sm"
            title="下移"
            @click="emit('move-down')"
          >
            ↓
          </VButton>
        </div>
        <VButton
          v-if="!root && canRemove !== false"
          aria-label="移除当前条件组"
          size="sm"
          type="danger"
          @click="emit('remove')"
        >
          移除此组
        </VButton>
      </div>

      <div class=":uno: space-y-2">
        <MatchRuleNodeEditor
          v-for="(child, index) in rule.children ?? []"
          :key="index"
          :can-move-down="index < (rule.children?.length ?? 0) - 1"
          :can-move-up="index > 0"
          :can-remove="true"
          :model-value="child"
          :path="`${currentPath}.children[${index}]`"
          :validation-errors="validationErrors"
          @change="emit('change')"
          @move-down="moveChild(index, 1)"
          @move-up="moveChild(index, -1)"
          @remove="removeChild(index)"
          @update:model-value="updateChild(index, $event)"
        />

        <div class=":uno: flex flex-wrap gap-2">
          <VButton size="sm" @click="addPathRule">添加匹配规则</VButton>
          <VButton size="sm" type="secondary" @click="addGroupRule">添加条件组</VButton>
        </div>
      </div>
    </template>

    <template v-else>
      <div class=":uno: flex flex-wrap items-center gap-2">
        <select
          :value="rule.type"
          :aria-invalid="hasFieldError('type')"
          aria-label="匹配规则类型"
          :class="
            hasFieldError('type')
              ? ':uno: border-red-300 focus:border-red-500'
              : ':uno: border-gray-200 focus:border-primary'
          "
          class=":uno: min-w-[8rem] shrink-0 rounded-md border bg-white px-2 py-1 pr-8 text-sm focus:outline-none"
          @wheel="updateSelectByWheel"
          @change="
            switchLeafType(($event.target as HTMLSelectElement).value as 'PATH' | 'TEMPLATE_ID')
          "
        >
          <option value="PATH">页面路径匹配</option>
          <option value="TEMPLATE_ID">模板 ID 匹配</option>
        </select>

        <select
          :value="rule.matcher"
          :aria-invalid="hasFieldError('matcher')"
          aria-label="匹配方式"
          :class="
            hasFieldError('matcher')
              ? ':uno: border-red-300 focus:border-red-500'
              : ':uno: border-gray-200 focus:border-primary'
          "
          class=":uno: min-w-[8rem] shrink-0 rounded-md border bg-white px-2 py-1 pr-8 text-sm focus:outline-none"
          @wheel="updateSelectByWheel"
          @change="
            updateGroupField(
              'matcher',
              ($event.target as HTMLSelectElement).value as MatchRule['matcher'],
            )
          "
        >
          <option v-for="option in matcherOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>

        <label
          :class="hasFieldError('negate') ? ':uno: text-red-600' : ':uno: text-gray-700'"
          class=":uno: inline-flex items-center gap-2 text-sm"
        >
          <input
            :checked="rule.negate"
            type="checkbox"
            @change="updateGroupField('negate', ($event.target as HTMLInputElement).checked)"
          />
          不满足本项（NOT）
        </label>

        <div v-if="!root" class=":uno: inline-flex items-center gap-1">
          <VButton
            v-if="canMoveUp"
            aria-label="上移当前匹配条件"
            class=":uno: min-w-0 px-2"
            size="sm"
            title="上移"
            @click="emit('move-up')"
          >
            ↑
          </VButton>
          <VButton
            v-if="canMoveDown"
            aria-label="下移当前匹配条件"
            class=":uno: min-w-0 px-2"
            size="sm"
            title="下移"
            @click="emit('move-down')"
          >
            ↓
          </VButton>
        </div>

        <VButton
          v-if="canRemove !== false"
          aria-label="移除当前匹配条件"
          size="sm"
          type="danger"
          @click="emit('remove')"
        >
          移除此条件
        </VButton>
      </div>

      <input
        :value="rule.value"
        :aria-invalid="hasFieldError('value')"
        aria-label="匹配值"
        :class="
          hasFieldError('value')
            ? ':uno: border-red-300 focus:border-red-500'
            : ':uno: border-gray-200 focus:border-primary'
        "
        class=":uno: w-full rounded-md border px-3 py-1.5 text-sm font-mono focus:outline-none"
        :placeholder="valuePlaceholder"
        @input="updateGroupField('value', ($event.target as HTMLInputElement).value)"
      />
    </template>

    <div v-if="ownErrorMessages.length" aria-live="polite" class=":uno: space-y-1" role="alert">
      <p
        v-for="(message, index) in ownErrorMessages"
        :key="`${currentPath}-${index}-${message}`"
        class=":uno: text-xs text-red-500"
      >
        {{ message }}
      </p>
    </div>
  </div>
</template>
