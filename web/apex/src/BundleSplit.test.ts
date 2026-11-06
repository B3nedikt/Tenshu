// The apex's first chunk must not contain the Kotlin runtime (web-app-suite.md F6).
//
// Measured 2026-10-23 before the split: ONE chunk, 4.7 MB (1.08 MB gzip), 3.9 MB of it the Kotlin
// bundle, downloaded by the public landing page. `RuntimeLaziness.test.tsx` guards when the runtime
// STARTS (the Web Lock); this guards whether it is DOWNLOADED. A static value import of any
// `@tenshu/sdk/*` entry from `main.tsx`'s static graph puts the runtime back into the first chunk,
// because every entry re-exports from the one Kotlin bundle (`web/sdk/src/_bundle.ts`).
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { reachablePackages } from './test/staticImportGraph'

const SRC = dirname(fileURLToPath(import.meta.url))

function sdkImports(entry: string): Array<[string, string]> {
  return [...reachablePackages(resolve(SRC, entry))]
    .filter(([specifier]) => specifier.startsWith('@tenshu/sdk'))
    .map(([specifier, chain]) => [
      specifier,
      chain.map(file => file.slice(SRC.length + 1)).join(' -> '),
    ])
}

describe('apex bundle split', () => {
  it('main.tsx reaches no @tenshu/sdk value statically', () => {
    // On failure this lists each SDK entry with the import chain that reaches it. Move the
    // importing component into RuntimeApp, or load it with a dynamic import().
    expect(sdkImports('main.tsx')).toEqual([])
  })

  it('the walker does see SDK imports where they exist', () => {
    // Control for the test above: a walker that saw nothing would pass it forever.
    expect(sdkImports('RuntimeApp.tsx').map(([specifier]) => specifier)).toContain(
      '@tenshu/sdk/onboarding',
    )
  })
})
