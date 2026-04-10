import { describe, expect, it } from "vitest";

import {
  COMPACT_LAYOUT_BREAKPOINT_PX,
  COMPACT_LAYOUT_MEDIA_QUERY,
  createMobileDrawerController,
} from "../useMobileDrawerState";

describe("createMobileDrawerController", () => {
  // why: CSS 和 JS 共用同一条窄宽度断点，避免抽屉状态和媒体查询进入不同布局分支。
  it("documents the compact layout breakpoint as 1300px", () => {
    expect(COMPACT_LAYOUT_BREAKPOINT_PX).toBe(1300);
    expect(COMPACT_LAYOUT_MEDIA_QUERY).toBe("(width < 1300px)");
  });

  // why: 移动端左右栏必须共用一份抽屉状态；
  // 否则很容易又退回成两颗布尔并存，出现左右栏同时打开的隐式组合态。
  it("keeps a single active drawer on mobile", () => {
    const controller = createMobileDrawerController();

    controller.syncViewport(true);
    controller.openDrawer("left");
    expect(controller.showLeftDrawer.value).toBe(true);
    expect(controller.showRightDrawer.value).toBe(false);

    controller.toggleDrawer("right");
    expect(controller.showLeftDrawer.value).toBe(false);
    expect(controller.showRightDrawer.value).toBe(true);

    controller.toggleDrawer("right");
    expect(controller.backdropVisible.value).toBe(false);
    expect(controller.activeDrawer.value).toBe("none");
  });

  // why: 抽屉只在移动端存在；切回桌面端时必须自动收口，
  // 否则下次再回到移动端会带着旧状态出现“默认先打开一边”的残留。
  it("resets drawer state when leaving mobile viewport", () => {
    const controller = createMobileDrawerController();

    controller.syncViewport(true);
    controller.openDrawer("left");
    expect(controller.backdropVisible.value).toBe(true);

    controller.syncViewport(false);
    expect(controller.activeDrawer.value).toBe("none");
    expect(controller.backdropVisible.value).toBe(false);

    controller.openDrawer("right");
    expect(controller.activeDrawer.value).toBe("none");
  });
});
