# Open-source licence notices and data attributions

**Status:** shipped on Android, desktop, iOS and web. iOS first compiled 2026-10-24 (all ten apps,
`scripts/ios-smoke.sh --build-only`; each bundle carries a 71-entry `kotlin-licences.json`). Counsel
has not reviewed §4.

Every Tenshu binary ships the notices for the third-party code it links and credits the data it
shows. Apache-2.0 §4, MIT and BSD require the notice *with the binary*; the ODbL requires the
OpenStreetMap credit wherever its data is shown. This doc is the rule set, the wiring, and the
licence audit a human decided against.

## 1. What each artefact ships, and where its notices come from

| Artefact | Code notices | Shown in |
|----------|--------------|----------|
| Android apps (10) | AboutLibraries per variant → `res/raw/aboutlibraries.json` | `:ui-core` `OpenSourceLicencesScreen` |
| Desktop apps (10, Control included) | `exportLibraryDefinitionsJvm` → `jvmMain` resources `/aboutlibraries.json` | same |
| iOS apps (10) | `:tenshu-sdk-ios-<app>:exportLibraryDefinitions` → bundle `kotlin-licences.json`; `ios-about/Resources/swift-licences.json` (libsodium, SKIE runtime); `ios-basemap/Resources/maplibre-licences.json` | `ios-about` `OpenSourceLicencesView` |
| Web apex | `web/apex/support-pages/thirdParty.ts` (node_modules walk, checked against the real bundle) + `sdkBundle.ts` (hand list of the Kotlin/JS SDK's contents) | `/legal/licenses/<lang>` |
| Servers | not distributed → no notices | — |

The Gradle side is one convention in the root `build.gradle.kts` (*Open-source licence notices*),
applied by plugin/name so a new app gets it without asking.

⚠⚠ **iOS needs `collect.all` and the variant-less task.** Kotlin/Native resolves
`iosArm64CompileKlibraries`, which the plugin's default `*CompileClasspath` selection never matches,
and its per-target `exportLibraryDefinitionsIosArm64` then matches nothing either — both produce an
**empty list** with nothing but an info line (measured, plugin 15.2.0).

⚠⚠ **Offline mode; licence texts are checked in** (`config/oss-licences/licenses/`). Online fetching
was the first attempt and dropped the Apache-2.0 text from some apps' JSON on a flaky connection,
silently. `OssLicencesJsonTest` (`control-desktop`) pins: non-empty, names coroutines/Ktor/stdlib/
Material3, every library has a licence, every licence has its text.

⚠ **Not `aboutlibraries-core` at runtime.** 15.2.0's JVM classes are Java 21 bytecode; the shared
modules run on 17, and the first parse fails at class load. `:ui-core` parses the JSON itself.

### 1.1 Copyright lines and NOTICE files — what the POMs do not carry

The plugin reads licences from POMs, which carry neither of two things the licences demand. A
`doLast` on the plugin's own task (root `build.gradle.kts`, *Copyright lines and NOTICE files*) adds
both to the generated JSON in place, so Android, desktop and the iOS bundle copy all get them:

- **The copyright line.** MIT, BSD-2/3 and ISC require "the above copyright notice", but the
  checked-in SPDX texts are templates (`Copyright (c) <year> <copyright holders>`). Each library on
  such a licence gets its own copy of the text with the real line: read from the artifact's own
  `LICENSE*`/`COPYING*` file first (slf4j, checker-qual), else from
  `config/oss-licences/copyrights.json` (exact id or `group:prefix*`, each line copied from the
  project's LICENSE, source noted in `_sources`).
  ⚠⚠ A template-licensed library with neither **fails the build**, naming it — the fix is a line in
  `copyrights.json`, never a placeholder that ships. `OssLicencesJsonTest` also asserts no shipped
  text contains `<year>`/`<owner>`/`<copyright holders>`.
- **Apache-2.0 §4(d) NOTICE files.** Every `NOTICE`, `NOTICE.txt`, `NOTICE.md` or
  `ThirdPartyNotices*` inside a resolved artifact is attached verbatim to its library as an extra
  licence entry named **"NOTICE"** — the screens show it after the licence text with no screen code
  of their own (PDFBox, fontbox, pdfbox-io, commons-logging, jakarta.inject, ONNX Runtime today).
  An artifact that ships its notices elsewhere gets a hand copy in
  `config/oss-licences/notices/<group>__<artifact>@<version>.txt` (today only
  `onnxruntime-android`, whose AAR omits the `ThirdPartyNotices.txt` the jar carries).
  ⚠ The copy is pinned to the version in its filename: a bump **fails the build** until it is
  re-copied from the new release.

⚠ Only external modules are read (`componentFilter`), so the licence task never waits on our own
compiles. ⚠ Kotlin/Native klibs carry no LICENSE/NOTICE files, so the iOS frameworks get the
hand-kept half only; today no iOS-linked library needs either.

## 2. The screens

- **Compose (Android/desktop):** `OpenSourceLicencesListItem` in each app's settings; it pushes
  `OpenSourceLicencesScreen(dataAttributions = …)`. Where the app has no settings screen, the row
  sits on the nearest management screen: Calendar → under *Calendars*; Maps → end of *Offline maps*
  (where its settings gear leads); Home → under its Settings placeholder.
  ⚠ The licences row always sits **directly after the privacy-policy row** (`AppPrivacyPolicyListItem`
  / `PrivacyPolicyLink`): the legal entries close each screen in one order, privacy policy
  then licences, on every app and platform — Calendar included, whose *Calendars* screen is also
  where its privacy link lives.
  ⚠ Mia and Home pad the system bars in the shell, Fit the bottom one: pass
  `Modifier.consumeWindowInsets(…)` or the top bar pads twice (`ui-agent.md` §8).
- **iOS:** `OpenSourceLicencesLink(data:)` in the same places (Control: *About*; Timeline: *About*;
  Fit: *Profile*; Contacts: *You*).
- **Web:** footer link *Licences* on every apex page. ⚠ Named "Third-party licences", not
  "open source": `store-presence-and-support-pages.md` §4.7 forbids an "open source" claim on the
  site while the repository is private, and tests enforce it.

## 3. Data attributions — who owes what

| Credit | Licence | Owed by | Where |
|--------|---------|---------|-------|
| © OpenStreetMap contributors | ODbL 1.0 | Maps, Timeline, Fit (render tiles); Control (serves the regions) | ⚠⚠ **on the map** (basemap chip over live tiles) + licences screen |
| Protomaps basemap schema | BSD-3 (schema), data ODbL | same | licences screen |
| Noto Sans (map label glyphs) | OFL 1.1 | Maps, Timeline, Fit | licences screen; `OFL.txt` ships in the glyph folder |
| Photon (komoot) place lookup | ODbL (OSM data) | Timeline (shows the names), Control (runs the lookup) | licences screen |
| On-device models | per model (all Apache-2.0 today, `deploy/models/models.json`) | Control | licences screen; Settings → Models shows each |
| Apple Maps | Apple's terms | elected MapKit renders | iOS: MapKit's own logo + legal link (never covered). Desktop snapshot: the chip reads *"© Apple Maps · Legal"* and opens the page MapKit's own Legal link opens (`gspe21-ssl.ls.apple.com/html/attribution.html`, crediting Apple's data providers, OSM included) |

⚠⚠ The basemap chip is the **only** attribution a Tenshu map carries — both renderers hide
MapLibre's own button. The chip follows `LocalBasemapData`: over live tiles (`OsmDataContent`) it
reads *"Offline · © OpenStreetMap contributors"*; over the desktop's elected Apple Maps snapshot
(`AppleMapsDataContent`) *"© Apple Maps · Legal"*, linking MapKit's own acknowledgements page; only the schematic
sketch, which draws no third-party data, keeps *"Offline · MapLibre"*. A new live render that calls
`content()` directly ships OSM tiles uncredited — `BasemapAttributionTest` pins all three states.

## 4. Licence audit (2026-10-23)

Generated from all 30 Kotlin artefacts' JSON (10 Android release, 10 desktop, 10 iOS frameworks) plus the hand lists and the web walk. Apache-2.0 (300 components), MIT (13),
BSD-2/BSD-3, ISC and the Bouncy Castle licence (MIT text) are permissive and not listed.

### 4.1 Flagged — needs a human decision

| Component | Version | Licence | Ships in | Assessment |
|-----------|---------|---------|----------|------------|
| OpenStreetMap data (offline regions, bundled demo `tenshu_basemap.pmtiles`) | Protomaps v4 builds | **ODbL 1.0 (share-alike)** | Maps, Timeline, Fit (all platforms), Control (distributes regions) | The tile archives are a *derivative database* we distribute publicly, so they are under ODbL: credit (done), licence named (done), and §4.6 asks us to offer the derived database or the means to recreate it — the Planetiler/`pmtiles extract` pipeline (`maps-region-delivery.md` D2) is that, but it is not published. Users' own timeline data joined with OSM place names stays private, so no share-alike there. ⚖️ counsel |
| `org.bytedeco:javacpp` | 1.5.14 | GPL-2.0+ / GPLv2+CPE **or Apache-2.0** | maps-android, timeline-android, fit-android | Dual-licensed; we elect Apache-2.0. The notice lists all three, as the POM does. |
| `org.checkerframework:checker-compat-qual` | 2.5.3 | GPL-2.0 w/ CPE **or MIT** | control (Android) | Annotations only; dual-licensed, elect MIT. |
| `com.garmin:fit` | 21.218.0 | **Garmin FIT Protocol License** (proprietary, free) | fit-desktop | Not open source; permits redistribution in apps under its terms. ⚖️ read before a store build. |
| Google Play services (`base`, `basement`, `tasks`, `location`, `places-placereport`), Play Billing (`billing`, `billing-ktx` 9.1.0) | various | **Android SDK License** (proprietary) | control only | Standard for Play apps; not OSS, no text to ship — name + URL shown. Billing also pulls `datatransport` + `firebase-encoders*` (Apache-2.0, so permissive — but it is Google's telemetry transport; the privacy policy is where that belongs, not here). |
| `lightningcss` (+ linux-x64-gnu) | — | **MPL-2.0** | nothing shipped — Tailwind build plugin only | Not in any served file (the web build fails if a bundled module is unlisted). No action. |

No dependency is AGPL, SSPL, LGPL-only, unlicensed or of unknown licence. **No GPL code is linked
without a permissive alternative.**

⚠ **ML Kit is gone** (2026-10-24): QR decoding on Android is `com.google.zxing:core` 3.5.4
(Apache-2.0, permissive — listed, not flagged) in control and drive-android, and with it went
`odml:image` and every Play-services artefact from **drive-android**, which now ships no proprietary
component at all. Re-checked against the generated lists (control/drive-android debug, Drive release
APK): no `mlkit` entry, `zxing:core` present.

### 4.2 Hand-maintained (not visible to the generator)

| Component | Version | Licence | Ships in |
|-----------|---------|---------|----------|
| libsodium | 1.0.20 | ISC | every iOS app (static, in `TenshuSdk`) |
| SKIE runtime (generated Swift) | 0.10.15 | Apache-2.0 | every iOS app |
| MapLibre Native for iOS | 6.28.0 | BSD-2-Clause (+ third-party notices in its upstream licence file) | Maps, Timeline, Fit (iOS) |
| Noto Sans glyphs | — | OFL 1.1 | map apps, all platforms (`OFL.txt` bundled) |
| Kotlin/JS SDK contents | see `web/apex/support-pages/sdkBundle.ts` | Apache-2.0 / MIT / BSD-3 | web |

⚠ Keep these in step on a bump: `scripts/build-libsodium-apple.sh`, the SKIE pin in
`libs.versions.toml`, `ios-basemap/basemap.yml`'s MapLibre `from:`.

## 5. Adding a dependency

Nothing to do for a Maven/npm dependency: the next build lists it. The build **prints** any licence
outside the permissive allow-list (`strictMode = WARN`); decide it here in §4.1 before it ships, and
add its text to `config/oss-licences/licenses/` if its terms require the text with the binary. ⚠
Never flip `strictMode` to `FAIL` without first deciding every open item in §4.1. A new MIT/BSD/ISC
dependency whose artifact carries no LICENSE fails the build until its copyright line is in
`config/oss-licences/copyrights.json`; a NOTICE it ships is picked up on its own (§1.1).

## 6. Open items

- ⚖️ ODbL §4.6 for the distributed tile archives (§4.1 row 1).
- ⚖️ Garmin FIT SDK terms for a store build of Fit desktop.
- MapLibre Native's binary bundles third-party code whose notices are in its upstream licence
  file; the screen links it rather than embedding it.
- `web/drive` (served on the drive origin) has no licences page yet; `thirdParty.ts` takes an app
  directory, so it can reuse the generator.
