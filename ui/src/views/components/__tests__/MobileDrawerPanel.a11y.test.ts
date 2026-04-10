// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import { nextTick } from "vue";

import MobileDrawerPanel from "../MobileDrawerPanel.vue";

describe("MobileDrawerPanel accessibility", () => {
  // why: 窄宽度抽屉不是普通侧栏，而是覆盖主区域的临时面板；
  // 打开后必须给出 dialog 语义、提供可见关闭按钮、把焦点限制在抽屉内，并允许 Esc 关闭。
  it("exposes compact drawers as dialogs, traps focus, and closes on Escape", async () => {
    const wrapper = mount(MobileDrawerPanel, {
      attachTo: document.body,
      props: {
        compact: true,
        descriptionId: "drawer-desc",
        drawerId: "drawer-left",
        open: true,
        side: "left",
        title: "选择列表",
        titleId: "drawer-title",
      },
      slots: {
        default: `
          <button data-test="first-action" type="button">首个操作</button>
          <button data-test="last-action" type="button">第二个操作</button>
        `,
      },
    });

    await nextTick();

    const dialog = wrapper.get('[role="dialog"]');
    const closeButton = wrapper.get('button[aria-label="关闭选择列表侧边栏"]');
    const lastAction = wrapper.get('[data-test="last-action"]');

    expect(dialog.attributes("aria-modal")).toBe("true");
    expect(dialog.attributes("aria-labelledby")).toBe("drawer-title");
    expect(dialog.attributes("aria-describedby")).toBe("drawer-desc");
    expect(closeButton.isVisible()).toBe(true);
    expect(document.activeElement).toBe(closeButton.element);

    (lastAction.element as HTMLButtonElement).focus();
    await dialog.trigger("keydown", { key: "Tab" });
    expect(document.activeElement).toBe(closeButton.element);

    (closeButton.element as HTMLButtonElement).focus();
    await dialog.trigger("keydown", { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(lastAction.element);

    await closeButton.trigger("click");
    expect(wrapper.emitted("close")).toHaveLength(1);

    await dialog.trigger("keydown", { key: "Escape" });
    expect(wrapper.emitted("close")).toHaveLength(2);
  });

  it("marks closed compact drawers as inert background content", () => {
    const wrapper = mount(MobileDrawerPanel, {
      props: {
        compact: true,
        descriptionId: "drawer-desc",
        drawerId: "drawer-left",
        open: false,
        side: "left",
        title: "选择列表",
        titleId: "drawer-title",
      },
      slots: {
        default: '<button type="button">不可聚焦</button>',
      },
    });

    expect(wrapper.attributes("aria-hidden")).toBe("true");
    expect(wrapper.attributes("inert")).toBeDefined();
  });

  it("keeps desktop drawers out of dialog mode", () => {
    const wrapper = mount(MobileDrawerPanel, {
      props: {
        compact: false,
        descriptionId: "drawer-desc",
        drawerId: "drawer-right",
        open: false,
        side: "right",
        title: "关联关系",
        titleId: "drawer-title",
      },
    });

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    expect(wrapper.attributes("aria-hidden")).toBeUndefined();
    expect(wrapper.attributes("inert")).toBeUndefined();
  });
});
