import { type InjectionRuleReadModel, MODE_OPTIONS, POSITION_OPTIONS } from '@/types'
import type { OrderMap } from '@/apis'

export function modeLabel(mode: string) {
  return MODE_OPTIONS.find((o) => o.value === mode)?.label ?? mode
}

export function positionLabel(pos?: string) {
  if (!pos) return ''
  return POSITION_OPTIONS.find((o) => o.value === pos)?.label ?? pos
}
export function rulePreview(rule: InjectionRuleReadModel) {
  return `${modeLabel(rule.mode)} · ${positionLabel(rule.position)}`
}

export function codePreview(code: string) {
  const t = code.replace(/\s+/g, ' ').trim()
  return t.length > 55 ? t.slice(0, 55) + '...' : t
}

export function uniqueStrings(values: string[]) {
  const seen = new Set<string>()
  return values.map((v) => v.trim()).filter((v) => v && !seen.has(v) && seen.add(v))
}

export function displayNameOf(item: { id: string; name?: string | null }) {
  const trimmed = item.name?.trim()
  return trimmed || item.id
}

/**
 * why: 排序映射只保存“显式排过序”的项；未出现的资源默认按 0 处理并排到前面，
 * 同值时再按名称字符序、最后按 id 稳定排序，确保前端即时排序与后端回存后的顺序契约一致。
 */
export function sortByOrderMap<T extends { id: string; name?: string | null }>(
  items: T[],
  orders: OrderMap,
) {
  return items
    .map((item) => ({ item }))
    .sort((a, b) => {
      const aOrder = Number.isFinite(orders[a.item.id]) ? orders[a.item.id] : 0
      const bOrder = Number.isFinite(orders[b.item.id]) ? orders[b.item.id] : 0
      if (aOrder !== bOrder) {
        return aOrder - bOrder
      }
      const nameCompare = displayNameOf(a.item).localeCompare(displayNameOf(b.item), undefined, {
        numeric: true,
        sensitivity: 'base',
      })
      if (nameCompare !== 0) {
        return nameCompare
      }
      return a.item.id.localeCompare(b.item.id, undefined, {
        numeric: true,
        sensitivity: 'base',
      })
    })
    .map(({ item }) => item)
}

/**
 * why: 一旦用户手动拖拽过顺序，就把当前整组列表固化成显式的 1..n，
 * 这样后续新增项仍会以默认 0 顶到前面，而当前这批已排序项能稳定保持拖拽结果。
 */
export function buildExplicitOrderMap<T extends { id: string }>(items: T[]): OrderMap {
  return items.reduce<OrderMap>((orders, item, index) => {
    orders[item.id] = index + 1
    return orders
  }, {})
}

export function sortSelectedFirst<T extends { id: string }>(items: T[], selectedIds: string[]) {
  const selected = new Set(selectedIds)
  return [...items].sort((a, b) => {
    const aSelected = selected.has(a.id)
    const bSelected = selected.has(b.id)
    if (aSelected === bSelected) return 0
    return aSelected ? -1 : 1
  })
}
