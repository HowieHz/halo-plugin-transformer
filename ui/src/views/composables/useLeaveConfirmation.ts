import { ref } from 'vue'

interface UseLeaveConfirmationOptions {
  hasUnsavedChanges: () => boolean
  hasValidationError: () => boolean
  discardChanges: () => void | Promise<void>
  saveChanges: () => Promise<boolean>
}

/**
 * why: 离开确认需要同时服务于页内切换、整页路由离开和本地按钮动作；
 * 把它收成单一会话原语，才能避免这些入口各自复制一套“是否脏、能否保存、怎么继续”的分支。
 */
export function useLeaveConfirmation(options: UseLeaveConfirmationOptions) {
  const leaveConfirmVisible = ref(false)
  const leaveConfirmCanSave = ref(false)
  const pendingLeaveAction = ref<null | (() => void | Promise<void>)>(null)

  function closeLeaveConfirm() {
    leaveConfirmVisible.value = false
    leaveConfirmCanSave.value = false
    pendingLeaveAction.value = null
  }

  async function runPendingLeaveAction() {
    const action = pendingLeaveAction.value
    closeLeaveConfirm()
    if (!action) {
      return
    }
    await action()
  }

  function requestLeave(action: () => void | Promise<void>) {
    if (!options.hasUnsavedChanges()) {
      void action()
      return true
    }

    pendingLeaveAction.value = action
    leaveConfirmCanSave.value = !options.hasValidationError()
    leaveConfirmVisible.value = true
    return false
  }

  async function confirmDiscardAndLeave() {
    await options.discardChanges()
    await runPendingLeaveAction()
  }

  async function confirmSaveAndLeave() {
    const saved = await options.saveChanges()
    if (!saved) {
      return false
    }
    await runPendingLeaveAction()
    return true
  }

  return {
    leaveConfirmVisible,
    leaveConfirmCanSave,
    requestLeave,
    closeLeaveConfirm,
    confirmDiscardAndLeave,
    confirmSaveAndLeave,
  }
}
