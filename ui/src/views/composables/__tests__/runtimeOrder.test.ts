import { describe, expect, it } from 'vitest'
import { clampRuntimeOrder, formatRuntimeOrder } from '../runtimeOrder'
import { RUNTIME_ORDER_DEFAULT, RUNTIME_ORDER_MAX } from '@/types'

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
})
