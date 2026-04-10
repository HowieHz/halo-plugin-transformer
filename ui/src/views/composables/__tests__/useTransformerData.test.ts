import { beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref } from "vue";

import {
  makeRuleEditorDraft,
  makeSnippetEditorDraft,
  type ActiveTab,
  type OrderedItemList,
} from "@/types";

const { toast, dialog, snippetApi, ruleApi } = vi.hoisted(() => ({
  toast: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
  dialog: {
    warning: vi.fn(),
  },
  snippetApi: {
    getSnapshot: vi.fn(),
    add: vi.fn(),
    update: vi.fn(),
    updateEnabled: vi.fn(),
    updateOrder: vi.fn(),
    delete: vi.fn(),
  },
  ruleApi: {
    getSnapshot: vi.fn(),
    add: vi.fn(),
    update: vi.fn(),
    updateEnabled: vi.fn(),
    updateOrder: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock("@halo-dev/components", () => ({
  Dialog: dialog,
  Toast: toast,
}));

vi.mock("@/apis", () => ({
  snippetApi,
  ruleApi,
}));

import { useTransformerData } from "../useTransformerData";

function snapshotOf<T>(
  items: T[],
  orders: Record<string, number> = {},
  orderVersion = 1,
): OrderedItemList<T> {
  return {
    first: true,
    hasNext: false,
    hasPrevious: false,
    last: true,
    page: 0,
    size: items.length,
    totalPages: 1,
    items,
    total: items.length,
    orders,
    orderVersion,
  };
}

describe("useTransformerData", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
  });

  // why: 启停现在应走独立写口，并只改已保存规则的 enabled；
  // 当前右侧的未保存草稿既不该被顺手保存，也不该被整页重载冲掉。
  it("toggles rule enabled without saving or discarding other draft fields", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      enabled: false,
      mode: "FOOTER",
      match: "",
      snippetIds: [],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });
    ruleApi.updateEnabled.mockResolvedValue({
      data: {
        ...savedRule,
        enabled: true,
        metadata: { ...savedRule.metadata, version: 2 },
      },
    });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    store.editRule.value = {
      ...store.editRule.value!,
      mode: "SELECTOR",
      match: "",
      name: "draft name",
    };
    store.editDirty.value = true;

    await store.toggleRuleEnabled();

    expect(ruleApi.updateEnabled).toHaveBeenCalledTimes(1);
    expect(ruleApi.update).not.toHaveBeenCalled();
    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(1);
    expect(store.editRule.value?.enabled).toBe(true);
    expect(store.editRule.value?.mode).toBe("SELECTOR");
    expect(store.editRule.value?.name).toBe("draft name");
    expect(store.editRule.value?.metadata.version).toBe(2);
    expect(store.editDirty.value).toBe(true);
    expect(toast.error).not.toHaveBeenCalled();
  });

  // why: 启用规则会让后端把已保存资源收敛到规范持久化形态；
  // 当前草稿若还没脏，就应直接展示这份最新真源，而不是继续停留在旧形态造成前后端分叉。
  it("rehydrates a clean rule draft to the saved canonical shape after enabling", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      enabled: false,
      mode: "FOOTER",
      position: "REMOVE",
      wrapMarker: true,
      snippetIds: [" snippet-a ", "snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      code: "<div>ok</div>",
    });
    const canonicalRule = {
      ...savedRule,
      enabled: true,
      position: "APPEND" as const,
      snippetIds: ["snippet-a"],
      metadata: { ...savedRule.metadata, version: 2 },
    };

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });
    ruleApi.updateEnabled.mockResolvedValue({
      data: canonicalRule,
    });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    expect(store.editDirty.value).toBe(false);
    expect(store.editRule.value?.position).toBe("REMOVE");
    expect(store.editRule.value?.snippetIds).toEqual(["snippet-a"]);

    await store.toggleRuleEnabled();

    expect(store.editRule.value?.enabled).toBe(true);
    expect(store.editRule.value?.position).toBe("APPEND");
    expect(store.editRule.value?.snippetIds).toEqual(["snippet-a"]);
    expect(store.editRule.value?.metadata.version).toBe(2);
    expect(store.editDirty.value).toBe(false);
  });

  // why: 代码片段启停也应只切 enabled 本身；
  // 其它未保存编辑必须继续留在右侧，而不是被 fetchAll 覆盖掉。
  it("toggles snippet enabled without saving or discarding other draft fields", async () => {
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      enabled: false,
      code: "<div>ok</div>",
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    snippetApi.updateEnabled.mockResolvedValue({
      data: {
        ...savedSnippet,
        enabled: true,
        metadata: { ...savedSnippet.metadata, version: 2 },
      },
    });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });

    const store = useTransformerData(ref<ActiveTab>("snippets"));
    await store.fetchAll();
    store.selectedSnippetId.value = "snippet-a";
    await nextTick();

    store.editSnippet.value = {
      ...store.editSnippet.value!,
      code: "",
      name: "draft snippet",
    };
    store.editDirty.value = true;

    await store.toggleSnippetEnabled();

    expect(snippetApi.updateEnabled).toHaveBeenCalledTimes(1);
    expect(snippetApi.update).not.toHaveBeenCalled();
    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(1);
    expect(store.editSnippet.value?.enabled).toBe(true);
    expect(store.editSnippet.value?.code).toBe("");
    expect(store.editSnippet.value?.name).toBe("draft snippet");
    expect(store.editSnippet.value?.metadata.version).toBe(2);
    expect(store.editDirty.value).toBe(true);
    expect(toast.error).not.toHaveBeenCalled();
  });

  // why: 前端保存规则时不应再去二次改写代码片段；双向关联应交给后端单接口完成。
  it("saves rules without issuing secondary snippet update requests", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a" },
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      code: "<div>ok</div>",
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });
    ruleApi.update.mockResolvedValue({ data: savedRule });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    await store.saveRule();

    expect(ruleApi.update).toHaveBeenCalledTimes(1);
    expect(snippetApi.update).not.toHaveBeenCalled();
  });

  // why: `REMOVE` 只在 `SELECTOR` 模式下才代表“删除元素且不再消费代码片段”；
  // 旧的 UI 状态若残留到 `FOOTER/HEAD`，写链路也必须以统一 payload 语义为准，不能静默抹掉用户重新选回来的 snippetIds。
  it("preserves snippetIds when saving a non-selector rule with stale remove state", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      mode: "FOOTER",
      position: "REMOVE",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      code: "<div>ok</div>",
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) })
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) });
    ruleApi.update.mockResolvedValue({ data: savedRule });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    await store.saveRule();

    expect(ruleApi.update).toHaveBeenCalledWith(
      "rule-a",
      expect.objectContaining({
        mode: "FOOTER",
        position: "APPEND",
        snippetIds: ["snippet-a"],
      }),
    );
  });

  // why: 新建规则也必须复用同一份写模型收敛；
  // 否则表单看起来允许重新关联 snippet，但提交前会被另一层 helper 偷偷清空。
  it("preserves snippetIds when adding a non-selector rule with stale remove state", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const createdRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      mode: "FOOTER",
      position: "APPEND",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (createdRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      code: "<div>ok</div>",
    });
    const draftRule = makeRuleEditorDraft({
      mode: "FOOTER",
      position: "REMOVE",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
      matchRuleSource: {
        kind: "RULE_TREE",
        data: {
          type: "GROUP",
          negate: false,
          operator: "AND",
          children: [
            {
              type: "PATH",
              negate: false,
              matcher: "ANT",
              value: "/**",
            },
          ],
        },
      },
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([]) })
      .mockResolvedValueOnce({ data: snapshotOf([createdRule]) });
    ruleApi.add.mockResolvedValue({ data: createdRule });
    ruleApi.updateOrder.mockResolvedValue({
      data: { orders: { "rule-a": 1 }, version: 2 },
    });

    const store = useTransformerData(activeTab);
    await store.fetchAll();

    await store.addRule(draftRule);

    expect(ruleApi.add).toHaveBeenCalledWith(
      expect.objectContaining({
        mode: "FOOTER",
        position: "APPEND",
        snippetIds: ["snippet-a"],
      }),
    );
  });

  // why: 批量导入走的是另一条写路径，但语义不能分叉；
  // 同一份草稿导入后也必须保留非 selector 规则重新关联的 snippetIds。
  it("preserves snippetIds when importing non-selector rules with stale remove state", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      code: "<div>ok</div>",
    });
    const createdRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      mode: "FOOTER",
      position: "APPEND",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (createdRule as { matchRuleSource?: unknown }).matchRuleSource;
    const importRule = makeRuleEditorDraft({
      mode: "FOOTER",
      position: "REMOVE",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
      matchRuleSource: {
        kind: "RULE_TREE",
        data: {
          type: "GROUP",
          negate: false,
          operator: "AND",
          children: [
            {
              type: "PATH",
              negate: false,
              matcher: "ANT",
              value: "/**",
            },
          ],
        },
      },
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([]) })
      .mockResolvedValueOnce({ data: snapshotOf([createdRule]) });
    ruleApi.add.mockResolvedValue({ data: createdRule });
    ruleApi.updateOrder.mockResolvedValue({
      data: { orders: { "rule-a": 1 }, version: 2 },
    });

    const store = useTransformerData(activeTab);
    await store.fetchAll();

    await store.importRules([importRule], true);

    expect(ruleApi.add).toHaveBeenCalledWith(
      expect.objectContaining({
        enabled: true,
        mode: "FOOTER",
        position: "APPEND",
        snippetIds: ["snippet-a"],
      }),
    );
  });

  // why: 规则保存只应刷新规则上下文；不该再顺手把代码片段列表和代码片段顺序也整页回拉。
  it("refreshes only rules after saving a rule", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      name: "Rule A",
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      code: "<div>ok</div>",
    });

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) })
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) });
    ruleApi.update.mockResolvedValue({ data: savedRule });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    await store.saveRule();

    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(2);
    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(1);
  });

  // why: 规则保存后的常规刷新必须把最新 `rule-order` version 一起带回；
  // 否则用户下一次拖拽仍会拿旧 version 提交，跨管理员协作时就会平白撞上冲突。
  it("uses the refreshed rule order version for later reorders after saving", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const ruleA = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      name: "Rule A",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/a/**",
          },
        ],
      },
    });
    const ruleB = makeRuleEditorDraft({
      id: "rule-b",
      metadata: { name: "rule-b", version: 1 },
      name: "Rule B",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/b/**",
          },
        ],
      },
    });
    delete (ruleA as { matchRuleSource?: unknown }).matchRuleSource;
    delete (ruleB as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({
        data: snapshotOf([ruleA, ruleB], { "rule-a": 1, "rule-b": 2 }, 1),
      })
      .mockResolvedValueOnce({
        data: snapshotOf([ruleA, ruleB], { "rule-a": 1, "rule-b": 2 }, 9),
      });
    ruleApi.update.mockResolvedValue({ data: ruleA });
    ruleApi.updateOrder.mockResolvedValue({
      data: { orders: { "rule-b": 1, "rule-a": 2 }, version: 10 },
    });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    await store.saveRule();
    await store.reorderRule({
      sourceId: "rule-a",
      targetId: "rule-b",
      placement: "after",
    });

    expect(ruleApi.updateOrder).toHaveBeenCalledWith({ "rule-b": 1, "rule-a": 2 }, 9);
  });

  // why: 代码片段保存只应刷新代码片段上下文；规则列表和规则顺序不该因为一次 snippet 保存被整页重拉。
  it("refreshes only snippets after saving a snippet", async () => {
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      name: "Snippet A",
      code: "<div>ok</div>",
    });
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a" },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedSnippet]) })
      .mockResolvedValueOnce({ data: snapshotOf([savedSnippet]) });
    snippetApi.update.mockResolvedValue({ data: savedSnippet });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });

    const store = useTransformerData(ref<ActiveTab>("snippets"));
    await store.fetchAll();
    store.selectedSnippetId.value = "snippet-a";
    await nextTick();

    await store.saveSnippet();

    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(2);
    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(1);
  });

  // why: snippet 删除和 rule 引用清理是最终一致；当前端发现 rule draft 里的 snippet 已不存在时，
  // 应先把坏 id 剪掉，但空关联本身仍是合法草稿态，不应阻止规则继续保存。
  it("prunes missing snippet ids and saves the rule with an empty association set", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      snippetIds: ["snippet-missing"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) })
      .mockResolvedValueOnce({ data: snapshotOf([savedRule]) });
    ruleApi.update.mockResolvedValue({ data: { ...savedRule, snippetIds: [] } });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    expect(store.editRule.value?.snippetIds).toEqual([]);

    const saved = await store.saveRule();

    expect(saved).toBe(true);
    expect(ruleApi.update).toHaveBeenCalledWith(
      "rule-a",
      expect.objectContaining({
        snippetIds: [],
      }),
    );
  });

  // why: 删除一个资源后，页面上两侧列表和关联面板都可能受影响；删除路径应刷新整页资源快照，而不是只刷当前标签页。
  it("refreshes both snippet and rule lists after deleting a rule", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      name: "Rule A",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      code: "<div>ok</div>",
    });

    snippetApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedSnippet], { "snippet-a": 1 }, 1) })
      .mockResolvedValueOnce({ data: snapshotOf([savedSnippet], { "snippet-a": 1 }, 1) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({ data: snapshotOf([savedRule], { "rule-a": 1 }, 1) })
      .mockResolvedValueOnce({ data: snapshotOf([], { "rule-a": 1 }, 1) });
    ruleApi.delete.mockResolvedValue({});
    ruleApi.updateOrder.mockResolvedValue({ data: { orders: {}, version: 2 } });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    store.confirmDeleteRule();
    const deleteDialogConfig = vi.mocked(dialog.warning).mock.calls[0]?.[0];
    expect(deleteDialogConfig).toBeTruthy();

    await deleteDialogConfig?.onConfirm?.();

    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(2);
    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(2);
  });

  // why: 左侧排序保存失败时，应回到后端最新快照；右侧未保存的规则草稿不能被整页重载冲掉。
  it("keeps unsaved rule editor state when rule reorder persistence fails", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const ruleA = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a" },
      name: "Rule A",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/a/**",
          },
        ],
      },
    });
    const ruleB = makeRuleEditorDraft({
      id: "rule-b",
      metadata: { name: "rule-b" },
      name: "Rule B",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/b/**",
          },
        ],
      },
    });
    delete (ruleA as { matchRuleSource?: unknown }).matchRuleSource;
    delete (ruleB as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });
    ruleApi.getSnapshot
      .mockResolvedValueOnce({
        data: snapshotOf([ruleA, ruleB], { "rule-a": 10, "rule-b": 20 }, 1),
      })
      .mockResolvedValueOnce({
        data: snapshotOf([ruleA, ruleB], { "rule-a": 10, "rule-b": 20 }, 1),
      });
    ruleApi.updateOrder.mockRejectedValue(new Error("boom"));

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    store.editRule.value = {
      ...store.editRule.value!,
      name: "draft rule name",
    };
    store.editDirty.value = true;

    await store.reorderRule({
      sourceId: "rule-a",
      targetId: "rule-b",
      placement: "after",
    });

    expect(store.editRule.value?.name).toBe("draft rule name");
    expect(store.editDirty.value).toBe(true);
    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(2);
    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(1);
  });

  // why: 代码片段排序失败同理也应回到最新快照，不能把右侧未保存代码内容覆盖掉。
  it("keeps unsaved snippet editor state when snippet reorder persistence fails", async () => {
    const snippetA = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a" },
      name: "Snippet A",
      code: "<div>a</div>",
    });
    const snippetB = makeSnippetEditorDraft({
      id: "snippet-b",
      metadata: { name: "snippet-b" },
      name: "Snippet B",
      code: "<div>b</div>",
    });

    snippetApi.getSnapshot
      .mockResolvedValueOnce({
        data: snapshotOf([snippetA, snippetB], { "snippet-a": 10, "snippet-b": 20 }, 1),
      })
      .mockResolvedValueOnce({
        data: snapshotOf([snippetA, snippetB], { "snippet-a": 10, "snippet-b": 20 }, 1),
      });
    snippetApi.updateOrder.mockRejectedValue(new Error("boom"));
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([]) });

    const store = useTransformerData(ref<ActiveTab>("snippets"));
    await store.fetchAll();
    store.selectedSnippetId.value = "snippet-a";
    await nextTick();

    store.editSnippet.value = {
      ...store.editSnippet.value!,
      code: "<div>draft</div>",
    };
    store.editDirty.value = true;

    await store.reorderSnippet({
      sourceId: "snippet-a",
      targetId: "snippet-b",
      placement: "after",
    });

    expect(store.editSnippet.value?.code).toBe("<div>draft</div>");
    expect(store.editDirty.value).toBe(true);
    expect(snippetApi.getSnapshot).toHaveBeenCalledTimes(2);
    expect(ruleApi.getSnapshot).toHaveBeenCalledTimes(1);
  });

  // why: editor draft 必须由当前 tab 的单一活动会话承载；
  // inactive tab 最多只记住 selectedId，不能继续隐藏第二份草稿并共享 dirty 状态。
  it("keeps exactly one active editor session for the current tab", async () => {
    const activeTab = ref<ActiveTab>("snippets");
    const savedSnippet = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      name: "Snippet A",
      code: "<div>saved</div>",
    });
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      name: "Rule A",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedSnippet]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });

    const store = useTransformerData(activeTab);
    await store.fetchAll();

    store.selectedSnippetId.value = "snippet-a";
    await nextTick();
    store.editSnippet.value = {
      ...store.editSnippet.value!,
      name: "draft snippet",
    };
    store.editDirty.value = true;

    store.selectedRuleId.value = "rule-a";
    await nextTick();

    expect(store.editRule.value).toBeNull();
    expect(store.editSnippet.value?.name).toBe("draft snippet");

    activeTab.value = "rules";
    await nextTick();

    expect(store.editSnippet.value).toBeNull();
    expect(store.editRule.value?.id).toBe("rule-a");
    expect(store.editRule.value?.name).toBe("Rule A");
    expect(store.editDirty.value).toBe(false);

    activeTab.value = "snippets";
    await nextTick();

    expect(store.editSnippet.value?.id).toBe("snippet-a");
    expect(store.editSnippet.value?.name).toBe("Snippet A");
    expect(store.editDirty.value).toBe(false);
  });

  // why: 右侧关系面板在编辑规则时必须跟随当前活动 draft，
  // 否则中间编辑器和右侧关联列表会各自引用不同真源，用户会看到自相矛盾的界面。
  it("derives snippets-in-rule from the active rule draft", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const snippetA = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      name: "Snippet A",
      code: "<div>a</div>",
    });
    const snippetB = makeSnippetEditorDraft({
      id: "snippet-b",
      metadata: { name: "snippet-b", version: 1 },
      name: "Snippet B",
      code: "<div>b</div>",
    });
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      snippetIds: ["snippet-a"],
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([snippetA, snippetB]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    expect(store.snippetsInRule.value.map((snippet) => snippet.id)).toEqual(["snippet-a"]);

    store.editRule.value = {
      ...store.editRule.value!,
      snippetIds: ["snippet-b"],
    };
    store.editDirty.value = true;
    await nextTick();

    expect(store.snippetsInRule.value.map((snippet) => snippet.id)).toEqual(["snippet-b"]);
  });

  // why: `SELECTOR + REMOVE` 的有效语义是不再关联代码片段；
  // 右侧关系面板应反映“当前有效关系”，而不是继续展示被隐藏字段里的历史值。
  it("treats selector remove mode as no effective snippet relations in the relation panel", async () => {
    const activeTab = ref<ActiveTab>("rules");
    const snippetA = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      name: "Snippet A",
      code: "<div>a</div>",
    });
    const snippetB = makeSnippetEditorDraft({
      id: "snippet-b",
      metadata: { name: "snippet-b", version: 1 },
      name: "Snippet B",
      code: "<div>b</div>",
    });
    const savedRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      mode: "SELECTOR",
      position: "APPEND",
      snippetIds: ["snippet-a"],
      match: ".slot",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (savedRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([snippetA, snippetB]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([savedRule]) });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedRuleId.value = "rule-a";
    await nextTick();

    expect(store.snippetsInRule.value.map((snippet) => snippet.id)).toEqual(["snippet-a"]);

    store.editRule.value = {
      ...store.editRule.value!,
      mode: "SELECTOR",
      position: "REMOVE",
      snippetIds: ["snippet-b"],
    };
    store.editDirty.value = true;
    await nextTick();

    expect(store.snippetsInRule.value).toEqual([]);
  });

  // why: 片段侧“被多少规则引用”和规则侧“当前关联了哪些片段”必须共享同一套有效语义；
  // 否则历史残留的 REMOVE.snippetIds 会让两边面板同时说出互相矛盾的话。
  it("ignores stale selector remove snippetIds when listing rules using a snippet", async () => {
    const activeTab = ref<ActiveTab>("snippets");
    const snippetA = makeSnippetEditorDraft({
      id: "snippet-a",
      metadata: { name: "snippet-a", version: 1 },
      name: "Snippet A",
      code: "<div>a</div>",
    });
    const selectorRemoveRule = makeRuleEditorDraft({
      id: "rule-a",
      metadata: { name: "rule-a", version: 1 },
      mode: "SELECTOR",
      position: "REMOVE",
      snippetIds: ["snippet-a"],
      match: ".slot",
      matchRule: {
        type: "GROUP",
        negate: false,
        operator: "AND",
        children: [
          {
            type: "PATH",
            negate: false,
            matcher: "ANT",
            value: "/**",
          },
        ],
      },
    });
    delete (selectorRemoveRule as { matchRuleSource?: unknown }).matchRuleSource;

    snippetApi.getSnapshot.mockResolvedValue({ data: snapshotOf([snippetA]) });
    ruleApi.getSnapshot.mockResolvedValue({ data: snapshotOf([selectorRemoveRule]) });

    const store = useTransformerData(activeTab);
    await store.fetchAll();
    store.selectedSnippetId.value = "snippet-a";
    await nextTick();

    expect(store.rulesUsingSnippet.value).toEqual([]);
  });
});
