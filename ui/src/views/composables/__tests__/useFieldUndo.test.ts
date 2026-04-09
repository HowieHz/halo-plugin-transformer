import { describe, expect, it } from "vitest";

import { useFieldUndo } from "../useFieldUndo";

describe("useFieldUndo", () => {
  // why: 当前实现只承诺“字段级逐步撤销”，
  // 这里锁住同一字段的回退语义，避免后续重构误伤按钮行为。
  it("undoes one field step by step", () => {
    const undo = useFieldUndo();
    undo.resetBaseline({
      name: "rule-a",
    });

    undo.trackChange("name", "rule-a", "rule-b");
    undo.trackChange("name", "rule-b", "rule-c");

    expect(undo.undo("name", "rule-c")).toBe("rule-b");
    expect(undo.undo("name", "rule-b")).toBe("rule-a");
  });

  // why: 长按按钮依赖 reset() 直接回到 baseline，
  // 这里锁住“清空字段历史并恢复初始值”的职责边界。
  it("resets a field back to baseline", () => {
    const undo = useFieldUndo();
    undo.resetBaseline({
      description: "initial",
    });

    undo.trackChange("description", "initial", "changed");

    expect(undo.reset("description")).toBe("initial");
    expect(undo.undo("description", "initial")).toBeUndefined();
  });
});
