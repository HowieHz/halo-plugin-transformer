// @vitest-environment jsdom

import { mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import FieldUndoButton from "../FieldUndoButton.vue";

describe("FieldUndoButton accessibility", () => {
  let originalRequestAnimationFrame: typeof window.requestAnimationFrame;
  let originalCancelAnimationFrame: typeof window.cancelAnimationFrame;

  beforeEach(() => {
    vi.useFakeTimers();
    originalRequestAnimationFrame = window.requestAnimationFrame;
    originalCancelAnimationFrame = window.cancelAnimationFrame;
    window.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      return window.setTimeout(() => callback(performance.now()), 16);
    }) as typeof window.requestAnimationFrame;
    window.cancelAnimationFrame = ((id: number) => {
      clearTimeout(id);
    }) as typeof window.cancelAnimationFrame;
  });

  afterEach(() => {
    window.requestAnimationFrame = originalRequestAnimationFrame;
    window.cancelAnimationFrame = originalCancelAnimationFrame;
    vi.useRealTimers();
  });

  it("describes long-press behavior and supports keyboard reset", async () => {
    const wrapper = mount(FieldUndoButton, {
      props: {
        previewStartMs: 100,
        resetPressMs: 300,
      },
    });

    const button = wrapper.get("button");
    const descriptionId = button.attributes("aria-describedby");

    expect(descriptionId).toBeTruthy();
    expect(wrapper.get(`#${descriptionId}`).text()).toContain("Enter / Space");

    await button.trigger("keydown", { key: "Enter" });
    await vi.advanceTimersByTimeAsync(320);
    await button.trigger("keyup", { key: "Enter" });

    expect(wrapper.emitted("reset")).toHaveLength(1);
    expect(wrapper.emitted("undo")).toBeUndefined();
  });
});
