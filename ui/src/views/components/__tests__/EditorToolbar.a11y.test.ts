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
    VSpace: defineComponent({
      name: "VSpace",
      setup(_, { attrs, slots }) {
        return () => h("div", attrs, slots.default?.());
      },
    }),
  };
});

import EditorToolbar from "../EditorToolbar.vue";

describe("EditorToolbar accessibility", () => {
  // why: 窄宽度下会把 ID 从默认视觉流里隐藏，测试要锁住两个约束：
  // 读屏仍能拿到 ID，上键盘导航也能把标题区聚焦出来，而不是只剩鼠标悬浮可见。
  it("keeps the title shell focusable and preserves an accessible ID label", () => {
    const wrapper = mount(EditorToolbar, {
      props: {
        title: "编辑规则",
        idText: "rule-home-banner",
      },
    });

    const titleShell = wrapper.get('[tabindex="0"]');
    expect(titleShell.text()).toContain("编辑规则");
    expect(titleShell.find(".sr-only").text()).toBe("ID: rule-home-banner");

    const visibleId = titleShell.find('[aria-hidden="true"]');
    expect(visibleId.attributes("title")).toBe("ID: rule-home-banner");
    expect(visibleId.text()).toBe("ID: rule-home-banner");
  });
});
