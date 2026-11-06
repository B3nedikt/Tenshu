/// <reference types="node" />
// Walks the STATIC import graph of TypeScript sources (dynamic `import()` is a chunk boundary and is
// not followed), so a test can assert what a bundle can reach. The apex's
// `src/test/staticImportGraph.ts` is the original; the apex does not depend on the app-kit (ADR
// 0020: it is the host, not an app), so this is a copy that also follows `@tenshu/app-kit/*`.
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ⚠ The clause excludes quotes, so `import './i18n'` (no `from`) cannot be swallowed into the next
// import's clause and hide its edge.
const IMPORT_RE =
  /^\s*(?:import|export)\s+(type\s+)?([^'"`;]*?)\s*from\s*['"]([^'"]+)['"]|^\s*import\s+['"]([^'"]+)['"]/gm

interface Edge {
  readonly specifier: string
  readonly typeOnly: boolean
}

function edges(source: string): Edge[] {
  const out: Edge[] = []
  for (const m of source.matchAll(IMPORT_RE)) {
    if (m[4]) {
      out.push({ specifier: m[4], typeOnly: false })
      continue
    }
    const clause = m[2] ?? ''
    // `import { type A, type B }` erases entirely; one value specifier keeps the edge.
    const braced = /^\{([\s\S]*)\}$/.exec(clause.trim())
    const allTypes =
      braced !== null &&
      braced[1]!
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
        .every((s) => s.startsWith('type '))
    out.push({ specifier: m[3]!, typeOnly: m[1] !== undefined || allTypes })
  }
  return out
}

function resolveFile(base: string): string | null {
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) {
    if (existsSync(candidate) && statSync(candidate).isFile()) return candidate
  }
  return null
}

// `@tenshu/app-kit/<entry>` → its source file, read from this package's `exports` map.
const KIT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const KIT_EXPORTS = (
  JSON.parse(readFileSync(resolve(KIT_ROOT, 'package.json'), 'utf8')) as { exports: Record<string, string> }
).exports

function resolveKit(specifier: string): string | null {
  const entry = `.${specifier.slice('@tenshu/app-kit'.length)}`
  const target = KIT_EXPORTS[entry]
  return target && /\.tsx?$/.test(target) ? resolve(KIT_ROOT, target) : null
}

/**
 * Every bare package specifier reachable from `entry` through static value imports, mapped to one
 * import chain that reaches it (for the failure message). Relative imports and `@tenshu/app-kit/*`
 * are followed; other packages are leaves.
 */
export function reachablePackages(entry: string): Map<string, string[]> {
  const found = new Map<string, string[]>()
  const seen = new Set<string>()
  const queue: Array<{ file: string; chain: string[] }> = [{ file: entry, chain: [entry] }]
  while (queue.length > 0) {
    const { file, chain } = queue.shift()!
    if (seen.has(file)) continue
    seen.add(file)
    for (const edge of edges(readFileSync(file, 'utf8'))) {
      if (edge.typeOnly) continue
      const next = edge.specifier.startsWith('.')
        ? resolveFile(resolve(dirname(file), edge.specifier))
        : edge.specifier.startsWith('@tenshu/app-kit/')
          ? resolveKit(edge.specifier)
          : null
      if (next !== null) queue.push({ file: next, chain: [...chain, next] })
      else if (!edge.specifier.startsWith('.') && !found.has(edge.specifier)) {
        found.set(edge.specifier, chain)
      }
    }
  }
  return found
}

/**
 * The `@tenshu/sdk` value imports reachable from `entry` other than `@tenshu/sdk/bridge`, each with
 * the chain that reaches it. ⚠⚠ Must be empty for every app and for the app-kit: any other entry
 * re-exports the Kotlin bundle (`web/sdk/src/_bundle.ts`), so one value import ships the whole
 * runtime to the app (web-app-suite.md §7).
 */
export function forbiddenSdkImports(entry: string): Array<[string, string]> {
  return [...reachablePackages(entry)]
    .filter(([specifier]) => specifier.startsWith('@tenshu/sdk') && specifier !== '@tenshu/sdk/bridge')
    .map(([specifier, chain]) => [specifier, chain.join(' -> ')])
}
