// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import { defineComponent, nextTick, ref } from "vue";

import ResourceList from "../ResourceList.vue";

type ResourceItem = {
  id: string;
  name: string;
  enabled: boolean;
};

function reorderItems(
  items: ResourceItem[],
  payload: { sourceId: string; targetId: string; placement: "before" | "after" },
) {
  const sourceIndex = items.findIndex((item) => item.id === payload.sourceId);
  const targetIndex = items.findIndex((item) => item.id === payload.targetId);
  if (sourceIndex === -1 || targetIndex === -1 || sourceIndex === targetIndex) {
    return items;
  }

  const nextItems = [...items];
  const [movedItem] = nextItems.splice(sourceIndex, 1);
  if (!movedItem) {
    return items;
  }

  const insertionBaseIndex = nextItems.findIndex((item) => item.id === payload.targetId);
  if (insertionBaseIndex === -1) {
    return items;
  }

  const insertionIndex =
    payload.placement === "before" ? insertionBaseIndex : insertionBaseIndex + 1;
  nextItems.splice(insertionIndex, 0, movedItem);
  return nextItems;
}

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

  // why: 键盘用户调整顺序时，最自然的连续动作是反复按上下方向键；
  // 重排后继续把焦点留在同一项的拖动句柄上，才能避免每次调整都重新找回操作点。
  it("keeps focus on the moved item's reorder handle after keyboard reordering", async () => {
    const wrapper = mount(
      defineComponent({
        components: { ResourceList },
        setup() {
          const items = ref<ResourceItem[]>([
            { id: "rule-a", name: "规则 A", enabled: true },
            { id: "rule-b", name: "规则 B", enabled: true },
            { id: "rule-c", name: "规则 C", enabled: true },
          ]);

          function handleReorder(payload: {
            sourceId: string;
            targetId: string;
            placement: "before" | "after";
          }) {
            items.value = reorderItems(items.value, payload);
          }

          return {
            handleReorder,
            items,
          };
        },
        template:
          '<ResourceList :items="items" reorderable @reorder="handleReorder" list-label="规则列表" />',
      }),
      {
        attachTo: document.body,
      },
    );

    const getReorderButtons = () =>
      wrapper.findAll('button[aria-keyshortcuts="ArrowUp ArrowDown"]');

    const movedHandle = getReorderButtons()[0];
    const movedHandleElement = movedHandle.element as HTMLButtonElement;
    movedHandleElement.focus();

    await movedHandle.trigger("keydown", { key: "ArrowDown" });
    await nextTick();

    expect(getReorderButtons().map((button) => button.attributes("aria-label"))).toEqual([
      "拖动排序：规则 B",
      "拖动排序：规则 A",
      "拖动排序：规则 C",
    ]);
    expect(document.activeElement).toBe(getReorderButtons()[1].element);

    wrapper.unmount();
  });
});
