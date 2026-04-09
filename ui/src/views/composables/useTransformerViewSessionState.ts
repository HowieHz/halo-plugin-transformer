import { computed, type Ref } from "vue";

import type { ActiveTab } from "@/types";

import {
  applyTransformerRouteSelection,
  type TransformerRouteState,
} from "./transformerRouteState";

export type TransformerPageMode = "single" | "create" | "bulk";

export type TransformerViewSessionState =
  | { tab: ActiveTab; mode: "single"; selectedId: string | null }
  | { tab: ActiveTab; mode: "create" }
  | { tab: ActiveTab; mode: "bulk" };

interface UseTransformerViewSessionStateOptions {
  activeTab: Ref<ActiveTab>;
  selectedSnippetId: Ref<string | null>;
  selectedRuleId: Ref<string | null>;
  viewMode: Ref<TransformerPageMode>;
}

/**
 * why: Transformer 页面真正的会话语义只有 `single / create / bulk` 三种；
 * 把 tab、当前页面模式和 remembered selection 的转换统一收口后，
 * URL 同步、切 tab、跳关系、打开新建弹窗就都不必再在 View 里手工改多颗 ref。
 */
export function useTransformerViewSessionState(options: UseTransformerViewSessionStateOptions) {
  const createModalTab = computed<ActiveTab | null>({
    get: () => (options.viewMode.value === "create" ? options.activeTab.value : null),
    set: (tab) => {
      if (tab) {
        openCreate(tab);
        return;
      }
      closeCreate();
    },
  });
  const isBulkMode = computed(() => options.viewMode.value === "bulk");
  const sessionState = computed<TransformerViewSessionState>(() => {
    if (options.viewMode.value === "create") {
      return {
        tab: options.activeTab.value,
        mode: "create",
      };
    }
    if (options.viewMode.value === "bulk") {
      return {
        tab: options.activeTab.value,
        mode: "bulk",
      };
    }
    return {
      tab: options.activeTab.value,
      mode: "single",
      selectedId: currentSelectedId(options.activeTab.value),
    };
  });

  function currentSelectedId(tab: ActiveTab) {
    if (options.viewMode.value !== "single") {
      return null;
    }
    return tab === "snippets" ? options.selectedSnippetId.value : options.selectedRuleId.value;
  }

  function switchTab(tab: ActiveTab) {
    options.activeTab.value = tab;
    options.viewMode.value = "single";
  }

  function openCreate(tab: ActiveTab) {
    options.activeTab.value = tab;
    options.viewMode.value = "create";
  }

  function closeCreate(tab?: ActiveTab) {
    if (options.viewMode.value !== "create") {
      return;
    }
    if (tab && options.activeTab.value !== tab) {
      return;
    }
    options.viewMode.value = "single";
  }

  function enterBulkMode(tab = options.activeTab.value) {
    options.activeTab.value = tab;
    options.viewMode.value = "bulk";
  }

  function exitBulkMode() {
    if (options.viewMode.value !== "bulk") {
      return;
    }
    options.viewMode.value = "single";
  }

  function selectResource(tab: ActiveTab, id: string) {
    switchTab(tab);
    if (tab === "snippets") {
      options.selectedSnippetId.value = id;
      return;
    }
    options.selectedRuleId.value = id;
  }

  function currentRouteState(): TransformerRouteState {
    return {
      tab: options.activeTab.value,
      selectedId: currentSelectedId(options.activeTab.value),
      action: options.viewMode.value === "create" ? "create" : null,
      viewMode: options.viewMode.value === "bulk" ? "bulk" : "single",
    };
  }

  function applyRouteState(nextState: TransformerRouteState) {
    options.activeTab.value = nextState.tab;
    options.viewMode.value =
      nextState.action === "create" ? "create" : nextState.viewMode === "bulk" ? "bulk" : "single";

    const nextSelection = applyTransformerRouteSelection(
      {
        snippets: options.selectedSnippetId.value,
        rules: options.selectedRuleId.value,
      },
      nextState,
    );
    options.selectedSnippetId.value = nextSelection.snippets;
    options.selectedRuleId.value = nextSelection.rules;
  }

  return {
    createModalTab,
    isBulkMode,
    sessionState,
    currentSelectedId,
    switchTab,
    openCreate,
    closeCreate,
    enterBulkMode,
    exitBulkMode,
    selectResource,
    currentRouteState,
    applyRouteState,
  };
}
