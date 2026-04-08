import { ref } from 'vue'

function cloneValue<T>(value: T): T {
  if (value === undefined) {
    return value
  }
  return JSON.parse(JSON.stringify(value)) as T
}

function isEqual(a: unknown, b: unknown) {
  return JSON.stringify(a) === JSON.stringify(b)
}

/**
 * why: 字段级撤销既要支持“回退一步”，也要支持“回到未修改状态”，
 * 因此这里同时维护每个字段的基线快照和逐步历史，供各编辑器复用。
 */
export function useFieldUndo() {
  const baseline = ref<Record<string, unknown>>({})
  const history = ref<Record<string, unknown[]>>({})

  function resetBaseline(snapshot: Record<string, unknown>) {
    baseline.value = cloneValue(snapshot)
    history.value = {}
  }

  function trackChange(field: string, previous: unknown, next: unknown) {
    if (isEqual(previous, next)) {
      return
    }

    const stack = [...(history.value[field] ?? [])]
    const previousClone = cloneValue(previous)
    if (!stack.length || !isEqual(stack[stack.length - 1], previousClone)) {
      stack.push(previousClone)
      history.value = {
        ...history.value,
        [field]: stack,
      }
    }
  }

  function isModified(field: string, current: unknown) {
    return !isEqual(current, baseline.value[field])
  }

  function undo(field: string, current: unknown) {
    const stack = [...(history.value[field] ?? [])]
    if (!stack.length) {
      return isModified(field, current) ? cloneValue(baseline.value[field]) : undefined
    }

    const previous = stack.pop()
    history.value = {
      ...history.value,
      [field]: stack,
    }
    return cloneValue(previous)
  }

  function reset(field: string) {
    history.value = {
      ...history.value,
      [field]: [],
    }
    return cloneValue(baseline.value[field])
  }

  return {
    resetBaseline,
    trackChange,
    isModified,
    undo,
    reset,
  }
}
