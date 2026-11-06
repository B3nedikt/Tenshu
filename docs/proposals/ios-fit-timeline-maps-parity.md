# iOS parity for Fit, Timeline and Maps

**Status: v0.5 — 2026-08-03. B-1 CLOSED (a tile has been drawn, §3.1.1); B-3 CLOSED (Maps ships on
iOS, §3.3.1); B-2 CLOSED (a durable `FileDatabase`, §3.2.1).** Remaining: **P4-2** (the consumers
the store unblocks — Timeline's keep-offline row, and un-caveating Maps' region copy) and **P6**
(B-4's streamed region path, Wi-Fi controls, the diagnostics log tail).
Fit and Timeline SwiftUI parity, the iOS offline-region data path and the
`:ui-map` iOS renderer are **merged**; the `:basemap-core` extraction supersedes
D2 with D7 — see §3.1. Everything below that is still open is listed here
with the reason it is open, because in every remaining case the blocker is *structural* rather than
"someone has to write the SwiftUI".

`ios-drive-parity.md` covers Drive and runs to its own phase map; this is its companion for the
other three first-party apps. `ios-first-party-route-1-migration.md` covers the *transport* those
apps run on (route-1, in-process) and is complete except for Maps, which has no shell to migrate.

---

## 1. Why this doc exists

The three apps were swept in full on 2026-07-31. The finding that shaped everything since:

> **Almost none of the gap was view-model logic.** `:fit-shared-logic` and `:timeline-shared-logic`
> already expose every action the missing surfaces needed — `merge`, `unmerge`, `setRole`, `rename`,
> `keepOffline`, `retryImport`, `importFullHistory`, `setPersonalisationStrength`. The gap was
> SwiftUI that had never been written, plus a small number of load-bearing structural holes.

So the cheap half closed quickly (§2). What is left is the expensive half, and it is worth writing
down precisely, because from the outside "Fit has no map on iOS" and "Maps does not exist on iOS"
look like the same kind of task, and they are not.

---

## 2. Where the three apps stand

### 2.1 Fit — closest to parity

Shipped (P1): Home empty state, import-status banner (and with it `retryImport`, previously
unreachable), category-detail data-sources row, workout provenance footer, the Profile *Personal*
section (height / weight / **birth date** / sex — birth date was absent entirely), and the
`importFullHistory` affordance.

Also fixed there: **the Record tab walled off route-1 devices.** The gate read
`!FitLiveClientSession.shared.isActive`, but a route-1 device never opens an EPC session *by
design*, so a fully working app showed "Not connected" on Record alone — and told the user to open a
screen that Control-on-iOS does not ship.

Still open: the **workout route map** (§3.1).

### 2.2 Timeline — screens exist, several were hollow

Shipped (P1): moment → place navigation, choose/rename place, merge/unmerge, place roles, and
personalisation strength (which all four shared view-models expose and iOS ignored everywhere).

Also fixed there: **an unprovisioned device showed fabricated data.** The shell fell back to
`MockTimelineRepository`, presenting invented places and trips as real, while Fit had long handled
the same state honestly. That needed a repository, not a view — `NotConnectedTimelineRepository`
now mirrors `NotConnectedFitRepository`, and `installMock()` survives for tests and previews only.

Still open: the **three map heroes** (§3.1), **keep-offline** (§3.2), Wi-Fi controls (no iOS
implementation exists at all), and the diagnostics log tail.

### 2.3 Maps — **shipped 2026-08-01 (P5)**

Was: no `maps-ios` shell, no `MapsSurface`, and `:maps-shared-logic` not exported by the
`tenshu-sdk-ios` umbrella — though it already declared `iosArm64` / `iosSimulatorArm64`, so its
view-models compiled for iOS.

All three units of B-3 landed together; as built in §3.3.1. Places (four tabs), place detail (with a
real MapLibre point hero) and the region picker render on the simulator, and `MapsLocalRuntime`
closes `ios-first-party-route-1-migration.md` **P4**.

⚠⚠ **D6 was stale and is now corrected.** It said Maps' iOS shell is sequenced *after* its real
`:place-service` because Maps is "also mock-backed on Android". That stopped being true when
`maps-shared-ui/.../MapsModules.kt` bound `PlaceProjectionRepository` over the real `TenshuClient`
(`maps-app.md` Ph9); `mockPlaceRepositoryModule()` survives only as a one-line revert for the rich
demo. What remains is a **thin-screens tradeoff, not a mock**: the store carries only
`{placeId, latE7, lngE7, capturedAt, label}`, so every real place lands under *Yours* and the Saved /
For-you tabs are empty until `:place-service` graduates the richer fields. See D6 in §8.

---

## 3. The structural blockers, in leverage order

### 3.1 ⚠⚠ No Compose Multiplatform UI ships on iOS at all — B-1

`:ui-map` now has real `iosMain` actuals over MapLibre, and they compile for both iOS
targets. That was the easy part, and it is done. The blocker is one layer up:

> The `TenshuSdk` umbrella exports **only Compose-free modules**. Adding `:ui-map` to it pulls the
> entire Compose runtime plus Skia — and a MapLibre link requirement — into *every* iOS app,
> including ones with no map.

**RESOLVED 2026-08-01 (P2a): none of the framework options — split the module instead.**
The premise was wrong. Measured, `BasemapStyle.kt` is 598 lines with **zero `@Composable`**,
importing Compose solely for `Color`; there are eight composables in the whole module. The
shareable part was never UI, so no Compose needs to reach iOS at all. `:basemap-core` (Compose-free:
style JSON, camera framing, source resolution, `normalizeRoute`, and the no-egress guard) is
exported through the **existing** umbrella, and the iOS map view is SwiftUI over MapLibre's native
SDK — House Rule #5's shape, which every other iOS surface already follows.

What remains of B-1 is only the consumer: **nothing has drawn a tile yet.**

⚠ Consuming apps must also add MapLibre 6.x themselves (SPM or CocoaPods) and order linker flags
`-framework <KotlinFramework> -framework MapLibre` — the Gradle artifact carries a headers-only
cinterop, not the binary framework. This is the iOS analogue of Android's manifest permission-strip
rule; it is recorded in `ui-map-agent.md`.

What *is* proven: `:basemap-core` compiles for every target and exports cleanly, and (from the P1 renderer)
`maplibre-compose` publishes real iOS klibs while **PMTiles on iOS needs no staging** — a bundled
resource is already a file, so `pmtiles://file://` names it in place and Android's `asset://`
SIGABRT hazard has no iOS equivalent. That finding transfers directly to the Swift path.

What is not: the native link, the tile read and the render. `ui-map-agent.md` carries the same
proven / not-proven split so "iOS is done" cannot be inferred from the source tree.

⚠ `:ui-map`'s `iosMain` actuals are **not the shipping path** under D7. They are left in
place until the SwiftUI view is proven — removing them on the strength of a plan, before anything
has rendered, would be the wrong order.

### 3.1.1 P2b as built — the first tile (2026-08-01)

**B-1 is closed.** `fit-ios`'s workout detail draws a live MapLibre map over the bundled Firenze
PMTiles, framed to the workout's own GPS route, in light and dark, with labels. Screenshots taken on
an iPhone 16 simulator through the *shipping* `WorkoutDetailView`.

What it is: `TenshuMapView.swift`, a ~60-line `UIViewRepresentable` over `MLNMapView`, plus a
Swift-side `BasemapStyle` wrapper and a `BasemapAssets` bundle locator. Every cartographic decision
comes from `:basemap-core` unchanged.

**Findings, in the order they will matter to P3 and P5:**

1. ✅ **`pmtiles://` works natively on MapLibre iOS 6.28.0.** Proven by render, not by reading
   upstream: `pmtiles://file://<absolute path>` reads the bundled archive, and the `file://` glyph
   template resolves (the labels in the screenshot are the proof — a broken glyph URL drops them
   while the fills stay). `PMTilesFileSource` is in the shipped binary. No substitute tile source was
   needed, and none may be substituted.
2. ⚠⚠ **Tiles and glyphs reach the bundle through the *app*, not through a Gradle task.**
   `:ui-map`'s `stageIosBasemapResources` only fed `:ui-map`'s own Compose resources, and iOS no
   longer links `:ui-map` — so that route is gone. `fit-ios/project.yml` mirrors
   `ui-map/src/androidMain/assets` into the target directly (a file reference for the archive, a
   `type: folder` reference for the atlas — a *group* would flatten the fontstack directories and
   `{fontstack}` would resolve to nothing). ⚠ **Mirrored, never duplicated in git**: `:ui-map`'s
   `*RealArchiveTest`s open that tree by relative path, so it stays the single source of truth.
   Every future map-bearing iOS app owes the same three lines. If a third one appears, that is the
   moment to factor them out — not before (two is not a pattern).
3. ✅ **The link-order rule does not apply on this path.** `ui-map-agent.md` records
   `-framework <KotlinFramework> -framework MapLibre` because maplibre-compose's klib is a
   headers-only cinterop. `:basemap-core` is MapLibre-free — it emits a *string* — so nothing in
   `TenshuSdk.xcframework` references a MapLibre symbol and the two frameworks are independent.
   MapLibre is added by the app as an SPM package (`maplibre-gl-native-distribution`, a plain
   `.binaryTarget`; ⚠ `pod` is not installed in this build environment). It is *dynamic*, so unlike
   the static umbrella it is embedded and signed into the bundle.
4. ✅ **`:basemap-core` needed no change.** `routeBasemapStyleJson(...)` was already public and
   already the right shape. `MLNMapView.styleJSON` takes the string directly — no temp file, no
   `styleURL` round-trip — and `RouteCameraFrame` maps onto `setCenter(_:zoomLevel:animated:)`.
   ⚠ Constructing the view **with** the style (`MLNMapView(frame:styleJSON:)`) rather than setting it
   afterwards matters: `MLNMapView(frame:)` alone would briefly resolve MapLibre's built-in *remote*
   demo style, and there must be no window in which this view can reach the network.
5. ⚠ The predicted default-argument gap was real and cost ~20 lines of Swift (`BasemapStyle`), which
   is where it should stay. `resolveBasemapSource` is called from Swift via SKIE's `onEnum(of:)` and
   behaves as documented.
6. ⚠ **Only the real-GPS branch changed.** A workout with no `geoRoute` still draws the normalized
   sketch — verified as its own probe run, because that is the *common* case (Health Connect / Google
   Fit rarely expose per-fix tracks) and a silently-empty map would be the obvious regression.
7. ⚠ **Verification needed a `DEV_HARNESS` launch argument** (`-workoutRouteMap [workoutId]`),
   because `simctl` has no tap verb and the detail is two taps deep. It mounts the shipping view over
   the shipping view model — the established `drive-ios` probe shape. ⚠ It must call
   `FitSurface.installMock()` **in `App.init()`**, not in a `.task`: `WorkoutDetailView`'s
   `@StateObject` resolves its VM at init and loads immediately, so a later install renders "Workout
   not found" (which is exactly what the first run did).

**Still not proven:** on-device (simulator only), the D10 streamed region path (B-4 — Kotlin work),
and any interactive/full-screen map (the hero is deliberately gesture-free).

**Consequence for P3 (Timeline's three heroes):** the view is reusable as-is, but it currently lives
in `fit-ios/Sources`. Timeline's slice should move `TenshuMapView` / `BasemapStyle` / `BasemapAssets`
to a location both shells compile (the Swift files are shell-owned; there is no shared Swift target
today) rather than copy them, and it inherits the same three `project.yml` lines.

**Consequence for `:ui-map`:** its iOS actuals are now provably redundant — the substrate they were
waiting on is proven and the shipping path went the other way. Deleting them (plus the iOS targets
and the `stageIosBasemapResources` task) is a clean, self-contained follow-up.

### 3.1.2 P3 as built — the shared Swift target + Timeline's three heroes (2026-08-01)

**P3 is done.** Timeline's moment, trip and place details each draw live MapLibre tiles, and the map
layer P2b left in `fit-ios/Sources` now lives in a **`ios-basemap/`** directory both shells compile.

#### The mechanism: shared sources + one XcodeGen `include`, not a local Swift package

`TenshuMapView` / `BasemapStyle` / `BasemapAssets` (plus the new `BasemapHeroes`) moved to
`ios-basemap/Sources`, and each shell picks them up with two lines:

```yaml
include:
  - path: ../ios-basemap/basemap.yml
targets:
  <App>:
    templates: [MapBearingApp]
```

⚖️ **A local Swift package was rejected on a build-ordering fact, not on taste.** These files import
`TenshuSdk`, an XCFramework **Gradle writes into a build-output directory**. SwiftPM resolves a
`.binaryTarget` *before any build phase runs*, so on the fresh `-derivedDataPath` §7 mandates —
precisely the case where that directory is empty — package resolution would fail before the pre-build
script that produces the framework ever executed. A package also could not carry the **bundle
resources**: the archive and glyph atlas must land in the *app's* bundle root for `Bundle.main` to find
them, and a package's resources go in its own bundle.

The include factors **all three** of D9's obligations, not just the sources — the MapLibre SPM package,
the product dependency, and the tile/glyph resource references ride in the same fragment, so P2b's "if
a third one appears, that is the moment to factor them out" is discharged for Maps in advance. ⚠ The
`type: folder` reference on the glyph atlas is load-bearing and now stated in exactly one place.

The honest cost: **no compiler-enforced module boundary**, and a new Swift file needs an `xcodegen
generate` in each consuming shell. At two consumers that is cheaper than the resolution-order problem;
a real framework target becomes worth its cost at four, or as soon as these files want their own tests.

#### What each hero needed

1. **Moment (movement route)** — the smallest change: the existing `route.count >= 2` branch swapped
   `RouteSketchCard` for `TimelineRouteMapCard`, which *is* the sketch when there are no tiles. The
   sketch was demoted from hero to fallback; `RouteSketch.swift`'s A35 note now records that the
   constraint was never "no map", it was "no egress".
2. **Trip (trip route)** — iOS had **no route hero at all**, while Compose has shown one since
   `maps-app.md` §2 step 3. Added *under* the cover hero rather than replacing it, which is how
   `TripDetailScreen` composes them: the cover carries identity, the map carries shape.
3. **Place (a point)** — ⚠ the one that is not a route. A single coordinate through
   `routeBasemapStyleJson` would draw a degenerate line and two stacked endpoint dots underneath the
   pin, so this takes the plain `offlineBasemapStyleJson` arm (new `BasemapStyle.point(…)`) with a
   centre pin, mirroring `PlaceMap` → `OfflineBasemap` on Compose. It is gated on the place actually
   having a coordinate: `(0, 0)` is a real location in the Atlantic, so an ungated map would render a
   confidently-wrong empty sea where the honest answer is the flat hero.

#### Findings

- ⚠⚠ **The mock could not reach two of the three heroes at all.** There was no `MomentKind.Movement`
  moment in `TimelineMockData` (so `RouteSketchCard` was unreachable and had *never* rendered from the
  mock), and every mock place had `latE7 = lngE7 = 0` (so the Compose place hero drew tiles of the
  Gulf of Guinea — the exact "demo is not self-consistent" failure `ui-map-agent.md` warns about). Both
  are fixed in `:timeline-shared-logic`, sited inside the bundled Firenze extract like `Trip.route`
  already was, and pinned by tests.
- ⚖️ **Exactly one mock place carries a coordinate, deliberately.** The place hero has two arms and a
  dataset where every place has a coordinate (or none) can only ever demonstrate one of them. The trip's
  own landmark got the coordinate; the Berlin places keep none and go on exercising the flat fallback.
- ✅ **`:basemap-core` again needed no change.** `offlineBasemapStyleJson` was already public and already
  the right shape; `RouteCameraFrame` is constructible from Swift for the fixed-zoom point case.
- ⚠ **`regionStream` is still not threaded on iOS, in any hero** — `RegionStreamSource` cannot be
  constructed from Swift (§3.4), so all three pass `regionBundlePath` only and fall through to the
  bundled demo. Unchanged from P2b; it is B-4's to close.
- ⚠ Fit's `WorkoutRouteMapView` now delegates to the shared `TenshuRouteMap`, which additionally gates
  on `route.count >= 2`. No behaviour change — its call site already gated identically — but the gate
  is now in one place for all four heroes.

### 3.2 ⚠⚠ iOS has no durable `FileDatabase` — B-2 — **CLOSED (P4-1, 2026-08-03)**

`DefaultRegionStore` writes bundle bytes through `FileDatabase`, and iOS bound
**`InMemoryFileDatabase`** (`control-shared-proxy/src/iosMain/.../PlatformProxyModule.kt`).

So a *downloaded* region on iOS lived in RAM and died with the process. The **streamed** read path
(D10) is unaffected and is live (P1) — but **"keep this offline" could not honestly ship on
iOS until there was a durable `FileDatabase`.** Writing the `TripOfflineRow` SwiftUI before that
would have produced a button that appears to work and silently forgets.

This is the same hole `ios-drive-parity.md` records for Drive's plaintext cache, so it is one fix
serving at least two apps — three, once Maps' region picker is counted (§3.3.1 item 6).

#### 3.2.1 P4-1 as built — `IosFileDatabase` (2026-08-03)

`core-common/src/iosMain/.../IosFileDatabase.kt`, bound in place of the in-memory one. Bytes land in
a `files/` directory **beside the object store's `objects/`** under the same
[`sharedReplicaDirectory`] root — App-Group container when the entitlement grants one, app-local
Application Support otherwise — so the content plane inherits the C4e replica's location decision
rather than inventing a second one. Threat-model **A5 amended** in the same PR (House Rule #2): the
privacy delta is exactly the one already accepted for the metadata replica, one plane down, and the
*new* fact worth recording is that plaintext which previously died with the process now persists on
disk.

⚠⚠ **The four bounded-IO overrides are the substance, not boilerplate.** `FileDatabase` supplies
defaults for `readBytesRange`/`writeBytesRange`/`moveFile`/`fileSize` that are *correct but
unbounded* — they read or rewrite the whole file. Inheriting them would have reintroduced the OOM
that the chunked content plane exists to avoid, on the platform where the byte budget is already
tightest (`ios-drive-parity.md` P4: multi-MB must never cross the SKIE bridge). So all four are true
positional IO over `NSFileHandle` / directory-entry reads / rename.

Three Foundation behaviours differ from the `java.io` the other impls are written against, each
silently wrong rather than loudly broken:

1. ⚠ `NSFileManager.moveItemAtPath` **fails on an existing destination** instead of replacing it
   (unlike `File.renameTo`), so the reassembly path's promote step would have kept the stale file.
   The destination is removed first.
2. ⚠ `fileHandleForUpdatingAtPath` **returns nil for a path that does not exist** rather than
   creating it, so a ranged write to a fresh file — which is how every reassembly starts — would
   have been a silent no-op. The file is created empty first.
3. ⚠ Callers key into a **virtual** tree (`/.health/streams/42`), not the host filesystem. `File(dir,
   path)` absorbs a leading slash on the JVM; string concatenation does not, so it is stripped
   explicitly. Without this every path would have escaped the container to the device root.

Tests: `IosFileDatabaseTest` (8 cases, `iosSimulatorArm64Test`). Only one of them —
`bytesSurviveANewInstanceOverTheSameDirectory` — would fail against the old binding; the rest pin the
Foundation-specific behaviour above, which is where the bugs would have been.

⚠ **Not fixed here, and not new:** eviction. The cache is bounded only by free disk, on iOS as on
Android (`retention-quota-model.md` tier 3 / phase B — the "max local data" ceiling + LRU over
`EvictFileBytes` is unbuilt on *every* platform). This slice makes iOS match the others rather than
lead them.

⚠ **Noticed while wiring: desktop bound `InMemoryFileDatabase` too** — same durability hole, and
**closed the next day (2026-08-03)** in its own PR, which needed no new implementation at all: the
hub had already written a disk-backed JVM `FileDatabase` (`HubFileDatabase`, hub proposal H3) whose
own KDoc said to promote it to a shared JVM source set once a second consumer appeared. It is now
`core-common`'s `JvmFileDatabase`, shared by hub and desktop Control. ⚠ That change also had to add
`FileDatabase` overrides to both desktop integration harnesses — they override `ObjectStore` but
never `FileDatabase`, which was harmless while the binding was in-memory and would otherwise have
written test content into the developer's real app data directory.

### 3.3 Maps needs three units, not one — B-3 — **CLOSED**

1. export `:maps-shared-logic` from the umbrella + a `MapsSurface` seam;
2. a `MapsLocalRuntime` (route-1, the `ios-first-party-route-1-migration.md` P4 that has been parked
   for want of a shell);
3. the SwiftUI screens — Places (4 tabs), place detail, region picker.

And the map is the place-detail **hero**, not an accessory, so a Maps built before B-1 is a list app
with a coloured rectangle where the map should be.

### 3.3.1 P5 as built — Maps on iOS (2026-08-01)

All three units landed in one slice. Places, place detail and the region picker render on an iPhone 16
simulator through the shipping views; the detail hero draws live MapLibre tiles over the bundled
Firenze PMTiles with a centre pin, labels and the attribution chip.

**Unit 1 — export + seam.** `export(:maps-shared-logic)` + an unexported `api(:maps-shared-data)`
(the `:timeline-shared-data` posture: Swift drives it through the surface's Kotlin methods and never
names `PlaceProjectionRepository`). `MapsSurface` is the `TimelineSurface` twin with **two** injected
seams rather than one — Maps' view models take a `PlaceRepository` *and* a `RegionRepository` — which
install and tear down together, because both are reads over the same `TenshuClient`. Default =
`NotConnectedPlaceRepository` + `NotConnectedRegionRepository` (D4); `installMock()` is a
test/preview/probe surface. One dependency was needed: `kotlinx-datetime` on the umbrella's own
classpath, for `MapsSurface.today()` — the type is already fully exported through Fit's and Timeline's
models, but every `-shared-logic` module holds it as `implementation`, so it is not transitive.

> ⚠⚠ **The export silently renamed *Timeline's* Swift types.** Kotlin/Native disambiguates two
> classes that would share one Obj-C name by appending `_` to whichever fq name sorts later — and
> `app.tenshu.maps.model.Place` sorts before `app.tenshu.timeline.model.Place`. Exporting
> `:maps-shared-logic` therefore turned the *shipped* `timeline-ios`'s `Place` into `Place_`, and the
> same for `GeoPoint`, `PlaceDetailViewModel` and `PlaceDetailUiState`. Nothing warns; the shell just
> stops compiling, and a less lucky collision (same shape, different meaning) would compile and be
> wrong. **Fix: the newcomer takes the qualified name** — `@ObjCName("MapsPlace")` etc. on the four
> Maps types (`@kotlin.native.ObjCName`, usable from `commonMain`, Kotlin-side and Compose-side
> untouched). ⚠ A nested member's `ObjCName` is *appended to its parent's*, so the sealed state's
> members are annotated `"Loaded"`, not `"MapsPlaceDetailUiStateLoaded"`. **This is a standing rule
> for every future umbrella export, not a one-off.**

**Unit 2 — `MapsLocalRuntime`.** A thin `FirstPartyLocalRuntime` consumer, structurally identical to
`TimelineLocalRuntime`. It needed **no new service wiring**: the route-1 graph
(`buildProxyModule()` + the iosMain `platformProxyModule`) already hosts `:place-service` *and*
`MapRegionService` — the same fact §3.1's region resolvers rest on. The only Maps-specific parts are
the two repositories and the **one** shared `ControlReachability` engine (`mapsModules()` binds one
per app, so the banner cannot flicker between two verdicts). `ClientRegionRepository` takes the
`RegionBundleStager` *directly* — Maps' seam is `RegionRepository`, not the
`RegionBundleResolver` Timeline and Fit adapt — so `IosRegionSupport.stager` stopped being private.
Covered by `MapsLocalRuntimeTest`. **This closes `ios-first-party-route-1-migration.md` P4**, and with
it that proposal's phase map.

**Unit 3 — the SwiftUI shell.** `maps-ios`, mirroring the Compose IA: Places is the single top-level
destination with its four tabs *inside* it (D1), a tapped place pushes the detail, the gear pushes the
picker. Route-1 gate + Keychain/App-Group entitlements from the first commit, learning from
`timeline-ios` P3-2 (a shell with no entitlements has no Keychain, so the gate can never see
provisioning and the app is permanently "not connected").

**Findings:**

1. ✅ **The two-line map include really did suffice.** `include: ../ios-basemap/basemap.yml` +
   `templates: [MapBearingApp]` and nothing else — no extra package, no extra resource reference, no
   link flag. Consumer #3 validates the factoring claim P3 made in advance.
2. ⚠⚠ **The region picker is a list, not a map, on *both* platforms** — so the open question about
   `TenshuMapView`'s never-exercised `interactive` flag did not arise, and it remains unexercised.
   The catalog is a fixed set of pre-built signed extracts (`maps-region-delivery.md`), so there is
   nothing to drag a selection rectangle over; the choice is which of N named regions to hold. An
   interactive map would be a *new* IA, not parity, and inventing one to exercise a flag would be the
   wrong order. **`interactive` is still unproven, and a future consumer must prove it by render.**
3. ⚠ **`MapsPlaceProjection` fills `location` unconditionally**, so the place hero's gate cannot be
   nullability — `(0, 0)` is the Atlantic (D11). `MapsPlace.hasCoordinate` mirrors `timeline-ios`'s
   `Place.hasCoordinate` exactly. ⚠ The render probe cannot reach that arm: every *mock* place is
   projected into the Firenze box, while a coordinate-less place is the *real* store's common case.
4. ⚠ **A `Text` interpolation of an integer is locale-grouped.** The Compose picker renders
   `byteSize / 1_000_000` raw; the literal Swift translation printed a 2.1 GB region as "2.100 MB",
   which is indistinguishable from 2.1 MB in a decimal-comma locale. Both iOS surfaces now use the
   `formatRegionSize` helper (GB above 1024 MiB).
5. ⚠ **`MapsSurface.today()` returns the real device date**, where the Compose composition root still
   passes `MapsMockData.today` (a frozen anchor that keeps the mock's relative dates readable). The
   shipping surface here is the real store, and against real visit dates a frozen "today" mis-dates
   every "3 days ago"; the probe accepts the drift.
6. ⚠ Both the picker and the detail's offline row **name the B-2 limitation in the copy** ("downloads
   last for this session only") rather than offering a button that silently forgets — D3's posture,
   applied to the one screen that would otherwise ship the offer Timeline is still waiting on.
   ⚠ **Superseded 2026-10-17 (App Store review prep):** the caveat and the picker's "the map
   fetches only the tiles it needs" were both false on iOS — B-2 is closed, and with B-4 open the
   renderer draws only a downloaded region, the bundled demo or the schematic. The copy now says
   that, and "Downloaded regions stay on this device until you delete them". ⚠ It deliberately
   makes no per-app / device-wide claim (P4-2's App Group caveat), and the download → relaunch →
   render chain is still unwatched end-to-end.

**Not proven:** on-device (simulator only); the live route-1 data path (the probes run over the mock,
and the shipping default is honestly empty — a Control-genesis A/B pass is the same tail Fit and
Timeline carry); `interactive`; and B-4's streamed region path, which is still Kotlin work.

### 3.4 `streamSource` is not implemented on iOS — B-4

`:ui-map`'s iOS actual ignores it and falls through region → demo → schematic, so the surface never
blanks.

⚠⚠ **This is Kotlin work, not SwiftUI work** — for two independent reasons. `buildStreamedPartial`'s
file writing is Android-specific, *and* (found in P2a) **`RegionStreamSource` cannot be
constructed from Swift at all**: its `readRange` is a suspend function type, exported as
`id<KotlinSuspendFunction2>` — a Kotlin interface Swift cannot conform to, for which SKIE generates
no wrapper. The streamed-default region path has to be assembled Kotlin-side and handed to Swift
already built.

---

## 4. Standing constraints

- ⚠⚠ **MapKit is never the renderer** — MapLibre over offline PMTiles is what every user gets, on
  every platform. Apple-served tiles would disclose a route's region to a third party on every
  detail open (the A35 posture), which is why `timeline-ios/Sources/RouteSketch.swift:6-13` exists.
  ⚠ **Narrowed from "ever" to "not by default" 2026-08-13:**
  [`ios-apple-maps-option.md`](ios-apple-maps-option.md) is **accepted** — a **user-elected,
  off-by-default, revocable** MapKit may render the three heroes on iOS, made live by D10 (our own
  tile plane now egresses per view too). ⚠⚠ That licenses MapKit *only* behind the election: no
  MapKit without `MapRendererControl` (AM-1 + AM-2 land together), never as the default, never on
  the region picker, not on macOS or Android. The threat-model amendment owes the first such PR.
- **Route-1 is in-process**, so an iOS first-party app's proxy and client die together. This is a
  real, recordable benefit: the client-resubscription gap (`core-client-resubscription.md`) is
  structurally impossible on iOS route-1.
- **The `OpenMoveJournal` implementations are `internal`** to `:timeline-shared-data`. Recovery
  logic belongs inside that module behind a narrow entry point; do not widen the factories.
- **iOS storage goes in Application Support, never Caches** (`iosRecordedWorkoutOutbox`,
  `IosOpenMoveJournal`, `IosRecordingJournal`, and now `<Application Support>/map-regions`). A file
  the renderer holds open must not be reclaimable mid-render.

---

## 5. Phases

| Phase | What | Gated on |
|---|---|---|
| **P1** | Fit + Timeline SwiftUI parity; iOS region data path; `:ui-map` iOS renderer | — **DONE** |
| **P2a** | `:basemap-core` extraction — the Compose-free half, exported through the existing umbrella | — **DONE** |
| **P2b** | The SwiftUI map view over MapLibre's native iOS SDK + **one** consumer: Fit's workout route map | — **DONE** 2026-08-01 (§3.1.1). The first real tile drawn on iOS. |
| **P3** | Timeline's three map heroes (moment route, trip route, place map) + the shared Swift map target | — **DONE** 2026-08-01 (§3.1.2). `ios-basemap/` + one XcodeGen `include`. |
| **P4-1** | Durable iOS `FileDatabase` — `IosFileDatabase` + binding | — **DONE** 2026-08-03 (§3.2.1). B-2 closed; threat-model A5 amended. |
| **P4-2** | The consumers: keep-offline UI in Timeline, and un-caveat Maps' region copy | P4-1. ⚠ Needs a real download surviving a relaunch on the sim first — see §3.2.1. ⚠⚠ The re-written copy must say the region is held **per app**, not device-wide: without the App Group each route-1 app has its own store — [`ios-control-offline-maps.md`](ios-control-offline-maps.md) §2. |
| **P5** | Maps on iOS — export + `MapsSurface` → `MapsLocalRuntime` → SwiftUI | — **DONE** 2026-08-01 (§3.3.1). B-3 closed; `ios-first-party-route-1-migration.md` P4 closed with it. |
| **P6** | Tail: `streamSource` on iOS, Wi-Fi controls, diagnostics log tail | B-4 |

**P4-2 or P6 next.** P4-1 landed the store, which unblocks *three* surfaces rather than two —
Timeline's keep-offline row, Drive's plaintext cache, and Maps' region picker, which still tells the
user its downloads expire with the process. ⚠ That copy is now **stale rather than wrong-and-shipped
on purpose**, and it is deliberately left alone until someone watches a real region download survive
a relaunch: promising persistence is a claim about a path no test exercises end-to-end (the unit
suite proves the store, not the download → store → renderer chain).

---

## 6. Smaller items and modelling mismatches

These are cheap but each needs a decision, not just code:

- ⚠ **`FitRepository.historyAccess()` defaults to `Unsupported`** and no iOS repository overrides it
  (`fit-shared-logic/.../FitRepository.kt:179`), so the full-history import row shipped in P1 can
  never appear on iOS. Honest — HealthKit has no 30-day gate the way Health Connect does — but it
  means the affordance is currently dead code on the platform. Either give iOS a real
  `historyAccess`, or decide the row is Android-only and say so.
- ⚠ **`canImportFiles` is structurally `false` on iOS, yet iOS genuinely can import files.**
  `FitSurface.profileViewModel()` builds `ProfileViewModel(installed)` with no `HealthFilePicker`,
  because the picker is the Swift `.fileImporter` — owned by the shell, not by Kotlin. The Takeout
  row is deliberately *not* gated on the flag, since that would hide a working feature. The model
  says "cannot" where the platform says "can".
- **Import copy diverges by platform.** Compose's strings are Health-Connect-specific; the iOS
  strings name Apple Health. `ImportStatus` already carries the `ImportSource`, so this becomes
  source-parameterised whenever the copy is centralised.
- **`:timeline-shared-ui` / `:fit-shared-ui` hold the Compose-side `TerminusRegionBundleResolver`
  twins**, which iOS cannot depend on. Single-sourcing them means relocating into the `-shared-data`
  modules *and* giving `:map-region-app` a `js` target — a cross-platform build change, not iOS
  wiring, so P1's region data path left it deliberately duplicated.
- **Pre-existing warning worth one sweep:** `do { for try await … } catch {}` in the Timeline detail
  views warns "'catch' block is unreachable" — SKIE's `StateFlow` sequence does not throw.

---

## 7. Verification posture

Two hazards specific to this area, both hit during P1:

- ⚠ **A Kotlin-only `iosTest` does not verify a SwiftUI slice.** Use `xcodebuild` on the app scheme,
  and remember there are two (`FitIos` / `FitIos-Dev`).
- ⚠ **Stale DerivedData fails at `CreateBuildDescription` with "There is no XCFramework found at
  \<path\>" even though the path exists.** Fix with a fresh `-derivedDataPath build/dd`, not by
  rebuilding the framework. And `xcodebuild` sitting at 0% CPU means Xcode.app holds the project.
- ⚠ `timeline-ios/Info.plist` is **xcodegen-generated and gitignored** — plist keys go in
  `project.yml` `info.properties`, then regenerate. A new Swift file also needs a regen, or the
  project silently omits it.

---

## 8. Decisions recorded here

| # | Decision | Where it came from |
|---|---|---|
| D1 | MapLibre over offline PMTiles is the renderer every user gets, or no map. ⚠ **Narrowed 2026-08-13** from an absolute to a *default*: [`ios-apple-maps-option.md`](ios-apple-maps-option.md) is accepted, so an off-by-default, revocable, per-device MapKit election may render the three heroes on iOS — and nothing else (its D6) | A35; `RouteSketch.swift:6-13` |
| D2 | ~~The framework-export shape belongs to the first consumer~~ — **superseded by D7** | P1 (`:ui-map` iOS renderer) |
| D7 | **No Compose reaches iOS.** Split `:ui-map` into a Compose-free `:basemap-core` (shared logic) + a Compose UI layer; the iOS view is SwiftUI over native MapLibre. House Rule #5's shape | §3.1 (P2a) |
| D8 | Colour crosses the boundary as an opaque `#RRGGBB` **String** — the style's own serialisation. ARGB `Int` exports as `Int32` and the natural Swift literal does not fit it; an inline class would make Kotlin/Native mangle the very names the extraction exists to expose | §3.1 (P2a) |
| D9 | **A map-bearing iOS app carries MapLibre (SPM) and mirrors the tile/glyph payload into its own bundle itself.** The umbrella carries only the style logic; `:basemap-core` is MapLibre-free, so there is no cinterop and no link-order rule on this path. The iOS analogue of Android's manifest permission-strip rule | §3.1.1 (P2b) |
| D10 | **Shared Swift is a sources directory + an XcodeGen `include`, not a local Swift package.** SwiftPM resolves a `.binaryTarget` before any build phase runs, so a package consuming the Gradle-built `TenshuSdk.xcframework` fails on a fresh `-derivedDataPath`; a package's resources also land in the wrong bundle. One `include` carries D9's whole obligation set, so consumer #3 inherits it | §3.1.2 (P3) |
| D11 | **The place hero is a point, not a one-element route.** `routeBasemapStyleJson` would draw a degenerate line + stacked endpoint dots under the pin; and a place with no coordinate gets the flat hero, never a map of `(0, 0)` | §3.1.2 (P3) |
| D3 | Keep-offline does not ship on iOS until a durable `FileDatabase` exists — an offer that silently forgets is worse than no offer | §3.2 (P1 region data path) |
| D4 | An unprovisioned iOS app shows an honest empty state, never mock data | §2.2 (P1) |
| D5 | Region storage on iOS is Application Support, backup-excluded | P1 (region data path) |
| D6 | ~~Maps' iOS shell is sequenced **after** its real `:place-service`, not before~~ — **the premise expired; corrected 2026-08-01.** `MapsModules.kt` binds `PlaceProjectionRepository` over the real `TenshuClient` (`maps-app.md` Ph9); `mockPlaceRepositoryModule()` is a one-line revert only. What is left is a *thin-screens* tradeoff (the store carries only `{placeId, latE7, lngE7, capturedAt, label}` ⇒ everything lands under *Yours*), which is a reason for the screens to be honest, not a reason to wait | §2.3 |
| D12 | **An umbrella export must not take a Swift name off a shipped shell.** K/N resolves an Obj-C name clash by appending `_` to the later fq name, silently renaming the *other* module's type; the newcomer carries `@ObjCName("<App><Type>")` instead. ⚠ A nested member's `ObjCName` is appended to its parent's | §3.3.1 (P5) |
