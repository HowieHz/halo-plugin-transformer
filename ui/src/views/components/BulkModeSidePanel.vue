<script lang="ts" setup>
defineProps<{
  tab: "snippets" | "rules";
  selectedCount: number;
}>();
</script>

<template>
  <div class=":uno: flex h-full flex-col">
    <div class=":uno: sticky top-0 z-10 flex h-12 shrink-0 items-center border-b bg-white px-4">
      <h2 class=":uno: text-sm font-semibold text-gray-900">关联信息</h2>
    </div>

    <div class=":uno: flex flex-1 items-center justify-center px-5 text-center">
      <p class=":uno: text-sm leading-6 text-gray-500">批量模式下不显示关联详情。</p>
    </div>
  </div>
</template>
