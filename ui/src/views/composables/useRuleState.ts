import { computed, type ComputedRef, type Ref } from 'vue'
import { Dialog, Toast } from '@halo-dev/components'
import { ruleApi } from '@/apis'
import type { InjectionRuleEditorDraft, InjectionRuleReadModel } from '@/types'
import { formatMatchRuleError, isValidMatchRule, resolveRuleMatchRule } from './matchRule'
import { buildRuleWritePayload } from './ruleDraft'
import { getErrorMessage } from './injectorShared'
import { uniqueStrings } from './util'

interface UseRuleStateOptions {
  creating: Ref<boolean>
  savingEditor: Ref<boolean>
  processingBulk: Ref<boolean>
  rules: ComputedRef<InjectionRuleReadModel[]>
  editRule: Ref<InjectionRuleEditorDraft | null>
  editRuleSnippetIds: Ref<string[]>
  editDirty: Ref<boolean>
  selectedRuleId: Ref<string | null>
  refreshRuleList: () => Promise<void>
  saveRuleOrderMap: (items: InjectionRuleReadModel[]) => Promise<true | string>
  applySavedRuleSnapshot: (rule: InjectionRuleReadModel) => void
}

/**
 * why: 规则上下文本身比代码块更复杂，包含 match-rule 校验、snippet 关系归一化与 CRUD；
 * 独立拆出后，规则写语义不再和列表装载、选中态、排序队列挤在同一个 500+ 行模块里。
 */
export function useRuleState(options: UseRuleStateOptions) {
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

  /**
   * why: 前端先做一层用户可读的快速校验，把大部分编辑态错误拦在保存前；
   * 后端仍会复核，但这里要尽量把错误定位成用户能直接修的提示。
   */
  function validateRuleDraft(rule: InjectionRuleEditorDraft): string | null {
    if (rule.mode === 'SELECTOR' && !rule.match.trim()) {
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

  const ruleEditorError = computed(() => {
    if (!options.editRule.value) return null
    return validateRuleDraft(options.editRule.value)
  })

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
    options.creating.value = true
    try {
      const payload = buildRuleWritePayload(rule, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return null
      }
      const response = await ruleApi.add(payload)
      await options.refreshRuleList()
      const orderResult = await options.saveRuleOrderMap(options.rules.value)
      options.selectedRuleId.value = response.data.id
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
      options.creating.value = false
    }
  }

  async function importRules(
    rules: InjectionRuleEditorDraft[],
    enabled: boolean,
  ): Promise<string[]> {
    if (!rules.length) {
      return []
    }

    options.processingBulk.value = true
    const createdIds: string[] = []
    const failures: string[] = []

    try {
      for (const rule of rules) {
        const error = validateRuleDraft(rule)
        if (error) {
          failures.push(error)
          continue
        }

        try {
          const payload = buildRuleWritePayload(
            {
              ...rule,
              enabled,
            },
            resolvePersistedSnippetIdsForRule(rule, []),
          )
          if (!payload) {
            failures.push('匹配规则有误，请先修正后再保存')
            continue
          }
          const response = await ruleApi.add(payload)
          createdIds.push(response.data.id)
        } catch (error) {
          failures.push(getErrorMessage(error, '创建失败'))
        }
      }

      if (createdIds.length > 0) {
        await options.refreshRuleList()
        const orderedItems = appendCreatedResourcesInOrder(options.rules.value, createdIds)
        const orderResult = await options.saveRuleOrderMap(orderedItems)
        if (orderResult !== true) {
          failures.push(`顺序保存失败：${orderResult}`)
        }
      }

      if (createdIds.length > 0 && failures.length === 0) {
        Toast.success(`已导入 ${createdIds.length} 个注入规则`)
      } else if (createdIds.length > 0) {
        Toast.warning(`已导入 ${createdIds.length} 个注入规则，另有 ${failures.length} 项失败`)
      } else {
        Toast.error(failures[0] ?? '导入失败')
      }

      return createdIds
    } finally {
      options.processingBulk.value = false
    }
  }

  async function saveRule() {
    if (!options.editRule.value) return false
    const error = ruleEditorError.value
    if (error) {
      Toast.error(error)
      return false
    }
    const nextSnippetIds = resolvePersistedSnippetIdsForRule(
      options.editRule.value,
      options.editRuleSnippetIds.value,
    )
    options.savingEditor.value = true
    try {
      const payload = buildRuleWritePayload(options.editRule.value, nextSnippetIds)
      if (!payload) {
        Toast.error('匹配规则有误，请先修正后再保存')
        return false
      }
      await ruleApi.update(options.editRule.value.id, payload)
      await options.refreshRuleList()
      options.editDirty.value = false
      Toast.success('保存成功')
      return true
    } catch (error) {
      Toast.error(getErrorMessage(error, '保存失败'))
      return false
    } finally {
      options.savingEditor.value = false
    }
  }

  async function toggleRuleEnabled() {
    if (!options.editRule.value) return
    const nextEnabled = !options.editRule.value.enabled
    const previousEnabled = options.editRule.value.enabled
    try {
      const response = await ruleApi.updateEnabled(
        options.editRule.value.id,
        nextEnabled,
        options.editRule.value.metadata.version,
      )
      options.applySavedRuleSnapshot(response.data)
      Toast.success(nextEnabled ? '规则已启用' : '规则已停用')
    } catch (error) {
      options.editRule.value.enabled = previousEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  async function setRulesEnabled(ids: string[], enabled: boolean) {
    const targetRules = options.rules.value.filter((rule) => ids.includes(rule.id))
    if (!targetRules.length) {
      Toast.warning('请先选择注入规则')
      return
    }

    options.processingBulk.value = true
    let successCount = 0
    try {
      for (const rule of targetRules) {
        try {
          await ruleApi.updateEnabled(rule.id, enabled, rule.metadata.version)
          successCount += 1
        } catch {
          continue
        }
      }

      await options.refreshRuleList()

      if (successCount === targetRules.length) {
        Toast.success(`已${enabled ? '启用' : '禁用'} ${successCount} 个注入规则`)
      } else if (successCount > 0) {
        Toast.warning(
          `已${enabled ? '启用' : '禁用'} ${successCount} 个注入规则，另有 ${
            targetRules.length - successCount
          } 个失败`,
        )
      } else {
        Toast.error(`${enabled ? '启用' : '禁用'}失败`)
      }
    } finally {
      options.processingBulk.value = false
    }
  }

  function confirmDeleteRule() {
    if (!options.editRule.value) return
    const id = options.editRule.value.id
    Dialog.warning({
      title: '删除规则',
      description: `确认删除规则 ${id}？删除后无法恢复。`,
      confirmType: 'danger',
      async onConfirm() {
        try {
          await ruleApi.delete(id)
          if (options.selectedRuleId.value === id) options.selectedRuleId.value = null
          options.editRule.value = null
          options.editRuleSnippetIds.value = []
          options.editDirty.value = false
          await options.refreshRuleList()
          const orderResult = await options.saveRuleOrderMap(options.rules.value)
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

  function confirmDeleteRules(ids: string[]) {
    const targetRules = options.rules.value.filter((rule) => ids.includes(rule.id))
    if (!targetRules.length) {
      Toast.warning('请先选择注入规则')
      return
    }

    Dialog.warning({
      title: '批量删除注入规则',
      description: `确认删除已选择的 ${targetRules.length} 个注入规则？删除后无法恢复。`,
      confirmType: 'danger',
      async onConfirm() {
        options.processingBulk.value = true
        let successCount = 0
        try {
          for (const rule of targetRules) {
            try {
              await ruleApi.delete(rule.id)
              successCount += 1
            } catch {
              continue
            }
          }

          if (targetRules.some((rule) => rule.id === options.selectedRuleId.value)) {
            options.selectedRuleId.value = null
            options.editRule.value = null
            options.editRuleSnippetIds.value = []
            options.editDirty.value = false
          }

          await options.refreshRuleList()
          const orderResult = await options.saveRuleOrderMap(options.rules.value)

          if (successCount === targetRules.length && orderResult === true) {
            Toast.success(`已删除 ${successCount} 个注入规则`)
          } else if (successCount > 0) {
            const suffix = orderResult === true ? '' : `；顺序保存失败：${orderResult}`
            Toast.warning(
              `已删除 ${successCount} 个注入规则，另有 ${
                targetRules.length - successCount
              } 个失败${suffix}`,
            )
          } else {
            Toast.error('删除失败')
          }
        } finally {
          options.processingBulk.value = false
        }
      },
    })
  }

  return {
    ruleEditorError,
    addRule,
    importRules,
    saveRule,
    toggleRuleEnabled,
    setRulesEnabled,
    confirmDeleteRule,
    confirmDeleteRules,
  }
}

function appendCreatedResourcesInOrder<T extends { id: string }>(items: T[], createdIds: string[]) {
  const createdIdSet = new Set(createdIds)
  const untouchedItems = items.filter((item) => !createdIdSet.has(item.id))
  const createdItems = createdIds
    .map((id) => items.find((item) => item.id === id))
    .filter((item): item is T => !!item)
  return [...untouchedItems, ...createdItems]
}
