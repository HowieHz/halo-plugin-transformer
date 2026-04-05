<script
  generic="T extends { id: string; name: string; description?: string; enabled: boolean }"
  lang="ts"
  setup
>
import { ref } from 'vue'
import StatusDot from './StatusDot.vue'

type ReorderPlacement = 'before' | 'after'

const props = defineProps<{
  items: T[]
  selectedId?: string | null
  emptyText?: string
  stretch?: boolean
  reorderable?: boolean
  listLabel?: string
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'create'): void
  (e: 'reorder', payload: { sourceId: string; targetId: string; placement: ReorderPlacement }): void
}>()

const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropPlacement = ref<ReorderPlacement | null>(null)

function clearDragState() {
  draggingId.value = null
  dropTargetId.value = null
  dropPlacement.value = null
}

function resolveDropPlacement(event: DragEvent, id: string): ReorderPlacement | null {
  if (!draggingId.value || draggingId.value === id) {
    dropTargetId.value = null
    dropPlacement.value = null
    return null
  }

  const currentTarget = event.currentTarget
  if (!(currentTarget instanceof HTMLElement)) {
    return null
  }

  const rect = currentTarget.getBoundingClientRect()
  const placement: ReorderPlacement =
    event.clientY < rect.top + rect.height / 2 ? 'before' : 'after'

  dropTargetId.value = id
  dropPlacement.value = placement
  return placement
}

function handleDragStart(event: DragEvent, id: string) {
  draggingId.value = id
  dropTargetId.value = null
  dropPlacement.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', id)
  }
}

function handleDragOver(event: DragEvent, id: string) {
  if (!draggingId.value) return
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
  resolveDropPlacement(event, id)
}

function handleDrop(event: DragEvent, id: string) {
  if (!draggingId.value) return
  event.preventDefault()
  const placement = resolveDropPlacement(event, id)
  const sourceId = draggingId.value
  clearDragState()
  if (!placement || sourceId === id) {
    return
  }
  emit('reorder', { sourceId, targetId: id, placement })
}

function handleSelect(id: string) {
  emit('select', id)
}

function handleItemKeydown(event: KeyboardEvent, id: string) {
  if (event.key !== 'Enter' && event.key !== ' ') {
    return
  }
  event.preventDefault()
  handleSelect(id)
}

function handleReorderButtonKeydown(event: KeyboardEvent, index: number) {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') {
    return
  }
  event.preventDefault()
  const target = props.items[index + (event.key === 'ArrowUp' ? -1 : 1)]
  if (!target) {
    return
  }
  emit('reorder', {
    sourceId: props.items[index].id,
    targetId: target.id,
    placement: event.key === 'ArrowUp' ? 'before' : 'after',
  })
}
</script>

<template>
  <div :class="stretch ? ':uno: flex-1 overflow-y-auto' : ''">
    <slot name="placeholder" />

    <ul
      :aria-label="props.listLabel ?? '资源列表'"
      class=":uno: divide-y divide-gray-100"
      role="listbox"
    >
      <li
        v-if="!items.length"
        class=":uno: flex flex-col items-center justify-center gap-3 py-10 px-4"
      >
        <span class=":uno: text-sm text-gray-500">{{ emptyText ?? '暂无数据' }}</span>
        <slot name="empty-action"></slot>
      </li>

      <li
        v-for="(item, index) in items"
        :key="item.id"
        :aria-selected="selectedId !== undefined && selectedId === item.id"
        :class="draggingId === item.id ? ':uno: opacity-60' : ''"
        class=":uno: relative cursor-pointer group focus:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
        role="option"
        tabindex="0"
        @dragover="handleDragOver($event, item.id)"
        @drop="handleDrop($event, item.id)"
        @click="handleSelect(item.id)"
        @keydown="handleItemKeydown($event, item.id)"
      >
        <div
          v-if="selectedId !== undefined && selectedId === item.id"
          class=":uno: bg-secondary absolute inset-y-0 left-0 w-0.5"
        />
        <div
          v-if="dropTargetId === item.id && dropPlacement === 'before'"
          class=":uno: pointer-events-none absolute left-4 right-4 top-0 h-0.5 rounded-full bg-primary"
        />
        <div
          v-if="dropTargetId === item.id && dropPlacement === 'after'"
          class=":uno: pointer-events-none absolute left-4 right-4 bottom-0 h-0.5 rounded-full bg-primary"
        />

        <div class=":uno: flex flex-col px-4 py-2.5 gap-1 hover:bg-gray-50">
          <div class=":uno: flex items-center justify-between gap-2">
            <span class=":uno: flex-1 min-w-0 text-sm text-gray-900 font-medium truncate">
              {{ item.name || item.id }}
            </span>
            <div class=":uno: flex items-center gap-1">
              <button
                v-if="reorderable && items.length > 1"
                :aria-label="`拖动排序：${item.name || item.id}`"
                aria-keyshortcuts="ArrowUp ArrowDown"
                class=":uno: inline-flex h-5 w-5 shrink-0 cursor-grab active:cursor-grabbing items-center justify-center rounded text-sm leading-none tracking-[-0.2em] text-gray-400 transition hover:text-gray-600"
                draggable="true"
                title="按住拖动排序；聚焦后可用上下方向键调整顺序"
                @click.stop
                @dragend="clearDragState"
                @dragstart.stop="handleDragStart($event, item.id)"
                @keydown.stop="handleReorderButtonKeydown($event, index)"
                @mousedown.stop
              >
                ⋮⋮
              </button>
              <slot :index="index" :item="item" name="actions" />
              <StatusDot :enabled="item.enabled" />
            </div>
          </div>

          <p v-if="item.description" class=":uno: text-xs text-gray-500 line-clamp-1">
            {{ item.description }}
          </p>

          <slot :item="item" name="meta" />

          <slot :item="item" name="hint" />
        </div>
      </li>
    </ul>
  </div>
</template>
