import type { LocationQuery, LocationQueryRaw } from "vue-router";

import type { ActiveTab } from "@/types";

export type TransformerRouteAction = "create" | null;
export type TransformerRouteViewMode = "single" | "bulk";

export interface TransformerRouteState {
  tab: ActiveTab;
  selectedId: string | null;
  action: TransformerRouteAction;
  viewMode: TransformerRouteViewMode;
}

export interface TransformerRememberedSelection {
  snippets: string | null;
  rules: string | null;
}

/**
 * why: `tab / id / action / mode` 组合起来才真正描述 Transformer 当前页面语义；
 * 把它们收成单一 route-state，才能避免 URL、局部 ref、批量模式之间再出现隐式漂移。
 */
export function parseTransformerRouteState(query: LocationQuery): TransformerRouteState {
  const tab = normalizeTransformerRouteTab(query.tab);
  const action = normalizeTransformerRouteAction(query.action);
  const viewMode = normalizeTransformerRouteViewMode(query.mode);
  const selectedId = typeof query.id === "string" ? query.id : null;

  return {
    tab,
    action,
    viewMode,
    selectedId: action || viewMode === "bulk" ? null : selectedId,
  };
}

export function buildTransformerRouteQuery(
  currentQuery: LocationQuery,
  state: TransformerRouteState,
): LocationQueryRaw {
  const nextQuery: LocationQueryRaw = {
    ...currentQuery,
    tab: state.tab,
  };

  if (state.action) {
    nextQuery.action = state.action;
    delete nextQuery.mode;
    delete nextQuery.id;
    return nextQuery;
  }

  if (state.viewMode === "bulk") {
    nextQuery.mode = "bulk";
    delete nextQuery.action;
    delete nextQuery.id;
    return nextQuery;
  }

  delete nextQuery.mode;
  delete nextQuery.action;
  if (state.selectedId) {
    nextQuery.id = state.selectedId;
  } else {
    delete nextQuery.id;
  }
  return nextQuery;
}

export function isSameTransformerRouteState(
  left: TransformerRouteState,
  right: TransformerRouteState,
): boolean {
  return (
    left.tab === right.tab &&
    left.selectedId === right.selectedId &&
    left.action === right.action &&
    left.viewMode === right.viewMode
  );
}

/**
 * why: route 只表达“当前 tab 想进入什么页面语义”，
 * `bulk / create` 并不代表“把记住的选中项（remembered selection）真删掉”；否则退出这些模式后就无法回到原先打开的资源。
 */
export function applyTransformerRouteSelection(
  currentSelection: TransformerRememberedSelection,
  state: TransformerRouteState,
): TransformerRememberedSelection {
  if (state.viewMode === "bulk" || state.action === "create") {
    return currentSelection;
  }

  return {
    ...currentSelection,
    [state.tab]: state.selectedId,
  };
}

/**
 * why: 左侧列表高亮和 URL `id` 一样，都属于“当前界面正在展示哪条资源”的可见语义；
 * create / bulk 只需要隐藏这个可见选中态，不应该把记住的选中项（remembered selection）本身清掉。
 */
export function resolveVisibleTransformerSelection(
  state: Pick<TransformerRouteState, "action" | "viewMode">,
  selectedId: string | null,
): string | null {
  if (state.viewMode === "bulk" || state.action === "create") {
    return null;
  }
  return selectedId;
}

function normalizeTransformerRouteTab(tab: unknown): ActiveTab {
  return tab === "rules" ? "rules" : "snippets";
}

function normalizeTransformerRouteAction(action: unknown): TransformerRouteAction {
  return action === "create" ? "create" : null;
}

function normalizeTransformerRouteViewMode(mode: unknown): TransformerRouteViewMode {
  return mode === "bulk" ? "bulk" : "single";
}
