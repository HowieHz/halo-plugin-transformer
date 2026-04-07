import type { AxiosError } from 'axios'
import type { ItemList, ResourceReadMetadata, ResourceWriteMetadata } from '@/types'

export type ReorderPlacement = 'before' | 'after'

export function emptyList<T>(): ItemList<T> {
  return {
    first: false,
    hasNext: false,
    hasPrevious: false,
    last: false,
    page: 0,
    size: 0,
    totalPages: 0,
    items: [],
    total: 0,
  }
}

export function getErrorMessage(error: unknown, fallback: string) {
  const axiosError = error as AxiosError<{
    message?: string
    detail?: string
    error?: { message?: string }
  }>
  return (
    axiosError?.response?.data?.message ||
    axiosError?.response?.data?.detail ||
    axiosError?.response?.data?.error?.message ||
    fallback
  )
}

export function replaceItemInList<T extends { id: string }>(
  list: ItemList<T>,
  updated: T,
): ItemList<T> {
  return {
    ...list,
    items: list.items.map((item) => (item.id === updated.id ? updated : item)),
  }
}

/**
 * why: 启停接口返回的是“最新已保存快照”；编辑器这里只应同步资源标识与版本，
 * 不能把 read-model 里的其它 metadata 字段整包并回草稿，否则读写边界会再次变宽。
 */
export function mergeSavedMetadata(
  draft: ResourceWriteMetadata,
  saved: ResourceReadMetadata,
): ResourceWriteMetadata {
  return {
    ...draft,
    name: saved.name,
    version: saved.version ?? null,
  }
}

export function reorderItems<T extends { id: string }>(
  items: T[],
  sourceId: string,
  targetId: string,
  placement: ReorderPlacement,
) {
  if (sourceId === targetId) {
    return null
  }

  const ordered = [...items]
  const sourceIndex = ordered.findIndex((item) => item.id === sourceId)
  const targetIndex = ordered.findIndex((item) => item.id === targetId)
  if (sourceIndex < 0 || targetIndex < 0) {
    return null
  }

  const [moving] = ordered.splice(sourceIndex, 1)
  const nextTargetIndex = ordered.findIndex((item) => item.id === targetId)
  const insertIndex = placement === 'before' ? nextTargetIndex : nextTargetIndex + 1
  ordered.splice(insertIndex, 0, moving)
  return ordered
}
