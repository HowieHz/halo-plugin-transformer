// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { defineComponent, nextTick, ref } from "vue";

vi.mock("@halo-dev/components", async () => {
  const { defineComponent, h } = await import("vue");

  return {
    Dialog: {
      warning: vi.fn(),
    },
    VButton: defineComponent({
      name: "VButton",
      setup(_, { attrs, slots }) {
        return () => h("button", attrs, slots.default?.());
      },
    }),
  };
});

import { makeMatchRuleGroup } from "@/types";

import MatchRuleEditor from "../MatchRuleEditor.vue";

describe("MatchRuleEditor accessibility", () => {
  // why: 既然模式切换声明成了 tab，就必须暴露正确的 `aria-selected`
  // 并支持方向键切换，否则读屏和键盘用户拿到的是一组语义不完整的“伪 tab”。
  it("uses tab semantics and supports keyboard mode switching", async () => {
    const wrapper = mount(
      defineComponent({
        components: { MatchRuleEditor },
        setup() {
          const matchRule = ref(makeMatchRuleGroup());
          const source = ref<{ kind: "RULE_TREE" | "JSON_DRAFT"; data: unknown }>({
            kind: "RULE_TREE",
            data: makeMatchRuleGroup(),
          });

          function handleStateUpdate(
            patch: Partial<{
              matchRule: typeof matchRule.value;
              matchRuleSource: typeof source.value;
            }>,
          ) {
            if (patch.matchRule) {
              matchRule.value = patch.matchRule;
            }
            if (patch.matchRuleSource) {
              source.value = patch.matchRuleSource;
            }
          }

          return {
            handleStateUpdate,
            matchRule,
            source,
          };
        },
        template:
          '<MatchRuleEditor :model-value="matchRule" :source="source" @update:state="handleStateUpdate" />',
      }),
      {
        attachTo: document.body,
        global: {
          stubs: {
            MatchRuleNodeEditor: {
              template: '<div role="tabpanel">simple panel</div>',
            },
          },
        },
      },
    );

    const tabs = wrapper.findAll('[role="tab"]');
    expect(tabs).toHaveLength(2);
    expect(tabs[0].attributes("aria-selected")).toBe("true");
    expect(tabs[1].attributes("aria-selected")).toBe("false");
    expect(tabs[0].attributes("aria-pressed")).toBeUndefined();

    await tabs[0].trigger("keydown", { key: "ArrowRight" });
    await nextTick();

    const nextTabs = wrapper.findAll('[role="tab"]');
    expect(nextTabs[0].attributes("aria-selected")).toBe("false");
    expect(nextTabs[1].attributes("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(nextTabs[1].element);

    wrapper.unmount();
  });
});
