import { axiosInstance } from '@halo-dev/api-client'
import type {
  CodeSnippetReadModel,
  CodeSnippetWritePayload,
  InjectionRuleReadModel,
  InjectionRuleWritePayload,
  ItemList,
} from '@/types'

const BASE = '/apis/injector.erzbir.com/v1alpha1'
const CONSOLE_BASE = '/apis/console.api.injector.erzbir.com/v1alpha1'
const SNIPPETS = `${BASE}/codeSnippets`
const SNIPPETS_WRITE = `${CONSOLE_BASE}/codeSnippets`
const SNIPPET_ORDER = `${CONSOLE_BASE}/snippet-order`
const RULES = `${BASE}/injectionRules`
const RULES_WRITE = `${CONSOLE_BASE}/injectionRules`
const RULE_ORDER = `${CONSOLE_BASE}/rule-order`

export type OrderMap = Record<string, number>
export interface PersistedOrderState {
  orders: OrderMap
  version: number | null
}

export const snippetApi = {
  list() {
    return axiosInstance.get<ItemList<CodeSnippetReadModel>>(SNIPPETS)
  },

  add(snippet: CodeSnippetWritePayload) {
    return axiosInstance.post<CodeSnippetReadModel>(SNIPPETS_WRITE, snippet)
  },

  update(id: string, snippet: CodeSnippetWritePayload) {
    return axiosInstance.put<CodeSnippetReadModel>(`${SNIPPETS_WRITE}/${id}`, snippet)
  },

  updateEnabled(id: string, enabled: boolean, version: number | null | undefined) {
    return axiosInstance.put<CodeSnippetReadModel>(`${SNIPPETS_WRITE}/${id}/enabled`, {
      enabled,
      metadata: { version: version ?? null },
    })
  },

  getOrder() {
    return axiosInstance.get<PersistedOrderState>(SNIPPET_ORDER)
  },

  updateOrder(orders: OrderMap, version: number | null | undefined) {
    return axiosInstance.put<PersistedOrderState>(SNIPPET_ORDER, {
      orders,
      metadata: { version: version ?? null },
    })
  },

  delete(id: string) {
    return axiosInstance.delete(`${SNIPPETS_WRITE}/${id}`)
  },
}

export const ruleApi = {
  list() {
    return axiosInstance.get<ItemList<InjectionRuleReadModel>>(RULES)
  },

  add(rule: InjectionRuleWritePayload) {
    return axiosInstance.post<InjectionRuleReadModel>(RULES_WRITE, rule)
  },

  update(id: string, rule: InjectionRuleWritePayload) {
    return axiosInstance.put<InjectionRuleReadModel>(`${RULES_WRITE}/${id}`, rule)
  },

  updateEnabled(id: string, enabled: boolean, version: number | null | undefined) {
    return axiosInstance.put<InjectionRuleReadModel>(`${RULES_WRITE}/${id}/enabled`, {
      enabled,
      metadata: { version: version ?? null },
    })
  },

  getOrder() {
    return axiosInstance.get<PersistedOrderState>(RULE_ORDER)
  },

  updateOrder(orders: OrderMap, version: number | null | undefined) {
    return axiosInstance.put<PersistedOrderState>(RULE_ORDER, {
      orders,
      metadata: { version: version ?? null },
    })
  },

  delete(id: string) {
    return axiosInstance.delete(`${RULES_WRITE}/${id}`)
  },
}
