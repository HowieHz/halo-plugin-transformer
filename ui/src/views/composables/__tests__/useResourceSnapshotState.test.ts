import { Toast } from "@halo-dev/components";
import { describe, expect, it, vi } from "vitest";

import type { ItemList, OrderedItemList } from "@/types";

import { useResourceSnapshotState } from "../useResourceSnapshotState";

vi.mock("@halo-dev/components", () => ({
  Toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function listOf<T>(items: T[]): ItemList<T> {
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
  };
}

function snapshotOf<T>(
  items: T[],
  orders: Record<string, number>,
  orderVersion: number | null,
): OrderedItemList<T> {
  return {
    ...listOf(items),
    orders,
    orderVersion,
  };
}

describe("useResourceSnapshotState", () => {
  // why: 创建/删除后会走一次“补写显式顺序”；如果这次保存失败，
  // 左侧列表必须回到服务端权威顺序，而不是停留在本地乐观态直到整页刷新。
  it("reloads authoritative orders when saveOrderMap fails", async () => {
    const api = {
      getSnapshot: vi.fn().mockResolvedValue({
        data: snapshotOf(
          [
            { id: "rule-a", name: "Rule A" },
            { id: "rule-b", name: "Rule B" },
            { id: "rule-c", name: "Rule C" },
          ],
          { "rule-c": 1, "rule-a": 2, "rule-b": 3 },
          3,
        ),
      }),
      updateOrder: vi.fn().mockRejectedValue(new Error("boom")),
    };

    const state = useResourceSnapshotState({
      api,
      resourceLabel: "转换规则",
    });
    state.applySnapshot(
      snapshotOf(
        [
          { id: "rule-a", name: "Rule A" },
          { id: "rule-b", name: "Rule B" },
        ],
        { "rule-a": 1, "rule-b": 2 },
        2,
      ),
    );

    const result = await state.saveOrderMap([
      { id: "rule-b", name: "Rule B" },
      { id: "rule-a", name: "Rule A" },
    ]);

    expect(result).toBe("转换规则顺序保存失败");
    expect(api.getSnapshot).toHaveBeenCalledTimes(1);
    expect(state.snapshot.value.orders).toEqual({ "rule-c": 1, "rule-a": 2, "rule-b": 3 });
    expect(state.snapshot.value.orderVersion).toBe(3);
    expect(state.items.value.map((item) => item.id)).toEqual(["rule-c", "rule-a", "rule-b"]);
  });

  // why: 拖拽重排和创建/删除后的补写，本质上是同一条排序持久化语义；
  // 拖拽失败时也必须回到服务端权威顺序，不能和 saveOrderMap 漂移成两套恢复逻辑。
  it("reloads authoritative orders when reorder persistence fails", async () => {
    const api = {
      getSnapshot: vi.fn().mockResolvedValue({
        data: snapshotOf(
          [
            { id: "rule-a", name: "Rule A" },
            { id: "rule-b", name: "Rule B" },
          ],
          { "rule-a": 1, "rule-b": 2 },
          3,
        ),
      }),
      updateOrder: vi.fn().mockRejectedValue(new Error("boom")),
    };

    const state = useResourceSnapshotState({
      api,
      resourceLabel: "转换规则",
    });
    state.applySnapshot(
      snapshotOf(
        [
          { id: "rule-a", name: "Rule A" },
          { id: "rule-b", name: "Rule B" },
        ],
        { "rule-a": 1, "rule-b": 2 },
        2,
      ),
    );

    await state.reorder({
      sourceId: "rule-b",
      targetId: "rule-a",
      placement: "before",
    });

    expect(api.getSnapshot).toHaveBeenCalledTimes(1);
    expect(state.snapshot.value.orders).toEqual({ "rule-a": 1, "rule-b": 2 });
    expect(state.snapshot.value.orderVersion).toBe(3);
    expect(Toast.error).toHaveBeenCalledWith("更新顺序失败");
  });
});
