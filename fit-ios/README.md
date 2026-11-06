# fit-ios

The Tenshu **Fit** iOS app ([`docs/proposals/fit-app.md`](../docs/proposals/fit-app.md)).

Native SwiftUI over the shared, Compose-free `:fit-shared-logic` view models, consumed through the
`FitSurface` seam on Fit's own `TenshuSdk` framework, `:tenshu-sdk-ios-fit`
(`ios-per-app-frameworks.md` P4; proposal [`ios-proxy-architecture.md`](../docs/proposals/ios-proxy-architecture.md) §9.3: *share the view
models + repositories, write the UI native*). The Fit twin of `drive-ios` / `control-ios` /
`timeline-ios`.

v1 runs over `MockFitRepository` + `MockRecordingEngine` — every screen renders with no proxy, fully
simulator-verifiable. The live-EPC data path (a real `:health-service`-backed `FitProjectionRepository`
over an EPC channel) is a later tier that swaps in behind `FitSurface.installRepository(...)` with no
SwiftUI change — the Drive Tier-1/Tier-2 shape.

The Xcode project + `Info.plist` are generated from `project.yml` and gitignored (build from source,
like the Kotlin framework + libsodium).

## Build (simulator)

```bash
# One-time: generate the Xcode project (needs `brew install xcodegen`).
#   ./gradlew generateFitIosProject          # or: (cd fit-ios && xcodegen generate)

# Its prebuild links Fit's own Kotlin framework (`scripts/ios-build-sdk.sh fit`).
xcodebuild -project fit-ios/FitIos.xcodeproj -scheme FitIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
```

⚠ Run `xcodebuild`, not just `:tenshu-sdk-ios-fit:iosSimulatorArm64Test` — the Kotlin gate compiles the
view models but **not** the SwiftUI (memory `feedback-ios-xcodebuild-swift-slices`).

## The map (P2b)

The workout detail's route hero is a **live MapLibre map over device-local PMTiles** whenever the
workout carries real GPS (`Workout.geoRoute`); everything else keeps the normalized `RoutePathView`
sketch. Every cartographic decision — style JSON, palette, label-language gate, camera framing — comes
from the Compose-free `:basemap-core` in Fit's framework, shared verbatim with the Compose renderer
([`ios-fit-timeline-maps-parity.md`](../docs/proposals/ios-fit-timeline-maps-parity.md) D7).

⚠ **The SwiftUI map layer itself now lives in [`ios-basemap`](../ios-basemap/README.md)**, not here
(P3, when Timeline became the second consumer). Everything this app owes the map beyond its Kotlin
framework — the shared sources, the **MapLibre 6.x** SPM package, and the **tile archive + glyph
atlas** as bundle resources — arrives through the single `include` in `project.yml` plus
`templates: [MapBearingApp]`. What stays in `fit-ios` is `WorkoutRouteMapView`: which fallback to
draw, and the card's own size and chrome.

⛔ **No MapKit, ever** — Apple-served tiles would disclose the route's region to a third party on
every detail open (threat model A35).

Head-less render probe (`DEV_HARNESS` only), which is how the first tile was witnessed:

```bash
xcrun simctl launch <udid> app.tenshu.fit.ios -workoutRouteMap            # w-sun-run — real GPS → map
xcrun simctl launch <udid> app.tenshu.fit.ios -workoutRouteMap w-sat-ride # no geoRoute → sketch
xcrun simctl io <udid> screenshot /tmp/shot.png
```

## Dev harness — live EPC pairing (`FitIos-Dev`)

The `FitIos-Dev` scheme (the `DebugHarness` config, which defines the `DEV_HARNESS` compilation
condition) adds a **Pair** tab beside the app: paste/scan a Control's `tenshu://epc/pair#…` link,
confirm the 6-digit SAS, and `FitLiveClientSession.activate` installs the real
`FitProjectionRepository` over the EPC channel (`ios-proxy-architecture.md` §12). The App tab remounts
so its view models rebind to the live store. The shipping `FitIos` scheme stays mock-only. On the
simulator the handshake fails at the transport (no live Control) — that proves the wiring; the SAS
round-trip is the on-device gate.

```bash
xcodebuild -project fit-ios/FitIos.xcodeproj -scheme FitIos-Dev \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
```
