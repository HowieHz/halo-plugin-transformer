import { computed, ref, watch, type ComputedRef, type Ref } from "vue";

import type { ActiveTab } from "@/types";

import { uniqueStrings } from "./util";

interface BulkSelectableResource {
  id: string;
}

interface UseBulkSelectionStateOptions {
  activeTab: Ref<ActiveTab>;
  snippets: ComputedRef<BulkSelectableResource[]>;
  rules: ComputedRef<BulkSelectableResource[]>;
}

/**
 * why: 按 tab 隔离的批量勾选集不该再顺带承担“当前页面是不是 bulk 模式”；
 * 把 mode 留给页面 session controller，选择集这里只负责被选中了哪些资源。
 */
export function useBulkSelectionState(options: UseBulkSelectionStateOptions) {
  const bulkSnippetIds = ref<string[]>([]);
  const bulkRuleIds = ref<string[]>([]);

  const currentBulkIds = computed(() =>
    options.activeTab.value === "snippets" ? bulkSnippetIds.value : bulkRuleIds.value,
  );

  const currentBulkSelectionCount = computed(() => currentBulkIds.value.length);
  const allCurrentSelected = computed(() => {
    const resourceIds = resolveResourceIds(options.activeTab.value);
    return resourceIds.length > 0 && resourceIds.every((id) => currentBulkIds.value.includes(id));
  });
  const someCurrentSelected = computed(
    () => currentBulkSelectionCount.value > 0 && !allCurrentSelected.value,
  );

  function clearCurrentBulkSelection() {
    replaceCurrentBulkSelection([]);
  }

  function replaceCurrentBulkSelection(ids: string[]) {
    const normalizedIds = filterExistingIds(options.activeTab.value, ids);
    if (options.activeTab.value === "snippets") {
      bulkSnippetIds.value = normalizedIds;
      return;
    }
    bulkRuleIds.value = normalizedIds;
  }

  function appendCurrentBulkSelection(ids: string[]) {
    replaceCurrentBulkSelection([...currentBulkIds.value, ...ids]);
  }

  function toggleCurrentBulkItem(id: string) {
    replaceCurrentBulkSelection(
      currentBulkIds.value.includes(id)
        ? currentBulkIds.value.filter((currentId) => currentId !== id)
        : [...currentBulkIds.value, id],
    );
  }

  function toggleCurrentBulkSelectAll() {
    if (allCurrentSelected.value) {
      clearCurrentBulkSelection();
      return;
    }
    replaceCurrentBulkSelection(resolveResourceIds(options.activeTab.value));
  }

  function isCurrentBulkSelected(id: string) {
    return currentBulkIds.value.includes(id);
  }

  function pruneSelection(tab: ActiveTab) {
    const nextIds = filterExistingIds(
      tab,
      tab === "snippets" ? bulkSnippetIds.value : bulkRuleIds.value,
    );
    if (tab === "snippets") {
      bulkSnippetIds.value = nextIds;
      return;
    }
    bulkRuleIds.value = nextIds;
  }

  function resolveResourceIds(tab: ActiveTab) {
    return (tab === "snippets" ? options.snippets.value : options.rules.value).map(
      (item) => item.id,
    );
  }

  function filterExistingIds(tab: ActiveTab, ids: string[]) {
    const allowedIds = new Set(resolveResourceIds(tab));
    return uniqueStrings(ids).filter((id) => allowedIds.has(id));
  }

  watch(options.snippets, () => pruneSelection("snippets"));
  watch(options.rules, () => pruneSelection("rules"));

  return {
    bulkSnippetIds,
    bulkRuleIds,
    currentBulkIds,
    currentBulkSelectionCount,
    allCurrentSelected,
    someCurrentSelected,
    clearCurrentBulkSelection,
    replaceCurrentBulkSelection,
    appendCurrentBulkSelection,
    toggleCurrentBulkItem,
    toggleCurrentBulkSelectAll,
    isCurrentBulkSelected,
  };
}
