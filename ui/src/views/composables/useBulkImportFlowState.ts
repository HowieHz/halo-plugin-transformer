import { computed, ref } from "vue";

import type {
  ActiveTab,
  TransformationSnippetEditorDraft,
  TransformationRuleEditorDraft,
} from "@/types";

export type BulkImportPayload =
  | { tab: "snippets"; items: TransformationSnippetEditorDraft[] }
  | { tab: "rules"; items: TransformationRuleEditorDraft[] };

export interface BulkImportResultState {
  count: number;
  tab: ActiveTab;
}

type BulkImportFlowState =
  | { step: "idle" }
  | { step: "source" }
  | { step: "options"; payload: BulkImportPayload }
  | { step: "result"; result: BulkImportResultState };

/**
 * why: 批量导入是一个线性 UI 流程；
 * 用单一判别状态表达 source/options/result，能避免多颗 ref 拼出互相矛盾的组合态。
 */
export function useBulkImportFlowState() {
  const flowState = ref<BulkImportFlowState>({ step: "idle" });

  const sourceVisible = computed(() => flowState.value.step === "source");
  const pendingImport = computed(() =>
    flowState.value.step === "options" ? flowState.value.payload : null,
  );
  const importResult = computed(() =>
    flowState.value.step === "result" ? flowState.value.result : null,
  );

  function openSource() {
    flowState.value = { step: "source" };
  }

  function openOptions(payload: BulkImportPayload) {
    flowState.value = { step: "options", payload };
  }

  function openResult(result: BulkImportResultState) {
    flowState.value = { step: "result", result };
  }

  function close() {
    flowState.value = { step: "idle" };
  }

  function continueImport() {
    openSource();
  }

  return {
    flowState,
    sourceVisible,
    pendingImport,
    importResult,
    openSource,
    openOptions,
    openResult,
    close,
    continueImport,
  };
}
