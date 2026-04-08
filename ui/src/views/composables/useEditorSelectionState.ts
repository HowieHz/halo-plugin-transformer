import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type {
  CodeSnippetEditorDraft,
  CodeSnippetReadModel,
  InjectionRuleEditorDraft,
  InjectionRuleReadModel,
  ItemList,
} from '@/types'
import { hydrateRuleEditorDraft } from './ruleDraft'
import { hydrateSnippetEditorDraft } from './snippetDraft'
import { mergeSavedMetadata } from './injectorShared'

interface UseEditorSelectionStateOptions {
  snippetsResp: Ref<ItemList<CodeSnippetReadModel>>
  rulesResp: Ref<ItemList<InjectionRuleReadModel>>
  snippets: ComputedRef<CodeSnippetReadModel[]>
  rules: ComputedRef<InjectionRuleReadModel[]>
}

/**
 * why: 选中态、草稿 hydration 与“只同步已保存快照的一小部分字段”属于编辑器上下文；
 * 把它从总控模块里拆出来后，CRUD 与排序逻辑都不必再关心右侧面板如何维护草稿。
 */
export function useEditorSelectionState(options: UseEditorSelectionStateOptions) {
  const selectedSnippetId = ref<string | null>(null)
  const selectedRuleId = ref<string | null>(null)

  const editSnippet = ref<CodeSnippetEditorDraft | null>(null)
  const editRule = ref<InjectionRuleEditorDraft | null>(null)
  const editRuleSnippetIds = ref<string[]>([])
  const editDirty = ref(false)

  const rulesUsingSnippet = computed(() => {
    if (!selectedSnippetId.value) return []
    return options.rules.value.filter((rule) => rule.snippetIds?.includes(selectedSnippetId.value!))
  })

  const snippetsInRule = computed(() => {
    if (!selectedRuleId.value) return []
    const rule = options.rules.value.find((item) => item.id === selectedRuleId.value)
    if (!rule?.snippetIds?.length) return []
    return rule.snippetIds
      .map((id) => options.snippets.value.find((snippet) => snippet.id === id))
      .filter((snippet): snippet is CodeSnippetReadModel => !!snippet)
  })

  function filterExistingSnippetIds(snippetIds: string[]) {
    const availableSnippetIds = new Set(options.snippets.value.map((snippet) => snippet.id))
    return snippetIds.filter((snippetId, index) => {
      return availableSnippetIds.has(snippetId) && snippetIds.indexOf(snippetId) === index
    })
  }

  function hydrateSelectedSnippetDraft() {
    if (!selectedSnippetId.value) {
      editSnippet.value = null
      editDirty.value = false
      return
    }
    const found = options.snippets.value.find((snippet) => snippet.id === selectedSnippetId.value)
    editSnippet.value = found ? hydrateSnippetEditorDraft(found) : null
    editDirty.value = false
  }

  function hydrateSelectedRuleDraft() {
    if (!selectedRuleId.value) {
      editRule.value = null
      editRuleSnippetIds.value = []
      editDirty.value = false
      return
    }
    const found = options.rules.value.find((rule) => rule.id === selectedRuleId.value)
    editRule.value = found ? hydrateRuleEditorDraft(found) : null
    editRuleSnippetIds.value = found ? filterExistingSnippetIds(found.snippetIds ?? []) : []
    if (editRule.value) {
      editRule.value.snippetIds = [...editRuleSnippetIds.value]
    }
    editDirty.value = false
  }

  /**
   * why: snippet 可能被别处删除，而 rule 清理是异步最终一致；
   * 编辑器不应继续把已不存在的 snippet id 当成“已选”，否则 UI 计数和后续保存 payload 都会漂移。
   */
  function reconcileRuleEditorSnippetIds() {
    const nextSnippetIds = filterExistingSnippetIds(editRuleSnippetIds.value)
    if (nextSnippetIds.length === editRuleSnippetIds.value.length) {
      return
    }
    editRuleSnippetIds.value = nextSnippetIds
    if (editRule.value) {
      editRule.value.snippetIds = [...nextSnippetIds]
    }
  }

  /**
   * why: 启停接口现在只返回最新已保存资源；
   * 这里仅把列表里的已保存快照替换掉，避免再用整页 reload 把右侧未保存草稿整体冲掉。
   */
  function applySavedSnippetSnapshot(snippet: CodeSnippetReadModel) {
    options.snippetsResp.value = replaceItemInList(options.snippetsResp.value, snippet)
    if (editSnippet.value?.id === snippet.id) {
      editSnippet.value.enabled = snippet.enabled
      editSnippet.value.metadata = mergeSavedMetadata(editSnippet.value.metadata, snippet.metadata)
    }
  }

  /**
   * why: 规则启停属于资源级动作，不应顺带重置当前编辑中的 matchRule / snippetIds 草稿；
   * 这里只同步最新的已保存启停状态，其余编辑态原样保留。
   */
  function applySavedRuleSnapshot(rule: InjectionRuleReadModel) {
    options.rulesResp.value = replaceItemInList(options.rulesResp.value, rule)
    if (editRule.value?.id === rule.id) {
      editRule.value.enabled = rule.enabled
      editRule.value.metadata = mergeSavedMetadata(editRule.value.metadata, rule.metadata)
    }
  }

  function toggleSnippetInRuleEditor(snippetId: string) {
    const ids = editRuleSnippetIds.value
    editRuleSnippetIds.value = ids.includes(snippetId)
      ? ids.filter((id) => id !== snippetId)
      : [...ids, snippetId]
    editDirty.value = true
  }

  function discardSnippetEdit() {
    hydrateSelectedSnippetDraft()
  }

  function discardRuleEdit() {
    hydrateSelectedRuleDraft()
  }

  watch(selectedSnippetId, hydrateSelectedSnippetDraft)
  watch(selectedRuleId, hydrateSelectedRuleDraft)
  watch(options.snippets, reconcileRuleEditorSnippetIds)

  return {
    selectedSnippetId,
    selectedRuleId,
    editSnippet,
    editRule,
    editRuleSnippetIds,
    editDirty,
    rulesUsingSnippet,
    snippetsInRule,
    hydrateSelectedSnippetDraft,
    hydrateSelectedRuleDraft,
    applySavedSnippetSnapshot,
    applySavedRuleSnapshot,
    toggleSnippetInRuleEditor,
    discardSnippetEdit,
    discardRuleEdit,
  }
}

function replaceItemInList<T extends { id: string }>(list: ItemList<T>, updated: T): ItemList<T> {
  return {
    ...list,
    items: list.items.map((item) => (item.id === updated.id ? updated : item)),
  }
}
