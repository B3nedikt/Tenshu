/// <reference types="node" />
// ⚠⚠ MapLibre (~800 KB) and pmtiles load with the first MAP, never with the day list: nothing
// `main.tsx` reaches statically may import them. `@tenshu/map-kit`'s entry defers them to a
// `React.lazy` chunk, and this app must neither undo that by importing either itself, nor reach a
// map-kit module past its entry. Dynamic `import()` is a chunk boundary, so the static graph is
// what the main chunk carries.
// ⚠ The app-kit walker follows relative imports and `@tenshu/app-kit/*` only, so map-kit's own
// graph is walked here from the entry the app imports (its `exports["."]`), and the two unioned.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { reachablePackages } from '@tenshu/app-kit/testing/importGraph'

const SRC = dirname(fileURLToPath(import.meta.url))
const MAP_KIT = resolve(SRC, '../../map-kit')
const HEAVY = ['maplibre-gl', 'maplibre-gl/dist/maplibre-gl.css', 'pmtiles']

function mapKitEntry(): string {
  const pkg = JSON.parse(readFileSync(resolve(MAP_KIT, 'package.json'), 'utf8')) as { exports: Record<string, string> }
  return resolve(MAP_KIT, pkg.exports['.']!)
}

/** Every package the main chunk reaches: the app's graph, plus map-kit's from its entry. */
function mainChunkPackages(): Map<string, string[]> {
  const app = reachablePackages(resolve(SRC, 'main.tsx'))
  const viaKit = app.get('@tenshu/map-kit')
  if (!viaKit) return app
  const kit = reachablePackages(mapKitEntry())
  for (const [p, chain] of kit) if (!app.has(p)) app.set(p, [...viaKit, '@tenshu/map-kit', ...chain])
  return app
}

function leaks(reached: Map<string, string[]>): string[] {
  return HEAVY.filter((p) => reached.has(p)).map((p) => `${p}: ${reached.get(p)!.join(' -> ')}`)
}

describe('map bundle split', () => {
  it('main.tsx reaches neither maplibre-gl nor pmtiles statically, through the app or map-kit', () => {
    expect(leaks(mainChunkPackages())).toEqual([])
  })

  it('reaches map-kit through the map screens only, and never a deep map-kit path', () => {
    const reached = mainChunkPackages()
    expect(reached.get('@tenshu/map-kit')?.join(' -> ')).toMatch(/AppMap\.tsx$/)
    expect([...reached.keys()].filter((p) => p.startsWith('@tenshu/map-kit/'))).toEqual([])
  })

  it('the walker is not blind: map-kit’s lazy half does reach MapLibre', () => {
    const reached = reachablePackages(resolve(MAP_KIT, 'src/MapView.tsx'))
    expect(reached.has('maplibre-gl')).toBe(true)
    expect(reached.has('pmtiles')).toBe(true)
  })
})
