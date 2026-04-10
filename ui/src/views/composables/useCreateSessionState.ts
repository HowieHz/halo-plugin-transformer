import type { Ref } from "vue";

import type { ActiveTab } from "@/types";

export interface CreateFormController<TSubmitPayload> {
  reset: () => void;
  hasUnsavedChanges: () => boolean;
  getValidationError: () => string | null;
  getSubmitPayload: () => TSubmitPayload;
}

interface UseCreateSessionStateOptions<TSnippetSubmitPayload, TRuleSubmitPayload> {
  createModalTab: Ref<ActiveTab | null>;
  snippetFormRef: Ref<CreateFormController<TSnippetSubmitPayload> | null>;
  ruleFormRef: Ref<CreateFormController<TRuleSubmitPayload> | null>;
}

/**
 * why: create modal 不是一个“顺手挂着的 UI 开关”，而是一段有明确起止点的会话；
 * 把打开、关闭、重置、放弃统一到单一原语里，才能避免切 tab / 切选中项后旧会话残留。
 */
export function useCreateSessionState<TSnippetSubmitPayload, TRuleSubmitPayload>(
  options: UseCreateSessionStateOptions<TSnippetSubmitPayload, TRuleSubmitPayload>,
) {
  const createModalTab = options.createModalTab;

  function open(tab: ActiveTab) {
    createModalTab.value = tab;
  }

  function close(tab?: ActiveTab) {
    if (tab && createModalTab.value !== tab) {
      return;
    }
    createModalTab.value = null;
  }

  function currentController() {
    if (createModalTab.value === "snippets") {
      return options.snippetFormRef.value;
    }
    if (createModalTab.value === "rules") {
      return options.ruleFormRef.value;
    }
    return null;
  }

  function hasUnsavedChanges() {
    return currentController()?.hasUnsavedChanges() ?? false;
  }

  function getValidationError() {
    return currentController()?.getValidationError() ?? null;
  }

  function resetForm(tab: ActiveTab) {
    if (tab === "snippets") {
      options.snippetFormRef.value?.reset();
      return;
    }
    options.ruleFormRef.value?.reset();
  }

  function discardCurrentSession() {
    const currentTab = createModalTab.value;
    if (!currentTab) {
      return;
    }
    resetForm(currentTab);
    close(currentTab);
  }

  return {
    createModalTab,
    open,
    close,
    currentController,
    hasUnsavedChanges,
    getValidationError,
    resetForm,
    discardCurrentSession,
  };
}
