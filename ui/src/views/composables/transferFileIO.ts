import type { TransferFileDraft } from "./transferExportBuilder";

interface SaveFilePickerOptionsLike {
  suggestedName?: string;
  types?: Array<{
    description?: string;
    accept: Record<string, string[]>;
  }>;
}

interface SaveFilePickerHandleLike {
  createWritable(): Promise<{
    write(data: string): Promise<void>;
    close(): Promise<void>;
  }>;
}

type WindowWithSaveFilePicker = Window & {
  showSaveFilePicker?: (options?: SaveFilePickerOptionsLike) => Promise<SaveFilePickerHandleLike>;
};

/**
 * why: 导出优先走系统“另存为”，失败时由上层决定如何兜底，
 * 这样既能覆盖浏览器不支持、非安全上下文等场景，也能给用户保留手动复制窗口。
 */
export async function downloadTransferFile(draft: TransferFileDraft) {
  const saveFilePicker = (window as WindowWithSaveFilePicker).showSaveFilePicker;

  if (typeof saveFilePicker !== "function") {
    throw new Error("当前环境暂时无法直接保存为文件");
  }
  const handle = await saveFilePicker({
    suggestedName: draft.fileName,
    types: [
      {
        description: "JSON 文件",
        accept: {
          "application/json": [".json"],
        },
      },
    ],
  });
  const writable = await handle.createWritable();
  await writable.write(draft.content);
  await writable.close();
}
