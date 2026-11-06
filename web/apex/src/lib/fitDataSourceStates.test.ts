// The TS half of the Fit data-source state pin: `FitDataSource.state` in
// `@tenshu/sdk/fit` must be exactly the set the Kotlin bridge emits. The Kotlin half,
// `FitDataSourceStateTest` (jsTest), iterates `FitDataSourceStateName.entries` and checks every
// `DataSourceState` case maps onto it; this one diffs that enum against `FIT_DATA_SOURCE_STATES`.
//
// ⚠ Read as source text, not imported: `@tenshu/sdk/fit` value-imports the Kotlin/JS bundle, and
// every apex suite mocks it. Same approach as `test/webCiPaths.test.ts`.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
const KOTLIN = resolve(REPO_ROOT, 'tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/FitBook.kt')
const TS = resolve(REPO_ROOT, 'web/sdk/src/fit.ts')

function names(source: string, pattern: RegExp, what: string): string[] {
  const body = source.match(pattern)?.[1]
  if (body === undefined) throw new Error(`${what} not found`)
  return [...body.matchAll(/[A-Za-z]+/g)].map((m) => m[0])
}

describe('Fit data-source states', () => {
  it('FIT_DATA_SOURCE_STATES is exactly the Kotlin FitDataSourceStateName enum, in order', () => {
    const kotlin = names(
      readFileSync(KOTLIN, 'utf8'),
      /enum class FitDataSourceStateName \{([^}]*)\}/,
      'FitDataSourceStateName',
    )
    const ts = names(readFileSync(TS, 'utf8'), /FIT_DATA_SOURCE_STATES = \[([^\]]*)\] as const/, 'FIT_DATA_SOURCE_STATES')
    expect(kotlin.length).toBeGreaterThanOrEqual(9)
    expect(ts).toEqual(kotlin)
  })
})
