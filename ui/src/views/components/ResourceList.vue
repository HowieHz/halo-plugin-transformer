<script
  generic="T extends { id: string; name: string; description?: string; enabled: boolean }"
  lang="ts"
  setup
>
import { nextTick, ref, watch, type ComponentPublicInstance } from "vue";

import StatusDot from "./StatusDot.vue";

type ReorderPlacement = "before" | "after";

const props = defineProps<{
  items: T[];
  selectedId?: string | null;
  bulkMode?: boolean;
  bulkSelectedIds?: string[];
  emptyText?: string;
  stretch?: boolean;
  reorderable?: boolean;
  listLabel?: string;
  panelId?: string;
  tabLabelledby?: string;
}>();

const emit = defineEmits<{
  (e: "select", id: string): void;
  (e: "create"): void;
  (e: "toggle-bulk-item", id: string): void;
  (e: "toggle-bulk-all"): void;
  (
    e: "reorder",
    payload: { sourceId: string; targetId: string; placement: ReorderPlacement },
  ): void;
  (e: "drag-state-change", active: boolean): void;
  (e: "scroll-container"): void;
}>();

const draggingId = ref<string | null>(null);
const dropTargetId = ref<string | null>(null);
const dropPlacement = ref<ReorderPlacement | null>(null);
const scrollContainer = ref<HTMLElement | null>(null);
const itemElements = ref<Record<string, HTMLElement | null>>({});
const reorderButtonElements = ref<Record<string, HTMLButtonElement | null>>({});
const pendingReorderFocusId = ref<string | null>(null);

defineExpose({
  getScrollContainer() {
    return scrollContainer.value;
  },
  commitPendingDrop() {
    commitPendingDrop(dropPlacement.value, dropTargetId.value);
  },
});

function clearDragState() {
  draggingId.value = null;
  dropTargetId.value = null;
  dropPlacement.value = null;
  emit("drag-state-change", false);
}

function resolveDropPlacement(event: DragEvent, id: string): ReorderPlacement | null {
  if (!draggingId.value || draggingId.value === id) {
    dropTargetId.value = null;
    dropPlacement.value = null;
    return null;
  }

  const currentTarget = event.currentTarget;
  if (!(currentTarget instanceof HTMLElement)) {
    return null;
  }

  const rect = currentTarget.getBoundingClientRect();
  const rawPlacement: ReorderPlacement =
    event.clientY < rect.top + rect.height / 2 ? "before" : "after";
  const currentIndex = props.items.findIndex((item) => item.id === id);
  if (currentIndex === -1) {
    dropTargetId.value = null;
    dropPlacement.value = null;
    return null;
  }

  const nextItem = props.items[currentIndex + 1];
  const placement = rawPlacement === "after" && nextItem ? "before" : rawPlacement;
  const targetId = rawPlacement === "after" && nextItem ? nextItem.id : id;

  dropTargetId.value = targetId;
  dropPlacement.value = placement;
  return placement;
}

function handleDragStart(event: DragEvent, id: string) {
  draggingId.value = id;
  dropTargetId.value = null;
  dropPlacement.value = null;
  emit("drag-state-change", true);
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", id);
  }
}

function handleDragOver(event: DragEvent, id: string) {
  if (!draggingId.value) return;
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = "move";
  }
  resolveDropPlacement(event, id);
}

function handleDrop(event: DragEvent, id: string) {
  if (!draggingId.value) return;
  event.preventDefault();
  const placement = resolveDropPlacement(event, id);
  commitPendingDrop(placement, dropTargetId.value);
}

/**
 * why: 顶部/底部自动滚动热区与绿色插入线可能同时存在；
 * 用户松手时 drop 事件未必还落在具体的列表项上，所以这里需要一个“按当前高亮落点兜底提交”的容器级路径。
 */
function handleContainerDragOver(event: DragEvent) {
  if (!draggingId.value || !dropTargetId.value || !dropPlacement.value) {
    return;
  }
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = "move";
  }
}

function handleContainerDrop(event: DragEvent) {
  if (!draggingId.value) {
    return;
  }
  event.preventDefault();
  commitPendingDrop(dropPlacement.value, dropTargetId.value);
}

function commitPendingDrop(placement: ReorderPlacement | null, targetId: string | null) {
  const sourceId = draggingId.value;
  clearDragState();
  if (!placement || !targetId || !sourceId || sourceId === targetId) {
    return;
  }
  emit("reorder", { sourceId, targetId, placement });
}

function handleSelect(id: string) {
  if (props.bulkMode) {
    emit("toggle-bulk-item", id);
    return;
  }
  emit("select", id);
}

function handlePrimaryActionKeydown(event: KeyboardEvent, id: string) {
  const currentIndex = props.items.findIndex((item) => item.id === id);
  if (currentIndex === -1) {
    return;
  }

  let nextIndex: number | null = null;
  if (event.key === "ArrowUp") {
    nextIndex = Math.max(0, currentIndex - 1);
  } else if (event.key === "ArrowDown") {
    nextIndex = Math.min(props.items.length - 1, currentIndex + 1);
  } else if (event.key === "Home") {
    nextIndex = 0;
  } else if (event.key === "End") {
    nextIndex = props.items.length - 1;
  }

  if (nextIndex === null || nextIndex === currentIndex) {
    return;
  }

  event.preventDefault();
  focusItem(props.items[nextIndex].id);
  if (!props.bulkMode) {
    emit("select", props.items[nextIndex].id);
  }
}

function handleReorderButtonKeydown(event: KeyboardEvent, index: number) {
  if (event.key !== "ArrowUp" && event.key !== "ArrowDown") {
    return;
  }
  event.preventDefault();
  const sourceItem = props.items[index];
  const target = props.items[index + (event.key === "ArrowUp" ? -1 : 1)];
  if (!sourceItem || !target) {
    return;
  }
  pendingReorderFocusId.value = sourceItem.id;
  emit("reorder", {
    sourceId: sourceItem.id,
    targetId: target.id,
    placement: event.key === "ArrowUp" ? "before" : "after",
  });
}

function isBulkSelected(id: string) {
  return props.bulkSelectedIds?.includes(id) ?? false;
}

function handleCheckboxClick(event: Event, id: string) {
  event.stopPropagation();
  emit("toggle-bulk-item", id);
}

function handleToggleBulkAll() {
  emit("toggle-bulk-all");
}

/**
 * why: 列表导航和键盘重排的焦点语义不同；
 * 上下导航应当把焦点移动到目标项，而重排后应当留在被移动项的拖动句柄上，方便连续调整顺序。
 * 这里提前收集 DOM 引用，避免后面再去靠脆弱的选择器回查。
 */
const setItemElement = (id: string, element: Element | ComponentPublicInstance | null) => {
  itemElements.value[id] = element instanceof HTMLElement ? element : null;
};

/**
 * why: 重排句柄在拖动和键盘调整两条路径里都是同一个权威焦点落点；
 * 这里提前收集每一项的 DOM 引用，避免后面再去靠脆弱的选择器回查。
 */
const setReorderButtonElement = (id: string, element: Element | ComponentPublicInstance | null) => {
  reorderButtonElements.value[id] = element instanceof HTMLButtonElement ? element : null;
};

/**
 * why: 资源列表的真实交互模型是“主操作按钮 + 次级控件”，不是 ARIA listbox；
 * 方向键只在主操作按钮之间移动焦点，避免把 checkbox / 拖动句柄塞进错误的复合组件语义里。
 */
function focusItem(id: string) {
  itemElements.value[id]?.focus();
}

/**
 * why: 键盘重排后，用户通常会继续连按方向键微调顺序；
 * 焦点必须留在同一项的拖动句柄上，否则会打断这条连续操作链路。
 */
async function restorePendingReorderButtonFocus() {
  const focusId = pendingReorderFocusId.value;
  if (!focusId) {
    return;
  }

  await nextTick();
  reorderButtonElements.value[focusId]?.focus();
  pendingReorderFocusId.value = null;
}

watch(
  () => props.items.map((item) => item.id),
  () => {
    if (!pendingReorderFocusId.value) {
      return;
    }
    void restorePendingReorderButtonFocus();
  },
);
</script>

<template>
  <div
    ref="scrollContainer"
    :aria-labelledby="props.tabLabelledby"
    :class="props.stretch ? ':uno: min-h-0 flex-1 overflow-y-auto' : ''"
    :id="props.panelId"
    class=":uno: relative"
    :role="props.panelId ? 'tabpanel' : undefined"
    @dragover="handleContainerDragOver"
    @drop="handleContainerDrop"
    @scroll="emit('scroll-container')"
  >
    <slot name="placeholder" />

    <ul :aria-label="props.listLabel ?? '资源列表'" class=":uno: divide-y divide-gray-100">
      <li
        v-if="props.bulkMode && props.items.length"
        class=":uno: sticky top-0 z-1 border-b border-gray-100 bg-white px-4 py-2"
      >
        <label class=":uno: flex cursor-pointer items-center gap-2 text-sm text-gray-700">
          <input
            aria-label="全选当前列表项"
            :checked="
              props.items.length > 0 && props.items.every((item) => isBulkSelected(item.id))
            "
            :indeterminate.prop="
              !!props.bulkSelectedIds?.length &&
              !props.items.every((item) => isBulkSelected(item.id))
            "
            type="checkbox"
            @change="handleToggleBulkAll"
          />
          <span>全选</span>
        </label>
      </li>

      <li
        v-if="!props.items.length"
        class=":uno: flex flex-col items-center justify-center gap-3 px-4 py-10"
      >
        <span class=":uno: text-sm text-gray-500">{{ props.emptyText ?? "暂无数据" }}</span>
        <slot name="empty-action"></slot>
      </li>

      <li
        v-for="(item, index) in props.items"
        :key="item.id"
        :class="draggingId === item.id ? ':uno: opacity-60' : ''"
        class=":uno: group focus-within:ring-primary/40 relative focus-within:ring-2"
        @dragover="handleDragOver($event, item.id)"
        @drop="handleDrop($event, item.id)"
      >
        <div
          v-if="props.selectedId !== undefined && props.selectedId === item.id"
          class=":uno: bg-secondary absolute inset-y-0 left-0 w-0.5"
        />
        <div
          v-if="dropTargetId === item.id && dropPlacement === 'before'"
          class=":uno: bg-primary pointer-events-none absolute top-0 right-4 left-4 h-0.5 rounded-full"
        />
        <div
          v-if="dropTargetId === item.id && dropPlacement === 'after'"
          class=":uno: bg-primary pointer-events-none absolute right-4 bottom-0 left-4 h-0.5 rounded-full"
        />

        <div class=":uno: flex gap-3 px-4 py-2.5 hover:bg-gray-50">
          <label
            v-if="props.bulkMode"
            class=":uno: mt-0.5 flex shrink-0 cursor-pointer items-start"
            @click.stop
          >
            <input
              :aria-label="`选择 ${item.name || item.id}`"
              :checked="isBulkSelected(item.id)"
              type="checkbox"
              @change="handleCheckboxClick($event, item.id)"
            />
          </label>

          <button
            :ref="(element) => setItemElement(item.id, element)"
            :aria-current="
              !props.bulkMode && props.selectedId !== undefined && props.selectedId === item.id
                ? 'true'
                : undefined
            "
            :aria-pressed="props.bulkMode ? isBulkSelected(item.id) : undefined"
            class=":uno: min-w-0 flex-1 text-left focus:outline-none"
            type="button"
            @click="handleSelect(item.id)"
            @keydown="handlePrimaryActionKeydown($event, item.id)"
          >
            <div class=":uno: flex min-w-0 flex-col gap-1">
              <span class=":uno: min-w-0 truncate text-sm font-medium text-gray-900">
                {{ item.name || item.id }}
              </span>

              <p v-if="item.description" class=":uno: line-clamp-1 text-xs text-gray-500">
                {{ item.description }}
              </p>

              <slot :item="item" name="meta" />

              <slot :item="item" name="hint" />
            </div>
          </button>

          <div class=":uno: flex shrink-0 items-center gap-1 self-start">
            <button
              v-if="props.reorderable && !props.bulkMode && props.items.length > 1"
              :ref="(element) => setReorderButtonElement(item.id, element)"
              :aria-label="`拖动排序：${item.name || item.id}`"
              aria-keyshortcuts="ArrowUp ArrowDown"
              class=":uno: inline-flex h-5 w-5 shrink-0 cursor-grab items-center justify-center rounded text-sm leading-none tracking-[-0.2em] text-gray-400 transition hover:text-gray-600 active:cursor-grabbing"
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
      </li>
    </ul>
  </div>
</template>
