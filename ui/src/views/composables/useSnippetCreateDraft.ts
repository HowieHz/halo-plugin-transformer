import { computed, ref } from "vue";

import type { TransformationSnippetEditorDraft } from "@/types";
import { makeSnippetEditorDraft } from "@/types";

import { validateSnippetDraft } from "./snippetValidation";
import { parseSnippetTransfer } from "./transfer";

/**
 * why: 新建代码片段和右侧编辑器都在操作同一个领域草稿模型；
 * 把初始值（baseline）/ 是否已修改（dirty）/ 校验 / 提交快照（submit snapshot）收口到 composable 后，弹窗组件才能退回纯展示层。
 */
export function useSnippetCreateDraft() {
  const draft = ref<TransformationSnippetEditorDraft>(makeSnippetEditorDraft());
  const baseline = makeSnippetEditorDraft();

  const validationError = computed(() => validateSnippetDraft(draft.value));

  function reset() {
    draft.value = makeSnippetEditorDraft();
  }

  function hasUnsavedChanges() {
    return (
      draft.value.enabled !== baseline.enabled ||
      draft.value.name !== baseline.name ||
      draft.value.description !== baseline.description ||
      draft.value.code !== baseline.code
    );
  }

  function getSubmitPayload() {
    return {
      snippet: {
        ...draft.value,
      },
    };
  }

  function importFromTransfer(raw: string) {
    draft.value = parseSnippetTransfer(raw);
    return draft.value;
  }

  return {
    draft,
    validationError,
    reset,
    hasUnsavedChanges,
    getSubmitPayload,
    importFromTransfer,
  };
}
