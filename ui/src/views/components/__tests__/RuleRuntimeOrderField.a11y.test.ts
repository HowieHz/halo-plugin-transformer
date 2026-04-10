// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import RuleRuntimeOrderField from "../RuleRuntimeOrderField.vue";

describe("RuleRuntimeOrderField accessibility", () => {
  // why: 运行顺序字段同时有滑条、快捷按钮和精确输入；
  // 这里锁住它们的可读名称与说明关系，避免后续重构后只剩视觉提示。
  it("keeps accessible names and descriptions for slider and manual mode", async () => {
    const wrapper = mount(RuleRuntimeOrderField, {
      props: {
        modelValue: 2147483645,
      },
    });

    const slider = wrapper.get('input[type="range"]');
    expect(slider.attributes("aria-label")).toBe("运行顺序滑条");
    expect(slider.attributes("aria-describedby")).toBeTruthy();

    const presetButton = wrapper.get('button[aria-label="将运行顺序设为 最低"]');
    expect(presetButton.attributes("aria-pressed")).toBe("true");

    const modeToggle = wrapper.get('button[aria-label="切换为精确输入运行顺序"]');
    expect(modeToggle.attributes("aria-pressed")).toBe("false");

    await modeToggle.trigger("click");

    const manualInput = wrapper.get('input[type="number"]');
    expect(manualInput.attributes("aria-label")).toBe("运行顺序精确值");
    expect(manualInput.attributes("aria-describedby")).toBeTruthy();
    expect(
      wrapper.get('button[aria-label="切换为滑条设置运行顺序"]').attributes("aria-pressed"),
    ).toBe("true");
  });
});
