package app.tenshu.ui.map

/**
 * Re-exports of the two `:basemap-core` types that were part of `:ui-map`'s public API before the
 * Compose-free core was split out (`docs/proposals/ios-fit-timeline-maps-parity.md` §3.1).
 *
 * Consumers name `BasemapCoord` and `RegionStreamSource` when they call [OfflineBasemap] /
 * [RouteBasemap], so aliasing them costs two lines and saves churning four call sites across
 * `:timeline-shared-ui`, `:fit-shared-ui` and `:maps-shared-ui` — a refactor whose whole claim is
 * "no behaviour change" should not also be a rename. A typealias is the same class, so a consumer
 * that *does* want to import from `app.tenshu.basemap` directly is free to.
 *
 * The rest of the moved surface (`MapPalette`, `BasemapSource`, `RouteCameraFrame`, the style
 * builders) was `internal` to `:ui-map` and had no external callers, so it is not aliased.
 */

/** @see app.tenshu.basemap.BasemapCoord */
typealias BasemapCoord = app.tenshu.basemap.BasemapCoord

/** @see app.tenshu.basemap.RegionStreamSource */
typealias RegionStreamSource = app.tenshu.basemap.RegionStreamSource
