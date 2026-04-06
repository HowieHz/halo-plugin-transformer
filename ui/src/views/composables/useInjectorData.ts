import { computed, ref, watch } from 'vue'
import type { AxiosError } from 'axios'
import { Dialog, Toast } from '@halo-dev/components'
import { ruleApi, snippetApi, type OrderMap } from '@/apis'
import type {
  CodeSnippetViewModel,
  CodeSnippetWritePayload,
  EditableInjectionRule,
  InjectionRule,
  ItemList,
} from '@/types'
import { buildExplicitOrderMap, sortByOrderMap, uniqueStrings } from './util'
import {
  formatMatchRuleError,
  hydrateRuleForEditor,
  isValidMatchRule,
  makeRulePayload,
  resolveRuleMatchRule,
} from './matchRule'

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

export function useInjectorData() {
  const loading = ref(false)
  const creating = ref(false)
  const savingEditor = ref(false)

  const snippetsResp = ref<ItemList<CodeSnippetViewModel>>(emptyList())
  const rulesResp = ref<ItemList<InjectionRule>>(emptyList())
  const snippetOrders = ref<OrderMap>({})
  const ruleOrders = ref<OrderMap>({})

  const snippets = computed(() => sortByOrderMap(snippetsResp.value.items, snippetOrders.value))
  const rules = computed(() => sortByOrderMap(rulesResp.value.items, ruleOrders.value))

  const selectedSnippetId = ref<string | null>(null)
  const selectedRuleId = ref<string | null>(null)

  const editSnippet = ref<CodeSnippetViewModel | null>(null)
  const editRule = ref<EditableInjectionRule | null>(null)
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
      .filter((snippet): snippet is CodeSnippetViewModel => !!snippet)
  })

  const snippetEditorError = computed(() => {
    if (!editSnippet.value?.code.trim()) {
      return '代码内容不能为空'
    }
    return null
  })

  const ruleEditorError = computed(() => {
    if (!editRule.value) return null
    return validateRule(editRule.value)
  })

  function normalizeRuleSnippetIds(rule: InjectionRule, snippetIds: string[]) {
    return rule.position === 'REMOVE' ? [] : uniqueStrings(snippetIds)
  }

  /**
   * why: `id` 只是前端展示态的派生字段，不属于后端写模型；
   * 写回前统一剔除，避免被严格字段校验拦下。
   */
  function makeSnippetPayload(snippet: CodeSnippetViewModel): CodeSnippetWritePayload {
    const payload = { ...snippet } as CodeSnippetWritePayload & { id?: string }
    delete payload.id
    return payload
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

  async function persistSnippetOrders(items: CodeSnippetViewModel[]) {
    const nextOrders = buildExplicitOrderMap(items)
    snippetOrders.value = { ...nextOrders }
    try {
      snippetOrders.value = (await snippetApi.updateOrder(nextOrders)).data
      return true
    } catch (error) {
      return getErrorMessage(error, '代码块顺序保存失败')
    }
  }

  async function persistRuleOrders(items: InjectionRule[]) {
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
  function validateRule(rule: EditableInjectionRule): string | null {
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
      syncEditSnippet()
      syncEditRule()
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

  function syncEditSnippet() {
    if (!selectedSnippetId.value) {
      editSnippet.value = null
      editDirty.value = false
      return
    }
    const found = snippets.value.find((snippet) => snippet.id === selectedSnippetId.value)
    editSnippet.value = found ?? null
    editDirty.value = false
  }

  function syncEditRule() {
    if (!selectedRuleId.value) {
      editRule.value = null
      editRuleSnippetIds.value = []
      editDirty.value = false
      return
    }
    const found = rules.value.find((rule) => rule.id === selectedRuleId.value)
    editRule.value = found ? hydrateRuleForEditor(found) : null
    editRuleSnippetIds.value = found ? [...(found.snippetIds ?? [])] : []
    editDirty.value = false
  }

  watch(selectedSnippetId, syncEditSnippet)
  watch(selectedRuleId, syncEditRule)

  async function addSnippet(snippet: CodeSnippetViewModel): Promise<string | null> {
    if (!snippet.code.trim()) {
      Toast.error('代码内容不能为空')
      return null
    }
    creating.value = true
    try {
      const response = await snippetApi.add(makeSnippetPayload(snippet))
      const id = response.data.id
      await fetchAll()
      const orderResult = await persistSnippetOrders(snippets.value)
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
    rule: EditableInjectionRule,
    snippetIds: string[],
  ): Promise<string | null> {
    const error = validateRule(rule)
    if (error) {
      Toast.error(error)
      return null
    }
    const nextSnippetIds = normalizeRuleSnippetIds(rule, snippetIds)
    creating.value = true
    try {
      const payload = makeRulePayload(rule, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return null
      }
      const response = await ruleApi.add(payload)
      await fetchAll()
      const orderResult = await persistRuleOrders(rules.value)
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
      await snippetApi.update(editSnippet.value.id, makeSnippetPayload(editSnippet.value))
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
    const nextSnippetIds = normalizeRuleSnippetIds(editRule.value, editRuleSnippetIds.value)
    savingEditor.value = true
    try {
      const payload = makeRulePayload(editRule.value, nextSnippetIds)
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
    const savedSnippet = snippets.value.find((snippet) => snippet.id === editSnippet.value?.id)
    if (!savedSnippet) return
    if (nextEnabled && snippetEditorError.value) {
      Toast.error(`当前代码块有错误，暂时无法启用：${snippetEditorError.value}`)
      return
    }
    try {
      editSnippet.value.enabled = nextEnabled
      const sourceSnippet = nextEnabled ? editSnippet.value : savedSnippet
      await snippetApi.update(
        sourceSnippet.id,
        makeSnippetPayload({
          ...sourceSnippet,
          enabled: nextEnabled,
        }),
      )
      await fetchAll()
    } catch (error) {
      editSnippet.value.enabled = !nextEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  async function toggleRuleEnabled() {
    if (!editRule.value) return
    const nextEnabled = !editRule.value.enabled
    const savedRule = rules.value.find((rule) => rule.id === editRule.value?.id)
    if (!savedRule) return
    if (nextEnabled) {
      const validationError = validateRule(editRule.value)
      if (validationError) {
        Toast.error(`当前规则有错误，暂时无法启用：${validationError}`)
        return
      }
    }
    try {
      editRule.value.enabled = nextEnabled
      const sourceRule = nextEnabled ? editRule.value : { ...savedRule, enabled: nextEnabled }
      const payload = makeRulePayload(
        sourceRule,
        nextEnabled
          ? normalizeRuleSnippetIds(editRule.value, editRuleSnippetIds.value)
          : normalizeRuleSnippetIds(savedRule, [...(savedRule.snippetIds ?? [])]),
      )
      if (!payload) {
        Toast.error('当前规则有错误，暂时无法启用：匹配规则有误，请先修正后再操作')
        editRule.value.enabled = !nextEnabled
        return
      }
      await ruleApi.update(editRule.value.id, payload)
      await fetchAll()
    } catch (error) {
      editRule.value.enabled = !nextEnabled
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
          const orderResult = await persistSnippetOrders(snippets.value)
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
          const orderResult = await persistRuleOrders(rules.value)
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
    syncEditSnippet()
  }

  function discardRuleEdit() {
    syncEditRule()
  }

  async function reorderSnippet(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = reorderItems(snippets.value, payload.sourceId, payload.targetId, payload.placement)
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
