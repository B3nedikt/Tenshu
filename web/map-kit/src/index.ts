// `@tenshu/map-kit` — the shared web map renderer (web-app-suite.md §4, W-MAP).
//
// ⚠⚠ This entry must never reach `maplibre-gl` or `pmtiles` statically (`lazySplit.test.ts`): they
// load with the map, not with the app. And never an `@tenshu/sdk` value import other than
// `/bridge` — that would ship the Kotlin runtime to the app (web-app-suite.md §7).
export { TenshuMap } from './TenshuMap'
export { MapUnavailable } from './chrome'
export { DEFAULT_MAP_LABELS } from './props'
export type { TenshuMapProps, TenshuMapLabels } from './props'
export type { LatLng, MapCamera, MapPin, MapPolyline, PolylineSegment } from './overlays'
export { bridgeRegionAccess, MapRegionMethod, RegionErrorKind, RegionTileError, MAX_RANGE_BYTES } from './regions'
export type { RegionAccess, RegionRow } from './regions'
// ⚠ Not the style module's values: they would pull the generated style JSON into the app's main
// chunk. The style loads with the map.
export type { MapTheme } from './style/basemapStyle'
export { supportsWebGL } from './webgl'
