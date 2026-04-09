import { describe, expect, it } from "vitest";

import { makeRuleEditorDraft } from "@/types";

import { validateRuleDraft } from "../ruleValidation";

describe("validateRuleDraft", () => {
  // why: create / edit / import 共享同一份规则校验后，
  // 这里要锁住最基础的 selector 必填语义，避免某一条写路径再次漂移。
  it("requires selector match for selector mode", () => {
    const rule = makeRuleEditorDraft({
      mode: "SELECTOR",
      match: "   ",
    });

    expect(validateRuleDraft(rule)).toBe("请填写匹配内容");
  });
});
