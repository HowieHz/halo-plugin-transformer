import { computed, ref, watch } from 'vue'
import type { AxiosError } from 'axios'
import { Dialog, Toast } from '@halo-dev/components'
import { ruleApi, snippetApi, type OrderMap } from '@/apis'
import type {
  CodeSnippetEditorDraft,
  CodeSnippetReadModel,
  InjectionRuleEditorDraft,
  InjectionRuleReadModel,
  ItemList,
} from '@/types'
import { buildExplicitOrderMap, sortByOrderMap, uniqueStrings } from './util'
import { formatMatchRuleError, isValidMatchRule, resolveRuleMatchRule } from './matchRule'
import { buildRuleWritePayload, hydrateRuleEditorDraft } from './ruleDraft'
import { buildSnippetWritePayload, hydrateSnippetEditorDraft } from './snippetDraft'

type ReorderPlacement = 'before' | 'after'

function emptyList<T>(): ItemList<T> {
  return {
    first: false,
    hasNext: false,
    hasPrevious: false,
    last: false,
    page: 0,
    size: 0,
    totalPages: 0,
    items: [],
    total: 0,
  }
}

function getErrorMessage(error: unknown, fallback: string) {
  const axiosError = error as AxiosError<{
    message?: string
    detail?: string
    error?: { message?: string }
  }>
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.detail ||
    axiosError?.response?.data?.error?.message ||
    fallback
  )
}

function replaceItemInList<T extends { id: string }>(list: ItemList<T>, updated: T): ItemList<T> {
  return {
    ...list,
    items: list.items.map((item) => (item.id === updated.id ? updated : item)),
  }
}

export function useInjectorData() {
  const loading = ref(false)
  const creating = ref(false)
  const savingEditor = ref(false)

  const snippetsResp = ref<ItemList<CodeSnippetReadModel>>(emptyList())
  const rulesResp = ref<ItemList<InjectionRuleReadModel>>(emptyList())
  const snippetOrders = ref<OrderMap>({})
  const ruleOrders = ref<OrderMap>({})

  const snippets = computed(() => sortByOrderMap(snippetsResp.value.items, snippetOrders.value))
  const rules = computed(() => sortByOrderMap(rulesResp.value.items, ruleOrders.value))

  const selectedSnippetId = ref<string | null>(null)
  const selectedRuleId = ref<string | null>(null)

  const editSnippet = ref<CodeSnippetEditorDraft | null>(null)
  const editRule = ref<InjectionRuleEditorDraft | null>(null)
  const editRuleSnippetIds = ref<string[]>([])

  const editDirty = ref(false)
  const syncingSnippetReorder = ref(false)
  const syncingRuleReorder = ref(false)
  const pendingSnippetOrders = ref<OrderMap | null>(null)
  const pendingRuleOrders = ref<OrderMap | null>(null)

  const rulesUsingSnippet = computed(() => {
    if (!selectedSnippetId.value) return []
    return rules.value.filter((rule) => rule.snippetIds?.includes(selectedSnippetId.value!))
  })

  const snippetsInRule = computed(() => {
    if (!selectedRuleId.value) return []
    const rule = rules.value.find((item) => item.id === selectedRuleId.value)
    if (!rule?.snippetIds?.length) return []
    return rule.snippetIds
      .map((id) => snippets.value.find((snippet) => snippet.id === id))
      .filter((snippet): snippet is CodeSnippetReadModel => !!snippet)
  })

  const snippetEditorError = computed(() => {
    if (!editSnippet.value?.code.trim()) {
      return '代码内容不能为空'
    }
    return null
  })

  const ruleEditorError = computed(() => {
    if (!editRule.value) return null
    return validateRuleDraft(editRule.value)
  })

  /**
   * why: 代码块关联属于写入语义，不应直接复用编辑器里“看起来像已选”的临时状态；
   * 这里集中收口 REMOVE 等模式差异，保证所有写路径都走同一套规则。
   */
  function resolvePersistedSnippetIdsForRule(
    rule: Pick<InjectionRuleEditorDraft, 'position'>,
    snippetIds: string[],
  ) {
    return rule.position === 'REMOVE' ? [] : uniqueStrings(snippetIds)
  }

  function reorderItems<T extends { id: string }>(
    items: T[],
    sourceId: string,
    targetId: string,
    placement: ReorderPlacement,
  ) {
    if (sourceId === targetId) {
      return null
    }

    const ordered = [...items]
    const sourceIndex = ordered.findIndex((item) => item.id === sourceId)
    const targetIndex = ordered.findIndex((item) => item.id === targetId)
    if (sourceIndex < 0 || targetIndex < 0) {
      return null
    }

    const [moving] = ordered.splice(sourceIndex, 1)
    const nextTargetIndex = ordered.findIndex((item) => item.id === targetId)
    const insertIndex = placement === 'before' ? nextTargetIndex : nextTargetIndex + 1
    ordered.splice(insertIndex, 0, moving)
    return ordered
  }

  async function saveSnippetOrderMap(items: CodeSnippetReadModel[]) {
    const nextOrders = buildExplicitOrderMap(items)
    snippetOrders.value = { ...nextOrders }
    try {
      snippetOrders.value = (await snippetApi.updateOrder(nextOrders)).data
      return true
    } catch (error) {
      return getErrorMessage(error, '代码块顺序保存失败')
    }
  }

  async function saveRuleOrderMap(items: InjectionRuleReadModel[]) {
    const nextOrders = buildExplicitOrderMap(items)
    ruleOrders.value = { ...nextOrders }
    try {
      ruleOrders.value = (await ruleApi.updateOrder(nextOrders)).data
      return true
    } catch (error) {
      return getErrorMessage(error, '注入规则顺序保存失败')
    }
  }

  /**
   * why: 前端先做一层用户可读的快速校验，把大部分编辑态错误拦在保存前；
   * 后端仍会复核，但这里要尽量把错误定位成用户能直接修的提示。
   */
  function validateRuleDraft(rule: InjectionRuleEditorDraft): string | null {
    if ((rule.mode === 'SELECTOR' || rule.mode === 'ID') && !rule.match.trim()) {
      return '请填写匹配内容'
    }
    const result = resolveRuleMatchRule(rule)
    if (result.error) {
      return `匹配规则有误：${formatMatchRuleError(result.error)}`
    }
    if (!isValidMatchRule(result.rule)) {
      return '请完善匹配规则'
    }
    return null
  }

  async function fetchAll() {
    loading.value = true
    try {
      const [snippetResponse, ruleResponse, snippetOrderResponse, ruleOrderResponse] =
        await Promise.all([
          snippetApi.list(),
          ruleApi.list(),
          snippetApi.getOrder(),
          ruleApi.getOrder(),
        ])
      snippetsResp.value = snippetResponse.data
      rulesResp.value = ruleResponse.data
      snippetOrders.value = snippetOrderResponse.data
      ruleOrders.value = ruleOrderResponse.data
      hydrateSelectedSnippetDraft()
      hydrateSelectedRuleDraft()
    } catch (error) {
      Toast.error(getErrorMessage(error, '加载数据失败'))
    } finally {
      loading.value = false
    }
  }

  async function reloadSnippetOrders() {
    snippetOrders.value = (await snippetApi.getOrder()).data
  }

  async function reloadRuleOrders() {
    ruleOrders.value = (await ruleApi.getOrder()).data
  }

  function hydrateSelectedSnippetDraft() {
    if (!selectedSnippetId.value) {
      editSnippet.value = null
      editDirty.value = false
      return
    }
    const found = snippets.value.find((snippet) => snippet.id === selectedSnippetId.value)
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
    const found = rules.value.find((rule) => rule.id === selectedRuleId.value)
    editRule.value = found ? hydrateRuleEditorDraft(found) : null
    editRuleSnippetIds.value = found ? [...(found.snippetIds ?? [])] : []
    editDirty.value = false
  }

  /**
   * why: 启停接口现在只返回最新已保存资源；
   * 这里仅把列表里的已保存快照替换掉，避免再用 `fetchAll()` 把右侧未保存草稿整体冲掉。
   */
  function applySavedSnippetSnapshot(snippet: CodeSnippetReadModel) {
    snippetsResp.value = replaceItemInList(snippetsResp.value, snippet)
    if (editSnippet.value?.id === snippet.id) {
      editSnippet.value.enabled = snippet.enabled
    }
  }

  /**
   * why: 规则启停属于资源级动作，不应顺带重置当前编辑中的 matchRule / snippetIds 草稿；
   * 这里只同步最新的已保存启停状态，其余编辑态原样保留。
   */
  function applySavedRuleSnapshot(rule: InjectionRuleReadModel) {
    rulesResp.value = replaceItemInList(rulesResp.value, rule)
    if (editRule.value?.id === rule.id) {
      editRule.value.enabled = rule.enabled
    }
  }

  watch(selectedSnippetId, hydrateSelectedSnippetDraft)
  watch(selectedRuleId, hydrateSelectedRuleDraft)

  async function addSnippet(snippet: CodeSnippetEditorDraft): Promise<string | null> {
    if (!snippet.code.trim()) {
      Toast.error('代码内容不能为空')
      return null
    }
    creating.value = true
    try {
      const response = await snippetApi.add(buildSnippetWritePayload(snippet))
      const id = response.data.id
      await fetchAll()
      const orderResult = await saveSnippetOrderMap(snippets.value)
      selectedSnippetId.value = id
      if (orderResult === true) {
        Toast.success('代码块已创建')
      } else {
        Toast.warning(`代码块已创建，但顺序保存失败：${orderResult}`)
      }
      return id
    } catch (error) {
      Toast.error(getErrorMessage(error, '创建失败'))
      return null
    } finally {
      creating.value = false
    }
  }

  async function addRule(
    rule: InjectionRuleEditorDraft,
    snippetIds: string[],
  ): Promise<string | null> {
    const error = validateRuleDraft(rule)
    if (error) {
      Toast.error(error)
      return null
    }
    const nextSnippetIds = resolvePersistedSnippetIdsForRule(rule, snippetIds)
    creating.value = true
    try {
      const payload = buildRuleWritePayload(rule, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return null
      }
      const response = await ruleApi.add(payload)
      await fetchAll()
      const orderResult = await saveRuleOrderMap(rules.value)
      selectedRuleId.value = response.data.id
      if (orderResult === true) {
        Toast.success('规则已创建')
      } else {
        Toast.warning(`规则已创建，但顺序保存失败：${orderResult}`)
      }
      return response.data.id
    } catch (error) {
      Toast.error(getErrorMessage(error, '创建失败'))
      return null
    } finally {
      creating.value = false
    }
  }

  async function saveSnippet() {
    if (snippetEditorError.value) {
      Toast.error(snippetEditorError.value)
      return false
    }
    if (!editSnippet.value) {
      return false
    }
    savingEditor.value = true
    try {
      await snippetApi.update(editSnippet.value.id, buildSnippetWritePayload(editSnippet.value))
      await fetchAll()
      editDirty.value = false
      Toast.success('保存成功')
      return true
    } catch (error) {
      Toast.error(getErrorMessage(error, '保存失败'))
      return false
    } finally {
      savingEditor.value = false
    }
  }

  async function saveRule() {
    if (!editRule.value) return false
    const error = ruleEditorError.value
    if (error) {
      Toast.error(error)
      return false
    }
    const nextSnippetIds = resolvePersistedSnippetIdsForRule(
      editRule.value,
      editRuleSnippetIds.value,
    )
    savingEditor.value = true
    try {
      const payload = buildRuleWritePayload(editRule.value, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return false
      }
      await ruleApi.update(editRule.value.id, payload)
      await fetchAll()
      editDirty.value = false
      Toast.success('保存成功')
      return true
    } catch (error) {
      Toast.error(getErrorMessage(error, '保存失败'))
      return false
    } finally {
      savingEditor.value = false
    }
  }

  async function toggleSnippetEnabled() {
    if (!editSnippet.value) return
    const nextEnabled = !editSnippet.value.enabled
    const previousEnabled = editSnippet.value.enabled
    try {
      const response = await snippetApi.updateEnabled(editSnippet.value.id, nextEnabled)
      applySavedSnippetSnapshot(response.data)
      Toast.success(nextEnabled ? '代码块已启用' : '代码块已停用')
    } catch (error) {
      editSnippet.value.enabled = previousEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  async function toggleRuleEnabled() {
    if (!editRule.value) return
    const nextEnabled = !editRule.value.enabled
    const previousEnabled = editRule.value.enabled
    try {
      const response = await ruleApi.updateEnabled(editRule.value.id, nextEnabled)
      applySavedRuleSnapshot(response.data)
      Toast.success(nextEnabled ? '规则已启用' : '规则已停用')
    } catch (error) {
      editRule.value.enabled = previousEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  function toggleSnippetInRuleEditor(snippetId: string) {
    const ids = editRuleSnippetIds.value
    editRuleSnippetIds.value = ids.includes(snippetId)
      ? ids.filter((id) => id !== snippetId)
      : [...ids, snippetId]
    editDirty.value = true
  }

  function confirmDeleteSnippet() {
    if (!editSnippet.value) return
    const id = editSnippet.value.id
    Dialog.warning({
      title: '删除代码块',
      description: `确认删除代码块 ${id}？删除后无法恢复。`,
      confirmType: 'danger',
      async onConfirm() {
        try {
          await snippetApi.delete(id)
          if (selectedSnippetId.value === id) selectedSnippetId.value = null
          editSnippet.value = null
          editDirty.value = false
          await fetchAll()
          const orderResult = await saveSnippetOrderMap(snippets.value)
          if (orderResult === true) {
            Toast.success('代码块已删除')
          } else {
            Toast.warning(`代码块已删除，但顺序保存失败：${orderResult}`)
          }
        } catch (error) {
          Toast.error(getErrorMessage(error, '删除失败'))
        }
      },
    })
  }

  function confirmDeleteRule() {
    if (!editRule.value) return
    const id = editRule.value.id
    Dialog.warning({
      title: '删除规则',
      description: `确认删除规则 ${id}？删除后无法恢复。`,
      confirmType: 'danger',
      async onConfirm() {
        try {
          await ruleApi.delete(id)
          if (selectedRuleId.value === id) selectedRuleId.value = null
          editRule.value = null
          editRuleSnippetIds.value = []
          editDirty.value = false
          await fetchAll()
          const orderResult = await saveRuleOrderMap(rules.value)
          if (orderResult === true) {
            Toast.success('规则已删除')
          } else {
            Toast.warning(`规则已删除，但顺序保存失败：${orderResult}`)
          }
        } catch (error) {
          Toast.error(getErrorMessage(error, '删除失败'))
        }
      },
    })
  }

  function discardSnippetEdit() {
    hydrateSelectedSnippetDraft()
  }

  function discardRuleEdit() {
    hydrateSelectedRuleDraft()
  }

  async function reorderSnippet(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = reorderItems(
      snippets.value,
      payload.sourceId,
      payload.targetId,
      payload.placement,
    )
    if (!ordered) {
      return
    }
    snippetOrders.value = buildExplicitOrderMap(ordered)
    pendingSnippetOrders.value = { ...snippetOrders.value }
    if (syncingSnippetReorder.value) {
      return
    }

    syncingSnippetReorder.value = true
    let updatedOnce = false
    try {
      while (pendingSnippetOrders.value) {
        const snapshot = pendingSnippetOrders.value
        pendingSnippetOrders.value = null
        snippetOrders.value = (await snippetApi.updateOrder(snapshot)).data
        updatedOnce = true
      }
      if (updatedOnce) {
        Toast.success('代码块顺序保存成功')
      }
    } catch (error) {
      Toast.error(getErrorMessage(error, '更新顺序失败'))
      pendingSnippetOrders.value = null
      try {
        await reloadSnippetOrders()
      } catch (reloadError) {
        Toast.error(getErrorMessage(reloadError, '重新加载代码块顺序失败'))
      }
    } finally {
      syncingSnippetReorder.value = false
    }
  }

  async function reorderRule(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = reorderItems(rules.value, payload.sourceId, payload.targetId, payload.placement)
    if (!ordered) {
      return
    }
    ruleOrders.value = buildExplicitOrderMap(ordered)
    pendingRuleOrders.value = { ...ruleOrders.value }
    if (syncingRuleReorder.value) {
      return
    }

    syncingRuleReorder.value = true
    let updatedOnce = false
    try {
      while (pendingRuleOrders.value) {
        const snapshot = pendingRuleOrders.value
        pendingRuleOrders.value = null
        ruleOrders.value = (await ruleApi.updateOrder(snapshot)).data
        updatedOnce = true
      }
      if (updatedOnce) {
        Toast.success('注入规则顺序保存成功')
      }
    } catch (error) {
      Toast.error(getErrorMessage(error, '更新顺序失败'))
      pendingRuleOrders.value = null
      try {
        await reloadRuleOrders()
      } catch (reloadError) {
        Toast.error(getErrorMessage(reloadError, '重新加载注入规则顺序失败'))
      }
    } finally {
      syncingRuleReorder.value = false
    }
  }

  return {
    loading,
    creating,
    savingEditor,
    snippets,
    rules,
    selectedSnippetId,
    selectedRuleId,
    editSnippet,
    editRule,
    editRuleSnippetIds,
    editDirty,
    snippetEditorError,
    ruleEditorError,
    rulesUsingSnippet,
    snippetsInRule,
    fetchAll,
    addSnippet,
    saveSnippet,
    toggleSnippetEnabled,
    confirmDeleteSnippet,
    discardSnippetEdit,
    reorderSnippet,
    addRule,
    saveRule,
    toggleRuleEnabled,
    confirmDeleteRule,
    discardRuleEdit,
    toggleSnippetInRuleEditor,
    reorderRule,
  }
}
