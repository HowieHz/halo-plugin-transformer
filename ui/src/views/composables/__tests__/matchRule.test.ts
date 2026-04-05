// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { makeRule } from '@/types'
import {
  clearPersistedMatchRuleDraft,
  hydrateRuleForEditor,
  persistMatchRuleEditorState,
} from '../matchRule'

describe('matchRule editor state', () => {
  const storage = new Map<string, string>()

  beforeEach(() => {
    storage.clear()
    vi.stubGlobal('window', {
      localStorage: {
        getItem(key: string) {
          return storage.get(key) ?? null
        },
        setItem(key: string, value: string) {
          storage.set(key, value)
        },
        removeItem(key: string) {
          storage.delete(key)
        },
        clear() {
          storage.clear()
        },
      },
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // why: 用户点“放弃”后应回到已保存内容，不能继续被本地旧 JSON 草稿覆盖。
  it('clears persisted draft while keeping editor mode', () => {
    const savedRule = makeRule({
      id: 'rule-a',
      metadata: { name: 'rule-a', generateName: 'InjectionRule-' },
    })

    persistMatchRuleEditorState({
      id: savedRule.id,
      matchRuleDraft: '{ invalid json',
      matchRuleEditorMode: 'JSON',
    })

    clearPersistedMatchRuleDraft(savedRule.id, 'JSON')

    const hydrated = hydrateRuleForEditor(savedRule)

    expect(hydrated.matchRuleEditorMode).toBe('JSON')
    expect(hydrated.matchRuleDraft).toContain('"type": "GROUP"')
    expect(hydrated.matchRuleDraft).not.toBe('{ invalid json')
  })
})
