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

  // why: 重做必须严格按“最近一次被撤销的操作”恢复，
  // 否则 Ctrl/Cmd+Shift+Z 会和字段级历史脱节，产生错误回放顺序。
  it('redos the latest undone field change across fields', () => {
    const undo = useFieldUndo()
    undo.resetBaseline({
      name: 'rule-a',
      description: 'first',
    })

    undo.trackChange('name', 'rule-a', 'rule-b')
    undo.trackChange('description', 'first', 'second')

    const undoneDescription = undo.undoLatest((field) => (field === 'name' ? 'rule-b' : 'second'))
    expect(undoneDescription).toEqual({
      field: 'description',
      value: 'first',
    })

    const redoneDescription = undo.redoLatest((field) => (field === 'name' ? 'rule-b' : 'first'))
    expect(redoneDescription).toEqual({
      field: 'description',
      value: 'second',
    })
  })

  // why: 一旦用户在撤销后又继续编辑，旧 redo 链就必须失效；
  // 否则会把已经分叉的历史重新写回，破坏当前草稿。
  it('drops redo history after a new tracked change', () => {
    const undo = useFieldUndo()
    undo.resetBaseline({
      name: 'rule-a',
    })

    undo.trackChange('name', 'rule-a', 'rule-b')

    const undone = undo.undoLatest(() => 'rule-b')
    expect(undone).toEqual({
      field: 'name',
      value: 'rule-a',
    })

    undo.trackChange('name', 'rule-a', 'rule-c')

    expect(undo.redoLatest(() => 'rule-c')).toBeUndefined()
  })
})
