import { describe, expect, it } from "vitest";

import { useSnippetCreateDraft } from "../useSnippetCreateDraft";

describe("useSnippetCreateDraft", () => {
  // why: 新建弹窗的 dirty 语义现在由共享 controller 承担；
  // 这里锁住它，避免以后又把“是否脏”偷偷散回组件里各算各的。
  it("tracks dirty state and submit payload from the shared draft", () => {
    const createDraft = useSnippetCreateDraft();

    expect(createDraft.hasUnsavedChanges()).toBe(false);

    createDraft.draft.value.code = "<div>hello</div>";

    expect(createDraft.hasUnsavedChanges()).toBe(true);
    expect(createDraft.getSubmitPayload().snippet.code).toBe("<div>hello</div>");
  });
});
