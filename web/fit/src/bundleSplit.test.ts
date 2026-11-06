/// <reference types="node" />
// ⚠⚠ MapLibre (~800 KB) loads with a workout's route map, never with the dashboard or History. The
// guard walks the static import graph (dynamic `import()` is a chunk boundary): `main.tsx` must not
// reach `@tenshu/map-kit` at all — the detail reaches it only through `lazy(() => import('./RouteMap'))`
// — and map-kit's own entry reaches MapLibre only lazily (its `lazySplit.test.ts`).
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { reachablePackages } from '@tenshu/app-kit/testing/importGraph'

const SRC = dirname(fileURLToPath(import.meta.url))
const HEAVY = ['@tenshu/map-kit', 'maplibre-gl', 'maplibre-gl/dist/maplibre-gl.css', 'pmtiles']

describe('Fit bundle split', () => {
  it('the app entry (dashboard, History, profile) reaches neither map-kit nor MapLibre statically', () => {
    const reached = reachablePackages(resolve(SRC, 'main.tsx'))
    const leaked = HEAVY.filter((p) => reached.has(p)).map((p) => `${p}: ${reached.get(p)!.join(' -> ')}`)
    expect(leaked).toEqual([])
  })

  it('the route map does reach map-kit — the walker is not blind', () => {
    expect(reachablePackages(resolve(SRC, 'components/RouteMap.tsx')).has('@tenshu/map-kit')).toBe(true)
  })

  it('the detail loads the route map through a dynamic import', () => {
    const detail = readFileSync(resolve(SRC, 'components/WorkoutDetail.tsx'), 'utf8')
    expect(detail).toMatch(/lazy\(\(\) => import\('\.\/RouteMap'\)/)
    expect(detail).not.toMatch(/^import .*from '\.\/RouteMap'/m)
  })
})
