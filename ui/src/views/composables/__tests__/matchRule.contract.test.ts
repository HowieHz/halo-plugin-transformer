import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { MATCH_RULE_ENUM_SPECS, MATCH_RULE_NODE_SPECS } from '../matchRuleContract.generated'
import { matchRuleSummary, parseMatchRuleDraft, supportsDomPathPrecheck } from '../matchRule'

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
  minimizedSummary?: string
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

interface MatchRuleContractSpec {
  version: number
  nodeTypes: typeof MATCH_RULE_NODE_SPECS
  enumValues: typeof MATCH_RULE_ENUM_SPECS
  checklist: MatchRuleContractChecklistItem[]
}

const contractSuite = loadJsonFile<MatchRuleContractSuite>('contract.cases.jsonc')
const spec = loadJsonFile<MatchRuleContractSpec>('contract.spec.jsonc')

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

  // why: 布尔最小化已经升级为共享契约；这里直接吃同一批 spec cases，
  // 防止前端分析表达式和后端运行时最小化再次各走各的。
  for (const contractCase of contractSuite.cases.filter(
    (item) => typeof item.shared?.minimizedSummary === 'string',
  )) {
    it(`matches boolean minimization contract: ${contractCase.name}`, () => {
      const result = parseMatchRuleDraft(JSON.stringify(contractCase.input))

      expect(result.error).toBeNull()
      expect(result.rule).not.toBeNull()
      expect(matchRuleSummary(result.rule!)).toBe(contractCase.shared?.minimizedSummary)
    })
  }

  // why: README 里列出来的共享 match-rule 语义，必须在 checklist 里落成可追踪项，
  // 并至少有一条 contract fixture 覆盖；否则文档一长，测试很容易漏补。
  it('covers every shared-contract checklist item with at least one fixture case', () => {
    const knownChecklistIds = new Set(spec.checklist.map((item) => item.id))
    const sharedChecklistIds = new Set(
      spec.checklist.filter((item) => item.layer === 'shared_contract').map((item) => item.id),
    )
    const coveredIds = new Set(
      contractSuite.cases.flatMap((contractCase) => contractCase.covers ?? []),
    )

    expect([...coveredIds].filter((id) => !knownChecklistIds.has(id))).toEqual([])
    expect([...sharedChecklistIds].filter((id) => !coveredIds.has(id))).toEqual([])
  })

  // why: 允许字段集合与错误文本模板已经改成由共享 spec 生成；
  // 这里直接对照源 spec，防止以后只改了源定义却忘了同步生成前端 helper。
  it('keeps generated frontend match-rule metadata in sync', () => {
    expect(MATCH_RULE_NODE_SPECS).toEqual(spec.nodeTypes)
    expect(MATCH_RULE_ENUM_SPECS).toEqual(spec.enumValues)
  })
})

function loadJsonFile<T>(fileName: string): T {
  return JSON.parse(normalizeJsonc(readFileSync(locateMatchRuleSpecFile(fileName), 'utf8'))) as T
}

function normalizeJsonc(content: string) {
  return stripTrailingCommas(stripJsonComments(content))
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

function locateMatchRuleSpecFile(fileName: string): string {
  let current = path.dirname(fileURLToPath(import.meta.url))

  while (true) {
    const candidate = path.join(current, 'specs', 'match-rule', fileName)
    if (tryReadFixture(candidate)) {
      return candidate
    }

    const parent = path.dirname(current)
    if (parent === current) {
      throw new Error(`Cannot find specs/match-rule/${fileName}`)
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

function stripJsonComments(content: string) {
  let result = ''
  let inString = false
  let isEscaped = false
  let inLineComment = false
  let inBlockComment = false

  for (let index = 0; index < content.length; index += 1) {
    const char = content[index]
    const next = content[index + 1]

    if (inLineComment) {
      if (char === '\n') {
        inLineComment = false
        result += char
      }
      continue
    }

    if (inBlockComment) {
      if (char === '*' && next === '/') {
        inBlockComment = false
        index += 1
      }
      continue
    }

    if (inString) {
      result += char
      if (isEscaped) {
        isEscaped = false
      } else if (char === '\\') {
        isEscaped = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }

    if (char === '/' && next === '/') {
      inLineComment = true
      index += 1
      continue
    }

    if (char === '/' && next === '*') {
      inBlockComment = true
      index += 1
      continue
    }

    if (char === '"') {
      inString = true
    }

    result += char
  }

  return result
}

function stripTrailingCommas(content: string) {
  let result = ''
  let inString = false
  let isEscaped = false

  for (let index = 0; index < content.length; index += 1) {
    const char = content[index]

    if (inString) {
      result += char
      if (isEscaped) {
        isEscaped = false
      } else if (char === '\\') {
        isEscaped = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }

    if (char === '"') {
      inString = true
      result += char
      continue
    }

    if (char === ',') {
      let lookahead = index + 1
      while (lookahead < content.length && /\s/.test(content[lookahead])) {
        lookahead += 1
      }
      if (content[lookahead] === '}' || content[lookahead] === ']') {
        continue
      }
    }

    result += char
  }

  return result
}
