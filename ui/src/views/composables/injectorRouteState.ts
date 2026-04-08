import type { ActiveTab } from '@/types'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'

export type InjectorRouteAction = 'create' | null
export type InjectorRouteViewMode = 'single' | 'bulk'

export interface InjectorRouteState {
  tab: ActiveTab
  selectedId: string | null
  action: InjectorRouteAction
  viewMode: InjectorRouteViewMode
}

/**
 * why: `tab / id / action / mode` 组合起来才真正描述 Injector 当前页面语义；
 * 把它们收成单一 route-state，才能避免 URL、局部 ref、批量模式之间再出现隐式漂移。
 */
export function parseInjectorRouteState(query: LocationQuery): InjectorRouteState {
  const tab = normalizeInjectorRouteTab(query.tab)
  const action = normalizeInjectorRouteAction(query.action)
  const viewMode = normalizeInjectorRouteViewMode(query.mode)
  const selectedId = typeof query.id === 'string' ? query.id : null

  return {
    tab,
    action,
    viewMode,
    selectedId: action || viewMode === 'bulk' ? null : selectedId,
  }
}

export function buildInjectorRouteQuery(
  currentQuery: LocationQuery,
  state: InjectorRouteState,
): LocationQueryRaw {
  const nextQuery: LocationQueryRaw = {
    ...currentQuery,
    tab: state.tab,
  }

  if (state.action) {
    nextQuery.action = state.action
    delete nextQuery.mode
    delete nextQuery.id
    return nextQuery
  }

  if (state.viewMode === 'bulk') {
    nextQuery.mode = 'bulk'
    delete nextQuery.action
    delete nextQuery.id
    return nextQuery
  }

  delete nextQuery.mode
  delete nextQuery.action
  if (state.selectedId) {
    nextQuery.id = state.selectedId
  } else {
    delete nextQuery.id
  }
  return nextQuery
}

export function isSameInjectorRouteState(
  left: InjectorRouteState,
  right: InjectorRouteState,
): boolean {
  return (
    left.tab === right.tab &&
    left.selectedId === right.selectedId &&
    left.action === right.action &&
    left.viewMode === right.viewMode
  )
}

function normalizeInjectorRouteTab(tab: unknown): ActiveTab {
  return tab === 'rules' ? 'rules' : 'snippets'
}

function normalizeInjectorRouteAction(action: unknown): InjectorRouteAction {
  return action === 'create' ? 'create' : null
}

function normalizeInjectorRouteViewMode(mode: unknown): InjectorRouteViewMode {
  return mode === 'bulk' ? 'bulk' : 'single'
}
