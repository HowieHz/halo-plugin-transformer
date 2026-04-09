import { Toast } from "@halo-dev/components";
import { nextTick, ref } from "vue";

type ImportSourceLabel = "剪贴板" | "文件";

interface UseTransferImportSourceFlowOptions {
  applyImportedContent: (raw: string, sourceLabel: ImportSourceLabel) => Promise<void> | void;
  emptyClipboardMessage?: string;
  clipboardReadErrorMessage?: string;
  importFailedMessage?: string;
}

/**
 * why: snippet / rule 新建弹窗的导入流程只是同一套 source flow orchestration，
 * 领域差异只在“拿到 raw 后怎么应用”。把这层弹窗/剪贴板/file input 编排收口后，
 * 权限处理、异常提示和后续编码策略就不必在两个组件里各改一遍。
 */
export function useTransferImportSourceFlow(options: UseTransferImportSourceFlowOptions) {
  const fileInput = ref<HTMLInputElement | null>(null);
  const importSourceVisible = ref(false);

  function openImportSourceModal() {
    importSourceVisible.value = true;
  }

  function closeImportSourceModal() {
    importSourceVisible.value = false;
  }

  async function importFromClipboard() {
    try {
      const text = await navigator.clipboard.readText();
      if (!text.trim()) {
        Toast.warning(options.emptyClipboardMessage ?? "剪贴板里没有可导入的 JSON");
        return;
      }
      await options.applyImportedContent(text, "剪贴板");
      closeImportSourceModal();
    } catch {
      Toast.error(options.clipboardReadErrorMessage ?? "读取剪贴板失败，请检查浏览器权限后重试");
    }
  }

  async function importFromFile() {
    closeImportSourceModal();
    await nextTick();
    fileInput.value?.click();
  }

  async function handleImportFile(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    try {
      await options.applyImportedContent(await file.text(), "文件");
    } catch (error) {
      Toast.error(
        error instanceof Error ? error.message : (options.importFailedMessage ?? "导入失败"),
      );
    } finally {
      input.value = "";
    }
  }

  return {
    closeImportSourceModal,
    fileInput,
    handleImportFile,
    importFromClipboard,
    importFromFile,
    importSourceVisible,
    openImportSourceModal,
  };
}
