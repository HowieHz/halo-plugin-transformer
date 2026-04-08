import type { InjectionKey, Ref } from 'vue'
import type { MatchRule } from '@/types'
import { cloneMatchRule, normalizeMatchRule } from './matchRule'

export type MatchRuleNodePath = number[]
export type MatchRuleDropPlacement = 'before' | 'after' | 'inside'

export interface MatchRuleDragContext {
  draggingPath: Ref<MatchRuleNodePath | null>
  dropTargetPath: Ref<MatchRuleNodePath | null>
  dropPlacement: Ref<MatchRuleDropPlacement | null>
  startDrag: (path: MatchRuleNodePath, event: DragEvent) => void
  setDropTarget: (path: MatchRuleNodePath | null, placement: MatchRuleDropPlacement | null) => void
  clearDragState: () => void
  canDrop: (
    sourcePath: MatchRuleNodePath,
    targetPath: MatchRuleNodePath,
    placement: MatchRuleDropPlacement,
  ) => boolean
  moveNode: (
    sourcePath: MatchRuleNodePath,
    targetPath: MatchRuleNodePath,
    placement: MatchRuleDropPlacement,
  ) => void
}

export const MATCH_RULE_DRAG_CONTEXT_KEY: InjectionKey<MatchRuleDragContext> =
  Symbol('match-rule-drag-context')

export function moveMatchRuleNode(
  rootRule: MatchRule,
  sourcePath: MatchRuleNodePath,
  targetPath: MatchRuleNodePath,
  placement: MatchRuleDropPlacement,
): MatchRule | null {
  if (!canMoveMatchRuleNode(rootRule, sourcePath, targetPath, placement)) {
    return null
  }

  const nextRoot = cloneMatchRule(normalizeMatchRule(rootRule))
  const sourceParent = getGroupAtPath(nextRoot, sourcePath.slice(0, -1))
  if (!sourceParent?.children) {
    return null
  }

  const sourceIndex = sourcePath[sourcePath.length - 1]
  if (sourceIndex === undefined || sourceIndex < 0 || sourceIndex >= sourceParent.children.length) {
    return null
  }

  const [movingNode] = sourceParent.children.splice(sourceIndex, 1)
  if (!movingNode) {
    return null
  }

  const adjustedTargetPath = rebasePathAfterRemoval(sourcePath, targetPath)
  if (placement === 'inside') {
    const targetGroup = getGroupAtPath(nextRoot, adjustedTargetPath)
    if (!targetGroup) {
      return null
    }
    targetGroup.children = [...(targetGroup.children ?? []), movingNode]
    return nextRoot
  }

  const targetParent = getGroupAtPath(nextRoot, adjustedTargetPath.slice(0, -1))
  if (!targetParent?.children) {
    return null
  }

  const targetIndex = adjustedTargetPath[adjustedTargetPath.length - 1]
  if (targetIndex === undefined) {
    return null
  }

  const insertIndex = placement === 'before' ? targetIndex : targetIndex + 1
  targetParent.children.splice(insertIndex, 0, movingNode)
  return nextRoot
}

export function canMoveMatchRuleNode(
  rootRule: MatchRule,
  sourcePath: MatchRuleNodePath,
  targetPath: MatchRuleNodePath,
  placement: MatchRuleDropPlacement,
): boolean {
  if (!sourcePath.length) {
    return false
  }
  if (isSamePath(sourcePath, targetPath)) {
    return false
  }
  if (isPathPrefix(sourcePath, targetPath)) {
    return false
  }

  const root = normalizeMatchRule(rootRule)
  const sourceParent = getGroupAtPath(root, sourcePath.slice(0, -1))
  const sourceIndex = sourcePath[sourcePath.length - 1]
  if (!sourceParent?.children || sourceIndex === undefined || !sourceParent.children[sourceIndex]) {
    return false
  }

  if (placement === 'inside') {
    return getGroupAtPath(root, targetPath) !== null
  }

  const targetParent = getGroupAtPath(root, targetPath.slice(0, -1))
  const targetIndex = targetPath[targetPath.length - 1]
  return (
    !!targetParent?.children && targetIndex !== undefined && !!targetParent.children[targetIndex]
  )
}

export function isSamePath(left: MatchRuleNodePath | null, right: MatchRuleNodePath | null) {
  if (!left || !right || left.length !== right.length) {
    return false
  }
  return left.every((segment, index) => segment === right[index])
}

export function pathKey(path: MatchRuleNodePath | null) {
  return path?.join('.') ?? ''
}

function isPathPrefix(prefix: MatchRuleNodePath, path: MatchRuleNodePath) {
  if (prefix.length > path.length) {
    return false
  }
  return prefix.every((segment, index) => segment === path[index])
}

function getGroupAtPath(rootRule: MatchRule, path: MatchRuleNodePath): MatchRule | null {
  let current: MatchRule = rootRule
  for (const index of path) {
    if (current.type !== 'GROUP') {
      return null
    }
    const next = current.children?.[index]
    if (!next) {
      return null
    }
    current = next
  }
  return current.type === 'GROUP' ? current : null
}

function rebasePathAfterRemoval(
  sourcePath: MatchRuleNodePath,
  targetPath: MatchRuleNodePath,
): MatchRuleNodePath {
  const nextPath = [...targetPath]
  const sourceParentPath = sourcePath.slice(0, -1)
  const sourceIndex = sourcePath[sourcePath.length - 1]
  if (sourceIndex === undefined || nextPath.length <= sourceParentPath.length) {
    return nextPath
  }

  const sharesParent = sourceParentPath.every((segment, index) => segment === nextPath[index])
  if (sharesParent && nextPath[sourceParentPath.length] > sourceIndex) {
    nextPath[sourceParentPath.length] -= 1
  }
  return nextPath
}
