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

  // why: 空 snippet 关联是合法草稿态；用户可以先建规则，后续再补建代码片段并关联。
  it("allows empty snippet associations for non-remove rules", () => {
    const rule = makeRuleEditorDraft({
      mode: "FOOTER",
      position: "REMOVE",
      snippetIds: [],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBeNull();
  });

  // why: `REMOVE` 只在 selector 语义下成立；切到非 selector 模式后，旧的 REMOVE 状态也不应触发额外校验。
  it("does not add extra validation when stale remove state exists outside selector mode", () => {
    const rule = makeRuleEditorDraft({
      mode: "HEAD",
      position: "REMOVE",
      snippetIds: [],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    expect(validateRuleDraft(rule)).toBeNull();
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
