import { describe, expect, it } from "vitest";

import { appendCreatedResourcesInOrder, buildExplicitOrderMap, sortByOrderMap } from "../util";

describe("sortByOrderMap", () => {
  // why: 未显式排序的资源默认按 0 处理并排在最前面，这样新增项不需要先保存 order map 也能自然浮到顶部。
  it("puts implicit zero-order items before explicit orders", () => {
    const items = [
      { id: "rule-b", name: "Bravo" },
      { id: "rule-a", name: "Alpha" },
      { id: "rule-c", name: "Charlie" },
    ];

    const sorted = sortByOrderMap(items, { "rule-c": 2, "rule-b": 1 });

    expect(sorted.map((item) => item.id)).toEqual(["rule-a", "rule-b", "rule-c"]);
  });

  // why: 当多个资源都还处于默认 0 时，列表顺序必须稳定且可预期；这里按显示名称字符序排，名称为空时再回退到 ID。
  it("sorts same-order items by display name", () => {
    const items = [
      { id: "rule-b", name: "Bravo" },
      { id: "rule-a", name: "Alpha" },
      { id: "rule-c", name: "" },
    ];

    const sorted = sortByOrderMap(items, {});

    expect(sorted.map((item) => item.id)).toEqual(["rule-a", "rule-b", "rule-c"]);
  });

  // why: 前后端在同 order、同显示名时都必须回退到 id；
  // 否则前端即时拖拽后的顺序和后端保存后再读出的顺序会发生抖动。
  it("sorts same-order and same-name items by id", () => {
    const items = [
      { id: "rule-b", name: "Same" },
      { id: "rule-a", name: "Same" },
      { id: "rule-c", name: "Same" },
    ];

    const sorted = sortByOrderMap(items, {});

    expect(sorted.map((item) => item.id)).toEqual(["rule-a", "rule-b", "rule-c"]);
  });
});

describe("buildExplicitOrderMap", () => {
  // why: 用户一旦拖拽过顺序，就应把当前整组列表固化成 1..n；后续新增项仍可继续凭默认 0 插到前面。
  it("builds ascending explicit order values from current list order", () => {
    const orders = buildExplicitOrderMap([{ id: "a" }, { id: "b" }, { id: "c" }]);

    expect(orders).toEqual({
      a: 1,
      b: 2,
      c: 3,
    });
  });
});

describe("appendCreatedResourcesInOrder", () => {
  // why: 批量导入成功项需要按“创建成功顺序”稳定追加到末尾，避免 snippet/rule 各自维护不同的导入后排序语义。
  it("moves created resources to the end in creation order", () => {
    const items = [
      { id: "existing-a" },
      { id: "created-b" },
      { id: "existing-c" },
      { id: "created-a" },
    ];

    const ordered = appendCreatedResourcesInOrder(items, ["created-a", "created-b"]);

    expect(ordered.map((item) => item.id)).toEqual([
      "existing-a",
      "existing-c",
      "created-a",
      "created-b",
    ]);
  });
});
