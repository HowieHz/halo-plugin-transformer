import { describe, expect, it } from "vitest";

import { makePathMatchRule, makeRuleEditorDraft, type MatchRule } from "@/types";

import { getEmptySnippetAssociationWarning } from "../ruleCapabilities";
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

describe("getEmptySnippetAssociationWarning", () => {
  // why: 空关联是合法草稿态，但 UI 仍应明确提醒“当前不会输出内容”，避免 no-op 配置看起来像已完整生效。
  it("returns a warning for empty non-remove associations", () => {
    const warning = getEmptySnippetAssociationWarning(
      makeRuleEditorDraft({
        mode: "FOOTER",
        snippetIds: [],
      }),
    );

    expect(warning).toContain("保存后不会输出内容");
  });

  // why: selector remove 本来就不消费代码片段；这里不应额外提示“缺少关联”，避免把合法删除规则误报成不完整。
  it("does not warn for selector remove rules", () => {
    const warning = getEmptySnippetAssociationWarning(
      makeRuleEditorDraft({
        mode: "SELECTOR",
        position: "REMOVE",
        snippetIds: [],
      }),
    );

    expect(warning).toBeNull();
  });

  // why: 一旦已有 snippet 关联，warning 就应该消失，避免提示和当前配置相互矛盾。
  it("does not warn when snippets are already linked", () => {
    const warning = getEmptySnippetAssociationWarning(
      makeRuleEditorDraft({
        mode: "HEAD",
        snippetIds: ["snippet-a"],
      }),
    );

    expect(warning).toBeNull();
  });
});
