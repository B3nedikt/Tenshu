// The Kotlin ↔ TS pins for `@tenshu/sdk/home`. `MAX_ACTIVITY_PAGE_SIZE` in
// `web/sdk/src/home.ts` is a copy of the Kotlin `internal const` that `Home.activityPage` enforces; the
// gateway validates against the TS copy, so a drift would let the gateway pass a page size the runtime
// refuses (or refuse one it allows). The same holds for the catalogue keys `HomeErrorKind` names.
//
// ⚠ Read as source text, not imported: `@tenshu/sdk/home` value-imports the Kotlin/JS bundle, and
// every apex suite mocks it. Same approach as `fitDataSourceStates.test.ts`.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
const KOTLIN = resolve(REPO_ROOT, 'tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/HomeBook.kt')
const TS = resolve(REPO_ROOT, 'web/sdk/src/home.ts')

function one(source: string, pattern: RegExp, what: string): string {
  const value = source.match(pattern)?.[1]
  if (value === undefined) throw new Error(`${what} not found`)
  return value
}

describe('Home SDK constants', () => {
  const kotlin = readFileSync(KOTLIN, 'utf8')
  const ts = readFileSync(TS, 'utf8')

  it('MAX_ACTIVITY_PAGE_SIZE is the Kotlin constant Home.activityPage enforces', () => {
    const k = Number(one(kotlin, /internal const val MAX_ACTIVITY_PAGE_SIZE = (\d+)/, 'Kotlin MAX_ACTIVITY_PAGE_SIZE'))
    const t = Number(one(ts, /export const MAX_ACTIVITY_PAGE_SIZE = (\d+)/, 'TS MAX_ACTIVITY_PAGE_SIZE'))
    expect(k).toBeGreaterThan(0)
    expect(t).toBe(k)
  })

  it('HomeErrorKind names exactly the keys HomeWebErrorKeys mints', () => {
    const block = one(kotlin, /internal object HomeWebErrorKeys \{([\s\S]*?)\n\}/, 'HomeWebErrorKeys')
    const k = [...block.matchAll(/ErrorCopyKey\("([^"]+)"\)/g)].map((m) => m[1]).sort()
    const kinds = one(ts, /export const HomeErrorKind = \{([\s\S]*?)\} as const/, 'HomeErrorKind')
    const t = [...kinds.matchAll(/'(error_home_[a-z_]+)'/g)].map((m) => m[1]).sort()
    expect(k.length).toBeGreaterThanOrEqual(4)
    expect(t).toEqual(k)
  })
})
