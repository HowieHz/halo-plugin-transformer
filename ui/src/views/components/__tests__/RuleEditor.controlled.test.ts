// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { defineComponent, ref } from "vue";

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

import { makeMatchRuleGroup, makeRuleEditorDraft } from "@/types";

import RuleEditor from "../RuleEditor.vue";

const controlledRuleEditorStubs = {
  DragAutoScrollOverlay: { template: "<div />" },
  EditorFooter: { template: "<div />" },
  EditorToolbar: { template: "<div />" },
  ExportContentModal: { template: "<div />" },
  FieldUndoButton: { template: "<button type='button'>undo</button>" },
  RuleRuntimeOrderField: { template: "<div />" },
  ItemPicker: defineComponent({
    name: "ItemPickerStub",
    props: {
      selectedIds: {
        type: Array,
        required: true,
      },
    },
    emits: ["toggle"],
    template: `
      <div>
        <button type="button" data-test="toggle-snippet" @click="$emit('toggle', 'snippet-a')">
          toggle snippet
        </button>
        <span data-test="selected-count">{{ selectedIds.length }}</span>
      </div>
    `,
  }),
  MatchRuleEditor: defineComponent({
    name: "MatchRuleEditorStub",
    emits: ["change", "drag-state-change", "update:state"],
    setup(_, { emit }) {
      function updateMatchRule() {
        const nextMatchRule = makeMatchRuleGroup({ operator: "OR" });
        emit("update:state", {
          matchRule: nextMatchRule,
          matchRuleSource: {
            kind: "RULE_TREE",
            data: nextMatchRule,
          },
        });
      }

      return {
        updateMatchRule,
      };
    },
    template: `
      <button type="button" data-test="update-match-rule" @click="updateMatchRule">
        update match rule
      </button>
    `,
  }),
};

function mountControlledRuleEditor() {
  return mount(
    defineComponent({
      components: { RuleEditor },
      setup() {
        const rule = ref(
          makeRuleEditorDraft({
            id: "rule-a",
            match: "#banner",
            mode: "SELECTOR",
            name: "初始规则",
            snippetIds: [],
          }),
        );
        const fieldChangeCount = ref(0);

        function handleRuleUpdate(nextRule: typeof rule.value) {
          rule.value = nextRule;
        }

        function handleFieldChange() {
          fieldChangeCount.value += 1;
        }

        return {
          fieldChangeCount,
          handleFieldChange,
          handleRuleUpdate,
          rule,
        };
      },
      template: `
        <div>
          <RuleEditor
            :dirty="false"
            :rule="rule"
            :saving="false"
            :snippets="[{ id: 'snippet-a', name: '片段 A', enabled: true }]"
            @field-change="handleFieldChange"
            @update:rule="handleRuleUpdate"
          />
          <output data-test="rule-name">{{ rule?.name }}</output>
          <output data-test="rule-snippet-count">{{ rule?.snippetIds.length }}</output>
          <output data-test="rule-operator">{{ rule?.matchRule.operator }}</output>
          <output data-test="field-change-count">{{ fieldChangeCount }}</output>
        </div>
      `,
    }),
    {
      global: {
        stubs: controlledRuleEditorStubs,
      },
    },
  );
}

describe("RuleEditor controlled state", () => {
  // why: 父层 `editRule` 才是唯一权威草稿，子组件不应再偷偷保留整份规则副本；
  // 这里锁住受控编辑语义，确保输入、关联代码片段和匹配规则更新都通过父层回写继续工作。
  it("keeps text edits flowing through the parent-owned rule state", async () => {
    const wrapper = mountControlledRuleEditor();

    const nameInput = wrapper.get('input[placeholder="不填默认为 ID"]');
    await nameInput.setValue("更新后的规则");

    expect((nameInput.element as HTMLInputElement).value).toBe("更新后的规则");
    expect(wrapper.get('[data-test="rule-name"]').text()).toBe("更新后的规则");
    expect(wrapper.get('[data-test="field-change-count"]').text()).toBe("1");
  });

  it("keeps snippet selection controlled by the parent rule state", async () => {
    const wrapper = mountControlledRuleEditor();

    await wrapper.get('[data-test="toggle-snippet"]').trigger("click");

    expect(wrapper.get('[data-test="rule-snippet-count"]').text()).toBe("1");
    expect(wrapper.get('[data-test="selected-count"]').text()).toBe("1");
  });

  it("keeps match-rule updates controlled by the parent rule state", async () => {
    const wrapper = mountControlledRuleEditor();

    await wrapper.get('[data-test="update-match-rule"]').trigger("click");

    expect(wrapper.get('[data-test="rule-operator"]').text()).toBe("OR");
  });
});
