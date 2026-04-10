import { describe, expect, it } from "vitest";
import { ref } from "vue";

import type { ActiveTab } from "@/types";

import { useTransformerViewSessionState } from "../useTransformerViewSessionState";

function createState() {
  const activeTab = ref<ActiveTab>("snippets");
  const selectedSnippetId = ref<string | null>("snippet-a");
  const selectedRuleId = ref<string | null>("rule-a");
  const viewMode = ref<"single" | "create" | "bulk">("single");

  return {
    activeTab,
    selectedSnippetId,
    selectedRuleId,
    viewMode,
    state: useTransformerViewSessionState({
      activeTab,
      selectedSnippetId,
      selectedRuleId,
      viewMode,
    }),
  };
}

describe("useTransformerViewSessionState", () => {
  // why: 页面级会话状态的价值就在于把 `single / create / bulk` 收成唯一判别源；
  // 这些入口一旦又开始各改各的 ref，路由与 UI 语义就会重新漂移。
  it("switches page mode through explicit single/create/bulk transitions", () => {
    const { activeTab, viewMode, state } = createState();

    state.openCreate("rules");
    expect(activeTab.value).toBe("rules");
    expect(viewMode.value).toBe("create");
    expect(state.createModalTab.value).toBe("rules");

    state.enterBulkMode();
    expect(viewMode.value).toBe("bulk");
    expect(state.isBulkMode.value).toBe(true);

    state.exitBulkMode();
    expect(viewMode.value).toBe("single");
    expect(state.createModalTab.value).toBeNull();
  });

  // why: 按 tab 隔离的 bulk 选择集只有在切 tab 时继续保留 bulk 语义才有意义；
  // 否则用户一切 tab 就被静默踢回单项模式，批量上下文会变成“看起来有状态、实际上进不去”。
  it("preserves bulk mode across tab switches but still closes create mode", () => {
    const { activeTab, viewMode, state } = createState();

    state.enterBulkMode("snippets");
    state.switchTab("rules");

    expect(activeTab.value).toBe("rules");
    expect(viewMode.value).toBe("bulk");
    expect(state.currentRouteState()).toEqual({
      tab: "rules",
      action: null,
      viewMode: "bulk",
      selectedId: null,
    });

    state.openCreate("snippets");
    state.switchTab("rules");

    expect(activeTab.value).toBe("rules");
    expect(viewMode.value).toBe("single");
    expect(state.createModalTab.value).toBeNull();
  });

  // why: create / bulk 只是隐藏当前可见选中态；remembered selection 仍然要保住，
  // 否则用户离开这两种页面语义后就回不到刚才正在看的资源。
  it("keeps remembered selection while hiding visible selection outside single mode", () => {
    const { selectedSnippetId, state } = createState();

    state.openCreate("snippets");
    expect(state.currentSelectedId("snippets")).toBeNull();
    expect(selectedSnippetId.value).toBe("snippet-a");

    state.enterBulkMode("snippets");
    expect(state.currentSelectedId("snippets")).toBeNull();
    expect(selectedSnippetId.value).toBe("snippet-a");
  });

  // why: 路由同步不能只会 parse；本地页面会话也必须能稳定 serialize / apply，
  // 否则前进后退和组件内跳转会走成两套不同状态机。
  it("applies route states and rebuilds route state from the current session", () => {
    const { selectedSnippetId, selectedRuleId, state } = createState();

    state.applyRouteState({
      tab: "rules",
      action: "create",
      viewMode: "single",
      selectedId: null,
    });

    expect(state.sessionState.value).toEqual({
      tab: "rules",
      mode: "create",
    });
    expect(selectedSnippetId.value).toBe("snippet-a");
    expect(selectedRuleId.value).toBe("rule-a");

    state.selectResource("rules", "rule-b");

    expect(state.currentRouteState()).toEqual({
      tab: "rules",
      action: null,
      viewMode: "single",
      selectedId: "rule-b",
    });
  });
});
