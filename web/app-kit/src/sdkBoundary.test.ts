// ⚠⚠ The app-kit may value-import `@tenshu/sdk/bridge` and nothing else from `@tenshu/sdk`: every
// other entry re-exports the Kotlin bundle, so one value import here would put the whole runtime
// (~1.7 MB) into every app built on the kit (web-app-suite.md §7, ADR 0020). Types are fine.
import { mkdtempSync, readdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { forbiddenSdkImports, reachablePackages } from './testing/importGraph'

const SRC = dirname(fileURLToPath(import.meta.url))

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return sources(path)
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : []
  })
}

describe('app-kit SDK boundary', () => {
  it('reaches no @tenshu/sdk value but /bridge from any of its files', () => {
    const files = sources(SRC)
    expect(files.length).toBeGreaterThan(10)
    // On failure: each forbidden entry with the chain that reaches it. Make the import `import type`.
    expect(files.flatMap((file) => forbiddenSdkImports(file))).toEqual([])
  })

  it('does reach @tenshu/sdk/bridge — the walker is not blind', () => {
    expect([...reachablePackages(resolve(SRC, 'bridge.ts')).keys()]).toContain('@tenshu/sdk/bridge')
  })

  it('flags a value import of another entry, and ignores a type-only one', () => {
    const dir = mkdtempSync(join(tmpdir(), 'app-kit-graph-'))
    writeFileSync(join(dir, 'leaf.ts'), "import { TenshuSdk } from '@tenshu/sdk/core'\nexport const x = TenshuSdk\n")
    writeFileSync(join(dir, 'types.ts'), "import type { File } from '@tenshu/sdk/files'\nexport type F = File\n")
    writeFileSync(join(dir, 'entry.ts'), "import { x } from './leaf'\nimport './types'\nexport { x }\n")
    const found = forbiddenSdkImports(join(dir, 'entry.ts'))
    expect(found.map(([specifier]) => specifier)).toEqual(['@tenshu/sdk/core'])
    expect(found[0]![1]).toMatch(/entry\.ts -> .*leaf\.ts$/)
  })
})
