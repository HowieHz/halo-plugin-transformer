import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'

export type DragAutoScrollDirection = 'up' | 'down'

interface UseDragAutoScrollOptions {
  minStepPx?: number
  maxStepPx?: number
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
  const isDragActive = ref(false)
  const activeDirection = ref<DragAutoScrollDirection | null>(null)
  const scrollTop = ref(0)
  const maxScrollTop = ref(0)
  const currentStepPx = ref(minScrollStepPx)
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

  function startAutoScroll(direction: DragAutoScrollDirection, event: DragEvent) {
    if (!isDragActive.value) {
      return
    }
    event.preventDefault()
    event.stopPropagation()
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move'
    }
    activeDirection.value = direction
    currentStepPx.value = resolveScrollStepPx(direction, event)
    updateScrollBounds()
    if (animationFrameId === null) {
      animationFrameId = requestAnimationFrame(tickAutoScroll)
    }
  }

  function handleZoneLeave(direction: DragAutoScrollDirection) {
    if (activeDirection.value === direction) {
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

  function resolveScrollStepPx(direction: DragAutoScrollDirection, event: DragEvent) {
    const currentTarget = event.currentTarget
    if (!(currentTarget instanceof HTMLElement)) {
      return minScrollStepPx
    }

    const rect = currentTarget.getBoundingClientRect()
    if (!rect.height) {
      return minScrollStepPx
    }

    const pointerRatio = clamp((event.clientY - rect.top) / rect.height, 0, 1)
    const edgeIntensity = direction === 'up' ? 1 - pointerRatio : pointerRatio
    const easedIntensity = edgeIntensity ** 1.6
    return minScrollStepPx + (maxScrollStepPx - minScrollStepPx) * easedIntensity
  }

  function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value))
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
    startAutoScroll,
    handleZoneLeave,
    stopAutoScroll,
  }
}
