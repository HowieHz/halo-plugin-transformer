import { ref } from "vue";

interface UseLeaveConfirmationOptions {
  hasUnsavedChanges: () => boolean;
  hasValidationError: () => boolean;
  discardChanges: () => void | Promise<void>;
  saveChanges: () => Promise<boolean>;
}

type PendingLeaveTarget =
  | {
      kind: "action";
      run: () => void | Promise<void>;
    }
  | {
      kind: "navigation";
      resolve: (allowed: boolean) => void;
    };

/**
 * why: 离开确认需要同时服务于页内切换、整页路由离开和本地按钮动作；
 * 把它收成单一会话原语，才能避免这些入口各自复制一套“是否脏、能否保存、怎么继续”的分支。
 */
export function useLeaveConfirmation(options: UseLeaveConfirmationOptions) {
  const leaveConfirmVisible = ref(false);
  const leaveConfirmCanSave = ref(false);
  const pendingLeaveTarget = ref<PendingLeaveTarget | null>(null);

  function clearPendingLeaveState() {
    leaveConfirmVisible.value = false;
    leaveConfirmCanSave.value = false;
    pendingLeaveTarget.value = null;
  }

  function closeLeaveConfirm() {
    const target = pendingLeaveTarget.value;
    clearPendingLeaveState();
    if (target?.kind === "navigation") {
      target.resolve(false);
    }
  }

  async function continuePendingLeave() {
    const target = pendingLeaveTarget.value;
    clearPendingLeaveState();
    if (!target) {
      return;
    }
    if (target.kind === "navigation") {
      target.resolve(true);
      return;
    }
    await target.run();
  }

  function queueLeaveConfirmation(target: PendingLeaveTarget) {
    pendingLeaveTarget.value = target;
    leaveConfirmCanSave.value = !options.hasValidationError();
    leaveConfirmVisible.value = true;
  }

  function requestActionLeave(action: () => void | Promise<void>) {
    if (!options.hasUnsavedChanges()) {
      void action();
      return true;
    }

    queueLeaveConfirmation({
      kind: "action",
      run: action,
    });
    return false;
  }

  function requestNavigationLeave() {
    if (!options.hasUnsavedChanges()) {
      return Promise.resolve(true);
    }

    return new Promise<boolean>((resolve) => {
      queueLeaveConfirmation({
        kind: "navigation",
        resolve,
      });
    });
  }

  async function confirmDiscardAndLeave() {
    await options.discardChanges();
    await continuePendingLeave();
  }

  async function confirmSaveAndLeave() {
    const saved = await options.saveChanges();
    if (!saved) {
      return false;
    }
    await continuePendingLeave();
    return true;
  }

  return {
    leaveConfirmVisible,
    leaveConfirmCanSave,
    requestActionLeave,
    requestNavigationLeave,
    closeLeaveConfirm,
    confirmDiscardAndLeave,
    confirmSaveAndLeave,
  };
}
