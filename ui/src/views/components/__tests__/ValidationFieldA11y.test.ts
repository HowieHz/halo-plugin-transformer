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

import { makeRuleEditorDraft, makeSnippetEditorDraft } from "@/types";

import RuleEditor from "../RuleEditor.vue";
import RuleFormModal from "../RuleFormModal.vue";
import SnippetEditor from "../SnippetEditor.vue";
import SnippetFormModal from "../SnippetFormModal.vue";

const baseFormModalStub = {
  template: `
    <div>
      <slot name="actions" />
      <slot name="form" />
      <slot name="picker" />
    </div>
  `,
};

const editorStubs = {
  DragAutoScrollOverlay: { template: "<div />" },
  EditorFooter: { template: "<div />" },
  EditorToolbar: { template: "<div />" },
  ExportContentModal: { template: "<div />" },
  FieldUndoButton: { template: "<button type='button'>undo</button>" },
  ItemPicker: { template: "<div />" },
  MatchRuleEditor: { template: "<div />" },
  RuleRuntimeOrderField: { template: "<div />" },
};

describe("Validation field accessibility", () => {
  // why: 代码内容错误不该只是“页面上出现一条红字”；
  // 文本域本身也必须通过 `aria-describedby` 指向错误消息，读屏才知道这条错误属于当前输入。
  it("associates snippet form code errors with the textarea", () => {
    const wrapper = mount(SnippetFormModal, {
      props: {
        saving: false,
      },
      global: {
        stubs: {
          BaseFormModal: baseFormModalStub,
          EnabledSwitch: { template: "<div />" },
          ImportSourceModal: { template: "<div />" },
        },
      },
    });

    const textarea = wrapper.get("textarea");
    const alert = wrapper.get('[role="alert"]');

    expect(textarea.attributes("aria-invalid")).toBe("true");
    expect(textarea.attributes("aria-describedby")).toBe(alert.attributes("id"));
    expect(textarea.attributes("required")).toBeDefined();
  });

  it("associates rule form selector errors with the input", async () => {
    const wrapper = mount(RuleFormModal, {
      props: {
        saving: false,
        snippets: [],
      },
      global: {
        stubs: {
          BaseFormModal: baseFormModalStub,
          EnabledSwitch: { template: "<div />" },
          ImportSourceModal: { template: "<div />" },
          ItemPicker: { template: "<div />" },
          MatchRuleEditor: { template: "<div />" },
          RuleRuntimeOrderField: { template: "<div />" },
        },
      },
    });

    await wrapper.get("select").setValue("SELECTOR");

    const input = wrapper.get('input[aria-invalid="true"]');
    const alert = wrapper.get('[role="alert"]');

    expect(wrapper.get("select").attributes("required")).toBeDefined();
    expect(input.attributes("aria-describedby")).toBe(alert.attributes("id"));
    expect(input.attributes("required")).toBeDefined();
  });

  it("associates snippet editor code errors with the textarea", () => {
    const wrapper = mount(SnippetEditor, {
      props: {
        dirty: false,
        saving: false,
        snippet: makeSnippetEditorDraft({ code: "" }),
      },
      global: {
        stubs: editorStubs,
      },
    });

    const textarea = wrapper.get("textarea");
    const alert = wrapper.get('[role="alert"]');

    expect(textarea.attributes("aria-invalid")).toBe("true");
    expect(textarea.attributes("aria-describedby")).toBe(alert.attributes("id"));
    expect(textarea.attributes("required")).toBeDefined();
  });

  it("associates rule editor selector errors with the input", () => {
    const wrapper = mount(RuleEditor, {
      props: {
        dirty: false,
        rule: makeRuleEditorDraft({ match: "", mode: "SELECTOR" }),
        saving: false,
        snippets: [],
      },
      global: {
        stubs: editorStubs,
      },
    });

    const input = wrapper.get('input[aria-invalid="true"]');
    const alert = wrapper.get('[role="alert"]');

    expect(input.attributes("aria-describedby")).toBe(alert.attributes("id"));
    expect(wrapper.get("select").attributes("required")).toBeDefined();
    expect(input.attributes("required")).toBeDefined();
  });

  // why: 运行顺序和匹配规则都是复合控件，不该再伪装成一个 `label for=<div>` 的原生输入；
  // 外层字段标签必须改为给 group 命名，这样读屏和点击语义才不会错位。
  it("labels composite rule fields as named groups", () => {
    const wrapper = mount(RuleFormModal, {
      props: {
        saving: false,
        snippets: [],
      },
      global: {
        stubs: {
          BaseFormModal: baseFormModalStub,
          EnabledSwitch: { template: "<div />" },
          ImportSourceModal: { template: "<div />" },
          ItemPicker: { template: "<div />" },
          MatchRuleEditor: { template: "<div>match-rule</div>" },
          RuleRuntimeOrderField: { template: "<div>runtime-order</div>" },
        },
      },
    });

    const groups = wrapper.findAll('[role="group"][aria-labelledby]');
    expect(groups).toHaveLength(2);
    expect(groups[1].attributes("aria-required")).toBe("true");
  });
});
