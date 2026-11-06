// A fixture `RegionAccess` over the bundled Firenze extract: one region, its bytes sliced from the
// archive the dev server serves. Same result shapes as `mapRegion.*` over the bridge, failures included.
import type { RegionAccess } from '../src/regions'

export const FIRENZE = {
  regionId: 'demo-firenze',
  name: 'Firenze (demo)',
  // The extract's own bbox is ~11.2–11.3 E, 43.7–43.8 N (`DemoArchiveBoundsTest`); a looser box is fine.
  bounds: [11.0, 43.6, 11.5, 43.9] as const,
}

export function fixtureRegionAccess(load: () => Promise<ArrayBuffer>): RegionAccess {
  let archive: Promise<ArrayBuffer> | null = null
  const bytes = () => (archive ??= load())
  return {
    async regionAt(latitude, longitude) {
      const [minLng, minLat, maxLng, maxLat] = FIRENZE.bounds
      const inside = latitude >= minLat && latitude <= maxLat && longitude >= minLng && longitude <= maxLng
      return { ok: true, region: inside ? { ...FIRENZE, byteSize: (await bytes()).byteLength } : null }
    },
    async readRange(regionId, offset, length) {
      if (regionId !== FIRENZE.regionId) return { ok: false, error: { kind: 'error_map_unknown_region' } }
      return { ok: true, bytes: (await bytes()).slice(offset, offset + length) }
    },
  }
}
