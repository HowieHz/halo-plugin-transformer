import { Dialog, Toast } from "@halo-dev/components";
import { computed, type ComputedRef, type Ref } from "vue";

import { snippetApi } from "@/apis";
import type { TransformationSnippetEditorDraft, TransformationSnippetReadModel } from "@/types";

import { appendCreatedResourcesInOrder } from "./resourceOrder";
import { getErrorMessage } from "./resourceSupport";
import { buildSnippetWritePayload } from "./snippetDraft";
import { validateSnippetDraft } from "./snippetValidation";

interface UseSnippetStateOptions {
  creating: Ref<boolean>;
  savingEditor: Ref<boolean>;
  processingBulk: Ref<boolean>;
  snippets: ComputedRef<TransformationSnippetReadModel[]>;
  editSnippet: Ref<TransformationSnippetEditorDraft | null>;
  editDirty: Ref<boolean>;
  selectedSnippetId: Ref<string | null>;
  refreshSnippetSnapshot: () => Promise<void>;
  refreshAllResources: () => Promise<void>;
  saveSnippetOrderMap: (items: TransformationSnippetReadModel[]) => Promise<true | string>;
  applySavedSnippetSnapshot: (snippet: TransformationSnippetReadModel) => void;
}

/**
 * why: 代码片段的 CRUD / 启停 / 删除语义应集中在 snippet 上下文里；
 * 这样总控层只负责组合，而不会继续同时理解“代码片段怎么保存”和“规则怎么保存”两套细节。
 */
export function useSnippetState(options: UseSnippetStateOptions) {
  const snippetEditorError = computed(() =>
    options.editSnippet.value ? validateSnippetDraft(options.editSnippet.value) : null,
  );

  async function addSnippet(snippet: TransformationSnippetEditorDraft): Promise<string | null> {
    const validationError = validateSnippetDraft(snippet);
    if (validationError) {
      Toast.error(validationError);
      return null;
    }
    options.creating.value = true;
    try {
      const response = await snippetApi.add(buildSnippetWritePayload(snippet));
      const id = response.data.id;
      await options.refreshSnippetSnapshot();
      const orderResult = await options.saveSnippetOrderMap(options.snippets.value);
      options.selectedSnippetId.value = id;
      if (orderResult === true) {
        Toast.success("代码片段已创建");
      } else {
        Toast.warning(`代码片段已创建，但顺序保存失败：${orderResult}`);
      }
      return id;
    } catch (error) {
      Toast.error(getErrorMessage(error, "创建失败"));
      return null;
    } finally {
      options.creating.value = false;
    }
  }

  async function importSnippets(
    snippets: TransformationSnippetEditorDraft[],
    enabled: boolean,
  ): Promise<string[]> {
    if (!snippets.length) {
      return [];
    }

    options.processingBulk.value = true;
    const createdIds: string[] = [];
    const failures: string[] = [];

    try {
      for (const snippet of snippets) {
        const validationError = validateSnippetDraft(snippet);
        if (validationError) {
          failures.push(validationError);
          continue;
        }

        try {
          const response = await snippetApi.add(
            buildSnippetWritePayload({
              ...snippet,
              enabled,
            }),
          );
          createdIds.push(response.data.id);
        } catch (error) {
          failures.push(getErrorMessage(error, "创建失败"));
        }
      }

      if (createdIds.length > 0) {
        await options.refreshSnippetSnapshot();
        const orderedItems = appendCreatedResourcesInOrder(options.snippets.value, createdIds);
        const orderResult = await options.saveSnippetOrderMap(orderedItems);
        if (orderResult !== true) {
          failures.push(`顺序保存失败：${orderResult}`);
        }
      }

      if (createdIds.length > 0 && failures.length === 0) {
        Toast.success(`已导入 ${createdIds.length} 个代码片段`);
      } else if (createdIds.length > 0) {
        Toast.warning(`已导入 ${createdIds.length} 个代码片段，另有 ${failures.length} 项失败`);
      } else {
        Toast.error(failures[0] ?? "导入失败");
      }

      return createdIds;
    } finally {
      options.processingBulk.value = false;
    }
  }

  async function saveSnippet() {
    if (snippetEditorError.value) {
      Toast.error(snippetEditorError.value);
      return false;
    }
    if (!options.editSnippet.value) {
      return false;
    }
    options.savingEditor.value = true;
    try {
      await snippetApi.update(
        options.editSnippet.value.id,
        buildSnippetWritePayload(options.editSnippet.value),
      );
      await options.refreshSnippetSnapshot();
      options.editDirty.value = false;
      Toast.success("保存成功");
      return true;
    } catch (error) {
      Toast.error(getErrorMessage(error, "保存失败"));
      return false;
    } finally {
      options.savingEditor.value = false;
    }
  }

  async function toggleSnippetEnabled() {
    if (!options.editSnippet.value) return;
    const nextEnabled = !options.editSnippet.value.enabled;
    const previousEnabled = options.editSnippet.value.enabled;
    try {
      const response = await snippetApi.updateEnabled(
        options.editSnippet.value.id,
        nextEnabled,
        options.editSnippet.value.metadata.version,
      );
      options.applySavedSnippetSnapshot(response.data);
      Toast.success(nextEnabled ? "代码片段已启用" : "代码片段已停用");
    } catch (error) {
      options.editSnippet.value.enabled = previousEnabled;
      Toast.error(getErrorMessage(error, nextEnabled ? "启用失败" : "停用失败"));
    }
  }

  async function setSnippetsEnabled(ids: string[], enabled: boolean) {
    const targetSnippets = options.snippets.value.filter((snippet) => ids.includes(snippet.id));
    if (!targetSnippets.length) {
      Toast.warning("请先选择代码片段");
      return;
    }

    options.processingBulk.value = true;
    let successCount = 0;
    try {
      for (const snippet of targetSnippets) {
        try {
          await snippetApi.updateEnabled(snippet.id, enabled, snippet.metadata.version);
          successCount += 1;
        } catch {
          continue;
        }
      }

      await options.refreshSnippetSnapshot();

      if (successCount === targetSnippets.length) {
        Toast.success(`已${enabled ? "启用" : "禁用"} ${successCount} 个代码片段`);
      } else if (successCount > 0) {
        Toast.warning(
          `已${enabled ? "启用" : "禁用"} ${successCount} 个代码片段，另有 ${
            targetSnippets.length - successCount
          } 个失败`,
        );
      } else {
        Toast.error(`${enabled ? "启用" : "禁用"}失败`);
      }
    } finally {
      options.processingBulk.value = false;
    }
  }

  function confirmDeleteSnippet() {
    if (!options.editSnippet.value) return;
    const id = options.editSnippet.value.id;
    Dialog.warning({
      title: "删除代码片段",
      description: `确认删除代码片段 ${id}？删除后无法恢复。`,
      confirmType: "danger",
      async onConfirm() {
        try {
          await snippetApi.delete(id, options.editSnippet.value?.metadata.version);
          if (options.selectedSnippetId.value === id) options.selectedSnippetId.value = null;
          options.editSnippet.value = null;
          options.editDirty.value = false;
          await options.refreshAllResources();
          const orderResult = await options.saveSnippetOrderMap(options.snippets.value);
          if (orderResult === true) {
            Toast.success("代码片段已删除");
          } else {
            Toast.warning(`代码片段已删除，但顺序保存失败：${orderResult}`);
          }
        } catch (error) {
          Toast.error(getErrorMessage(error, "删除失败"));
        }
      },
    });
  }

  function confirmDeleteSnippets(ids: string[]) {
    const targetSnippets = options.snippets.value.filter((snippet) => ids.includes(snippet.id));
    if (!targetSnippets.length) {
      Toast.warning("请先选择代码片段");
      return;
    }

    Dialog.warning({
      title: "批量删除代码片段",
      description: `确认删除已选择的 ${targetSnippets.length} 个代码片段？删除后无法恢复。`,
      confirmType: "danger",
      async onConfirm() {
        options.processingBulk.value = true;
        let successCount = 0;
        try {
          for (const snippet of targetSnippets) {
            try {
              await snippetApi.delete(snippet.id, snippet.metadata.version);
              successCount += 1;
            } catch {
              continue;
            }
          }

          if (targetSnippets.some((snippet) => snippet.id === options.selectedSnippetId.value)) {
            options.selectedSnippetId.value = null;
            options.editSnippet.value = null;
            options.editDirty.value = false;
          }

          await options.refreshAllResources();
          const orderResult = await options.saveSnippetOrderMap(options.snippets.value);

          if (successCount === targetSnippets.length && orderResult === true) {
            Toast.success(`已删除 ${successCount} 个代码片段`);
          } else if (successCount > 0) {
            const suffix = orderResult === true ? "" : `；顺序保存失败：${orderResult}`;
            Toast.warning(
              `已删除 ${successCount} 个代码片段，另有 ${
                targetSnippets.length - successCount
              } 个失败${suffix}`,
            );
          } else {
            Toast.error("删除失败");
          }
        } finally {
          options.processingBulk.value = false;
        }
      },
    });
  }

  return {
    snippetEditorError,
    addSnippet,
    importSnippets,
    saveSnippet,
    toggleSnippetEnabled,
    setSnippetsEnabled,
    confirmDeleteSnippet,
    confirmDeleteSnippets,
  };
}
