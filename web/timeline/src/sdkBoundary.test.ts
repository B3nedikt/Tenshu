/// <reference types="node" />
// ⚠⚠ This app's bundle must not contain the Kotlin runtime: `@tenshu/sdk/bridge` is the only
// `@tenshu/sdk` entry it may reach as a value (directly or through `@tenshu/app-kit`); every other
// entry re-exports the Kotlin bundle (web-app-suite.md §7). Types are fine.
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { forbiddenSdkImports, reachablePackages } from '@tenshu/app-kit/testing/importGraph'

const MAIN = resolve(dirname(fileURLToPath(import.meta.url)), 'main.tsx')

describe('SDK boundary', () => {
  it('main.tsx reaches no @tenshu/sdk value but /bridge', () => {
    // On failure: each forbidden entry with the import chain that reaches it.
    expect(forbiddenSdkImports(MAIN)).toEqual([])
  })

  it('does reach the bridge — the walker is not blind', () => {
    expect([...reachablePackages(MAIN).keys()]).toContain('@tenshu/sdk/bridge')
  })
})
