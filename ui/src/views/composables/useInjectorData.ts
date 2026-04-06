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
  const editSnippetRuleIds = ref<string[]>([])

  const editRule = ref<EditableInjectionRule | null>(null)
  const editRuleSnippetIds = ref<string[]>([])

  const editDirty = ref(false)
  const syncingSnippetReorder = ref(false)
  const syncingRuleReorder = ref(false)
  const pendingSnippetOrders = ref<OrderMap | null>(null)
  const pendingRuleOrders = ref<OrderMap | null>(null)

  const rulesUsingSnippet = computed(() => {
    if (!selectedSnippetId.value) return []
    return rules.value.filter((r) => r.snippetIds?.includes(selectedSnippetId.value!))
  })

  const snippetsInRule = computed(() => {
    if (!selectedRuleId.value) return []
    const rule = rules.value.find((r) => r.id === selectedRuleId.value)
    if (!rule?.snippetIds?.length) return []
    return rule.snippetIds
      .map((id) => snippets.value.find((s) => s.id === id))
      .filter((s): s is CodeSnippetViewModel => !!s)
  })

  const snippetEditorError = computed(() => {
    if (!editSnippet.value?.code.trim()) {
      return '代码内容不能为空'
    }
    return null
  })

  const ruleEditorError = computed(() => {
    if (!editRule.value) return null
    return _validateRule(editRule.value)
  })

  function _normalizeRuleSnippetIds(rule: InjectionRule, snippetIds: string[]) {
    return rule.position === 'REMOVE' ? [] : uniqueStrings(snippetIds)
  }

  function _normalizeSnippetRuleIds(ruleIds: string[]) {
    const allowedRuleIds = new Set(
      rules.value.filter((rule) => rule.position !== 'REMOVE').map((rule) => rule.id),
    )
    return uniqueStrings(ruleIds).filter((ruleId) => allowedRuleIds.has(ruleId))
  }

  /**
   * why: `id` 只是前端编辑态里便于选中和显示的派生字段，不属于代码块扩展对象本身；
   * 写回后端时必须剔除，避免被严格字段校验当成脏数据拦下。
   */
  function _makeSnippetPayload(
    snippet: CodeSnippetViewModel,
    ruleIds?: string[],
  ): CodeSnippetWritePayload {
    const payload = {
      ...snippet,
      ruleIds: ruleIds ?? snippet.ruleIds,
    } as CodeSnippetWritePayload & { id?: string }
    delete payload.id
    return {
      ...payload,
    }
  }

  function _reorderItems<T extends { id: string }>(
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

  async function _persistSnippetOrders(items: CodeSnippetViewModel[]) {
    const nextOrders = buildExplicitOrderMap(items)
    snippetOrders.value = { ...nextOrders }
    try {
      snippetOrders.value = (await snippetApi.updateOrder(nextOrders)).data
      return true
    } catch (error) {
      return getErrorMessage(error, '代码块顺序保存失败')
    }
  }

  async function _persistRuleOrders(items: InjectionRule[]) {
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
   * why: 前端先做一轮用户可读的快速校验，
   * 把大多数结构问题拦在保存前；后端仍会复核，防止绕过 UI 直接写入坏数据。
   */
  function _validateRule(rule: EditableInjectionRule): string | null {
    if ((rule.mode === 'SELECTOR' || rule.mode === 'ID') && !rule.match.trim())
      return '请填写匹配内容'
    const result = resolveRuleMatchRule(rule)
    if (result.error) return `匹配规则有误：${formatMatchRuleError(result.error)}`
    if (!isValidMatchRule(result.rule)) return '请完善匹配规则'
    return null
  }

  async function _applySnippetRuleSelection(snippetId: string, nextRuleIds: string[]) {
    const next = new Set(uniqueStrings(nextRuleIds))
    const current = new Set(
      rules.value.filter((r) => r.snippetIds?.includes(snippetId)).map((r) => r.id),
    )
    await Promise.all(
      rules.value.map(async (rule) => {
        const has = current.has(rule.id)
        const shouldHave = next.has(rule.id)
        if (has === shouldHave) return
        const updatedIds = shouldHave
          ? uniqueStrings([...(rule.snippetIds ?? []), snippetId])
          : (rule.snippetIds ?? []).filter((id) => id !== snippetId)
        const payload = makeRulePayload(rule, updatedIds)
        if (!payload) throw new Error('匹配规则有误')
        await ruleApi.update(rule.id, payload)
      }),
    )
  }

  async function _applyRuleSnippetSelection(ruleId: string, nextSnippetIds: string[]) {
    const next = new Set(uniqueStrings(nextSnippetIds))
    const current = new Set(
      snippets.value.filter((s) => s.ruleIds?.includes(ruleId)).map((s) => s.id),
    )
    await Promise.all(
      snippets.value.map(async (snippet) => {
        const has = current.has(snippet.id)
        const shouldHave = next.has(snippet.id)
        if (has === shouldHave) return
        const updatedIds = shouldHave
          ? uniqueStrings([...(snippet.ruleIds ?? []), ruleId])
          : (snippet.ruleIds ?? []).filter((id) => id !== ruleId)
        await snippetApi.update(snippet.id, _makeSnippetPayload(snippet, updatedIds))
      }),
    )
  }

  async function fetchAll() {
    loading.value = true
    try {
      const [sr, rr, snippetOrderResp, ruleOrderResp] = await Promise.all([
        snippetApi.list(),
        ruleApi.list(),
        snippetApi.getOrder(),
        ruleApi.getOrder(),
      ])
      snippetsResp.value = sr.data
      rulesResp.value = rr.data
      snippetOrders.value = snippetOrderResp.data
      ruleOrders.value = ruleOrderResp.data
      _syncEditSnippet()
      _syncEditRule()
    } catch (error) {
      Toast.error(getErrorMessage(error, '加载数据失败'))
    } finally {
      loading.value = false
    }
  }

  function _syncEditSnippet() {
    if (!selectedSnippetId.value) {
      editSnippet.value = null
      editSnippetRuleIds.value = []
      editDirty.value = false
      return
    }
    const found = snippets.value.find((s) => s.id === selectedSnippetId.value)
    editSnippet.value = found ? found : null
    editSnippetRuleIds.value = rules.value
      .filter((r) => r.snippetIds?.includes(selectedSnippetId.value!))
      .map((r) => r.id)
    editDirty.value = false
  }

  function _syncEditRule() {
    if (!selectedRuleId.value) {
      editRule.value = null
      editRuleSnippetIds.value = []
      editDirty.value = false
      return
    }
    const found = rules.value.find((r) => r.id === selectedRuleId.value)
    editRule.value = found ? hydrateRuleForEditor(found) : null
    editRuleSnippetIds.value = snippets.value
      .filter((s) => s.ruleIds?.includes(selectedRuleId.value!))
      .map((s) => s.id)
    editDirty.value = false
  }

  watch(selectedSnippetId, _syncEditSnippet)
  watch(selectedRuleId, _syncEditRule)

  async function addSnippet(
    snippet: CodeSnippetViewModel,
    ruleIds: string[],
  ): Promise<string | null> {
    if (!snippet.code.trim()) {
      Toast.error('代码内容不能为空')
      return null
    }
    const nextRuleIds = _normalizeSnippetRuleIds(ruleIds)
    creating.value = true
    try {
      const res = await snippetApi.add(_makeSnippetPayload(snippet, nextRuleIds))
      const id = res.data.id
      if (nextRuleIds.length) await _applySnippetRuleSelection(id, nextRuleIds)
      await fetchAll()
      const orderResult = await _persistSnippetOrders(snippets.value)
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
    const err = _validateRule(rule)
    if (err) {
      Toast.error(err)
      return null
    }
    const nextSnippetIds = _normalizeRuleSnippetIds(rule, snippetIds)
    creating.value = true
    try {
      const payload = makeRulePayload(rule, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return null
      }
      const res = await ruleApi.add(payload)
      const id = res.data.id
      if (nextSnippetIds.length) await _applyRuleSnippetSelection(id, nextSnippetIds)
      await fetchAll()
      const orderResult = await _persistRuleOrders(rules.value)
      selectedRuleId.value = res.data.id
      if (orderResult === true) {
        Toast.success('规则已创建')
      } else {
        Toast.warning(`规则已创建，但顺序保存失败：${orderResult}`)
      }
      return res.data.id
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
    const currentSnippet = editSnippet.value
    const nextRuleIds = _normalizeSnippetRuleIds(editSnippetRuleIds.value)
    savingEditor.value = true
    try {
      await snippetApi.update(currentSnippet.id, _makeSnippetPayload(currentSnippet, nextRuleIds))
      await _applySnippetRuleSelection(currentSnippet.id, nextRuleIds)
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
    const err = ruleEditorError.value
    if (err) {
      Toast.error(err)
      return false
    }
    const nextSnippetIds = _normalizeRuleSnippetIds(editRule.value, editRuleSnippetIds.value)
    savingEditor.value = true
    try {
      const payload = makeRulePayload(editRule.value, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return false
      }
      await ruleApi.update(editRule.value.id, payload)
      await _applyRuleSnippetSelection(editRule.value.id, nextSnippetIds)
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
    try {
      editSnippet.value.enabled = nextEnabled
      await snippetApi.update(editSnippet.value.id, _makeSnippetPayload(editSnippet.value))
      await fetchAll()
    } catch (error) {
      editSnippet.value.enabled = !nextEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  async function toggleRuleEnabled() {
    if (!editRule.value) return
    const nextEnabled = !editRule.value.enabled
    if (nextEnabled) {
      const validationError = _validateRule(editRule.value)
      if (validationError) {
        Toast.error(`当前规则有错误，暂时无法启用：${validationError}`)
        return
      }
    }
    try {
      editRule.value.enabled = nextEnabled
      const payload = makeRulePayload(
        editRule.value,
        _normalizeRuleSnippetIds(editRule.value, editRuleSnippetIds.value),
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

  function toggleRuleInSnippetEditor(ruleId: string) {
    const ids = editSnippetRuleIds.value
    editSnippetRuleIds.value = ids.includes(ruleId)
      ? ids.filter((id) => id !== ruleId)
      : [...ids, ruleId]
    editDirty.value = true
  }

  function toggleSnippetInRuleEditor(snippetId: string) {
    const ids = editRuleSnippetIds.value
    editRuleSnippetIds.value = ids.includes(snippetId)
      ? ids.filter((n) => n !== snippetId)
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
          await _applySnippetRuleSelection(id, [])
          await snippetApi.delete(id)
          snippetsResp.value.items = snippetsResp.value.items.filter((s) => s.id !== id)
          if (selectedSnippetId.value === id) selectedSnippetId.value = null
          editSnippet.value = null
          editSnippetRuleIds.value = []
          editDirty.value = false
          const orderResult = await _persistSnippetOrders(snippets.value)
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
          await _applyRuleSnippetSelection(id, [])
          await ruleApi.delete(id)
          rulesResp.value.items = rulesResp.value.items.filter((r) => r.id !== id)
          if (selectedRuleId.value === id) selectedRuleId.value = null
          editRule.value = null
          editRuleSnippetIds.value = []
          editDirty.value = false
          const orderResult = await _persistRuleOrders(rules.value)
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
    _syncEditSnippet()
  }

  function discardRuleEdit() {
    _syncEditRule()
  }

  /**
   * why: 左侧资源列表的拖拽排序属于持久化排序，而不是临时前端排序；
   * 当前列表会被固化成显式的 1..n；未出现在 order map 里的新资源仍按默认 0 排到最前面。
   */
  async function reorderSnippet(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = _reorderItems(
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
      await fetchAll()
    } finally {
      syncingSnippetReorder.value = false
    }
  }

  async function reorderRule(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = _reorderItems(
      rules.value,
      payload.sourceId,
      payload.targetId,
      payload.placement,
    )
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
      await fetchAll()
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
    editSnippetRuleIds,
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
    toggleRuleInSnippetEditor,
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
