import { describe, expect, it } from "vitest";

import { resolveEditorEmptyStateMessage } from "../editorEmptyState";

describe("resolveEditorEmptyStateMessage", () => {
  // why: 窄宽度下左右结构会折叠成抽屉/列表，继续说“从左侧选择”会误导当前布局；
  // 这里锁住布局才是文案真源，资源类型只是宽布局下的补充说明。
  it("uses a generic prompt for compact layouts", () => {
    expect(
      resolveEditorEmptyStateMessage({
        layout: "compact",
        resourceLabel: "规则",
      }),
    ).toBe("从列表选择后进行编辑");
  });

  it("keeps resource-specific guidance for split panes", () => {
    expect(
      resolveEditorEmptyStateMessage({
        layout: "split-pane",
        resourceLabel: "代码片段",
      }),
    ).toBe("从左侧选择代码片段进行编辑");
  });
});
