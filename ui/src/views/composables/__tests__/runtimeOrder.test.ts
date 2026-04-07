import { describe, expect, it } from 'vitest'
import {
  clampRuntimeOrder,
  findNearestRuntimeOrderStepIndex,
  formatRuntimeOrder,
} from '../runtimeOrder'
import { RUNTIME_ORDER_DEFAULT, RUNTIME_ORDER_MAX } from '@/types'

describe('runtimeOrder', () => {
  // why: UI 六档滑条和后端 `runtimeOrder` 必须共用同一组离散值映射；
  // 这里锁住“自定义值 -> 最近档位”的规则，避免后续改成不稳定的近似行为。
  it('maps exact and custom values to stable preset indexes', () => {
    expect(findNearestRuntimeOrderStepIndex(0)).toBe(0)
    expect(findNearestRuntimeOrderStepIndex(RUNTIME_ORDER_DEFAULT)).toBe(5)
    expect(findNearestRuntimeOrderStepIndex(100)).toBe(0)
    expect(findNearestRuntimeOrderStepIndex(1_800_000_000)).toBe(4)
  })

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
