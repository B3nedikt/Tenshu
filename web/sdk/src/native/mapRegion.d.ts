// `MapRegion` — the streamed-region surface of the shared web map renderer (web-app-suite.md W-MAP).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// One catalog region. `bounds` is `[minLng, minLat, maxLng, maxLat]` (the catalog's coarse coverage,
// for framing), or null when the row has none — such a row is never chosen by a lookup.
interface NativeMapRegion {
  readonly regionId: string
  readonly name: string
  readonly byteSize: number
  readonly bounds: readonly [number, number, number, number] | null
}

type NativeMapRegionFailure = {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

type NativeMapRegionCatalog =
  | { readonly ok: true; readonly buildDate: string; readonly regions: readonly NativeMapRegion[] }
  | NativeMapRegionFailure

type NativeMapRegionAt = { readonly ok: true; readonly region: NativeMapRegion | null } | NativeMapRegionFailure

type NativeMapRegionsIn = { readonly ok: true; readonly regions: readonly NativeMapRegion[] } | NativeMapRegionFailure

// ⚠ `bytes` is an ArrayBuffer of its own (a copy), so the gateway may transfer it. Empty at/after EOF.
type NativeMapRegionRange = { readonly ok: true; readonly bytes: ArrayBuffer } | NativeMapRegionFailure

interface MapRegionInstance {
  readonly MAX_RANGE_BYTES: number
  catalog(): Promise<NativeMapRegionCatalog>
  regionAt(latitude: number, longitude: number): Promise<NativeMapRegionAt>
  regionsIn(minLng: number, minLat: number, maxLng: number, maxLat: number): Promise<NativeMapRegionsIn>
  streamRange(regionId: string, offset: number, length: number): Promise<NativeMapRegionRange>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const MapRegion: { getInstance(): MapRegionInstance }
}
