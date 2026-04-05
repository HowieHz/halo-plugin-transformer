<script lang="ts" setup>
import type { CodeSnippet, InjectionRule } from '@/types'
import ItemListV from './ItemListV.vue'
import { rulePreview } from '@/views/composables/util'
import { matchRuleExpression } from '@/views/composables/matchRule'

defineProps<{
  mode: 'snippets' | 'rules'
  selectedSnippetId: string | null
  selectedRuleId: string | null
  rulesUsingSnippet: InjectionRule[]
  snippetsInRule: CodeSnippet[]
}>()

const emit = defineEmits<{
  (e: 'jump-to-rule', id: string): void
  (e: 'jump-to-snippet', id: string): void
}>()
</script>

<template>
  <div class=":uno: h-full flex flex-col">
    <div class=":uno: sticky top-0 z-10 h-12 flex items-center border-b bg-white px-4 shrink-0">
      <template v-if="mode === 'snippets'">
        <h2 v-if="selectedSnippetId" class=":uno: text-sm font-semibold text-gray-900">
          被 <span class=":uno: text-primary">{{ rulesUsingSnippet.length }}</span> 个规则引用
        </h2>
        <span v-else class=":uno: text-sm text-gray-400">选择一个代码块</span>
      </template>
      <template v-else>
        <h2 v-if="selectedRuleId" class=":uno: text-sm font-semibold text-gray-900">
          关联 <span class=":uno: text-primary">{{ snippetsInRule.length }}</span> 个代码块
        </h2>
        <span v-else class=":uno: text-sm text-gray-400">选择一个规则</span>
      </template>
    </div>

    <div class=":uno: flex-1 overflow-y-auto">
      <template v-if="mode === 'snippets'">
        <ItemListV
          v-if="selectedSnippetId"
          :items="rulesUsingSnippet"
          list-label="引用当前代码块的规则列表"
          empty-text="该代码块暂未被任何规则引用, 请在编辑面板中添加"
          @select="emit('jump-to-rule', $event)"
        >
          <template #meta="{ item: r }">
            <span class=":uno: text-xs text-gray-500">{{ rulePreview(r) }}</span>
            <span
              class=":uno: mt-0.5 block overflow-hidden text-ellipsis whitespace-nowrap text-xs text-gray-400"
              :title="matchRuleExpression(r.matchRule)"
            >
              {{ matchRuleExpression(r.matchRule) }}
            </span>
          </template>

          <template #hint>
            <span
              class=":uno: text-xs text-primary opacity-0 mt-0.5 group-hover:opacity-100 transition-opacity"
            >
              点击跳转到规则 →
            </span>
          </template>
        </ItemListV>
      </template>

      <template v-else>
        <ItemListV
          v-if="selectedRuleId"
          :items="snippetsInRule"
          list-label="当前规则关联的代码块列表"
          empty-text="该规则暂未关联代码块, 请在编辑面板中添加"
          @select="emit('jump-to-snippet', $event)"
        >
          <template #hint>
            <span
              class=":uno: text-xs text-primary opacity-0 mt-0.5 group-hover:opacity-100 transition-opacity"
            >
              点击跳转到代码块 →
            </span>
          </template>
        </ItemListV>
      </template>
    </div>
  </div>
</template>
