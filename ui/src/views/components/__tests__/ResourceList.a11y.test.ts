// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";

import ResourceList from "../ResourceList.vue";

describe("ResourceList accessibility", () => {
  // why: 批量模式把“整行可选”和“复选框可选”叠在一起后，
  // 屏幕阅读器最容易先失去复选框名称，所以这里锁住面板语义和选择标签。
  it("exposes tab panel semantics and bulk checkbox labels", () => {
    const wrapper = mount(ResourceList, {
      props: {
        items: [
          {
            id: "snippet-a",
            name: "页头脚本",
            enabled: true,
          },
        ],
        bulkMode: true,
        bulkSelectedIds: [],
        listLabel: "代码片段列表",
        panelId: "transformer-panel-snippets",
        tabLabelledby: "transformer-tab-snippets",
      },
    });

    const panel = wrapper.get('[role="tabpanel"]');
    expect(panel.attributes("id")).toBe("transformer-panel-snippets");
    expect(panel.attributes("aria-labelledby")).toBe("transformer-tab-snippets");

    const listbox = wrapper.get('[role="listbox"]');
    expect(listbox.attributes("aria-multiselectable")).toBe("true");

    const checkboxes = wrapper.findAll('input[type="checkbox"]');
    expect(checkboxes[0].attributes("aria-label")).toBe("全选当前列表项");
    expect(checkboxes[1].attributes("aria-label")).toBe("选择 页头脚本");
  });
});
