import { describe, expect, it } from 'vitest'
import { useFieldUndo } from '../useFieldUndo'

describe('useFieldUndo', () => {
  // why: Ctrl/Cmd+Z 走的是“最近一次字段操作”语义；
  // 这里锁住跨字段时间线，避免后续又退回成“每个字段各撤各的”。
  it('undoes the latest tracked field change across fields', () => {
    const undo = useFieldUndo()
    undo.resetBaseline({
      name: 'rule-a',
      description: 'first',
    })

    undo.trackChange('name', 'rule-a', 'rule-b')
    undo.trackChange('description', 'first', 'second')

    const latest = undo.undoLatest((field) => (field === 'name' ? 'rule-b' : 'second'))

    expect(latest).toEqual({
      field: 'description',
      value: 'first',
    })
  })

  // why: 同一字段连续修改后，最近撤销必须逐步回退，而不是直接跳回 baseline。
  it('keeps per-field step-by-step undo when using the latest timeline', () => {
    const undo = useFieldUndo()
    undo.resetBaseline({
      name: 'rule-a',
    })

    undo.trackChange('name', 'rule-a', 'rule-b')
    undo.trackChange('name', 'rule-b', 'rule-c')

    const latest = undo.undoLatest(() => 'rule-c')
    expect(latest).toEqual({
      field: 'name',
      value: 'rule-b',
    })

    const previous = undo.undoLatest(() => 'rule-b')
    expect(previous).toEqual({
      field: 'name',
      value: 'rule-a',
    })
  })
})
