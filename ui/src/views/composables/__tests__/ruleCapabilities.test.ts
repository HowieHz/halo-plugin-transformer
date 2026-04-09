import { describe, expect, it } from "vitest";

import { makePathMatchRule, makeRuleEditorDraft, type MatchRule } from "@/types";

import { buildRuleWritePayload } from "../ruleDraft";

function makeValidMatchRule(): MatchRule {
  return {
    type: "GROUP",
    negate: false,
    operator: "AND",
    children: [makePathMatchRule({ value: "/**" })],
  };
}

describe("buildRuleWritePayload", () => {
  // why: 切出 selector 模式后，旧的 REMOVE 状态不应继续清空 snippetIds / wrapMarker；
  // 否则历史 UI 状态会偷偷污染 HEAD/FOOTER 持久化结果。
  it("keeps snippets and wrapMarker when stale remove state exists outside selector mode", () => {
    const rule = makeRuleEditorDraft({
      mode: "FOOTER",
      position: "REMOVE",
      wrapMarker: true,
      snippetIds: ["snippet-a"],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    const payload = buildRuleWritePayload(rule);

    expect(payload).toMatchObject({
      mode: "FOOTER",
      match: "",
      position: "APPEND",
      wrapMarker: true,
      snippetIds: ["snippet-a"],
    });
  });

  // why: selector remove 仍然要收敛成“无 snippet、无 wrap marker”的最小执行语义，
  // 避免 DOM 删除规则继续携带无意义字段。
  it("clears snippets and wrapMarker for selector remove rules", () => {
    const rule = makeRuleEditorDraft({
      mode: "SELECTOR",
      position: "REMOVE",
      match: "#main",
      wrapMarker: true,
      snippetIds: ["snippet-a"],
      matchRule: makeValidMatchRule(),
      matchRuleSource: { kind: "RULE_TREE", data: makeValidMatchRule() },
    });

    const payload = buildRuleWritePayload(rule);

    expect(payload).toMatchObject({
      mode: "SELECTOR",
      match: "#main",
      position: "REMOVE",
      wrapMarker: false,
      snippetIds: [],
    });
  });
});
