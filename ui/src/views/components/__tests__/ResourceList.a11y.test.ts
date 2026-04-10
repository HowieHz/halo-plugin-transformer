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
  // why: 批量模式允许“点整行即可勾选”作为命中区便利，
  // 但真正的已选语义必须只由 checkbox 暴露，不能再留下第二个可聚焦 toggle 控件。
  it("keeps the checkbox as the only bulk selection control", async () => {
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

    const list = wrapper.get("ul");
    expect(list.attributes("aria-label")).toBe("代码片段列表");
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false);

    expect(wrapper.find('button[type="button"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("页头脚本");

    const checkboxes = wrapper.findAll('input[type="checkbox"]');
    expect(checkboxes[0].attributes("aria-label")).toBe("全选当前列表项");
    expect(checkboxes[1].attributes("aria-label")).toBe("选择 页头脚本");

    await wrapper.get("li:last-child").trigger("click");
    expect(wrapper.emitted("toggle-bulk-item")).toEqual([["snippet-a"]]);
    expect(wrapper.emitted("select")).toBeUndefined();
  });

  // why: 非批量模式下左侧资源列表是 master-detail 导航；
  // 当前项应由主操作按钮显式暴露出来，而不是继续滥用 option/selected 语义。
  it("marks the current item on the primary row action in single-select mode", () => {
    const wrapper = mount(ResourceList, {
      props: {
        items: [
          { id: "snippet-a", name: "页头脚本", enabled: true },
          { id: "snippet-b", name: "页尾脚本", enabled: false },
        ],
        listLabel: "代码片段列表",
        selectedId: "snippet-b",
      },
    });

    const currentButton = wrapper.get('button[aria-current="true"]');
    expect(currentButton.text()).toContain("页尾脚本");
    expect(wrapper.find('[role="option"]').exists()).toBe(false);
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
