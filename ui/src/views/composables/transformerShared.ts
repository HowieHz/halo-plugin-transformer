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
