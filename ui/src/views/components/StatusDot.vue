<script lang="ts" setup>
defineProps<{ enabled: boolean }>();
</script>

<template>
  <span
    :aria-label="enabled ? '已启用' : '已停用'"
    :class="enabled ? ':uno: bg-primary' : ':uno: bg-gray-500'"
    :title="enabled ? '已启用' : '已停用'"
    role="img"
    class=":uno: h-1.5 w-1.5 shrink-0 rounded-full"
  />
</template>
