import { RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'

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

/**
 * why: 滑条模式只暴露六档稳定预设；
 * 当当前值是手输出来的自定义整数时，也需要找到最近档位来承接滑条位置。
 */
export function findNearestRuntimeOrderStepIndex(value: number) {
  const exactIndex = RUNTIME_ORDER_STEPS.findIndex((step) => step.value === value)
  if (exactIndex >= 0) {
    return exactIndex
  }

  return RUNTIME_ORDER_STEPS.reduce(
    (nearestIndex, step, index) =>
      Math.abs(step.value - value) < Math.abs(RUNTIME_ORDER_STEPS[nearestIndex].value - value)
        ? index
        : nearestIndex,
    0,
  )
}

export function formatRuntimeOrder(value: number) {
  return value.toLocaleString('en-US')
}
