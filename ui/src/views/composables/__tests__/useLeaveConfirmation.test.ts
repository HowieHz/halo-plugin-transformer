import { describe, expect, it, vi } from 'vitest'
import { useLeaveConfirmation } from '../useLeaveConfirmation'

describe('useLeaveConfirmation', () => {
  // why: 干净状态下不该弹确认框；否则页内切换和普通导航都会被无意义地拦截。
  it('runs leave action immediately when nothing is dirty', async () => {
    const action = vi.fn()
    const state = useLeaveConfirmation({
      hasUnsavedChanges: () => false,
      hasValidationError: () => false,
      discardChanges: vi.fn(),
      saveChanges: vi.fn(async () => true),
    })

    const allowed = state.requestLeave(action)

    expect(allowed).toBe(true)
    expect(action).toHaveBeenCalledTimes(1)
    expect(state.leaveConfirmVisible.value).toBe(false)
  })

  // why: 一旦存在未保存修改，就必须先进入统一确认会话，而不是让不同入口各自决定是否直接离开。
  it('opens confirmation when unsaved changes exist', () => {
    const state = useLeaveConfirmation({
      hasUnsavedChanges: () => true,
      hasValidationError: () => false,
      discardChanges: vi.fn(),
      saveChanges: vi.fn(async () => true),
    })

    const allowed = state.requestLeave(vi.fn())

    expect(allowed).toBe(false)
    expect(state.leaveConfirmVisible.value).toBe(true)
    expect(state.leaveConfirmCanSave.value).toBe(true)
  })

  // why: 放弃后应先回到已保存状态，再继续原先那次离开动作，避免旧草稿残留影响后续页面。
  it('discards changes before continuing the pending leave action', async () => {
    const discardChanges = vi.fn()
    const leaveAction = vi.fn()
    const state = useLeaveConfirmation({
      hasUnsavedChanges: () => true,
      hasValidationError: () => true,
      discardChanges,
      saveChanges: vi.fn(async () => true),
    })

    state.requestLeave(leaveAction)
    await state.confirmDiscardAndLeave()

    expect(discardChanges).toHaveBeenCalledTimes(1)
    expect(leaveAction).toHaveBeenCalledTimes(1)
    expect(state.leaveConfirmVisible.value).toBe(false)
  })

  // why: “保存后继续”只有在保存真正成功时才能重放离开动作；否则会把失败保存误当成已安全离开。
  it('only continues after save succeeds', async () => {
    const leaveAction = vi.fn()
    const saveChanges = vi.fn<() => Promise<boolean>>()
    saveChanges.mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    const state = useLeaveConfirmation({
      hasUnsavedChanges: () => true,
      hasValidationError: () => false,
      discardChanges: vi.fn(),
      saveChanges,
    })

    state.requestLeave(leaveAction)
    await state.confirmSaveAndLeave()
    expect(leaveAction).not.toHaveBeenCalled()
    expect(state.leaveConfirmVisible.value).toBe(true)

    await state.confirmSaveAndLeave()
    expect(leaveAction).toHaveBeenCalledTimes(1)
    expect(state.leaveConfirmVisible.value).toBe(false)
  })
})
