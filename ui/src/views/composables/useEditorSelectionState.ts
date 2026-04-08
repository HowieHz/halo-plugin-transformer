import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import type {
  ActiveTab,
  TransformationSnippetEditorDraft,
  TransformationSnippetReadModel,
  TransformationRuleEditorDraft,
  TransformationRuleReadModel,
  ItemList,
} from '@/types'
import { hydrateRuleEditorDraft } from './ruleDraft'
import { hydrateSnippetEditorDraft } from './snippetDraft'
import { mergeSavedMetadata } from './transformerShared'

interface UseEditorSelectionStateOptions {
  activeTab: Ref<ActiveTab>
  snippetsResp: Ref<ItemList<TransformationSnippetReadModel>>
  rulesResp: Ref<ItemList<TransformationRuleReadModel>>
  snippets: ComputedRef<TransformationSnippetReadModel[]>
  rules: ComputedRef<TransformationRuleReadModel[]>
}

interface SnippetEditorSession {
  tab: 'snippets'
  draft: TransformationSnippetEditorDraft | null
  dirty: boolean
}

interface RuleEditorSession {
  tab: 'rules'
  draft: TransformationRuleEditorDraft | null
  snippetIds: string[]
  dirty: boolean
}

type EditorSession = SnippetEditorSession | RuleEditorSession

/**
 * why: 选中态、草稿 hydration 与“只同步已保存快照的一小部分字段”属于编辑器上下文；
 * 把它从总控模块里拆出来后，CRUD 与排序逻辑都不必再关心右侧面板如何维护草稿。
 */
export function useEditorSelectionState(options: UseEditorSelectionStateOptions) {
  const rememberedSelectionByTab = ref<Record<ActiveTab, string | null>>({
    snippets: null,
    rules: null,
  })
  const editorSession = ref<EditorSession>(createSnippetEditorSession())

  const selectedSnippetId = computed({
    get: () => rememberedSelectionByTab.value.snippets,
    set: (selectedId: string | null) => {
      rememberedSelectionByTab.value = {
        ...rememberedSelectionByTab.value,
        snippets: selectedId,
      }
      if (options.activeTab.value === 'snippets') {
        hydrateSelectedSnippetDraft()
      }
    },
  })

  const selectedRuleId = computed({
    get: () => rememberedSelectionByTab.value.rules,
    set: (selectedId: string | null) => {
      rememberedSelectionByTab.value = {
        ...rememberedSelectionByTab.value,
        rules: selectedId,
      }
      if (options.activeTab.value === 'rules') {
        hydrateSelectedRuleDraft()
      }
    },
  })

  const editSnippet = computed({
    get: () => (editorSession.value.tab === 'snippets' ? editorSession.value.draft : null),
    set: (draft: TransformationSnippetEditorDraft | null) => {
      if (editorSession.value.tab !== 'snippets') {
        return
      }
      editorSession.value = {
        ...editorSession.value,
        draft,
      }
    },
  })

  const editRule = computed({
    get: () => (editorSession.value.tab === 'rules' ? editorSession.value.draft : null),
    set: (draft: TransformationRuleEditorDraft | null) => {
      if (editorSession.value.tab !== 'rules') {
        return
      }
      editorSession.value = {
        ...editorSession.value,
        draft,
      }
    },
  })

  const editRuleSnippetIds = computed({
    get: () => (editorSession.value.tab === 'rules' ? editorSession.value.snippetIds : []),
    set: (snippetIds: string[]) => {
      if (editorSession.value.tab !== 'rules') {
        return
      }
      editorSession.value = {
        ...editorSession.value,
        snippetIds: [...snippetIds],
      }
      if (editorSession.value.draft) {
        editorSession.value.draft.snippetIds = [...snippetIds]
      }
    },
  })

  const editDirty = computed({
    get: () => editorSession.value.dirty,
    set: (dirty: boolean) => {
      editorSession.value = {
        ...editorSession.value,
        dirty,
      }
    },
  })

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
      .filter((snippet): snippet is TransformationSnippetReadModel => !!snippet)
  })

  function filterExistingSnippetIds(snippetIds: string[]) {
    const availableSnippetIds = new Set(options.snippets.value.map((snippet) => snippet.id))
    return snippetIds.filter((snippetId, index) => {
      return availableSnippetIds.has(snippetId) && snippetIds.indexOf(snippetId) === index
    })
  }

  function hydrateSelectedSnippetDraft() {
    if (options.activeTab.value !== 'snippets') {
      return
    }
    const found = options.snippets.value.find((snippet) => snippet.id === selectedSnippetId.value)
    editorSession.value = createSnippetEditorSession(
      found ? hydrateSnippetEditorDraft(found) : null,
    )
  }

  function hydrateSelectedRuleDraft() {
    if (options.activeTab.value !== 'rules') {
      return
    }
    const found = options.rules.value.find((rule) => rule.id === selectedRuleId.value)
    const snippetIds = found ? filterExistingSnippetIds(found.snippetIds ?? []) : []
    const draft = found ? hydrateRuleEditorDraft(found) : null
    if (draft) {
      draft.snippetIds = [...snippetIds]
    }
    editorSession.value = createRuleEditorSession(draft, snippetIds)
  }

  /**
   * why: snippet 可能被别处删除，而 rule 清理是异步最终一致；
   * 编辑器不应继续把已不存在的 snippet id 当成“已选”，否则 UI 计数和后续保存 payload 都会漂移。
   */
  function reconcileRuleEditorSnippetIds() {
    if (editorSession.value.tab !== 'rules') {
      return
    }
    const nextSnippetIds = filterExistingSnippetIds(editorSession.value.snippetIds)
    if (nextSnippetIds.length === editorSession.value.snippetIds.length) {
      return
    }
    editorSession.value = {
      ...editorSession.value,
      snippetIds: nextSnippetIds,
    }
    if (editorSession.value.draft) {
      editorSession.value.draft.snippetIds = [...nextSnippetIds]
    }
  }

  /**
   * why: 启停接口现在只返回最新已保存资源；
   * 这里仅把列表里的已保存快照替换掉，避免再用整页 reload 把右侧未保存草稿整体冲掉。
   */
  function applySavedSnippetSnapshot(snippet: TransformationSnippetReadModel) {
    options.snippetsResp.value = replaceItemInList(options.snippetsResp.value, snippet)
    if (editorSession.value.tab === 'snippets' && editorSession.value.draft?.id === snippet.id) {
      editorSession.value.draft.enabled = snippet.enabled
      editorSession.value.draft.metadata = mergeSavedMetadata(
        editorSession.value.draft.metadata,
        snippet.metadata,
      )
    }
  }

  /**
   * why: 规则启停属于资源级动作，不应顺带重置当前编辑中的 matchRule / snippetIds 草稿；
   * 这里只同步最新的已保存启停状态，其余编辑态原样保留。
   */
  function applySavedRuleSnapshot(rule: TransformationRuleReadModel) {
    options.rulesResp.value = replaceItemInList(options.rulesResp.value, rule)
    if (editorSession.value.tab === 'rules' && editorSession.value.draft?.id === rule.id) {
      editorSession.value.draft.enabled = rule.enabled
      editorSession.value.draft.metadata = mergeSavedMetadata(
        editorSession.value.draft.metadata,
        rule.metadata,
      )
    }
  }

  function toggleSnippetInRuleEditor(snippetId: string) {
    if (editorSession.value.tab !== 'rules') {
      return
    }
    const nextSnippetIds = editorSession.value.snippetIds.includes(snippetId)
      ? editorSession.value.snippetIds.filter((id) => id !== snippetId)
      : [...editorSession.value.snippetIds, snippetId]
    editorSession.value = {
      ...editorSession.value,
      snippetIds: nextSnippetIds,
      dirty: true,
    }
    if (editorSession.value.draft) {
      editorSession.value.draft.snippetIds = [...nextSnippetIds]
    }
  }

  function discardSnippetEdit() {
    hydrateSelectedSnippetDraft()
  }

  function discardRuleEdit() {
    hydrateSelectedRuleDraft()
  }

  watch(options.activeTab, (activeTab) => {
    if (activeTab === 'snippets') {
      hydrateSelectedSnippetDraft()
      return
    }
    hydrateSelectedRuleDraft()
  })
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

function createSnippetEditorSession(
  draft: TransformationSnippetEditorDraft | null = null,
): SnippetEditorSession {
  return {
    tab: 'snippets',
    draft,
    dirty: false,
  }
}

function createRuleEditorSession(
  draft: TransformationRuleEditorDraft | null = null,
  snippetIds: string[] = [],
): RuleEditorSession {
  return {
    tab: 'rules',
    draft,
    snippetIds,
    dirty: false,
  }
}

function replaceItemInList<T extends { id: string }>(list: ItemList<T>, updated: T): ItemList<T> {
  return {
    ...list,
    items: list.items.map((item) => (item.id === updated.id ? updated : item)),
  }
}
