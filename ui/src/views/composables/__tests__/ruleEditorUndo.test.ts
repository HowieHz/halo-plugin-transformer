import { describe, expect, it } from "vitest";

import { makeRuleEditorDraft } from "@/types";

import { buildRuleUndoBaselineSnapshot, resolveRuleUndoFieldCurrentValue } from "../ruleEditorUndo";

describe("ruleEditorUndo", () => {
  // why: 输出注释标记和插入位置是两个独立字段；
  // 这里只锁住撤销基线，避免后续重构再把它们重新绑成一个复合快照。
  it("tracks position independently from wrapMarker", () => {
    const rule = makeRuleEditorDraft({
      mode: "SELECTOR",
      position: "APPEND",
      wrapMarker: true,
    });

    const baseline = buildRuleUndoBaselineSnapshot(rule, []);
    const nextRule = {
      ...rule,
      wrapMarker: false,
    };

    expect(baseline.position).toBe("APPEND");
    expect(resolveRuleUndoFieldCurrentValue("position", nextRule, [])).toBe("APPEND");
    expect(resolveRuleUndoFieldCurrentValue("wrapMarker", nextRule, [])).toBe(false);
  });
});
