import { describe, expect, it } from "vitest";

import { makeSnippetEditorDraft } from "@/types";

import { validateSnippetDraft } from "../snippetValidation";

describe("validateSnippetDraft", () => {
  // why: 代码内容为空是 snippet 写路径的共同领域约束；
  // 一旦这里改坏，新建、编辑和导入提示都会一起漂移。
  it("rejects blank code", () => {
    const snippet = makeSnippetEditorDraft({
      code: "   ",
    });

    expect(validateSnippetDraft(snippet)).toBe("代码内容不能为空");
  });
});
