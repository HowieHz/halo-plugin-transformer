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
    VModal: defineComponent({
      name: "VModal",
      setup(_, { attrs, slots }) {
        return () => h("div", { ...attrs, role: "dialog" }, [slots.default?.(), slots.footer?.()]);
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

import BaseFormModal from "../BaseFormModal.vue";

describe("BaseFormModal accessibility", () => {
  it("keeps an accessible dialog name when the default title is visually hidden", () => {
    const wrapper = mount(BaseFormModal, {
      props: {
        hideDefaultTitle: true,
        saving: false,
        showPicker: false,
        title: "新建代码片段",
      },
      slots: {
        form: "<div>form body</div>",
      },
    });

    const dialog = wrapper.get('[role="dialog"]');
    expect(dialog.attributes("aria-label")).toBe("新建代码片段");
    expect(dialog.text()).toContain("新建代码片段");
  });
});
