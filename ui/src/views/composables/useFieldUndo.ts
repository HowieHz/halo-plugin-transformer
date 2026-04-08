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
  const timeline = ref<string[]>([])
  const future = ref<Record<string, unknown[]>>({})
  const redoTimeline = ref<string[]>([])

  function resetBaseline(snapshot: Record<string, unknown>) {
    baseline.value = cloneValue(snapshot)
    history.value = {}
    timeline.value = []
    future.value = {}
    redoTimeline.value = []
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

    timeline.value = [...timeline.value, field]
    future.value = {}
    redoTimeline.value = []
  }

  function isModified(field: string, current: unknown) {
    return !isEqual(current, baseline.value[field])
  }

  function undo(field: string, current: unknown) {
    const stack = [...(history.value[field] ?? [])]
    if (!stack.length) {
      const fallback = isModified(field, current) ? cloneValue(baseline.value[field]) : undefined
      if (fallback !== undefined) {
        pushRedoValue(field, current)
        timeline.value = removeLatestTimelineField(field)
        redoTimeline.value = [...redoTimeline.value, field]
      }
      return fallback
    }

    const previous = stack.pop()
    history.value = {
      ...history.value,
      [field]: stack,
    }
    pushRedoValue(field, current)
    timeline.value = removeLatestTimelineField(field)
    redoTimeline.value = [...redoTimeline.value, field]
    return cloneValue(previous)
  }

  function reset(field: string) {
    history.value = {
      ...history.value,
      [field]: [],
    }
    timeline.value = timeline.value.filter((entry) => entry !== field)
    future.value = {}
    redoTimeline.value = []
    return cloneValue(baseline.value[field])
  }

  function undoLatest(resolveCurrent: (field: string) => unknown) {
    for (let index = timeline.value.length - 1; index >= 0; index -= 1) {
      const field = timeline.value[index]
      const previous = undo(field, resolveCurrent(field))
      if (previous !== undefined) {
        return {
          field,
          value: previous,
        }
      }
    }
    return undefined
  }

  function redo(field: string, current: unknown) {
    const stack = [...(future.value[field] ?? [])]
    if (!stack.length) {
      return undefined
    }

    const next = stack.pop()
    future.value = {
      ...future.value,
      [field]: stack,
    }
    history.value = {
      ...history.value,
      [field]: [...(history.value[field] ?? []), cloneValue(current)],
    }
    redoTimeline.value = removeLatestRedoTimelineField(field)
    timeline.value = [...timeline.value, field]
    return cloneValue(next)
  }

  function redoLatest(resolveCurrent: (field: string) => unknown) {
    for (let index = redoTimeline.value.length - 1; index >= 0; index -= 1) {
      const field = redoTimeline.value[index]
      const next = redo(field, resolveCurrent(field))
      if (next !== undefined) {
        return {
          field,
          value: next,
        }
      }
    }
    return undefined
  }

  function removeLatestTimelineField(field: string) {
    const nextTimeline = [...timeline.value]
    for (let index = nextTimeline.length - 1; index >= 0; index -= 1) {
      if (nextTimeline[index] === field) {
        nextTimeline.splice(index, 1)
        break
      }
    }
    return nextTimeline
  }

  function removeLatestRedoTimelineField(field: string) {
    const nextTimeline = [...redoTimeline.value]
    for (let index = nextTimeline.length - 1; index >= 0; index -= 1) {
      if (nextTimeline[index] === field) {
        nextTimeline.splice(index, 1)
        break
      }
    }
    return nextTimeline
  }

  function pushRedoValue(field: string, current: unknown) {
    future.value = {
      ...future.value,
      [field]: [...(future.value[field] ?? []), cloneValue(current)],
    }
  }

  return {
    resetBaseline,
    trackChange,
    isModified,
    undo,
    undoLatest,
    redo,
    redoLatest,
    reset,
  }
}
