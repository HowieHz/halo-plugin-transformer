import { describe, expect, it } from "vitest";
import { computed, ref } from "vue";

import { makeRuleEditorDraft } from "@/types";

import { useRuleFormSemantics } from "../useRuleFormSemantics";

describe("useRuleFormSemantics", () => {
  // why: create / edit 共享同一份规则表单语义后，`REMOVE` 的字段可见性、
  // 空关联 warning 和 snippet 勾选行为就必须从同一个地方派生，避免两边各修一半后再次漂移。
  it("derives shared selector remove semantics from the same rule source", () => {
    const rule = ref(
      makeRuleEditorDraft({
        mode: "SELECTOR",
        position: "REMOVE",
        snippetIds: [],
      }),
    );

    const semantics = useRuleFormSemantics({
      rule,
    });

    expect(semantics.needsTarget.value).toBe(true);
    expect(semantics.needsSnippets.value).toBe(false);
    expect(semantics.needsWrapMarker.value).toBe(false);
    expect(semantics.emptySnippetAssociationWarning.value).toBeNull();
  });

  it("builds toggled snippet ids from the shared rule source", () => {
    const rule = ref(
      makeRuleEditorDraft({
        mode: "FOOTER",
        snippetIds: ["snippet-a"],
      }),
    );

    const semantics = useRuleFormSemantics({
      rule,
    });

    expect(semantics.buildToggledSnippetIds("snippet-a")).toEqual([]);
    expect(semantics.buildToggledSnippetIds("snippet-b")).toEqual(["snippet-a", "snippet-b"]);
  });

  it("can validate selector input against an explicit match draft", () => {
    const rule = ref(
      makeRuleEditorDraft({
        mode: "SELECTOR",
        match: "#saved",
      }),
    );
    const matchValue = ref("");

    const semantics = useRuleFormSemantics({
      rule,
      matchValue: computed(() => matchValue.value),
    });

    expect(semantics.matchFieldError.value).toBe("请填写匹配内容");

    matchValue.value = "#draft";

    expect(semantics.matchFieldError.value).toBeNull();
  });
});
