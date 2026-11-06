// How a map reaches region bytes. ⚠ No `@tenshu/sdk` VALUE import anywhere in map-kit: every entry
// but `/bridge` re-exports the Kotlin bundle (web-app-suite.md §7). Types are fine.
import type { MapRegionAt, MapRegionRange, MapRegionRow } from '@tenshu/sdk/mapRegion'

export type RegionRow = MapRegionRow

/**
 * What `<TenshuMap>` needs from the outside world: which region covers a point, and a byte range of
 * its PMTiles archive. In an app this is `bridgeRegionAccess(bridge.call)`; in the dev harness and
 * the tests it is a fixture over a local archive.
 */
export interface RegionAccess {
  regionAt(latitude: number, longitude: number): Promise<MapRegionAt>
  readRange(regionId: string, offset: number, length: number): Promise<MapRegionRange>
}

/** The gateway methods map-kit calls. An app adds these to its `ProxyMethod` table AND its allowlist. */
export const MapRegionMethod = {
  RegionAt: 'mapRegion.regionAt',
  RegionsIn: 'mapRegion.regionsIn',
  StreamRange: 'mapRegion.streamRange',
  Catalog: 'mapRegion.catalog',
} as const

/** The largest single `mapRegion.streamRange` window (`MapRegion.MAX_RANGE_BYTES`, 1 MiB). */
export const MAX_RANGE_BYTES = 1 << 20

type Call = <T>(method: string, params?: unknown) => Promise<T>

/** [RegionAccess] over an app's apex bridge (`createApexBridge(...).call`). */
export function bridgeRegionAccess(call: Call): RegionAccess {
  return {
    regionAt: (latitude, longitude) => call<MapRegionAt>(MapRegionMethod.RegionAt, { latitude, longitude }),
    readRange: (regionId, offset, length) =>
      call<MapRegionRange>(MapRegionMethod.StreamRange, { regionId, offset, length }),
  }
}

/** The kinds `MapRegion` fails with (`MapRegionErrors` in Kotlin). Spelled here as data, not imported. */
export const RegionErrorKind = {
  transportUnconfigured: 'error_map_transport_unconfigured',
  transportFailed: 'error_map_transport_failed',
  catalogUnverified: 'error_map_catalog_unverified',
  unknownRegion: 'error_map_unknown_region',
} as const

/** A failed region read, carrying the bridge's kind so the UI can say something true. */
export class RegionTileError extends Error {
  readonly kind: string
  constructor(kind: string, message: string) {
    super(message)
    this.name = 'RegionTileError'
    this.kind = kind
  }
}
