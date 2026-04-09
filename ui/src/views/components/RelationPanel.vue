<script lang="ts" setup>
import { computed } from "vue";

import type {
  TransformationPosition,
  TransformationRuleReadModel,
  TransformationSnippetReadModel,
} from "@/types";
import { matchRuleSummary } from "@/views/composables/matchRule";
import { rulePreview } from "@/views/composables/util";

import ResourceList from "./ResourceList.vue";

const props = defineProps<{
  mode: "snippets" | "rules";
  selectedSnippetId: string | null;
  selectedRuleId: string | null;
  selectedRulePosition?: TransformationPosition | null;
  rulesUsingSnippet: TransformationRuleReadModel[];
  snippetsInRule: TransformationSnippetReadModel[];
}>();

const emit = defineEmits<{
  (e: "jump-to-rule", id: string): void;
  (e: "jump-to-snippet", id: string): void;
}>();

const ruleSnippetsEmptyText = computed(() =>
  props.selectedRulePosition === "REMOVE"
    ? "该规则无需关联代码片段"
    : "该规则暂未关联代码片段，请在规则编辑器中添加",
);
</script>

<template>
  <div class=":uno: flex h-full flex-col">
    <div class=":uno: sticky top-0 z-10 flex h-12 shrink-0 items-center border-b bg-white px-4">
      <template v-if="mode === 'snippets'">
        <h2 v-if="selectedSnippetId" class=":uno: text-sm font-semibold text-gray-900">
          被 <span class=":uno: text-primary">{{ rulesUsingSnippet.length }}</span> 个规则引用
        </h2>
        <span v-else class=":uno: text-sm text-gray-400">选择一个代码片段</span>
      </template>
      <template v-else>
        <h2 v-if="selectedRuleId" class=":uno: text-sm font-semibold text-gray-900">
          关联 <span class=":uno: text-primary">{{ snippetsInRule.length }}</span> 个代码片段
        </h2>
        <span v-else class=":uno: text-sm text-gray-400">选择一个规则</span>
      </template>
    </div>

    <div class=":uno: flex-1 overflow-y-auto">
      <template v-if="mode === 'snippets'">
        <ResourceList
          v-if="selectedSnippetId"
          :items="rulesUsingSnippet"
          list-label="引用当前代码片段的规则列表"
          empty-text="该代码片段暂未被任何规则引用，请到规则编辑器中关联"
          @select="emit('jump-to-rule', $event)"
        >
          <template #meta="{ item: rule }">
            <span class=":uno: text-xs text-gray-500">{{ rulePreview(rule) }}</span>
            <span
              class=":uno: mt-0.5 block overflow-hidden text-xs text-ellipsis whitespace-nowrap text-gray-400"
              :title="matchRuleSummary(rule.matchRule)"
            >
              {{ matchRuleSummary(rule.matchRule) }}
            </span>
          </template>

          <template #hint>
            <span
              class=":uno: text-primary mt-0.5 text-xs opacity-0 transition-opacity group-hover:opacity-100"
            >
              点击跳转到规则 →
            </span>
          </template>
        </ResourceList>
      </template>

      <template v-else>
        <ResourceList
          v-if="selectedRuleId"
          :items="snippetsInRule"
          list-label="当前规则关联的代码片段列表"
          :empty-text="ruleSnippetsEmptyText"
          @select="emit('jump-to-snippet', $event)"
        >
          <template #hint>
            <span
              class=":uno: text-primary mt-0.5 text-xs opacity-0 transition-opacity group-hover:opacity-100"
            >
              点击跳转到代码片段 →
            </span>
          </template>
        </ResourceList>
      </template>
    </div>
  </div>
</template>
