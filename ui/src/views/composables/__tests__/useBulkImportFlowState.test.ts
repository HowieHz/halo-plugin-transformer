import { describe, expect, it } from "vitest";

import { useBulkImportFlowState } from "../useBulkImportFlowState";

describe("useBulkImportFlowState", () => {
  // why: 批量导入 UI 只能处于一个明确阶段；
  // 打开下一个阶段时，上一阶段的派生状态必须一起收口，避免 source/options/result 同时成立。
  it("moves through source, options and result as a single flow state", () => {
    const flow = useBulkImportFlowState();

    flow.openSource();
    expect(flow.sourceVisible.value).toBe(true);
    expect(flow.pendingImport.value).toBeNull();
    expect(flow.importResult.value).toBeNull();

    flow.openOptions({
      tab: "snippets",
      items: [],
    });
    expect(flow.sourceVisible.value).toBe(false);
    expect(flow.pendingImport.value).toEqual({
      tab: "snippets",
      items: [],
    });
    expect(flow.importResult.value).toBeNull();

    flow.openResult({
      count: 2,
      tab: "snippets",
    });
    expect(flow.sourceVisible.value).toBe(false);
    expect(flow.pendingImport.value).toBeNull();
    expect(flow.importResult.value).toEqual({
      count: 2,
      tab: "snippets",
    });
  });

  // why: 取消和“继续导入”都应该把流程重置到明确状态；
  // 否则上一次 pending payload / result 会偷偷残留到下一轮导入里。
  it("resets derived state when closing or continuing the flow", () => {
    const flow = useBulkImportFlowState();

    flow.openOptions({
      tab: "rules",
      items: [],
    });
    flow.close();

    expect(flow.sourceVisible.value).toBe(false);
    expect(flow.pendingImport.value).toBeNull();
    expect(flow.importResult.value).toBeNull();

    flow.openResult({
      count: 1,
      tab: "rules",
    });
    flow.continueImport();

    expect(flow.sourceVisible.value).toBe(true);
    expect(flow.pendingImport.value).toBeNull();
    expect(flow.importResult.value).toBeNull();
  });
});
