/// <reference types="node" />
// ⚠⚠ This app's bundle must not contain the Kotlin runtime: `@tenshu/sdk/bridge` is the only
// `@tenshu/sdk` entry it may reach as a value (directly or through `@tenshu/app-kit`); every other
// entry re-exports the Kotlin bundle (web-app-suite.md §7). Types are fine.
//
// ⚠⚠ And the list route must not load MapLibre: `maplibre-gl` / `pmtiles` load with the first
// rendered map (map-kit's lazy `MapView`), never with the app. The walker treats `@tenshu/map-kit` as
// a leaf, so the map-kit entry is walked here too — the same graph Vite bundles.
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { forbiddenSdkImports, reachablePackages } from '@tenshu/app-kit/testing/importGraph'

const SRC = dirname(fileURLToPath(import.meta.url))
const MAIN = resolve(SRC, 'main.tsx')
const MAP_KIT = resolve(SRC, '../../map-kit/src/index.ts')
const HEAVY = ['maplibre-gl', 'maplibre-gl/dist/maplibre-gl.css', 'pmtiles']

function leaks(entry: string): string[] {
  const reached = reachablePackages(entry)
  return HEAVY.filter((p) => reached.has(p)).map((p) => `${p}: ${reached.get(p)!.join(' -> ')}`)
}

describe('SDK boundary', () => {
  it('main.tsx reaches no @tenshu/sdk value but /bridge', () => {
    // On failure: each forbidden entry with the import chain that reaches it.
    expect(forbiddenSdkImports(MAIN)).toEqual([])
    expect(forbiddenSdkImports(MAP_KIT)).toEqual([])
  })

  it('does reach the bridge — the walker is not blind', () => {
    expect([...reachablePackages(MAIN).keys()]).toContain('@tenshu/sdk/bridge')
  })
})

describe('bundle split: MapLibre is not on the list route', () => {
  it('the app reaches the map-kit, and neither it nor the map-kit entry reaches MapLibre statically', () => {
    expect([...reachablePackages(MAIN).keys()]).toContain('@tenshu/map-kit')
    expect(leaks(MAIN)).toEqual([])
    expect(leaks(MAP_KIT)).toEqual([])
  })

  it('the lazily loaded MapView does reach it — the walker is not blind', () => {
    expect(leaks(resolve(SRC, '../../map-kit/src/MapView.tsx'))).not.toEqual([])
  })
})
