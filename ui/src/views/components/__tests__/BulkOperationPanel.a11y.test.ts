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

import BulkOperationPanel from "../BulkOperationPanel.vue";

describe("BulkOperationPanel accessibility", () => {
  it("announces the current bulk selection count through a live region", () => {
    const wrapper = mount(BulkOperationPanel, {
      props: {
        canDisable: true,
        canEnable: true,
        processing: false,
        ruleCompatibilityStatus: "idle",
        ruleCompatibilityStep: null,
        selectedCount: 3,
        tab: "snippets",
      },
      global: {
        stubs: {
          EditorToolbar: {
            template: "<div><slot name='actions' /></div>",
          },
        },
      },
    });

    const liveRegion = wrapper.get('[aria-live="polite"]');
    expect(liveRegion.text()).toBe("当前已选择 3 个代码片段");
  });
});
