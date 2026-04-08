import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { Toast } from '@halo-dev/components'
import type { PersistedOrderState, OrderMap } from '@/apis'
import type { ItemList } from '@/types'
import { buildExplicitOrderMap, sortByOrderMap } from './util'
import { getErrorMessage, reorderItems, type ReorderPlacement } from './injectorShared'

interface OrderApi {
  getOrder: () => Promise<{ data: PersistedOrderState }>
  updateOrder: (
    orders: OrderMap,
    version: number | null | undefined,
  ) => Promise<{ data: PersistedOrderState }>
}

interface UseResourceOrderStateOptions<T extends { id: string }> {
  itemsResp: Ref<ItemList<T>>
  api: OrderApi
  resourceLabel: string
}

/**
 * why: 左侧排序本身就是独立的 bounded context；
 * 它不该继续散落在 snippet/rule CRUD 流程里，而应统一封装 optimistic reorder、版本号、失败回滚与提示语义。
 */
export function useResourceOrderState<T extends { id: string }>(
  options: UseResourceOrderStateOptions<T>,
) {
  const orders = ref<OrderMap>({})
  const orderVersion = ref<number | null>(null)
  const syncingReorder = ref(false)
  const pendingOrders = ref<OrderMap | null>(null)

  const items = computed(() => sortByOrderMap(options.itemsResp.value.items, orders.value))

  function applyOrderSnapshot(snapshot: PersistedOrderState) {
    orders.value = snapshot.orders
    orderVersion.value = snapshot.version
  }

  async function reloadOrders() {
    const response = await options.api.getOrder()
    applyOrderSnapshot(response.data)
  }

  async function restoreOrdersAfterFailedSave(previousOrders: OrderMap, previousVersion: number | null) {
    try {
      await reloadOrders()
    } catch {
      orders.value = { ...previousOrders }
      orderVersion.value = previousVersion
    }
  }

  async function saveOrderMap(nextItems: T[]) {
    const previousOrders = { ...orders.value }
    const previousVersion = orderVersion.value
    const nextOrders = buildExplicitOrderMap(nextItems)
    orders.value = { ...nextOrders }
    try {
      const response = await options.api.updateOrder(nextOrders, orderVersion.value)
      applyOrderSnapshot(response.data)
      return true
    } catch (error) {
      await restoreOrdersAfterFailedSave(previousOrders, previousVersion)
      return getErrorMessage(error, `${options.resourceLabel}顺序保存失败`)
    }
  }

  async function reorder(payload: {
    sourceId: string
    targetId: string
    placement: ReorderPlacement
  }) {
    const ordered = reorderItems(items.value, payload.sourceId, payload.targetId, payload.placement)
    if (!ordered) {
      return
    }

    orders.value = buildExplicitOrderMap(ordered)
    pendingOrders.value = { ...orders.value }
    if (syncingReorder.value) {
      return
    }

    syncingReorder.value = true
    let updatedOnce = false
    try {
      while (pendingOrders.value) {
        const snapshot = pendingOrders.value
        pendingOrders.value = null
        const response = await options.api.updateOrder(snapshot, orderVersion.value)
        applyOrderSnapshot(response.data)
        updatedOnce = true
      }
      if (updatedOnce) {
        Toast.success(`${options.resourceLabel}顺序保存成功`)
      }
    } catch (error) {
      Toast.error(getErrorMessage(error, '更新顺序失败'))
      pendingOrders.value = null
      try {
        await reloadOrders()
      } catch (reloadError) {
        Toast.error(getErrorMessage(reloadError, `重新加载${options.resourceLabel}顺序失败`))
      }
    } finally {
      syncingReorder.value = false
    }
  }

  return {
    items: items as ComputedRef<T[]>,
    orders,
    orderVersion,
    syncingReorder,
    applyOrderSnapshot,
    reloadOrders,
    saveOrderMap,
    reorder,
  }
}
