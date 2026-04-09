import type { TransformationSnippetEditorDraft } from "@/types";

/**
 * why: 代码片段的前端校验同样需要单一真源；
 * 这样新建弹窗、右侧编辑器和导入提示才会共享同一条领域语义。
 */
export function validateSnippetDraft(
  snippet: Pick<TransformationSnippetEditorDraft, "code">,
): string | null {
  if (!snippet.code.trim()) {
    return "代码内容不能为空";
  }
  return null;
}
