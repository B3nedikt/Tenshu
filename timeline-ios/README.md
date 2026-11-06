# timeline-ios

The Tenshu **Timeline** iOS app ([`docs/proposals/timeline-app.md`](../docs/proposals/timeline-app.md)).

Native SwiftUI over the shared, Compose-free `:timeline-shared-logic` view models, consumed through
the `TimelineSurface` seam on Timeline's own `TenshuSdk` framework, `:tenshu-sdk-ios-timeline`
(`ios-per-app-frameworks.md` P4; proposal [`ios-proxy-architecture.md`](../docs/proposals/ios-proxy-architecture.md) §9.3: *share the view
models + repositories, write the UI native*). This is the Timeline twin of `drive-ios` / `control-ios`.

The shipping app runs its **own in-process route-1 proxy** over the shared App-Group replica once
Control has provisioned key material (`TimelineLocalRuntime`, `ios-first-party-route-1-migration.md`
P3). Until then it renders `NotConnectedTimelineRepository` — every screen empty, plus a shell banner
saying why.

⚠ That fallback used to be `MockTimelineRepository`, which showed an unprovisioned user invented
places and trips as if they were their own. The mock is now a **test/preview surface only**
(`TimelineSurface.installMock()`); the runtime default and teardown target is
`installNotConnected()`. The `DEV_HARNESS` EPC tier still swaps in a real
`TimelineProjectionRepository` behind `TimelineSurface.installRealClient(...)` with no SwiftUI
change — the Drive Tier-1/Tier-2 shape.

The Xcode project + `Info.plist` are generated from `project.yml` and gitignored (build from source,
like the Kotlin framework + libsodium).

## Build (simulator)

```bash
# One-time: generate the Xcode project (needs `brew install xcodegen`).
#   ./gradlew generateTimelineIosProject      # or: (cd timeline-ios && xcodegen generate)

# Build the app. Its prebuild links Timeline's own Kotlin framework (`scripts/ios-build-sdk.sh timeline`).
xcodebuild -project timeline-ios/TimelineIos.xcodeproj -scheme TimelineIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
```

⚠ Run `xcodebuild`, not just `:tenshu-sdk-ios-timeline:iosSimulatorArm64Test` — the Kotlin gate compiles the
view models but **not** the SwiftUI, so a Swift-only break passes it (memory
`feedback-ios-xcodebuild-swift-slices`).

## The three map heroes (P3)

Movement detail, trip detail and place detail each draw a **live MapLibre map over device-local
PMTiles** — the movement's and the trip's route, and the place as a pinned point. Everything
cartographic comes from `:basemap-core`; the SwiftUI map layer is
[`ios-basemap`](../ios-basemap/README.md), shared with `fit-ios` and included by two lines in
`project.yml` ([`ios-fit-timeline-maps-parity.md`](../docs/proposals/ios-fit-timeline-maps-parity.md)
D7/D9, §3.1.2).

Each hero keeps its fallback rather than blanking:

| hero | map when | otherwise |
|---|---|---|
| moment | a `movement` moment whose trace decodes to ≥ 2 points | the kind's glyph card; the normalized `RouteSketch` if there are no tiles at all |
| trip | `trip.route.count >= 2` (the map sits **under** the cover hero, as on Compose) | nothing — no route, no map |
| place | the place has a coordinate | the flat personalised rectangle |

⛔ **No MapKit, ever** — Apple-served tiles would disclose the region on every detail open (A35).

⚠ Only `regionBundlePath` is threaded, never `regionStream`: the D10 streamed source cannot be built
from Swift (its `readRange` is a suspend function type), so that is Kotlin work — B-4. Until then a
place or route outside a *downloaded* region draws on the bundled **Firenze** demo archive, and
geometry outside that extent frames onto empty tiles.

Head-less render probe (`DEV_HARNESS` only), which is how these were witnessed:

```bash
xcrun simctl launch <udid> app.tenshu.timeline.ios -mapHero moment   # lis-riverwalk → route map
xcrun simctl launch <udid> app.tenshu.timeline.ios -mapHero trip     # lisbon-2026-04 → route map
xcrun simctl launch <udid> app.tenshu.timeline.ios -mapHero place    # ponte-vecchio → pinned point
xcrun simctl launch <udid> app.tenshu.timeline.ios -mapHero place mogg  # no coordinate → flat hero
xcrun simctl io <udid> screenshot /tmp/shot.png
```

## Dev harness — live EPC pairing (`TimelineIos-Dev`)

The `TimelineIos-Dev` scheme (the `DebugHarness` config, which defines the `DEV_HARNESS` compilation
condition) adds a **Pair** tab beside the app: paste/scan a Control's `tenshu://epc/pair#…` link,
confirm the 6-digit SAS, and `TimelineLiveClientSession.activate` installs the real
`TimelineProjectionRepository` over the EPC channel (`ios-proxy-architecture.md` §12). The App tab
remounts so its view models rebind to the live store. The shipping `TimelineIos` scheme has no EPC
path at all — it gates on route-1, else the not-connected surface. On the simulator the handshake fails at the transport (no live Control) — that proves the
wiring; the SAS round-trip is the on-device gate.

```bash
xcodebuild -project timeline-ios/TimelineIos.xcodeproj -scheme TimelineIos-Dev \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
```
