export type EditorEmptyStateLayout = "split-pane" | "compact";

/**
 * why: 空态文案的差异来自布局，不来自编辑器类型。
 * 把文案决策收敛到一个 helper，可以避免规则/代码片段编辑器各自维护一份提示词后再次分叉。
 */
export function resolveEditorEmptyStateMessage(options: {
  layout: EditorEmptyStateLayout;
  resourceLabel: string;
}) {
  if (options.layout === "compact") {
    return "从列表选择后进行编辑";
  }

  return `从左侧选择${options.resourceLabel}进行编辑`;
}
