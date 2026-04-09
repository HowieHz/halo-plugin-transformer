import { describe, expect, it } from "vitest";

import { makePathMatchRule, makeRuleEditorDraft, type MatchRule } from "@/types";

import { validateRuleDraft } from "../ruleValidation";

function makeValidMatchRule(): MatchRule {
  return {
    type: "GROUP",
    negate: false,
    operator: "AND",
    children: [makePathMatchRule({ value: "/**" })],
  };
}

describe("validateRuleDraft", () => {
  // why: create / edit / import 共享同一份规则校验后，
  // 这里要锁住最基础的 selector 必填语义，避免某一条写路径再次漂移。
  it("requires selector match for selector mode", () => {
    const rule = makeRuleEditorDraft({
      mode: "SELECTOR",
      match: "   ",
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBe("请填写匹配内容");
  });

  // why: 非 REMOVE 规则需要明确关联代码片段；否则看起来可保存的规则会在运行时变成空操作。
  it("requires snippets for non-remove rules", () => {
    const rule = makeRuleEditorDraft({
      mode: "FOOTER",
      position: "REMOVE",
      snippetIds: [],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBe("请至少关联一个代码片段");
  });

  // why: `REMOVE` 只在 selector 语义下成立；切到非 selector 模式后，旧的 REMOVE 状态不应继续关闭 snippet 要求。
  it("does not let stale remove semantics bypass snippet requirements outside selector mode", () => {
    const rule = makeRuleEditorDraft({
      mode: "HEAD",
      position: "REMOVE",
      snippetIds: [],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBe("请至少关联一个代码片段");
  });

  // why: selector remove 会直接删除目标元素，本来就不应再要求关联代码片段。
  it("allows selector remove rules without snippets", () => {
    const rule = makeRuleEditorDraft({
      mode: "SELECTOR",
      position: "REMOVE",
      match: "#main",
      snippetIds: [],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBeNull();
  });
});
