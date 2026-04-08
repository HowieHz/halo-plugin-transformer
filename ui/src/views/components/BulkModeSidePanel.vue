<script lang="ts" setup>
import { computed } from 'vue'
import type { ActiveTab } from '@/types'

const props = defineProps<{
  tab: ActiveTab
  selectedCount: number
}>()

const resourceLabel = computed(() => (props.tab === 'snippets' ? '代码片段' : '转换规则'))
</script>

<template>
  <div class=":uno: h-full flex flex-col">
    <div class=":uno: sticky top-0 z-10 h-12 flex items-center border-b bg-white px-4 shrink-0">
      <h2 class=":uno: text-sm font-semibold text-gray-900">
        已勾选 <span class=":uno: text-primary">{{ selectedCount }}</span> 个{{ resourceLabel }}
      </h2>
    </div>

    <div class=":uno: flex flex-1 items-center justify-center px-5 text-center">
      <p class=":uno: text-sm leading-6 text-gray-500">
        批量模式下右侧不展示单项关联详情；如需查看引用关系，请先退出批量操作。
      </p>
    </div>
  </div>
</template>

