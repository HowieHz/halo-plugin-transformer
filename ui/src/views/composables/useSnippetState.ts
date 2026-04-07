import { computed, type ComputedRef, type Ref } from 'vue'
import { Dialog, Toast } from '@halo-dev/components'
import { snippetApi } from '@/apis'
import type { CodeSnippetEditorDraft, CodeSnippetReadModel } from '@/types'
import { buildSnippetWritePayload } from './snippetDraft'
import { getErrorMessage } from './injectorShared'

interface UseSnippetStateOptions {
  creating: Ref<boolean>
  savingEditor: Ref<boolean>
  snippets: ComputedRef<CodeSnippetReadModel[]>
  editSnippet: Ref<CodeSnippetEditorDraft | null>
  editDirty: Ref<boolean>
  selectedSnippetId: Ref<string | null>
  fetchAll: () => Promise<void>
  saveSnippetOrderMap: (items: CodeSnippetReadModel[]) => Promise<true | string>
  applySavedSnippetSnapshot: (snippet: CodeSnippetReadModel) => void
}

/**
 * why: 代码块的 CRUD / 启停 / 删除语义应集中在 snippet 上下文里；
 * 这样总控层只负责组合，而不会继续同时理解“代码块怎么保存”和“规则怎么保存”两套细节。
 */
export function useSnippetState(options: UseSnippetStateOptions) {
  const snippetEditorError = computed(() => {
    if (!options.editSnippet.value?.code.trim()) {
      return '代码内容不能为空'
    }
    return null
  })

  async function addSnippet(snippet: CodeSnippetEditorDraft): Promise<string | null> {
    if (!snippet.code.trim()) {
      Toast.error('代码内容不能为空')
      return null
    }
    options.creating.value = true
    try {
      const response = await snippetApi.add(buildSnippetWritePayload(snippet))
      const id = response.data.id
      await options.fetchAll()
      const orderResult = await options.saveSnippetOrderMap(options.snippets.value)
      options.selectedSnippetId.value = id
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
      options.creating.value = false
    }
  }

  async function saveSnippet() {
    if (snippetEditorError.value) {
      Toast.error(snippetEditorError.value)
      return false
    }
    if (!options.editSnippet.value) {
      return false
    }
    options.savingEditor.value = true
    try {
      await snippetApi.update(
        options.editSnippet.value.id,
        buildSnippetWritePayload(options.editSnippet.value),
      )
      await options.fetchAll()
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

  async function toggleSnippetEnabled() {
    if (!options.editSnippet.value) return
    const nextEnabled = !options.editSnippet.value.enabled
    const previousEnabled = options.editSnippet.value.enabled
    try {
      const response = await snippetApi.updateEnabled(
        options.editSnippet.value.id,
        nextEnabled,
        options.editSnippet.value.metadata.version,
      )
      options.applySavedSnippetSnapshot(response.data)
      Toast.success(nextEnabled ? '代码块已启用' : '代码块已停用')
    } catch (error) {
      options.editSnippet.value.enabled = previousEnabled
      Toast.error(getErrorMessage(error, nextEnabled ? '启用失败' : '停用失败'))
    }
  }

  function confirmDeleteSnippet() {
    if (!options.editSnippet.value) return
    const id = options.editSnippet.value.id
    Dialog.warning({
      title: '删除代码块',
      description: `确认删除代码块 ${id}？删除后无法恢复。`,
      confirmType: 'danger',
      async onConfirm() {
        try {
          await snippetApi.delete(id)
          if (options.selectedSnippetId.value === id) options.selectedSnippetId.value = null
          options.editSnippet.value = null
          options.editDirty.value = false
          await options.fetchAll()
          const orderResult = await options.saveSnippetOrderMap(options.snippets.value)
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

  return {
    snippetEditorError,
    addSnippet,
    saveSnippet,
    toggleSnippetEnabled,
    confirmDeleteSnippet,
  }
}
