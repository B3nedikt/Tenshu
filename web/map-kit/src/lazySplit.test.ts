// ⚠⚠ MapLibre (~800 KB) and pmtiles load with the MAP, not with the app: an app's other routes — and
// the apex, which never imports map-kit — must not download them. The guard walks the static import
// graph (dynamic `import()` is a chunk boundary), the same walker the apps' SDK-boundary tests use
// and the apex's `BundleSplit.test.ts` is built on.
import { readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { forbiddenSdkImports, reachablePackages } from '@tenshu/app-kit/testing/importGraph'

const SRC = dirname(fileURLToPath(import.meta.url))
const HEAVY = ['maplibre-gl', 'maplibre-gl/dist/maplibre-gl.css', 'pmtiles']

describe('map-kit bundle split', () => {
  it('the package entry reaches neither maplibre-gl nor pmtiles statically', () => {
    const reached = reachablePackages(resolve(SRC, 'index.ts'))
    const leaked = HEAVY.filter((p) => reached.has(p)).map((p) => `${p}: ${reached.get(p)!.join(' -> ')}`)
    expect(leaked).toEqual([])
  })

  it('the lazily loaded MapView does reach them — the walker is not blind', () => {
    const reached = reachablePackages(resolve(SRC, 'MapView.tsx'))
    expect(reached.has('maplibre-gl')).toBe(true)
    expect(reached.has('pmtiles')).toBe(true)
  })

  it('nothing in map-kit value-imports @tenshu/sdk (the Kotlin runtime) — types only', () => {
    const files = readdirSync(SRC, { recursive: true, encoding: 'utf8' })
      .filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f))
      .map((f) => join(SRC, f))
    expect(files.length).toBeGreaterThan(5)
    expect(files.flatMap((f) => forbiddenSdkImports(f))).toEqual([])
  })
})
