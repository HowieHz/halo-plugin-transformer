import { RUNTIME_ORDER_MAX } from '@/types'

/**
 * why: 运行顺序最终会写进后端 `int` 字段；
 * 前端所有入口都应共用同一套非负、上限封顶的归一化规则，避免滑条/手输/导入各自漂移。
 */
export function clampRuntimeOrder(value: number) {
  if (!Number.isFinite(value)) {
    return RUNTIME_ORDER_MAX
  }
  return Math.min(RUNTIME_ORDER_MAX, Math.max(0, Math.trunc(value)))
}

export function formatRuntimeOrder(value: number) {
  return value.toLocaleString('en-US')
}
