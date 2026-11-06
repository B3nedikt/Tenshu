# maps-ios

The Tenshu **Maps** iOS app ([`docs/proposals/maps-app.md`](../docs/proposals/maps-app.md); the
slice is P5 of [`ios-fit-timeline-maps-parity.md`](../docs/proposals/ios-fit-timeline-maps-parity.md)).

Native SwiftUI over the shared, Compose-free `:maps-shared-logic` view models, consumed through the
`MapsSurface` seam on Maps' own `TenshuSdk` framework,
`:tenshu-sdk-ios-maps` (`ios-per-app-frameworks.md` P4)
([`ios-proxy-architecture.md`](../docs/proposals/ios-proxy-architecture.md) §9.3: *share the view
models + repositories, write the UI native*). The twin of `timeline-ios` / `fit-ios` / `drive-ios`.

Three screens, mirroring the Compose IA rather than re-deriving it:

| screen | Compose twin |
|---|---|
| `PlacesView` — the four tabs (Yours / Saved / For you / Contribute) | `PlacesScreen` |
| `MapsPlaceDetailView` — point map hero, identity, actions, history | `PlaceDetailScreen` + `PlaceDetailContent` |
| `RegionPickerView` — the offline-region catalog | `RegionPickerScreen` |

## Route 1, from the start

Maps is **born on route 1** — it is the `ios-first-party-route-1-migration.md` **P4** that had been
parked purely for want of a shell, so there is no EPC tier here to migrate off and none to fall back
to. Provisioned → `MapsLocalRuntime` runs the production proxy graph in this process over the shared
App-Group replica Control writes; not provisioned → the honest empty surface plus a banner saying
why (**D4**), never mock data. `MapsSurface.installMock()` is a test / preview / probe surface only.

Both services Maps reads are already hosted by that graph, so it needed no new service wiring:
`:place-service` (the POI store, shared with Timeline) and `MapRegionService` (the offline basemap
catalog).

⚠ Keychain + App Group entitlements are wired from the first commit. `timeline-ios` shipped without
them for a while — a shell with no Keychain can never see provisioning, so the route-1 gate is
permanently "not connected" and it looks like a data bug.

## The map

The place-detail hero is a real MapLibre map over device-local PMTiles, via the shared
[`ios-basemap`](../ios-basemap) layer. Maps is **consumer #3** and needed exactly the two lines that
layer documents — `include: ../ios-basemap/basemap.yml` plus `templates: [MapBearingApp]`; the Swift
sources, the MapLibre SPM package and the tile/glyph bundle resources all ride in.

⛔ **No MapKit, ever** (threat model A35). ⚠ The hero is gated on the place having a *real*
coordinate: `MapsPlaceProjection` fills `location` unconditionally and `(0, 0)` is the Atlantic
(**D11**) — see `MapsPlace.hasCoordinate`.

⚠ The region picker is a **list, not a map**, exactly as on Compose: the catalog is a fixed set of
pre-built signed extracts, so there is nothing to drag a selection box over. Nothing in this app uses
`TenshuMapView`'s `interactive` flag, which therefore remains unexercised across all three consumers.

## Known gaps

- **A downloaded region does not survive a relaunch** — the proxy's region store writes through
  `FileDatabase` and iOS binds `InMemoryFileDatabase` (parity doc B-2, shared with Drive's plaintext
  cache). Both the picker and the detail's offline row say so rather than silently forgetting.
- **`regionStream` is not threaded** (B-4): `RegionStreamSource.readRange` is a suspend function
  type, which Swift cannot conform to, so every hero passes `regionBundlePath` only and falls through
  to the bundled demo archive — a **Firenze** extract, so a place elsewhere frames onto empty tiles.
- **Saved / For you are empty against the real store**, and detail loses photos + visit history:
  `:place-service` carries only `{placeId, latE7, lngE7, capturedAt, label}` (the thin-screens
  tradeoff `MapsPlaceProjection` records). The screens say so; they do not invent rows.
- On-device: simulator only so far.

## Build (simulator)

```bash
# One-time: generate the Xcode project (needs `brew install xcodegen`).
#   ./gradlew generateMapsIosProject      # or: (cd maps-ios && xcodegen generate)

# Build the app. Its prebuild links Maps' own Kotlin framework (`scripts/ios-build-sdk.sh maps`).
xcodebuild -project maps-ios/MapsIos.xcodeproj -scheme MapsIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
```

⚠ Run `xcodebuild`, not just `:tenshu-sdk-ios-maps:iosSimulatorArm64Test` — the Kotlin gate compiles the
view models but **not** the SwiftUI, so a Swift-only break passes it. ⚠ Use a fresh
`-derivedDataPath`: stale DerivedData fails at `CreateBuildDescription` with "There is no XCFramework
found at \<path\>" even though the path exists. `xcodebuild` sitting at 0% CPU means Xcode.app holds
the project.

The generated `MapsIos.xcodeproj` + `Info.plist` are gitignored (build from source, like the Kotlin
framework). ⚠ A new Swift file needs an `xcodegen generate`, or the project silently omits it.

## Render probes (`MapsIos-Dev` scheme)

`simctl` has no tap verb and the detail is two taps deep, so the `DebugHarness` config carries a
launch argument that mounts one shipping screen over its shipping view model — the established shape
(`drive-ios` has a dozen, `fit-ios` has `-workoutRouteMap`, `timeline-ios` has `-mapHero`):

```bash
xcrun simctl launch --terminate-running-process <udid> app.tenshu.maps.ios -mapsScreen places
xcrun simctl launch --terminate-running-process <udid> app.tenshu.maps.ios -mapsScreen detail home
xcrun simctl launch --terminate-running-process <udid> app.tenshu.maps.ios -mapsScreen regions
```

They run over `MapsSurface.installMock()`, installed in `App.init()` — **not** in a `.task`: each
screen's `@StateObject` resolves its view model at init, so a repository installed later arrives
after the first load. What they prove is the *view*, not the data path.

⚠ Every mock place carries a coordinate (`MapsMockData.projectToGeo` sites them inside the bundled
Firenze extract), so the probe cannot reach the hero's no-coordinate arm. That arm is the *real*
store's common case.

## App icon

`Resources/Assets.xcassets/AppIcon.appiconset/icon_1024.png` is upscaled from
`maps-android`'s 192 px launcher — placeholder-grade, enough for a simulator build. It wants the real
1024 px artwork before any TestFlight build.
