# ios-basemap — the shared SwiftUI map layer

The Swift sibling of **`:basemap-core`**: a `UIViewRepresentable` over MapLibre's native iOS SDK, plus
the thin wrappers that feed it. Every cartographic decision — style JSON, palette, label-language
gate, camera framing, archive resolution — comes from `:basemap-core` on the `TenshuSdk` framework and
is shared verbatim with the Compose renderer
([`ios-fit-timeline-maps-parity.md`](../docs/proposals/ios-fit-timeline-maps-parity.md) D7).

Extracted from `fit-ios/Sources` in P3, when Timeline became the second consumer. `maps-ios` joined as
the third in P5 (its place-detail hero) and **needed nothing but the two lines below** — no extra
package, no extra resource reference, no link flag — which is the evidence the factoring claim was
right.

⛔ **No MapKit, ever.** Apple-served tiles would disclose the viewed region to a third party on every
detail open (threat model **A35**), which is why `timeline-ios/Sources/RouteSketch.swift` drew an
abstract sketch for as long as it did. MapLibre over device-local PMTiles is what removes the
trade-off: no network egress, no API key, House Rule #1 intact.

## What is here

| file | |
|---|---|
| `TenshuMapView.swift` | the `MLNMapView` representable, the attribution chip, the centre pin |
| `BasemapStyle.swift` | Swift-side wrapper filling in the Kotlin default arguments; `route(…)` and `point(…)` |
| `BasemapAssets.swift` | where the archive + glyph atlas live in the app bundle |
| `BasemapHeroes.swift` | `TenshuRouteMap` / `TenshuPointMap` — a map with a caller-supplied fallback; `Color.basemapHex` |
| `basemap.yml` | the XcodeGen fragment every consumer includes |

## How an app consumes it

Two lines in the app's `project.yml`:

```yaml
include:
  - path: ../ios-basemap/basemap.yml
targets:
  <App>:
    templates: [MapBearingApp]
```

That one include carries **all three** things a map-bearing iOS app owes beyond its Kotlin framework
(D9), so the third consumer inherits them rather than re-deriving them:

1. these Swift sources,
2. **MapLibre 6.x** as an SPM package + product dependency (a *dynamic* xcframework, so it is embedded
   and signed into the bundle, unlike the static `TenshuSdk` framework),
3. the **tile archive + glyph atlas** as bundle resources, mirrored from
   `ui-map/src/androidMain/assets` — ⚠ *mirrored, never duplicated in git*: `:ui-map`'s archive tests
   open that tree by relative path, so it stays the single source of truth.

⚠ A new Swift file here needs an `xcodegen generate` in **each** consuming shell, or that project
silently omits it.

## ⚠ Why sources, and not a local Swift package

These files import `TenshuSdk`, a framework **Gradle writes into a build-output directory**
(`tenshu-sdk-ios-<app>/build/bin/<target>/<variant>Framework`). A SwiftPM `.binaryTarget` is resolved *before* any build
phase runs, so on the fresh `-derivedDataPath` this repo's build instructions require — precisely the
case where that directory is empty — package resolution would fail before the pre-build script that
produces the framework ever executed. A package would also have to re-declare MapLibre while the app
still embeds it, and could not carry the bundle resources, which must land in the *app's* bundle root
for `Bundle.main` lookups to find them.

Both shells are XcodeGen-generated and XcodeGen takes sources from any path, so compiling these files
into each app target costs one `templates:` line and keeps all three obligations in one file.

The cost, stated plainly: there is no compiler-enforced module boundary — an app *could* reach into
these types' internals, and nothing stops one shell drifting a file the other depends on. At three
consumers that is still cheaper than the resolution-order problem above; if a fourth appears, or these
files grow their own tests, a real framework target becomes worth its cost.

## Proven / not proven

**Proven** (P2b then P3, by render on an iPhone 16 simulator, not by reading upstream):
`pmtiles://file://<abs path>` reads the bundled archive natively on MapLibre iOS 6.28.0; the `file://`
glyph template resolves (labels are the proof — a broken glyph URL drops them while fills stay);
`MLNMapView.styleJSON` takes `:basemap-core`'s string directly; `routeCameraFrame` maps onto
`setCenter(_:zoomLevel:animated:)`.

**Not proven**: on-device (simulator only), the D10 streamed region path (B-4 — Kotlin work: a
`RegionStreamSource` cannot be constructed from Swift at all), and any interactive/full-screen map
(every hero is deliberately gesture-free).

⚠⚠ **`TenshuMapView.interactive` has still never been exercised.** P5 looked at it — the obvious
candidate was Maps' region picker — and found that screen is a *list* on both platforms: the catalog
is a fixed set of pre-built signed extracts, so there is nothing to drag a selection box over. Whoever
first needs a pannable map owes a render, not a reading of this file.

⚠ The bundled archive is a **Firenze** extract. Geometry outside it frames onto empty tiles — expected,
not a bug, and the reason the Timeline and Fit mock fixtures are sited there.
