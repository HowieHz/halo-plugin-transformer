import { describe, expect, it } from 'vitest'
import { clampRuntimeOrder, formatRuntimeOrder, snapRuntimeOrderToPreset } from '../runtimeOrder'
import { RUNTIME_ORDER_DEFAULT, RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'

describe('runtimeOrder', () => {
  // why: 所有前端入口都应把运行顺序裁剪到后端 int 可接受范围；
  // 这样不会因为不同入口实现细节不同而写出协议外数值。
  it('clamps runtime order into the backend-safe integer range', () => {
    expect(clampRuntimeOrder(Number.NaN)).toBe(RUNTIME_ORDER_MAX)
    expect(clampRuntimeOrder(RUNTIME_ORDER_MAX + 100)).toBe(RUNTIME_ORDER_MAX)
    expect(clampRuntimeOrder(-5)).toBe(0)
    expect(clampRuntimeOrder(123.9)).toBe(123)
  })

  // why: 当前值展示要可读；大整数应固定带分组，方便人工核对导入/手输值。
  it('formats runtime order for display', () => {
    expect(formatRuntimeOrder(RUNTIME_ORDER_DEFAULT)).toBe('2,147,483,645')
  })

  // why: 滑条交互面向档位选择；接近预设值时应自动吸附，避免用户去手搓那几个大整数。
  it('snaps slider values near preset runtime order steps', () => {
    expect(snapRuntimeOrderToPreset(10)).toBe(0)
    expect(snapRuntimeOrderToPreset(RUNTIME_ORDER_STEPS[1].value + 20_000_000)).toBe(
      RUNTIME_ORDER_STEPS[1].value,
    )
    expect(snapRuntimeOrderToPreset(RUNTIME_ORDER_STEPS[2].value + 100_000_000)).toBe(
      RUNTIME_ORDER_STEPS[2].value + 100_000_000,
    )
  })
})
