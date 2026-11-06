// MapRegion entry point (web-app-suite.md §4, W-MAP): which catalog region covers a place or a
// camera, and the bytes of its PMTiles archive a range at a time — what `@tenshu/map-kit`'s PMTiles
// source reads through. Backed by `MapRegion` on the Kotlin side (`:tenshu-sdk-js`), whose ranges
// are served by `StreamingRegionSource`, the class Control runs (`maps-region-delivery.md` D10).
//
// ⚠⚠ A deployment that configures no tile host (`maps-region-delivery.md` §Web scope W.1) answers
// `MapRegionErrorKind.transportUnconfigured` on every call that needs it. Say no map is set up,
// never draw an empty map.
// ⚠ Every call is `{ ok: true, … }` or a `MapRegionFailure`. A failure is never an empty answer; the
// one legitimate empty is a range at/after EOF.

import * as bundle from './_bundle'

export interface MapRegionRow {
  readonly regionId: string
  readonly name: string
  readonly byteSize: number
  // `[minLng, minLat, maxLng, maxLat]`, or null when the catalog row carries no box.
  readonly bounds: readonly [number, number, number, number] | null
}

export interface MapRegionFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

export type MapRegionCatalog =
  | { readonly ok: true; readonly buildDate: string; readonly regions: readonly MapRegionRow[] }
  | MapRegionFailure
export type MapRegionAt = { readonly ok: true; readonly region: MapRegionRow | null } | MapRegionFailure
export type MapRegionsIn = { readonly ok: true; readonly regions: readonly MapRegionRow[] } | MapRegionFailure
// `bytes` is an ArrayBuffer of exactly the bytes read; it crosses the bridge as a transferable.
export type MapRegionRange = { readonly ok: true; readonly bytes: ArrayBuffer } | MapRegionFailure

// The catalogue keys this surface answers with (`MapRegionErrors` in Kotlin). Branch on `kind`.
export const MapRegionErrorKind = {
  transportUnconfigured: 'error_map_transport_unconfigured',
  transportFailed: 'error_map_transport_failed',
  catalogUnverified: 'error_map_catalog_unverified',
  unknownRegion: 'error_map_unknown_region',
  badRequest: 'error_map_bad_request',
  unavailable: 'error_map_unavailable',
} as const

// The largest `streamRange` window (1 MiB). A reader splits bigger reads.
export const MAX_REGION_RANGE_BYTES = 1 << 20

export const MapRegion = bundle.MapRegion.getInstance()
