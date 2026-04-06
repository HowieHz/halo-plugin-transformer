import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
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
  name: string
  input: unknown
  shared?: SharedExpectation
  ts?: SideExpectation
}

interface MatchRuleContractSuite {
  version: number
  cases: MatchRuleContractCase[]
}

const contractSuite = loadContractSuite()

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
})

function loadContractSuite(): MatchRuleContractSuite {
  const fixturePath = locateContractFixture()
  return JSON.parse(readFileSync(fixturePath, 'utf8')) as MatchRuleContractSuite
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

function locateContractFixture(): string {
  let current = path.dirname(fileURLToPath(import.meta.url))

  while (true) {
    const candidate = path.join(current, 'contracts', 'match-rule-contracts.json')
    if (tryReadFixture(candidate)) {
      return candidate
    }

    const parent = path.dirname(current)
    if (parent === current) {
      throw new Error('Cannot find contracts/match-rule-contracts.json')
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
