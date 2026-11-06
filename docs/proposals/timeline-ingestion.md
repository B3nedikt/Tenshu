# Timeline ingestion — capture and import into the Moment/Place spine

**Status:** v0.1 — **sketch, no code (2026-07-06).** How real data enters the Timeline
spine (`Moment`/`Place`, `:timeline-service` + `:place-service`). Companion to
[`timeline-services.md`](timeline-services.md) — that doc built the *storage* substrate and
the read-model projector; this doc is the *write* side: the two acquisition modes (live
background capture + bulk file import), the per-platform reality behind each, and the
threat-model escalation that continuous location implies. Executes the previously-thin P4/P6
of `timeline-services.md` §7 and adds the Google/Apple import story that doc did not size.

**Owner's steer (2026-07-06):** get to *ingestion of trips with a background service* soon;
give *Google Timeline import* at least some priority; find the Apple equivalent. This doc is
the design that precedes the code, per the repo's docs-first convention.

---

## 1. The one framing that drives everything

There are **two independent acquisition modes**, and the platforms are asymmetric across
them. Conflating them into one "import" abstraction is the trap.

| Mode | What it is | Google | Apple | Desktop/Web |
|---|---|---|---|---|
| **Bulk import** | Backfill existing history from a file | `Timeline.json` (on-device export) or legacy Takeout zip | **nothing** — no export exists | file the user brought over |
| **Live capture** | Ongoing background detection of visits/trips | (build our own) | `CLVisit` / significant-location-change | n/a (no live sensors) |

The asymmetry is load-bearing:

- **Google gives you the *history file* but no live API.** As of Google's 2024–25 privacy
  change, Timeline moved fully **on-device, E2E-encrypted**; Takeout / cloud / web export for
  Timeline is **gone**, and there is **no OAuth/REST connector**. The only path is the user
  manually exporting inside the Google Maps app. This is philosophically perfect for Tenshu:
  we ingest data the user has *pulled out of a walled garden*, and no server ever sees it.
- **Apple gives you the *live visit stream* but no history file.** Significant Locations is
  not exportable at all. But `CLVisit` (`startMonitoringVisits`) wakes the app in the
  background on each arrival/departure with a coordinate + arrival/departure timestamps — the
  native primitive for live visit capture.

So: **bulk import is a file-parser + a guided-handoff wizard (no connector); live capture is
a per-platform background engine.** Neither substitutes for the other.

### 1.1 This is where Fit and Timeline deliberately diverge

`fit-app.md` carries the hard constraint *"No background location — location only during an
explicitly started workout."* **Timeline is the app where background location is the entire
point.** Keep the capability in the Timeline modules; Fit never links it. Health *import*
(the FIT/TCX/GPX + Health Connect / HealthKit work, `fit-app.md` §9.4–9.6) is a separate,
already-built seam — Timeline's `Activity` moments hydrate from `:health-service`, they are
not re-captured here.

---

## 2. The seam (mirrors the health-import recipe exactly)

The Fit health-import track (P5.1→P5.3) already proved the shape; location maps 1:1 onto it.
Reuse the pattern deliberately rather than inventing a second one.

| Health (built) | Timeline ingestion (proposed) | Notes |
|---|---|---|
| `HealthImporter` interface, **DI-bound, not `expect`/`actual`** | `LocationCaptureSource` (live) | each app binds its platform source; avoids premature `actual` stubs |
| `ImportedHealth` DTO | `ImportedTimeline` DTO | plain lists of place/visit/segment records, epoch-ms + E7 coords |
| `HealthImportWriter` (idempotent → record ops) | `TimelineImportWriter` (→ `recordPlace` / `recordMoment`) | content-addressed keys make re-import idempotent |
| `ReferenceHealthImporter` (fixture *through* the contract) | reference source (replaces `TimelineSeeder`) | seeding == importing, one path |
| `ActivityFileParser` (GPX/TCX, pure `commonMain`, unit-tested) | `GoogleTimelineParser` (sniff single-JSON vs Takeout-zip) | the device-independent, fully testable slice |
| Android `HealthConnectImporter` (live) | Android live capture (FusedLocation / geofencing / Activity Recognition) | needs a device |
| iOS `HealthKitImporter` (native Swift, gated on shell) | iOS `CLVisit` capture (native Swift, gated on `timeline-ios` shell) | same XCFramework gate |

**Module home.** All of this lands in `:timeline-shared-data` (the Compose-free data module
that already holds `TimelineProjectionRepository` / `InProcessTimelineProxy`), alongside
`:place-service` + `:timeline-service` clients. `commonMain` holds the DTOs, the writer, the
parser, and the reference source; platform capture lives in `androidMain` / (native Swift for
iOS) behind the DI-bound `LocationCaptureSource`.

```kotlin
// commonMain — the shared contract
data class ImportedTimeline(
    val places: List<ImportedPlace>,     // placeId?, latE7, lngE7, label, externalRefs
    val visits: List<ImportedVisit>,     // placeRef, start, end (→ Visit moments)
    val segments: List<ImportedSegment>, // activity type + start/end + optional polyline (→ Activity/trip material)
)

interface LocationCaptureSource {                 // live; DI-bound per platform, NOT expect/actual
    fun isAvailable(): Boolean
    fun captureStream(): Flow<CapturedVisit>      // arrival/departure events, per platform
}

class TimelineImportWriter(private val client: TenshuClient, private val context: /*…*/) {
    suspend fun write(imported: ImportedTimeline)  // → recordPlace / recordMoment, idempotent
}
```

**Idempotency is the whole game for re-import.** `Place` is content-addressed
(`PlaceDataIdProvider`) and `Moment` by `momentId`; deriving a stable `momentId` from
`(placeRef, startEpoch)` means re-importing the same `Timeline.json`, or a live re-capture of
an already-imported visit, folds to the same row instead of duplicating. This is exactly why
the record ops are content-addressed (`timeline-services.md` §2).

---

## 3. Bulk import — Google Timeline (and why it's a wizard, not a connector)

### 3.1 There are two Google formats; sniff both

A robust importer detects and handles both vintages (the open-source Dawarich project ships a
"format detector" precisely because of this fork):

| Export | Shape | Contents | Obtainable today? |
|---|---|---|---|
| **On-device export** (2024+) | a **single JSON** — `Timeline.json` (Android) / `location-history.json` (iPhone); some EU accounts get **CSV** | `semanticSegments` + `rawSignals` + `userLocationProfile` | ✅ the only current path |
| **Legacy Takeout** (pre-2024, archived) | a **zip** from takeout.google.com | `Records.json` (raw pings) + `Semantic Location History/YYYY/YYYY_MONTH.json` | ❌ removed from Takeout — but users hold archives with *longer* history |

`GoogleTimelineParser` sniffs by structure (top-level `semanticSegments` → new; a zip
containing `Records.json` / `Semantic Location History/` → legacy; leading `[`/CSV header →
those variants) and normalises all into `ImportedTimeline`. We map **`semanticSegments`
place-visits → `ImportedVisit` + `ImportedPlace`**, and **activity segments → `ImportedSegment`**;
`rawSignals`/`Records.json` raw pings are the polyline material for trips but are *not*
stored as Moments (too dense — they'd ride the content plane if ever kept, like workout
routes). Pure `commonMain`, unit-testable against fixture files with **no device and no new
deps** (kotlinx.serialization is already in the graph; zip via the platform's stdlib
`java.util.zip` in a small `expect` reader, or require the user to unzip for v1).

### 3.2 There is no API — the acquisition is a guided handoff

Because Google exposes no programmatic path, the import UX is:

1. **A short instructional wizard** — "In Google Maps, tap your profile → *Your Timeline* →
   *Settings* → *Export Timeline data* → *Save* (iPhone: *Save to Files*)." Static, illustrated
   steps; we can't automate it. (Optionally deep-link to the Maps app; the export screen itself
   isn't deep-linkable.)
2. **The OS file picker** → hand us `Timeline.json` / the zip.
3. **`GoogleTimelineParser` → `TimelineImportWriter`.**

**Nice cross-surface consequence:** the file is produced on the *phone*, but Timeline runs on
desktop/web too. The natural bridge is **Tenshu Drive itself** — export on phone → save into
Drive → import into Timeline on any device. On-brand, and no third-party transfer. Plain file
transfer (AirDrop / cable) is the fallback. (This makes "import from a Drive file" a nice
optional source in the picker step.)

### 3.3 Apple has no bulk import

Significant Locations cannot be exported; there is no `Timeline.json` equivalent. On Apple the
*only* ingestion is live capture (§4.2). State this plainly in the UI so users don't hunt for a
non-existent Apple export.

---

## 4. Live capture — the background service

This is "ingestion of trips with a background service." Raw position fixes → **Visit**
detection (dwell at a place) and **trip** material (movement between visits) → `ImportedTimeline`
→ the writer. The clustering is the P6 detector from `timeline-services.md` §5.2 (shared
`commonMain` logic, reused at capture *and* at merge time by the `TripMerger`).

### 4.1 Android

- **Sensing:** `FusedLocationProviderClient` (batched, low-power) + **Activity Recognition**
  (`ActivityTransition` — still/walking/driving) to gate when to sample, + **geofencing**
  around known `Place`s for cheap arrival/departure. This trio is the standard low-battery
  visit-detection stack; raw high-rate GPS is avoided.
- **Execution:** reuse the existing background substrate. Continuous location needs a
  **foreground service** (Android's `location` FGS type) — the direct precedent is Drive's
  `SyncedFolderForegroundService` / `FolderUploadForegroundService` (`drive-android/.../backup`,
  `.../upload`) with their launchers; a `TimelineCaptureForegroundService` mirrors them.
  Periodic *flush* of buffered fixes → detection → writer runs as a `HeadlessSafe`
  `BackgroundOperation` scheduled via `ScheduleService` / `SyncWorker` (WorkManager), the same
  path `BackgroundSyncScheduler` uses. **Permission:** `ACCESS_BACKGROUND_LOCATION` +
  `ACTIVITY_RECOGNITION`, requested with a first-class rationale (§5). Fully de-Googled:
  FusedLocation degrades to `LocationManager` where Play Services is absent, matching Fit's
  no-Play-Services stance.

### 4.2 iOS — the Apple equivalent

- **`CLVisit` via `startMonitoringVisits`** is *exactly* this: the OS wakes the app on arrival
  and departure with a coordinate + arrival/departure dates — a `CLVisit` maps almost directly
  to an `ImportedVisit` (no clustering needed for visits; Apple already did it).
  **`startMonitoringSignificantLocationChanges`** gives the between-visit movement for trips.
  Both are low-power and survive app termination (the OS relaunches into the background).
- **Where it runs:** native Swift, feeding the shared `LocationCaptureSource` /
  `TimelineImportWriter` across the SDK boundary — the same shape as the planned Swift
  `HealthKitImporter` (`fit-app.md` §9.5). **Gated on** a `timeline-ios` shell + exporting
  `:timeline-shared-data` into the iOS XCFramework — neither exists yet (same gate as Fit).
- **Permission:** `NSLocationAlwaysAndWhenInUseUsageDescription` + the Always authorization
  prompt.

### 4.3 Desktop / Web

No live location sensing worth building. Ingestion on these surfaces is **bulk import only**
(§3) — and the Drive-file bridge (§3.2) means a phone can still feed a desktop Timeline.

### 4.4 The capture indicator — calm but present (as-built 2026-07-12)

Two decisions settled once `TimelineCaptureForegroundService` was live on-device:

**The notification is a feature, not a nuisance.** A `location`-typed FGS *must* carry a
visible, ongoing notification — Android won't let a location service run silently, and for
this type the notification can't be deferred or collapsed away while the service runs. That
constraint aligns with what we want anyway: the persistent notification **is** the required
capture indicator (threat-model A35). It's the honest opposite of Google's Location History,
which logs silently inside Play Services with no per-app indicator. So we keep it visible and
make it *calm* rather than hiding it:

- Channel stays `IMPORTANCE_LOW` (no sound, no heads-up, no vibration → lands in the "Silent"
  shade section), `setOngoing` + `setOnlyAlertOnce`.
- Copy is **steady-state**, not alarmist: title **"Timeline is on"**, text **"Recording the
  places you visit · Manage in Settings"**. The earlier "Capturing your timeline" over-stated
  a service that is mostly idle waiting on a visit transition, and the imperative "Turn this
  off" read as a warning.
- Own-app glyph (`ic_notification_timeline`, the timeline pictogram at 24dp status-bar scale),
  not the generic framework location pin — the notification should read as "your app, running
  quietly," not a system alert.

Note: the OS's own location-access dot in the *status bar* is separate and unavoidable for any
`location` FGS; only the shade notification is ours to shape.

**Activity Transition API is a deferred, opt-in, GMS-gated enhancement — not the baseline.**
§4.1 lists `ActivityTransition` + geofencing as the "standard low-battery stack," but as-built
capture runs a plain continuous-location FGS over `LocationCaptureEngine` (the ATA/geofence
trio is not wired). Moving to it is worth doing *later*, but its honest quality delta is
**battery + Doze survival**, not materially better visit data — our own `VisitDetector` over
fused location already gets the enter/exit boundaries mostly right; activity transitions just
sharpen them and let the OS batch wakeups at far lower power. Crucially, ATA / Activity
Recognition / geofencing all live in **Google Play Services**, which is absent on exactly the
de-Googled devices (GrapheneOS, /e/OS) this app most courts. So it can't be the default: it
belongs as an **opt-in "battery-friendly mode" offered only where Play Services is present**,
behind an upfront note about the GMS dependency. A geofence/broadcast-driven variant could
also run *without* an FGS (no notification) — but that would drop the A35 indicator, so it's a
deliberate trade, not a free win. The AOSP continuous-FGS path stays the privacy-first default.

---

## 5. Geocoding (P4) rides along

Captured fixes and imported raw pings are coordinates; turning them into labelled `Place`s is
the geocoding seam already designed in `timeline-services.md` §3.3–3.4 (`jordond/compass`
device tier → self-hosted Photon/Nominatim → commercial, cheapest-egress-first). Live capture
is the first real customer: on arrival at an unknown coordinate, reverse-geocode to a label +
optional GERS `externalRef`, honouring the privacy chain (egress **direct to
provider/self-hosted, never via the relay**). Google/Apple imports arrive *pre-labelled* (the
semantic export already names the place) — store that label, but **never** persist Google/Apple
*content* beyond a foreign-key id (ToS + §3.1 of the services doc).

---

## 6. Privacy & threat model (House Rule #2)

Continuous background location is the **single biggest sensitivity escalation** the platform
has taken on — it reveals home, work, routines, and presence. `timeline-services.md` §3.4
already flagged *location* as a new sensitive asset; this doc is where the **amendment lands
with the code**:

- **New sensitive-asset class** in `docs/sync/threat-model.md` (sibling to health A34):
  location visits/traces = special-category-adjacent; sealed plane only, **no location field
  ever relay-visible** (HR#1 intact — the relay sees ciphertext).
- **Capture is opt-in and revocable**, off by default. A visible, persistent indicator while
  the capture FGS runs (Android already forces the FGS notification; mirror the intent on iOS).
- **Geocoding egress rules** (§5): direct-to-provider/self-hosted, never via the relay; the
  relay must not learn locations. Commercial geocoding only on explicit user action, persisting
  at most a foreign-key id.
- **Import ingests third-party-origin data**; treat imported files as untrusted input (robust
  parsing, never crash on malformed — same posture as `ActivityFileParser`).

---

## 7. Phasing (each independently shippable)

Mirrors the health track's proven order — *foundation → device-independent import → live
capture → iOS*.

1. **I1 — ingestion seam foundation (no device).** `ImportedTimeline` DTO +
   `TimelineImportWriter` (idempotent → `recordPlace`/`recordMoment`) + a reference source that
   replaces `TimelineSeeder` (seed == import). In-process testable over `InProcessTimelineProxy`,
   exactly like `HealthImportWriter`. *Analog of health P5.1.*
2. **I2 — Google Timeline import (no device).** `GoogleTimelineParser` (sniff single-JSON /
   legacy-zip / CSV) → `ImportedTimeline` → writer. Pure `commonMain`, unit-tested against
   fixture exports. Immediate bulk-history payoff, fully verifiable without hardware — **the
   highest-leverage first real data.** *Analog of health P5.3.* Ships with the guided-handoff
   wizard + file picker (desktop first; Drive-file source optional).
   **As-built (I2 parser core — the device-free slice, analog of health P5.3):**
   `GoogleTimelineParser` + `TimelineImportFile` + `FileTimelineImporter` landed in
   `:timeline-shared-data` `commonMain` (kotlinx-serialization added to the module). It sniffs by
   top-level structure — new on-device export (`semanticSegments`, coords as a `"52.52°, 13.40°"`
   degree string) vs legacy Semantic Location History (`timelineObjects`, E7-int coords + place
   `name`). Mapping (§2): a **visit → `ImportedPlace` + `Visit` moment** (place id = Google's
   `placeId` foreign key, else a coordinate key — the only Google *content* persisted, §5); an
   **activity segment → a subjectless `Activity` moment** titled by its human motion type
   (`humanActivity`). Idempotent via content-addressed ids (`gv:place:startMs` / `ga:startMs`).
   Untrusted input → empty, never throws (§6). Raw signals / `Records.json` pings dropped (§8).
   `GoogleTimelineParserTest` covers both vintages, coordinate fallback, re-import stability,
   cross-file dedupe, and malformed input.
   **As-built (I2b — desktop import UI, analog of health P5.3b):** `timeline-desktop`'s `File →
   "Import Google Timeline…"` menu opens a short **guided-handoff dialog** (§3.2 — how to export
   from the Maps app; Google has no API, so the file *is* the handoff) → a native `FileDialog`
   (`.json`) → `FileTimelineImporter` → the shared writer, with a result dialog reporting the count
   (or a hint when the file held nothing recognisable, §6). `InProcessTimelineProxy` now exposes
   `client`/`tContext` for the app-module importer (mirrors `InProcessFitProxy`); the reactive
   projector Flows re-emit as rows land, no manual refresh. `TimelinePipelineIntegrationTest` gains
   an end-to-end case (legacy export → repository shows the named café + idempotent re-import).
   **Still deferred:** Android/iOS/web import surfaces (each builds its own platform picker); a
   Drive-file import source (§3.2); CSV and in-app zip (§8).
3. **I3 — Android live capture (needs device).** `TimelineCaptureForegroundService` +
   FusedLocation / Activity Recognition / geofencing → Visit detection (P6 clustering) → writer,
   scheduled via the `HeadlessSafe` / `ScheduleService` / `SyncWorker` substrate. Background
   location permission + rationale. Threat-model amendment lands here. *Analog of health P5.2 +
   the §5.2 detector.*
   **As-built (I3a — the visit-detection engine, the device-free core landed first):** `VisitDetector`
   (`:timeline-shared-data` `commonMain` `…/capture/`) is the P6 stay-point clustering as pure math —
   the classic Li-et-al. algorithm: the longest consecutive run of [CapturedFix]es that stays within
   `radiusM` of its anchor and spans ≥ `minDwellMs` is a dwell, centroid = the run's mean. It maps
   dwells onto the neutral `ImportedTimeline` (content-addressed `Place` snapped to a grid so repeat
   visits fold; `Visit` moment keyed off `(placeId, arrival)` — idempotent like the importers). No
   Moment for movement (trip material = I5, §8 raw-ping decision); labels blank until geocoded (§5).
   `VisitDetectorTest` covers dwell/movement/sub-threshold/two-dwells/fold/idempotency, all hardware-free.
   Shared by live capture (I3b) **and** the merge-time `TripMerger` (I5).
   **As-built (I3b-1 — the device-free capture seam + write loop):** `LocationCaptureSource` (DI-bound
   interface, mirrors Fit's `HealthImporter`, **not** `expect`/`actual`) publishes a cold `Flow<CapturedVisit>`
   — **visits are the common currency** so Android (cluster fixes internally via `VisitDetector`) and iOS
   (`CLVisit` direct, §4.2) both fit. `LocationCaptureEngine` (`commonMain`) collects that flow, maps each
   visit via `VisitDetector.capturedVisitsToTimeline`, and persists through the shared `TimelineImportWriter`
   — inert until `run()`, no-op when the source is unavailable (off-by-default, §6). `TimelinePipelineIntegrationTest`
   gains a live-capture case (a fake source's two dwells fold to one place with a two-visit history in the repository).
   ⚠ **Architecture correction:** the Timeline app runs `InProcessTimelineProxy` **standalone** — it binds
   **no `ScheduleService`/`PlatformScheduler`/`ScheduleDrainLoop`** (that substrate lives only in Control's
   proxy). So I3b uses a **live foreground service** with an in-service capture loop, **not** WorkManager /
   a `HeadlessSafe` scheduled op.
   **As-built (I3b-2a — the streaming clustering operator + threat-model, device-free):** `detectVisits`
   (`Flow<CapturedFix>.detectVisits(config): Flow<CapturedVisit>`) is the online counterpart of
   `VisitDetector.detect` — it emits a visit the moment a fix lands outside the current dwell's radius (the
   natural cadence for continuous capture; a visit is complete on departure), sharing the batch path's geo
   helpers (`capturedDistanceMeters`/`toCentroidVisit`) so the two clusterings agree bit-for-bit. The Android
   source will compose it as `fixes().detectVisits()`, keeping the platform layer a thin fix emitter and the
   clustering pure/testable (`FixClusteringTest`). The **§6 threat-model amendment landed here** — new asset
   **A35 (precise location — visits & traces)**: sealed plane only / no coordinate relay-visible, capture
   opt-in + off-by-default + revocable, persistent capture indicator, on-device clustering with raw pings
   never stored, geocoding egress direct-to-provider never via relay.
   **As-built (I3b-2b — the Android capture runtime; compiles, on-device behaviour pending):**
   `AndroidLocationCaptureSource` (`:timeline-shared-data` androidMain) = platform `LocationManager`
   (**not** Play-Services fused — no GMS dep, de-Googled per §4.1) → cold `callbackFlow<CapturedFix>`
   (explicit `LocationListener` for API-24 safety) → `detectVisits`. `TimelineCaptureForegroundService`
   (`:timeline-android`, `foregroundServiceType="location"`, mirrors Drive's `FolderUploadForegroundService`)
   runs `LocationCaptureEngine.run()` over the injected source + `InProcessTimelineProxy`; its persistent
   notification is the **A35 capture indicator**. `TimelineCaptureServiceLauncher` + `timelineAndroidModule()`
   (binds source/control/launcher, wired in `TimelineApplication` like Fit). **Off-by-default opt-in:** a
   DI-optional `TimelineCaptureControl` (`:timeline-shared-logic`) persisted by `AndroidTimelineCaptureControl`
   (`SharedPreferences`) drives a **Settings toggle** (rendered only where a control is bound — Android;
   desktop shows nothing); `MainActivity` observes it, grants `ACCESS_FINE_LOCATION` (+ `POST_NOTIFICATIONS`)
   then starts the service, stops it when toggled off, and resets the toggle if permission is refused —
   **nothing auto-starts capture.** `:timeline-android:assembleDebug` + desktop compile green.
   **Deferred (device / refinement):** the on-device pass (FGS + `LocationManager` + permission flow +
   the capture-indicator UX); `ACCESS_BACKGROUND_LOCATION`'s separate in-context consent step (v1 captures
   via the FGS while running) + boot-resume; fused-location as a battery optimisation behind the same seam.
4. **I4 — iOS live capture.** `CLVisit` + significant-location-change in native Swift → shared
   writer, gated on a `timeline-ios` shell + XCFramework export.
   **As-built (I4-1 — the shared event-write primitive, device-free):** `CapturedVisitWriter`
   (`:timeline-shared-data` `commonMain`) is the single write path all capture sources converge on —
   map visit(s) → `ImportedTimeline` → `TimelineImportWriter`, idempotent. The Android pull engine now
   *delegates* to it, and it is the **iOS-facing seam**: the design decision (§4.2) is that iOS uses the
   **event model, not a `Flow` + collector** — `CLVisit` wakes the app briefly per arrival/departure, so
   the native delegate hands each visit *directly* to `CapturedVisitWriter.write(visit)` and awaits it (a
   long-lived collector might not drain before iOS re-suspends the app). `TimelinePipelineIntegrationTest`
   covers the direct path (single + batch write, idempotent fold). **Deferred to I4-2 (needs the iOS
   toolchain + a device — iosArm64 currently blocked locally on a `:core-common` libsodium cinterop):**
   exporting the capture + proxy path into the `:tenshu-sdk-ios` XCFramework (the `timeline-ios` app is
   mock-only today, so a real `InProcessTimelineProxy`-on-iOS bridge is a prerequisite), the native Swift
   `CLVisit`/significant-location-change delegate calling `CapturedVisitWriter`, the `Info.plist`
   `NSLocationAlwaysAndWhenInUse…` usage strings + Always authorization, and the same off-by-default opt-in
   (a `TimelineCaptureControl` iOS actual + the Settings toggle already renders it).
   **I4-2 SHIPPED device-free 2026-07-24, folded into movement capture M4** (the two shared the gate
   and the delegate; one slice covers dwells *and* moves). As-built lives in
   [`timeline-movement-capture.md`](timeline-movement-capture.md) §7 item 4: `IosLocationCapture`
   (Kotlin `iosMain`, not literal Swift — the `IosRecordingEngine` precedent) runs `CLVisit`
   monitoring + significant-change fixes over the route-1 in-process runtime, exactly the event
   model §4.2 designed — each visit handed directly to `CapturedVisitWriter.write` and awaited.
   The libsodium-cinterop blocker named above dissolved with the route-1 migration. On-device pass
   remains (the I3b-2b twin).
5. **I5 — TripMerger + refinement** (`timeline-services.md` §5.2). The per-device detector,
   reused as a `SemanticMerger` so trips get more complete as device data converges.
   **As-built (I5a — the trip detector, the device-free core landed first):** `TripDetector`
   (`:timeline-service-common` `commonMain` — moved there 2026-07-13, see §11)
   groups moments into candidate trips by **distance-from-home + time-gap** — a run of *away* moments
   (beyond `awayRadiusM`, default ~100 km) with no time gap over `maxGapMs` (36 h), long/large enough
   to be a journey — over **neutral `TripMomentInput` primitives** (id + time + E7 coord), so it has no
   schema/service/IO dependency and is reused at **both detect-time and merge-time** (`membersWithinSpan`
   returns the away moments inside an incoming trip's span — how a receiving device unions its own
   moments). ⚠ **Home is a parameter, deliberately** — this **resolves the §10 `TripMerger`-purity
   open question**: the distance-from-home heuristic's one external input is passed in by the caller
   (from local state), so the detector stays a pure function and can run inside a `SemanticMerger.merge`
   (no service IO). `TripDetectorTest` covers cluster / near-home / gap-split / too-short / span-union /
   order-independence, all hardware-free.
   **As-built (I5b — the mergeable `Trip` + `TripMerger`):** `Trip` gained a **membership collection**
   (`list(of = TripMember.Schema)`) and flipped to `Syncable(Eager, ConflictStrategy.Merge)`. ⚠ Membership
   members are a **`TripMember` composite** (a `momentId` ref, content-addressed by a `TripMemberDataIdProvider`),
   **not** a value-list — the DB serialiser recurses into each list element as an entity needing a schema +
   `DataIdProvider`, so `list(of = TString.Schema)` fails ("Schema not found for tenshu/core/string"); list
   elements must be composites, exactly as `Folder.files` are `File`s. `TripMerger : SemanticMerger`
   (`:timeline-service-proxy`, `:sync-service-common` dep added) **unions membership by `momentId`** and keeps
   the newer state's scalars, mirroring `FolderMerger`; `TripMergeTest` (`:sync-service-proxy` commonTest, the
   `FolderMergeTest` harness with the timeline registry) proves the union survives the real
   encrypt→`SyncBlock`→`processSyncBlock`→merge path. **`Trip.create(membership = …)` is back-compatible**
   (defaults empty; existing callers untouched). ⚠ **Production registration deferred, deliberately:** the
   Timeline app runs `InProcessTimelineProxy` **standalone — no `SyncProcessor`/`SemanticMergerRegistry`**, so
   there is no production merge consumer for timeline types yet (registering `TripMerger` in Control's
   `PlatformProxyModule` would be wrong — Control doesn't host/sync timeline). The merger is landed + proven,
   ready to register when a **syncing timeline proxy** lands (the "synced Timeline is a later, larger concern",
   `timeline-services.md` §7).
   **As-built (I5c — the device-local trip-suggestion engine):** `TripSuggester`
   (`:timeline-service-common` `commonMain` — moved there 2026-07-13, see §11) turns a device's moments into "read as a trip?" suggestions — pure, does **not** sync (§5.2).
   It does two things over the I5a `TripDetector`: (1) **derives home** — the distance-from-home heuristic
   needs a home coordinate and **none is stored yet (§10)**, so it derives one locally as *the most-frequented
   grid cell* (`deriveHome`), self-contained until a stored home setting can override it; (2) **shapes**
   each candidate into a `TripSuggestion` with a **content-addressed `suggestedTripId`** (`suggested-trip:<startMs>`,
   so accepting twice folds to one `Trip`) and a title from the dominant member place label (date formatting
   left to the caller — the module stays datetime-dep-free). `TripSuggesterTest` covers away-journey→suggestion,
   home-only→none, title fallback, and home derivation.
   **As-built (I5d — the app-side wiring, end-to-end):** the real `TimelineProjectionRepository` now
   *implements* the `tripSuggestion()` / `acceptTripSuggestion()` / `dismissTripSuggestion()` seam (were
   stubbed): it joins its Visit moments with their resolved place coords (`PlaceInput` gained `latE7`/`lngE7`),
   builds `TripSuggestionInput`s, and runs `TripSuggester`; the top *unresolved* candidate drives the existing
   **Interlude card** (VM + UI unchanged — the presentation `TripSuggestion` model already existed, and its
   `cover`/`confidence` are unused by the card, so a placeholder cover suffices). **Accept records a real
   `Trip`** via `recordTrip(Trip.create(…, membership = suggestion.memberMomentIds))` (formatting
   `dateRange`/`year` app-side with kotlinx-datetime) — the Trip syncs and converges via `TripMerger`;
   **dismiss** just hides it. Resolved suggestions are tracked in an in-memory set (per-device, never synced —
   §5.2). `TimelinePipelineIntegrationTest` proves it end-to-end (home + away moments → the Lisbon journey
   surfaces as a suggestion → accept records the `Trip` → suggestion clears). **The trip line (I5a–d) is now
   device-free-complete.** Later slices: photo covers (captured-photo slice); a stored home setting to override
   the derived home; and — when a **syncing timeline proxy** lands — registering `TripMerger` in production DI.
6. **I6 — cross-source reconciliation / dedupe** (§7.1). Fold near-duplicate captures of the
   same real-world visit/trip — iOS + Android + smartwatch-imported + Google-import all seeing
   the same lunch — into one canonical Moment with unioned provenance.
   **As-built (I6a — the reconciler, the device-free core landed first):** `MomentReconciler`
   (`:timeline-service-common` `commonMain`, beside `TripDetector`/`TripSuggester` — moved there
   2026-07-13, see §11) clusters
   near-duplicate `ReconcilableMoment`s and elects a canonical. **Fuzzy identity (§7.1):** `Visit`s
   fold when they share a **place cell** (coords snapped to a ~55 m grid — cross-source captures
   rarely share a `placeId` but share *location*) **and** a **time bucket** (default 2 h); `Activity`s
   fold by **workout subject**; subjectless `Event`/`Quiet` never fold. **Convergence without key
   churn:** the cluster/canonical key is derived **deterministically off the invariant** (`placeCell +
   timeBucket`), *not* off the present-sources set, so two devices reconciling independently produce
   the **same** canonical key → sync collides them → a future `MomentMerger` unions `sources`. The
   canonical is elected by **source priority** (live capture > Google > file import), ties by
   earliest-then-id; the others become `supersededMomentIds` (superseded, **not** tombstoned — §7.1
   reversibility). `MomentReconcilerTest` covers fold / separate-time-or-place / activity-by-subject /
   subjectless / determinism / election. ⚠ Documented limit (a §7.1 open sub-question): time bucketing
   has **boundary cases** (a visit straddling a bucket edge won't fold) — the price of a stable key.
   **As-built (I6b — the mergeable `Moment` + `MomentMerger`):** `Moment` gained two fields — a
   **`sources` collection** (`list(of = MomentSource.Schema)` — a `MomentSource` composite content-addressed
   by its tag, same recipe as I5b's `TripMember`, since value-lists aren't serialisable) and a scalar
   **`supersededBy`** (the canonical's key, `""` for a live/canonical moment; the projector hides moments
   whose `supersededBy` is set — superseded, not tombstoned) — both added in **one lockstep wire change**,
   and `Moment` flipped to `Syncable(Eager, ConflictStrategy.Merge)`. `Moment.create(sources = …,
   supersededBy = …)` is back-compatible (defaults; existing callers untouched). `MomentMerger : SemanticMerger`
   (`:timeline-service-proxy`) **unions `sources` by tag** and keeps the newer state's scalars (header +
   `supersededBy` — last-writer-wins on the strictly-newer HLC, which keeps `supersededBy` *reversible*:
   the deterministic reconciliation pass re-stamps it identically on both devices, and clearing it when a
   source is removed re-exposes the moment, per §7.1), mirroring `TripMerger`; `MomentMergeTest` proves
   the union survives the real
   encrypt→`SyncBlock`→`processSyncBlock`→merge path (`{capture} ∪ {google}`). Registered as a composite +
   trait in `timelineServiceCommonModule`. ⚠ **Production registration deferred (same as `TripMerger`):**
   the timeline app is standalone-no-sync, so `MomentMerger` registers when a syncing timeline proxy lands.
   Health-side `Workout` dedupe stays a `:health-service` concern; the Moment layer keys off the resolved
   subject (§7.1).
   **As-built (I6c — read-model reconciliation, the app-side wiring):** the real `TimelineProjectionRepository`
   now reconciles at read time. A `reconcile(moments, places)` step (a) **drops moments whose `supersededBy`
   is set** (the projector-hides-superseded behaviour §7.1 calls for, honouring a persisted/synced reconciliation
   pass), and (b) **collapses near-duplicates** the sync key layer can't — running `MomentReconciler` over the
   moments (resolving `Visit` coords from the place join) and keeping only each cluster's canonical id — so
   `homeDays()`/`places()` show **one moment per real-world moment**. `MomentInput` gained `supersededBy`.
   `TimelinePipelineIntegrationTest` proves it (two near-duplicate café captures + a superseded moment → the
   feed shows one). ⚠ **Why read-time, not a write pass:** the timeline app is standalone-no-sync, so there is
   no place for a *persisted* canonical/supersession to converge (that needs a syncing timeline proxy + the
   `MomentMerger` registration, deferred). Read-time reconciliation is the honest standalone realisation of
   "read canonicals, hide superseded"; the I6b schema (`sources`/`supersededBy`) + `MomentMerger` are ready for
   when the synced write-pass lands. Source-tagging moments at ingestion (so the canonical carries real
   provenance rather than an empty election) rides that same future slice. **The device-free ingestion track
   (I1–I2, I3, I4-1, I5a–d, I6a–c) is complete;** the remaining work is genuinely device/toolchain-gated: I3's
   Android-capture on-device pass, and I4-2 iOS-native `CLVisit`.

I1–I2 make Timeline ingest real history with no hardware and land the writer everything else
reuses. I3 is the "trips with a background service" the owner wants; I4 brings it to Apple; I5
and I6 are convergence polish (trips get more complete; duplicates collapse).

> **⚠ Naming note — I5 is *not* movement capture.** The built I5 (`TripDetector` /
> `TripSuggester` / `TripMerger`) is a **curation** pass that groups existing `Visit` moments into
> **journeys** (away-from-home chapters). It records no movement, and the `VisitDetector` KDoc's
> "trip material is I5, not stored here" pointer is misleading. Capturing the actual **path
> between dwells** (the walk/run/ride/drive — the thing a user sees nothing of today after a
> walk) is a separate, unbuilt acquisition mode designed in
> [`timeline-movement-capture.md`](timeline-movement-capture.md) (phases M1–M5). Movement is the
> negative space of dwell detection: the fixes `detectVisits` currently discards, shaped into a
> `MOVEMENT` moment + a decimated trace.

### 7.1 I6 — cross-source reconciliation (suggested design)

**The problem.** Content-addressed `momentId` from `(placeRef, start)` dedupes *exact* repeats
(re-importing the same file, re-capturing an already-stored visit). It does **not** catch
*cross-source near-duplicates*: the same real-world visit captured by iOS `CLVisit`, Android
FusedLocation, a smartwatch's imported workout, and a Google Timeline backfill all land with
**different** `momentId`s (start times differ by seconds/minutes, coordinates by metres), so
they never collide at the sync key layer and the `SemanticMerger` — which merges two states of
*one* key — never fires. This is a distinct problem from sync merge and needs its own pass.

**The shape of the design** (the same per-device-detect + merge-time-converge pattern as
TripSuggestion/§5.2, reused a third time):

1. **A fuzzy "visit identity."** Two `Visit` moments are the same visit iff their time intervals
   overlap within a tolerance **and** they resolve to the same `Place` (or coordinates within a
   radius). `Activity` moments dedupe by *subject* — same `Workout` (or same activity type +
   overlapping window + proximate endpoints); a run logged by watch **and** phone is one activity.
   (Note this crosses into `:health-service` — the underlying `Workout` dedupe, "same run from
   two devices," may be its own health-side concern; the Moment layer dedupes by the resolved
   subject.)
2. **A per-device reconciliation pass** clusters near-duplicates and elects a **canonical** by a
   **source-priority order** (e.g. live first-party capture > Google semantic import > bare-GPX
   raw). The canonical carries a **`sources` provenance set** (iOS+Android+Google…) and the raw
   source moments are marked **superseded** (a supersession ref → canonical), **not tombstoned** —
   so disabling/removing one source later can drop *only* its contribution without losing the
   visit. The projector reads canonicals and hides superseded moments (zero UI change beyond the
   projector).
3. **Convergence without key churn — the hard part.** For two devices that reconcile
   independently to agree, the canonical must key **deterministically** off the cluster's
   *invariant* — a rounded time bucket + `placeRef` — **not** off which sources happen to be
   present. If the key shifted when a new source arrived (device A has 2 of 3, device B all 3),
   the canonical would churn and re-duplicate. Keying off the invariant means every device
   produces the *same* canonical key; ordinary sync then collides them and a
   `MomentMerger : SemanticMerger` on that key **unions the `sources` sets** — reconciliation
   converges through the machinery we already have, exactly as TripMerger does for membership.

**Open sub-questions to size at I6** (not blockers for I1–I5):

- Tolerance parameters (time window, spatial radius) — shares I3's visit-detection tuning corpus.
- Supersession vs tombstone reversibility — confirm the projector can cleanly re-expose a source's
  moments if the user removes a *different* source.
- Canonical-key stability under partial/late-arriving data — the invariant-keying above is the
  proposed mitigation; validate it holds when sources arrive out of order across a sync.
- Whether health-side `Workout` dedupe (watch + phone recording one run) belongs here or in
  `:health-service`; leaning `:health-service` with the Moment layer keying off the resolved subject.

---

## 8. Open questions

**Decided (2026-07-06):**

- **Zip handling** — v1 accepts the **single-JSON new format + already-unzipped legacy JSONs**;
  in-app unzip (a small `expect` reader) is added only if demand shows.
- **Raw-ping storage** — **dropped for v1.** `rawSignals`/`Records.json` dense polylines are not
  stored; visits + semantic segments are what the Chapters narrative shows. (Content-plane
  storage remains available later if a map view ever needs the trace.)
- **Cross-source dedupe** — promoted to a real phase, **I6** (§7.1); it's a reconciliation pass,
  not a writer tweak.

**Still open (not blockers):**

- **Visit-detection tuning** (dwell radius / min-duration) — shared with the P6 detector; needs
  real captured data to tune, so I2's imported history doubles as the tuning corpus. (Fine for now.)
- **`TripMerger` purity** — unchanged from `timeline-services.md` §10 (home-location for the
  distance heuristic must be available from local state at merge time).
- **I6 sub-questions** — tolerance parameters, supersession-vs-tombstone reversibility, canonical-key
  stability, and the health-side `Workout` dedupe boundary (§7.1).

## 9. Version

Timeline-ingestion v0.1 · sketch 2026-07-06 · §4.4 capture-indicator + ATA-status amendment
2026-07-12. Companion to
[`timeline-services.md`](timeline-services.md) (storage substrate + projector) and
[`fit-app.md`](fit-app.md) (health-import recipe this mirrors). No code. Research provenance:
Google Maps Timeline 2024–25 on-device migration + export formats (single `Timeline.json` /
`location-history.json` vs legacy Takeout `Records.json` + Semantic Location History; no API);
Apple `CLVisit` / significant-location-change (live capture; no export). Substrate: Tenshu
`HeadlessSafe` / `ScheduleService` / `SyncWorker` + Drive `dataSync` foreground services.

## 11. As-built — the pure algorithms live in `:timeline-service-common` (2026-07-13)

`MomentReconciler`, `TripSuggester` and `TripDetector` were originally written into
`:timeline-service-proxy`, next to the `SemanticMerger`s that consume them. But they are **pure
functions over plain data** — the only thing they import is `kotlin.math`. Nothing about them is
proxy-side.

That became a real layering problem when Timeline moved onto the Control proxy (`timeline-app.md`
§10): the *client-side* projector (`TimelineProjectionRepository`) calls the reconciler and the
suggester, which forced the **app** to depend on `:timeline-service-proxy` — a proxy module, in a
process that runs no proxy.

So the three files (and their tests) moved to `:timeline-service-common`, package
`org.tenshu.timeline.common`. Both consumers now reach them from the layer they belong to:
`MomentMerger`/`TripMerger` (proxy) and the client projector (app). `:timeline-shared-data` no longer
depends on `:timeline-service-proxy` at all in its main source set — only in tests, which stand the
service up in-process.

Nothing else changed: no behaviour, no schema, no wire, no op. The split is exactly
**pure algorithm → `-common`, `ExecutionContext`-bound merger → `-proxy`.**
