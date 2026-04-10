// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";

vi.mock("@halo-dev/components", async () => {
  const { defineComponent, h } = await import("vue");

  return {
    VButton: defineComponent({
      name: "VButton",
      setup(_, { attrs, slots }) {
        return () => h("button", attrs, slots.default?.());
      },
    }),
  };
});

import { makePathMatchRule } from "@/types";

import MatchRuleNodeEditor from "../MatchRuleNodeEditor.vue";

describe("MatchRuleNodeEditor accessibility", () => {
  // why: 匹配规则树里的字段错误如果不绑定回具体控件，
  // 读屏只能听到“有错误”，却不知道是匹配方式还是匹配值出了问题。
  it("associates field errors with the matching controls", () => {
    const wrapper = mount(MatchRuleNodeEditor, {
      props: {
        modelValue: makePathMatchRule({ matcher: "ANT", value: "" }),
        validationErrors: [
          { path: "$.matcher", message: "匹配方式不合法" },
          { path: "$.value", message: "匹配值不能为空" },
        ],
      },
    });

    const alerts = wrapper.findAll('[role="alert"] p');
    const matcherAlert = alerts.find((alert) => alert.text() === "匹配方式不合法");
    const valueAlert = alerts.find((alert) => alert.text() === "匹配值不能为空");

    const selects = wrapper.findAll("select");
    const matcherSelect = selects[1];
    const valueInput = wrapper.get('input[aria-label="匹配值"]');

    expect(matcherAlert?.attributes("id")).toBeTruthy();
    expect(valueAlert?.attributes("id")).toBeTruthy();
    expect(matcherSelect.attributes("aria-describedby")).toContain(
      matcherAlert?.attributes("id") ?? "",
    );
    expect(valueInput.attributes("aria-describedby")).toContain(valueAlert?.attributes("id") ?? "");
  });

  // why: 当前树节点拖拽还不支持键盘重排；
  // 既然做不到，就不能把拖动句柄继续暴露成可聚焦按钮误导键盘用户。
  it("does not expose unsupported drag handles as focusable buttons", () => {
    const wrapper = mount(MatchRuleNodeEditor, {
      props: {
        modelValue: makePathMatchRule(),
      },
    });

    expect(wrapper.find('button[aria-label="拖动当前匹配条件"]').exists()).toBe(false);
  });
});
