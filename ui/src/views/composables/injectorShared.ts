import type { AxiosError } from 'axios'
import type { Metadata } from '@halo-dev/api-client'
import type { ItemList } from '@/types'

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

export function mergeSavedMetadata<T extends { metadata: Metadata }>(draft: T, saved: T): Metadata {
  return {
    ...draft.metadata,
    ...saved.metadata,
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
