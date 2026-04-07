import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { MATCH_RULE_ENUM_SPECS, MATCH_RULE_NODE_SPECS } from '../matchRuleContract.generated'
import { parseMatchRuleDraft, supportsDomPathPrecheck } from '../matchRule'

interface WriteValidationExpectation {
  ok?: boolean
  relativePath?: string
  message?: string
}

interface SideExpectation {
  writeValidation: WriteValidationExpectation
}

interface SharedExpectation {
  domPathPrecheck?: boolean
  writeValidation?: WriteValidationExpectation
}

interface MatchRuleContractCase {
  covers?: string[]
  name: string
  input: unknown
  shared?: SharedExpectation
  ts?: SideExpectation
}

interface MatchRuleContractSuite {
  version: number
  cases: MatchRuleContractCase[]
}

interface MatchRuleContractChecklistItem {
  id: string
  layer: 'shared_contract' | 'frontend_only'
  summary: string
}

interface MatchRuleContractChecklistSuite {
  version: number
  items: MatchRuleContractChecklistItem[]
}

interface MatchRuleContractMetadata {
  version: number
  nodeTypes: typeof MATCH_RULE_NODE_SPECS
  enumValues: typeof MATCH_RULE_ENUM_SPECS
  checklist: MatchRuleContractChecklistItem[]
}

const contractSuite = loadJsonFile<MatchRuleContractSuite>('match-rule-contracts.json')
const checklistSuite = loadJsonFile<MatchRuleContractChecklistSuite>(
  'match-rule-contract-checklist.json',
)
const metadata = loadJsonFile<MatchRuleContractMetadata>('match-rule-contract-metadata.json')

describe('matchRule contract fixtures', () => {
  // why: 规则树在前后端各维护一份实现；这里复用共享样例，强制锁住 TS 端对同一批输入的严格写入语义。
  for (const contractCase of contractSuite.cases) {
    it(`matches write validation contract: ${contractCase.name}`, () => {
      const result = parseMatchRuleDraft(JSON.stringify(contractCase.input))
      const expectation = resolveWriteValidation(contractCase, '$')

      if (expectation.ok) {
        expect(result.error).toBeNull()
        expect(result.rule).not.toBeNull()
        return
      }

      expect(result.error).not.toBeNull()
      expect(result.error?.path).toBe(expectation.path)
      expect(result.error?.message).toBe(expectation.message)
    })
  }

  // why: DOM 路径预筛提示必须与后端运行期判断共享同一批样例，否则性能警告很容易再次读旧状态或发生语义漂移。
  for (const contractCase of contractSuite.cases.filter(
    (item) => typeof item.shared?.domPathPrecheck === 'boolean',
  )) {
    it(`matches dom path precheck contract: ${contractCase.name}`, () => {
      const result = parseMatchRuleDraft(JSON.stringify(contractCase.input))

      expect(result.error).toBeNull()
      expect(result.rule).not.toBeNull()
      expect(supportsDomPathPrecheck(result.rule)).toBe(contractCase.shared?.domPathPrecheck)
    })
  }

  // why: README 里列出来的共享 match-rule 语义，必须在 checklist 里落成可追踪项，
  // 并至少有一条 contract fixture 覆盖；否则文档一长，测试很容易漏补。
  it('covers every shared-contract checklist item with at least one fixture case', () => {
    const knownChecklistIds = new Set(checklistSuite.items.map((item) => item.id))
    const sharedChecklistIds = new Set(
      checklistSuite.items
        .filter((item) => item.layer === 'shared_contract')
        .map((item) => item.id),
    )
    const coveredIds = new Set(
      contractSuite.cases.flatMap((contractCase) => contractCase.covers ?? []),
    )

    expect([...coveredIds].filter((id) => !knownChecklistIds.has(id))).toEqual([])
    expect([...sharedChecklistIds].filter((id) => !coveredIds.has(id))).toEqual([])
  })

  // why: 允许字段集合与错误文本模板已经改成由共享 metadata 生成；
  // 这里直接对照源 metadata，防止以后只改了 metadata 却忘了同步生成前端 helper。
  it('keeps generated frontend match-rule metadata in sync', () => {
    expect(MATCH_RULE_NODE_SPECS).toEqual(metadata.nodeTypes)
    expect(MATCH_RULE_ENUM_SPECS).toEqual(metadata.enumValues)
    expect(checklistSuite.items).toEqual(metadata.checklist)
  })
})

function loadJsonFile<T>(fileName: string): T {
  return JSON.parse(readFileSync(locateContractFixture(fileName), 'utf8')) as T
}

function resolveWriteValidation(contractCase: MatchRuleContractCase, rootPath: string) {
  const sharedExpectation = contractCase.shared?.writeValidation
  const runtimeExpectation = contractCase.ts?.writeValidation

  const ok = runtimeExpectation?.ok ?? sharedExpectation?.ok
  if (typeof ok !== 'boolean') {
    throw new Error(`Missing writeValidation.ok in contract case: ${contractCase.name}`)
  }

  const relativePath = runtimeExpectation?.relativePath ?? sharedExpectation?.relativePath
  const message = runtimeExpectation?.message ?? sharedExpectation?.message

  return {
    ok,
    path: formatRuntimePath(rootPath, relativePath),
    message,
  }
}

function formatRuntimePath(rootPath: string, relativePath?: string) {
  if (!relativePath?.trim()) {
    return rootPath
  }
  return `${rootPath}.${relativePath}`
}

function locateContractFixture(fileName: string): string {
  let current = path.dirname(fileURLToPath(import.meta.url))

  while (true) {
    const candidate = path.join(current, 'contracts', fileName)
    if (tryReadFixture(candidate)) {
      return candidate
    }

    const parent = path.dirname(current)
    if (parent === current) {
      throw new Error(`Cannot find contracts/${fileName}`)
    }
    current = parent
  }
}

function tryReadFixture(candidate: string): boolean {
  try {
    return readFileSync(candidate, 'utf8').length > 0
  } catch {
    return false
  }
}
