import { describe, expect, it } from "vitest";

import {
  applyTransformerRouteSelection,
  buildTransformerRouteQuery,
  isSameTransformerRouteState,
  parseTransformerRouteState,
  resolveVisibleTransformerSelection,
} from "../transformerRouteState";

describe("transformerRouteState", () => {
  // why: `action=create` 和 `mode=bulk` 都会改变页面语义；这些状态进入本地编辑器前必须先消除
  // `id` 等互斥字段，避免 URL 表达出一组彼此冲突的界面状态。
  it("drops selected ids for create and bulk route states", () => {
    expect(
      parseTransformerRouteState({
        tab: "rules",
        action: "create",
        id: "rule-a",
      }),
    ).toEqual({
      tab: "rules",
      action: "create",
      viewMode: "single",
      selectedId: null,
    });

    expect(
      parseTransformerRouteState({
        tab: "snippets",
        mode: "bulk",
        id: "snippet-a",
      }),
    ).toEqual({
      tab: "snippets",
      action: null,
      viewMode: "bulk",
      selectedId: null,
    });
  });

  // why: URL 是页面导航合同；serialize 必须和 parse 保持同一套互斥规则，否则内部状态同步时会不断抖动。
  it("builds mutually exclusive route queries from route state", () => {
    expect(
      buildTransformerRouteQuery(
        { foo: "bar", id: "old" },
        {
          tab: "rules",
          action: "create",
          viewMode: "single",
          selectedId: null,
        },
      ),
    ).toEqual({
      foo: "bar",
      tab: "rules",
      action: "create",
    });

    expect(
      buildTransformerRouteQuery(
        { foo: "bar", action: "create" },
        {
          tab: "snippets",
          action: null,
          viewMode: "bulk",
          selectedId: null,
        },
      ),
    ).toEqual({
      foo: "bar",
      tab: "snippets",
      mode: "bulk",
    });
  });

  // why: guarded navigation 要判断“目标页面语义是否真的变了”，而不是只比某一个 query 键；
  // 否则浏览器前进后退时仍可能绕过草稿保护。
  it("compares complete route states instead of individual query keys", () => {
    expect(
      isSameTransformerRouteState(
        {
          tab: "snippets",
          action: null,
          viewMode: "single",
          selectedId: "snippet-a",
        },
        {
          tab: "snippets",
          action: null,
          viewMode: "single",
          selectedId: "snippet-a",
        },
      ),
    ).toBe(true);

    expect(
      isSameTransformerRouteState(
        {
          tab: "snippets",
          action: null,
          viewMode: "single",
          selectedId: "snippet-a",
        },
        {
          tab: "snippets",
          action: null,
          viewMode: "bulk",
          selectedId: null,
        },
      ),
    ).toBe(false);
  });

  // why: 记住的选中项是标签页内的恢复锚点；
  // create / bulk 只改变当前页面语义，不该把这份记忆态顺手清空。
  it("preserves remembered selection for create and bulk route states", () => {
    expect(
      applyTransformerRouteSelection(
        {
          snippets: "snippet-a",
          rules: "rule-a",
        },
        {
          tab: "snippets",
          action: null,
          viewMode: "bulk",
          selectedId: null,
        },
      ),
    ).toEqual({
      snippets: "snippet-a",
      rules: "rule-a",
    });

    expect(
      applyTransformerRouteSelection(
        {
          snippets: "snippet-a",
          rules: "rule-a",
        },
        {
          tab: "rules",
          action: "create",
          viewMode: "single",
          selectedId: null,
        },
      ),
    ).toEqual({
      snippets: "snippet-a",
      rules: "rule-a",
    });
  });

  // why: UI 需要在 create / bulk 里隐藏列表高亮，
  // 但这只是“可见选中态”收起，不代表记住的选中项被删除。
  it("hides visible selection for create and bulk states", () => {
    expect(
      resolveVisibleTransformerSelection(
        {
          action: "create",
          viewMode: "single",
        },
        "snippet-a",
      ),
    ).toBeNull();

    expect(
      resolveVisibleTransformerSelection(
        {
          action: null,
          viewMode: "bulk",
        },
        "rule-a",
      ),
    ).toBeNull();

    expect(
      resolveVisibleTransformerSelection(
        {
          action: null,
          viewMode: "single",
        },
        "rule-a",
      ),
    ).toBe("rule-a");
  });
});
