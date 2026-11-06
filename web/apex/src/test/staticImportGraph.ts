// Walks the STATIC import graph of the apex sources (dynamic `import()` is a chunk boundary and is
// not followed), so a test can assert what the first chunk can reach. Used by BundleSplit.test.ts.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

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
        .map(s => s.trim())
        .filter(s => s.length > 0)
        .every(s => s.startsWith('type '))
    out.push({ specifier: m[3]!, typeOnly: m[1] !== undefined || allTypes })
  }
  return out
}

function resolveRelative(from: string, specifier: string): string | null {
  const base = resolve(dirname(from), specifier)
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) {
    if (existsSync(candidate) && !candidate.endsWith('/')) {
      try {
        readFileSync(candidate)
        return candidate
      } catch {
        // a directory
      }
    }
  }
  return null
}

/**
 * Every bare package specifier reachable from `entry` through static value imports, mapped to one
 * import chain that reaches it (for the failure message).
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
      if (edge.specifier.startsWith('.')) {
        const next = resolveRelative(file, edge.specifier)
        if (next !== null) queue.push({ file: next, chain: [...chain, next] })
      } else if (!found.has(edge.specifier)) {
        found.set(edge.specifier, chain)
      }
    }
  }
  return found
}
