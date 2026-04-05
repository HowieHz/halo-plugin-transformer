import { axiosInstance } from '@halo-dev/api-client'
import type {
  CodeSnippetViewModel,
  CodeSnippetWritePayload,
  InjectionRuleViewModel,
  InjectionRuleWritePayload,
  ItemList,
} from '@/types'

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
    return axiosInstance.get<ItemList<CodeSnippetViewModel>>(SNIPPETS)
  },

  add(snippet: CodeSnippetWritePayload) {
    return axiosInstance.post<CodeSnippetViewModel>(SNIPPETS_WRITE, snippet)
  },

  update(id: string, snippet: CodeSnippetWritePayload) {
    return axiosInstance.put<CodeSnippetViewModel>(`${SNIPPETS_WRITE}/${id}`, snippet)
  },

  reorder(items: SortOrderItem[]) {
    return axiosInstance.put<CodeSnippetViewModel[]>(SNIPPETS_REORDER, { items })
  },

  delete(id: string) {
    return axiosInstance.delete(`${SNIPPETS}/${id}`)
  },
}

export const ruleApi = {
  list() {
    return axiosInstance.get<ItemList<InjectionRuleViewModel>>(RULES)
  },

  add(rule: InjectionRuleWritePayload) {
    return axiosInstance.post<InjectionRuleViewModel>(RULES_WRITE, rule)
  },

  update(id: string, rule: InjectionRuleWritePayload) {
    return axiosInstance.put<InjectionRuleViewModel>(`${RULES_WRITE}/${id}`, rule)
  },

  reorder(items: SortOrderItem[]) {
    return axiosInstance.put<InjectionRuleViewModel[]>(RULES_REORDER, { items })
  },

  delete(id: string) {
    return axiosInstance.delete(`${RULES}/${id}`)
  },
}
