// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";

const { toast } = vi.hoisted(() => ({
  toast: {
    error: vi.fn(),
    warning: vi.fn(),
  },
}));
const { clipboardReadText } = vi.hoisted(() => ({
  clipboardReadText: vi.fn<() => Promise<string>>(),
}));

vi.mock("@halo-dev/components", () => ({
  Toast: toast,
}));

import { useTransferImportSourceFlow } from "../useTransferImportSourceFlow";

describe("useTransferImportSourceFlow", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(globalThis.navigator, "clipboard", {
      configurable: true,
      value: {
        readText: clipboardReadText,
      },
    });
  });

  // why: 这层 composable 的价值就在于把 source flow orchestration 收成唯一实现；
  // 所以剪贴板、file input、错误提示这些行为必须被单测锁住，避免 snippet/rule 弹窗再各自长回一份。
  it("warns for empty clipboard content without applying imported content", async () => {
    const applyImportedContent = vi.fn();
    clipboardReadText.mockResolvedValue("   ");
    const flow = useTransferImportSourceFlow({
      applyImportedContent,
    });

    await flow.importFromClipboard();

    expect(toast.warning).toHaveBeenCalledWith("剪贴板里没有可导入的 JSON");
    expect(applyImportedContent).not.toHaveBeenCalled();
    expect(flow.importSourceVisible.value).toBe(false);
  });

  it("applies clipboard content and closes the source modal", async () => {
    const applyImportedContent = vi.fn();
    clipboardReadText.mockResolvedValue('{"kind":"demo"}');
    const flow = useTransferImportSourceFlow({
      applyImportedContent,
    });
    flow.openImportSourceModal();

    await flow.importFromClipboard();

    expect(applyImportedContent).toHaveBeenCalledWith('{"kind":"demo"}', "剪贴板");
    expect(flow.importSourceVisible.value).toBe(false);
  });

  it("closes the source modal before opening the file picker", async () => {
    const flow = useTransferImportSourceFlow({
      applyImportedContent: vi.fn(),
    });
    const click = vi.fn();

    flow.fileInput.value = {
      click,
    } as unknown as HTMLInputElement;
    flow.openImportSourceModal();

    await flow.importFromFile();
    await nextTick();

    expect(flow.importSourceVisible.value).toBe(false);
    expect(click).toHaveBeenCalledTimes(1);
  });

  it("applies file content and clears the input value", async () => {
    const applyImportedContent = vi.fn();
    const flow = useTransferImportSourceFlow({
      applyImportedContent,
    });
    const event = {
      target: {
        files: [
          {
            text: vi.fn().mockResolvedValue('{"kind":"demo"}'),
          },
        ],
        value: "chosen.json",
      },
    } as unknown as Event;

    await flow.handleImportFile(event);

    expect(applyImportedContent).toHaveBeenCalledWith('{"kind":"demo"}', "文件");
    expect((event.target as HTMLInputElement).value).toBe("");
  });
});
