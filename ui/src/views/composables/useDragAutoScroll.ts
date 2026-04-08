import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'

export type DragAutoScrollDirection = 'up' | 'down'

interface UseDragAutoScrollOptions {
  minStepPx?: number
  maxStepPx?: number
}

interface DragAutoScrollZoneBounds {
  topZoneHeight?: number
  bottomZoneHeight?: number
}

/**
 * why: 列表拖拽与规则树拖拽都需要同一套“拖到边缘就自动滚动”的交互，
 * 用共享状态机可以避免每个组件各自维护一套定时器与边界判断，减少后续漂移。
 */
export function useDragAutoScroll(
  containerRef: Ref<HTMLElement | null>,
  options: UseDragAutoScrollOptions = {},
) {
  const minScrollStepPx = options.minStepPx ?? 6
  const maxScrollStepPx = options.maxStepPx ?? 28
  const initialScrollStepPx = Math.max(1, minScrollStepPx * 0.5)
  const warmupDurationMs = 320
  const isDragActive = ref(false)
  const activeDirection = ref<DragAutoScrollDirection | null>(null)
  const scrollTop = ref(0)
  const maxScrollTop = ref(0)
  const currentStepPx = ref(minScrollStepPx)
  let activeDirectionStartedAt = 0
  let animationFrameId: number | null = null

  const canScrollUp = computed(() => scrollTop.value > 0)
  const canScrollDown = computed(() => scrollTop.value < maxScrollTop.value)

  function updateScrollBounds() {
    const container = containerRef.value
    if (!container) {
      scrollTop.value = 0
      maxScrollTop.value = 0
      return
    }
    scrollTop.value = container.scrollTop
    maxScrollTop.value = Math.max(0, container.scrollHeight - container.clientHeight)
  }

  function stopAutoScroll() {
    activeDirection.value = null
    currentStepPx.value = minScrollStepPx
    activeDirectionStartedAt = 0
    if (animationFrameId !== null) {
      cancelAnimationFrame(animationFrameId)
      animationFrameId = null
    }
  }

  function tickAutoScroll() {
    const container = containerRef.value
    if (!container || !isDragActive.value || !activeDirection.value) {
      stopAutoScroll()
      return
    }

    updateScrollBounds()
    const nextOffset = activeDirection.value === 'up' ? -currentStepPx.value : currentStepPx.value
    const canScroll = activeDirection.value === 'up' ? canScrollUp.value : canScrollDown.value

    if (canScroll) {
      container.scrollTop += nextOffset
      updateScrollBounds()
    }

    animationFrameId = requestAnimationFrame(tickAutoScroll)
  }

  /**
   * why: 自动滚动的热区应该只是“感知边缘位置”，不能再靠覆盖层自己接管 drop；
   * 否则一旦提示层和真实落点重叠，就会出现高亮正确但松手失败的错觉。
   */
  function handleContainerDragOver(event: DragEvent, zoneBounds: DragAutoScrollZoneBounds = {}) {
    if (!isDragActive.value) {
      return
    }

    const currentTarget = event.currentTarget
    if (!(currentTarget instanceof HTMLElement)) {
      stopAutoScroll()
      return
    }

    const direction = resolveAutoScrollDirection(currentTarget, event, zoneBounds)
    if (!direction) {
      stopAutoScroll()
      return
    }

    event.preventDefault()
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move'
    }

    if (activeDirection.value !== direction) {
      activeDirectionStartedAt = performance.now()
    }
    activeDirection.value = direction
    currentStepPx.value = resolveScrollStepPxWithinBounds(
      currentTarget,
      direction,
      event,
      zoneBounds,
    )
    updateScrollBounds()
    if (animationFrameId === null) {
      animationFrameId = requestAnimationFrame(tickAutoScroll)
    }
  }

  function handleContainerDragLeave(event: DragEvent) {
    const currentTarget = event.currentTarget
    if (!(currentTarget instanceof HTMLElement)) {
      stopAutoScroll()
      return
    }

    const rect = currentTarget.getBoundingClientRect()
    const isOutside =
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom

    if (isOutside) {
      stopAutoScroll()
    }
  }

  function handleContainerScroll() {
    updateScrollBounds()
  }

  function setDragActive(active: boolean) {
    isDragActive.value = active
    if (active) {
      updateScrollBounds()
      return
    }
    stopAutoScroll()
  }

  function resetOnGlobalDragEnd() {
    setDragActive(false)
  }

  function resolveScrollStepPxWithinBounds(
    currentTarget: HTMLElement,
    direction: DragAutoScrollDirection,
    event: DragEvent,
    zoneBounds: DragAutoScrollZoneBounds = {},
  ) {
    const zoneRect = resolveZoneRect(currentTarget, direction, zoneBounds)
    if (!zoneRect.height) {
      return minScrollStepPx
    }

    const pointerRatio = clamp((event.clientY - zoneRect.top) / zoneRect.height, 0, 1)
    const edgeIntensity = direction === 'up' ? 1 - pointerRatio : pointerRatio
    const easedIntensity = edgeIntensity ** 1.6
    const spatialStep = minScrollStepPx + (maxScrollStepPx - minScrollStepPx) * easedIntensity
    const warmupProgress =
      activeDirectionStartedAt > 0
        ? clamp((performance.now() - activeDirectionStartedAt) / warmupDurationMs, 0, 1)
        : 0
    return (
      initialScrollStepPx + (spatialStep - initialScrollStepPx) * easeOutQuad(warmupProgress)
    )
  }

  function resolveAutoScrollDirection(
    currentTarget: HTMLElement,
    event: DragEvent,
    zoneBounds: DragAutoScrollZoneBounds,
  ) {
    const rect = currentTarget.getBoundingClientRect()
    const topZoneHeight = clamp(zoneBounds.topZoneHeight ?? 0, 0, rect.height)
    const bottomZoneHeight = clamp(zoneBounds.bottomZoneHeight ?? 0, 0, rect.height)

    if (topZoneHeight > 0 && event.clientY <= rect.top + topZoneHeight) {
      return 'up'
    }
    if (bottomZoneHeight > 0 && event.clientY >= rect.bottom - bottomZoneHeight) {
      return 'down'
    }
    return null
  }

  function resolveZoneRect(
    currentTarget: HTMLElement,
    direction: DragAutoScrollDirection,
    zoneBounds: DragAutoScrollZoneBounds,
  ) {
    const rect = currentTarget.getBoundingClientRect()
    const topZoneHeight = clamp(zoneBounds.topZoneHeight ?? rect.height, 0, rect.height)
    const bottomZoneHeight = clamp(zoneBounds.bottomZoneHeight ?? rect.height, 0, rect.height)

    if (direction === 'up') {
      return {
        top: rect.top,
        height: topZoneHeight,
      }
    }

    return {
      top: rect.bottom - bottomZoneHeight,
      height: bottomZoneHeight,
    }
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value))
  }

  function easeOutQuad(value: number) {
    return 1 - (1 - value) ** 2
  }

  watch(
    isDragActive,
    (active, _, onCleanup) => {
      if (!active || typeof window === 'undefined') {
        return
      }

      window.addEventListener('dragend', resetOnGlobalDragEnd, true)
      window.addEventListener('drop', resetOnGlobalDragEnd, true)

      const cleanup = () => {
        window.removeEventListener('dragend', resetOnGlobalDragEnd, true)
        window.removeEventListener('drop', resetOnGlobalDragEnd, true)
      }

      onCleanup(cleanup)
    },
    { flush: 'post' },
  )

  onBeforeUnmount(() => {
    stopAutoScroll()
    if (typeof window !== 'undefined') {
      window.removeEventListener('dragend', resetOnGlobalDragEnd, true)
      window.removeEventListener('drop', resetOnGlobalDragEnd, true)
    }
  })

  return {
    isDragActive,
    activeDirection,
    canScrollUp,
    canScrollDown,
    setDragActive,
    handleContainerScroll,
    handleContainerDragOver,
    handleContainerDragLeave,
    stopAutoScroll,
  }
}
