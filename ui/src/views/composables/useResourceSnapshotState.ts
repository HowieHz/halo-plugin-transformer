import { Toast } from "@halo-dev/components";
import { computed, ref, shallowRef, type ComputedRef } from "vue";

import type { PersistedOrderState, OrderMap } from "@/apis";
import type { OrderedItemList } from "@/types";

import { buildExplicitOrderMap, sortByOrderMap, type ReorderPlacement } from "./resourceOrder";
import { emptyList, getErrorMessage } from "./resourceSupport";

interface SnapshotApi<T> {
  getSnapshot: () => Promise<{ data: OrderedItemList<T> }>;
  updateOrder: (
    orders: OrderMap,
    version: number | null | undefined,
  ) => Promise<{ data: PersistedOrderState }>;
}

interface UseResourceSnapshotStateOptions<T extends { id: string }> {
  api: SnapshotApi<T>;
  resourceLabel: string;
}

function emptyOrderedList<T>(): OrderedItemList<T> {
  return {
    ...emptyList<T>(),
    orders: {},
    orderVersion: null,
  };
}

function cloneSnapshot<T>(snapshot: OrderedItemList<T>): OrderedItemList<T> {
  return {
    ...snapshot,
    items: [...snapshot.items],
    orders: { ...snapshot.orders },
  };
}

/**
 * why: 左侧资源列表真正的 authority 是一个完整 snapshot：
 * `items + orders + orderVersion` 必须同进同退；把它们封在同一个 composable 里，
 * 才不会在调用方手里再次被拆回多份隐式状态。
 */
export function useResourceSnapshotState<T extends { id: string; name?: string | null }>(
  options: UseResourceSnapshotStateOptions<T>,
) {
  const snapshot = shallowRef<OrderedItemList<T>>(emptyOrderedList<T>());
  const hasLoaded = ref(false);
  const pendingRefreshCount = ref(0);
  const syncingReorder = ref(false);
  const pendingOrders = ref<OrderMap | null>(null);

  const items = computed(() => sortByOrderMap(snapshot.value.items, snapshot.value.orders));
  const refreshing = computed(() => pendingRefreshCount.value > 0);

  function applySnapshot(nextSnapshot: OrderedItemList<T>) {
    snapshot.value = cloneSnapshot(nextSnapshot);
    hasLoaded.value = true;
  }

  function applyOrderSnapshot(nextOrderState: PersistedOrderState) {
    snapshot.value = {
      ...snapshot.value,
      orders: { ...nextOrderState.orders },
      orderVersion: nextOrderState.version,
    };
  }

  function replacePersistedItem(updated: T) {
    snapshot.value = {
      ...snapshot.value,
      items: snapshot.value.items.map((item) => (item.id === updated.id ? updated : item)),
    };
  }

  async function reloadSnapshot() {
    pendingRefreshCount.value += 1;
    try {
      const response = await options.api.getSnapshot();
      applySnapshot(response.data);
    } finally {
      pendingRefreshCount.value -= 1;
    }
  }

  async function restoreSnapshotAfterFailedSave(previousSnapshot: OrderedItemList<T>) {
    try {
      await reloadSnapshot();
    } catch {
      snapshot.value = cloneSnapshot(previousSnapshot);
    }
  }

  async function persistOrderSnapshot(
    nextOrders: OrderMap,
    persistOptions: {
      errorMessage: string;
    },
  ) {
    const previousSnapshot = cloneSnapshot(snapshot.value);
    applyOrderSnapshot({
      orders: nextOrders,
      version: previousSnapshot.orderVersion,
    });
    try {
      const response = await options.api.updateOrder(nextOrders, previousSnapshot.orderVersion);
      applyOrderSnapshot(response.data);
      return true;
    } catch (error) {
      await restoreSnapshotAfterFailedSave(previousSnapshot);
      return getErrorMessage(error, persistOptions.errorMessage);
    }
  }

  async function saveOrderMap(nextItems: T[]) {
    const nextOrders = buildExplicitOrderMap(nextItems);
    return persistOrderSnapshot(nextOrders, {
      errorMessage: `${options.resourceLabel}顺序保存失败`,
    });
  }

  async function flushPendingReorders() {
    let updatedOnce = false;
    while (pendingOrders.value) {
      const nextOrders = pendingOrders.value;
      pendingOrders.value = null;
      const result = await persistOrderSnapshot(nextOrders, {
        errorMessage: "更新顺序失败",
      });
      if (result !== true) {
        Toast.error(result);
        return;
      }
      updatedOnce = true;
    }
    if (updatedOnce) {
      Toast.success(`${options.resourceLabel}顺序保存成功`);
    }
  }

  async function reorder(payload: {
    sourceId: string;
    targetId: string;
    placement: ReorderPlacement;
  }) {
    const orderedItems = reorderItems(
      items.value,
      payload.sourceId,
      payload.targetId,
      payload.placement,
    );
    if (!orderedItems) {
      return;
    }

    applyOrderSnapshot({
      orders: buildExplicitOrderMap(orderedItems),
      version: snapshot.value.orderVersion,
    });
    pendingOrders.value = { ...snapshot.value.orders };
    if (syncingReorder.value) {
      return;
    }

    syncingReorder.value = true;
    try {
      await flushPendingReorders();
    } catch (error) {
      pendingOrders.value = null;
      Toast.error(getErrorMessage(error, "更新顺序失败"));
    } finally {
      syncingReorder.value = false;
    }
  }

  return {
    snapshot,
    hasLoaded,
    items: items as ComputedRef<T[]>,
    refreshing,
    syncingReorder,
    applySnapshot,
    applyOrderSnapshot,
    replacePersistedItem,
    reloadSnapshot,
    saveOrderMap,
    reorder,
  };
}

function reorderItems<T extends { id: string }>(
  items: T[],
  sourceId: string,
  targetId: string,
  placement: ReorderPlacement,
) {
  if (sourceId === targetId) {
    return null;
  }

  const ordered = [...items];
  const sourceIndex = ordered.findIndex((item) => item.id === sourceId);
  const targetIndex = ordered.findIndex((item) => item.id === targetId);
  if (sourceIndex < 0 || targetIndex < 0) {
    return null;
  }

  const [moving] = ordered.splice(sourceIndex, 1);
  const nextTargetIndex = ordered.findIndex((item) => item.id === targetId);
  const insertIndex = placement === "before" ? nextTargetIndex : nextTargetIndex + 1;
  ordered.splice(insertIndex, 0, moving);
  return ordered;
}
