// Guards the one thing that decides whether any of the other apex tests ever
// run: `.github/workflows/web.yml`'s `paths:` filter.
//
// `:tenshu-sdk-js` compiles ~33 KMP modules into the bundle the apex imports, and
// the workflow used to name 11 of them. The other 22 could regress the bundle
// with no web build in between — four commits in one three-day window did exactly
// that, all in `sync-service-proxy` / `storage-service-proxy`, i.e. schema
// evolution and the entitlement→figures mapping the storage gauge reads.
// See `docs/proposals/apex-currency-sweep.md` F1.
//
// ⚠⚠ A curated list is what failed. This test makes the workflow's list DERIVED
// from `tenshu-sdk-js/build.gradle.kts` — the file that necessarily changes when
// a dependency is added, and which is itself matched by `tenshu-sdk-js/**`. So a
// new `api(project(":…"))` always triggers the run that fails this test. The
// guard cannot be outrun by the change it exists to catch.
//
// ⚠ Deliberately asserts COVERAGE, not equality: `web/**`, `tenshu-sdk-js/**` and
// the workflow's own path legitimately appear in the filter and name no module,
// and a future entry for some module outside the bundle graph is a judgement call
// this test has no business vetoing.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
const GRADLE_FILE = resolve(REPO_ROOT, 'tenshu-sdk-js/build.gradle.kts')
const WORKFLOW_FILE = resolve(REPO_ROOT, '.github/workflows/web.yml')

/** Every `project(":name")` the JS SDK module declares — the bundle's real graph. */
function declaredModules(): string[] {
  const source = readFileSync(GRADLE_FILE, 'utf8')
  const found = [...source.matchAll(/project\(":([a-z0-9-]+)"\)/g)].map(m => m[1]!)
  return [...new Set(found)].sort()
}

/**
 * The `paths:` entries of each trigger block, in file order. Hand-rolled rather
 * than pulled through a YAML dependency: the shape is two fixed `paths:` lists of
 * `- "…"` scalars, and a parser we can read start to finish beats a dependency
 * added for one test.
 */
function pathFilters(): string[][] {
  const lines = readFileSync(WORKFLOW_FILE, 'utf8').split('\n')
  const blocks: string[][] = []
  let current: string[] | null = null
  for (const line of lines) {
    if (/^\s*paths:\s*$/.test(line)) {
      current = []
      blocks.push(current)
      continue
    }
    if (current === null) continue
    const entry = /^\s*-\s*"([^"]+)"\s*$/.exec(line)
    if (entry) {
      current.push(entry[1]!)
      continue
    }
    // A comment or blank line inside the list is still part of it; anything else
    // ends the block.
    if (!/^\s*(#.*)?$/.test(line)) current = null
  }
  return blocks
}

/** Does `entry` (a workflow path glob) cover every file under `module/`? */
function covers(entry: string, module: string): boolean {
  if (!entry.endsWith('/**')) return false
  const dir = entry.slice(0, -'/**'.length)
  if (!dir.includes('*')) return dir === module
  // Support the `timeline-service*` style too, so this test constrains coverage
  // without also dictating the workflow's spelling.
  const pattern = new RegExp(`^${dir.split('*').map(escapeRegExp).join('[^/]*')}$`)
  return pattern.test(module)
}

function escapeRegExp(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

describe('web CI path filter', () => {
  it('finds both trigger blocks', () => {
    // If this fails the workflow was restructured and the two assertions below
    // are silently vacuous — which is exactly how the original gap survived.
    expect(pathFilters()).toHaveLength(2)
  })

  it('reads a non-empty module list out of the SDK build file', () => {
    // Same reasoning: a regex that matched nothing would make every coverage
    // assertion trivially true.
    expect(declaredModules().length).toBeGreaterThan(20)
  })

  it.each([
    ['push', 0],
    ['pull_request', 1],
  ])('covers every module the JS bundle depends on (%s)', (_trigger, index) => {
    const entries = pathFilters()[index]!
    const missing = declaredModules().filter(m => !entries.some(e => covers(e, m)))
    expect(
      missing,
      `these modules are compiled into the apex bundle but changing them would ` +
        `not run Web CI: ${missing.join(', ')}`,
    ).toEqual([])
  })

  it('applies the same filter to push and pull_request', () => {
    const [push, pull] = pathFilters()
    expect(new Set(pull), 'the PR filter must not be narrower than the push filter')
      .toEqual(new Set(push))
  })
})
