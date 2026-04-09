import { describe, expect, it, vi } from "vitest";
import { ref } from "vue";

import type { ActiveTab } from "@/types";

import { useCreateSessionState, type CreateFormController } from "../useCreateSessionState";

function makeFormController() {
  return {
    reset: vi.fn(),
    hasUnsavedChanges: vi.fn(() => true),
    getValidationError: vi.fn(() => "validation error"),
    getSubmitPayload: vi.fn(() => ({ ok: true as const })),
  } satisfies CreateFormController<{ ok: true }>;
}

describe("useCreateSessionState", () => {
  // why: create modal 是一段独立会话；一旦用户决定放弃，必须同时回滚草稿并终止这段会话，
  // 否则后续切 tab / 切选中项时就会继续挂着一层已经失效的旧 modal。
  it("discards the current create session by resetting and closing it", () => {
    const snippetForm = makeFormController();
    const ruleForm = makeFormController();
    const state = useCreateSessionState({
      snippetFormRef: ref(snippetForm),
      ruleFormRef: ref(ruleForm),
    });

    state.open("snippets");
    state.discardCurrentSession();

    expect(snippetForm.reset).toHaveBeenCalledTimes(1);
    expect(ruleForm.reset).not.toHaveBeenCalled();
    expect(state.createModalTab.value).toBeNull();
  });

  // why: create session 的 authoritative source 只有 `createModalTab`；
  // 切走当前上下文时应统一关闭，而不是让旧 tab 的 create 语义继续泄漏到新页面语义中。
  it("closes the active create session regardless of the next view target", () => {
    const state = useCreateSessionState({
      snippetFormRef: ref(makeFormController()),
      ruleFormRef: ref(makeFormController()),
    });

    state.open("rules");
    state.close();

    expect(state.createModalTab.value).toBeNull();
  });

  it("reads validation and dirty state from the active session only", () => {
    const snippetForm = makeFormController();
    const ruleForm = makeFormController();
    const state = useCreateSessionState({
      snippetFormRef: ref(snippetForm),
      ruleFormRef: ref(ruleForm),
    });

    state.open("rules" satisfies ActiveTab);

    expect(state.hasUnsavedChanges()).toBe(true);
    expect(state.getValidationError()).toBe("validation error");
    expect(ruleForm.hasUnsavedChanges).toHaveBeenCalledTimes(1);
    expect(ruleForm.getValidationError).toHaveBeenCalledTimes(1);
    expect(snippetForm.hasUnsavedChanges).not.toHaveBeenCalled();
    expect(snippetForm.getValidationError).not.toHaveBeenCalled();
  });
});
