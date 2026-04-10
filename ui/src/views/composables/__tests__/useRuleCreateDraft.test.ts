import { describe, expect, it } from "vitest";

import { useRuleCreateDraft } from "../useRuleCreateDraft";

describe("useRuleCreateDraft", () => {
  // why: 规则新建弹窗的提交快照必须和活动 draft 解耦；
  // 否则父层在保存前后继续修改表单时，会把同一对象引用带进提交链路。
  it("returns a detached submit snapshot", () => {
    const createDraft = useRuleCreateDraft();
    createDraft.draft.value.name = "Rule A";

    const payload = createDraft.getSubmitPayload();
    payload.rule.name = "Mutated";

    expect(createDraft.draft.value.name).toBe("Rule A");
  });
});
