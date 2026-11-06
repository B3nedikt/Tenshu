# :ui-map — module conventions

The reusable **offline vector basemap** surface, extracted from Maps Ph7b
([`docs/proposals/maps-app.md`](../docs/proposals/maps-app.md) §6) so every map consumer
(Maps today; Timeline, Photos later) shares one engine instead of copying it. android + desktop +
**iOS** (see *iOS* below — the renderer compiles and its tiles are bundled, but nothing has watched
it draw yet).

> ⚠⚠ **This module is now the Compose half only.** The style JSON, the palette, `routeCameraFrame`,
> `normalizeRoute` and `resolveBasemapSource` live in **[`:basemap-core`](../basemap-core/basemap-core-agent.md)**,
> which has no Compose on its classpath and *is* exported from the map-bearing apps' iOS frameworks (`tenshu-sdk-ios-<app>`). Anything you would
> want to reach from SwiftUI belongs there, not here. `BasemapCoord` and `RegionStreamSource` are
> re-exported here as typealiases so existing call sites did not have to change.

## Public API

- **`OfflineBasemap(modifier, height, seed, center, regionBundlePath, content)`** — `expect`/`actual`:
  - **Android** — a live **MapLibre Native** map (`org.maplibre.compose:maplibre-compose`) over a
    bundled offline PMTiles region.
  - **iOS** — the **same** live MapLibre map, from the same library and the same `commonMain` style
    (see *iOS* below).
  - **Desktop** — the **schematic** Canvas basemap (the MapLibre-Compose desktop target is
    early-stage; offline sources unsupported there).
  - `content` is overlaid (`BoxScope`, centre-anchored). `center: BasemapCoord?` frames a real
    point; when null a per-`seed` location inside the bundled region is used (mock/demo).
  - `regionBundlePath: String?` — the **app-side terminus of region-bundle delivery**
    (`docs/proposals/maps-region-delivery.md` D5/D6): an absolute path to a downloaded `.pmtiles`
    region already on local disk. When non-null and present, Android opens THAT region (staging it
    into `filesDir` first if it lives outside app-private storage) instead of the demo; a missing
    file / load failure falls back to the demo then the schematic — the surface never blanks.
    Desktop ignores it (schematic-only), as it does `center`. The resolution order is the pure
    `resolveBasemapSource(regionBundlePath, regionBundleExists, bundledDemoAvailable)` →
    `BasemapSource.{Region|BundledDemo|Schematic}` seam (unit-tested in `commonTest`; the eventual
    IPC byte-transfer plugs in behind the same staging path).
- **`BasemapChrome(showPin)`** — the standard overlay: centre pin + attribution chip ("Offline · MapLibre" over the sketch, the OSM credit over live tiles, "© Apple Maps · Legal" over the desktop snapshot; `LocalBasemapData`) +
  scale label. Shared so live and schematic renders stay pixel-identical.
- **`BasemapCoord(latitude, longitude, zoom)`**.

Consumers build their own chrome/overlays on top: Maps' `MapSnippet` is `OfflineBasemap { BasemapChrome() }`.

## House Rule #1 (load-bearing)

The basemap is **fully offline and keyless** — bundled PMTiles, a **label-free** style built at
runtime (no `glyphs`/`sprite`/symbol layers → no font/icon/tile fetch), loaded via
`pmtiles://file://…`. Nothing leaves the device.

- ℹ **Label-free is a ban on *fetching* glyphs, not a ban on labels** — and as of LB-1 the style
  **does** draw labels, still with zero egress. See *Labels & glyphs* below and
  [`docs/proposals/basemap-labels.md`](../docs/proposals/basemap-labels.md).

- ⚠ **`pmtiles://asset://` SIGABRTs** in maplibre-android-sdk 13.0.2 (a native abort
  `onMapLoadFailed` can't catch). Always copy the asset to `filesDir` and use `pmtiles://file://`.
- ⛔ **`pmtiles://file:///proc/self/fd/N` (a `ProxyFileDescriptor`) does NOT work** — spike-proven
  NEGATIVE 2026-07-13. maplibre-native 13.0.2's PMTiles reader does its **own `open()` on the path
  string** (it never reads through the fd you pass); opening the AppFuse-backed `/proc/self/fd/N`
  fails: `E Mbgl: Error fetching PMTiles header: Cannot read file /proc/self/fd/N`, and
  `ProxyFileDescriptorCallback.onRead` is never called. So **there is no "local path backed by lazy
  bytes" form maplibre accepts** — `file://` must be a *real, fully-materialized* file. This kills the
  streamed-default-via-fd design.
- ✅ **Streaming IS possible via a *partial sparse file*** (spike-proven POSITIVE 2026-07-13, screenshot;
  productionised — see below). Since a snippet is a **static camera**, the tiles MapLibre reads are a small
  computable set. Build a **sparse full-size `.pmtiles`** with only the header + directory + metadata +
  covering tiles populated (holes elsewhere → zero, MapLibre culls them). Real `file://` → renders — **no
  `INTERNET`, no loopback, HR#1 intact.** `PmtilesDirectory.kt` (commonMain, unit-tested) decodes the v3
  directory (Hilbert `zxyToTileId`, columnar-varint) to compute the covering ranges;
  `buildStreamedPartial` (androidMain) fetches them via a `RegionStreamSource.readRange` (bound to
  `streamRegionRange`) into the sparse file. See `maps-region-delivery.md` D10 / §7 phase-2.
- ⚠ **Every consuming *app* must strip the AAR-merged permissions** in its manifest, or a
  privacy-flagship app ships an unused `INTERNET` + location grant:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" tools:node="remove" />
  ```
  See `maps-android/src/main/AndroidManifest.xml` for the canonical example. (`ACCESS_NETWORK_STATE`
  stays — MapLibre's connectivity receiver reads it; no egress.)

## iOS

The iOS `actual`s (`src/iosMain/`) render the **same live MapLibre Native map as Android**, from the
same `maplibre-compose` artifact and the same `commonMain` style. iOS is not a second-class
schematic: `maplibre-compose` (version pinned in `gradle/libs.versions.toml`) is a genuine KMP
library that publishes `iosArm64` +
`iosSimulatorArm64` klibs, and `MaplibreMap` / `BaseStyle.Json` / `rememberCameraState` all live in
*its* commonMain, so the two renderers are near-identical code.

### ⛔ MapKit is never the default — narrowed from "never at all" on 2026-08-13

Apple-served map tiles disclose the viewed region to a third party on **every detail open**. That
is why `timeline-ios/Sources/RouteSketch.swift` was an abstract normalized sketch rather than a map
(threat model A35) — it is that screen's **no-tiles fallback** now, under a real basemap — and why
MapLibre over device-local PMTiles — no network egress, no API key, House Rule #1 intact — is what
every user gets by default.

⚠⚠ **The old absolute rule ("No MapKit. Ever.") was narrowed to a *default*, not repealed.**
[`ios-apple-maps-option.md`](../docs/proposals/ios-apple-maps-option.md) shipped AM-1…AM-5 on
iOS/iPadOS **and** macOS: MapKit is now a legitimate renderer **only** as an explicit, revocable,
**off-by-default election**, and only for the three heroes (place detail, trip/movement route,
workout route). It remains forbidden as *the* renderer (D1), as the region picker, as an
Explore/Navigate surface, and on **Android** at all.

⚠ The election is per **app**, not per device (D9 corrected D2). ⚠⚠ A hero that renders MapKit
with **no consent gate** breaks the absolute rule rather than narrowing the default — which is why
the election (AM-1) landed *before* the renderer (AM-2). So: still do not "simplify" any of this
to MapKit; adding it means adding an election, and the A35 amendment goes in the same PR.

### ⚠⚠ The consumer contract — the iOS analogue of the manifest permission-strip rule

Android consumers must strip AAR-merged permissions. iOS consumers owe something different but just
as load-bearing: **the MapLibre binary framework is NOT in the Gradle artifact.** The published
klib carries a *cinterop* (`…Cinterop-MapLibreMain-….klib`) — headers only. Every consuming iOS app
must therefore:

1. add **MapLibre 6.x** itself, via SPM (`maplibre-gl-native-distribution`) or CocoaPods
   (`pod("MapLibre", "6.25.1")`); and
2. order **Other Linker Flags** so the Kotlin framework precedes MapLibre:
   `-framework <KotlinFramework> -framework MapLibre`.

Without (2) the link fails or the map silently no-ops. This is upstream's documented requirement,
not a Tenshu quirk.

⚠⚠ **The framework-export question is SETTLED, and the answer was "neither module goes in".**
Every iOS app here links one **static** `TenshuSdk` framework (its own `tenshu-sdk-ios-<app>`; until
`ios-per-app-frameworks.md` P5 it was one shared umbrella, `tenshu-sdk-ios`), and those frameworks
export **only Compose-free modules** — services, clients, and the `*-shared-logic`
presenters SwiftUI binds to. Every iOS app in this repo is SwiftUI; **no Compose Multiplatform UI
ships on iOS at all**. The three options a first consumer faced were:

- put `:ui-map` in the (then shared) umbrella → the **whole Compose runtime + Skia** lands in every iOS app that
  links it, including the ones with no map, *and* the MapLibre link requirement comes with it;
- build a **separate framework** for map-bearing apps, so the cost is paid only where the map is;
- host the Compose map in a `ComposeUIViewController` from a per-app framework.

**Decision: none of them — split the module instead.** Mirroring House Rule #5, which makes the same
call for the web: **share the map's logic, write the iOS map view in SwiftUI over MapLibre's native
iOS SDK.** The shareable part of a map is not its UI, and the measurement said so plainly —
`BasemapStyle.kt` was 598 lines with zero `@Composable`, importing Compose for one thing (`Color`),
in a module with 8 composables total. That Compose-free part is now
[`:basemap-core`](../basemap-core/basemap-core-agent.md) and it *is* exported from the map-bearing apps' frameworks; the
Compose runtime still never reaches iOS.

⚠ The iOS `actual`s below therefore are **not** the shipping path any more. They are left in place
deliberately: deleting them belongs with the slice that proves a SwiftUI view actually draws a tile,
not before it.

### Tiles and glyphs — no `asset://`, and no staging

`asset://` is the APK asset manager and has no iOS counterpart, so:

- The **style's glyph URL is parameterised** (`glyphsUrl`, defaulting to the Android `asset://`
  constant). iOS passes `fileGlyphsUrl(...)` — a `file://` directory. The no-egress guard already
  accepted `file://`, so House Rule #1 is unaffected: the rule is about the bytes being on the
  device, not about which local scheme names them.
- Tiles + glyphs reach the app bundle as **Compose resource files**, mirrored from
  `src/androidMain/assets` by the `stageIosBasemapResources` Sync task. ⚠ They are **mirrored, not
  duplicated in the repo** — the archive tests open `src/androidMain/assets/…` by relative path, so
  the Android tree stays the single source and the iOS copy cannot drift from the one under test.
- ✅ **The `pmtiles://asset://` SIGABRT hazard has no iOS equivalent.** A Compose resource on iOS is
  already a real file on disk, so `pmtiles://file://` names the bundle copy **in place** — nothing is
  copied into `filesDir` first. Nor is a downloaded region staged: everything this process can read
  is already inside its sandbox. Both Android staging paths simply do not exist here.
- The glyph directory is derived from the **archive's** URI, not from a glyph file. `{fontstack}` is
  substituted verbatim by MapLibre and the fontstack names contain spaces (`"Noto Sans Regular"`);
  deriving from a space-free path keeps percent-encoding out of the base.
- A missing glyph atlas degrades to a **label-free** style, not to a broken one — a template MapLibre
  cannot resolve would cost a load error per label, while dropping labels still leaves a readable map.

### Known gaps

- ⚠ **`streamSource` (the D10 sparse-file streamed default) is not implemented on iOS.** It is
  ignored; resolution falls through region → bundled demo → schematic, so a caller that passes one
  still renders. The pieces are portable (`PmtilesDirectory` already targets iOS); only
  `buildStreamedPartial`'s file writing is Android-specific.
- ✅ **PMTiles-on-iOS is PROVEN (2026-08-01, P2b).** `pmtiles://file://<abs path>` reads the bundled
  archive on **MapLibre 6.28.0's** native iOS SDK — witnessed as a rendered map (Firenze streets, the
  Arno, and **labels**, so the `file://` glyph atlas resolves too) in `fit-ios`'s workout detail on a
  simulator. It was proven through the **SwiftUI** path, not this module: same style JSON, same
  `pmtiles://file://` URL shape, same glyph template, so the finding transfers, but the *Compose* iOS
  actuals below are still unwitnessed and are not the shipping path.
- `:ui-core` gained the same two iOS targets (`:ui-map`'s commonMain uses its spacing tokens). Its
  `expect` `KmpNavHost` has a **minimal, documented iOS actual**: no iOS app here navigates in
  Compose. (`QrScanner` moved to `:ui-qr-scanner`, which has no iOS target at all.) Anyone making
  `KmpNavHost` a real iOS navigation host must add
  the per-entry `ViewModelStore` and bridge the interactive pop gesture.

### ⚠⚠ What is proven, and what is not

**Proven:** `:ui-map:compileKotlinIosSimulatorArm64` is green — the actuals typecheck against
maplibre-compose's iOS klib; the tiles + glyph atlas land at the exact bundle path
`Res.getUri("files/…")` resolves; `:ui-map:desktopTest` and the full gate stay green. And, since
P2b, the *substrate* is proven on a real simulator: MapLibre iOS 6.28.0 reads
`pmtiles://file://<abs path>`, resolves `file://` SDF glyphs, and draws our archive.

**Not proven — and now unlikely ever to be:** **nobody has seen the Compose iOS actuals below draw a
tile.** The first real tile on iOS was drawn by `fit-ios`'s **SwiftUI** `TenshuMapView` (D7), which
does not link this module at all. Everything cartographic it uses lives in `:basemap-core`; what is
untested here is only the maplibre-compose binding. Under D7 these actuals are scheduled for removal,
so treat them as dead weight awaiting a cleanup rather than as a path to build on.

⚠ **The `stageIosBasemapResources` Sync task feeds only this module's own Compose resources.** A
SwiftUI consumer gets nothing from it and must mirror `src/androidMain/assets` into its own app
bundle — see `fit-ios/project.yml` for the worked example. Keep mirroring; do not copy the archive
into a second place in the repo, because the `*RealArchiveTest`s open this tree by relative path.

## Labels & glyphs

The style draws place labels (LB-1). MapLibre renders `text-field` from **SDF glyph PBFs** fetched
from the style's `glyphs` template — so the atlas is **shipped in the APK** and the template is
`asset://glyphs/{fontstack}/{range}.pbf`. Nothing is fetched; House Rule #1 holds.

`src/androidMain/assets/glyphs/` — Noto Sans (OFL 1.1, `OFL.txt` bundled beside it), from
[protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets/tree/main/fonts), the same
source as our v4 tiles. **Regular + Medium, ranges `0-255` + `256-511`** (~416 KB total): Basic
Latin + Latin-1 covers de/it/fr/es, Latin Extended-A covers cs/pl/hr/hu/sk. Greek (`768-1023`) and
Cyrillic (`1024-1279`) are *not* shipped — add the range files when a region needs them. CJK/Hangul
must never be shipped: `MapLibreMapOptions.localIdeographFontFamily` renders those from device
system fonts.

- ⚠ **`text-font` must equal the asset directory name, spaces and all** (`"Noto Sans Medium"`).
  `{fontstack}` is substituted verbatim into the asset path. Verified working on-device with the
  space in the name — `BasemapStyleTest` pins the two constants to the shipped directories.
- ⚠ **`routeBasemapStyle` rebuilds the style root** rather than copying it, so every root key
  (`glyphs` included) must be carried across by hand or the route map inherits symbol layers with no
  font. Guarded by `BasemapStyleTest.routeStyleCarriesGlyphsAcrossTheRebuild`.
- The style JSON lives in **`:basemap-core`**, not beside a renderer, so `commonTest` can assert it —
  in particular the **no-egress guard**, which walks every string in the emitted style and fails on
  any non-device-local URL. Keep it that way: the guard has to travel with the style, or the
  platforms that assemble their glyph URL at runtime are the ones it never covers.
- ⚠⚠ **Gate label density with the layer's `minzoom`, never with `["zoom"]` inside a `filter`.**
  Filters are evaluated per *tile*, so filter-`zoom` is the tile's canonical zoom and is capped by the
  archive's `maxzoom`: on the demo archive (maxzoom 14) a `>= 15` filter branch can **never** fire,
  and on the deployed regions (maxzoom 15) a `>= 16` branch never fires either — overzoom does not
  raise it. Layer `minzoom` compares against the *camera*, which is what "show this once the user is
  zoomed in" actually means. Road labels are split into `roads-labels-{major,minor,path}` purely so
  each class gets its own `minzoom`. (The per-feature `min_zoom` **attribute** gate is a different
  thing and is correctly compared against tile zoom.)
- Label density is bounded by MapLibre's collision engine and available line length, **not** by the
  feature count: one z14 tile over central Florence holds 294 named roads, and the 14.5 snippet
  camera draws exactly one street name. Tune by measuring on device, not by counting features.
- ⚠⚠ **POIs need a kind allowlist; `min_zoom` alone is not a sufficient gate.** Protomaps calibrates
  `min_zoom` for a full-screen map and for some kinds it is far too eager — 38–61 `library` features
  pass it in one central-Florence tile. `LANDMARK_POI_KINDS` cuts that to 10–14 real landmarks. POIs
  also carry **no `sort_key`**, so `symbol-sort-key` falls back to `min_zoom` as the prominence
  ranking; without it a random chapel can out-rank the Duomo.
- ⚠⚠ **Localised labels are gated on shipped glyphs, and the gate is the load-bearing part.** Tiles
  carry ~20 `name:<lang>` variants including `el`/`he`/`ja`/`ko`; rendering one whose script is not in
  the bundled ranges gives tofu boxes — worse than the endonym. `labelLanguageFor()` clears a
  language against an explicit `GLYPH_COVERED_LANGUAGES` list, deliberately **not** an "is it Latin"
  heuristic (Vietnamese is Latin but needs Latin Extended Additional, which we do not ship). Roads
  stay endonyms — you do not translate "Via dei Pandolfini".
- ℹ **CJK/Hangul already work; do not add code for them.** `MapLibreMapOptions
  .localIdeographFontFamilyEnabled` defaults to `true` and `createFromAttributes(context)` — which
  maplibre-compose calls internally — sets the family to `"sans-serif"`, so those glyphs come from
  device system fonts. Note maplibre-compose's `MapOptions` exposes **no** hook for this
  (render/gesture/ornament only), so if that default ever changes there is no clean override.

## Bundled tiles

`src/androidMain/assets/tenshu_basemap.pmtiles` — a small Firenze extract (ODbL), clipped with
`pmtiles extract` to the z12–14 band a fixed-zoom snippet needs (~1.3 MB). A **demo/proof** asset:
production swaps in the Organic-Maps region-bundle delivery (rides the content-delivery machinery).
Real per-place centring (SI lat/lng) arrives when consumers pass a `BasemapCoord` from the real
`:place-service`.

### ⚠⚠ Two archive generations are in play — do not "modernise" the demo asset

The demo asset and the deployed regions come from **different Protomaps basemap generations**, and
each has something the other lacks:

|  | bundled demo | deployed regions (`catalog.json`, `tileSchemaVersion: 4`) |
|---|---|---|
| attribute naming | `pmap:kind`, `pmap:min_zoom` | `kind`, `min_zoom`, `sort_key` |
| `transit` layer (rail) | **yes** | **no** (checked: `test-firenze`, `test-toscana`) |
| also carries | `natural`, `physical_line`, `physical_point` | — |

Consequences, both load-bearing:

1. **Style rules must read attributes under both namings** — that is what `tileAttr()` in
   `BasemapStyle.kt` is for. Pinned by `BasemapStyleTest.labelsReadAttributesUnderBothProtomapsNamings`.
   The vocabulary is identical (`locality`, `neighbourhood`, `major_road`, …); only the keys differ.
2. **The demo asset cannot simply be re-extracted from a current v4 build.** It is the only local
   archive carrying `transit`, and `MvtRealArchiveTest` / `RailTileReaderRealArchiveTest` /
   `RailMatchRealArchiveTest` (M9 rail matching) read it directly. Re-extracting from the 2026-07 v4
   source drops `transit` and takes 9 tests with it. **This was tried during LB-1 and reverted.**

⚠ **Open question for the tile pipeline (not a `:ui-map` issue):** the local v4 builds carry no
`transit` layer at all, so M9's rail matching may have nothing to match against in a *deployed*
region. Worth verifying against a real downloaded region before relying on it.

- ⚠⚠ **A changed asset does NOT invalidate `:ui-map:desktopTest`.** These tests open the archive by
  relative path (`File("src/androidMain/assets/…")`), not as a test resource, so Gradle sees no input
  change and reports a stale PASS. After touching any bundled archive, run
  `./gradlew :ui-map:desktopTest --rerun-tasks`. LB-1 shipped a green report on a stale cache this
  way.
- ⚠ Any future style rule keyed on tile attributes must be verified against **the demo asset**, not
  only a downloaded region — the demo is the fallback every fresh install renders.

## ⚠ Gotcha — a blank live basemap almost always means the camera is outside the tile extent

**Symptom.** On Android, the live MapLibre basemap draws **only the style background** — no
roads/water/buildings. The style loads, no crash, no error is logged.

**Root cause: it's a data/camera mismatch, NOT a MapLibre or `pmtiles extract` bug.** The
`pmtiles extract` archives render fine on maplibre-native Android — on-device instrumentation
(a logging HTTP range server in front of the archive) confirmed MapLibre reads the header,
metadata, **root directory**, and the covering **tiles** (incl. the exact centre tile at its
byte offset) and draws them. If the surface is blank, the camera is pointed at a location the
bundled/staged archive does **not cover**, so MapLibre correctly culls to empty.

The classic instance (found 2026-07-12): the bundled demo asset is a **Firenze** extract
(`FIRENZE_LNG/LAT` ≈ 11.256, 43.770), but the Maps **mock places** (`MapsMockData`) project onto
a **Lisbon** bbox (lat 38.69–38.75, lng −9.20…−9.10). Every place snippet centres on Lisbon →
no data in the Firenze extract → blank. Fix = make the demo self-consistent: either bundle a
**Lisbon** extract, or move the mock places' bbox onto Firenze. (`center == null` falls back to a
Firenze jitter and renders correctly — proof the extract is fine.)

**Don't re-file this as a MapLibre bug.** A 2026-07-12 investigation briefly (and wrongly)
concluded `pmtiles extract` output was un-renderable on Android and filed
[maplibre-native#4398](https://github.com/maplibre/maplibre-native/issues/4398) — since **retracted
and closed as invalid**. The earlier "differentiator is the encoder" reasoning was confounded by
the snippet camera sitting on a Lisbon place, outside the Firenze extent. Transport (`file://`,
loopback HTTP, remote HTTPS CDN) was never the issue either. Before suspecting the library, check
that the camera is inside the archive's geographic bounds.
