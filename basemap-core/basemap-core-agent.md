# :basemap-core — module conventions

The **Compose-free core of the offline basemap**: the MapLibre style JSON, the palette, the route
camera framing, the schematic route fit, and the archive-resolution seam. Nothing on the classpath
but kotlinx-serialization, and that only as an `implementation` detail.

Split out of `:ui-map` per [`ios-fit-timeline-maps-parity.md`](../docs/proposals/ios-fit-timeline-maps-parity.md)
§3.1. `:ui-map` keeps every `@Composable` and now depends on this.

## Why the split

iOS ships **no Compose Multiplatform UI at all** — every iOS surface is SwiftUI over Compose-free
shared modules. Putting `:ui-map` in an iOS app framework (`tenshu-sdk-ios-<app>`) would pull the whole Compose
runtime plus Skia into every iOS app that links it, including the ones with no map, *and* bring the
MapLibre link requirement along. So, mirroring House Rule #5 (which makes the same call for the web):
**share the map's logic, write the iOS map view in SwiftUI over MapLibre's native iOS SDK.**

The measurement that made this obvious rather than a matter of taste: of `:ui-map`'s four
`commonMain` files, `BasemapStyle.kt` was **598 lines with zero `@Composable`**, importing Compose
for exactly one thing — `androidx.compose.ui.graphics.Color`. The whole module held 8 composables.

## Public API

| | |
|---|---|
| `offlineBasemapStyleJson(pal, tileUrl, labelLanguage, glyphsUrl)` | the style, as a JSON **string** |
| `routeBasemapStyleJson(…, route, routeColorHex, …)` | the same with a route line + endpoints overlaid |
| `mapPalette(dark)` → `MapPalette` | eight `#RRGGBB` colours |
| `labelLanguageFor(languageTag)` | the `name:<lang>` suffix, or null → endonym |
| `fileGlyphsUrl(glyphsRoot)` | the non-Android glyph template |
| `routeCameraFrame(route, …)` → `RouteCameraFrame` | centre + zoom fitting a route to a viewport |
| `normalizeRoute(route)` | the schematic unit-square fit |
| `resolveBasemapSource(…)` → `BasemapSource` | region → bundled demo → schematic |
| `BasemapCoord`, `RegionStreamSource` | also re-exported from `:ui-map` as typealiases |

⚠ **The API hands out the style as a `String`, never a `JsonObject`.** The `JsonObject` builders are
`internal` (the tests want structure, not text). Two reasons, both load-bearing: a JSON string is
what *both* renderers actually want — `BaseStyle.Json(String)` in maplibre-compose, `MLNStyle`'s
`styleJSON` in MapLibre's native iOS SDK — and it keeps kotlinx-serialization types off the exported
Obj-C surface.

## ⚠ House Rule #1 lives here, not in the renderer

`BasemapStyleTest.everyUrlInTheStyleIsDeviceLocal` walks **every string in the emitted style, at any
depth**, and fails on anything URL-shaped that is not `pmtiles://file://`, `asset://` or `file://`.
It is recursive rather than key-based on purpose — it covers keys nobody has added yet.

That guard belongs wherever the style is *authored*. It came here with the style precisely so a
SwiftUI renderer over the same JSON inherits it for free; leaving it behind in `:ui-map` would have
meant the one platform whose glyph URL is assembled at runtime is the one platform it never covers.

## The colour representation

`MapPalette`'s colours are opaque `#RRGGBB` **strings**. The full reasoning is in `MapPalette.kt`'s
KDoc; the short form is that a hex string is what the style already serialises to, and it is the one
representation that crosses the Obj-C bridge without a trap:

- an ARGB `Int` exports as Swift `Int32`, and `0xFFF0ECE4` does not fit a signed 32-bit integer, so
  the natural Swift literal is a compile error;
- a `@JvmInline value class` is type-safe in Kotlin and mangled by Kotlin/Native's Obj-C export,
  which defeats the whole point of the module.

`:ui-map` converts at its own boundary (`BasemapColors.kt`): hex → Compose `Color` for the schematic
Canvas, Compose `Color` → hex for the route line's `MaterialTheme.colorScheme.primary`. The pair
round-trips, pinned by `BasemapColorsTest`.

## Targets

android + jvm + iosArm64 + iosSimulatorArm64 + js(IR).

- **iOS**: exported from the map-bearing apps' frameworks (`tenshu-sdk-ios-fit`, `-timeline`, `-maps`). No `iosX64` — they have none.
- **js**: mirrors `:vector-tile`. It keeps `commonMain` honest (js is the target that catches an
  accidental JVM-only dependency), and there is a plausible consumer: MapLibre GL JS takes a style
  JSON object of exactly this shape, so a web map would inherit this style *and its no-egress guard*
  rather than re-deriving them in TypeScript.

## ⚠ What the Swift surface actually looks like

Checked against the generated header + SKIE wrappers, not assumed. Top-level functions arrive as
Swift global functions (`offlineBasemapStyleJson(pal:tileUrl:labelLanguage:glyphsUrl:)`,
`mapPalette(dark:)`, `routeCameraFrame(route:…)`), `RouteCameraFrame` as a struct-like class of three
`Double`s, and `BasemapSource` gets a SKIE `onEnum(of:)` exhaustive switch. Two rough edges:

- ⚠ **Kotlin default arguments do not survive.** Swift must pass every parameter — including
  `glyphsUrl:` (which on iOS is never the default anyway) and `routeCameraFrame`'s
  `paddingFraction:`/`minZoom:`/`maxZoom:`. If that becomes annoying, the fix is a Swift-side
  convenience wrapper in the app, not a change here.
- ⚠⚠ **`RegionStreamSource` cannot be constructed from Swift.** Its `readRange` is a *suspend*
  function type, which exports as `id<KotlinSuspendFunction2>` — a Kotlin interface Swift cannot
  conform to. The D10 streamed-partial path therefore has to be assembled on the Kotlin side (as it
  already is for Compose, via `:map-region-app`). This is a second, independent reason B-4
  (`streamSource` on iOS) is Kotlin work rather than SwiftUI work.
- ℹ `normalizeRoute` returns `[KotlinPair<KotlinDouble, KotlinDouble>]` — boxed. Fine for the sketch
  fallback it backs; if a Swift caller ever iterates it hot, give it a dedicated value type.

## ✅ The first consumer, and what it needed (P2b, 2026-08-01)

`fit-ios`'s workout-route hero is the first SwiftUI map over this module, and the first thing on iOS
to draw a real tile. Three findings worth carrying to Timeline's heroes and to Maps:

- **This module needed no change at all.** `routeBasemapStyleJson(...)` was already public and already
  the right shape; the Swift side hands its return straight to `MLNMapView.styleJSON` (which exists —
  no temp file, no `styleURL` round-trip) and its `routeCameraFrame(...)` straight to
  `setCenter(_:zoomLevel:animated:)`.
- **The default-argument gap is real but small.** The consumer wrote one `enum BasemapStyle` wrapper
  (~20 lines) filling in `labelLanguage:` / `glyphsUrl:` from bundle lookups, exactly as the note
  above predicted. Keep the fix Swift-side; do not add Kotlin overloads.
- ⚠ **The style's House Rule #1 guard is the *only* thing testing the URLs**, and it did its job:
  every URL a SwiftUI consumer emits (`pmtiles://file://…` for the archive, `file://…` for the glyph
  atlas) is composed here, so `everyUrlInTheStyleIsDeviceLocal` covers a renderer it never sees. The
  corollary is the important half — **a consumer that builds a URL outside this module escapes the
  guard.** `fit-ios/Sources/BasemapAssets.swift` deliberately builds only *inputs* to these builders.

Proven on the way: MapLibre iOS **6.28.0** reads `pmtiles://file://<abs path>` natively and resolves
the `file://` glyph template (labels render), so nothing about the style needs a per-platform arm.

## ✅ The second, third and fourth consumers (P3, 2026-08-01)

Timeline's moment, trip and place heroes. The Swift layer moved out of `fit-ios/Sources` into
[`ios-basemap/`](../ios-basemap/README.md), a sources directory both shells compile via one XcodeGen
`include`. Two more findings:

- **Still no change here.** `offlineBasemapStyleJson` was already the right shape for the one hero
  that is *not* a route, and `RouteCameraFrame` is constructible from Swift — so a fixed-zoom point
  camera needs no `routeCameraFrame` equivalent and no new export.
- ⚠ **A point is not a one-element route, and the API already says so.** Feeding a single coordinate
  to `routeBasemapStyleJson` draws a degenerate line layer plus two stacked endpoint circles beneath
  whatever pin the renderer overlays. The place hero therefore takes the `offlineBasemapStyleJson`
  arm — the same split `PlaceMap` (→ `OfflineBasemap`) versus `TripRouteMap` (→ `RouteBasemap`) makes
  on the Compose side. If a caller ever asks for "a route builder that also handles one point", the
  answer is no: they want the other function.
