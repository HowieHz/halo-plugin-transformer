import { RUNTIME_ORDER_MAX, RUNTIME_ORDER_STEPS } from '@/types'

const PRESET_SNAP_RATIO = 0.08

function getPresetSnapDistance(index: number) {
  const step = RUNTIME_ORDER_STEPS[index]
  if (!step) {
    return 0
  }

  const previous = RUNTIME_ORDER_STEPS[index - 1]
  const next = RUNTIME_ORDER_STEPS[index + 1]
  const nearestGap = Math.min(
    previous ? step.value - previous.value : Number.POSITIVE_INFINITY,
    next ? next.value - step.value : Number.POSITIVE_INFINITY,
  )

  return Number.isFinite(nearestGap) && nearestGap > 0
    ? Math.max(1, Math.round(nearestGap * PRESET_SNAP_RATIO))
    : 0
}

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

/**
 * why: 滑条主要服务“按档位挑优先级”，而不是让用户精确命中那几个大整数；
 * 接近预设档位时自动吸附，能让交互更稳，但又保留离档位较远时的连续调节空间。
 */
export function snapRuntimeOrderToPreset(value: number) {
  const normalized = clampRuntimeOrder(value)

  for (const [index, step] of RUNTIME_ORDER_STEPS.entries()) {
    if (Math.abs(normalized - step.value) <= getPresetSnapDistance(index)) {
      return step.value
    }
  }

  return normalized
}

/**
 * why: 滑条拖动时的辅助视觉应与真正的吸附阈值严格一致；
 * 这样“看见刻度”就等于“松手会吸过去”，不会出现提示和结果打架。
 */
export function findRuntimeOrderSnapStep(value: number) {
  const normalized = clampRuntimeOrder(value)

  for (const [index, step] of RUNTIME_ORDER_STEPS.entries()) {
    if (Math.abs(normalized - step.value) <= getPresetSnapDistance(index)) {
      return step
    }
  }

  return null
}

/**
 * why: 视觉提示应该帮助用户理解“当前处在哪个优先级区间”，
 * 而不是再把大整数原样抛回界面，增加理解成本。
 */
export function describeRuntimeOrderRange(value: number) {
  const normalized = clampRuntimeOrder(value)
  const exactStep = RUNTIME_ORDER_STEPS.find((step) => step.value === normalized)
  if (exactStep) {
    return `当前档位：${exactStep.label}`
  }

  for (let index = 0; index < RUNTIME_ORDER_STEPS.length - 1; index += 1) {
    const lower = RUNTIME_ORDER_STEPS[index]
    const upper = RUNTIME_ORDER_STEPS[index + 1]
    if (normalized > lower.value && normalized < upper.value) {
      return `当前范围：${lower.label} ～ ${upper.label}`
    }
  }

  const lastStep = RUNTIME_ORDER_STEPS[RUNTIME_ORDER_STEPS.length - 1]
  return `当前档位：${lastStep?.label ?? '最低'}`
}
