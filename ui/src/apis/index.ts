import { axiosInstance } from '@halo-dev/api-client'
import type { CodeSnippet, InjectionRule, ItemList } from '@/types'

const BASE = '/apis/injector.erzbir.com/v1alpha1'
const CONSOLE_BASE = '/apis/console.api.injector.erzbir.com/v1alpha1'
const SNIPPETS = `${BASE}/codeSnippets`
const SNIPPETS_WRITE = `${CONSOLE_BASE}/codeSnippets`
const SNIPPETS_REORDER = `${CONSOLE_BASE}/codeSnippets/reorder`
const RULES = `${BASE}/injectionRules`
const RULES_WRITE = `${CONSOLE_BASE}/injectionRules`
const RULES_REORDER = `${CONSOLE_BASE}/injectionRules/reorder`

interface SortOrderItem {
  id: string
  sortOrder: number
}

export const snippetApi = {
  list() {
    return axiosInstance.get<ItemList<CodeSnippet>>(SNIPPETS)
  },

  add(snippet: CodeSnippet) {
    return axiosInstance.post<CodeSnippet>(SNIPPETS_WRITE, snippet)
  },

  update(id: string, snippet: CodeSnippet) {
    return axiosInstance.put<CodeSnippet>(`${SNIPPETS_WRITE}/${id}`, snippet)
  },

  reorder(items: SortOrderItem[]) {
    return axiosInstance.put<CodeSnippet[]>(SNIPPETS_REORDER, { items })
  },

  delete(id: string) {
    return axiosInstance.delete(`${SNIPPETS}/${id}`)
  },
}

export const ruleApi = {
  list() {
    return axiosInstance.get<ItemList<InjectionRule>>(RULES)
  },

  add(rule: InjectionRule) {
    return axiosInstance.post<InjectionRule>(RULES_WRITE, rule)
  },

  update(id: string, rule: InjectionRule) {
    return axiosInstance.put<InjectionRule>(`${RULES_WRITE}/${id}`, rule)
  },

  reorder(items: SortOrderItem[]) {
    return axiosInstance.put<InjectionRule[]>(RULES_REORDER, { items })
  },

  delete(id: string) {
    return axiosInstance.delete(`${RULES}/${id}`)
  },
}
