# Timeline movement capture — recording the *trip*, not just the *dwell*

**Status:** v0.5 — **M1–M3, M6 fidelity, M7 Google activity import,
M5-lite the movement card and M8 T0 kinematic transit all shipped (2026-07-20).** Movement is
now recorded, imported, re-classifiable, **visible**, and — for rail — distinguished from driving
on-device. §§12–14 design the fidelity pass and the transit classifier; remaining phasing:
**M9 COMPLETE (M9-1 decoder → M9-2 matcher → M9-3 refinement → M9-4 proxy op → M9-5 sweep → M9-6
launch trigger) + M10 flight shipped** → **M5 full route-on-map SHIPPED device-free (2026-07-24;
maintainer click-through pending)** → **M4 iOS capture SHIPPED device-free (2026-07-24, §7 item 4
as-built — bundles ingestion I4-2; on-device pass pending). The M-track phase map is CODE-COMPLETE;
what remains is hardware-gated.** ⚠ §13.2a: the station-POI assumption behind T2 is **verified false** against real
tiles; T2 is replaced by T2′ (stop-on-rail), which needs no new data source. Outstanding
non-code: the M3b on-device movement pass, a maintainer click-through of the movement card, and a
**T0 calibration pass over a real Takeout import** (§9's tuning corpus — M7's `sourceActivityType`
is the labelled data that closes it).
⚠⚠ **§32 (2026-08-12) is the current live-defect list** — a real 70 km rail commute diagnosed against
the pulled log *and* the stored moments/traces/places. Five fixes landed with it (**S9**
`ModeCoalescer` §32.2, the sweep's capture-driven schedule §32.3, the **v6 confidence fix** §32.5, the
**v8 terminal-fix fix** §32.6 and the **v10 wrong-leg fix** §32.7, which together move the classifier
to **8** and the underground promotion to **10**).
⚠⚠ **Read §32.8 before §32.4.** §32.8 measured the remaining list and *retracted two of its entries*:
mid-ride dwells do **not** split the journey (the sweep schedule fixed it — the day now renders as six
rows with none), the still-here veto was never implicated, and there was no spurious station arrival
to delete. What is genuinely left is **place naming**, `TransitKinematics` at ~1 fix/min, and the train +
metro folding into one row.
Companion to
[`timeline-ingestion.md`](timeline-ingestion.md) (the capture/import track) and
[`timeline-services.md`](timeline-services.md) (the Moment/Place spine). This doc designs the
one acquisition mode the ingestion track deliberately deferred: **movement segments** — the
walk, run, ride, or drive *between* dwells. It is the missing half of live capture.

**Trigger (2026-07-19):** on-device, the user turned Timeline on and walked for ~30 minutes.
The feed stayed empty. That is **working as coded**: the capture pipeline only records *stays*
(a ≥5-min dwell within a 100 m radius — `VisitDetector`/`FixClustering`), and movement is
explicitly discarded (`FixClustering.kt` / `VisitDetector.kt:21`: *"Movement between stays
yields no visit — trip material is I5, not stored as a Moment here"*). A pure walk contains no
dwell, so nothing is ever written.

---

## 0. Terminology correction — this is **not** the existing "I5"

The `VisitDetector` KDoc points at "I5" for trip material, and `timeline-ingestion.md` §7 I5 is
labelled *"TripMerger + refinement."* That naming is misleading and has already caused
confusion. **The built I5 (`TripDetector` / `TripSuggester` / `TripMerger`,
`:timeline-service-common`) is a curation pass, not a capture pass.** It groups *existing
`Visit` moments* into **journeys** — a run of away-from-home visits (default > 100 km from
home, gap < 36 h) becomes a `Trip` chapter ("Lisbon, Apr 12–17"). It reads visit coordinates;
it **never records movement** and it produces nothing for a local walk (which has no visits to
group in the first place).

So the gap the user hit is a genuinely unbuilt acquisition mode. To avoid overloading "I5",
this track is named **M1–M5 (Movement)** and lives in its own doc.

| | Reads | Produces | Built? |
|---|---|---|---|
| **Visit capture** (I3) | position fixes | `Visit` moment + `Place` (dwell) | ✅ |
| **Trip curation** (I5) | `Visit` moments | `Trip` (journey chapter over away-visits) | ✅ device-free |
| **Movement capture** (this doc, M) | position fixes | `Movement` moment + trace (the path *between* dwells) | ❌ |

---

## 1. The framing that drives the design

**Movement is the negative space of dwell detection.** The streaming segmenter already knows
exactly when a dwell begins and ends (`detectVisits` opens a cluster on the first fix, closes it
when a fix leaves the anchor radius). Everything *outside* a confirmed dwell is movement. We are
not adding a second sensor or a second detector — we are **keeping the fixes we currently throw
away** and shaping them into a segment.

Three consequences fall out of that framing and shape every decision below:

1. **One segmenter, two outputs.** The capture operator emits a *sealed* `CaptureEvent`
   — `Dwell(CapturedVisit)` **or** `Move(CapturedMove)` — from a single pass over the fix
   stream. The visit path is unchanged; the move path is new.
2. **The trace is heavy; the summary is light.** A dwell is four numbers (centroid + arrival +
   departure). A move is a *polyline* — a list of fixes. That asymmetry forces a storage
   decision (§4): summary metadata on the synced spine, the polyline as a **bounded, decimated
   blob** (content-plane-ready).
3. **Sensitivity goes up, not sideways.** A visit reveals *where* you were; a trace reveals
   *how you got there* — the route past your kids' school, the detour to a clinic. Threat-model
   asset **A35 already anticipated this** ("precise location — visits **and traces**"); §6 lands
   the amendment.

---

## 2. What gets recorded — the `Movement` moment

A movement segment becomes a first-class **`Moment`** so it rides the existing spine, projector,
feed, and reconciliation with zero new plumbing on the read side:

- **`kind = MOVEMENT`** — a new `MomentKind` constant. `MomentKind` is stored as a *plain
  string* field (`Moment.kt:145`), not a serialised enum, so adding a value is **not** a
  positional-composite change and needs no format bump *for the kind itself*. ⚠ Old peers must
  render an unknown kind via a graceful fallback (they already must, for forward-compat); the
  projector maps `MOVEMENT` → an "activity-like" anatomy.
- **`title`** — human summary, `"Walk · 2.4 km"` (mode · distance). Localised via
  `stringResource` at the projector, per ADR 0016 — the stored title stays a neutral fallback.
- **`durationSec`** — the segment span (existing field, reused).
- **`subject = SubjectRef(TRACE, traceId)`** — **the trace rides the existing `subject` field**,
  exactly as a `Visit` points at a `Place` and an `Activity` at a `Workout`. This is the
  load-bearing modelling choice: **no new field is added to the synced `Moment` composite**, so
  `Moment`'s wire shape is untouched. Only a new `SubjectKind.TRACE` string + a new subject
  record are introduced.

### 2.1 The trace record — `MovementTrace`

A new composite (`:timeline-service`, beside `Place`/`Trip`), the subject a `MOVEMENT` moment
points at. It carries the **summary on the sync/metadata plane** and the **path as a bounded
blob**:

```kotlin
interface MovementTrace : Composite {           // Syncable(Eager), keyed by traceId
    val traceId: TString        // content-addressed: "cap-move:<startMs>"
    val mode: TString           // walk | run | cycle | drive | unknown  (heuristic, §5)
    val distanceM: TInteger     // summed haversine over the fixes
    val startMs / endMs: UnixInstant
    val pointCount: TInteger
    val bbox: …                 // min/max latE7/lngE7 — cheap map framing without decoding the blob
    val polyline: TByteArray    // decimated, delta+varint encoded path (§4); "" if dropped
}
```

`RecordMovementTrace` op mirrors `RecordPlace`/`RecordMoment`; content-addressed by `traceId`
so a re-emit folds (§3).

**Why a separate record and not fields on `Moment`:** (a) it keeps the heavy `polyline` blob off
the `Moment` row so the feed query stays light and the moment composite's wire shape is
unchanged; (b) it mirrors the `Visit → Place` shape the projector already knows; (c) the trace
can later go **lazy / content-plane** (§4) without touching `Moment` at all.

---

## 3. The segmentation algorithm — the core

`FixClustering.detectVisits` becomes a special case of a **segmenter** that emits both event
kinds from one online pass. Pure `commonMain`, hardware-free, unit-tested exactly like
`detectVisits`/`VisitDetector`.

```
Flow<CapturedFix>.segment(config): Flow<CaptureEvent>     // Dwell(CapturedVisit) | Move(CapturedMove)
```

State: an anchor-run cluster (as today) **plus** a `moveBuffer` of fixes accumulated since the
last confirmed dwell's departure.

Per fix:
- **Within anchor radius** → add to the current cluster (candidate dwell).
- **The cluster crosses `minDwellMs` while still inside the radius** → a dwell is now *confirmed*.
  **Flush the `moveBuffer` as a `Move`** (from the last departure to this dwell's arrival), if it
  clears the move thresholds (below); then clear it. The dwell itself still emits on departure,
  unchanged.
- **A fix leaves the anchor radius**:
  - if the closing cluster was a *confirmed* dwell → emit `Dwell(CapturedVisit)` (today's
    behaviour), then begin a fresh `moveBuffer` at the departure fix;
  - if the cluster never reached `minDwellMs` (a sub-threshold pause — a traffic light, a shop
    window) → it was **movement all along**: fold those fixes into `moveBuffer` and keep going.
    *This is what makes a continuous walk one `Move` instead of dozens of sub-threshold
    fragments.*

Flush an open `Move` on: (a) the next confirmed dwell (above), (b) **capture stop** (service
teardown — flush the tail), or (c) a **soft `maxOpenMoveMs` cap** (default 2 h) that closes the
segment and starts a fresh one, so a very long continuous journey still surfaces incrementally
instead of only on arrival. Emitting-on-close keeps every `Move` **immutable** (simpler than a
mutating in-progress moment); the cap's cost is that a marathon drive fragments into ≤2 h legs —
an acceptable v1 trade, noted in §7.

**A `Move` must clear thresholds to be recorded** (jitter/GPS-wander suppression):
`minMoveDistanceM` (default 250 m), `minMoveDurationMs` (default 3 min), and ≥ 3 fixes.
Below any → dropped silently (it was noise, not a trip).

**These thresholds resolve the user's empty-feed report:** a 30-min walk clears 250 m / 3 min
trivially, so it becomes exactly one `Move` — flushed when they next dwell (e.g. arriving home)
or on the 2 h cap or on capture stop.

### 3.1 Config

Extends `VisitDetectionConfig` (or a sibling `MovementDetectionConfig` composed alongside it):

```kotlin
data class MovementDetectionConfig(
    val minMoveDistanceM: Double = 250.0,
    val minMoveDurationMs: Long = 3 * 60 * 1000L,
    val maxOpenMoveMs: Long = 2 * 60 * 60 * 1000L,
    val maxPolylinePoints: Int = 500,     // decimation cap (§4)
)
```

Per the existing config KDoc, these are conservative first guesses; the imported-history corpus
(I2) and real captures are the tuning data.

---

## 4. Storing the path — bounded blob now, content-plane later

The polyline is the only heavy part, and the platform already has a hard lesson here: the
cache-aside `SqliteObjectStore` holds every row resident, and large eager blobs (`Workout.Streams`)
OOM'd Control ([`bounded-store-cache.md`](bounded-store-cache.md)). §8 of the ingestion doc
pre-decided raw pings would *"ride the content plane if ever kept, like workout routes."*

**v1 decision — bounded inline blob, not the content plane.** At the current sensing cadence
(1 fix / 60 s / 20 m, `AndroidLocationCaptureSource`) even a 3-hour drive is ~180 points. Douglas–
Peucker decimation to `maxPolylinePoints` (500) + delta-of-E7 + delta-of-timestamp varint
encoding keeps a trace **≈ ≤ 6 KiB** — under the C-lite resident tier's ~4–8 KiB threshold,
and stored as a **single `TByteArray` scalar** (the shape SyncPolicy override-lists already use;
*not* a value-list, which isn't serialisable — the I5b/I6b lesson). A `MovementTrace` row is
therefore small and safe to sync `Eager`.

**Escape hatch (documented, not built).** If sampling later densifies (an opt-in high-fidelity
mode, or ATA-gated bursts), a single trace could exceed the resident tier. At that point the
`polyline` moves to the **content plane** — content-addressed, CEK-sealed, chunked, fetched
lazily on open — exactly the `File`/`Workout.Streams` → Option-B path, and `MovementTrace` keeps
only summary + a content ref. The §2.1 split (summary vs blob) is designed so this migration
touches `MovementTrace` alone, never `Moment`. **Prerequisite:** Timeline runs
`InProcessTimelineProxy` *standalone* and has no content-plane seam today; wiring one is the real
cost, hence deferral until traces actually grow.

The codec (`MovementTracePolyline.encode/decode`) is pure `commonMain`, unit-tested round-trip;
decimation is deterministic so re-encoding an unchanged path is stable.

---

## 5. Mode classification — speed heuristic, de-Googled

Mode is derived **on-device from the trace's own geometry** — no Activity Recognition dependency
(ATA lives in Google Play Services, absent on the GrapheneOS/e OS devices this app courts;
same stance as ingestion §4.4).

Compute per-leg speeds (haversine between consecutive fixes ÷ Δt), take a **robust central
statistic** (median, not max — GPS spikes), bucket:

| median speed | mode |
|---|---|
| < 2.5 m/s (~9 km/h) | `walk` |
| 2.5 – 4 m/s | `run` |
| 4 – 8 m/s | `cycle` |
| > 8 m/s | `drive` (covers transit; v1 doesn't split them) |
| too few fixes / degenerate | `unknown` |

Pure function `classifyMode(fixes): String`. **ATA as an opt-in refinement** (sharper boundaries +
lower-power batched wakeups) is the same deferred, GMS-gated enhancement already described for
visit capture; it slots behind this seam without changing the stored shape.

> **As-built placement (M1):** `classifyMode` landed in the `capture` package of
> `:timeline-shared-data`, beside `MovementSegmenter` — **not** in `:timeline-service-common`. The
> §11 move of `TripDetector`/`MomentReconciler` to `-common` was driven by a *dual consumer*
> (client projector **and** proxy merger) over *neutral* inputs. `classifyMode` operates over
> `CapturedFix` (which lives in `:timeline-shared-data`) and has a single consumer (the segmenter,
> at capture time), so cohesion keeps it with the fixes; there is no pull toward `-common`.

---

## 6. Write path & seam widening

Mirror the visit write path exactly:

- **`ImportedTimeline` gains `moves: List<ImportedMove>`** (+ `ImportedMovementTrace`), alongside
  today's `places`/`visits`. `TimelineImportWriter.write` learns to emit
  `RecordMovementTrace` + a `MOVEMENT` `RecordMoment`. Idempotent via the content-addressed
  `traceId`/`momentId`, like everything else.
- **`CapturedMoveWriter`** (or an extension of `CapturedVisitWriter`) reuses the **durable
  outbox** so a move survives a dead/hung proxy and never kills the capture engine
  (`CapturedVisitWriter`'s hard-won durability posture — do not regress it).
- **`LocationCaptureSource` widens** from `visits(): Flow<CapturedVisit>` to
  `events(): Flow<CaptureEvent>`:
  - **Android** composes `fixes().segment()` — one operator, both event kinds (§3).
  - **iOS (M4)** already gets `Dwell`s from `CLVisit`; movement comes from
    `startMonitoringSignificantLocationChanges` (a coarse, low-power between-visit fix stream) →
    `segment()` over those sparser fixes. The trace is lower-fidelity on iOS (Apple gives fewer
    points), which the mode heuristic and decimation both tolerate.
- `LocationCaptureEngine.run()` collects `events()` and dispatches each to the visit or move
  writer. The visit path is byte-for-byte unchanged.

---

## 7. Phasing (each independently shippable, device-free first)

Mirrors the ingestion track's proven *pure-core → write-seam → device → iOS* order.

1. **M1 — the segmenter + move model (no device).** `CaptureEvent` sealed type;
   `Flow<CapturedFix>.segment()` (dwell + move, §3); `CapturedMove` DTO;
   `MovementTracePolyline` codec (§4); `classifyMode` (§5). All pure `commonMain`, unit-tested
   against synthetic fix streams (walk / drive / sub-threshold-pause-doesn't-split / jitter-
   dropped / open-move-cap / dwell-flushes-move). **This is the highest-leverage slice — it
   proves the algorithm the user's report is really about, with zero hardware.**
   **As-built (M1 — SHIPPED, device-free, 2026-07-19):** all in the `capture` package of
   `:timeline-shared-data`. `MovementSegmenter` is a pure `onFix`/`onEnd` state machine (testable
   without a `Flow`) driving the `segment()` operator; it mirrors `detectVisits`'s anchor-radius
   clustering so the `Dwell` stream is **bit-for-bit identical to `VisitDetector.detect`** (asserted),
   while accumulating a `moveBuffer` flushed on dwell-confirm / `maxOpenMoveMs` cap / capture-stop.
   `CapturedMove` + `CaptureEvent` (sealed Dwell|Move) + `MovementMode` in `CaptureModels`;
   `MovementDetectionConfig` + `classifyMode` (median-leg-speed, spike-resistant) in
   `MovementSegmenter.kt`; `MovementTracePolyline` (Douglas–Peucker + uniform-stride cap; delta +
   zig-zag-varint codec) in its own file; `pathDistanceMeters` added to `Geo.kt`. A sub-threshold
   move is dropped via `toCapturedMoveOrNull` (min 250 m / 3 min / ≥3 fixes). **17 tests green**
   (`MovementSegmenterTest` 5, `MovementTracePolylineTest` 5, `MovementModeTest` 7) on
   `:timeline-shared-data:desktopTest`. No schema/wire/op yet — that is M2.
2. **M2 — the write seam (no device).** `MovementTrace` schema + `RecordMovementTrace` op +
   `SubjectKind.TRACE` + `MomentKind.MOVEMENT`; `ImportedTimeline.moves` + writer mapping;
   `CapturedMoveWriter` over the outbox. In-process tested via `InProcessTimelineProxy`
   (`TimelinePipelineIntegrationTest`: a synthetic walk → one `MOVEMENT` moment + trace →
   projector shows "Walk · N km", idempotent re-emit folds). **This PR is format-neutral** — the new
   `MovementTrace` type + `traces` relation + `MOVEMENT`/`TRACE` values are all *additive* (no
   existing row reshaped), and nothing emits a `MovementTrace` until capture actually runs, so it
   touches `TENSHU_STORAGE_FORMAT_VERSION` not at all. (`master` is already on v3 for an unrelated
   change, and `MovementTrace` rides additively under it.) A dedicated lockstep bump — if one is
   still warranted — belongs to **M3's on-device activation**, with the first emitted row (below).
   **As-built (M2 — SHIPPED, device-free, 2026-07-19):**
   `MovementTrace` (`:timeline-service`, `Syncable(Eager)`, keyed by `traceId`; summary scalars +
   a `byteArray` `polyline`) + `RecordMovementTrace` op + `MovementTraceDataIdProvider`;
   registered in the common/client/proxy modules like `Trip` (DTO, trait, client Ro, proxy
   executor, factory branch). New **`TimelineService.traces`** relation + its
   `StringKeyedRelationMaintainer` (the maintainer was already generic — a third binding). Subject
   modelling per §2.1: **`Moment` is unchanged** — a `MOVEMENT` moment rides the ordinary `moments`
   spine with `SubjectRef(TRACE, traceId)`; only the `MOVEMENT`/`TRACE` **string values** were added
   (no positional-composite change). `ImportedTimeline` gained `traces: List<ImportedMovementTrace>`;
   `MovementMapper.capturedMovesToTimeline` builds the moment + trace pair (title "Walk · 2.4 km",
   decimated+encoded polyline); `TimelineImportWriter` writes traces before their moments.
   `CapturedMoveWriter` mirrors `CapturedVisitWriter`; both now delegate to a **new shared
   `DurableImportWriter`** (extracted so the outbox/timeout/replay durability is single-sourced — the
   proven `CapturedVisitWriterOutboxTest` still passes against it, no drift). **Format-neutral** —
   the additions are purely additive and no `MovementTrace` flows yet, so this PR doesn't touch the
   format version (a dedicated bump, if warranted, rides M3's on-device activation, below); no relay
   redeploy is owed by this PR. Projector needs **no change**: an unknown kind falls back to the generic
   anatomy and `MomentReconciler` passes a `MOVEMENT` moment through as its own canonical (verified).
   **Tests green:** `TimelinePipelineIntegrationTest.capturedMovementLandsAsAMomentAndTrace`
   (walk → segment → write → projector shows "Walk · N km"; trace persists with a **polyline that
   decodes back through the real store**; idempotent re-write folds) + the full timeline +
   `sync-service-proxy` `MomentMergeTest`/`TripMergeTest` suites + `timeline-desktop`.
3. **M3 — Android capture (needs device).** Widen `LocationCaptureSource.events()`;
   `AndroidLocationCaptureSource` composes `fixes().segment()`; the FGS engine dispatches both.
   On-device pass: walk / drive → a movement entry appears; battery sanity at 1 fix/min. **This
   is the slice that closes the user's report.** ⚠ **Any `MovementTrace`-specific
   `TENSHU_STORAGE_FORMAT_VERSION` bump lands here** — with the on-device activation, before the first
   real trace is emitted, so an old peer never receives a `SyncBlock` it can't parse and clients +
   relay move in lockstep (HR #9). (Whether a *dedicated* bump is even needed depends on the version
   at that time — `MovementTrace` is additive, so it may simply ride the then-current format.)
   **As-built (M3 — SHIPPED, on-device pass pending, 2026-07-19):** the `LocationCaptureSource` seam
   widened from `visits(): Flow<CapturedVisit>` to **`events(): Flow<CaptureEvent>`** (dwell **and**
   move). `LocationCaptureEngine` now holds both a `CapturedVisitWriter` and a `CapturedMoveWriter`
   (sharing the one `CapturedVisitOutbox` — entries key on distinct moment ids, so either drain
   replays either kind) and dispatches each event by type in one collect loop; its `config` param
   split into `visitConfig` + `moveConfig`. `AndroidLocationCaptureSource.events()` composes
   `fixes().segment(config, moveConfig)` — the platform layer stays a thin `LocationManager` fix
   emitter (still de-Googled; no fused location). The `TimelineCaptureForegroundService` wiring is
   unchanged (named-arg construction + defaults). Device-free proof: `TimelinePipelineIntegrationTest`
   `.captureEngineDispatchesBothMovesAndVisits` (a `Move` + a `Dwell` fed through the widened engine
   → both a MOVEMENT moment and a Visit moment land). **Green:** `:timeline-shared-data:desktopTest`
   (8 pipeline cases) + androidMain compile + **`:timeline-android:assembleDebug`** (DI/manifest).
   **Deferred (device):** the on-device pass — walk/drive produces a movement entry, background/Doze
   fix delivery, battery at 1 fix/min — the same hardware-gated tail as visit capture's I3b-2b. **Any
   `MovementTrace`-specific format bump + relay redeploy ships with that activation** (the first slice
   that emits a real trace), not with this build — additive schema, nothing emitted yet, so no
   lockstep pressure until then.
4. **M4 — iOS capture. SHIPPED device-free 2026-07-24** (bundles ingestion I4-2 — iOS *visit*
   capture was never built either, so this slice is iOS live capture wholesale). **As-built:**
   the **event model** the `CapturedVisitWriter` KDoc designed for iOS — direct awaitable writes,
   not Android's long-lived Flow+collector. Acquisition is Kotlin `iosMain` (the
   `IosRecordingEngine` precedent), not literal Swift: `IosLocationCapture`
   (`:timeline-shared-data` iosMain) owns a `CLLocationManager` + Kotlin delegate running
   **`CLVisit` monitoring (dwell authority) + significant-location-change fixes (movement)**;
   no `location` UIBackgroundModes — both monitors relaunch the app per event, and the Swift shell
   re-arms capture in `TimelineIosApp.init` so a background relaunch actually receives the event
   that woke it. The segmenter interplay is a new **pure `EventDrivenCaptureSession`**
   (`commonMain`, desktop-tested): SLC fixes → shared `MovementSegmenter`, `Move`s surfaced,
   segmenter `Dwell`s dropped (CLVisit owns dwells; structurally near-absent from ≥500 m-spaced
   fixes anyway); a visit event flushes the led-up-to transit **terminated at the visit centroid**
   (timestamp coerced forward — CLVisit arrivals are retroactive) and **replaces** the segmenter
   (its flush is non-mutating; a reused buffer would extend the flushed move into the next trip);
   a closed visit re-seeds movement from the place. CLVisit sentinel mapping: distant-future
   departure = arrival delivery → the §11.1 *confirmation* emit (`ongoing=true`, departure clamps
   to now, `OngoingVisitState` set — content-addressing folds the later final emit onto it);
   distant-past arrival on a closed visit clamps to `minDwellMs` before departure; neither bound →
   dropped. Enactment mirrors Android: `IosTimelineCaptureControl` (NSUserDefaults opt-in, A35(a))
   + `TimelineCaptureRuntime` (`:tenshu-sdk-ios`) over the route-1 runtime's client
   (`isCaptureAvailable` = live runtime installed → gates the new Settings toggle); the toggle tap
   is the consent moment (requests Always authorization); a denied/restricted grant resets the
   flag. Writes ride the same never-throwing writers, ordered through a single worker channel.
   ⚠ v1 limits, accepted: no durable iOS outbox (a write failed while sealed is dropped, logged);
   walks under SLC's ~500 m resolution may be missed (the battery deal iOS offers — the fidelity
   escape hatch is standard-updates-while-foregrounded, later); rail refinement sweep not wired
   (the `railLinesIn` op is inert on iOS — no `TileDecompressor` — so it would no-op as
   `Unavailable`; harmless to add when the decompressor lands). **Remaining: the on-device pass**
   (real visit/SLC deliveries, background relaunch, battery) — the same hardware-gated tail as
   Android's M3b.
5. **M5 — the route on a map (UI). SHIPPED device-free 2026-07-24.** A `MOVEMENT` moment's detail
   renders its decoded polyline over `:ui-map` (the Maps `pmtiles` offline stack already exists).
   **As-built:** assembly of existing pieces, no new schema/op/wire — the detail hero is the same
   `TripRouteMap` → `RouteBasemap` surface the trip detail ships, drawn from the trace instead of
   visit-place coordinates. The UI `Moment` gains `traceId` (its `TRACE` subject id, symmetric with
   `placeId`); `TimelineRepository.movementRoute(traceId)` is the **one** read that projects the
   `polyline` blob (one-shot on detail open, decoded via `MovementTracePolyline`; the feed's
   `TRACE_FIELDS` stays blob-free per §2.1 — the id filter runs client-side, the `renamePlace`
   row-lookup shape, bounded because every blob is decimated); `MomentDetailViewModel` gains the
   optional best-effort `RegionBundleResolver` and upgrades the hero in place (moment → route →
   streamed/staged region), mirroring `PlaceDetailViewModel`; a movement with no decodable path
   keeps the M5-lite stats-only body. Proven end-to-end in `TimelinePipelineIntegrationTest`
   (captured walk → store → `movementRoute` returns the decoded path; unknown id → empty) + VM
   route/region threading tests. Remaining: the maintainer on-device click-through (the route draws
   over real region tiles), folded into the standing M3b pass.
   **iOS sibling (2026-07-24):** the SwiftUI `MomentDetailView` draws the movement hero too —
   `RouteSketchCard`, the Swift twin of `:ui-map`'s `RoutePolyline` normalized sketch (the desktop
   fallback shape) over the same shared VM `route` state. **Deliberately not MapKit**: Apple-served
   tiles would disclose the route's region to a third party on every detail open, which is exactly
   what the offline-tile/schematic posture avoids (A35); a real geographic basemap on iOS is a
   MapLibre-offline follow-up behind the same card. ⚠ **Narrowed 2026-08-13** —
   [`ios-apple-maps-option.md`](ios-apple-maps-option.md) is accepted, so this hero is one of the
   three MapKit may render *when the user has elected it* (off by default, revocable, per device).
   MapLibre stays what this card draws for everyone who has not.
   Drive-bys: the `MomentKind.movement` SF-symbol
   case (missed at M5-lite — movement rendered the default "circle" glyph in the feed rail and
   hero) + mode/distance stat labels on the detail (M5-lite parity; mode rendered raw-capitalized
   so an unknown newer mode still displays).

M1+M2 make movement real and verifiable with no hardware. M3 is the fix the user is waiting for.

---

## 8. Privacy & threat model (House Rule #2)

Traces are **more** disclosive than visits (route vs endpoint). The amendment lands with M2/M3:

- **Asset A35 already scopes it** ("precise location — visits *and traces*", ingestion §4.4 /
  threat-model). Reaffirm and extend: the `polyline` — inline `TByteArray` or (later)
  content-plane blob — is **sealed like all synced data; the relay sees ciphertext** (HR #1
  intact). No coordinate, no route, ever relay-visible in plaintext.
- **On-device only:** segmentation, decimation, and mode classification are all local pure
  functions. No route leaves the device except as sealed sync payload.
- **Same opt-in / indicator posture as visit capture** — off by default, the persistent FGS
  notification is the A35 capture indicator; movement adds no new consent surface (it reuses the
  same location grant + running service).
- **Decimation is a privacy feature too**, not only a size one: the stored path is a bounded
  approximation, not the raw high-rate trace.

---

## 9. Open questions

**Decided (2026-07-19):**

- **Traces sync — ship v1 eager (Option A), architect toward summary-only (Option C).** The
  `MOVEMENT` moment + `MovementTrace` (summary **and** the decimated polyline) sync `Eager`, so a
  walk shows on every device and survives phone loss (sealed on the relay, HR #1 intact). This is
  chosen over **device-local-only** (Option B, the `TripSuggestion` posture) because Timeline is
  consumed on **desktop/web where there is no capture** — device-local movement would be invisible
  exactly where much viewing happens; B also loses history with the phone. The volume worry is
  modest at the current 1-fix/min cadence: a normal day is ~4–6 segments (≈36 KiB/day, ≈13 MB/yr);
  even a heavy-trip user ≈65 MB/yr. **Crucially the §2.1/§4 summary-vs-blob split makes Option C a
  drop-in later** — if a power-user's volume or a future dense-sampling/high-fidelity mode changes
  the math, the polyline relocates to the content plane (fetched lazily on open) touching only
  `MovementTrace`, never `Moment`. So: eager now, escape hatch pre-built.
- **Emit-on-close, not live in-progress moves (Option A).** A `Move` emits when it closes (next
  confirmed dwell / `maxOpenMoveMs` cap / capture stop) → **immutable** moments, clean idempotency,
  no re-sync churn. This already fixes the trigger report ("nothing showed after the walk" → it
  shows *after*, and the 2 h cap surfaces long ongoing journeys periodically). A live
  "walking now" card (a mutating moment keyed on `startMs`, re-syncing a growing blob every update)
  is a deferred nicety, not worth the churn in v1.
- **Single `drive` bucket in v1.** Mode classification keeps one vehicular bucket; splitting
  bus/train/car needs ATA or rail map-matching — out of scope, slots behind the §5 seam later.
  ⚠ **Superseded 2026-07-20 — see §13.** The "needs ATA" half of that reasoning was wrong: we
  already ship rail geometry offline. The *deferral* stands (it is not v1 work); the *reason*
  changes, and the designed answer is now map-matching, not an activity-recognition API.

**Deferred with a designed answer:**

- **Cross-kind reconciliation with health workouts → an I6 extension (not an M-track blocker).**
  A phone-captured `MOVEMENT` walk and a Fit watch `ACTIVITY` (`Workout` subject) of the *same*
  run are near-duplicates the built `MomentReconciler` won't fold (different kind + subject). The
  fix is **Option 2 — reconcile at the Moment layer** (extend `MomentReconciler` to fold
  MOVEMENT↔ACTIVITY by overlapping time **and** proximate geometry, so a walk and a drive in the
  same window don't fold), reusing the I6 supersede-not-delete / deterministic-key / converge-via-
  `MomentMerger` machinery — which also handles the late-arriving workout (reconciliation re-runs
  when it lands). A **capture-side gate** (Option 1) is rejected: the watch commonly syncs *after*
  the walk is captured, making suppression ordering-fragile, and it couples capture→health at the
  wrong layer. **Priority rule:** a health `Workout` supersedes the captured `MOVEMENT` (structured,
  sensor-rich beats a decimated trace) *unless* the workout has no GPS and the capture has a route
  (keep the trace). The interim v1 double-entry is a **narrow overlap** — only users running
  background capture *and* a GPS workout of the same session; most have one source.

**Still open (needs data, not a decision):**

- **Tuning corpus.** `minMoveDistanceM` / `maxOpenMoveMs` / decimation ε want real captures; shares
  the visit-detection tuning-data gap (ingestion §8). Ship the conservative §3.1 defaults; tune later.

## 11. On-device capture findings (as-built, 2026-07-19)

The first real-device pass (Android, an OPLUS/ColorOS phone) exposed that live capture recorded
**nothing** at a place the user sat at — surfacing four genuine bugs in the shipped Android capture
path plus a UX gap. All are fixed in `feat/timeline-capture-fixes` with **production values**; the
diagnosis is preserved here because none were visible in the device-free tests.

### 11.1 Fixes are only delivered while *moving* — and only outdoors

The Android `LocationCaptureSource` had three compounding faults that meant a stationary user got no
usable fixes, so no dwell ever formed:

1. **`minDistanceM = 20 f`** on `requestLocationUpdates`. `LocationManager` only broadcasts a fix once
   the device has *moved* that far, so **sitting still delivers zero fixes** — the exact opposite of
   what dwell detection needs. → **`minDistanceM = 0`** (updates pace on `minTimeMs` alone).
2. **GPS-only provider preference.** Indoors GPS often never gets a fix while `NETWORK` (wifi/cell)
   does. → **listen on all enabled providers** (`GPS` + `NETWORK`).
3. **No seed** → up to a full `minTimeMs` before the first fix. → **seed from `getLastKnownLocation`**,
   but only if **recent** (`SEED_MAX_AGE_MS = 2 min`): a stale seed carries an old timestamp that
   mis-dates the dwell (an 80-min-old GPS last-known produced a bogus "1 h 20 min" visit).

**And the detector only emitted a dwell on *departure*.** Even with fixes flowing, the streaming
segmenter closed a dwell only when a fix landed *outside* the radius — so a place you were **still at**
recorded nothing until you left (or capture stopped, which dropped it). → the segmenter now emits the
dwell **on confirmation** (`Dwell(ongoing = true)`) the moment the stay crosses `minDwellMs`, and again
on departure (`ongoing = false`, final duration); both are the same content-addressed visit and fold to
one place.

### 11.2 Live duration without per-minute sync writes (the §9 "count up" decision → option B)

Once a place recorded, its duration was frozen (written once at confirmation). We considered
**re-emitting the dwell every minute** (option A) but rejected it: re-writing + syncing a place row per
minute while present is heavy on the sync plane. **Option B (chosen):** the moment is written just twice
(confirm + departure); a device-local **`OngoingVisitState`** singleton (never synced — capture and the
UI are co-process in the Timeline app) holds the current place + arrival, and the projector shows
`isCurrent` + a **live duration = now − arrival**, re-emitted by a `LIVE_TICK_MS` ticker in
`homeDays()`. The engine sets `OngoingVisitState` on the confirmation dwell and clears it on
departure / a move / capture stop.

⚠ Note: a revisit to the same place within the reconciler's time bucket can fold the ongoing moment into
an earlier canonical, in which case the live overlay (matched by arrival) won't attach — a known edge,
acceptable for v1.

### 11.3 Place polish — imagery, precision, venue naming (follow-up track)

> **Phase 2 continues this** in [`timeline-recognition-and-place-naming.md`](timeline-recognition-and-place-naming.md):
> the residual venue-vs-street gap (P3 shipped Photon but with a blunt `limit=1`/no-radius query),
> confidence-aware coarsening, a `labelSource` marker so improvements reach already-mislabelled
> places, and an opt-in device-local **WiFi fingerprint** — plus the T0 tuning + T1 sweep
> diagnosability that fix the "train reads as Drive" case (§13).

A second on-device pass raised: every place shows the same flat blue placeholder, and a coarse indoor
fix names the *street* not the *venue*. Three phased fixes:

- **P1 — place-detail map hero (SHIPPED).** The `DEFAULT_COLOR` hero (identical for every place) is
  replaced by a real offline map centred on the place's coordinate — `:ui-map`'s `OfflineBasemap` +
  a pin, over the place's own downloaded/streamed region tiles (keyless MapLibre, HR #1), demo/schematic
  fallback so it never blanks. `Place` gained `latE7`/`lngE7` (projector-populated); `PlaceDetailViewModel`
  resolves the region stream like `TripDetailViewModel`. Feed rows stay text (a MapLibre surface per
  `LazyColumn` row would OOM).
- **P2 — accuracy-weighted dwell centroid (SHIPPED, `Geo.kt` §11.3 above).** Fixes weight `1/accuracy²`,
  so one precise GPS fix pulls the location instead of being smeared by coarse ±100 m network fixes;
  accuracy-free fixtures are exactly the plain mean (unchanged). Helps the mixed-fix case; deep-indoor
  precision is still capped by the ±100 m network floor (GPS can't see sky).
- **P3 — venue naming via Photon POI geocoding (proxy-mediated, SHIPPED).** The address-only
  `android.location.Geocoder` returns streets; Photon (POI-aware) returns OSM venue names.
  ⚠ The Timeline app **deliberately has no `INTERNET`** (egress routes through Control — manifest
  `tools:node="remove"`), so Photon can't call out directly. As-built: a **`ReverseGeocode` op on
  `:map-region-service`** (params latE7/lngE7 → label `TString`); the proxy impl
  (`ReverseGeocodeImplementation` + `PhotonGeocoder`, a `MapRegionProxyComponents` seam built in
  `mapRegionComponents(…, geocodingEndpoint)`) does the Photon egress **on Control**, mirroring the
  tile-streaming ops — the app stays INTERNET-free. A Timeline `ProxyReverseGeocoder` (in
  `:timeline-android`, over `client.mapRegionService.reverseGeocode`) is chained **ahead** of the
  platform `AndroidReverseGeocoder` (`ChainedReverseGeocoder` — POI first, street fallback), both under
  the existing `PlaceGeocodingControl` consent. Threat-model **A35 amended** (HR #2 — egress off the app
  *and* the relay; Control ≠ relay). Endpoint configurable (self-hosted Photon preferred; komoot public
  default under consent). Green: `map-region-service*` compile + `jvmTest`, `control-shared-proxy`
  desktop + android compile (the 3 platform factory call sites), `timeline-android:assembleDebug`.
  Caveat unchanged: deep-indoor precision is still capped by the ±100 m network floor, so the venue name
  raises the odds but rename stays the reliable fix.

### 11.4 Capture dies on aggressive OEMs and never comes back — the resilience slice (2026-07-21)

A second real-device session (the same ColorOS phone) found capture "stopped tracking" mid-day. The
chain of findings, none visible without the device:

1. **Background location had been silently downgraded** to "while in use" (`ACCESS_BACKGROUND_LOCATION`
   revoked) — ColorOS auto-downgrades it. ~~Capture then works only while the app is on screen.~~ User-
   fixed in settings; not a code issue, but the first red herring.
   > ⚠⚠ **Struck too hastily, and re-instated with the nuance (2026-08-10, §30).** The *literal*
   > sentence is wrong: an **already-running** location FGS keeps capturing with only foreground
   > permission — measured, 122 fixes between 01:00 and 05:00, backgrounded and screen off. But the
   > *practical* claim it was making is right, because a killed FGS **cannot restart** without
   > background location (§30), so after the first OEM kill capture really does only run while the app
   > is open. Two different states, and §26.6 saw only the first.
2. **The whole process was force-killed** — `pidof` returned nothing while the foreground-service
   notification still showed. That paradox is real: the FGS notification lives in **SystemUI, a
   separate process**, so a force-stop orphans it — a visible "capturing" banner backed by a dead
   process. So the notification is *not* a liveness signal.
3. **Nothing restarted it.** `START_STICKY` does **not** survive an OEM force-stop (only a memory
   kill), and the only FGS start path was foreground-only (`MainActivity`'s opt-in observer). So once
   ColorOS killed it, capture stayed dead until the user next opened the app — including across a
   plain reboot.

**As-built (SHIPPED device-free + emulator-verified, 2026-07-21).** App-side resilience, all in
`:timeline-android` over a pure decision in `:timeline-shared-data`:

- **`CaptureWatchdogWorker`** — a WorkManager periodic job (~15 min) that restarts the FGS if the
  opt-in is on but the service isn't running. WorkManager survives OEM kills far better than a bare
  FGS (the OS keeps re-scheduling it). `CaptureWatchdogScheduler` (mirrors Drive's
  `BackupWorkScheduler`) enqueues it while capture is on, from `TimelineApplication` — so it is armed
  even if the user never returns to Settings. The restart decision is `decideCaptureRestart` (pure,
  unit-tested): restart iff opted-in **and** service dead; it never *stops* anything.
- **`CaptureBootReceiver`** — `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` re-arm, closing the
  reboot-kills-capture-permanently gap. `BOOT_COMPLETED` is an allowed window for background FGS
  start on Android 12+.
- **Battery-optimisation exemption** (`BatteryOptimization` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
  — prompted once when capture is enabled and the app isn't exempt. ⚠ This is both a **durability**
  lever (aggressive OEMs force-stop non-exempt apps regardless of how correct the FGS is) and a
  **correctness** one (exemption lifts the Android 12+ background-FGS-start restriction, so the boot
  receiver and watchdog restarts are reliably *allowed*, not merely attempted — the re-arm wraps the
  start and treats a refusal as a logged no-op).
- **`CaptureHealthStore`** — a few `SharedPreferences` timestamps/counters (last FGS start, watchdog
  restart count + time, last tick). This is the durable "did it actually run?" trail the field
  session wanted, done without the (read-only, error-only) `LoggingService`: it survives process
  death and is readable on-device / over `adb`. Emulator run confirmed the full loop — FGS start
  recorded, watchdog enqueued and executed, and a restart correctly detected-and-recorded.

⚠ **The decisive fix is still user-side and no code replaces it.** On ColorOS/OPPO/Xiaomi/… the app
must be on the OEM's **"auto-launch"/startup allowlist** (separate from battery optimisation) and
ideally locked in recents; without it the OS force-stops even a battery-exempt app. The app-side
watchdog *recovers* from a kill within ~15 min and survives reboots; the allowlist *prevents* the
kill. Both matter. Worst-case capture is down one watchdog interval after a kill — a large
improvement over "dead until reopen", but not zero.

## 12. Fidelity — what we throw away, and why it is not symmetric (2026-07-20)

**Trigger.** Reviewing the shipped M1–M3 against the (unbuilt) Google-activity import raised a
question the phasing had not asked: *is the model lossless enough that data we record today stays
useful for things we have not designed yet?* It is not. This section enumerates the loss and fixes
the part that matters.

### 12.0 The asymmetry that sets priority

> **Import loss is re-runnable. Capture loss is permanent.**

A Takeout archive can be re-imported after the parser widens — the source file is still on disk.
A position fix that was never persisted is gone forever; you cannot go back and re-derive a train
ride from a trace that dropped its accuracy, its speed distribution, and its direction of travel.

That asymmetry inverts the intuitive ordering. **Capture fidelity outranks import fidelity**, and
both outrank the UI work — because every slice that ships before the fidelity pass writes rows that
are permanently un-improvable. Concretely: §13's transit classifier cannot be applied retroactively
to any trace written today, because nothing records which classifier produced the label.

### 12.1 Capture-side gaps (fix these first)

| Gap | Where | Consequence |
|---|---|---|
| **No `classifierVersion`** | `MovementTrace.kt` | `MovementSegmenter.kt:7` calls its own thresholds "conservative first guesses" needing a tuning corpus. With no version stored, **retuning can never be applied retroactively**, and pre-/post-tuning traces are silently incomparable in one feed. **Highest-value missing field**; the precondition for §13. |
| **No `modeConfidence`, no median speed** | `classifyMode`, `MovementSegmenter.kt:189` | Only the bucket survives. A trace whose median sat at 2.49 m/s ("walk") is stored indistinguishably from one at 0.8 m/s. No way to surface uncertainty, no way to re-bucket. |
| **`accuracyM` dropped at encode** | `MovementTracePolyline.kt:60` (decode defaults it to `0`) | After round-trip a 5 m GPS fix and a 2 km cell fix are indistinguishable → **no post-hoc re-filtering and no confidence re-derivation**. Directly blocks §13's T0, which needs to discount low-accuracy legs. |
| **No start/end coordinates — bbox only** | `MovementTrace.kt:68-71`, `MovementMapper.kt:48` | The bbox is `minOf`/`maxOf`, order-agnostic: **a walk A→B and the return B→A have identical summaries.** Direction survives only inside `polyline`, which the KDoc says may be empty. Defeats §2.1's own stated goal ("frame the route without decoding") for anything directional — no origin pin, no destination pin, no arrow. |
| **`pointCount` is post-decimation** | `MovementMapper.kt:46` (`simplified.size`) | You cannot tell how much was discarded, so you cannot tell a dense trace from a sparse one. |
| **`distanceM` not re-derivable from `polyline`** | `MovementSegmenter.kt:169`, `MovementMapper.kt:30` | Computed over the raw path *before* simplification — correct, but it means decode-and-re-sum disagrees with the stored value. Worth a KDoc note so a future reader does not "fix" it. |
| **`capPoints` is uniform-stride by index** | `MovementTracePolyline.kt:101` | Discards the Douglas–Peucker shape guarantee it just computed. A 3-hour drive capped to 500 points keeps every 20th index regardless of whether that stretch was a motorway straight or a city grid. Should cap by *arc length*, not index. |
| **Sub-threshold moves vanish with no record** | `toCapturedMoveOrNull`, `MovementSegmenter.kt:169` | <250 m / <3 min / <3 fixes → dropped silently. Under Doze or indoor fix starvation a real 10-min walk can starve below 3 fixes and be discarded as noise, indistinguishable from genuine jitter. |
| **`CapturedFix` is narrow upstream of the schema** | `CaptureModels.kt:15`, `AndroidLocationCaptureSource.kt:100` | `altitude`, `bearing`, `speed`, `provider` are never even read off `android.location.Location`. `provider` (GPS vs NETWORK) and `speed` are both §13 inputs. |

**Decision D-F1 — add the fields, keep the plane.** `classifierVersion`, `modeConfidence`,
`medianSpeedMmPerS`, `startLatE7/startLngE7/endLatE7/endLngE7`, `rawPointCount` are all scalars.
They cost ~28 bytes on a row already budgeted at ≈6 KiB, so they stay on the eager summary plane;
§4's content-plane escape hatch remains reserved for the `polyline` alone. `accuracyM` re-enters
the *codec* (a fourth varint stream), which does grow the blob — see §12.4.

**Decision D-F2 — store the classifier's inputs, not only its verdict.** The rule generalises
beyond mode: where a field is *derived*, persist enough to re-derive it under a better rule.
This is what makes §13 shippable in tiers instead of as one big-bang classifier.

### 12.2 Import-side gaps

`GoogleTimelineParser.kt` declares a deliberately narrow DTO surface and runs
`Json { ignoreUnknownKeys = true }`, so everything else is dropped silently at deserialization.

**Fits the schema today, purely parser work:**

- **`timelinePath`** (new format: `semanticSegments[].timelinePath[]` of
  `{point, durationMinutesOffsetFromStartTime}`) — **not referenced anywhere in the repo.** This is
  the actual movement geometry in modern exports, and it maps 1:1 onto `CapturedFix` → the existing
  `MovementTracePolyline` codec. Largest single loss: every imported activity is currently *pathless*.
- **`simplifiedRawPath`** (legacy) — E7 + `timestampMs`, same shape, fits fully.
- **`waypointPath`** (legacy) — fits, but waypoints carry **no timestamps**; needs either synthetic
  deltas or a timeless-path codec variant.
- **`distance` / `distanceMeters`** → `MovementTrace.distanceM`, once activities become traces.

**Needs new fields (proposed, small):**

- `startLocation` / `endLocation` (legacy), `activity.start` / `.end` (new) → the same
  start/end-coordinate gap as §12.1. **One fix serves both directions.**
- `activityType` **raw string** — currently bucketed by `humanActivity()` into 9 English display
  labels and stored as the moment *title*, so the raw value is unrecoverable: `IN_BUS`, `IN_TRAM`,
  `IN_SUBWAY` all collapse, `IN_PASSENGER_VEHICLE` and `MOTORCYCLING` both become "Driving".
  ⚠ **This is the one field that already carries the transit class §13 is trying to derive** — and
  we currently discard it. Retaining it is near-free and immediately useful.
- Google's own confidence (`activitySegment.confidence` HIGH/MEDIUM/LOW; new
  `topCandidate.probability`) → the same `modeConfidence` field §12.1 adds.

**Structural, deferred:**

- `childVisits` (sub-visits inside a mall/airport) would flatten into look-alike duplicates the
  reconciler cannot distinguish — needs a `parentMomentId`. **Out of scope; import must not flatten
  them silently.** Recommendation: skip children in v1 and log the count.
- `parkingEvent`, `otherCandidateActivities`, `placeConfidence`/`visitConfidence` — no home; defer.

**Policy-blocked, not schema-blocked:**

- **`transitPath`** (legacy `activitySegment.transitPath.{transitStops[], name}`) carries the actual
  line name ("U8") and ordered stop list — exactly what §13 wants. But the parser's own KDoc
  (`GoogleTimelineParser.kt:30-33`) records that Google *content* must not be persisted beyond the FK
  `placeId`, and stop names and line names are content. **Decision D-F3: do not persist
  `transitPath`.** The raw `activityType` category is a classification, not content, and is retained
  instead. Flagged explicitly because it is tempting and it is the wrong shortcut.
  (⚠ Note the existing legacy path already persists `placeVisit.location.name` as `label` — a
  pre-existing tension with the same KDoc, out of scope here but worth a separate look.)

### 12.3 `sources` provenance is wired but inert — and it blocks coexistence

`Moment.sources: TList<MomentSource>` exists, is in the schema (`Moment.kt:105`), is `Merge`-wired,
and `Moment.create` accepts it. **Nothing ever sets it.**

- `ImportedMoment` (`ImportedTimeline.kt:73`) has no `sources` field at all.
- `TimelineImportWriter.kt:101` calls `Moment.create(...)` without passing it.
- `TimelineProjectionRepository.kt:393` builds `ReconcilableMoment` **without a `source` argument**.

Knock-on: `MomentReconciler.kt:49` unions `members.map { it.source }` — always empty — so
**`electCanonical`'s source-priority tie-break has no signal**, and a Google-backfilled visit meeting
a live-captured visit in the same cell/bucket is resolved by the *earliest-start* fallback rather
than by source priority. The only code in the repo that sets `sources` is a merge test.

This is inert today because the two datasets rarely coexist. **It stops being inert the moment
Google activity import lands** — which is exactly what this track proposes. Fix it in the fidelity
pass, ahead of the importer. No new schema field is needed; the plumbing already exists.

### 12.4 Budget check (the `Workout.Streams` lesson)

`MovementTrace` is `Syncable(Eager)` with a blob field — structurally the pattern that OOM'd Control
([`bounded-store-cache.md`](bounded-store-cache.md)). Adding fields pushes on the C-lite ~4–8 KiB
resident threshold, so the additions are budgeted explicitly:

- scalars (§12.1): ~28 B — negligible.
- `accuracyM` in the codec: a fourth varint stream, ~1 B/point after delta encoding for typical
  slowly-varying accuracy → ≈ +500 B at the 500-point cap. Acceptable.
- **raw (pre-decimation) fixes: NOT stored on the eager plane.** If a tuning corpus later wants
  them, they go to the content plane per §4, never inline. The §2.1 summary-vs-blob split is what
  keeps that a `MovementTrace`-only migration.

### 12.5 Live bug found during this review

`GoogleTimelineParser.parseAll` (lines 50–56) merges only `places` and `moments` —
**`traces`, `workouts` and `trips` are silently dropped on multi-file import**, even though
`ImportedTimeline` carries them. Harmless today (the Google path emits none); **fires the instant
activity import lands.** Fix it in the fidelity pass, not in the importer slice, so the importer
lands on a correct merge.

### 12.6 The open move buffer was not durable — `OpenMoveJournal` (as-built, 2026-07-31)

**The largest instance of §12.0's asymmetry, and it was not in the §12.1 table.** Every gap listed
there is about *fidelity* — a recorded leg that is less useful than it could be. This one was about
*existence*: `MovementSegmenter` accumulated the whole in-progress journey in an in-memory
`moveBuffer` that became durable only at a **flush** (a dwell, the `maxOpenMove` cap, or capture
stop). Nothing persisted the buffer itself, so a process death mid-journey discarded the entire leg.

That is not a rare corner. ColorOS force-kills the capture foreground service routinely (§11.4), and
**iOS holds no capture process at all** — the `CLVisit`/significant-change monitors relaunch the app
per event and it is suspended again in between, so *every* inter-event teardown discarded the
journey so far. The existing `CapturedVisitOutbox` / `DurableImportWriter` do not help: they make a
*detected* leg durable before its write ops run, one step too late.

**Shape — the outbox contract, one level upstream.** A new `OpenMoveJournal` (injected interface,
`OpenMoveJournal.None` default — *not* `expect`/`actual`, since the segmenter is pure `commonMain`
shared by Android's streaming source and iOS's event-driven session) checkpoints the open path to a
platform `OutboxStore` from `:app-outbox` — the same durable seam `CapturedVisitOutbox` binds, with
its write-temp-then-rename atomicity and corrupt-entry purge. Bound to `filesDir/move_journal/` on
Android and Application Support on iOS. ⚠ **Its own directory, never the visit outbox's** —
`DurableImportWriter.drain` decodes every entry in that store as an `ImportedTimeline` and *purges*
whatever fails, which would silently eat the record.

Decisions:

- **Cadence: every 3rd fix, or `minMoveDurationMs`, whichever first.** Both arms are derived from
  existing thresholds rather than invented: 3 is `toCapturedMoveOrNull`'s recordability floor, so
  the journal is never more than one *unrecordable* fragment behind, and a kill costs a bounded
  tail (≤2 fixes) rather than the leg. Writing per fix would buy back only a fragment that could
  never have been recorded. The time arm exists for iOS, where significant-change fixes can be far
  enough apart that a count-only rule would leave the journal hours stale.
- **Resume-or-flush, keyed on a real fix timestamp** (`maxResumeGapMs`, default 15 min — the
  §11.4 watchdog's recovery interval, the realistic worst case for kill→restart). Inside it the
  path is prepended and the journey continues as one leg; beyond it the recovered path is flushed
  as its own leg, because stitching across a long outage would fabricate a straight-line jump that
  inflates `distanceM` and injects a synthetic high-speed leg into `classifyMode`. The verdict is
  taken on the first fix, not at construction — the segmenter deliberately has no clock (the
  `MigrationContext` posture: a `now()` fake is unrepresentable).
- **Raw fixes, never the decimated polyline.** `distanceM` and `classifyMode` run over the raw path
  (§4), so a resumed buffer must restore exactly what was captured.
- **Idempotence is free.** Moves are content-addressed by `startMs` (`cap-move:` / `cap-trace:`), so
  re-emitting a leg that *was* written before the kill folds onto the same rows. The flush sites
  additionally rewrite the journal in the same call, so an already-recorded leg is not re-opened and
  stitched onto the next journey.
- **`onEnd` clears the journal; a cancelled collector never reaches it.** That asymmetry is the
  whole recovery discriminator: a deliberate stop leaves nothing, a kill leaves the leg.

**No `TENSHU_STORAGE_FORMAT_VERSION` bump (House Rule #9 considered and does not fire).** Nothing
here is a stored row or a wire shape: the journal is device-local app-private JSON, no schema
composite changed, and `CapturedFix`'s new `@Serializable` is the same carve-out `ImportedTimeline`
already documents for the visit outbox (local outbox only, never the wire — HR#4 untouched). The
record carries its own tiny `version` instead, so a later §12.1 fix-model widening rejects an old
record rather than mis-reading it. No relay redeploy, no threat-model delta — the coordinates at
rest are exactly the visit outbox's A35 posture (app-private, platform-encrypted, never egressed).

⚠ **Residual window (documented, not closed):** a flush rewrites the journal to the new open path in
the same call that returns the event, so a kill in the microseconds between that return and
`CapturedMoveWriter`'s outbox persist still loses that one leg. It is the same window the dwell path
has always had, versus the hours-long one this closes.

Not addressed here, deliberately: the live in-progress ride in the feed (`OngoingMoveState`, the
movement twin of §11.2 — this slice is its precondition), collapsing short in-ride stops, and rail
snapping.

### 12.7 The in-progress ride — `OngoingMoveState` (as-built, 2026-08-01)

§12.6's deferred half, and the reason it was deferred: it needs the durable journal underneath it.

**The gap.** An in-flight journey was *structurally* invisible, three ways at once. There was no
`OngoingMoveState` (visits have had one since §11.2). `TimelineProjection.moment` computed
`isCurrent` as `ongoingArrivalMs != null && kind == VISIT`, so `isCurrent` was **unreachable** for a
movement. And a move only becomes a row at a flush — a dwell, the 2 h cap, or capture stop. So a
45-minute train ride showed nothing, and the *preceding* visit's live state had been cleared the
moment the user left.

**Shape — a live projection, never a row.** §9's emit-on-close decision (Option A) stands: movement
moments stay immutable, written once when the leg closes. The in-flight leg is therefore surfaced
as a **synthetic feed row** projected from device-local state, which is never emitted, synced or
reconciled. Decisions:

- **A distinct `CaptureEvent.MoveProgress`, not `Move(ongoing = true)`.** The dwell path can carry
  ongoing-ness on a boolean because both dwell emits are genuinely the same content-addressed row.
  A movement's is the opposite — it must *never* reach a writer — so a separate type makes writing
  it by forgetting a flag unrepresentable rather than merely discouraged.
- **Progress is gated on the ordinary recordability bar** (`toCapturedMoveOrNull`: ≥3 fixes, ≥3 min,
  ≥250 m), reusing the flush-time builder. The bar for *surfacing* a ride is the bar for *recording*
  one, so the feed can never advertise a leg capture would then discard as noise. It is monotone, so
  a surfaced ride cannot flicker.
- **Mutual exclusion by construction.** `OngoingMoveState.set` clears the visit state and
  `OngoingVisitState.set` clears the move state, so "both current" cannot be represented. The
  capture loops still clear explicitly where they mean *stop* rather than *switch*.
- **No duplicate, two independent ways.** Capture clears the live state *before* it writes; and the
  projector drops the synthetic row whenever a stored `MOVEMENT` moment shares its `startMs` (which
  content-addressing guarantees: `cap-move:$startMs`). The second is what makes the guarantee
  independent of write latency, of dispatch ordering, and of a peer's copy arriving first.
- **The live duration rides §11.2's ticker.** `liveVisitFlow` became `liveCaptureFlow`, folding both
  states and re-emitting every `LIVE_TICK_MS`; duration is recomputed from `now` at projection time.
  **No per-minute writes** — the same cost that was rejected for visits.
- **Staleness bound = `maxResumeGapMs`**, derived rather than restated. The state cannot expire
  itself, and capture dying while fixes stop leaves a well-formed `OngoingMove` that would tick for
  ever. Past the window the segmenter would flush the journalled path as a *finished* leg rather
  than resume it — so a ride we would not resume is a ride we must not still be showing.

**App-open recognition** (the maintainer's specific ask, and §12.6's payoff). `OngoingMoveState`
dies with the process; the `OpenMoveJournal` does not. `OngoingMoveState.restoreFrom(journal, now)`
republishes the leg a killed process left open — guarded on recordability, on the same freshness
window, and on never overwriting live state, and it deliberately **does not clear or rewrite the
journal** (a reader that consumed it would destroy the durability §12.6 provides). Narrow
platform entry points (`restoreOngoingMove`) live inside `:timeline-shared-data` because the journal
factories are `internal` — the durable record keeps one owner. Android calls it from
`TimelineApplication.onCreate` gated on the capture opt-in; iOS from
`TimelineCaptureRuntime.startIfEnabled()`, which already runs on every launch — and iOS needs it on
*every* wake, not just after a crash, since the monitors relaunch the app per event.

**Provisional mode — an honest unknown, not a confident guess.** An in-flight classification is
computed over however much of the path exists, so it changes as the journey goes on.
`OngoingMove.provisionalMode` therefore gates on `MIN_NAMEABLE_CONFIDENCE = 35`, derived from the
classifier's own arithmetic (`confidence = margin × sample × accuracy × 100`, `sample` saturating at
`CONFIDENT_LEG_COUNT = 20`) — roughly ten legs of unambiguous, well-fixed movement, about ten minutes
at Android's cadence. Below it the row still appears with its live distance and duration and renders
as the existing "Movement" fallback; **no new vocabulary was introduced** (nothing to localise at
n=1, and an unsettled mode reads exactly as every other unknown-mode leg). ⚠ The gate makes an early
verdict *silent*, not *right*: transit promotion needs stop structure a partial path rarely shows, so
a long rail journey may read "Driving" and settle to "Train" only at the flush.

**Interaction with §13.4's station-stop collapse (B2).** An ongoing *movement* can legitimately be
the trailing leg of a collapse — train → short stop → still riding is exactly the shape B2 exists
for — so `CollapsedRide` carries `isCurrent` and the collapsed row stays current. (Contrast guard 5,
which refuses to swallow an ongoing *stay*.) The live leg's trace id is filtered out of `traceIds`:
no `MovementTrace` row exists yet, so a hero pointed at it would draw nothing. The live leg is also
never absorbed by `mergeConsecutiveVisits` — normally impossible since nothing follows it, but a
peer's synced visit can sort after it, and being absorbed would take the whole signal with it.

**No format/wire/relay impact.** Nothing here is a stored row or a wire shape — `OngoingMoveState`
is process-local and never crosses the wire, and the synthetic row exists only in the presentation
model. `TENSHU_STORAGE_FORMAT_VERSION` untouched (House Rule #9 considered, does not fire), no relay
redeploy, no threat-model delta.

⚠ **Remaining:** the on-device pass (a real walk, killed mid-journey, app reopened) — the same
hardware-gated tail every capture slice carries. Desktop/web show no live ride at all, correctly:
the state is device-local and there is no capture there.

---

## 13. Transit classification — de-Googled, offline, tiered (2026-07-20)

**Question that opened this:** can we recognise a train ride ourselves, even partially — Germany
first, crude algorithm — with an activity-recognition API as fallback?

**Answer: yes, and better than the fallback framing implies.** §9's deferral said splitting
bus/train/car "needs ATA or rail map-matching." Two corrections:

### 13.1 The platform APIs do not actually provide the missing class

- **Google Play Services ActivityRecognition** would invert this app's posture outright. Timeline
  ships with `INTERNET` removed (`tools:node="remove"`, §11.3 P3) and capture is deliberately
  de-Googled (`LocationManager`, no fused provider), targeting GrapheneOS/e OS. Pulling in GMS for
  one classification is a large stance change for a small gain.
- **iOS `CMMotionActivity`** *is* clean — on-device, no network, no Google. But it reports
  automotive / cycling / walking / running / stationary. **It does not distinguish train from car
  either.**

So neither API supplies the bucket in question. Own-classification is not a workaround pending the
real solution; it **is** the solution. Platform APIs remain a *power* optimisation (batched low-power
wakeups) behind the §5 seam, not an accuracy one.

### 13.2 We already ship rail geometry, offline

The decisive finding. `deploy/tiles/regions.json` pins **Protomaps basemap v4**
(`tileSchemaVersion: 4`, enforced by `scripts/tiles-build.sh`), which has a dedicated **`transit`
layer** with fields `railway, highspeed, network, route, ref, service`, plus a `pois` layer carrying
its own `railway` field. Decoding the bundled demo archive
(`ui-map/src/androidMain/assets/tenshu_basemap.pmtiles`) confirms real features, not just schema:
`railway` values `rail`, `tram`, `platform`, `yard`, `siding`.

And `regions.json` now carries a **`global` region**, so this is planet coverage — not
a Germany-first special case. **Map-matching is therefore offline, private, network-free, and works
in a tunnel.** It fits the de-Googled posture rather than straining it.

⚠ **Two gaps, both real work.** Nothing in the repo decodes MVT: `PmtilesDirectory.kt` parses the
PMTiles header/directory to compute byte ranges and hands the payload to MapLibre untouched. And
`transit` is **not in the style** at all (`OfflineBasemap.android.kt:230` styles
`earth/landcover/landuse/water/buildings/roads/boundaries`), so MapLibre's own query APIs cannot see
it either — rail is not even *rendered* today. The seam is clean, though: `RegionStreamSource.readRange`
already fetches exactly the right bytes.

### 13.2a VERIFIED against real tiles (M9, 2026-07-20) — and T2 as designed is dead

The "verify before designing T2 on it" warning above was acted on: every one of the 24 tiles in the
shipped demo archive was decoded and surveyed. Two results, one better than hoped and one fatal.

**`transit` is richer than the schema list suggested — T1 is comfortably viable.** Across the
archive: **1305 `railway=rail`** and **196 `railway=tram`** LineStrings, plus `highspeed=yes` (99),
`service=yard`/`siding`/`crossover` (388 — ⚠ track a train does not *run* on, so a matcher must
exclude it), `pmap:level`/`layer` for tunnels and bridges, an `aeroway` key sharing the layer
(runways — also to exclude), and named lines (`Bologna-Firenze (direttissima)`, `Ferrovia
Faentina`, `Linea T3`). Enough to match against, and enough to tell high-speed rail from a tram.

⚠⚠ **There are no railway-station POIs. Anywhere.** The `pois` layer's key set across all 24 tiles
is `amenity, cuisine, historic, iata, landuse, leisure, name, name:*, pmap:kind, pmap:kind_detail,
pmap:min_zoom, pmap:pgf:name, religion, shop, sport, tourism` — **no `railway` key at all**, and the
only "station" values are `bus_station`, `fire_station`, `fitness_station`. This holds even though
the extract covers **Firenze Santa Maria Novella**, a major terminus. The `railway` field *is*
declared in the archive's schema-wide metadata, which is what the earlier survey saw — but Planetiler
emits no such feature. (`landuse` does carry `landuse=railway` polygons — railway *land*, i.e. yards
and corridors, not stations.)

**Consequence: T2 as specified is not implementable on this basemap**, and no amount of a bigger
archive changes it — this is a schema-level absence, not a sampling artefact.

> ⚠⚠ **FALSIFIED 2026-08-05 by §24.** That last sentence was wrong, and wrong in the confident
> direction. A survey of the **production** `de-bayern` bundle found named `kind=station` POIs
> (*München Hauptbahnhof*, *Marienplatz*) plus `platform`/`tram_stop`/`railway` at **z14** — the same
> zoom band the demo archive covers. It **was** an artefact of the archive surveyed, not a property of
> the schema. `MvtRealArchiveTest` remains true *of the demo archive* and should be read as pinning
> that archive's contents, not a general claim. **T2 is revived** — see §24.

**The replacement is better anyway: T2 collapses into T1.** What T2 wanted was a *station* signal;
what it actually needs is "the trace stopped somewhere it makes sense for a train to stop", and once
T1 can match a trace to rail geometry, **a full stop while matched to rail is exactly that** — no POI
required. It also stops depending on POI coverage quality, which varies enormously by region. The
tier table below is updated accordingly.

`MvtRealArchiveTest` (`:ui-map` desktopTest) is the executable record of this survey, so the absent
POI cannot quietly return to the design: it asserts the absence and tells the reader to revisit this
section if it ever fails.

### 13.3 The tiers

Each ships independently and raises confidence; the classifier records which tier produced the
verdict (§12's `classifierVersion`), so later tiers can re-label older traces **only if** the
fidelity pass landed first.

| Tier | Signal | Cost | Buys |
|---|---|---|---|
| **T0 — kinematic** | Heading-variance per unit distance (rail curves have huge radii; road driving turns discretely at junctions), sustained-cruise fraction, stop cadence + regularity, speed ceiling | Pure `commonMain`, no deps, unit-testable on synthetic traces | A confidence-scored `transit` hint. Cheap, immediate, works everywhere. |
| **T1 — rail map-match** | Match the decoded polyline against `transit`-layer `railway=rail` geometry | **The real investment: an MVT decoder.** Reusable far beyond this (POIs, Maps Ph-P4, offline search) | The decisive signal. On-rail ⇒ rail, near-conclusively. |
| ~~**T2 — station endpoints**~~ | ~~Move endpoints near `pois` `railway=station`~~ | — | **DEAD (§13.2a): the basemap has no station POIs.** Superseded by T2′ below. |
| **T2′ — stop-on-rail** | A full stop (T0 already detects these) at a point T1 matched to rail | Free once T1 exists — no new data source | The station signal T2 wanted, without depending on POI coverage. Disambiguates tram/subway; catches what T0's spacing heuristic guesses at. |
| **T3 — imported `activityType`** | Google's own `IN_TRAIN`/`IN_SUBWAY`/`IN_BUS` | Trivial once §12.2 retains the raw value | Ground truth on imported history — **and a labelled corpus to tune T0/T1 against.** |

**T3 is quietly the most valuable early.** It is nearly free, and it converts imported history into
the tuning corpus §9 flags as "still open — needs data, not a decision."

### 13.4 Failure modes to design against

- **Station stops split the ride.** A long stop at a major station exceeds `minDwellMs` and the dwell
  detector splits one journey into separate legs. Mitigation is *not* to suppress the dwell (it is a
  real stay) but to let T2 mark both legs `transit` and leave journey-level grouping to the existing
  I5 `TripMerger`, which already composes moments into chapters.
- **Tunnels and subways starve fixes entirely**, distorting the median and dropping legs below the
  §12.1 thresholds. T0 must weight by *delivered* fix density, and this is why `accuracyM` and
  `provider` must survive into the trace.
- **Autobahn at 180 km/h overlaps regional-rail speed.** Speed alone is never sufficient — hence
  heading-variance as T0's primary discriminator, not the speed ceiling.

### 13.5 Model impact

`MovementMode` gains `transit` (and possibly `train`/`tram`/`bus` if T2/T3 justify the split).
⚠ `MovementMode` values are stored as **plain strings** in `MovementTrace.mode`, so adding values is
*not* a positional-composite change — same forward-compat posture as `MomentKind.MOVEMENT` (§2). Old
peers must fall back gracefully on an unknown mode. Confirm against HR #9 at implementation time:
additive string values have historically ridden the then-current format version.

---

## 14. Revised phasing (2026-07-20)

M1–M3 are shipped; M5 shipped 2026-07-24 (§7 item 5 as-built); M4 stands. The sections above insert
a fidelity pass **ahead** of everything else, on the §12.0 irreversibility argument.

1. **M6 — fidelity pass (no device). ← next.** §12.1 capture fields (`classifierVersion`,
   `modeConfidence`, median speed, start/end coords, `rawPointCount`), `accuracyM` back into the
   codec, arc-length `capPoints`, `sources` end-to-end (§12.3), and the §12.5 `parseAll` fix.
   **One `TENSHU_STORAGE_FORMAT_VERSION` bump covers the lot** (HR #9 — `MovementTrace` reshapes,
   and positional composites make even additive field changes peer-breaking). Pre-release, so a
   wipe+resync is the accepted cost.
   *Rationale for going first: every later slice writes rows, and rows written without version,
   confidence, or direction are permanently un-improvable.*
2. **M7 — Google activity import (no device).** `timelinePath` / `simplifiedRawPath` → real
   `MovementTrace`s; raw `activityType` retained (T3); `g-trace:<startMs>` id namespace so imported
   and captured traces of the same journey stay distinct rows until `MomentReconciler` folds them.
   Lands on a correct `parseAll` and a live `sources` field.

   **As-built (M7 — SHIPPED, device-free, 2026-07-20):** an activity segment now produces the *same
   moment + trace pair live capture writes* (`movementRows`), so imported and captured movement are
   one shape all the way down and the projector, reconciler and future route UI need no
   import-specific branch. Path preference is **timestamped samples → untimed waypoints → bare
   endpoints**: new-format `timelinePath` (ISO `time` *or* `durationMinutesOffsetFromStartTime`),
   legacy `simplifiedRawPath` (E7 + timestamp + accuracy — maps onto `CapturedFix` exactly), legacy
   `waypointPath` (⚠ **no timestamps at all** → synthesised evenly across the span; a *shape* claim,
   not a speed claim), else a two-point `startLocation`/`endLocation` path purely so **direction of
   travel** survives. An activity with no geometry at all still becomes a movement moment.

   `MovementTrace` gained **`sourceActivityType`** (format v7 → **v8**): Google's raw label kept
   verbatim, because it draws the transit-vs-car distinction our own classifier cannot (§13.1), and
   because it doubles as the labelled tuning corpus §9 wants. `mode` maps to the coarse bucket
   (bus/tram/subway/rail/ferry → the new `MovementMode.TRANSIT`); flying deliberately stays `UNKNOWN`
   rather than inventing a bucket capture can never produce. `classifierVersion` is stamped
   `MOVEMENT_CLASSIFIER_EXTERNAL` (0) so a future re-classification pass can distinguish "our label,
   possibly stale" from "the source's label, probably better than ours"; `medianSpeedMmPerS` is left
   0 (Google gives a label, not a speed distribution — and for waypoint paths any derived speed would
   be an interpolation artefact). Confidence maps from legacy `HIGH`/`MEDIUM`/`LOW` or the new 0..1
   `probability`. All Google moments (visits included) now carry `sources = [google]`.

   **Two latent bugs found by the new tests:** `"motorcycling"` *contains* `"cycl"`, so `MOTORCYCLING`
   classified as `CYCLE` — and the pre-existing `humanActivity` had the same ordering flaw, rendering
   it as the title **"Cycling"** since I2. Both branches now test `motorcycl` first; `IN_TRAM` also
   gained a real label instead of falling through to title-casing.

   ⚠ **Known gap — movement moments never reconcile.** `MomentReconciler.clusterKey` keys visits on
   place cell + time bucket and activities on their workout subject; **everything else falls to
   `"m:<momentId>"`, which can only ever match itself.** So a journey that was both captured *and*
   imported lists twice. Deliberately not fixed here: a movement cluster key is a real design
   question (what makes two movements "the same" — start/end cells + time bucket? overlap fraction?),
   and the practical overlap is narrow in the same way §9's health-workout case is — importing
   *history* predates capture, so it does not collide. Its own slice; the `sources` field it needs is
   now populated, which was the blocker.

   ⚠ **Still not imported, deliberately:** `transitPath` (line name, stop list) is Google *content*
   per the parser's ToS line — blocked even though it is exactly what a transit feature wants (D-F3);
   `childVisits` are skipped rather than flattened (they would land as look-alike duplicates the
   reconciler cannot distinguish, and need a `parentMomentId` that does not exist); `parkingEvent`,
   `otherCandidateActivities` and the visit-confidence fields have no home.

   Green: `:timeline-shared-data:desktopTest` **114** (+6), `:timeline-service-common`,
   `:sync-service-proxy`, `:timeline-shared-logic`, `:core-common`, `:timeline-desktop`,
   `:timeline-android:assembleDebug`, iOS `iosSimulatorArm64`, `scripts/android-smoke.sh`.
3. **M5-lite — the movement card (UI).** `MomentKind.Movement` in `TimelineModel.kt:35` +
   a `Kind.MOVEMENT` arm in `TimelineProjection.kt:331` (today a movement falls through
   `else -> Event` and renders with a **music-note icon** and a `" · 32 min"` orphan-separator
   subtitle). Mode icon, distance, duration. **Makes M1–M3 and M6–M7 visible for the first time** —
   until this lands, the user's original report stands regardless of what is recorded.

   **As-built (M5-lite — SHIPPED, device-free, 2026-07-20).** The read model learns a kind the write
   model has emitted since M2. **No schema, wire or format change** — this is purely projection + UI,
   so `TENSHU_STORAGE_FORMAT_VERSION` is untouched.

   - **Model:** `MomentKind.Movement` + `Moment.movementMode` / `Moment.distanceM` (nullable, only
     populated for that kind). `MovementModes` mirrors the `MovementTrace.mode` strings. ⚠ `mode` is
     carried as a **raw String, not an enum** — the wire value is a plain string, so a newer peer can
     send a mode this build has never heard of and the UI must fall back rather than fail.
   - **Projection:** new `TraceInput` (id / mode / distanceM — the card's three fields, *not* the
     polyline) + a `traceResolver` that mirrors `subtitleResolver`'s dangling-reference contract
     (unresolvable subject → `null`, never a failure). `days()` gained a `traces` param.
   - **Repository:** a fifth `tracesFlow()` over the already-declared `TimelineService.traces`
     relation, folded into `homeDays()`'s `combine` (now the 5-arg overload) and into the
     single-moment lookup — without the latter, opening a movement's detail lost its mode. ⚠
     `TRACE_FIELDS` deliberately **omits `polyline`**: decoding a path per feed row is exactly what
     §2.1's summary-vs-blob split exists to avoid.
   - **UI:** per-mode glyphs (`forMovement`: walk / run / bike / car / transit); the subtitle slot
     takes the **localised mode label** since a movement has no place subject — which also kills the
     orphan `" · "` the old fallthrough produced; the meta line leads with distance instead of a
     photo count (a walk has no photos, so the generic head rendered the actively-unhelpful "No
     photos"); and a new `MovementBody` on the detail screen replaces the Event anatomy, which had
     offered *"Add a note about this event…"* and a place-context card a movement has no place for.
   - **Localisation (ADR 0016):** all new strings via `stringResource`. Distance follows the
     `DurationForm` precedent — `TimelineFormat.distanceParts` emits **whole + tenths as separate
     integers**, never a pre-formatted `"2.4"`, so the decimal separator lives in the template where
     a translator can change it (`%1$d.%2$d km` → `%1$d,%2$d km`). ⚠ `MomentDetailScreen`'s
     *existing* bodies remain unlocalised hard literals; the new body does not add to that debt.

   **Deferred deliberately:** `Day.movement` (the day-level distance/step rollup) stays `null`. It is
   cheap to compute from the day's traces, but it has no render slot, and its `steps` field has no
   source at all — a day-summary strip is a design decision, not a side effect of this slice.

   **⚠ Visual verification pending.** Every layer is test-covered and compiles, but no one has *seen*
   the card — the sandbox cannot screenshot a desktop window. A maintainer click-through of
   `:timeline-desktop:run` (and the Android app) is the outstanding step.

   Green: `:timeline-shared-data:desktopTest` **118**, `:timeline-shared-logic:desktopTest` **29**,
   `:timeline-shared-ui`, `:timeline-desktop`, `:timeline-android:assembleDebug`,
   `:timeline-service-common`, `:sync-service-proxy`, iOS `iosSimulatorArm64` (logic + data;
   `:timeline-shared-ui` has no iOS target), `scripts/android-smoke.sh` PASSED.
4. **M8 — T0 kinematic transit.** Ships a confidence-scored `transit` mode behind §13.3's tier seam.

   **As-built (M8 — SHIPPED, device-free, 2026-07-20).** `TransitKinematics` in the `capture` package;
   `classifyMode` offers its `drive` verdict to `refineWithTransitKinematics`. **No schema, wire or
   format change** — `MovementTrace.mode` is a plain string and `TRANSIT` already exists (M7), so
   only `MOVEMENT_CLASSIFIER_VERSION` moves, 1 → **2**. That bump is precisely what M6 built the field
   for: a v1 row's walk/run/cycle label is still correct under v2, so a future re-classification sweep
   only has to revisit `drive` rows.

   **Design posture — promotion only, bias to the status quo.** A false "Train" on someone's daily
   commute is a confident, visible lie about their life; a missed train just leaves the existing
   "Drive". So the tier *only* promotes an established `drive`, never demotes anything, and every
   ambiguous case returns the baseline untouched.

   **Two signals, neither sufficient alone:**
   - **Turn density** (degrees of heading change per km, over legs ≥ 30 m so bearings aren't noise).
     Rail curve radii are enormous; road networks turn discretely at junctions. ⚠ Separates rail from
     *urban* driving but **not from motorway** driving, which is also gently curving.
   - **Stop structure** — full stops, ≥ `MIN_STATION_SPACING_M` apart, with a fast cruise between.

   Plus a shortcut: sustained cruise > 55 m/s (~198 km/h) is not achievable by road, bounded above at
   120 m/s so a flight isn't claimed as ground transit.

   ⚠ **A synthetic-fixture probe during development found the rule as first written would promote a
   rural drive.** A straight country road with stop-sign junctions ~1 km apart passes turn density
   *and* has widely-spaced full stops. Two changes closed it: `MIN_STATION_SPACING_M` raised 800 m →
   **1500 m**, and a new `SHAPE_RULE_MIN_CRUISE_MPS` (**20 m/s**) conjunct — a train runs fast between
   stations, a car pausing at junctions does not. All three conjuncts are load-bearing; dropping any
   one readmits a road shape. The same probe caught a **vacuous test**: the stop-and-go traffic
   fixture classified as `walk` on median speed, so it never reached the transit gate and would have
   passed against any implementation.

   **What T0 structurally cannot do** (documented, not worked around):
   - **Buses read as `drive`** — road-following (high turn density) and stops every few hundred
     metres. Neither signal fires; §13.3's T2 stop-POI tier is what would catch it.
   - **Slow urban rail** (trams, some subway lines) cruises below the 20 m/s conjunct and stays
     `drive`. The deliberate conservative side of the trade; T1/T2 recover it.
   - **Tunnels starve fixes**, so the geometry is missing — the fix-quality weight means a subway
     ride usually *fails* to be promoted rather than being wrongly promoted.
   - **A station stop long enough to be a dwell splits the journey upstream** in the segmenter, so a
     long ride can arrive already cut into legs with fewer stops than the whole trip had (§13.4).
     Journey-level grouping stays the existing I5 `TripMerger`'s job.

   ⚠ **Thresholds are reasoned, not fitted.** No real captured trace has been through this — the
   fixtures are synthetic paths built from the documented geometry. §9's tuning-corpus gap is still
   open, and M7's imported `sourceActivityType` is the labelled data that would close it: a
   calibration pass over a real Takeout import is the obvious next validation, and would likely move
   these constants.

   Green: `:timeline-shared-data:desktopTest` **129** (+14 in `TransitKinematicsTest`),
   `:timeline-shared-logic`, `:timeline-service-common`, `:sync-service-proxy`, `:timeline-desktop`,
   `:timeline-android:assembleDebug`, iOS `iosSimulatorArm64`.
5. **M9 — MVT decoder + T1/T2 map-matching.** The substantial one; reusable well beyond Timeline.
   ⚠ §13.2's station-POI assumption is now **verified false** (§13.2a) — T2 is dead and replaced by
   T2′ (stop-on-rail), which needs no new data source.

   **As-built (M9-1 — the decoder, SHIPPED device-free 2026-07-20).** New **`:vector-tile`** module:
   `MvtDecoder` + `MvtLayer`/`MvtFeature`/`MvtValue` + a bounds-checked `ProtoReader`. Pure KMP,
   **no dependencies** — deliberately not inside `:ui-map`, because the first consumer is Timeline's
   map-matching and it has no business pulling a Compose UI module (and MapLibre) onto its classpath.
   No protobuf runtime: MVT is a small fixed schema and the repo already hand-rolls varints twice.

   Takes **already-decompressed** bytes, mirroring `PmtilesDirectory.decodeDirectory`. That is not
   laziness — PMTiles stores tiles gzipped and `commonMain` has **no decompressor** (`java.util.zip`
   is JVM/Android-only). A shared gunzip seam is its own decision (expect/actual vs. a dependency)
   and this slice declines to make it silently.

   `PmtilesDirectory` gained the two things a tile *reader* needs and a range *planner* never did:
   **`tileCompression` (byte 98) and `tileType` (byte 99), previously unparsed** — byte 98 is the
   payload compression, distinct from byte 97's directory compression, so a would-be reader saw gzip
   and no reason why — and a public `tileRange(...)` single-tile lookup (the resolver was private).

   Verified two ways, deliberately: `:vector-tile`'s **synthetic** suite pins the spec (geometry
   cursor persistence across commands — the classic mis-implementation; multi-ring `MoveTo`;
   `ClosePath`; zig-zag negatives; every value type; unknown-field skipping; truncation at every
   byte offset; a varint shift-overflow probe), and `:ui-map`'s **`MvtRealArchiveTest`** decodes the
   actual shipped archive end-to-end (header → root dir → tile range → gunzip → decode) and asserts
   real Firenze geography, including that projected rail geometry lands in the right city — the check
   a self-consistent synthetic suite structurally cannot make.

   **As-built (M9-2 — the matcher, SHIPPED device-free 2026-07-20).** `RailMatcher` + `GeoMath` in
   `:vector-tile`. Pure, **no I/O**: `railLinesFrom(layer)` applies the §13.2a tag rules
   (keep `railway` ∈ rail/tram/light_rail/subway/narrow_gauge; drop anything with `service=*` —
   yard/siding/crossover — or `aeroway`), `match(path, lines)` returns a **coverage fraction** rather
   than a boolean (§12.1's store-the-inputs rule: a later tuning pass can move the threshold without
   re-running the matching), and `stopsOnRail` implements **T2′**.

   ⚠⚠ **The tram hazard — found by measurement, and it reshaped the API.** Matching real Firenze road
   geometry against real Firenze rail: **10 of 240 road traces matched track completely**, because
   `Linea T1/T2/T3` run *down the middle of streets*. A car on a tram street genuinely is on tram
   geometry, so a single coverage number would have promoted ordinary drives to "Train". The fix is
   structural, not a threshold tweak: **the verdict is split**. `exclusiveCoverage` counts only track
   a road vehicle physically cannot occupy (heavy rail, subway, narrow gauge) and is the figure a
   promotion may rest on; `coverage` includes street-running track and needs corroboration (T0's
   shape signals, or T2′ stops on the same track). Re-measured on the same 240 traces:
   **2 matches, 0.83%** — a 5× reduction. The residual two are real (roads do run inside rail
   corridors), which is why T1 is a strong signal rather than a proof.

   Also load-bearing: when both are in range the per-point scan **prefers exclusive track**, so a
   tram beside heavy rail cannot mask the stronger signal by being scanned first.

   Verified both ways again: `:vector-tile`'s synthetic suite pins the contract (tag rules, tolerance
   boundary, partial coverage, the tram/heavy split, prefilter correctness, T2′, degenerate input),
   and `:ui-map`'s **`RailMatchRealArchiveTest`** runs it over real geometry — a jittered trace along
   real heavy rail matches exclusively; **real road geometry from the same tile** is the negative
   case, which is the adversarial shape a synthetic test would never produce.

   **As-built (M9-3 — the refinement rule, SHIPPED device-free 2026-07-20).** `RailRefinement` in
   `:timeline-shared-data`: the rule that turns T1/T2′ evidence into a re-stamped classification.
   `MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED = 4`, distinct from the capture-time version, so the
   sweep's candidate query is just "version below 4, not external, mode drive-or-transit"
   (`needsRefinement`). **No format change** — the field already exists; only its value moves.

   **T1 is the one tier permitted to walk a verdict back.** M8's promotion-only posture holds for T0
   because it *infers* from shape; T1 is **direct evidence**, and a path nowhere near track is not a
   train whatever its shape suggested. So a T0 `transit` can be demoted to `drive`.

   ⚠⚠ **That is only safe because "no track" and "could not look" are different values.** An
   undownloaded region, a failed fetch, or partial coverage must never read as absence of rail —
   demoting on missing data would silently relabel every train taken outside downloaded coverage.
   Hence `RailEvidence.Unavailable` vs `Surveyed(emptyList())`: the first returns the trace untouched
   *and unstamped*, so a later sweep retries; only the second is evidence. This is the single most
   load-bearing decision in the slice and the tests lead with it.

   The rule, in order: exclusive (car-impossible) coverage ≥ 0.8 → transit; street-running coverage
   ≥ 0.9 **plus corroboration** (T0 already said transit, or ≥2 stops on the track — a car does not
   repeatedly stop *on* tram rails) → transit; surveyed and not on track → demote a T0 transit, or
   confirm-and-stamp a drive. Externally-labelled traces (`MOVEMENT_CLASSIFIER_EXTERNAL`, i.e. M7's
   Google `activityType`) are **never** overwritten — that label distinguishes bus from tram from
   rail, finer than any tier of ours. Walks, runs, rides and flights are out of scope entirely.

   **As-built (M9-4 — the proxy-side rail-lines op, SHIPPED device-free 2026-07-20).** ⚖️ The decode
   decision was settled **proxy-side**, and the op is `MapRegionService.railLinesIn(bbox)` →
   `TList<RailLineGeometry>`.

   - **`:vector-tile` gained `RailTileReader`** — the whole tile pipeline in one pure function: cover
     the bbox, resolve byte ranges, decompress, decode MVT, filter to running track, project. Written
     against injected `readRange`/`decompress` lambdas, so the proxy and a test run identical code.
     `PmtilesDirectory` **moved here from `:ui-map`** (a Compose module the proxy must not depend on);
     `LonLatPolyline` is the wire codec.
   - **`TileDecompressor` is a seam, not an `expect/actual`.** JS's `DecompressionStream` is
     *asynchronous* and iOS needs a zlib cinterop, so three of four actuals would be awkward for a
     feature two platforms use. Each `PlatformProxyModule` hands in what it has: Android and desktop
     pass `JvmTileDecompressor` (`java.util.zip`), iOS passes `null`. ⚠ It is deliberately **not** a
     `MapRegionProxyComponents` field — those are assembled in `commonMain` DI, which by definition
     cannot supply a JVM-only implementation, so the field would be permanently null.
   - **Only the bounding box crosses the boundary — never the trace.** Sending the route to Control
     for matching there would have been fewer bytes, but this keeps the most sensitive artefact
     inside one process. The proxy learns a bbox; the app matches locally with M9-2's `RailMatcher`.
   - Region resolution is smallest-covering-area from the **catalogue**, not the installed set —
     `StreamingRegionSource` range-fetches an undownloaded region, and requiring a download would
     refuse to answer in exactly the traveller case that matters.

   ⚠ **A failure or empty result from this op is `RailEvidence.Unavailable`, never "no track".** The
   op answers empty for: no covering region, no platform gzip (iOS today), or a corrupt bundle — none
   of which are evidence. M9-3's guard is what makes that safe, and the tests pin all three paths.

   Verified: three op-pipe tests through the real client→proxy chain (no covering region, no
   decompressor, garbage bundle), plus `RailTileReaderRealArchiveTest` running the **entire pipeline
   against the real shipped archive** — bbox in, real Firenze rail out, including the wire codec
   round-trip and the tile budget.

   **As-built (M9-5 — the sweep, SHIPPED device-free 2026-07-20).** `RailRefinementSweep` in
   `:timeline-shared-data` closes the loop: read traces where `needsRefinement`, decode each path,
   ask `railLinesIn` for track near its bbox, apply `RailRefinement.refine`, re-record.

   **Idempotent and interruptible by construction.** Every write is a content-addressed upsert, the
   candidate query derives from the stored version, and there is no cursor — killing a run mid-sweep
   costs only the work in flight. A second sweep examines nothing.

   ⚠ **The moment's title had to move with the mode.** A `MOVEMENT` moment stores `"Drive · 34.2 km"`
   as its neutral fallback title, and the card renders that title *next to* the mode label — so
   refining the trace to `transit` without touching the moment would put a visible contradiction on
   screen. The sweep regenerates the title through `MovementMapper.moveTitle`, the same source the
   capture mapper used, and writes the moment only when the mode actually changed.

   ⚠ **An empty survey is treated as `Unavailable`, not as evidence.** `railLinesIn` answers empty
   for three non-evidence reasons (no covering region, no platform gzip, a corrupt bundle) which are
   indistinguishable on the wire from a genuine "no track". The sweep therefore never demotes on an
   empty answer; the trace stays un-stamped and a later run — once the region is downloaded — gets
   its chance. This is M9-3's guard reaching the one place it actually matters.

   ⚠ **Stops are approximate here.** T2′ derives them from the *stored* polyline, which is decimated,
   so a brief station stop may have been simplified away. The error direction is safe — a missed stop
   withholds corroboration rather than inventing it — but stop-on-rail is weaker over stored traces
   than it would be over raw fixes.

   Found while writing it: the stored trace polyline is **`MovementTracePolyline`**-encoded
   (lat/lng/time/accuracy) while `LonLatPolyline` is the *wire* codec for rail lines coming back from
   the proxy. Decoding one with the other yields plausible-looking garbage rather than an error, and
   the first draft did exactly that.

   **As-built (M9-6 — the launch trigger, SHIPPED device-free 2026-07-21).** ⚖️ **bgproc Phase 6 is
   *not* needed.** Its periodic path is proxy-side (a param-less `HeadlessSafe` op in the sync-proxy
   graph, dispatched by `ScheduleDrainLoop`/WorkManager) — the wrong shape for a `TenshuClient`
   enrichment that reads timeline traces from the *app* graph, and riding it would mean re-authoring
   the sweep as a proxy op. The codebase already made this exact call for the geocoding backfill
   (`place-geocoding.md` §6: "belongs to bgproc Phase 6 once it lands; don't hand-roll one here" — and
   then it didn't wait, running `GeocodingBackfillTrigger` at app start instead). Phase 6 is also
   already largely landed for its *real* drivers (offline-keeper, preference enforcement, sync-drain),
   so the sweep was never its blocker.

   `RailRefinementTrigger` mirrors that precedent: `TimelineApplication.onCreate` launches one
   `sweep()` pass, gated on `TimelineCaptureControl.enabled`. **The capture gate is not arbitrary** —
   `needsRefinement` skips externally-labelled traces (Google's imported `activityType`), so captured
   movement is the *only* source of refinable traces; a user who only imported has nothing to refine,
   and a user who captures is exactly the population with candidates. One pass per launch suffices
   because the sweep is bounded (`DEFAULT_LIMIT`) and idempotent (version-stamped upserts): it
   converges over history across sessions, and unavailable evidence (no downloaded region, offline)
   just defers a trace to the next launch. Egress note: the sweep sends Control a bbox to fetch
   *public* map tiles — Control already stores the trace, tiles carry no user data (HR #1) — so unlike
   geocoding this is a battery/data cost, not a new third-party disclosure.

   Green: `:timeline-shared-data:desktopTest` (+2 gate tests), `:timeline-android:compileDebugKotlin`,
   `scripts/android-smoke.sh`.

   **Outstanding: on-device confirmation.** Everything compiles, smoke-passes, and is unit-covered,
   but the actual "my commute now reads *Train*" needs a real device with a downloaded region and a
   captured train trace — the hardware pass this track has owed since M3.

   *(Superseded plan — the decision the above resolved:)*

   1. ⚖️ **Where tiles get decompressed.** `commonMain` has no gunzip and PMTiles payloads are
      gzipped (M9-1). Rather than an `expect/actual` (awkward: JS `DecompressionStream` is *async*,
      iOS needs zlib cinterop), the recommendation is to **decode proxy-side**: Control is JVM/Android
      where `java.util.zip` exists, already owns tile egress, and already serves Timeline ops
      (`ReverseGeocode` is the precedent — the app stays `INTERNET`-free). A `:map-region-service` op
      returning *rail lines near a bbox* keeps the vector-tile dependency on the proxy and sends the
      app only the geometry it needs.
   2. **The sweep** — query traces where `needsRefinement`, fetch evidence per trace, apply
      `RailRefinement.refine`, re-record. Idempotent by construction (content-addressed `traceId`,
      and the version stamp stops re-work).
   3. **Its trigger** — bgproc Phase 6 is the natural home; until then a manual/settings action is
      enough to exercise it.
6. **M4 — iOS capture** shipped 2026-07-24 (§7 item 4) and **M5 — full route on `:ui-map`**
   shipped 2026-07-24 (§7 item 5) — the revised phasing is complete.

---

## 10. Version

Timeline-movement-capture v0.7 · §18 (one stay → three rows: the dwell anchor radius is not
accuracy-aware, the phantom walk it manufactures is *also* the merge veto, and the two silent returns
that make Wi-Fi recognition look dead — slices S6/S7) added 2026-08-04. Earlier:
v0.6 · §17 (fidelity, the leg boundary and battery — the multi-modal-journey
review: adaptive+batched sensing, mode-change leg splitting, step cadence, the dark-leg subway
detector, and the FGS verdict) added 2026-08-04 as **design, no code**; it supersedes §5's implicit
"speed median is enough" and names §17.1's four causes for why §13/§15/§16 do not fire on a
multi-modal journey. Earlier: v0.5 · design 2026-07-19, fidelity + transit 2026-07-20, M7 as-built
2026-07-20 (§14.2: Google activity segments → real traces; `sourceActivityType`; `MovementMode.TRANSIT`;
⚠ movement moments do not yet reconcile), M5-lite as-built 2026-07-20 (§14.3: the movement card —
projection + UI only, no format change; ⚠ visual verification pending), M8 as-built 2026-07-20
(§14.4: T0 kinematic transit — promotion-only, classifier version 1→2; ⚠ thresholds reasoned, not
fitted to real traces), M9-1/M9-2 as-built 2026-07-20 (§14.5: the `:vector-tile` MVT decoder +
§13.2a's real-tile survey, which killed T2-as-designed and replaced it with T2′; then `RailMatcher`,
whose split verdict came out of measuring real roads against real trams).
§12 (fidelity — capture-vs-import irreversibility, the field gaps, inert `sources`, the `parseAll`
bug), §13 (de-Googled tiered transit classification over the shipped Protomaps `transit` layer) and
§14 (revised phasing, M6 first) added 2026-07-20; §9's "single `drive` bucket" reasoning superseded
by §13.1. Earlier: §11 on-device capture findings + fixes
(minDistance/provider/seed/emit-on-confirm + option-B live duration) 2026-07-19. Companion to
[`timeline-ingestion.md`](timeline-ingestion.md) (visit capture + import; §0 clarifies this is
**not** its I5) and [`timeline-services.md`](timeline-services.md) (Moment/Place spine). Reuses
the `CapturedVisitWriter` durability posture, the `-common` pure-algorithm layering (ingestion
§11), and the A35 location threat-model asset. No code.

### 14.6 Flight — the cheap win the airborne band was already measuring (M10, 2026-07-20)

**Trigger:** reviewing M9-2's `aeroway` exclusion raised "could we detect flights too?" The tempting
reading — *map-match against `aeroway=runway`* — is **wrong**: a flight follows a great circle at
altitude, not the runway, so runway geometry is touched only during taxi and the takeoff roll. The
`aeroway` exclusion in `RailMatcher` stays correct (an airport perimeter road is not rail) and is
simply not the flight signal.

The real signal was already measured and thrown away. `TransitKinematics` computes cruise speed and
bounded its transit claim at `IMPLAUSIBLE_GROUND_SPEED_MPS` (120 m/s ≈ 432 km/h), letting anything
faster fall back to the `drive` baseline. **An airliner cruises ~230–250 m/s and nothing on the
ground comes close**, so that band is conclusive on its own — no shape analysis, no new data source.

**As-built:** `MovementMode.FLIGHT`; the refinement checks the airborne band *first* (the bands are
disjoint, so a flight is not a rail verdict to be argued about); a new
`IMPLAUSIBLE_FLIGHT_SPEED_MPS` (400 m/s) ceiling because cruise is an 85th-percentile figure that a
couple of GPS teleports can drag arbitrarily high — "Flight" on a garbled walk would be worse than
the baseline it replaces. `MOVEMENT_CLASSIFIER_VERSION` 2 → **3** (the rule changed; a v2 row's
ground verdict still stands, so a sweep need only revisit fast traces). Google's `FLYING` import
stops flattening to `unknown` (M7 kept the raw label, so imported history gains the bucket for free),
and the card gets a plane glyph + localised label. **No format change** — `mode` is a plain string.

⚠ **In practice most flights leave no trace at all** — airplane mode, or a phone in a pocket away
from a window — so this fires only for the window-seat case, and the flight leg may not even clear
`toCapturedMoveOrNull`'s ≥3-fix threshold. The general signal is a **pair of airport dwells with a
gap no ground transport could cover**, which is a different acquisition mode (it works on *dwells*,
not movement) and is **not built**. It is newly viable, though: unlike railway stations, airports
*are* in the basemap — the real-tile survey found `pois` `pmap:kind=aerodrome` with `iata=FLR` and a
`landuse` `aeroway=aerodrome` **polygon**, which gives a proper containment test rather than fragile
proximity to a single point. Airports are large; the polygon matters. Would also want altitude, which
`CapturedFix` still does not carry (§12.1).

---

## 15. Collapsing short in-ride station stops (as-built, 2026-08-01)

**The report.** One train ride reads as *ride → a "stay" at a station the user merely passed through
→ ride*. `VisitDetectionConfig.minDwellMs` is **5 minutes** and a station stop is routinely exactly
that, so capture writes a `Visit` **and** splits the journey into two `MovementTrace`s that nothing
ever rejoins. It also degrades classification: `TransitKinematics`' stop-structure rule counts
well-spaced stops *within* one trace, so splitting a ride starves each half of the very signal that
identifies it as transit — the interaction §13.4 and the `TransitKinematics` KDoc already name as a
known limit.

### 15.1 Shape — read-time, in the projector

`TimelineProjection.collapseTransitStationStops`, the **dual of `mergeConsecutiveVisits`** and living
beside it. Read-time, not a persisted pass, because it is reversible, it mutates nothing captured, and
the underlying traces must stay intact so `RailRefinementSweep` keeps refining each leg's
classification independently.

**Ordering is load-bearing** (`days()`): visit merge → **stop collapse** → `movementEndpoints`.

- Merge first, so a station stay capture split into fragments presents as *one span* and its **total**
  dwell is what the duration bound judges (pinned by `aStationStopCaptureSplitIntoFragmentsStillCollapses`).
- Collapse before `movementEndpoints`, so the surviving ride brackets against the real origin and
  destination — "Home → Work", not "Home → Station". `movementEndpoints`' deliberate refusal to scan
  past an intervening movement is untouched and still correct; the collapse simply removes the
  intervening pair before it looks.

### 15.2 The rule — five conjuncts, every one a veto that fails closed

A `movement → visit → movement` triple collapses into one ride only when **all** hold:

1. **Contiguity** within `ENDPOINT_MAX_GAP_MS` (30 min) on *both* sides. The existing constant, not a
   new one: the question is literally the same one `movementEndpoints` asks — "is this visit part of
   this movement's story?" A capture hole means we cannot claim the second leg continues the first.
2. **Both legs resolve a trace whose mode is `transit`.** v1 is transit-only, by decision: a 5-minute
   stop during a *drive* is far more likely a real errand, and walk/run/cycle have no station concept.
   An unresolvable trace counts as not-transit — the same "unknown ⇒ never silently swallow" posture
   `moveDistanceM` takes.
3. **The stay is ≤ `STATION_STOP_MAX_MS` = 12 min.** The window is narrow and bounded from below by
   capture (nothing under `minDwellMs` = 5 min is ever a `Visit`), so the constant only chooses where
   in `[5 min, …)` the line falls. 12 min covers a terminus turnaround, a cross-platform connection
   and a held train, plus headroom for the segmenter's departure-detection lag — a dwell is
   *confirmed* at 5 min (§11) and its recorded duration runs to the first fix outside the anchor
   radius, so a 6-minute stop routinely records as 8–9. Above the bound we take the false **negative**:
   a spurious station row is a small self-explanatory blemish, while swallowing a real 20-minute errand
   deletes something the user did. Same "bias hard toward the status quo" posture as §13.
4. **The place is not user-meaningful** — no user-authored label (`Place.labelSource == user`) *and* no
   `PlaceRole` row. ⚠ The role veto is deliberately **wider than the display rule**: it counts
   *suggested* roles too, because the collapse must not swallow a place the inference pass already
   believes is Home merely because the confirm prompt is unanswered. (`PlaceRoleNames.NONE` is
   excluded — a dismissal is a positive statement that the place has no role.) ⚠⚠ A place we cannot
   *find* is treated as meaningful and survives: we cannot prove it is a nowhere.
5. **The stay is not the user's current one.** Swallowing "you are here now" is the worst available
   failure.

Three legs with two qualifying stops fold into one ride (the scan extends over `[stop, leg]` pairs).

### 15.3 The collapsed row must be correct, not merely merged

⚠⚠ **The title is regenerated, never inherited.** A `MOVEMENT` moment stores its title as e.g.
`"Transit · 20.0 km"` and the card renders that string *next to* the mode + distance meta — so keeping
the first leg's title would print one leg's distance beside the combined ride. `MovementMapper.moveTitle`
(the same generator capture uses) produces the new one, so a collapsed ride and a natively-whole one are
byte-identical. This is the trap that bit M9-5. Distance is the sum of the legs; duration spans the
whole journey, station stops included — which is what a train ride's duration means.

**The hero draws the whole journey.** `Moment.traceId` is singular and could only ever show the first
leg, so the UI model gains **`Moment.traceIds`** (ordered, populated for *every* movement) and
`TimelineRepository` gains **`movementRoute(traceIds: List<String>)`**, which the real repository
implements as ONE read whose results are concatenated **in the caller's order**. `traceId` stays as the
first leg for simple consumers. ⚠ A fake built as `object : TimelineRepository by other` gets a
*generated* forwarding override for the list overload that does **not** route through a hand-written
single-id override on the same object — override the list one.

### 15.4 As-built notes

- `PlaceInput` gains `labelSource` + a derived `hasUserLabel` (mirroring `PlaceClustering.Candidate`),
  and `PLACE_FIELDS` now projects `Place.LABEL_SOURCE`. ⚠ A field absent from a projection reads back
  empty, which degrades to `geocoded` — the *unprotected* reading — so without projecting it the guard
  would have silently never fired.
- `days()` gains `rolePlaceIds: Set<String> = roles.keys`; `TimelineProjectionRepository` widens it to
  every role-bearing place. `moment(id)` now passes roles too, so the detail cannot judge the collapse
  differently from the feed and 404 on the row the user just tapped.
- A collapsed stop's own absorbed short movements (§2b `containedMoves` — walking between platforms) go
  with it, intentionally: they describe the inside of a row that no longer exists.
- **No format change.** Read-time projection only; `MovementTrace`, `Moment` and every stored row are
  untouched, so no `TENSHU_STORAGE_FORMAT_VERSION` bump and no relay redeploy (HR #9).
- `MomentReconciler` is deliberately untouched — it keys every movement to its own `momentId` and solves
  a different problem (cross-source duplicates).
- Tests: `TimelineProjectionTest` "in-ride station-stop collapse" section. Every negative test asserts
  its own precondition (it re-runs the fixture with the one guard restored and proves that twin *does*
  collapse), so none can pass by failing to reach the decision. Each guard was mutation-checked — see
  the PR description for which mutation kills which test.

**Not done / next:** the transit-only restriction is v1; a `drive` variant would need a different
signal than duration (a motorway services stop looks nothing like an errand, but nothing in the trace
says so). Thresholds are reasoned, not fitted — the §9 tuning corpus (a real Takeout import) is the
instrument that would settle `STATION_STOP_MAX_MS`. On-device pass pending.

---

## 16. Snapping a transit route to the rail geometry (B4, as-built, 2026-08-01)

**The report.** A recorded train ride is drawn as raw GPS, and raw GPS is not where the train was: it
stops in tunnels, walks along the wrong side of a building in an urban canyon, and has the corner cut
off every curve by §4's decimation. We ship the exact rail linework, so the drawn route can be
corrected to the track the train actually ran on.

**Why it was cheap.** `RailRefinementSweep` (§13.3, M9-5) *already* fetches rail linework per trace
(`MapRegionService.railLinesIn`, segment by segment) and *already* matches every point against it.
Snapping is one more pass over geometry that is already in memory at exactly the right moment — no
second fetch path, no new op on the egress side, no extra tile budget.

### 16.1 The algorithm

`RailSnapper` (`:vector-tile`, pure `commonMain`, no I/O — the `RailMatcher` posture):

1. **Project.** Each source point goes to the nearest candidate line within the 50 m tolerance —
   the nearest position *on* the line, which is what the new `projectOntoPolyline` adds over the
   existing `pointToPolylineMeters` (it also returns *how far along* the line the landing sits).
2. **Walk the track between.** Two consecutive landings on the **same** line have that line's own
   vertices between them emitted, in travel order. This is where tunnels and curves come back: the
   geometry between two landings is the real track, not the straight chord the GPS implied.
   ⚠ Travel order is not vertex order — a tile encoder digitises a line whichever way it likes, so
   the along-distances decide the direction, not the array index.
3. **Unmatched points.** Dropped when a same-line walk already spans them (the track is the better
   answer for a tunnel), kept raw otherwise (the walk to the platform genuinely is not on rails, and
   inventing track for it would be a lie).

**No routing, ever.** Two landings on *different* lines are joined directly. Filling across lines
would need a graph search over the rail network, and from here a train changing tracks and a tile
boundary look identical.

### 16.2 The threshold — and why raising the number was the wrong lever

The gate is **exclusive-track coverage ≥ 0.5**, numerically the same bar `RailRefinement` promotes
on. The obvious "be stricter than promotion" move is to raise it, and it does not survive contact
with the measurements in `timeline-recognition-and-place-naming.md` §3: **real on-device train traces
reach only ~0.54–0.61 exclusive coverage** (two real regional-train journeys sat at 0.58 / 0.61).
An 0.8 bar makes snapping dead code in the field — and worse, the traces that most need correcting
are exactly the ones with the *lowest* coverage.

So the extra strictness is bought **structurally**, which is also strictly better, because a coverage
number cannot tell a tram from a train while a candidate filter can:

- ⚠⚠ **Only exclusive track is a snapping candidate** (`RailMatcher.isExclusiveTrack` — heavy rail,
  subway, narrow gauge). Measured, not assumed: real Firenze *road* traces matched real Firenze rail
  completely **10 times in 240**, because the T1/T2/T3 tram lines run down the middle of streets;
  gating on exclusive track cut that to **2 in 240**. A car on a tram street *is* on tram geometry, so
  projecting a drive onto a tram line would be a confident, visible lie. Excluding street-running
  track makes that unrepresentable **per point** rather than vetoing a whole trace — a train arriving
  beside a tram simply does not borrow the tram's geometry.
  The accepted cost: a genuine **tram ride never snaps**. That is the honest outcome — nothing in the
  geometry distinguishes it from a car on the same street.
- Only a trace whose refined verdict is `transit` is offered at all.
- ⚠⚠ **`RailEvidence.Unavailable` is never evidence.** An undownloaded region, a missing gzip binding
  and a corrupt bundle all answer empty. Empty must never mean "no track", and it must never produce
  a snapped path: the trace is left un-snapped *and* un-stamped, so a later sweep retries it.

### 16.3 The stored shape — ⚠⚠ a sibling row, and why it had to be

`SnappedRoute` is a **new `Syncable(Eager)` composite keyed by `traceId`**, not a second field on
`MovementTrace`.

The obvious shape was `MovementTrace.snappedPolyline` beside the raw one. It is unrepresentable
today: `MovementTrace` rides as a composite parameter of `recordMovementTrace`, so its bytes inline
into that operation's row, and E2's chain verify rejects the nested reshape outright ("Nested
reshapes are not yet supported"). This is the **third** confirmation of the rule `PlaceMerge` and
`PlaceRole` established — *no type that appears as an operation parameter can gain a field* until the
version-N overlay orchestration lands (`core-schema-evolution.md` §8.4).

⚠ **One exception, and it is not a precedent:** FA-4's `MovementTrace.steps` (total pedometer steps,
`-1` = not counted) was appended **in place at v1, before the production reset** (2026-10-29), when
no stored row existed to mis-parse. After the reset the rule above holds again.

The constraint lands on the right side of the argument anyway:

- **The raw polyline is untouched by construction.** §12.0's doctrine is that capture loss is
  permanent, so a classifier's *inputs* are preserved and not merely its verdict. A snap **is** a
  verdict — it depends on which regions happened to be downloaded, on tile zoom, on the tolerance and
  on the snapper version, and every one of those can improve later. Overwriting the raw path would
  make the improvement unrepresentable and the mistake unrecoverable.
- Most traces are walks and drives that will never carry a snap; a sibling row costs them nothing.

Fields: `traceId` (key) · `source` (`rail` today — a plain string, so a later road snapper needs no
schema change) · `snapVersion` · `coverageBp` (the exclusive coverage that justified it, in basis
points — §12.0's "keep the inputs" applied to this tier) · `polyline`.

⚠⚠ **Codec.** `polyline` is the **`MovementTracePolyline`** codec (lat/lng/**time**/accuracy), *not*
`LonLatPolyline` (the lon/lat-only wire codec for rail linework). Decoding one with the other yields
plausible garbage rather than an error, and a previous slice did exactly that. Timestamps are what
make this the same *journey* rather than a shape: a landing keeps its source fix's own timestamp
verbatim, and a walked track vertex gets one interpolated by along-track distance inside its bracket.

Syncing a *derived* row earns its keep because the derivation is not reproducible everywhere — the
snap needs the covering map region downloaded, and the phone that recorded the ride may be the only
device that has it.

### 16.4 ⚠ Display-only

Nothing re-derives distance, duration or mode from the snapped path. Those are computed at capture
over the **raw, pre-decimation** fixes (§3), and re-deriving them here would silently rewrite recorded
history — a stored 34.2 km journey becoming 34.6 km because a later basemap release moved a curve.
*(Follow-up worth considering: snapped distance is arguably more truthful than a decimated chord sum,
but it is a different number for the same past event, so it needs its own decision and its own field
rather than a quiet substitution.)*

### 16.5 Reading it

`TimelineProjectionRepository.movementRoute(traceIds)` reads both relations in the same one-shot and
substitutes **per leg**: a leg with a `SnappedRoute` draws from it, every other leg falls back to its
raw path. Per-leg rather than per-journey is the point — a ride plus the walk to the platform is
exactly the mixed case. The substitution is invisible above that function, so the route hero, the iOS
sketch and the maps fit all inherit it with no call-site change. The `:tenshu-sdk-js` timeline bridge
is deliberately blob-free (it never projects a polyline), so it needs no change either.

### 16.6 Idempotence

`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` goes **8 → 9**, which is what re-offers already-refined
traces to the sweep — without it the correction would only ever reach journeys recorded after this
shipped. Everything else is the existing machinery: the candidate query is derived from the stored
version, both writes are content-addressed upserts, and a second sweep examines nothing.

### 16.7 Format version + migration (House Rule #9)

- **`TENSHU_STORAGE_FORMAT_VERSION` 17 → 18.** A peer that predates `SnappedRoute` parses the block
  to null and silently **drops** it — the same silent-format-mix class as v11's `DeltaBatchPayload`,
  v15's `RealmSchemaEpoch` and v17's `PlaceRole`. Clients move in lockstep; pre-release wipe+resync.
- **`TimelineService` `ServiceSchema` 1 → 2, with an EMPTY v1→v2 `SchemaMigration`.** A genuinely new
  type reshapes no stored row, so there is nothing to migrate — but the step must be registered,
  because the per-service floor is *derived* from the chain and `SchemaMigrationRegistry.verify`
  requires the chain to reach the declared current version. This is TimelineService's first
  registered step, so its derived floor becomes v1. (The `SyncService` v2 / `PlaceService` v2+v3
  precedent.)
- **No existing row shape changed** — see §16.3.
- ⚠ **No relay redeploy.** Since schema-evolution E2e the 426 gate reads `RELAY_PROTOCOL_VERSION`
  (the relay's own wire contract) and never `TENSHU_STORAGE_FORMAT_VERSION`; the relay stores
  ciphertext (HR #1) and cannot be affected by a payload reshape. Nothing here touches the envelope,
  routes, auth or the blob-ref format.
- The new `recordSnappedRoute` op is declared in Timeline's bundled `AppManifest` — Control's
  permission list is an allowlist, and an unlisted op is refused.

### 16.8 Tests

`RailSnapperTest` (`:vector-tile`, geometry) · `RailRouteSnapTest` (policy + timestamps) ·
`RailRefinementSweepTest` (the store round-trip, raw-polyline immutability, idempotence) ·
`TimelinePipelineIntegrationTest` (the per-leg read substitution). Every negative asserts its own
precondition — each "does not snap" case first proves the identical fixture *does* snap as heavy
rail. ⚠ Two things that would have made tests vacuous and are now pinned: the sweep-level tram test
must seed a **`transit`** baseline (seeded as `drive` the classifier refuses first and the snapper's
kind rule is never reached), and the timestamp fixtures must **zig-zag** (Douglas–Peucker collapses a
straight path to its two endpoints, leaving nothing to check). Each guard was mutation-checked; see
the PR description for which mutation kills which test.

**Not done / next:** the on-device pass (this is device-free, like M1–M3 and M5). Road snapping is
the natural sibling and needs a different candidate rule entirely — the exclusivity argument that
makes rail safe has no road analogue. Snapped distance (§16.4) is a deliberate non-goal here.

---

## 17. Fidelity, the leg boundary, and battery — the multi-modal-journey review (design, 2026-08-04)

**The report.** A real trip: *a regional train into a city, a metro ride from the arrival station
into the centre, then on foot to a supermarket.* It was timed correctly to the minute and recorded as **one row**, drawn as a
line that is obviously not the line the train ran on. Five questions fell out of it — why the
geometry is wrong, whether metro rides are recognisable at all, whether the correction passes should
be lazy, whether the foreground service is the right primitive, and why bicycle/run/walk are not
distinguished.

⚠ The framing that makes this section coherent: **almost none of this is missing capability.** §13's
tiers, §16's snapper and §15's collapse are all built and all correct. They do not fire on this
journey because of decisions taken *upstream* of them, in the sensing loop and at the leg boundary.
Fix those and three shipped slices start working on the exact case that motivated them.

### 17.1 Diagnosis — four causes, in order of impact

**1. We sample one fix per minute.** `AndroidLocationCaptureSource(minTimeMs = 60_000L)`. At 120 km/h
that is **one point every 2 km**. The rendered line is not a poor match to the track; it is a ~50-point
chord polygon across an 80 km route, and §4's Douglas–Peucker at `simplifyEpsilonM = 15 m` is inert
because the input is already three orders of magnitude coarser than its epsilon. This is the single
largest lever, and it is upstream of everything else here.

**2. One trace is one mode, and nothing splits on a mode change.** `MovementSegmenter` closes a leg
only on a *dwell* (`minDwellMs` = 5 min inside a 100 m radius), on `maxOpenMoveMs`, or at `onEnd`. The
reported journey had no ≥5 min stationary dwell in it — a cross-platform change at the interchange is
under two minutes — so it is **one** `CapturedMove` carrying **one** median-speed verdict
(`classifyMode`). Train, metro and walk are averaged into a single number. ⚠ This is *not* §15's
station-stop collapse misfiring: §15 rejoins legs capture split, and here capture never split at all.

**3. Which is also why nothing snapped.** `RailRouteSnap.MIN_EXCLUSIVE_COVERAGE = 0.5` is measured
over the **whole** trace path. A path that is roughly half heavy rail, a quarter underground (starved
or ±500 m fixes) and a quarter pavement cannot clear it, so no `SnappedRoute` row is written and
`TimelineProjectionRepository.movementRoute` correctly falls back to raw GPS per leg. **Leg splitting
is the precondition for §16 to apply to a multi-modal journey at all** — §16.5's per-leg substitution
was built for exactly this shape and never gets legs to substitute.

⚠ Secondary, and independent of the above: `RailSnapper` recovers curvature only *between two
landings on the same `RailLine` object*, and lines are produced **per tile** (~2.4 km at z14). With
fixes 2 km apart, consecutive landings routinely fall on different fragments of the same physical
track and are joined by the straight chord §16.1 reserves for genuine line changes. Stitching
fragments that share an endpoint, before matching, is a small change with disproportionate effect —
and it becomes *more* valuable as cause 1 is fixed, not less.

**4. Correction runs once, at app launch.** `RailRefinementTrigger.runOnce()` sweeps a bounded batch
when Timeline is opened with capture on. A journey that ends at 11:20 stays raw until the next launch,
and needs Control reachable for the tile range-reads.

### 17.2 S1 — adaptive, batched sensing (the fidelity *and* the battery slice)

The current request is one shape for all of life: GPS + NETWORK, 60 s, `minDistance = 0`, held open
continuously for as long as capture is on. It is simultaneously **too coarse while moving** (cause 1)
and **far too expensive while still** — most of a day is stationary, and we keep a location request
alive through all of it.

Duty-cycle by state instead:

| State | Request | Why |
|---|---|---|
| **Stationary** (dwell confirmed) | none; arm `TYPE_SIGNIFICANT_MOTION` (one-shot hardware trigger) + `PASSIVE_PROVIDER` | The engine is off for the ~20 h/day that carries no information. Passive fixes are free — they arrive only when some *other* app already paid for one. |
| **On foot** | 20–30 s | Walking geometry is not curvature-critical; the §17.4 cadence signal does the classifying. |
| **Vehicle / rail** | 5–10 s, batched | This is where the drawn line lives. 10 s at 120 km/h is a point every 330 m — enough for the snapper to land consecutive points on the same track fragment. |

⚠⚠ **Three separate power effects, and conflating them is how this gets built wrong.** (a) *Not
requesting at all* while stationary is the dominant saving and needs no new API. (b) The *interval*
buys geometry and costs GNSS duty cycle. (c) **Batching** (`LocationRequestCompat.setMaxUpdateDelayMillis`,
androidx.core) reduces application-processor wakeups, not GNSS duty cycle — and it is honoured only
where the platform and HAL support it (framework batching lands at API 31+). ⚠⚠ **A 10 s interval on
a device that does not batch is strictly worse than today**, so the interval must be chosen
conditionally and verified by measuring real wakeup cadence on a real device, never assumed from the
API being present. `:timeline-shared-data` has no `androidMain` dependency block today; `libs.core.ktx`
has to be added for the compat types.

⚠⚠ **A sampling change invalidates the classifier's calibration.** `MODE_BOUNDARIES` (2.5 / 4.0 / 8.0
m/s) are applied to the median of *per-leg* speeds, and a leg's speed is straight-line displacement
over the interval — which under-reports on any non-straight path, proportionally to the interval.
Shortening the interval therefore **shifts every measured median upward**, most on the twistiest
paths. S1 and §17.4 must land together, or ride a `MOVEMENT_CLASSIFIER_VERSION` bump so already-stamped
traces are not judged against thresholds fitted to a different sampling regime.

Two knock-on constants that stop being generous at 10 s: `MovementDetectionConfig.maxPolylinePoints`
= 500 is under one hour of a train ride, and `CHECKPOINT_FIX_STRIDE` = 3 becomes a journal write every
30 s instead of every 3 min — the stride wants a time arm made primary (the time arm already exists,
sized against `minMoveDurationMs`, and should simply dominate).

#### 17.2a S1 as built (2026-08-05, device-free)

Shipped as designed, in two halves, with these deltas:

- **The policy is pure and the API level is an argument.** `CapturePace` (Stationary / OnFoot /
  Vehicle) → `LocationRequestSpec(intervalMs, maxUpdateDelayMs)` lives in `commonMain`
  (`CapturePace.kt`), with the v1 table 5 min/15 min · 30 s/2 min · 10 s/5 min. ⚠⚠ **The
  below-API-31 clamp is inside the policy, not at the Android call site** — "a 10 s interval on a
  device that does not batch is strictly worse than today" is a *rule*, so it is unit-tested rather
  than trusted. `UNBATCHED_MIN_INTERVAL_MS` = **30 s**, not today's 60 s: half the interval is still
  a real geometry win at walking pace for twice the wakeups; the vehicle rate is what the clamp
  exists to refuse.
- ⚠⚠ **Stationary is a slow request, not the absence of one** — §18.6's requirement, folded in here
  as it asked. The KDoc says so at the constant, because the obvious future "optimisation" to zero
  would silently break dwell *departure* detection, which still reads off the fix stream. Significant
  motion is layered on as a **fast wake**, never as the replacement.
- **The pace comes from the segmenter's own events** (`CapturePacePolicy.paceFor`), so nothing
  observes the user twice and nothing can hold two opinions about what they are doing. ⚠ It reads the
  **raw** bucket, not `OngoingMove.provisionalMode`: that property gates on confidence because it
  feeds a *label*, and it reads UNKNOWN for roughly the first ten legs — i.e. exactly the start of a
  journey we most want sampled properly. A wrong VEHICLE costs some battery; a wrong ON_FOOT costs
  those minutes' geometry permanently. Cycle counts as Vehicle (curvature-rich at 14–29 km/h);
  UNKNOWN and any unrecognised mode fall to ON_FOOT, the cheaper guess.
- **Android**: `LocationManagerCompat` + `LocationRequestCompat` behind a new `androidMain`
  dependency block (`libs.core.ktx` — there was none in `:timeline-shared-data`). The
  `minTimeMs` constructor parameter is **gone**; the interval is the policy's output. Re-registration
  happens on every pace transition, `drop(1)` so the initial registration is not restarted. All
  per-collection state (pace cell, listener, armed trigger) lives inside the `callbackFlow`.
- ✅ **`TYPE_SIGNIFICANT_MOTION` needs no permission** — checked, not assumed: `ACTIVITY_RECOGNITION`
  gates `TYPE_STEP_COUNTER`/`TYPE_STEP_DETECTOR` (API 29+), not this. `timeline-android`'s manifest
  is untouched. It is armed **only** while Stationary and is a hardware **one-shot**, so the re-arm
  point is the next transition back into Stationary — deliberately not inside the callback, which
  would leave a trigger live while moving.
- ✅ All three §11.1 on-device findings survive verbatim: `minDistanceM = 0`, GPS **and** NETWORK,
  and the recent-last-known seed with `SEED_MAX_AGE_MS`.
- **`MOVEMENT_CLASSIFIER_VERSION` 3 → 4.** ⚠ **`MODE_BOUNDARIES` deliberately NOT re-tuned** —
  fitting them needs the T3 corpus, which does not exist; guessing would be worse than a documented,
  distinguishable status quo. `MovementClassifierVersionTest` pins the buckets so a future retune has
  to be deliberate, and pins that `RailRefinement.needsRefinement` still behaves: 4 < 9, so fresh
  traces are still offered to the rail sweep and already-refined ones are not re-offered.
- ⚠⚠ **The two knock-on constants above are NOT fixed and are now live risks.** At a 10 s interval
  `maxPolylinePoints` = 500 caps under an hour of a train ride, and `CHECKPOINT_FIX_STRIDE` = 3
  becomes a journal write every 30 s. The stride was left alone on purpose: making the time arm
  primary widens the durability window a kill can cost from three fixes to `minMoveDurationMs`, which
  is a trade the maintainer should make explicitly rather than have bundled into a sensing slice.
- **Format impact: none.** No stored row changed shape; `SchemaShapeBaselineEnforcementTest` green.

### 17.3 S2 — split a leg when the mode changes, not only when the user stops

The journey has three legs because the *mode* changed three times, not because the user stayed
anywhere. Capture has no notion of that boundary.

⚠ **Do not put this in the online state machine.** A mode change is only visible in retrospect — you
cannot classify a leg that has not happened yet, and the streaming segmenter is the one piece here
whose correctness (journal recovery, dwell confirmation, content addressing) is hard-won and
device-proven. The shape instead: **a pure post-pass at flush time.** `flushMove` currently hands one
buffered fix list to `toCapturedMoveOrNull`; it should hand it to a changepoint splitter that returns
*one or more* homogeneous runs, each of which then goes through the existing builder unchanged.

That keeps the split a pure, unit-testable function over a fix list — the `MovementSegmenter`
posture — and it composes with everything downstream for free: each run is content-addressed by its
own `startMs`, so recovery and idempotence are unaffected, and each becomes a `MovementTrace` the
sweep can refine, promote and snap **independently**, which is precisely what §16.5's per-leg
substitution was written for.

Guards, all of them vetoes that fail toward the status quo (the §13 posture):

- **Hysteresis.** Require N consecutive windows in the new class before cutting. A traffic jam is not
  a walk; a train slowing into a station is not a cycle.
- **Minimum leg.** A candidate run that fails `minMoveDistanceM` / `minMoveDurationMs` is merged back
  into its neighbour rather than dropped — the whole journey must remain accounted for.
- **Prefer not splitting.** An ambiguous boundary leaves one leg. Today's behaviour is a coarse row;
  the failure mode of over-splitting is a shredded journey, which is worse and less recoverable.
- ⚠ The live `MoveProgress` view is computed over the *open* buffer and will therefore show the
  provisional whole; it is re-split at flush. That is acceptable (live is explicitly provisional,
  §12.7) but it must not be allowed to write anything.

### 17.4 S3 — walk / run / cycle by cadence, not by gyroscope

Timeline uses **no motion sensors at all** today; the three buckets rest entirely on a 1-D speed
median whose bands genuinely overlap (a fast walk against a slow run; a slow cyclist against a runner).

**Gyroscope is the wrong instrument** — it needs continuous sampling plus frequency analysis at
milliamps. The pedometer already runs in the sensor hub:

- `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR`, registered with a `maxReportLatencyUs` of minutes →
  hardware-batched, sub-milliamp.
- Walk ≈ 1.6–2.0 steps/s · Run ≈ 2.5–3.1 steps/s · **Cycle ≈ zero steps at 3–8 m/s** ← the decisive
  one · vehicle/rail ≈ zero steps at higher speed.

Steps-per-minute × median speed makes the decision 2-D and resolves exactly the confusion reported.
It is also the **honest input to S2's splitter**: a cadence change is a far crisper changepoint than a
speed change, and it is the one signal that survives a tunnel.

⚠ `ACTIVITY_RECOGNITION` must be declared in `timeline-android`'s manifest (API 29+) *and* granted at
runtime. Undeclared, the sensor silently reports nothing and the classifier quietly degrades to
today's behaviour with no crash and no log — the `READ_HEIGHT` failure mode. `:fit-android`'s
`AndroidRecordingEngine` already declares it and reads `TYPE_STEP_COUNTER`; copy that, and put the
absence behind an explicit `SensorState`-style value so "no pedometer on this device" is a
distinguishable verdict rather than "zero steps" (a phone in a bag reads zero steps on a walk).

Accelerometer-window classification (2–4 s windows; mean/variance/energy/FFT-peak features; a small
decision tree) is the classic literature and would give mode detection with no GPS at all. It is a
**later tier, not this slice** — cadence already covers the reported confusion, and the accelerometer
tier costs continuous sampling that only pays for itself once we want classification without location.

### 17.5 S4 — the metro leg, and what the shipped map data actually supports

**Verified against `RailMatcher.railKindOf` and §13.2a's real-tile survey:**

- The **production DACH tiles carry rail in the `roads` layer** as `kind=rail` with `kind_detail` ∈
  `rail | tram | light_rail | subway | narrow_gauge`. **Subway alignment geometry is therefore already
  shipping and already distinguishable** — no new data source is needed to know where a metro line
  runs.
- ~~**There are no railway-station POIs anywhere**, at schema level.~~ ⚠⚠ **FALSIFIED — see §28.2b.**
  This was true of the *bundled Firenze demo* archive (which is what `MvtRealArchiveTest` decodes, and
  all it can pin) and generalised to every cut without a survey. The deployed `de-bayern` `pois` layer
  carries named `station` / `halt` / `stop` / `tram_stop` / `platform`, and the offline stop lookup now
  reads them. T2′ (stop-on-rail) is kept — on its own merits, not because the POIs are absent.
- **No line identity.** Nothing in the production `roads` schema names a line (a metro or suburban-rail line number). The demo
  Protomaps `transit` layer carried `network` / `ref` / `route`; ⚠ **whether a real `de-bayern` extract
  carries anything equivalent has never been surveyed** — §13.2a decoded the Firenze demo archive only.
  That survey is cheap and it decides whether line naming is reachable for free or needs §17.6.

**The metro signal is a positive one, not an absence.** GPS is the wrong sensor underground, so the
tempting framing ("we lose the leg") inverts the actual evidence. An underground leg has a signature:
*the fix stream goes dark, or degrades to coarse NETWORK fixes, for N minutes, and resumes X km away
with **both endpoints on `kind_detail=subway` geometry**, at a speed no walk could cover.* That is a
**dark-leg detector**, and every input already exists — the fixes, the gap, and the subway linework.
The leg then snaps to the subway line between its endpoints, giving the correct route *and* the
correct label from one inference. In practice the metro in the motivating report has mobile coverage, so expect coarse
NETWORK fixes clustering along the alignment rather than true darkness — which is strictly easier.

⚠ Snapping an underground leg means relaxing §16's exclusive-track candidate rule to *include*
`subway`, which is safe on the exclusivity argument (a road vehicle cannot occupy subway track) but
**not** on the coverage argument — a dark leg has almost no points to cover with. So a dark leg needs
its own gate: endpoint proximity plus an implied speed no surface mode explains, never a coverage
fraction over a handful of fixes.

Ship the **tile-fragment stitching** (§17.1 cause 4-secondary) in the same slice; it is the same file
and the same test fixture.

### 17.6 Additional data sources — cheapest first

We do **not** build tiles: `scripts/tiles-build.sh` cuts bbox extracts from a published Protomaps v4
planet. The schema is therefore not ours to extend without owning a planet build.

| Option | Buys | Cost | Verdict |
|---|---|---|---|
| **Survey a real `de-bayern` extract** | Whether `network`/`ref` already ship on production rail | ~half a day, zero runtime cost | **Do this first** — it is a precondition for judging the rest |
| **Our own per-region transit overlay** from OSM extracts (osmium/Overpass → stations, platforms, `route=subway/train/tram` relations), signed and delivered like the tile bundles | Stations, line identity, per-line alignment — revives T2 properly, on our own schema | A few MB/region, a new build step + a catalogue entry | **The recommendation** if the survey comes back empty |
| Custom Planetiler profile / own planet build | Same data, inside the basemap | 100+ GB, multi-hour, and we own the pipeline forever | **No** — the overlay gets the same data without owning a planet |
| GTFS (per transit operator) | Real services and schedules: "<line>, 09:41" | Feed-per-operator, periodic refresh, large | Later, if ever — a different kind of claim about the user's day |

The overlay is also the honest answer to "could we name the line": nothing we ship today can, and no
amount of tuning changes that.

⭐ **The user-facing target, recorded so the overlay has one** (2026-08-12): a metro leg should read
**"<line> from <station A> to <station B>"**. §32.14 delivers the second half — the stops — from tiles we
already ship; the **line** is exactly and only what this overlay buys. The survey this table calls for
was done (§24) and came back empty, so the overlay is now the recommendation on measurement rather
than on expectation.

#### ⭐ The line can be attached **retroactively** — and the key is the stops, not the path

Nothing here has to happen during recording. Late re-labelling is this track's established mechanism,
not a workaround: `RailRefinementSweep` already lands a trace with a speed verdict and corrects it once
map data is consulted, and `MOVEMENT_CLASSIFIER_VERSION` exists precisely so a later, better rule can
find the rows still carrying an old one (§12.1, "store the inputs, not only the verdict"). When this
overlay lands, a sweep revisits stored traces; **no journey needs re-walking**.

⚠ The boundary is what was *persisted*, not what was true:

| kept | gone |
|---|---|
| the trace polyline, start/end points, bbox, mode, classifier version, and the §32.14 stop endpoints | the raw fix stream (`RawFixJournal` is debug-only and capped) and the dwell **cluster** — only its centroid survives |

That is the same line §27 draws when it explains why `UndergroundHop` must run at capture — *"the
evidence is perishable and the tiles are not"* — and why §32.10 could correct a place's accuracy going
forward but could not decide whether a past stop was real.

⚠⚠ **Do not plan on matching a metro leg's geometry against the overlay.** §27 measured the stored
trace of a real metro hop at **8 fixes decimated to 2, with a travelling span of 0 m**: there is no path
there to map-match. The identifying evidence is the **stop pair** — the leg's two stations, against
the overlay's `route=subway` relations names the line with no geometry at all.

⇒ A heavy-rail leg can also be matched by geometry, but the underground case — the one that motivated
the whole request — cannot. **The stop pair is what identifies the line.**

#### ⭐ Proven against live OSM (2026-08-12) — the line is there, but do **not** join by name

Overpass over the two public station coordinates, before committing to any pipeline:

| station | `route=subway` relations |
|---|---|
| Station A (arrival interchange) | **one line** (both directions, nothing else) |
| Station B (city centre) | four lines, including that one |
| **intersection** | ⭐ **that one line** |

So a stop-pair intersection over `route=subway` names the line, and the extraction is small — one
direction of that line lists 18 stops, so a region's whole subway/tram/rail set is the few MB §17.6 estimated.

⚠⚠ **But the two sources disagree on the station's name.** The shipped basemap POI at that coordinate
(what §32.14 resolves, at 79 m) and the OSM route relation give the same station **two different
names**. A name-based join therefore fails on the very first station of the motivating journey.
⚠ This is not an OSM quirk to work around — it is the same disagreement that already left **the two
names as two separate `Place` rows** in the real store.

⇒ **Join by coordinate, not by name.** The overlay should be indexed by stop position, and the line
lookup then runs off the leg's own stored endpoints — the same input §32.14 uses, resolved
independently. That also decouples the two: the basemap POI supplies the **display name**, the overlay
supplies the **line**, and neither has to agree with the other about spelling.

⚠ This corrects an earlier reading of this section: §32.14 is *not* a prerequisite for line identity.
It remains what makes the row readable ("<interchange> → <centre stop>") and it proves the endpoint really
is a station, but the line can be resolved without it.

#### ⭐ Scope, settled (maintainer, 2026-08-12): the **line**, never the service

The line's name is the overlay's whole output: produce exactly that and stop. **GTFS stays out of
scope** — the table above keeps it at "later, if ever", and this pins why: naming *which* run of the line (a
departure at 11:02, a delay, a formation) is a different kind of claim about the user's day, needs a
feed per operator, and is not what was asked for.

⚠ Display the relation's **`ref`**, not its `name`. Measured, one relation carries
a bare line number as `ref` and a `name` of the form `<network> <line>: <terminus> => <terminus>` —
the name is a *direction*, useful for
disambiguation and unreadable on a feed row.

#### ⚠⚠ The intersection is a **set**, and often not a singleton

The motivating Station A → Station B case resolves cleanly to one line, which flatters the rule. A
city's shared trunks do not:

| stop pair | lines serving both |
|---|---|
| Station A → Station B | **{one line}** |
| Station B → Station C (shared trunk) | **{two lines}** |

Where two lines run together across the centre, *any* leg between two trunk stops answers with two lines.
⚠⚠ And the tie cannot be broken by geometry for the case that motivated this: §27 measured a covered
metro hop's stored trace at **8 fixes decimated to 2, travelling span 0 m**. There is nothing to
map-match against a per-line alignment.

⇒ **The consumer needs a rule for the ambiguous case before this ships**, and it is a display decision
rather than a data one. Showing the set — both line numbers, "X/Y" — is the honest default and is what a departure
board does; falling back to the bare mode throws away a true fact; and picking one arbitrarily is the
only option that can be *wrong*. Deciding it late, after the pipeline exists, is how a plausible-but-
wrong label gets shipped.

### 17.7 Is the foreground service the right primitive? — yes, and do not make it adaptive

It **is** a real FGS: `foregroundServiceType="location"` in the manifest, `ServiceCompat.startForeground(…,
FOREGROUND_SERVICE_TYPE_LOCATION)`, `setOngoing(true)`. What reads as "not sticky" is Android 14+
behaviour — the platform made foreground-service notifications user-dismissible for most types,
`location` included; the service keeps running. The `IMPORTANCE_LOW` channel additionally files it in
the silent section. Neither is a defect, and neither is fixable: the exemptions are `mediaPlayback`
and `callStyle`. (Reassuringly, Android 15's FGS *timeout* applies to `dataSync`/`mediaProcessing`,
**not** `location` — there is no clock running on this service.)

It is also the right primitive. On a de-Googled device an FGS is the only sanctioned way to hold
background location continuously; WorkManager's 15-minute floor destroys both dwell confirmation and
move segmentation, GMS geofencing is off the table by stance, and exact alarms are discouraged and no
cheaper.

⚠⚠ **The tempting refinement — stop the service while stationary and restart it on significant
motion — is rejected.** Starting a `location` FGS from the background on 14+ is exactly the fragile
path `TimelineCaptureForegroundService.onCreate` already has to guard against, and process death is
what the whole §11.4 OEM-resilience slice exists to prevent; ColorOS needs no help killing this. The
service stays up permanently — a notification costs nothing — and **§17.2's adaptation happens inside
it, in the location request, not in the service lifecycle.** The battery win comes from the sensor
policy; buying it with lifecycle churn would trade a measured saving for an unmeasured resilience
regression.

Worth doing while in there: make the notification carry the live leg ("Train · 34 km · since 09:37")
instead of static text. Better as UX and strictly stronger as the A35 capture indicator, which is the
notification's actual job.

### 17.8 Where the correction passes run — lazy, but not launch-only

The deferral is already right in principle (§13.3: the classifier is synchronous, the tiles are async
through Control) and wrong in *timing*. Two changes, no new machinery:

- **Trigger on leg close.** A `Move` flush is literally "the user finished doing one thing" — the
  event §17.2 asks about. It is bounded (one trace), it is exactly when the data is freshest, and the
  sweep is already idempotent and version-stamped so an extra invocation costs a query.
- **Keep a periodic catch-up** for everything the per-leg trigger missed (Control unreachable, region
  not yet downloadable, a `MOVEMENT_CLASSIFIER_VERSION` bump re-offering history), on idle+charging
  rather than at launch.

⚠ Worth an explicit decision when this is built: the sweep is client-side, running in Timeline's
process over `TenshuClient`, and **Timeline has neither a scheduler nor network** — both belong to
Control, which is also where the tiles and the store already are. Moving the sweep proxy-side is the
architecturally cleaner home and is a bigger change than it looks (§13.3 chose app-side deliberately,
for the capture opt-in gate). Not decided here.

### 17.9 Phasing

Each slice is independently shippable and device-free up to its on-device pass. The order is not
arbitrary — S1 changes the data every later slice reasons over, so landing it late means calibrating
twice.

1. **S1 — adaptive + batched sensing.** Fixes geometry and battery together. ⚠ Bumps
   `MOVEMENT_CLASSIFIER_VERSION` (§17.2's calibration argument), and wants the real-device wakeup
   measurement before the vehicle interval is chosen.
2. **S2 — mode-change leg splitting.** Unblocks §13's classification and §16's snapping on
   multi-modal journeys. Pure function at the flush boundary; no schema change.
3. **S3 — step cadence into `classifyMode`.** Needs the `ACTIVITY_RECOGNITION` declaration. Lands
   with or immediately after S1.
4. **S4 — dark-leg/subway detection + rail-fragment stitching.** Depends on S2 having produced a leg
   to detect.
5. **S5 — survey a real `de-bayern` extract**; scope the OSM transit overlay (§17.6) only if it comes
   back without line identity.

Also queued, small: **the notification live-leg text** (§17.7) and the **sweep trigger move** (§17.8).
§18 adds **S6** (accuracy-aware dwell clustering — belongs with S1, same subsystem) and **S7** (Wi-Fi
observability — independent, rides any slice).

**Format impact:** none anticipated. `mode` is a plain string (§13.5), no stored row reshapes, and
`MOVEMENT_CLASSIFIER_VERSION` is a value, not a schema version — so no
`TENSHU_STORAGE_FORMAT_VERSION` bump and no relay redeploy (HR #9). ⚠ Confirm against
`SchemaShapeBaselineEnforcementTest` per slice rather than trusting this paragraph: if a leg-split
verdict or a cadence figure ends up *persisted* (and §12.1's "store the inputs, not just the verdict"
doctrine argues it should), that is a `MovementTrace` reshape — and `MovementTrace` is an operation
parameter, so §16.3's rule applies and it must be a **sibling row**, not a new field. *(The step
**total** did land on the trace — FA-4, the pre-reset exception §16.3 names; cadence is that total
over the span.)*

**Non-goals here:** road snapping (§16's "not done"), accelerometer-window classification (§17.4),
GTFS (§17.6), and moving the sweep proxy-side (§17.8) — each is named so it is not re-derived, none
is scoped.

---

## 18. One stay, three rows — dwell fragmentation and the merge vetoes (design, 2026-08-04)

**The report**, from the same day as §17: a full day at one desk read as **three consecutive entries for
the office**, and the venue had to be named by hand despite the phone being on its Wi-Fi the whole time.

This is the symptom `timeline-recognition-and-place-naming.md` §8.2 diagnosed and F1/F2 fixed
— arriving again. ⚠ It is **not** a regression of that fix: F1/F2 addressed *place identity* (which
row a fragment attaches to). This report is about the **fragments themselves**, which nothing has yet
addressed, plus a third, orthogonal failure in the Wi-Fi path.

### 18.1 Three independent failures, stacked

1. **Capture manufactures the fragments** (§18.2) — the generator.
2. **The read-time merge then refuses to rejoin them** (§18.3, §18.4).
3. **Wi-Fi recognition returned silently** (§18.5) — so the fragments did not even land on one place.

Each is separately sufficient to produce the report, which is why fixing one and re-testing is the
wrong move: fix the generator, and 2 and 3 stop mattering for this case.

### 18.2 The generator — the dwell anchor radius is not accuracy-aware

`VisitDetectionConfig.radiusM = 100.0`, a bare constant, is applied to fixes whose indoor accuracy is
**±100 m** (the NETWORK provider, which §11.1 added precisely because GPS gets nothing indoors). A
*stationary* phone therefore leaves its own anchor by construction: `MovementSegmenter.step` closes
the cluster on the first outlier, starts a new anchor at that outlier, and five minutes later mints a
second dwell — with a new arrival timestamp, hence a new content-addressed
`cap-visit:<placeId>:<arrival>`, hence a new row. Repeat, and one stay is three.

⚠⚠ **F1 already encoded exactly the right rule, one layer up.** Recognition §8.4's snap radius is
`max(candidate.accuracyM, existing.accuracyM).coerceIn(60, 150)` — *two dwells can only be told apart
to within the accuracy of the fixes that produced them.* That reasoning is identical here and was
never applied to the **clustering that produces the dwells**. The fix went to the symptom, not the
generator. `CapturedFix.accuracyM` has been available since M6, so the input is already there.

### 18.3 The phantom walk — a spurious row that is *also* the merge veto

When a cluster closes on an outlier, its fixes go to `moveBuffer`, and `pathDistanceMeters` sums
**raw consecutive legs**. Jitter therefore accumulates monotonically: ten ±100 m NETWORK fixes at one
per minute is on the order of **a kilometre of "path" from a phone that never moved**.
`toCapturedMoveOrNull` asks for only 250 m / 3 min / ≥3 fixes — so a **phantom `Walk` is written
between the two fragments**.

That is not merely an extra row. `ABSORB_MOVE_MAX_M = 300` in `mergeConsecutiveVisits`, so the
phantom is *precisely* the thing that vetoes the merge that would have repaired the split. One noise
source produces the fragment, the spurious row, and the reason the repair does not run.

⚠ **Falsifiable prediction, and the first thing to check on the device:** the log should show a short
walk (~300 m–1 km) between the office rows. If it is there, this is the mechanism. If it is not, the
veto was the gap or `sameArea` — see §18.4.

### 18.4 The four ways the merge can refuse, and how to tell them apart

| Veto | Constant | Reads as |
|---|---|---|
| Time gap between fragments | `MERGE_MAX_GAP_MS` = 15 min | Starved fixes indoors; a dwell that closed and did not reopen for a while |
| An absorbed movement is real travel | `ABSORB_MOVE_MAX_M` = 300 m | §18.3's phantom walk |
| Not the same area | `SAME_PLACE_RADIUS_M` = 200 m, else `subjectId` equality | Fragments on different places > 200 m apart, or (coords missing) different placeIds |
| **The movement's trace did not resolve** | `moveDistanceM` → `Long.MAX_VALUE` | ⚠⚠ silent |

⚠⚠ The fourth deserves attention. `moveDistanceM` returning `Long.MAX_VALUE` for an unresolvable
trace is a deliberate "unknown ⇒ treated as far, so we never silently swallow a real trip" — correct
as stated, but its **other face** is that *any* movement whose trace row is not among the loaded
`traces` vetoes every merge around it, silently and unconditionally. Whether the projector's trace
read can be narrower than its moment read (paging, projection, a limit) is worth checking before
tuning anything else; if it can, this veto fires for reasons that have nothing to do with the user's
day.

### 18.5 Why the Wi-Fi feature looked dead — the two silent returns

`WifiPlaceFingerprint.resolvePlaceId` returns the candidate unchanged, **logging nothing**, in
exactly the two states most likely to be the true one:

1. `control.enabled.value == false` — the opt-in is off by default;
2. `usable(connected.currentBssid()) == null` — Android handed back the redacted
   `02:00:00:00:00:00`, or location services are off system-wide, or there is no connected network.

F2 added `wifi_recognised` / `wifi_adopted` / `wifi_learned` breadcrumbs precisely so this would be
readable on-device. But the **absence** path is still unlogged, so "no wifi lines at all" cannot be
distinguished from "the feature never ran" — which means §8.2's original complaint (*"is this even
working?" was unanswerable without reading `shared_prefs` by hand*) is only **half** closed. The
missing half is the half that fires when something is wrong.

⚠ Sequencing note when reading a device: F1/F2 affect only **new** captures;
history repair is F3 / the proxy-side P4e pass. Rows written before those landed keep
their split, so old fragments are not evidence about current behaviour.

### 18.6 Does §17 fix this? Partly — and it changes one of §17's requirements

- **S1 (adaptive sensing) — yes, and it is the largest effect.** A stationary phone that requests no
  location produces no jitter: no fragments, no phantom walks, nothing for the merge to veto on. This
  is a second, independent argument for the stationary policy §17.2 justified purely on battery.
  ⚠⚠ **But it imposes a requirement §17.2 did not state: dwell *exit* must not depend on the fixes S1
  stops requesting.** Significant motion becomes the departure trigger — which is strictly better,
  because a hardware trigger does not jitter — and a naive "stop location while dwelling" that leaves
  departure detection on the fix stream would break the thing it was meant to help. Fold this into
  S1's design, not into a later slice.
- **S3 (cadence) — under-weighted in §17.4.** **Zero steps is the cleanest "still here" signal
  available**, and it is exactly what separates *the fix moved 150 m* from *the user moved 150 m*.
  Cadence should gate **dwell exit**, not only mode classification.
- **S2 / S4 / S5** — unrelated to this report.
- **Not covered at all:** the anchor radius, and the Wi-Fi silence. Hence the two slices below.

### 18.7 Two more slices

**S6 — accuracy-aware dwell clustering.** Three parts, all in the capture loop:
1. The anchor radius scales with the fixes' own accuracy, mirroring F1's rule and bounded the same
   way. ⚠ Widening it costs resolution between genuinely adjacent places — the accuracy scaling is
   what bounds that cost, since a clean ±10 m outdoor fix keeps a tight radius. It should not become
   a bare larger constant.
2. A **still-here veto** from cadence / significant motion (§18.6): do not close a cluster on
   position alone when the motion sensors say the device has not moved.
3. Refuse to build a `Move` out of fixes that never left an accuracy-sized ball. ⚠ This is
   deliberately *not* "raise `ABSORB_MOVE_MAX_M`": that constant would have to grow past a real 300 m
   walk to suppress the phantom, which would then start swallowing genuine short trips. Kill the
   phantom at the source and the read-time constant can stay honest.

**S7 — Wi-Fi observability.** One breadcrumb on the unavailable path, distinguishing *consent off* /
*BSSID redacted* / *no network*, and the last verdict surfaced in Settings beside the toggle. Tiny,
independent of everything else, and it is the whole difference between "does not really work" and a
diagnosis.

**Ordering:** S6 belongs with S1 — same subsystem, same on-device pass, and S1 removes most of the
input S6 guards against. S7 is independent and small enough to ride any slice.

**Format impact:** none. Capture-loop tuning plus one log line; no stored row changes shape, so no
`TENSHU_STORAGE_FORMAT_VERSION` bump and no relay redeploy (HR #9).

**Open:** whether `mergeConsecutiveVisits`' constants should be accuracy-aware too, or whether fixing
the generator makes that unnecessary. Prefer the latter until a case survives S6 — a read-time merge
tuned around noise the capture layer no longer produces is a constant nobody will be able to justify
later.

### 18.8 S6 as built (2026-08-05, device-free) — and one thing the design did not anticipate

**S6a — the anchor radius.** `VisitDetectionConfig` gains `maxAnchorRadiusM` (250 m, a field so it is
tunable and testable), and the rule is one shared function, `VisitDetectionConfig.anchorRadiusM(anchor,
fix)` in `Geo.kt`:

```
max(anchor.accuracyM, fix.accuracyM).coerceIn(radiusM, maxAnchorRadiusM)
```

⚠⚠ **All three clustering call sites read it** — `MovementSegmenter.step`, `VisitDetector.detect`,
`FixClustering.detectVisits` — so divergence is unrepresentable rather than merely tested against.
(`MovementSegmenterTest` pins the batch/streaming equality bit-for-bit, and its fixtures report *no*
accuracy at all, so a new test re-asserts the same invariant on accuracy-bearing fixes.) `accuracyM
== 0` is *not reported*, never *perfect*: both unreported falls to the floor, one reported governs
via the `max`.

⚠⚠ **The finding the design did not anticipate: as first written, at exactly ±100 m S6a was a
no-op** — and it is now fixed. `radiusM` is already 100, so `max(accuracy).coerceIn(100, 250)`
returned 100 for any pair reporting 100 m or better; the rule only widened from ~101 m up. §18.2
describes the office case as "±100 m", so **the slice did nothing on its own motivating report**.

⚠ **The first proposed remedy — lower the floor, as F1 did — does not work**, and it is recorded
here because it is the intuitive one: `coerceIn(60, 250)` of 100 is *still* 100. Lowering the floor
only tightens the radius for **good** fixes; it cannot widen a coarse one. Anyone reaching for it
later should stop here.

**The fix is a factor, and S6's other half already had it.**
`anchorRadiusM = (anchorAccuracyFactor × max(accuracy)).coerceIn(radiusM, maxAnchorRadiusM)`, with
`anchorAccuracyFactor = 2.0` — numerically and *for the same reason* as
`MovementDetectionConfig.extentAccuracyFactor`, which the same slice introduced two functions away:
**`accuracyM` is a ~68 % confidence radius**, so two fixes on opposite sides of one stationary point
sit ~2× that apart with neither having moved, and a 1× bar admits exactly the cloud the rule exists
to hold together. That is a statistical property of independent errors, **not a constant fitted to a
device's reported number** — which is why it did not need the on-device measurement to settle. At
±100 m the radius is now 200 m; at ±10 m it scales to 20 m and is floored back to 100 m, so clean
outdoor clustering is untouched; at ±150 m it caps at 250 m.

⚠ The two factors should move together if either is ever re-tuned — same premise, same evidence.
`theReportedHundredMetreAccuracyIsNotANoOp` pins the exact reported accuracy, with a config-only
twin at `anchorAccuracyFactor = 1.0` proving the pre-fix shape fragments the same stay.

**S6b — the extent gate.** `toCapturedMoveOrNull` gains a fourth conjunct: the path's **extent**
(greatest distance any fix reaches from the first — `pathExtentMeters`) must exceed
`max(minExtentM, extentAccuracyFactor × mean reported accuracy)`.

- ⚠⚠ **Extent, never net displacement** — a real out-and-back walk finishes where it started, so a
  displacement gate would delete exactly the trips the guard exists to protect. "Large path, small
  extent" is the jitter signature, and that is the whole discriminator.
- `minExtentM` = **100 m**, reasoned not fitted: 250 m of path (`minMoveDistanceM`) in the degenerate
  perfect out-and-back reaches only 125 m of extent, so anything at or above that would start
  deleting trips the *path* gate already accepted. 100 keeps margin under it.
- ⚠ **Deviation from §18.7's literal `max(meanAccuracy, MIN_EXTENT_M)`:** the accuracy arm carries a
  factor, `extentAccuracyFactor` = **2.0**. `accuracyM` is a ~68 % confidence radius, so two fixes on
  opposite sides of one stationary point are routinely ~2× that apart with nothing having moved; a 1×
  bar would therefore still admit an ordinary ±100 m indoor cloud, which is the case the gate exists
  for. The honest consequence, stated in the KDoc: at ±150 m indoors we refuse to record a real 280 m
  walk. That is not information lost — at that accuracy the fixes never distinguished it from sitting
  still.
- ✅ It is **not** "raise `ABSORB_MOVE_MAX_M`". The projector's constants are untouched.

**S6's third part (the still-here veto from cadence / significant motion, §18.7 item 2) is NOT
built** — it needs S3's step signal to be a veto rather than a guess, and S1's significant-motion
trigger is currently a *departure* hint, not a *stay* proof.

⚠ **iOS behaviour changes too, and that is intended.** `IosLocationCapture` and
`EventDrivenCaptureSession` feed the same shared `MovementSegmenter`, so both S6 rules apply there
the moment this ships. No iOS source was touched.

**Testing posture (HR #7).** Every negative assertion is twinned with the same fixture under the
pre-S6 rule, and the twin has to *fail* the way the report described — a "no phantom move" assertion
would otherwise also hold against an implementation that never emits a move. Both twins are expressed
purely in **config** (`maxAnchorRadiusM = radiusM` collapses the scaling back to the old bare
constant; `minExtentM = 0` / `extentAccuracyFactor = 0` disables the extent gate), so the old
behaviour is reachable with no test-only code path.

**Format impact: none, confirmed rather than asserted** — `SchemaShapeBaselineEnforcementTest` is
green, so no composite's positional layout moved: no `TENSHU_STORAGE_FORMAT_VERSION` bump, no service
version bump, no relay redeploy (HR #9).

---

## 18.9 Verifying this offline — the replay instrument and the raw-fix corpus (as-built, 2026-08-05)

**The question that opened this:** can S1/S6 be checked against historic data already on the phone,
rather than only forward on a live device?

**Partly — and the split is sharp enough to be the design.**

### 18.9.1 What is replayable, and what is not

| slice | replayable? | why |
|---|---|---|
| **S6b** (extent gate) | **yes, today** | every `MovementTrace` stores its path *with per-point accuracy* |
| **S6a** (anchor radius) | **no** | a dwell keeps only its centroid — the cluster is discarded |
| **S1** (sensing) | **not even in principle** | it changes *which fixes exist*; 10 s cannot be synthesised from 60 s samples |

⚠⚠ **S6b is replayable because M6 deliberately made it so.** `MovementTracePolyline`'s KDoc states
the reason accuracy went into the codec: *"a classifier which must discount low-accuracy legs could
never be applied to already-stored traces."* That was written as an abstract argument for keeping
inputs; it is now the thing that lets us measure a shipped rule against a real corpus. The same
`classifierVersion` foresight that made the rail sweep possible pays out a second time.

⚠⚠ **S6a is not replayable because nothing persists a raw fix**, and that is exactly the gap §9 has
called a missing "tuning corpus" since M1. It is also why `MODE_BOUNDARIES` has never been
calibrated, and why S1 could only bump the classifier version rather than re-fit it. The jitter that
split §18.2's stay into three rows was never written down.

### 18.9.2 The instrument — `SegmentationReplay` + `SegmentationReplaySweep`

Pure `commonMain` replay over two corpora, plus a read-only sweep that feeds it from the store.
`RailRefinementSweep` is the precedent it is modelled on — it already reads every trace through
`TenshuClient` and decodes polylines — **minus every write**. A tool that measures the rules must not
become another thing that can change the data it is measuring.

- ⚠ Stored traces use the **stored raw `distanceM`** for the length arm and the **decoded geometry**
  for the extent arm. Neither substitutes for the other: recomputing length from a decimated path
  under-reports it, while extent survives Douglas–Peucker almost exactly because DP preserves the
  extremes extent is measured over.
- ⚠ The extent bar is read through the same `extentFloorFor` the live gate uses — extracted for this
  purpose. A bar computed in two places is a bar that drifts, at which point the replay quietly
  answers a question about a rule that is not the one shipping.
- ⚠ Undecidable traces (path too short after decimation) are **counted and reported**, never folded
  into "not rejected". An instrument that reports unknowns as passes is worse than no instrument.
- ⚠ Replay builds its segmenters with `OpenMoveJournal.None`, so a diagnostic run cannot corrupt the
  in-progress journey it is diagnosing.
- ⚠ "No raw-fix corpus yet" is logged explicitly — indistinguishable-from-silence would let an empty
  corpus read as "the corpus showed no change", and only one of those is evidence about S6a.

Output goes to `Diagnostics`, which Settings already renders with copy-to-clipboard — so a run is
readable on the device with no export, and without pulling Control's multi-hundred-megabyte store off
the phone.

### 18.9.3 The corpus — `RawFixJournal`

An append-only CSV of the fix stream, recorded **before** `segment()` (the corpus must be what the
platform delivered, not what survived clustering — anything downstream has already had the decisions
the replay wants to re-run applied to it).

⚠⚠ **It is the most sensitive artefact the app produces** — strictly more revealing than anything
capture stores, because it is the *un-generalised* record, before a dwell is rounded to a centroid
and before decimation discards the shape of a walk. Threat-model **A35**, with a posture deliberately
stricter than the capture opt-in it sits behind:

- **Its own consent**, off by default — accepting background location is not accepting a verbatim
  trace log. Consent is re-read **per fix**, not latched at construction, so flipping the switch off
  stops recording immediately.
- **Bound only in debug builds.** `timelineAndroidModule(debuggable = …)` omits the binding entirely
  in release, so the Settings section does not render and the journal degrades to `RawFixJournal.None`
  — unreachable *by construction*, not by a runtime check someone could later relax.
- **App-private, never synced, never uploaded, deletable** from the same screen that enables it.
- ⚠ An existing corpus stays **readable after consent is withdrawn**. Hiding it would be the worst
  outcome: the file exists and the one control that could delete it claims there is nothing to delete.

⚠ **Bounded by stopping, not rotation.** A ring buffer is the reflexive choice and the wrong one: a
window that silently slides makes a replay result unattributable to a period, and losing the hour you
were debugging is worse than "recording stopped, here is when".

⚠ Plain CSV, not the `MovementTracePolyline` delta codec — that codec is streaming and delta-based,
so it cannot be appended one fix at a time without rewriting the file, which is precisely what an
append-only journal must avoid. Text also means the corpus can be read by eye.

⚠ No write-temp-then-rename (unlike `JsonOpenMoveJournal`): that protects a record *replaced* in
place, where a torn write destroys the previous copy. Here every write appends one line, so a crash
costs a truncated final line, which the codec drops on its own.

### 18.9.4 What this does and does not settle

**Settles, from data already on the device:** how many stored traces the S6b extent gate would now
reject, with the extent, bar, mean accuracy and stored distance behind each verdict — i.e. §18.3's
falsifiable prediction, measured rather than inferred from row counts.

**Settles after recording for a while:** S6a end-to-end — dwell counts before and after, over a real
day, including the ±100 m case §18.8 could only argue about from first principles.

**Does not settle, and cannot:** everything S1 rests on — real wakeup cadence, battery, whether the
device honours batching, significant-motion wake. Forward-only, on hardware.

**Format impact: none.** Read-only projection + an app-private debug file; no stored row changes
shape (`SchemaShapeBaselineEnforcementTest` green), so no version bump and no relay redeploy (HR #9).

---

## 19. S2 + S3 as built — leg splitting and step cadence (2026-08-05, device-free)

Shipped together because they are one loop: the splitter cuts on a mode boundary, and cadence is
what makes the mode reliable enough to cut on.

### 19.1 One rule, two consumers — `LegMode`

`classifyMode` (whole leg) and `ModeSplitter` (per boundary) read the **same** function. That is not
tidiness: two rules would let a journey be cut at a boundary the classifier then disagreed with,
producing a leg labelled with a mode its own split denies.

**Cadence decides pedestrian vs wheeled; speed decides within each.** The reliable discriminator goes
first. ⚠ **The decisive fact is that cycling produces no steps** — a cyclist and a runner at 5 m/s are
identical to GPS and unmistakable to a pedometer.

- ⚠⚠ **`null` steps ≠ `0` steps**, and every layer preserves the difference: `CapturedFix`,
  `StepCounter`, the CSV corpus, `LegMode`. `0` is evidence of wheels; `null` is no evidence, and
  falls back to the pre-S3 bands **verbatim** — a device without a pedometer loses nothing.
- ⚠ **Zero steps at walking pace is still a walk.** A phone in a bag misses steps, so only the speed
  band may promote to a vehicle. The asymmetry is deliberate: reading a walk as a bike ride is a
  visible lie, reading a bike ride as a walk is caught upstairs.
- ⚠ **A slow leg votes for nothing** — below `INDETERMINATE_SPEED_MPS` it abstains and inherits, which
  is also what stops a red light from manufacturing a boundary.

### 19.2 S2 — the split is a pure post-pass at the flush boundary

`flushMove` became plural. `MovementSegmenter`'s streaming state is untouched: it still decides *when*
to flush; `ModeSplitter` decides *how many moves* that flush yields. A mode change is only visible in
retrospect, and the online state machine (journal recovery, dwell confirmation, content addressing) is
the hardest-won correctness in the file — so nothing there moved.

- ⚠ **Runs share their boundary fix**, so summed distance equals the whole path's. Cutting *between*
  fixes would silently delete the boundary leg.
- ⚠ **Unviable runs merge back, never drop** (forward into the previous run; a leading one folds into
  its successor). Splitting must never shorten a journey.
- Hysteresis of 3 voting legs; a brief slowdown cannot cut a ride.
- ⚠ `splitOnModeChange = false` restores pre-S2 behaviour exactly, which is what lets every S2 test be
  twinned in config with no test-only code path.
- Each run keeps its own `startMs` ⇒ its own `cap-move:$startMs` ⇒ recovery and idempotence untouched;
  each becomes a `MovementTrace` the rail sweep refines **independently**, which is the precondition
  §16.5's per-leg substitution was built for.

### 19.3 The §18.6 still-here veto — and the divergence it nearly caused

A fix that leaves the anchor radius while the pedometer counted **zero** steps did not walk out of it,
so the cluster stays open.

⚠⚠ **Bounded by `stillHereRadiusFactor` (2× the accuracy-scaled radius), and the bound is the entire
safety argument** — zero steps is equally what a driver, a cyclist and a train passenger report, so an
unbounded veto would hold one dwell open across a whole journey.

⚠⚠ **Caught during the build: the veto was first written into `MovementSegmenter.step` alone, and the
whole suite stayed green** — because every existing fixture carries `null` cadence, under which the
veto cannot fire. It would have diverged from `VisitDetector.detect` the moment a real device reported
a counted zero, silently, past a test whose *name* claims to pin the two together. Fix: one shared
`VisitDetectionConfig.holdsInCluster`, used by all three clusterings (the S6a lesson, re-learned one
slice later), plus a test that exercises the pin on step-bearing fixes.

### 19.4 ⚠⚠ Where cadence is persisted — and where it deliberately is not

**It rides with the raw-fix corpus (§18.9) and nowhere else.** Two independent walls block the
obvious homes, and both are worth knowing before anyone tries again:

1. **`MovementTracePolyline`'s codec is unversioned.** A fifth delta stream would make every
   already-stored blob decode as *silent garbage* — the reader would take the next point's latitude
   as this point's step count — rather than fail. Adding a stream requires adding a version marker
   first.
2. **A scalar `medianCadence` on `MovementTrace` is unrepresentable**: that type is an **operation
   parameter**, and *no type that appears as an operation parameter can gain a field* until overlay
   orchestration lands. This is the **fourth** confirmation of the rule `PlaceRole`, `SnappedRoute`
   (§16.3) and `MovementTrace` itself established.

So the verdict is persisted and its cadence input is not — **a knowing §12.1 deferral**, recorded
rather than quietly accepted. The escape hatch, when it is wanted, is a **sibling row** (the
`SnappedRoute` precedent), not a field. The debug corpus is the right home meanwhile: it has no schema
constraints and exists precisely to make thresholds like `PEDESTRIAN_MIN_SPM` fittable.

⚠ The corpus codec now accepts **4 or 5 columns** — a strict arity check would have silently dropped
every pre-S3 line, discarding exactly the historic data the journal exists to accumulate.

### 19.5 As built

`ACTIVITY_RECOGNITION` declared in `timeline-android` (⚠ undeclared, `TYPE_STEP_COUNTER` can still
resolve and then simply never deliver — no crash, no log). `AndroidStepCounter` batches at 60 s so the
AP wakes rarely; ⚠ the **first** reading after `start()` returns `null` rather than the boot total (a
boot total attributed to one leg reads as a marathon), and a backwards counter (reboot) re-baselines
instead of emitting a negative or huge delta.

**Verification:** 12 new tests, every negative twinned in config; the full gate
(`desktopTest jvmTest test`) green; `:timeline-android:compileDebugKotlin` green; HR #8 smoke gate
passed. **Format-neutral, confirmed not asserted** — `SchemaShapeBaselineEnforcementTest` green, so no
version bump and no relay redeploy (HR #9).

**NOT verified:** any of it on a real device. Specifically — whether the pedometer delivers under
batching while the FGS holds location, whether `PEDESTRIAN_MIN_SPM = 60` separates a real bag-carried
walk from a real bike ride, and whether the hysteresis of 3 legs is right at S1's vehicle cadence.
Those are the corpus's job, which is now recordable.

---

## 20. S4 as built — stitching tile-split rail, and the underground leg (2026-08-05, device-free)

The slice that finally makes §17's opening report — *train → metro → walk* — renderable. S2 was its
precondition: without leg splitting there was no metro leg to recognise.

### 20.1 S4a — rejoin what tiling split

MVT geometry is **clipped to its tile**, so one railway crossing four tiles arrives as four
`RailLine`s. `RailTileReader`'s endpoint dedup correctly suppresses the *same* line re-read from a
neighbour; it cannot merge *different* clipped fragments, and must not try.

That is invisible to `RailMatcher` (coverage is per point) and decisive for `RailSnapper`, whose
curvature recovery is *"walk the line's own vertices between two landings **on the same line**"*.
With fixes ~2 km apart and z14 tiles ~2.4 km across, consecutive landings routinely straddle a
boundary and get the straight chord §16.1 reserves for genuine line changes.

⚠⚠ **Degree-2 only.** A tile boundary and a **railway junction** are the same thing from geometry:
points where line ends coincide. Chaining at a junction would fabricate a path through a switch —
exactly the "no routing, ever" line §16.1 draws. So a join happens only where **exactly two** ends
meet; three or more (a switch, a station throat) leave every fragment alone. The cost is that a route
through a junction still chords; the alternative is confidently drawing the wrong branch.

⚠ A neighbour is **reversed** when that is how it joins — a tile encoder digitises each fragment
whichever way it likes, the same trap `RailSnapper` documents for its own walk. ⚠ Stitching is
lossless: it re-groups points, never drops or invents them (pinned by a test).

⚠ This gets *more* valuable as S1's shorter interval lands, not less: more landings mean more chances
for a pair to straddle a boundary.

### 20.2 S4b — the underground ride, recognised by its absence

⚠⚠ **The reframe is the whole slice.** "GPS is the wrong sensor underground, so we lose the leg"
inverts the evidence. A metro ride leaves a **positive** signature: the stream goes dark for minutes,
resumes kilometres away, at a speed no walk explains, with **both ends on subway alignment** — which
the production basemap already ships (`kind_detail=subway`). Every input already existed;
`RailRefinementSweep` has the geometry in hand at the moment it judges the trace.

⚠⚠ **It needs its own gate, because a coverage fraction is meaningless here.** Every other tier rests
on *what fraction of the path lies on track*; a dark leg has **almost no points to cover with** — that
is its defining property — so a coverage threshold would reject precisely the case it exists to catch.
The gate is **endpoint-anchored** instead: both bracketing fixes near subway track, and the gap
un-walkable.

⚠⚠ **Where the tier actually earns its keep — found by a test that failed for the right reason.** The
first fixture put its fixes *on* the line; the trace was promoted by the ordinary exclusive-coverage
rule, so the test proved nothing about the new tier. The real operating band is **50–150 m from the
alignment**: a metro rider's surviving fixes are taken at street level above a tunnel, which with GPS
error puts them *outside* `RailMatcher.DEFAULT_TOLERANCE_M` (50 m) and so invisible to the coverage
tiers, but inside `ENDPOINT_TOLERANCE_M`. Inside 50 m this tier is redundant; the band above it was
previously unreachable. That is now recorded on the constant itself.

Guards, all biased toward the status quo: **subway specifically** (heavy rail is above ground and
already covered; admitting it would let any outage beside a mainline promote a drive) · **both** ends
must anchor · a speed **floor** no pedestrian reaches · a speed **ceiling**, because a coarse network
fix or a clock jump can imply anything and *"the metro did 400 km/h"* is worse than *"unknown"* ·
promotion only when the dark legs are a **material share of DISTANCE** — deliberately not of time,
since the dark leg's time is exactly what is missing.

### 20.3 As built

`RailRefinement.refine` gains an optional `fixes` parameter — **separate from `path`, not replacing
it**: every coverage tier is coordinate-only by design and a dark leg is a *time* fact a lon/lat path
cannot express. Empty ⇒ the tier does not run, so every pre-S4 caller keeps its exact behaviour (and
that is what the promotion test's twin pins).

`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` **9 → 10**: both halves change what an already-refined
trace would now conclude, so every one is re-offered to the sweep — which is why that constant is a
version and not a boolean.

**Verification:** 7 stitcher + 8 dark-leg tests, every negative twinned; full gate
(`desktopTest jvmTest test`) green; HR #8 smoke gate passed. **Format-neutral, confirmed not
asserted** — no version bump, no relay redeploy (HR #9).

**NOT verified on a device**, and this slice needs it more than most: whether a real metro ride's
surviving fixes land inside `ENDPOINT_TOLERANCE_M`, whether `MIN_DARK_GAP_MS` = 3 min matches real
tunnel silence, and whether stitching actually closes the chords on a real regional-train trace.

> ⚠⚠ **Both gate questions are now ANSWERED against real data — and both answers were "no"** (§21,
> §25). `MIN_DARK_GAP_MS` never fires on a covered metro (largest gap 122 s), and
> `ENDPOINT_TOLERANCE_M` at 150 m rejected 3 of 11 real rides including the motivating one, so it is
> now **200 m**. The tolerance figure below is superseded; §25.4 carries the measured distribution.
The §18.9 replay is the instrument for the last one — it re-runs over stored traces.

---

## 21. S4 measured against the real journey (2026-08-05) — the dark-leg tier does not fire

**Method.** The reported trip (*a town → a city interchange by regional train → a metro ride → walk*,
shown in the UI as one **131.9 km / 1 h 43** row) was read out of the live desktop Control
store — a read-only snapshot of `objects.db`, 65 stored `MovementTrace` rows, polylines decoded with
the shipped codec. No fixtures.

⚠ First correction: the journey is **four stored traces**, not one — three `transit` legs plus a
`walk`. The single row is §15 collapsing the three transit legs at read time. Capture
had already split them (station stops), so §17.1's cause-2 (*"nothing splits a leg"*) did **not**
apply to this journey; it applies to journeys whose stops are shorter than `minDwellMs`.

### 21.1 ⚠⚠ Q1 answered — and the premise fails before the tolerance question

**The metro ride (interchange → metro station, idx 45→49 of the third transit leg) never goes dark.**

| gap | duration | distance | ≥180 s? | ≥800 m? |
|---|---|---|---|---|
| 45→46 | 60 s | 896 m | no | yes |
| 46→47 | **122 s** | 578 m | no | no |
| 47→48 | 61 s | 1 446 m | no | yes |
| 48→49 | 95 s | 259 m | no | no |

The largest gap while *moving* is **122 s**, under `MIN_DARK_GAP_MS` (180 s). The one ≥180 s gap in
the whole leg is the **8-minute platform wait** (251 s / 162 m), correctly rejected by the distance
gate. **`DarkLegDetector` finds zero candidates on the leg containing the ride.**

⚠⚠ **It fails at the gap gate, not the tolerance gate.** §20.2 anticipated the risk as *"will the
fixes land inside `ENDPOINT_TOLERANCE_M`"*; the real answer is that the question is never reached.
The city's metro has mobile coverage, so `NETWORK` keeps delivering underground — every 60–122 s.

**What the ride actually looks like is an accuracy collapse, not a silence:** the fixes across the
ride report `100, 100, 100, 100, 100, 600, 400, 93` metres, against `4–11 m` above ground either
side. **That** is the underground signature on a covered metro — a sustained run of coarse fixes
along a plausible alignment at a plausible speed — and it is a different gate from the one S4b ships.

> ⚠⚠ **PARTLY FALSIFIED 2026-08-05 by §25.1.** The observation is right, the framing is not. Reading
> the *whole* leg rather than the metro portion shows coarse fixes scattered throughout the
> above-ground train ride as well — nothing "collapses", because the stream is always a GPS/NETWORK
> mixture. The real signature is an interval containing **no GPS fix at all** while NETWORK keeps
> delivering. Sharper, and easier to test. "4–11 m above ground either side" describes the fixes
> immediately bracketing the ride, not the leg.

⇒ **S4b's expected value on a covered metro is ~zero as tuned.** It remains correct for genuinely
dark tunnels (regional rail, older systems, airplane-mode); it should not be described as *the* metro
detector until the accuracy-collapse variant exists. Do not simply lower `MIN_DARK_GAP_MS` toward
120 s — an ordinary above-ground sampling gap reaches that, and the tier would start promoting drives.

### 21.2 ⭐ S6a validated in the wild — the same journey contains §18.2's mechanism

The 8-minute wait on the interchange platform produced **no dwell**. Why: a ±100 m `NETWORK` fix
wandered **176 m** from the anchor, and the pre-S6a rule compared it against a bare **100 m** radius,
so the cluster broke and the remaining window was too short to reach `minDwellMs`.

S6a's accuracy-scaled radius is `2 × max(100, 100) = 200 m` ⇒ **176 m < 200 m, the cluster holds.**
§18.2 was argued from first principles; this is the mechanism observed in production data, and the
shipped fix covers it with 24 m of margin.

### 21.3 ⚠⚠ The fix stream is not time-ordered — 6.1 % of pairs go backwards

Across all 65 traces, **149 of 2 438 consecutive fix pairs have a negative time delta** (up to −9 s).
`MovementSegmenter`'s KDoc states *"Assumes fixes arrive in time order (live sensors do)"*. **They do
not.** The cause is structural: `activeProviders()` registers **GPS and NETWORK as two independent
listeners**, and their fixes interleave by arrival, not by timestamp.

Consequences, in order of severity:

- ⚠⚠ **CORRECTED in §22 — the ordering is *not* what inflates the distance.** This bullet originally
  attributed the ~53 % over-report (129.8 km for an ≈85 km route) to the out-of-order stream. Measured:
  monotonic ordering recovers **1.2 km of 45**, and *sorting* makes it **worse** (132.9 km). The cause
  is coarse fixes used as route vertices; see §22. The ordering defect below is real and worth fixing
  on its own terms — it just is not this symptom's cause.
- `LegMode.legsOf` and `classifyMode` skip `dt <= 0` legs, so out-of-order pairs are excluded from
  *classification* — accidental protection that happens to hold.
- Douglas–Peucker, `capPoints` and `MovementTracePolyline` all assume monotonic time.

**This is a capture-layer defect, not a tuning question**, and it is upstream of every distance figure
the app displays. The fix is to order (or merge) the two providers' fixes before `segment()`.

### 21.4 ⚠ Two more corrections to §17

- **Sampling is ~35 s, not 60 s.** Median interval per leg: 32–35 s; median step 655–923 m. §17.1's
  *"60 s ⇒ one point per ~2 km"* overstated the spacing by ~2.5×, because two providers each at
  `minTimeMs = 60_000` interleave to roughly half that. The direction of S1 is unchanged; its
  headline number was wrong.
- **Coarse fixes manufacture rail-only speeds.** 8/30, 16/50 and 14/51 fixes per leg report ≥200 m
  accuracy, and mixing them with metre-accurate ones produces legs of **68–79 m/s** — above
  `TransitKinematics.RAIL_ONLY_SPEED_MPS` (55 m/s), the band documented as *"effectively rail-only on
  the ground"* and treated as near-conclusive. Here the traces really were trains, so nothing was
  mislabelled; but a *drive* with one stray cell fix can reach that band the same way. The cruise
  figure is an 85th percentile, which is what has been absorbing this.

### 21.5 What this changes

Nothing already merged is wrong; S6a is *confirmed*. The open items this creates:

1. ~~**Order the fix stream** — the highest-value fix here~~ ⚠⚠ **Wrong: measured at 1.2 km of 45.**
   Both halves shipped in §22, but the one that recovers the distance is the *geometry* half, not the
   ordering half. Recorded rather than quietly amended, because the mis-attribution is instructive:
   the visible defect (a backwards timestamp) and the visible symptom (an inflated distance) looked
   causally linked and were not.
2. ~~**An accuracy-collapse variant** of the dark-leg tier (§21.1)~~ — **BUILT as S8 (§25)**, and
   the name did not survive the measurement: nothing collapses, GPS simply stops while NETWORK
   continues. S4b stays as the dark-tunnel case, now one recogniser of two inside
   `BlindIntervalDetector`.
3. Discount or exclude ≥200 m-accuracy fixes from *speed* derivation (§21.4), so the rail-only
   shortcut cannot be reached by measurement error.
4. Correct §17.1's sampling figure.


---

## 22. Fix-stream hygiene — what actually inflated the distance (2026-08-05)

Built off §21's measurements, and it **corrects §21's own diagnosis**.

### 22.1 ⚠⚠ The mis-attribution, stated plainly

§21.3 found that 6.1 % of consecutive fix pairs run backwards in time, and concluded that this was
why the reported journey read 131.9 km against an ≈85 km rail route. The candidates were then
measured against that journey rather than argued about:

| variant | distance | vs ≈85 km |
|---|---|---|
| as stored (what the UI shows) | 129.8 km | +53 % |
| **sort by timestamp** | **132.9 km** | ⚠ *worse than doing nothing* |
| drop out-of-order (monotonic) | 128.6 km | −1.2 km |
| **drop fixes with accuracy ≥ 200 m from the geometry** | **78.0 km** | ✅ |
| accuracy-relative *leg* rule (k × max accuracy) | 109–120 km | insufficient |

**Ordering was not the cause.** The proposed fix would have delivered 1.2 km of the 45 km error.

⚠⚠ **And sorting is actively worse than leaving the stream alone.** A stale coarse fix, sorted into
its timestamp position, lands in the *middle* of the path and turns one there-and-back excursion into
two long legs. Dropping it removes the excursion. This is pinned by
`droppingBeatsSortingOnTheShapeThatMotivatedThis`, so the intuitive "improvement" cannot be made
without deleting a test that explains why not.

⚠ **Why the accuracy-relative leg rule failed** (the shape S6a/S6b established, tried here first): a
coarse fix does not produce *small* spurious steps — it is **displaced off the true path**, so both
legs around it are long and both look like real travel. Shortening legs cannot fix a detour; removing
the vertex can.

### 22.2 The rule: presence versus geometry

⚠⚠ Coarse fixes are **not** discarded. §11.1's on-device finding is that indoors GPS often never
fixes while NETWORK does, and listening on both *is what makes an indoor place record at all*. So the
split is by **role**:

- **presence** — dwell clustering counts every fix, coarse included. Untouched.
- **geometry** — distance, polyline and speed use credible fixes only.

⚠⚠ **With a fallback, and the fallback is the measured case, not defensive coding.** Over the 65
stored traces a hard filter pushed **three below the ≥3-fix floor** and halved several genuine
journeys — those were recorded largely on NETWORK, where the coarse fixes *are* the route. A route
with known uncertainty beats no route, so `geometryPath` yields to the unfiltered path when filtering
would leave too little.

`maxGeometryAccuracyM = 200` is **reasoned, not fitted**: the median step between fixes is ~800 m
(§21.4), so a fix uncertain by 200 m already carries a quarter of the leg it sits in. ⚠ A 300 m bar
happened to land almost exactly on the true rail distance for this journey (85.1 km vs ≈85 km) —
**one journey cannot fit a constant**, so the tighter reasoned bar is kept and the coincidence is
recorded rather than adopted.

### 22.3 As built

`FixHygiene` (pure `commonMain`) + a `Flow<CapturedFix>.inTimeOrder()` operator applied in
`AndroidLocationCaptureSource` **after** the raw-fix corpus records and **before** `segment()` — the
corpus must keep the stream exactly as the platform delivered it, disorder included, since that
disorder is itself the finding. `toCapturedMoveOrNull` applies both steps to the **move path only**,
so distance, polyline and classification all improve consistently while dwell clustering is untouched.

⚠ `accuracyM == 0` means *not reported* and is kept — absence of a quality claim is not a claim of
poor quality, the convention `anchorRadiusM` and `confidenceOf` already follow.

**Expected effect on the reported journey: 131.9 km → ~78 km.** Not yet observed — the traces already
stored keep their recorded distance (it is a capture-time figure, §16.4's rule), so this shows up on
newly captured journeys. ⚠ The §18.9 replay cannot re-derive it either: it reads the *decimated stored
polyline*, whose coarse vertices are already baked in.

**Verification:** 8 new tests, the two counter-intuitive claims each pinned with a config twin; full
gate green; smoke gate passed; format-neutral (no version bump, no relay redeploy).

**NOT verified:** the real effect on a live capture — which needs one more journey.

---

## 23. S7 as built — Wi-Fi recognition says what it is doing (2026-08-05, device-free)

Closes §18.5: `WifiPlaceFingerprint.resolvePlaceId` had **two early returns that logged nothing**, and
they were the two states most likely to be the true one — consent off (the default) and an unusable
BSSID. F2 added `wifi_recognised`/`wifi_adopted`/`wifi_learned` so an on-device pass could answer
*"is this even working?"*, but only along the paths where it **does** work. The silent half was the
half that fires when something is wrong, which is exactly why the feature could look dead while
behaving as written.

### 23.1 ⚠⚠ The distinction that makes it actionable

`usable()` collapsed *no network*, *withheld identity* and *blank* into one `null`. Those are not the
same thing to a user, and only one is fixable:

| state | meaning | actionable? |
|---|---|---|
| `ConsentOff` | the opt-in is off | ✅ turn it on |
| `NoNetwork` | not on Wi-Fi | ❌ nothing is wrong |
| **`IdentityWithheld`** | on Wi-Fi, but Android returned `02:00:00:00:00:00` | ✅ **location services are off** |
| `Recognised` / `Adopted` / `Learned` | working | — |

⚠⚠ **`IdentityWithheld` was previously indistinguishable from "not on Wi-Fi"**, so the one state the
user could do something about was hidden behind the one they could not. No amount of visiting places
teaches anything in that state.

### 23.2 The posture, and three things it deliberately does not do

- ⚠⚠ **Consent is checked before the radio is touched.** An opted-out user's BSSID is never read —
  not even to render a status line. Pinned by a test that counts reads, not just outcomes.
- ⚠⚠ **`probe()` never learns.** `resolvePlaceId` binds as a *side effect of answering*, which is
  right when a real visit is being recorded and wrong when a screen is merely opened — a probing
  Settings screen that minted bindings would fabricate place associations from wherever the phone
  happened to be. `probe` shares the lookup and none of the writes; its test is twinned against
  `resolvePlaceId` so it cannot pass by the store being unreachable.
- ⚠ **Logging is transition-only**, mirroring `SchemaFenceStatus`. `resolvePlaceId` runs on every
  dwell confirmation, and for the default (opted-out) population a per-dwell line would crowd the
  bounded diagnostics buffer with the least interesting fact in it.
- ⚠ **`Unknown` is not a failure.** "We have not looked yet" must not read as "it is broken"; a
  Settings row that cried failure before capture had ever run would train the user to ignore it.

### 23.3 As built

`WifiRecognitionState` (sealed, `commonMain`) + a `StateFlow` on `WifiPlaceFingerprint`, surfaced as a
status line under the existing Wi-Fi toggle — the same screen that owns the consent, so the answer is
where the question is asked rather than behind `adb`. ⚠ Capture's foreground service and the UI share
one process, so this is the singleton's own state; no IPC.

Only `IdentityWithheld` is styled as an error. Everything else is neutral.

**No new permission, no storage, no sync, no schema** — a state value in memory plus a log line; §5's
privacy posture is unchanged. Format-neutral.

**Verification:** 10 tests, weighted toward the states that were silent (a suite that only checked the
working paths would reproduce F2's gap exactly); full gate green; smoke gate passed.

**NOT verified:** the status line on a real device — in particular whether a location-services-off
phone actually reports the redacted sentinel rather than `null`, which is the assumption the
`IdentityWithheld` branch rests on.


---

## 24. S5 — the production tile survey (2026-08-05): line identity is absent, stations are not

**Method.** `pmtiles extract` of a city-centre bbox from the **deployed** `de-bayern.pmtiles`
(1.8 GB, `tiles.service.tenshu.app`) by HTTP range requests → a 13 MB fixture → decoded with the
shipped `MvtDecoder`/`PmtilesDirectory`. 48 tiles at z14, 475 rail linestrings. These are the bytes
the app streams, not a fresh Planetiler build.

⚠ The survey lives as `ProductionRailSchemaSurveyTest` (`:ui-map` desktopTest) and **skips** when the
fixture is absent, so a clean checkout stays green while the survey stays re-runnable. The one
command is in its KDoc. ⚠ A skip is honest here in a way it would not be for a behavioural test: it
asserts a fact about an external dataset, not about our code.

### 24.1 ⚠ First, a flaw in the survey's own first draft

The initial verdict asked *"is `ref`/`network` present on a rail-bearing layer?"* and answered **yes**
— which is true and useless. Those keys are in the `roads` layer's key set because **road** features
carry them (motorway shields: `shield_text`, `network_1`, `network_2`). The question has to be asked
**per feature**, split by `kind_detail`. Restated that way:

| kind_detail | features | `ref` | `name` | `network` | `route` |
|---|---|---|---|---|---|
| `rail` | 306 | 216 | 73 | **0** | **0** |
| `subway` | 93 | **0** | 10 | **0** | **0** |
| `tram` | 71 | 4 | 14 | **0** | **0** |

### 24.2 Verdict A — line identity is NOT reachable ⇒ the overlay stands

`network` and `route` are **zero on every rail feature**. And the `ref` values that do exist on heavy
rail are **`5500`, `5525`** — German *VzG line numbers* (infrastructure route ids), with `name` values
naming a city pair: **corridor identity, not service identity**. Nobody has ever waited for
train number 5500.

⚠⚠ **Subway carries `ref` on 0 of 93 features** — the exact thing the original report asked about
("can we say which metro line?") is the case with the least data.

⇒ **§17.6's per-region OSM transit overlay is confirmed as the only route to a service line name.** That
decision was previously reasoned from the demo archive; it is now measured on production.

### 24.3 ⚠⚠ Verdict B — station POIs DO exist, and §13.2a was wrong to generalise

The production `pois` layer carries `kind=station` **with names** — the city's main station, a
central metro station — plus `platform`, `tram_stop`, `railway`, `bus_station`, `bus_stop`. 186 distinct
`kind` values in one small bbox.

§13.2a concluded from the Firenze demo archive that there are *"no railway-station POIs. Anywhere"*
and that *"no amount of a bigger archive changes it — this is a schema-level absence, not a sampling
artefact."* **That generalisation is false.**

⚠ And the obvious explanation is also wrong: it is **not** a zoom clip. The station POIs sit at
**z14**, and the demo archive's own header declares **z12–14** — it covers that band. The likely cause
is that the two archives are **different Protomaps profile vintages**, which the codebase already has
independent evidence for: `RailMatcher.railKindOf` exists precisely because the demo tags rail
`railway=<value>` in a `transit` layer while production uses `kind=rail` in `roads`. A profile that
moved rail between layers can equally have gained station POIs. **Fact certain, cause reasoned.**

### 24.4 What this changes

1. **T2 (station endpoints) is revived.** It was declared DEAD on the demo survey and replaced by T2′
   (stop-on-rail). T2′ stays useful, but station POIs are now available to corroborate it.
2. ⭐ **It gives §21.1's unbuilt accuracy-collapse metro detector a much better anchor.** §20.2 anchors
   a dark leg to *subway alignment*; named stations are strictly stronger — "the fixes went coarse
   between **Station A** and **Station B**" is a far tighter claim than "near some subway line",
   and it survives the 50–150 m accuracy band that §21.1 showed is where real metro fixes land.
3. **⚠ A standing lesson**: `MvtRealArchiveTest` pinned the *absence* so it "could not quietly return
   to the design" — good instinct, wrong scope. It pins the **demo archive**, and its conclusion was
   read as a claim about the schema. A test that pins a fact about one dataset should say which
   dataset in its name or its assertions.

**No code changed** — a survey test plus doc corrections. No schema, no wire, no format, no HR #8
surface (nothing under `androidMain`).

---

## 25. S8 — the covered metro, measured before it was built (2026-08-05, device-free)

The tier §21.5 left open. ⚠⚠ **The design changed twice under measurement, and both times the
written premise was the thing that was wrong** — first §21.1's own account of the signature, then
the assumption that the tier would fire on the journey that motivated it.

**Method.** Same instrument as §21: a read-only snapshot of the live desktop Control store, 65
`MovementTrace` rows decoded with the shipped codecs, plus the S5 extract of the **deployed**
`de-bayern.pmtiles` decoded with the shipped `MvtDecoder`/`RailMatcher`. No fixtures — every number
below is production data through production code.

### 25.1 ⚠⚠ It is not an accuracy *collapse*. One provider stops; the other carries on

§21.1 described the signature from the metro portion alone — `100, 100, 100, 100, 100, 600, 400, 93`
against `4–11 m` "above ground either side". Reading the **whole** leg falsifies that framing: coarse
fixes are scattered throughout the above-ground train ride too, alternating with metre-accurate ones
almost every other sample.

Nothing collapses. `activeProviders()` registers GPS and NETWORK as two independent listeners
(§21.3), so the stream is *always* a mixture — GPS at 4–28 m, NETWORK at 44–800 m. What happens
underground is that **GPS stops delivering and NETWORK does not**. The signature is therefore not a
distribution shift in accuracy; it is an interval containing **no GPS fix at all**, which is a
sharper and much easier thing to test.

⇒ ⭐ **A dark leg and a covered metro are the same fact — an interval in which we do not know where
the user is** — so S8 is not a second tier beside S4b. `DarkLegDetector` becomes
`BlindIntervalDetector` with two recognisers over one set of gates: `SILENT` (no fixes; the genuinely
dark tunnel) and `COARSE` (fixes arrive, none of them GPS; the covered metro). Duplicating the
distance floor, speed band, subway anchor and dominance rule across two objects would have let them
drift apart, and they are the same rules for the same reason.

### 25.2 ⭐⭐ The corpus contains one metro hop eleven times in eight days

Sweeping every trace for intervals with no GPS fix surfaces two cleanly separated populations.

**The commute.** Eleven intervals of 4.8–16.2 minutes, displacement **2 336–2 966 m**, implied
4.2–11.2 m/s, brackets repeatedly at the same two places. The two stations are 2.55 km apart. This
is one metro hop, recorded over and over, in both directions.

**Traces where GPS simply never worked.** Motorway drives of 29–82 km with blind intervals of 24–61
minutes. These are not underground; they are a phone that never got a GPS fix at all.

The two are separated by the share of a trace's fixes that are GPS: **42–57 %** for the metro
population against **0–23 %** for the other. That gap is the guard, and it is the *same distinction*
`RailEvidence` already draws one layer up: **"we could not look" is not "we looked and found
nothing"**. A trace that was blind end to end proves nothing by being blind in the middle.

⚠ The bar at 0.35 is worth trusting for a reason beyond sitting in that gap: after §22's hygiene
shifts every figure (below), the populations move to **60–82 %** and **0–33 %** — and 0.35 still sits
inside the new gap. A threshold that survives a pipeline change unaltered is measuring something
real.

### 25.3 ⚠⚠ Measuring across the platform wait rejects the tier's own motivating case

The reported journey's blind interval runs about fifteen minutes. Straight-line displacement over that
whole window implies **2.9 m/s** — *below* the 4 m/s floor S4b set to exclude walking. Eight of those
fifteen minutes are standing on the platform.

A `SILENT` interval has no interior points, so its endpoint figure is all there is. A `COARSE` one
does: the cell fixes are poor positions, but they are positions. Trimming to the sub-span that
actually moved puts the same ride at **7.5 m/s**. So the detector reports the *travelling* span, not
the bracket span — and the two kinds consequently carry different ceilings, because they are
different estimators: a trimmed figure is tight enough to bound at metro speed (15 m/s), a diluted
one is only a lower bound and would reject long journeys for having paused.

### 25.4 ⚠⚠ 150 m rejected the very journey that prompted the tier

§20.2 anticipated exactly one risk — *"whether a real metro ride's surviving fixes land inside
`ENDPOINT_TOLERANCE_M`"* — and §21 could not answer it because the gap gate failed first. Now it can.
Measured against production subway geometry, the real brackets sit

`1, 1, 2, 3, 4, 7, 8, 9, 81, 116, 157, 167, 191 m`

from subway alignment. At **150 m, three of eleven fail — including the reported journey, at 167 m.**
The tolerance moves to **200 m**. The nearest thing that must stay rejected is a drive bracket at
**905 m**, so this keeps a 4.7× margin and costs no measured precision. ⚠ The one candidate inside
200 m that is *not* promoted (149/151 m) is excluded by the GPS-share guard, not by distance — two
independent guards, which is the posture every tier here takes.

### 25.5 ⚠⚠ §22's geometry filter degrades this signal — and the "presence vs geometry" split needs a third role

§22 established that a fix has two roles: **presence** (dwell clustering keeps every fix) and
**geometry** (route drawing keeps only credible ones). `CapturedMove.fixes` is the *filtered* path,
and that is what gets encoded into the stored polyline — so from §22 onward the ≥200 m fixes are gone
before this tier ever sees a trace.

Re-running the sweep through the shipped hygiene shows the cost: eleven metro intervals become eight,
and three relabel candidates become one (plus one outside the surveyed region). **The accuracy
pattern is neither presence nor geometry — it is metadata about the sensor**, and a filter designed
for drawing routes discards it.

⚠ The tier survives only because typical NETWORK accuracy (~100 m) is *below* §22's 200 m bar, so the
interval's defining property — fixes present, none of them GPS — is preserved even though its
extreme members are dropped. That is luck in the sense that nothing was designed to preserve it.

⚠⚠ Storing the unfiltered stream is **blocked by §19.4's two walls** (the polyline codec is
unversioned; `MovementTrace` is an operation parameter and cannot gain a field), so this is recorded
as a knowing limitation rather than fixed. If a future slice needs the raw accuracy trace, the escape
hatch is the same one cadence took: a sibling row, or the debug `RawFixJournal`.

### 25.6 ⚠⚠ The tier is worthless unless it may contradict a *walk*

The traces where a metro ride is the whole journey are classified **`walk` or `run`** — median leg
speed over a blind middle comes out low. The traces where this tier fires and the verdict is already
`transit` are the long commutes, promoted by heavy-rail coverage long before the metro leg matters:
their blind interval scores **0.01–0.10** of trace distance, while the mislabelled short trips score
**0.60–0.80**.

⇒ Scoped to `drive`/`transit` baselines, as `refine` has always been, **this tier would change no
verdict in the entire corpus.** So `refine` now admits a pedestrian baseline — for this tier and no
other.

⚠⚠ That is not a loosening of the "a walk beside a railway is still a walk" rule; it is that rule
holding. The coverage tiers rest on **proximity**, which is genuinely not evidence about a walker. A
blind interval rests on its own **kinematics** — 2.5 km in five minutes — which no pedestrian
produces whatever they are standing next to. The guard that keeps this honest is that a pedestrian
trace is returned *unstamped* when the tier does not fire, so the sweep never records a verdict it
did not reach.

⚠ The sweep's candidate query widens with it, and most traces are walks. `BlindIntervalDetector.detect`
is therefore **tile-free**: the cheap kinematic half decides candidacy locally and only survivors
spend a tile-fetching op. That is the reason the map half is a separate call rather than a parameter.

### 25.7 As built

`BlindIntervalDetector` (replacing `DarkLegDetector`) + `RailRefinement.refine` pedestrian arm +
`needsRefinement(classification, fixes)` + the sweep passing decoded fixes to it.
`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` **10 → 11**, so every already-refined trace is re-offered.

**Verification:** 16 detector tests, every negative twinned and every precondition asserted (two
fixtures failed for the right reason and were corrected against the real proportions — measuring
across the wait, and a stationary tail handing the trimming search an in-band sub-span). Full gate
`desktopTest jvmTest test` green; HR #8 smoke gate passed. **Format-neutral** — a classifier version
is a value in an existing field, no shape moved, no relay redeploy (HR #9).

⚠ **Not committed: the calibration probe.** The bracket measurements above were taken with a
throwaway test carrying real coordinates from a real commute. This repository is
public; the aggregate distances are here and the coordinates are not, and any re-run should stay
local for the same reason.

**Not verified on a device.** What the corpus predicts, once the sweep runs at the next Timeline
launch: the short trips whose middle is a metro ride re-label from `walk`/`run` to `transit`, and
nothing else moves.

---

## 26. The on-device diagnosis (2026-08-10) — four live defects, none of them missing capability

§25 closed by predicting what would happen "once the sweep runs at the next Timeline launch". It had
not run. Reported journey: *office → walk → metro station → metro ride → interchange → regional
train → onward station → bike home*, recorded as `Walk · <car showroom> · Walk · «» · Information ·
Drive 22 km · Walk 463 m · Drive 59 km · Walk · Run · Run · <home street>`.

⚠ Places below are named by role, for the reason §25 already gives — this repository is public.

⭐⭐ **The §17 shape a third time: every tier was built and correct, and four defects *upstream* of
all of them kept them from running.** When several shipped slices all no-op on the case that
motivated them, the fault is above them, not inside any one.

⚠ **Read these two artefacts first.** `files/diagnostics/timeline.log` (the `Diagnostics` stream) and
`files/raw_fix_journal/fixes.csv` (§18.9's corpus, debug builds only) are on the device and named two
of the four defects outright. Control's store came off with
`adb exec-out run-as app.tenshu.control.android cat …/objects.db` (⚠ plus `-wal`; the WAL held 155 MB
of the recent truth) and decodes with the §25 Python port of the `DbEntry` codec.

### 26.1 The rail sweep read an empty store and called it an answer (the largest cause)

Three days of capture, one `rail_sweep_start` line: **`0 candidate(s) of 0 trace(s)`** against a
store holding **15** traces. `RailRefinementSweep.readTraces()` ended `onFailure = { emptyList() }`,
so a failed IPC read was indistinguishable from an empty store — ⚠⚠ **exactly the distinction
`fetchEvidence` already draws one layer down** (`RailEvidence.Unavailable` is deliberately not
`Surveyed(emptyList())`), applied to the rail-lines read and never to the trace read above it. And
`RailRefinementTrigger` fired immediately in `onCreate` while the sweep's first act *is* that IPC
read, so it raced the proxy binding; the live instance ran one second after `MY_PACKAGE_REPLACED`,
in the same second as `fgs_start_refused`, and its one-shot-per-launch was spent.

⭐ Relaunching the app, unmodified, gave `5 candidate(s) of 15`, `refined 5, snapped 4` — including
`walk→transit cov=1.0 kinds=[subway]`, **S8 firing exactly as designed**. Fix = `SweepResult.tracesUnreadable`
+ await `TenshuClient.proxyConnected` + retry while unreadable. ⚠ Both halves are needed:
`ProxyConnectivity`'s KDoc records that a connected proxy still refuses every call under schema skew,
and a refusal has no edge to wait for. ⚠ A genuinely *empty* store is not retried — that is settled.

### 26.2 `ACTIVITY_RECOGNITION` was declared and never requested — so S3 has never run

`granted=false` with **no `USER_SET` flag** = never denied, never asked, on every device that has run
capture. `AndroidStepCounter`'s KDoc predicted it verbatim: sensor non-null ⇒ `isAvailable()` true ⇒
`start()` succeeds ⇒ the listener never fires ⇒ `stepsSinceLast()` is `null` forever, silently. ⇒
`LegMode` takes the no-pedometer branch, so **the "cycling produces no steps" discriminator that is
the whole point of S3 has never once run in the field**. Measured: a bike ride at 12–13 km/h records
as `run`, because `cycle` needs ≥ 4.0 m/s ≈ 14.4 km/h from speed alone — ordinary city cycling is
structurally unreachable without cadence. ⚠ `ACCESS_BACKGROUND_LOCATION` is likewise never requested —
**and §26.6 concludes it should not be: it is not needed, and has been dropped from the manifest.**
⚠⚠ Fixing it exposed a trap: the result callback read `grants[ACCESS_FINE_LOCATION] == true`, but the
map only carries *requested* keys, so once location is held and only activity is outstanding that key
is absent — and the old test read the absence as a refusal and switched capture **off**.

### 26.3 Provider duplicates collapse the median, and `walk` is unreachable by the transit tier

GPS and NETWORK are two listeners, so a position arrives twice a second or two apart. Measured over
2 587 real fixes: **33 % of consecutive pairs are ≤ 4 s apart with a median displacement of 0.0 m**;
the duplicate cluster ends at ~6 s and real motion appears from 10 s (109.6 m median). Since the
verdict is the **median** of per-leg speeds, one such pair per real leg is enough — **60–93 % of legs
under 0.05 m/s** on three real transit legs, median 0.00–0.14 m/s for journeys covering kilometres ⇒
`walk` ⇒ and ⚠⚠ `refineWithTransitKinematics` **only ever promotes `drive`**, so a `walk` verdict is
out of the transit tier's reach entirely. Fix = `LegMode.MIN_LEG_MS` 8 s, absorbing spanned fixes
(no distance lost); cadence sums across the span and a `null` inside it propagates rather than
counting as zero — a manufactured zero is evidence for *wheels*.

⚠⚠ **Not the sampling change §17.2 warns about.** `MODE_BOUNDARIES` are physical, not fitted to our
medians, and the duplicates biased measurement *away* from them; corrected figures move toward
physical truth in every corpus case (walk 0.66 → 1.09, bike 0.11 → 3.41, train 12.7 → 17.7) and no
trace changes bucket in the wrong direction.

### 26.4 Naming: a station could never win, and a notice board could

⚠ Naming is `PhotonGeocoder` (OSM), not the tiles. At the interchange's fix komoot returns
`information/board` **"Information" at 50 m**, a `railway` feature named for the station at 61 m, and a
`railway=stop` at 70 m. Two independent causes: `railway` is not in `VENUE_OSM_KEYS` so a station
could never win the venue tier; and with no venue qualifying, ranking fell through to *nearest
labelled feature of any kind*. ⚠ Several offenders carry a venue key — **a vending machine is
`amenity`** — so `!isNonPlace` had to become part of `isVenue`, not a later filter. ⚠ The station
test is on the osm **value**: `railway` also tags the rails, and a line named after two cities is not a place.
⚠ Street furniture is **demoted, never dropped** — if it is all Photon offers, its name beats none.

⚠⚠ **The metro-station dwell is not fixable by ranking and this is measured, not assumed.** From its 300 m-accuracy
fix a `limit=50` reverse query returns **no** `railway`/`public_transport` feature at all — Photon's
reverse response is capped at ~70 m and the station is further than that from a cell centroid. The
venue tier (P3b) is what promotes a `shop=car` showroom at 14 m over the nearest feature, which is
*literally named* after the metro station at 4 m. ⇒ **The real fix
is accuracy-gated naming** — a 300 m fix has no business naming a venue 14 m away — and §18's
accuracy-scaled radius was applied to place *identity* and never to *naming*. Blocked on the
reverse-geocode **operation not carrying accuracy** (an op-contract change), so it is its own slice.

### 26.5 What is fixed, what is not

Shipped: 26.1 · 26.2 · 26.4 partially · 26.3 — four independent changes,
no schema/wire/format movement, HR #9 untriggered.

⚠ **Honest residual.** 26.3 fixes no symptom by itself; it makes the classifier's *input* honest. The
bike still reads `run` until cadence exists (26.2), and the underground legs still measure ~0 —
**correctly**, because the NETWORK position is genuinely frozen at a cell centroid, which is why the
answer there is `BlindIntervalDetector` via the sweep (26.1) and never a speed threshold. The reported
metro leg and the spurious mid-train `walk` remain un-refinable: at 2–5 stored points they cannot satisfy
`MIN_COARSE_FIXES`/`MIN_COARSE_INTERVAL_MS` against a 3-minute trace, because the dwell split cut them
too short. Still open: accuracy-gated naming (26.4), §18 dwell
fragmentation (one 27-min interchange stay = 2 rows, home = 3), and the on-device pass of 26.1 in its
exact failing scenario (a package replace with capture on and traces present).

⚠ **Also observed:** the smoke gate *launches* Timeline but does not **build** it, so it gates a stale
APK; and a `move_recorded` batch is written at flush (all six evening legs at one second, after
arriving home), which is where S2's post-pass produced the drive/walk/drive split.

### 26.6 `ACCESS_BACKGROUND_LOCATION` — dropped here, and RE-INSTATED in §30 (2026-08-10)

> ⚠⚠⚠ **This section's conclusion is WRONG and is superseded by §30.** Its measurements stand; its
> claim to have enumerated every state does not. It reasoned that the states are exhaustive — FGS
> alive (fine location suffices) or process dead (no permission helps) — and missed the third:
> **process alive, app backgrounded, FGS being restarted**. In that state Android denies the FGS the
> *location type* unless background location is held, so capture cannot come back after a kill. Read
> §30 before acting on anything below.

§26.2 listed it beside `ACTIVITY_RECOGNITION` as "likewise never requested". ⚠⚠ **That framing was
wrong, and the two are not alike.** Activity recognition was *needed and silently missing*. Background
location is **not needed at all**, and declaring it was pure cost.

**Why fine location suffices.** A foreground service with `foregroundServiceType="location"` counts as
*"while using the app"*, so `ACCESS_FINE_LOCATION` plus a running FGS gives continuous capture with the
app backgrounded and the screen off. Nothing in the code ever read the background permission — it
appeared only in the manifest and in one `MainActivity` comment calling it "a later refinement". ⚠ Note
`CaptureRearm.hasLocationPermission` checks **fine location only**, so the `watchdog_no_permission`
breadcrumb was never about background access.

**Measured on the real phone, not reasoned:**

| observation | result |
|---|---|
| fixes 01:00–05:00, screen off, backgrounded, permission **ungranted** | **122** |
| `am kill` on the FGS process | **refused** — the FGS protects the process from memory-pressure kills |
| process killed outright (`am crash`) | new pid + FGS back in **< 5 s**, `START_STICKY`, restart is the *system's* so the API-31+ background-FGS-start restriction never applies |
| after that recovery: journal growth over 3 min | **+10 fixes** (~30 s apart), focus on the launcher, importance `(fg-service)`, background location still `granted=false` |

⭐ **The states are exhaustive, which is what makes this a decision rather than a guess.** Either the
FGS is alive — fine location suffices — or the process is dead, and no permission helps something that
is not running. There is no reachable third state, so there is none in which this permission changes
the outcome.

⚠ **What it does *not* address, and is often confused with:** `fgs_start_refused` is the Android 12+
**foreground-service background-start** restriction — a separate mechanism with its own exemption list
that background location is not on. Its mitigations are the battery-optimisation exemption and the
§11.4 boot/package-replace receivers, both already shipped; the device log shows the `BOOT_COMPLETED`
route succeeding where the `MY_PACKAGE_REPLACED` route was refused.

**It would come back only if capture read location without a live FGS** — geofence transitions
(`GeofencingClient` needs it on API 29+) or a background worker taking a fix. §17's FGS verdict
explicitly rejected both in favour of keeping the service up permanently and adapting the request
inside it, and §18's "dwell exit must not depend on the fixes S1 stops requesting" is answered by
`TYPE_SIGNIFICANT_MOTION`, a **sensor** — no location permission of any kind.

⚖️ **Why removing beats leaving it declared:** declaring background location obliges a Play Console
background-location declaration and review on the listing. Paying that for a permission with no
behaviour, which some OEMs then surface as an "Allow all the time" toggle that does nothing, is the
worst of both ends. ⚠ If it is ever genuinely needed it must be requested **separately, after fine
location is held** (Android suppresses the dialog otherwise), from a user-initiated setting with
prominent disclosure — never bundled into the capture toggle.

---

## §19 — the BSSID branch had no distance guard (as built 2026-08-10)

Found chasing a live report: an unbroken stay at a user-named office rendered as **office · café ·
office**, the café ~556 m away and named by hand months earlier.

⚠⚠ `WifiPlaceFingerprint.resolvePlaceId` tested distance on **two of its three branches**. The SSID
branch checks `SSID_VENUE_RADIUS_M`, `nearestKnownPlace` checks its own radius — and a bound BSSID
was returned **unconditionally**, from any distance, outranking a perfectly good coordinate. The
archetype is a phone hotspot: one radio, learned at a café, carried everywhere afterwards.

**As built.** A bound AP is now distrusted when the dwell's coordinate can be *positively
established* as farther than `VENUE_NETWORK_RADIUS_M` (500 m, the number the SSID branch already
used — one constant for one question). The state is reported as a new
`WifiRecognitionState.MovedNetwork`, so Settings → Diagnostics names it.

⚠ **No re-learn on that path.** Rebinding a travelling AP to wherever you happen to be is how it
would erase a real venue's fingerprint and then claim the next place too. Distrust it here, leave
the binding alone, let the coordinate answer.

⚠⚠ **The predicate is `knownToBeFarFrom`, not `!near(...)`, and that distinction is the guard.**
`near` answers "is it close", for which *no coordinate* and *far away* are both false — so the first
cut read absence as distance and disabled AP recognition for every caller that passes no coordinate.
That is exactly the GPS-drift correction this branch exists to provide, and the existing
`learnsANewNetworkThenRecognisesItDespiteGpsDrift` caught it. **Unknown is not elsewhere** — the same
rule `timeline-photos.md` §11.4 states for photo coordinates.

⚠ **This is not established as the cause of the reported symptom.** The other candidate is §18's
accuracy-blind clustering: a cold-start dwell whose own centroid lands in a neighbouring cell that
already holds a named place. Both remain open; the `MovedNetwork` breadcrumb now distinguishes them
on the next occurrence. ⚠ And note the margin: at 556 m a 500 m bound clears it by 56 m. A café
400 m away would still be claimed — this is a sanity bound, not a precision one, and the near-
neighbour case is S6's job.

## 27. The underground leg — why it stayed `walk`, and the anchor that gives it away (2026-08-10)

§26.5 listed the metro hop as un-refinable. It is not; it was being looked for in the wrong place, with
a guard that is anti-correlated with the thing it was meant to detect.

### 27.1 The evidence is not in the move, and not in the store

| span | fine-share | coarse run | duration | travelling span |
|---|---|---|---|---|
| the **stored trace** (184 s) | 0.00 | 12 | 153 s | **0 m** |
| **dwell → move → dwell** (709 s) | 0.25 | 33 | 709 s | **2761 m @ 4.7 m/s** |

⚠⚠ **The move alone contains no travel at all** — its position is frozen end to end and its recorded
924 m is one cell-change jump at the boundary. The ride's evidence spans the two *platform dwells*,
which capture detects correctly and which no transit tier had ever examined. On a metro hop the
platform wait is not noise around the journey; it is most of the journey's duration.

⚠ And it cannot be recovered later: `geometryPath` drops the ≥ 200 m platform fixes as bad *geometry*
(correct, for distance — they are also the only evidence, which is §25's "presence vs geometry needs a
third role" arriving from the other side) and Douglas–Peucker decimates the rest, 8 fixes to 2 here.
⇒ the tier must run at **capture**.

### 27.2 ⚠⚠ A falsified idea, recorded so it is not re-derived

The first design measured `MIN_FINE_FIX_SHARE` over a **context window** rather than the interval, on
the reasoning that the guard asks "can this device see GPS at all?" — a property of the session, not
of the interval — and that a tunnel-to-tunnel hop is punished for being exactly what it is (0.25,
inside the drive band). Symmetric ±15 min looked decisive: metro 0.47 / 0.38, trains 0.16 / 0.22 / 0.24.

**It does not survive contact with capture, which can only see the past.** Trailing-only:

| window | 15 min | 20 min | 25 min | 30 min | 45 min |
|---|---|---|---|---|---|
| metro (1st) | 0.27 | 0.37 | 0.46 | 0.50 | 0.53 |
| **metro (2nd)** | **0.20** | **0.16** | **0.20** | **0.20** | **0.20** |
| worst train | 0.22 | 0.22 | 0.21 | 0.22 | 0.26 |

**No trailing window separates them at any size** — the second hop followed a train ride and inherited
its coarse stream, scoring *below* the trains it must beat. ⚠ The symmetric result was an artefact of
seeing the future. Do not re-derive this idea.

### 27.3 ⭐ The discriminator: the anchor stops moving

Underground the phone is pinned to one cell, so consecutive NETWORK fixes repeat **the identical
coordinate**; a train above ground hands off between cells continuously and its coarse fixes march.
Median displacement between consecutive coarse fixes:

| | metro (1st) | metro (2nd) | train 22 km | train 59 km | train 55 km | bike | walk |
|---|---|---|---|---|---|---|---|
| median coarse step | **0 m** | **0 m** | 482 m | 311 m | 360 m | 61 m | 48 m |

Categorical rather than marginal, and — the property that matters — it needs **no context window and
no view of the future**. It replaces the fine-share guard for this tier (which is why `UndergroundHop`
passes `minFineFixShare = 0.0`), refusing the same drives for a stronger reason.
⚠ **Median, not mean:** the one cell-change jump *is* the ride, and a mean would let it mask an
otherwise-marching stream.

### 27.4 As built

`UndergroundHop` (pure) + `MovementSegmenter` retaining a bounded `previousDwellTail` so the dwell
triple is reachable at flush + `MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND` (5). Promotion is
**kinematic only** — capture cannot reach tiles — and sits *below* `MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED`
so the sweep still revisits it and may **demote** it against subway alignment (the M9-3 rule that the
map tier is the one allowed to demote). Promote-then-verify, because the evidence is perishable and
the tiles are not.

**Verified by replaying the real raw-fix corpus through the shipped segmenter**, not only fixtures:
both metro hops now emit `transit` (v=5); the 22 km and 59 km trains stay `drive`, the bike stays
`run`, the city walks stay `walk`.

⚠ **Capture-time only** — it improves future journeys. Already-stored legs stay as they are unless a
replay path is built; the raw fixes they needed are gone.
⚠ n=2 metro hops in this corpus (§25 had 11 in the desktop store); the *margin* is enormous but the
*sample* is small, and a rider whose metro has no cell coverage at all produces a SILENT interval
instead, which this tier does not claim to cover.

### 27.5 The blank `city` — a shipped rule that had never once fired (2026-08-10)

⚠⚠ **Nine geocoded places on the real store, nine blank cities.** `ReverseGeocode` returned a bare
`TString`, so `ProxyReverseGeocoder` could only build `GeocodeResult(label = …)` and `city` /
`countryCode` defaulted to `""` on **every** lookup. The proxy tier runs first in the Android chain,
so nothing downstream ever saw a locality.

That silently disabled a feature that shipped: `PlaceLabelEnricher`'s confidence-aware coarsening
(4-B, `place-geocoding.md` §4.3) is `row.accuracyM > COARSE_LABEL_ACCURACY_M && result.city.isNotBlank()`,
and **its second conjunct was never true**. Its whole purpose is that a precise venue name from an
imprecise fix is *more* wrong than a coarse one — which is exactly the 300 m-accuracy metro-station dwell
labelled with a car showroom 14 m away.

⚠ The fields were never a Photon limitation: `PhotonProperties` has parsed `city` all along. The loss
was the op's return type, one layer further out.

**As built.** New `GeocodedPlace` composite (label + city + countryCode) and a new op id
`reverseGeocodePlace`. ⚠⚠ **A new id, not a redefinition** — op params and returns are positional
composites, so a client built against the old shape would parse a composite as a string and produce
garbage; with a new id it gets a typed `unsupportedOperation` instead (`core-schema-evolution.md`
§"Ops", the `RenewTaskLease` precedent). ⚠ The composite needs a client-module `type(…)` registration
with a `roFactory` or the read dies as an opaque `error/internal` — the `GetWorkoutStreamsRo` /
`RailLineGeometry` trap. ⚠ No service version bump and no baseline entry: an op-return composite is
never a stored row, so nothing is pinned and there is nothing to migrate.

⚠⚠ **Why the tests live at the egress, not at the enricher.** `PlaceLabelEnricherTest` already covers
coarsening and passed throughout — its **fake supplies a city the production path could not produce**.
A fake that models only the reachable-in-principle case hides exactly this class of defect, so the
assertion belongs where the value is really made.

⚠ Testing it through `MockEngine` was tried and dropped: `PhotonGeocoder` wraps its fetch in
`withTimeoutOrNull`, and under `runTest`'s virtual clock the scheduler fast-forwards past the timeout
while the engine is still answering, so every lookup returns `null` for reasons unrelated to the code
under test. `runTest { withContext(Dispatchers.Default) { … } }` works but throws away virtual time —
a workaround, not an idiom. The locality selection is now a **pure** `localityOf(features, lat, lng)`
tested directly, matching `PhotonGeocoderTest`'s own stated posture that the network path is a thin
wrapper.

⚠ Still open, and unchanged by this: the op carries no **accuracy**, so the naming ranking still
cannot know a 300 m fix has no business naming a 14 m venue (§26.4).

## 28. Naming the platform — a transit stop beats the city when the fix is coarse (2026-08-10)

§26.4 left the metro-station dwell labelled after a `shop=car` 14 m from a 300 m-accuracy
fix, and concluded that no *ranking* rule could fix it. That conclusion held; the fix is not a ranking
rule.

### 28.1 Two things changed the shape of the problem

⭐ **§27.5 made the coarse branch live.** With `city` finally crossing the op boundary, 4-B's
accuracy-coarsening fires for the first time, so the label at the metro station becomes **the city's name** — no
longer confidently wrong, but not where anyone was either. That reframes the question: the alternative
a transit stop has to beat is now **the city**, not a venue.

⭐ **The station is reachable, it was just never asked for.** An unfiltered reverse at that point
returns no railway feature at any `limit` (Photon caps around 70 m; the station node is 82 m away),
but `osm_tag=railway` returns the `railway/station` itself, named, at 82 m. The station was never ranked
badly — it was never *offered*. That is a second, tag-filtered request, so it is its own operation
(`NearestTransitStop`) asked only when the caller has already decided it needs one.

### 28.2 ⚠⚠ The gate is accuracy, and its placement is the whole safety argument

The stop lookup lives **inside** the existing coarse branch (`accuracyM > COARSE_LABEL_ACCURACY_M`).
A precise fix keeps its venue and never even spends the request. That matters because a distance rule
alone is not safe — measured across cities, ordinary cafés with a named stop in range:

| | ≤ 100 m | ≤ 150 m | ≤ 300 m |
|---|---|---|---|
| Paris | 38 % | 54 % | 85 % |
| Tokyo | 10 % | 30 % | 90 % |
| Berlin | 7 % | 14 % | 64 % |

A gate that fired on proximity would rename a large fraction of well-fixed city cafés after a station.
Inside the coarse branch it cannot: those dwells never reach it.

⚠ The search radius is the row's **own** `accuracyM` — a stop only answers for a fix if it lies where
that fix could plausibly have been — capped at `MAX_STOP_SEARCH_M` (300 m) so a wild fix cannot reach
across a district. ⚠ That cap is honest rather than principled: at 300 m the answer stops being
"inside the uncertainty" and starts being "somewhere in this part of town", and it should tighten when
a dwell-level transit signal exists (§28.4).

### 28.3 Verified against live Photon, at each real dwell's real accuracy

The metro-station dwell (300 m) took the station's name (80 m); a 100 m dwell that was a station stop
between two train legs took that station's name (6 m); a 94 m café dwell had no stop in range and fell
to the city; a precise (29 m) and an unknown-accuracy (0 m) dwell never asked.

⚠ `accuracyM == 0` means *not reported* and is not `> 80`, so an unknown-accuracy fix never triggers
this — the same convention the rest of the enricher follows.

### 28.4 What is still not done

⚠ **The trajectory is still not consulted.** §27's anchor rule made it available (the leg out of that dwell now
classifies as `transit`), and it is the signal that would let the radius tighten while *widening* the
rule for dwells genuinely bracketed by transit legs — §26's graded gate. It needs the moment/trace
neighbours plumbed into a sweep that today reads `Place` rows only.

⚠ The offline path (**B**, station POIs from tiles we already ship) would remove the second network
request and work underground. `NearestTransitStop` is deliberately shaped so that is a proxy-side swap
behind an unchanged op. **Built — §28.2b below.**

### 28.2b The offline tier: station names out of the tiles we already ship (2026-08-11)

Built as option **B**. `nearestTransitStop` is now **two tiers, tiles first**, behind the same op:
`TileTransitStopSource` reads the `pois` layer of the covering region; only on a miss does the
coordinate go to Photon.

⚠⚠ **The premise recorded in this document was false, and this is the correction.** §13.2a surveyed the
*bundled Firenze demo* archive, found no station POIs, and the conclusion propagated into
`RailMatcher.stopsOnRail`'s KDoc and §17.5 as **"the basemap emits no station POIs at all"**. Surveyed
against the **deployed** `de-bayern` cut, the `pois` layer carries them with names. Nine z15 tiles
around one plaza:

| kind | count | named? |
|---|---|---|
| `station` | 16 | yes — the plaza's station at 93 m |
| `stop` | 96 | yes |
| `platform` | 82 | yes |
| `tram_stop` | 43 | yes |
| `bus_stop` | 101 | yes |
| `subway_entrance` | 131 | **no — all 11 within 400 m are unnamed** |

The two archives are different Protomaps generations (`ui-map/ui-map-agent.md`), which is the same
reason production rail rides `roads` and not `transit`. ⚠ Both stale claims are corrected in place;
`MvtRealArchiveTest` is re-scoped to "*this bundled archive* has none", which is all a bundled-asset
test can honestly pin — **and its filter read `pmap:kind` only, so a production-spelled `kind=station`
would have slipped past the very test that exists to detect the change.**

**Two findings from that survey shape the code.**

⚠⚠ **Exact kind values, never substring matching.** A regex survey returned a **stationery shop**:
`station` is a substring of `stationery`, `fire_station`, `charging_station` and
`bicycle_repair_station`, and `stop` of `buffer_stop` (a lump of metal at the end of a siding).

⚠⚠ **Tiers before distance.** At that plaza a `bus_stop` sits at 52 m and a `stop` node at 90 m,
while the `station` is at 93 m — so nearest-first answers from the wrong node. This is the same shape
as the "Information" bug: distance alone is what loses. Ranking is `station`/`halt` → `stop`/`tram_stop`
/`platform` → rest, distance only within a tier. ⭐ Mutation-checked against the real archive: ranking
on distance alone returns `stop`, and the test fails.

**Why it is worth having when Photon already answers:**

- **No coordinate reaches a third party.** Every coarse dwell near a station currently goes to komoot's
  public instance. Egress becomes a byte range against our own tile CDN — and **none at all** once the
  region is installed. The tier order is therefore a *privacy* decision, pinned by its own test
  (`theTileTierAnswersAndTheNetworkIsNeverAsked` asserts the network was **not called**, not merely
  that the tile name won).
- **It answers with no network** — a tunnel, a plane, a dead SIM abroad.
- **No rate limit**, and no dependency on a courtesy service staying up.

⚠ It is not strictly better: coverage is whatever the catalogue holds, so outside a catalogued region
the network tier still runs. A tile miss is ignorance, not "no stop here" — which is why they compose
as tiers. ⚠⚠ Region resolution deliberately does **not** require an *installed* bundle:
`StreamingRegionSource` range-fetches an uninstalled one, which is exactly the traveller case.

⚠ **Bus is deliberately excluded.** The network tier filters Photon on `osm_tag=railway`, so admitting
bus would make one op answer differently in kind depending on which tier served it — and bus stops are
~6× denser than stations, so a coarse residential dwell would start being named after the stop on the
corner. One line to add if a real site ever needs it; none has.

⚠ Read at the **archive's deepest zoom**, not a constant: the demo archive stops at z14 and the
deployed cut goes to z15, and a feature appears in every tile at or above its own `min_zoom`, so the
deepest tile holds the most POIs. A hard-coded 14 would silently read a coarser tile than ships.

As built: `StopTileReader` + `PmtilesTileWalk` (the tile walk extracted from `RailTileReader` — the
part worth having exactly once is the two-pass leaf prefetch, which degrades *silently* when wrong) in
`:vector-tile`; `TileTransitStopSource` + the `TransitStopTiles` seam in `:map-region-service-proxy`.
No op, wire, schema or format change; no threat-model delta (egress moves from a third party to our
own CDN, strictly narrowing disclosure). Verified against the live archive by
`ProductionStopPoiTest` — two real stations both named, and nothing invented in a park.

## 29. One stay, one row — the open dwell had no durable record (2026-08-10)

⚠⚠ **First, a correction to §26.5.** It said "one 27-min interchange stay = 2 rows". That is true of the
*store* and false of the *timeline*: `mergeConsecutiveVisits` folds those two fragments into a single
27-minute row, which is exactly what the original report described. The projector was never asked
before the claim was made. §18's merge machinery is working.

### 29.1 What is actually fragmented, and why

Measured on the device over one working day: **eight `dwell_ongoing` events, one `dwell_closed`.**

```
app_start → fgs_started → dwell_ongoing (+5 min) → ✗ process dies, no close → app_start → …
```

A dwell is emitted twice by design — on **confirmation** (`ongoing = true`, duration ≈ `minDwellMs`)
and on **departure** (the real duration). A process killed in between therefore leaves a row **stuck
at five minutes**; and the next process starts with no anchor, so it mints a fresh
`cap-visit:<placeId>:<arrivalMs>` and a **second row for the same stay**. Three 5-minute office rows
for one continuous day at one desk, and neither the gap rule nor the place rule can merge them —
they are 90+ minutes apart, and they *should* not be merged by widening a gap constant.

⭐ **The asymmetry is the bug.** The open **move** has survived process death since M3
(`OpenMoveJournal`, `restoreOngoingMove`); the open **dwell** never had an equivalent. Two halves of
one state machine with different durability.

⚠⚠ **Honest attribution:** that day's figures are inflated by the debugging session — 7 of its
restarts are `MY_PACKAGE_REPLACED`, i.e. installs. On an ordinary day the ratio is 11 confirmed / 10
closed. The mechanism is real and worth fixing (ColorOS kills are routine, §11.4), but its measured
frequency that day is an artefact of the measurer.

### 29.2 As built

`MovementSegmenter` takes a second journal for the open cluster, and on the first fix after a restart
resumes it **iff** both conditions hold: the gap is within `maxResumeGapMs` (the rule the open move
already uses) and the new fix still `holdsInCluster` the recovered anchor. ⚠ The second is not
optional — resuming on time alone would assert the user never left a place they had in fact left,
which is a worse error than an extra row.

⭐ **Resuming the cluster is what makes one stay one row**: the visit is content-addressed by its
arrival, so the original cluster keeps the original id and the re-emitted dwell folds onto the
existing row. `currentDwellEmitted` is deliberately left `false` so the resumed cluster re-emits — and
that re-emit is the repair, because it also carries the corrected span.

⚠ The stuck-at-five-minutes half is fixed by the same mechanism but only at **departure**: an open
dwell's duration is re-emitted on departure alone (§11.2 — the live count-up is device-local and
deliberately not synced), so what resuming buys is that the eventual departure accounts for the whole
stay rather than only the part after the kill.

⚠ `JsonOpenMoveJournal` gained an `entryId` rather than being forked: both journals persist a list of
`CapturedFix` that must survive process death, and a second copy of the version/corruption handling
would be a second place for it to drift. The Android dwell journal gets its **own directory** anyway,
so one's corrupt-record purge cannot take the other's.

⚠ Not addressed here: the hour-long capture holes visible in the same log (`fgs_start_refused`
repeating for an hour) are the Android 12+ background-FGS-start restriction, a different
mechanism — no journal helps when nothing is running to write one.

## 30. Capture could not restart itself — and §26.6 was wrong (2026-08-10)

§29 fixed the stay splitting *across* a restart. This is the restart itself: the hour-long holes in the
same log, `fgs_start_refused` repeating for an hour.

### 30.1 ⚠⚠⚠ Reproduced, with the system's own reason

Force-stop, then fire the boot receiver into a background process on Android 16 (API 36):

```
I ActivityManager: Background started FGS: Allowed [... code:SYSTEM_ALLOW_LISTED ...]
W ActivityManager: Foreground service started from background can not have
                   location/camera/microphone access: .TimelineCaptureForegroundService
```

and, once the swallowed exception was actually logged (§30.3):

```
SecurityException: Starting FGS with type location ... targetSDK=36 ...
  and the app must be in the eligible state/exemptions to access the foreground only permission
```

⭐ **Two separate facts, and conflating them is what misled §26.6.** The background *start* is
**allowed** — the battery-optimisation exemption covers it (`code:SYSTEM_ALLOW_LISTED`). What is
refused is the service holding the **location type**, because an FGS started while the app is in the
background is denied location unless the app holds `ACCESS_BACKGROUND_LOCATION`. The service then
stops itself, and capture stays dead until the user next opens the app — which is §11.4's "capture
dies on aggressive OEMs and never comes back", precisely.

### 30.2 Why §26.6 got it wrong

It argued the states are exhaustive: *FGS alive ⇒ fine location suffices; process dead ⇒ no permission
helps something that is not running.* Both are true. The set is not exhaustive — it omits **process
alive, app backgrounded, FGS being restarted**, which is exactly the watchdog and boot-receiver path
and the only state that matters after a kill. ⚠ The 122 overnight fixes it rested on were all from an
**already-running** service, so they could not have exposed the gap: the measurement was sound and the
generalisation was not.

⇒ The permission is re-declared, and now actually **requested** — separately and only once fine
location is held, because Android auto-denies a background request bundled with the foreground one.
Guarded by a pref like the battery ask, since a refusal must not become a prompt on every launch and
capture still works while the app is open.

### 30.3 ⚠⚠ The diagnostic that made this un-diagnosable

`ServiceCompat.startForeground` was wrapped in `runCatching { … }.isSuccess`, which **discarded the
exception** and logged a guess: *"no permission / not eligible"*. So the one line that could have
explained an hour-long capture hole explained nothing, and the true reason had to be recovered from
`logcat` on a live device months later. It now logs the exception type and message — which is how the
`SecurityException` above was obtained. ⭐ A refusal path that cannot say *why* is not a diagnostic;
it is a placeholder that looks like one.

### 30.4 Verified end to end on the device

⚠ `adb` on this ColorOS device cannot grant `ACCESS_BACKGROUND_LOCATION`
(`SecurityException: Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS`; the uid
mode stays `FINE_LOCATION: foreground`), so the positive case needed the maintainer to tap it. Granted
(`USER_SET`, uid mode `FINE_LOCATION: allow`), the same reproduction inverts:

| | permission absent | permission held |
|---|---|---|
| our log | `fgs_start_refused` | **`fgs_started`** |
| ActivityManager | *"can not have location/camera/microphone access"* | silent |
| service | stopped itself | `isForeground=true`, `types=0x00000008` (location) |

Force-stopped, revived from a background broadcast with **no UI**, keeping the location type; Wi-Fi
place recognition fired immediately and the raw-fix journal kept growing (3158 → 3161). ⭐ That is the
whole claim: capture now comes back on its own after a kill, which it demonstrably could not do
before.

### 30.5 ⚖️ What re-declaring it owes before any listing (2026-08-11)

`ACCESS_BACKGROUND_LOCATION` is the permission Play scrutinises hardest: it needs a written declaration,
a **demo video of the real in-app flow**, prominent disclosure, and the feature to be *core*. That is
recorded — with draft justification copy — in
[`docs/how-to/play-store-declarations.md`](../how-to/play-store-declarations.md).

⚠ The two properties the form actually tests are already true and must stay true: the permission is
requested **in context with a rationale and not bundled** with the foreground one (`maybeAskForBackgroundLocation`),
and **refusing it degrades rather than breaks** capture. So this is a form-filling exercise, not a
redesign.

⚠⚠ The checklist deliberately covers **every** policy-gated declaration across the apps, not just this
one — there are 16 `health.READ_*` permissions and 4 foreground-service types, and those are *more*
likely to block a listing than the single location permission that prompted it.

⚠ Also corrected in the same pass: `MainActivity.missingPermissions`' KDoc still asserted the permission
"is not needed" and had "been dropped from the manifest" — the §26.6 conclusion, left standing beside
the manifest that re-declares it. A stale "not needed" next to a live declaration is exactly how it gets
removed a second time.

## 31. The trajectory-aware naming gate — tightening only (2026-08-11)

§28 lets a named transit stop beat the city when a fix is too coarse to name a venue, bounded by the
fix's own accuracy. §26 always intended the trajectory to refine that. §27's anchor rule made it available. This is
that refinement — and it turns out to be a **precision guard, not a fix**, which shapes it entirely.

### 31.1 What the real legs say

The four coarse dwells the rule can touch, with the modes and confidences the store holds:

| dwell | acc | label today | leg in | leg out |
|---|---|---|---|---|
| metro station | 300 m | the station | walk/35 | **walk/conf=0** |
| stop between train legs | 100 m | the stop | transit/57 | transit/70 |
| town station | 100 m | the town | run/18 | drive/48 |
| — | 94 m | the city | — | — |

⚠⚠⚠ **The metro station's outbound leg is the metro ride, classified `walk` with confidence 0.** A gate
reading "walk ⇒ not transit" would tighten past the 82 m station and put the city's name back on a metro
platform —
undoing §28 on the very case that motivated it. So a non-transit verdict counts **only** when the
classifier was confident (`MIN_CONFIDENT` = 30, and both brackets must exist); everything else is
`UNKNOWN` and changes nothing.

### 31.2 ⭐ Tighten only, and why widening was rejected

Every real transit stop measured sat within **80 m** — comfortably inside the existing bound — so a
wider radius in transit context would buy nothing and risk naming a dwell after a station streets away.
The rule is therefore **monotone**: it can only make a label more conservative.

What it protects is the case the maintainer's data does not contain. Ordinary cafés with a named stop
in range: **Paris 38 % / Tokyo 30 % at 150 m**, 85–90 % at 300 m. A poor-fix dwell at such a café would
be renamed after a station; a confident walk in and out is the evidence that says do not. Tightened
radius = 40 m, under the width of the streets those cafés sit on. ⚠ Tightening is **not a veto** — a
stop 6 m away is where you were standing, whatever the legs say.

### 31.3 Verified to change nothing here

Replayed over the real store against live Photon, with the actual adjacency and each dwell's real
accuracy: **all four labels identical**. Metro station `UNKNOWN` → 300 m → the station (80 m); the
stop between train legs `TRANSIT` → 100 m → the stop (12 m); the town station `UNKNOWN` (weak run/18)
→ unchanged; the 94 m dwell still coarsens to the city.

⚠ **That a change alters nothing on the available data is the design, not a shortfall** — but it does
mean its *benefit* is measured externally (the café figures) and not on this corpus. Worth knowing
before tuning either constant.

⚠ Cost: one moment+trace read per sweep, taken **lazily** — a sweep whose dwells are all precise never
pays for it. A place with several dwells takes the strongest verdict across them (`TRANSIT` beats
`UNKNOWN` beats `NOT_TRANSIT`): a station is a station even if you once walked there.

---

## 32. One commute, nine rows — the 2026-08-12 diagnosis

The reported journey: a bike ride to a station (Station A) → a regional train to an interchange
(Station B) → a metro ride to Station C → a walk to the office. Nine rows came out, one of them named
after a bar. Diagnosed against the pulled `timeline.log` **and** Control's real `objects.db` — the
moments, traces and places as stored, not as rendered. Role labels below (Station A/B/C, the office)
refer to this journey throughout §32.

### 32.1 What capture actually recorded

Capture wrote, in order: a `Run` (the bike ride, v4 conf=9, median 3.81 m/s); the Station A dwell (two
fragments, merged at read time); a **`Drive · 22.3 km`** train leg (v4 **conf=0**, median 17.6 m/s); a
spurious 7-minute dwell (acc 300 m) at a passing loop; a `Transit · 48.9 km` leg (v4 conf=22); a
spurious 5-minute dwell labelled with the city name; two short **v5** `Transit` legs (1.8 km, 1.2 km);
a 14-minute dwell named after a bar near Station C; a `Walk · 399 m`; and the office, also labelled with
the city name.

⚠⚠ **The geometry says the legs are not what they look like.** The 1.8 km `v5` leg runs into Station B:
it is the **tail of the train ride**, not the metro. Only the 1.2 km leg is the metro, and it dies short
of the centre. **The rest of the metro ride has no fixes and no row at all** — it is inside the bar
dwell. Reading the modes without the coordinates would have got this backwards.

### 32.2 ⭐ Two adjacent same-mode rows can never merge — S9, built

`ModeSplitter` cuts on [`LegMode`] — raw per-leg speed and cadence. The verdict a row actually carries
comes from `classifyMode` → `TransitKinematics` → `promoteIfUnderground`, **afterwards**. Nothing
reconciled the two, so the splitter could cut on a boundary a later tier then overruled and the cut
stood anyway. Measured here: the two `v5` legs were cut apart because their raw leg speeds sat in
different bands (2.54 vs 0.18 m/s), then **both promoted to `TRANSIT` by `UndergroundHop`**. The split
bought nothing and cost a row.

⚠⚠ **Read-time cannot reach this shape.** `collapseTransitStationStops` folds `move → stop → move`, and
`isPassThroughStop` returns false for anything that is not a `Visit` — two *adjacent* movement rows with
no stay between them are structurally outside it. Fixing it there would mean teaching the projector to
merge captured legs, which is the "one journey, many rows" problem S2 created; fixing it at the flush
means the second row is never written.

**`ModeCoalescer`** (new) re-joins adjacent runs **iff their final classifications agree and the joined
path still classifies the same way**. Both conjuncts are load-bearing: *agree* keeps S2's real cuts
(train → walk stays two rows); *and the join still says so* keeps it honest, because distance, extent,
median and confidence are all recomputed over the union — a join is a new measurement, not a
relabelling. A run that is unrecordable on its own **breaks the chain** rather than being skipped, so
nothing joins across a hole. A single run is byte-identical to the pre-S9 path.

### 32.3 ⭐ The sweep had no schedule — built

`RailRefinementTrigger` ran `runOnce()` per app launch. **On the reported day the Timeline process never
started**: a full day of clean capture in the log and not one `app_start`. So the sweep — the tile tier
that logs `drive→transit evidence=surveyed(193) … kinds=[rail]` on every other day in that same log —
did not run at all. Zero traces reached v11; zero routes were snapped.

⚠⚠ **The cost is not a delayed label, it compounds.** `collapseTransitStationStops` guard 2 requires
*both* sides of a station stop to be `transit`, so the single unrefined `drive` leg is what left the
whole journey shredded across the passing-loop stop. One missing sweep, three rows.

The sweep is now driven by the thing that creates work for it: `LocationCaptureEngine` calls
`onTraceRecorded()` after a move is written, and `runOnNewTraces()` collects those nudges for the
lifetime of the capture session (the FGS scope — outside a session nothing produces refinable traces).
The launch pass stays as the catch-up over history.

⚠ Nudges are conflated **and** drained after the quiet period. Conflation alone is not enough: the first
nudge of a burst goes straight to the waiting receiver, so without the drain every burst costs two
sweeps, the second finding nothing. Bursts are the normal shape — one dwell confirmation flushes every
leg buffered behind it, six at once in the real logs.

⚠ No feedback loop: the sweep writes traces, but only *capture* nudges.

### 32.4 Still open — diagnosed here, not fixed

1. ~~**Mid-ride dwells split the journey.**~~ — ⚠⚠ **RESOLVED, and this diagnosis was wrong on every
   claim it made. Measured in §32.8.**
2. ~~**`TransitKinematics` cannot see a station stop at ~1 fix/min.**~~ — **FIXED in §32.11**, which
   also sharpens this entry: the rule *can* see the stop at 60 s, but only at one sampling **phase** in
   six. "Got lucky" was nearer the truth than "cannot".
3. ~~**`confidenceOf` returns 0 for every drive above 16 m/s.**~~ — **FIXED, see §32.5.**
4. ~~**The station-stop collapse deleted the interchange (Station B) arrival.**~~ — ⚠⚠ **also wrong:
   there was never a Station B arrival to delete. Measured in §32.8.**
5. **Naming.** ⚠ The "too generic" half — **three rows called by the city's name** — is **FIXED in
   §32.9**, and §32.9 also corrects this entry's stated mechanism ("no notion of kind" is false; the
   shipped path already ranks by kind). The "too specific" half (the bar name) is still open, but it is
   a *dwell* problem, not a naming one — see §32.9's last paragraph.
6. **Fragmentation, in the same day's data.** Station C ×3 (two rows plus the bar row), the
   passing-loop stop ×2, and Station B ×3 under different names for one station. See
   `timeline-place-identity-fragmentation`.
7. **A skew refusal ate a sweep.** `rail_sweep_read_error — unannouncedSession …
   [tenshu/file/service announced=4 floor=1 current=3]`. The retry path handled it, but it is the
   `tracesUnreadable` case in the wild.

### 32.5 ⭐ The open-ended bucket had a synthetic far edge — v6, built

`confidenceOf`'s margin term asks how far the median sits from the **nearest boundary of its bucket**.
Every bucket but the last has two boundaries; `drive` is open above 8 m/s, and the code supplied a
synthesised far edge at `MODE_BOUNDARIES.last() * 2` = 16 m/s:

```kotlin
val upper = MODE_BOUNDARIES.firstOrNull { it > median } ?: (MODE_BOUNDARIES.last() * 2)
val margin = (minOf(median - lower, upper - median) / halfWidth).coerceIn(0.0, 1.0)
```

Past 16 m/s the second term goes negative and the margin coerces to **0**. Measured across the band:

| median m/s | 9 | 12 | 16 | 17.6 | 25 | 40 |
|---|---|---|---|---|---|---|
| confidence, v4 | 24 | 96 | **0** | **0** | **0** | **0** |
| confidence, v6 | 24 | 96 | 96 | 96 | 96 | 96 |

⚠⚠ **The faster the travel, the less confident the verdict** — inverted exactly where the bucket is
least ambiguous. The reported 22.3 km rail leg (median 17.6 m/s) is the 0 in the store.

**Fix:** the open-ended bucket is measured against the one edge it has. Bounded buckets are untouched —
`halfWidth` still comes from the synthesised edge, so the top bucket still saturates a full
half-width above 8 m/s, exactly as the original comment intended.

#### ⚠ Why nobody noticed: both consumers failed *safe*

- `OngoingMove.MIN_NAMEABLE_CONFIDENCE` (35) — the live "you are on a ride" card **never named a
  motorway drive or a train**. It showed the unnamed state, which is a legitimate state.
- §31's `TransitAdjacency.MIN_CONFIDENT` (30) — a fast leg could never contribute to a `NOT_TRANSIT`
  bracket, so the naming gate never tightened to 40 m. §31.3's "verified to change nothing here" was
  measured on legs slow enough to carry a real confidence (the `drive/48` in its own table sits below
  16 m/s), so it did not expose this.

Nothing looked broken; things merely never happened. ⚠ That is the lesson worth keeping — the
monotonicity case in `ModeConfidenceTest` catches it without knowing which consumer to look at.

#### Version: 4 → 6, and the tiers now sort in order

The **mode verdict is unchanged**, so a v4 row's bucket is still correct — what moved is the
confidence beside it. It still needs a version, because a v4 `confidence = 0` on a fast leg means *"the
formula broke"* while v6's means *"genuinely on a boundary"*, and `TransitAdjacency` gates on exactly
that number. ⚠ It **cannot be swept retroactively**: confidence derives from the raw fixes and only the
decimated polyline is stored, so old rows keep their 0 and the version is the only thing that says not
to trust it.

⚠ `MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND` moved 5 → 7 alongside, so the constants sort in the order
the tiers actually apply: `EXTERNAL(0) < capture(6) < underground(7) < rail-refined(11)`. Nothing
compares the middle two numerically today — which is precisely why they were free to drift into the
wrong order — so `MovementClassifierVersionTest` now pins the whole chain. Stored `5` rows keep their
meaning and still sort below the tile tier, so they remain refinable.

⚠ No `SchemaMigration` and no format bump (HR #9): `classifierVersion` is a value in an existing
`MovementTrace` field, not a change to any stored row's shape. `SchemaShapeBaselineEnforcementTest`
confirms it.

### 32.6 ⭐ The metro ride that was never recorded — the ends are presence, v8

The metro ride from Station B to Station C is ~4.2 km. What the store holds is **1223 m**, ending
before the tunnel. The rest — the whole underground portion — is in no row at all; it falls in the
63-second gap between the end of that leg and the start of the bar dwell.

⚠ It is not decimation. `rawPointCount` is recorded *after* hygiene (`MovementMapper` sets it from
`CapturedMove.fixes`), and that leg's is 16 against a stored `pointCount` of 4 — the geometry it was
decimated *from* already stopped there.

**It is `FixHygiene.geometryPath`.** §22 excludes coarse fixes from route geometry, for a good and
measured reason — a ±300–600 m fix is displaced *off* the true path, so the route goes true → off →
true and the excursion's two legs both read as travel. That reasoning is exactly right about interior
vertices and exactly wrong about the two ends:

| fix | dropping it | keeping it |
|---|---|---|
| interior, coarse | removes a true→off→true **excursion** | adds two long spurious legs |
| **terminal**, coarse | **truncates the journey** — deletes real distance and the destination | adds one leg, to where the user actually was |

⚠⚠ And the correlation is vicious: on a metro hop the arrival fix is coarse **because** the rider was
underground. The rule discards its evidence precisely when that evidence is the only thing recording
the ride. Isolated on the segmenter with the two inputs differing *only* in the arrival fix's accuracy:

| arrival fix | recorded | ends at |
|---|---|---|
| fine (40 m) | 2162 m, 17 fixes | the arrival ✓ |
| coarse (300 m) | **1113 m**, 16 fixes | last pre-tunnel fix |

This is §25's "presence vs geometry needs a third role", answered for the case where the answer is
forced: **a terminal fix is not a vertex the path passes through, it is the claim *this is where the
leg ended*, and nothing else in the path carries that claim.**

`geometryPath` now keeps index 0 and `lastIndex` regardless of accuracy. Bounded and self-limiting, so
§22's inflation does not re-open: at most one added leg per end against an interior spike's two;
`classifyMode` takes the **median** per-leg speed, so one long terminal leg cannot flip a multi-leg
verdict; S6b's extent floor scales with *mean reported accuracy*, so admitting coarse ends raises the
bar a path must clear; and an all-coarse path still falls below `MIN_GEOMETRY_FIXES` credible vertices
and still takes the unfiltered fallback.

⚠ **Not re-measured against §22's 65-trace corpus** — that corpus lives on the device, not the repo.
§22's 78.0 km against an ≈85 km true route is expected to move *up*, since what is restored is real
travel at the ends of legs. That is an expectation, not a measurement; the on-device replay
(`SegmentationReplay`, §18.9) is where it gets settled.

**Version 6 → 8** (underground 7 → 9). Like `4`, this moves the *population* the thresholds are
compared against rather than the thresholds: `distanceM` grows and the median gains a leg, so a v8
verdict is not comparable with a v6 one on any journey whose ends were coarse.

#### ⚠ What this does *not* fix

The leg now reaches Station C, but two things about that ride are still wrong and are §32.4's:

- **The `v5` promotion landed on the wrong leg.** The 1.8 km leg promoted as an underground hop is the
  *train's* tail into Station B; the real metro ride is the leg after it. `UndergroundHop` asks whether
  a leg sits inside a blind interval, and the train's terminal approach sits in one too.
- **The bar dwell is still at Station C**, and the 14-minute dwell there still absorbs the walk out of
  the station.

### 32.7 ⭐ The promotion landed on the wrong leg — the span is shared, v10

The two `v5` legs were both promoted as underground hops. Only the second is the metro ride; the first
is the **train's terminal approach into Station B**.

Both guards in `UndergroundHop.promotes` are about the **span**, and a span is shared: one flush
produces several legs and all of them are judged against the same window. `coarseIntervals` builds
*maximal runs of non-fine fixes*, so an all-coarse flush is **one** interval covering everything —
departure dwell included — and `movedInside` is a **time-overlap** test. It therefore asks *"did this
leg happen during the blackout?"*, which every leg in the flush answers yes to.

Measured, reproducing the flush (a 40-fix dwell tail, then the marching train tail, then the pinned
metro leg):

| | anchor frozen? | promoted before | after |
|---|---|---|---|
| the span | **true** | — | — |
| train's tail — fixes *marching* ~450 m/step | false | **true** ⚠ | false |
| the metro leg — fixes pinned | true | true | true |

⚠⚠ **The span test is close to unconditionally true**, and that is the part worth remembering:
`previousDwellTail` contributes up to `DWELL_TAIL_FIXES` (40) fixes at one location, so it dominates
the median and the span reads frozen *however the ride actually moved*. It was carrying the whole
decision. The single interval covered indices `0..60` of a 61-fix span.

The per-leg signal was already right there and simply never consulted — `anchorIsFrozen` says false
for the train tail and true for the metro leg. So the same measure is now asked of the leg itself. No
new constant: `MAX_ANCHOR_STEP_M`'s two-orders-of-magnitude gap (0 m metro vs 311–482 m train) was
measured **per ride** in the first place; this asks it of the ride again rather than only of the
window around it.

⚠ **Accepted cost:** a leg with fewer than `MIN_ANCHOR_FIXES` coarse fixes is no longer promoted.
Three fixes is two steps and the cell-change jump is one of them, so the median is the mean — the
averaging failure `anchorIsFrozen`'s own KDoc refuses. A leg that thin cannot tell pinned from
marching, and §27's posture is that a missed promotion leaves the speed label while a wrong one is a
confident lie.

⚠ **What the train's tail reads as now:** its speed label (median 2.5 m/s). That is worse-*looking*
and more honest — and it is the case §32.3 just made recoverable, because the leg is on rail alignment
and `RailRefinement` promotes it with tile evidence on the next sweep, which now actually runs.

**Underground version 9 → 10**, capture unchanged at 8. Unlike the two previous renumbers this is not
a knock-on: **this tier's own rule changed**. ⚠ 10 was the last free slot below `RAIL_REFINED` (11) —
the next change here has to move that constant too, and moving it re-offers every already-refined
trace to the sweep.

#### ⚠⚠ This defect was propping up an S9 test

`ModeCoalescerTest`'s end-to-end case was built on the reported metro pair, and it broke the moment
this landed — its own precondition assertion fired ("fixture no longer promotes both halves"). That is
the correct outcome and worth recording: **the two adjacent `Transit` rows existed partly *because* of
the over-broad promotion.** S9's end-to-end case is now the shape `ModeSplitter`'s hysteresis KDoc
names instead — a ride cut by one braking stop, refined by `TransitKinematics` — so it no longer
depends on the tier §32.7 changes.

### 32.8 ⚠⚠ Measured after the fact — two of §32.4's defects were not defects

Going back to fix §32.4-1 ("mid-ride dwells split the journey") started by trying to reproduce it, and
the reproduction refused. Recording this because **the wrong diagnosis was written down confidently
and would have been built on.**

#### The clustering is not inflating anything

A station stop driven through the real segmenter, varying only what §32.4-1 blamed:

| scenario | dwell |
|---|---|
| 2 min stop, decelerating approach, acc 300 m, `steps = 0` | **none** |
| 2 min stop, acc 95 m, `steps = 0` | **none** |
| 4 min wait @ 1 fix/min | **none** — the ride stays *one* 44.9 km move |
| 7 min wait, `steps = 0` | 420 s |
| 7 min wait, **`steps = null`** | **420 s — identical** |

Both claims fail. The still-here veto is **not implicated**: `steps = null` (no pedometer, veto
impossible) produces the identical dwell. And there is **no duration inflation**: 7 minutes recorded
is 7 minutes genuinely stationary, to the second.

⚠ So the passing-loop stop was not "a ~2 min stop recorded as 7" — the train really did wait ~7
minutes. It is a **single-track passing loop**; waiting for oncoming traffic is what happens there. The
city-named stop is a signal hold short of Station B. Both are real stationary periods over
`minDwellMs`, and capture recorded them correctly. **The defect was never in the clustering** — it is
purely the semantic one `collapseTransitStationStops`' KDoc already states: *stationary on a train is
not a place*.

#### …and the collapse is the answer, which now works

The real stored moments, traces and places for the day, run through `TimelineProjection.days`:

| | rows |
|---|---|
| as the user saw it (leg 1 unrefined `drive`) | Run · 1.6 km / **Station A 56 min** / Drive · 22.3 km / **passing loop 6 min** / Transit · 50.7 km / Transit · 1.2 km / **bar 14 min** / Walk / **city name** |
| with leg 1 refined to `transit` | Run / Station A / **Transit · 72.9 km** / Transit · 1.2 km / bar / Walk / city name |
| …and captured under S9 (§32.2) | Run · 1.6 km / Station A 56 min / **Transit · 74.2 km** / bar 14 min / Walk · 399 m / city name |

⭐ **Nine rows become six, and every mid-ride dwell disappears** — the passing loop *and* the signal
stop. Nothing new was needed: the collapse was always correct and was starved of a `transit` verdict on
leg 1, which is exactly what §32.3's capture-driven sweep now delivers.

#### §32.4-4 was wrong for a different reason

The stop the collapse swallows is the signal hold, 2.5 km short of Station B. It is *inside* the ride,
so collapsing it is right. There was never a Station B arrival to delete: the rider stepped off the
train onto the metro without staying five minutes, so **no dwell was ever detected there**. A transfer
is structurally invisible to a `minDwellMs` rule, which is a different problem from the one §32.4-4
described.

#### What is actually left on this journey

Measured, not inferred:

1. **Naming** (§32.4-5) — the bar row is really Station C, the city-named row is really the office.
   Unchanged and now the most visible thing wrong with the day.
2. **The 74.2 km row merges the train and the metro**, so the transfer at Station B is invisible. This
   is the collapse working *as specified*; whether one row or two is right is a design question, not a
   bug — but it is the reason the ride reads as a single improbable 74 km hop.
3. **`Run · 1.6 km` was a bicycle** — median 3.81 m/s, 0.19 m/s under `LegMode`'s cycle floor, with
   confidence 9. A cadence signal would settle it; S3 already reads the pedometer when there is one.

### 32.9 ⭐ Three places called by the city's name — the street tier was missing

§32.4-5 claimed the geocoder "takes the platform `featureName` first with no notion of *kind*". That is
**wrong about the shipped path**: on Android the first tier is `ProxyReverseGeocoder` → Control's
`PhotonGeocoder`, which already ranks by kind (P3b) — named POIs win, street furniture never does.
Measured live at the dwell in question, Photon's nearest feature is a `highway=footway`, and the ranking
is what promoted an `amenity=bar` over it. The kind is not being ignored.

The real defect is one tier down. `PlaceLabelEnricher` refuses a venue above
`COARSE_LABEL_ACCURACY_M` (80 m) — correctly, a venue is a *point* and a ±100 m fix cannot claim one —
then asks §28 for a transit stop, and with no stop falls **straight to `result.city`**:

| place | accuracy | branch taken | stored label |
|---|---|---|---|
| Station A | 100 | coarse → stop | the station ✓ |
| Station C | 300 | coarse → stop | the station ✓ |
| **the office** | **100** | coarse → **no stop → city** | **the city** |
| **the signal hold** | **95** | coarse → **no stop → city** | **the city** |
| the bar dwell | 14 | precise → venue | the bar |

⚠⚠ The city is not merely a coarse answer, it is **the same string for everywhere in the city**: three
separate rows on this one store read the city's name, one of them the user's office. It also collides
with every future poor-fix dwell there, which is a fragmentation source as well as a naming one.

**A street is the tier that was missing, and it is the right shape for the job.** A venue is a point; a
city is 10 km across; a street is a *line*, so a ±100 m fix on a street is genuinely on that street.
Measured against live Photon, the two city-named rows resolve to two different streets — distinct,
correct, and no longer colliding.

`ReverseGeocoder.street(lat, lng)` is added exactly as `transitStop` already was: an interface method
defaulting to `null` ("this tier does not do streets"), asked **only** inside the coarse branch and
only when no stop answered. Order is now **stop → street → city**.

⚠ No radius parameter, unlike `transitStop`: this asks *"what am I standing on"* and the provider
answers for the coordinate given, so there is no distance to bound and no constant to justify.

⚠ **No wire change and no new operation id.** The op's return (`GeocodedPlace`) carries
`label/city/countryCode` and adding `street` to it would be a positional reshape of an op return —
which this repo's rule (and that op's own KDoc, the `RenewTaskLease` precedent) says requires a **new
operation id**, not a redefinition. It is not needed: on Android the chain's *second* tier is
`AndroidReverseGeocoder`, whose platform `Address.thoroughfare` is the street, costs no egress, and was
already being parsed and thrown away. Desktop's `PhotonReverseGeocoder` reads `properties.street` off
the same response shape.

#### ⚠ What this does not fix

The bar row is untouched here — accuracy 14 m takes the *precise* branch, so it keeps its venue.
⚠ This section called it a "spurious dwell"; **that was asserted without evidence and §32.10 retracts
it** — the cluster is discarded, so whether the stop was real is not answerable from the store. What
§32.10 does fix is the ±14 m claim itself.

### 32.10 ⭐ The bar row — a place claimed a precision its own cluster contradicts

§32.9 called this a *spurious* dwell. ⚠ **That could not be confirmed and should not have been
asserted**: `SegmentationReplay`'s own KDoc records that a dwell keeps only its centroid — *the cluster
is discarded* — so whether the user genuinely stopped for 14 minutes is not answerable from the store.
Only the `RawFixJournal` (§18.9) or their memory can settle it.

**What the store does settle, and it is enough.** Measured on the stay:

| | |
|---|---|
| dwell centroid → first fix of the walk **1 second later** | **165 m** |
| dwell centroid → Station C | 209 m |
| walk start → Station C | 45 m |
| stored `accuracyM` | **14 m** |

The place claims ±14 m while the stay demonstrably reached 165 m from its centroid — and the user was
at the station one second after it ended, 209 m away. **This holds whether or not the stop was real.**

`toCentroidVisit` computes `accuracyM` as the **median of the fixes' reported accuracy** — *"how good
were the fixes?"*. Every consumer asks a different question — *"how well does this dwell localise a
place?"* — and the two come apart exactly here:

| cluster | fixes | before | after |
|---|---|---|---|
| a stay that really is a point | ±14 m, tight | 14 | **14** |
| **a stay walked through** | ±14 m, spread ~150 m | **14** | **77** |
| an indoor jitter cloud | ±100 m, spread 63 m | 100 | **100** |

The stored figure is now the **worse** of fix quality and cluster radius. A stay whose fixes span
300 m localises a place to ±150 m however crisp each fix was.

⚠ `max`, deliberately **not** a sum: an indoor cloud's spread *is* its fix error, so adding them would
count the same uncertainty twice and coarsen every ordinary indoor dwell — the same reasoning
`extentAccuracyFactor` already carries on the movement side.

⚠ **Radius, not median distance.** The median under-reports a cluster the user walked through, which
is the whole case this exists for. The errors are not symmetric: over-reporting only moves a place
*down* the naming tiers (venue → stop → street → city), while under-reporting leaves the bar name in
place. It is bounded by construction — `holdsInCluster` admits nothing past ~2× the anchor radius.

⚠ **Monotone in the safe direction**: this can only make a place *less* precise, never more, so no
dwell that names a venue today loses one it was entitled to.

**Effect on the reported day.** With a spread ≥165 m the stay leaves the precise branch, so §32.9's
tiers apply: no stop within 165 m (Station C is 209 m — just out of reach), so it takes the street.
Not the station, but honest — the user was somewhere on that block — and no longer a bar they may
never have entered.

⚠ Existing rows keep their stored accuracy; this changes what future captures write.

### 32.11 ⭐ The stop detector was deciding on sampling phase, not on the journey — v9

§32.4-2 said `TransitKinematics` "cannot see a station stop at ~1 fix/min". Half right, and the half it
got wrong is the interesting one: at 60 s cadence a synthetic ride with two 60 s stops promotes
perfectly well — *if the fixes happen to land inside the stops*. The same ride, same cadence, sampled
at a different **offset**:

| phase | 0 s | 10 s | 20 s | 30 s | 40 s | 50 s |
|---|---|---|---|---|---|---|
| well-spaced stops | **2** | 0 | 0 | 0 | 0 | 0 |
| verdict | `transit` | `drive` | `drive` | `drive` | `drive` | `drive` |

**One offset in six.** Nothing about the journey differs between those six runs. `STOP_SPEED_MPS` asks
for a leg whose *endpoints* are both inside the stop, and at a cadence comparable to the stop that is a
coin flip — so the verdict was being decided by where the clock ticked.

#### ⚠ And the cadence is not ours to fix

The obvious repair is "sample faster" — S1 already requests 10 s in a vehicle, and at 10 s the stop is
detectable at every phase. It does not hold:

- the interval is a **floor**, not a promise; the platform delivers what GNSS and Doze allow, and the
  reported journey came in at **~60 s throughout**;
- promotion to `VEHICLE` waits on the first `MoveProgress`, which needs ≥3 min *and* ≥250 m, so the
  opening minutes of every journey are sampled at the slower pace by construction;
- and the pace is driven from the leg's own classified mode — the thing this rule is trying to decide.

A rule that only works when sampling is fast relative to the stop is a rule that works by luck.

#### The fix: ask what a stop *looks like* at any cadence

A leg of duration `T` containing a stop of duration `S` covers `(T − S)·v`, so its speed is
`v·(1 − S/T)`. Requiring the stop to occupy at least **half** the sampling interval — the weakest claim
that still means "a stop happened here" rather than "a slight slowing" — gives exactly `0.5·v`. The bar
is now the looser of a standstill and half the trace's own cruise. **The constant is the half, and it
is a statement about observability, not a tuned threshold.**

⚠ It only ever *widens* what counts as a stop, so every surrounding guard is doing more work than
before: rail-like straightness (≤ `RAIL_TURN_DENSITY_DEG_PER_KM`), ≥ `MIN_STATION_SPACING_M` spacing,
and a ≥ `SHAPE_RULE_MIN_CRUISE_MPS` cruise. **The four road controls are what make that safe to say**,
and all four still refuse promotion unchanged: motorway with no stops, a rural drive with junction
stops, stop-and-go traffic, and an urban grid.

**Version 8 → 9** (underground stays 10). Promotion-only, as the whole tier is: a v8 `transit` stays
`transit`; what moves is which `drive` rows become `transit`.

⚠ This is the tier that, on the reported day, would have made leg 1 `transit` at capture — which is
the verdict `collapseTransitStationStops` needs on *both* sides of the passing-loop stop. §32.3's sweep
already gets there with tiles; this gets there without waiting for one.

### 32.12 ⭐ D-T1: a change of vehicle is two rows (maintainer decision, 2026-08-12)

**Decision.** A **transfer** — the user got off one vehicle and onto another — renders as **two rows**.
Only a **pass-through station stop** — the vehicle paused and they stayed on it — folds into one.
Condensing a whole journey into a single row is a **display option** for later (a "condense" toggle in
the spirit of Drive's sort control); it is never a capture-side merge, because the toggle needs the
un-collapsed legs to condense *from*. Data side, the legs stay separate rows regardless.

#### Why it needed doing rather than recording

§32.8's table showed the day as one `Transit · 74.2 km`; that simulation predated §32.7, and on current
`master` the day already renders as two rows (`72.9 km` + `1.2 km`) because the train's tail is no
longer merged into the metro leg at capture. But the *common* case was still wrong — with an ordinary
6-minute connection wait at the interchange:

| | rows |
|---|---|
| before | Run / Station A / **`Transit · 74.2 km`** / street / Walk / street |
| after | Run / Station A / `Transit · 72.9 km` / **Station B** / `Transit · 1.2 km` / … |

The fold did not merely join two rides — it **deleted the interchange**.

#### The discriminator, and the one that was not available

Both legs are `transit`, so the mode cannot separate them. The obvious source is the rail *kinds* the
sweep already matches (`kinds=[subway, rail]` in its own log) — but `SnappedRoute.Sources` has exactly
one value, `RAIL`, whose KDoc says it deliberately means *"heavy rail, subway, narrow gauge"* as one
bucket. Persisting the kinds reshapes a stored composite: a service-version bump, a migration, and a
second read joined onto the feed path.

⭐ `MovementTrace.classifierVersion` already carries the bit, on the row the projector is reading
anyway. `MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND` (§27, capture-time frozen anchor) says *metro*; the
sweep's `wentUnderground` branch — computed from `BlindIntervalDetector.anchoredToSubway`, so the tier
positively confirms subway — used to overwrite it with the plain `RAIL_REFINED`, **erasing the one fact
that distinguishes the hop from the train it connects to**. It now stamps
`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND` (12), which sorts *with* the tile tier so a
swept metro leg is never re-offered.

#### The rule

`collapseTransitStationStops` breaks the walk when one leg is known underground and the other known
not. ⚠ **Only a positive disagreement blocks.** Unknown on either side folds exactly as before, so a
leg the sweep has not reached — or any row written before this — cannot change the feed it was already
producing.

⚠ **Known gap, accepted with the decision:** a train → train interchange is surface on both sides and
still folds. The sweep's matched kinds would separate it; revisit if and when the condense toggle
lands, since that toggle wants the un-collapsed data anyway.

⚠ The twins are what make this safe: *two surface legs still collapse across a pass-through stop*
(the passing loop — otherwise the whole 9→6 improvement unwinds) and *an unknown version folds exactly
as it did before*.

### 32.13 ⚠⚠ The corridor `ref` cannot separate an interchange — measured, and the route is closed

D-T1 (§32.12) left one accepted gap: a **train → train** interchange is surface on both sides, so the
underground stamp cannot see it. The natural discriminator is *which line each leg ran on*, and §24.2
had already measured that service identity is unreachable (`network`/`route` zero on every rail
feature) — but it also recorded that heavy rail carries `ref`, a **VzG corridor number**. Corridor
identity is useless for *showing* a line to a user and is exactly the right shape for asking *"is this
still the same railway?"*, so it was worth one measurement before spending two composite reshapes
(`RailLineGeometry` to carry it, a second to persist the matched corridor per trace) plus their
migrations.

**Method.** The §24 harness, extended (`surveyCorridorRefUsability`), over the same 13 MB production
extract around the commute, z14, heavy rail only (`kind_detail=rail`; subway is already D-T1's). 306
linestrings: **216 with `ref`, 73 with `name`**.

#### ⚠⚠ Q1 — a corridor does **not** carry one `ref`

| corridor | refs seen |
|---|---|
| **the commute's own corridor** | **three different refs** |
| three other named corridors | one ref each |

⚠⚠ **That first row is the reported journey's own line.** The regional train runs one corridor, which
appears under **three** different VzG numbers. A rule of the form "the legs' refs differ ⇒ they were
different trains" would therefore fire *mid-ride*, inventing an interchange on precisely the journey
this work came from. The signal is not merely weak; it is actively wrong on the motivating case.

#### Q2 — and at the interchange itself the tagging is patchy

Of 29 heavy-rail linestrings in the interchange's tile: **5 carry neither `ref` nor `name`**, several
(four) carry a `ref` with no name, and two carry the commute corridor's `name` with **no `ref` at
all**. So even where the corridors converge, a leg's matched segments may resolve to nothing.

#### Verdict

**Closed.** `ref` is not a corridor key, and no amount of plumbing makes it one. The two reshapes are
not written, and the measurement that says why cost one 13 MB extract.

⚠ `name` is more stable in the ref→name direction, but its coverage is **73/306 (24 %)** against
`ref`'s 71 %, and Q1b shows one name spanning three refs — so it is not the answer either.

⇒ **The D-T1 gap stands as a gap.** The remaining options are to stop the collapse *deleting* the
stop (render it inside the ride, the `ContainedMove` pattern) or to leave it until the condense toggle
is designed — which may make it moot, since that toggle wants the un-collapsed legs anyway. Both are
product decisions, not discoveries.

⚠ The survey **skips without the fixture**, like §24's: it asserts a fact about an external dataset,
not about our code. The extract command is in its KDoc.

### 32.14 ⭐ A transit leg is named by the stops it ran between

The report: the metro ride read **"Transit to <street>"**, where the expectation was *"<line> from
Station B to Station C"*. Two separable things are tangled in that, and only one is reachable.

#### Why the row said a street name

`movementEndpoints` names a movement from the **immediately bracketing visits** — deliberately, so an
unrecorded stop cannot collapse "Home → shop → Work" into "Home → Work". For a *transit* leg that is
the wrong kind of thing to name it after. On the reported journey the brackets were:

| | what it actually is |
|---|---|
| before | a **signal hold**, 2.5 km short of Station B |
| after | a stay **200 m from Station C**, on a nearby street |

Neither is where the rider boarded or alighted — and there is **no Station B dwell at all**, because
they changed trains without staying `minDwellMs`. No amount of better place-naming fixes that; a
transfer is invisible to a dwell rule (§32.8).

#### Measured: the stops are already reachable

`StopTileReader.nearestStop` against the production extract, at the leg's own endpoints:

| point | 100 m | 200 m | 300 m |
|---|---|---|---|
| metro leg start (Station B) | **Station B** (79 m) | same | same |
| metro leg end (where the rider surfaced) | — | a tram stop (165 m) | **Station C** (210 m) |
| the signal hold | — | — | — |
| the office (a non-stop control) | — | — | ⚠ Station C (252 m) |

⭐ **`tierOf` ranks `station`/`halt` above `tram_stop` regardless of distance**, so the tram stop wins at
200 m only because the station is *out of range* there — not because it outranks it. At 300 m the
station wins on the tier, which is why widening the radius is safe and does **not** mean "take the
nearest thing". `TRANSIT_STOP_RADIUS_M = 300` is read straight off this table.

⚠ 300 m is also where the first false positive appears (the office, 252 m from a metro station). That
is survivable **only** because the rule is asked on `transit` legs alone — a walk that finishes near a
station is never offered to it. That gate is the whole safety argument, and it has its own test.

#### Where it runs, and why not the sweep

Read-time, in `TimelineProjectionRepository`, cached by `traceId`. No schema change.

⚠⚠ The sweep was the first choice and is **not available**: persisting the names needs a field on
`MovementTrace`, which rides as a composite parameter of `recordMovementTrace` — and
`TimelineServiceCommonModule` already records, with three prior confirmations, that **no type that
appears as an operation parameter can gain a field** until the version-N overlay lands. E2's chain
verify rejects the nested reshape by name. Persistence would therefore mean a **new synced type**
(~19 files, the `SnappedRoute` footprint) bought for sync and render cost alone.

⚠ Read-time is not the expensive option it looks: `TileTransitStopSource` range-fetches **our own** CDN
and answers with **no network at all** once the region is installed. The 1 rps throttle that made this
look costly guards *Photon*, not the tiles.

⚠ Fallback is today's dwell endpoints (maintainer's choice), so a leg with no stop in range reads
exactly as it did.

#### ⚠ The line name is not reachable and this does not change that

Line identity needs §17.6's per-region OSM transit overlay. §24.2 measured `network`/`route` at **zero
on every rail feature** and subway `ref` on **0 of 93**. The row will read "Station B → Station C"; the
line prefix waits on the overlay.

---

### §32.15 — turn density was measuring the fix noise, and the sweep was erasing what only capture can see

**Reported** on the same commute as §32.8, on a build carrying every §32.x fix: the bar name was back,
the metro ride was missing, and the train's stops were wrong.

The day recorded structurally well — the train is **one** leg, the metro ride is **one** leg, the
interchange survives as its own row. Three separate mechanisms produced the three complaints, and they
are not variations of one bug.

#### ⚠⚠ First, an error in the instrument — the replay had been sorting

The first replay disagreed with the device: **230 fixes and 95 754 m** where the device recorded **95
and 79 425 m**. The cause is one line. `AndroidLocationCaptureSource` records the journal **before**
`inTimeOrder()`, so the corpus is the stream *as the platform delivered it* — GPS and NETWORK
interleaved by arrival — and the engine segments the **dropped** stream. The replay was calling
`sortedBy { timestampMs }`, which is exactly what `FixHygieneTest.droppingBeatsSortingOnTheShapeThat‐
MotivatedThis` exists to forbid: sorting relocates a stale fix into the middle of the path and weaves
a zig-zag.

⚠ **This invalidates numbers already written into §32.4's residual list** — "94 m median accuracy,
81 °/km" were measured through the sorted replay. The faithful figures are **64 m** and **54.7 °/km**.
The direction was right; the magnitudes were the instrument's.

⚠ Applying `FixHygiene.inTimeOrder` to the journal before replaying reproduces the device **exactly**
(79 425 m, 95 points, `drive`, confidence 52). Any future replay owes that step.

#### 1. The train read `Drive` — turn density is a one-sided estimator

Every conjunct of the stop-shape rule passed except one:

| | measured | bar | |
|---|---|---|---|
| cruise | 29.3 m/s (105 km/h) | ≥ 20 | ✅ |
| well-spaced stops | 10 | ≥ 2 | ✅ |
| **turn density** | **54.7 °/km** | ≤ 25 | ❌ |

Median reported accuracy on that leg is **64 m**; `TRANSIT_MIN_BEARING_LEG_M` was a fixed **30 m**.
The bar sat *below the noise floor*.

⚠⚠ The part that makes this a defect rather than a tuning miss: turn density sums the **absolute**
heading change between consecutive legs, so noise perturbing each bearing independently can only ever
*increase* it — there is no cancelling term. Fix noise does not blur turn density, it **biases** it,
in the single direction that reads "road-like", without bound as leg length falls toward the fix
uncertainty. Pinned directly by `aStationaryPlatformJitterLegOnlyEverAddsTurn`.

Measured on the journey, varying only the bar:

| min leg | 30 m | 100 m | 150 m | 200 m | **300 m** | 500 m |
|---|---|---|---|---|---|---|
| turn °/km | **54.7** | 36.9 | 31.3 | 31.2 | **22.4** | 17.4 |
| path kept | 100 % | 99 % | 98 % | 98 % | **95 %** | 88 % |

`bearingLegBarFor` now returns `max(30 m, 5 × median accuracy)`.

⚠ **The factor is calibrated, not derived.** A physical error model (bearing σ ≈ atan(σ√2 / L))
predicts far more noise than the journey shows, because consecutive fixes share satellite geometry and
multipath and their errors largely cancel in a *difference* of bearings. Any closed form overestimates,
so the 5 comes from measurement and wants T3-corpus calibration like `STRAIGHT_RAIL_DEG_PER_KM`.

⚠ Median, not mean — a cutting or tunnel mouth must not raise the bar for a whole journey.
⚠ `accuracyM <= 0` is **not reported**, never "perfect": reading it as 0 would silently restore the
old bar.
⚠ Cost accepted: a *short* leg with poor fixes yields fewer usable bearings and can fall below
`MIN_TRANSIT_LEGS`, staying `drive` — the conservative direction, per §13's standing posture.
⚠ The adversary is pinned: `aRuralDriveWithEQUALLYpoorFixesStillStaysDrive` gives the rural-junction
shape the **same ±64 m fixes**, and cruise still refuses it.

#### 2. The sweep erased the underground verdict — ⚠⚠ and this is coupled to fix 1

Capture stamped the metro leg **correctly**. The stored row read plain rail-refined.

`RailRefinement.refine` recomputes `wentUnderground` from the fixes it is handed, and the sweep hands
it the **decimated stored polyline** — 6 points for this ride. By §27's own argument the evidence
*cannot exist there*: platform fixes are dropped as bad geometry, Douglas–Peucker removes most of the
rest. So the branch stamping `RAIL_REFINED_UNDERGROUND` could **never fire from a sweep**, and the
coverage branch stamped plain `RAIL_REFINED` — erasing a fact the tier could not re-derive. §32.12
added the constant but only on the unreachable branch.

⚠⚠ **The coupling.** `isVehicleChange` blocks a fold only on a *positive* disagreement. Today the
interchange survives only because the two legs differ in **mode** (drive vs transit). Fix 1 makes the
train `transit`; both legs then read the same stamp, and the interchange row is folded away again —
re-breaking D-T1. **The two fixes have to land together**, which is why they are one change.

The rule now: the sweep may **demote**, never **forget**. `transitVersion` carries the fact forward on
branches whose verdict is still `transit`; a leg the map positively walks back to `drive` is stamped
plain `RAIL_REFINED`, leaving M9-3 intact.

⚠ **Renumbering.** The tier block sat at 10/11/12 immediately above the classifier line, so there was
no free slot and `9 → 10` would have collided with `UNDERGROUND` and silently reinterpreted stored
rows. The block moved to **20/21/22** and the classifier to **13**, which leaves legacy 10/11/12
unambiguous (no new constant reuses them — that is the whole reason for the jump) and 13–19 free.
⚠ Measured before renumbering, on a real Android store: 49 traces at versions **4, 9 and 11 only** —
nothing carried 10 or 12. Legacy 11 rows simply re-sweep, which is idempotent.
`LEGACY_UNDERGROUND_VERSIONS` kept a peer's un-upgraded row readable — ⚠ **removed at the 1.0.1
re-cut** (2026-11): every store was wiped, so no row carries 10/12/22 any more. The numbers stay
unreused so this history stays unambiguous.

#### 3. "The bar name is back" — ⚠⚠ §32.10 worked; the row was unreachable

The dwell measured **128 m** this time. Under the pre-§32.10 rule the same dwell claimed **±14 m**.
The fix works.

The stored `Place` still read **accuracyM = 14, capturedAt three days earlier** — nothing had written
to it. `TimelineImportWriter`'s guard:

```kotlin
val wouldErase = p.label.isBlank() && exLabel.isNotBlank()
if (wouldErase || wouldOverrideUser) return@forEach
```

A capture writes no label, so *every* re-visit to an already-named place hit this and was skipped —
**the whole row, not just the name**. So a place is frozen at the accuracy of the first visit that
ever created it, the enricher keeps reading that figure, keeps taking the precise-venue branch, and
keeps re-deriving the wrong venue. ⚠ The guard was right about what it protected and wrong about its
blast radius: **a name is a claim the user may have made; an accuracy is a measurement nobody
authored.** The write now proceeds, preserving label, source, coordinates and `capturedAt`, and
refreshing only the measurement.

#### §32.16 — and the same freeze existed across devices

⚠⚠ The local write was only half of it. `PlaceMerger` merged `accuracyM` with **`minOf`**, so a peer
still holding 14 clamped the corrected 128 straight back on sync — the row could not be fixed by *any*
device. That rule was right when the field meant "fix quality" (tighter genuinely is better evidence)
and wrong once §32.10 made it "how well we know where this place is": under the new meaning `minOf`
keeps the most **optimistic** claim ever made, and every consumer fails dangerously in that direction —
`PlaceProximity.radiusFor` folds too little (one venue, several rows), the enricher takes the
name-a-venue branch, §31 reads a confidence it has not earned.

⚠ **`maxOf` was rejected as the mirror-image ratchet**: one bad indoor visit would coarsen a known
venue forever and cost it its name. Both extremes are absorbing states, and a *measurement* should not
have one. The rule is now **the newer non-zero reading**, which follows the precedent `mergeLabel`
already sets (between two user labels the newer wins) and is well defined because a merger only ever
runs on a strictly newer incoming block.

⚠ `PlaceMerger` had **no test at all**, despite two rules marked "visible for tests". It has one now,
including `neitherDirectionIsAnAbsorbingState` — which states the property rather than an instance of
it, and fails under either ratchet.

#### What this does not fix

⚠ The line name itself. The line prefix still needs §17.6's overlay; the row reads its stops.
⚠ Two `Place` rows for one station — measured in the store, **34 m apart**, and the day's arrival
alternates between them from day to day. `PlaceIdentityResolver` folds a *new* place onto a known one
and returns early for an id it already knows, so once both exist they are permanent. That is the
§32-era face of the known place-identity fragmentation track.

---

### §32.17 — a vague place was swallowing a precisely-located one

Follow-up to §32.16, and a **correction to what §32.15 claimed**.

⚠⚠ §32.15's closing note said the interchange arrival alternated between two `Place` rows across
days. **It does not.** One of the two rows had already been merged into the other — the `PlaceMerge`
row exists, `PlaceReconciler` is wired and working, and the read path folds it
(`PlaceMergeFolding.repointToRoots` / `rootsOnly`). The alternation was an artefact of a diagnostic
script that read raw rows **without applying the merge plane**. The reconciler was never broken.

The real fragmentation is one cell south: **two rows for Station C, 244 m apart, both ±300 m**, plus
the bar row 195 m away.

#### Why the reconciler could not fold them, and why a wider ceiling is the wrong fix

`PlaceProximity.radiusFor` clamps at **150 m**, so a 244 m pair is unreachable however bad the fixes.
The obvious fix is a wider clamp, and it was measured on the real store:

| max radius | folds |
|---|---|
| 150 m (today) | **0** — the cluster stays fragmented |
| 250 m | 2 — the cluster folds ✅ |
| 300 m | 4 — ⚠⚠ **including the user's office into the metro station** (256 m) |

A 50 m safety band, fitted to one store, with only **12 m** separating the pair that must fold
(244 m) from the pair that must never (256 m). Rejected.

#### The asymmetry that does separate them

It is not distance, it is **who is uncertain**. `radiusFor` takes the *worse* of the two accuracies,
so a single vague row reaches out and swallows a well-located neighbour — precisely the office being
absorbed by a station admitting ±300 m.

A second rule takes the **better** of the two, so a fold requires *both* rows to be unable to tell
the difference:

| pair | apart | worse | better | mutual rule |
|---|---|---|---|---|
| the two station rows | 244 m | 300 | **300** | ✅ folds |
| office ↔ station | 256 m | 300 | **100** | ❌ refused |

⚠ It is a **union** with the existing rule, never a replacement. Alone it is *stricter* for
mismatched pairs — a ±100 m indoor dwell 80 m from a ±16 m known home would stop folding, which is
exactly the §8.2 fragmentation F1 exists to prevent. A second way to say yes can only add folds, so
nothing that folds today stops. `theUnionOnlyEverAddsFolds` pins that.

⚠ The office's real margin is **106 m**, set by the *unchanged* 150 m ceiling rather than by the new
rule's 100 m — a union is only as strict as its most permissive arm. An earlier draft of this section
claimed 156 m; the test caught it.

⚠ `accuracyM <= 0` is **not reported**: the new rule abstains rather than reading it as "mutually
certain" (which would refuse everything) or "unbounded" (which would fold across a district).

Measured outcome over the real store: **exactly one fold**, the two Station C rows, whose labels are
equal so `mayMerge` returns `ALLOW` without needing corroboration. Nothing else moves.

#### ⚠⚠ Wi-Fi identity was investigated first, and measured inert

The strongest available identity signal is `PlaceNetworkFingerprint`, and `PlaceClustering` already
carries `networkTags` — but only inside `permits`, i.e. it can *arbitrate* a fold geometry already
proposed and can never *propose* one. Promoting it into `isOneVenue` looked like the principled fix.

It was measured on the store first, and it would have folded **nothing**:

- both Station C rows have **zero** fingerprints — there is no Wi-Fi on a metro platform;
- across all 13 places, **no two distinct rows share a tag**.

The promotion is probably still right in principle — a shared access point is tens of metres of
evidence — but it is inert on today's data, so it is left unbuilt rather than shipped unexercised.

#### Not fixed

⚠ The bar row (195 m, ±14 → ±128 after §32.16) does **not** fold: with a better accuracy of 128 m it
sits outside its own uncertainty. That is the honest answer for a spot the user walked through, and
§32.16 stops it claiming a venue name it cannot support — it will name by stop or street instead.
`PlaceLabelEnricher.nearestStopWithin` searches `min(accuracyM, 300)`, so at 128 m it cannot reach
Station C 195 m away and will fall to the street tier.

---

### §33 — the transit-line overlay, as built

The pipeline §17.6 called for, sliced so each piece is independently shippable and device-free. **L1
is built**; the rest is named here so the order is not re-derived.

| slice | what it is | state |
|---|---|---|
| **L1** | the artefact format + the stop-pair lookup (`TransitLineIndex`, `:vector-tile`) | ✅ **built** |
| **L2** | the build step: OSM → the artefact, per region | ✅ **built** |
| **L3** | delivery: the catalogue entry, beside the tile bundles | ✅ **built** |
| **L4** | the op (`linesServing`) + a proxy tier reading the artefact | ✅ **built** |
| **L5** | the read-time consumer: the line on the row | ✅ **built** |

⚠ L1 first on purpose: the format is what every later slice reasons over, and it is the one piece
that can be settled entirely from the §17.6 proof without a build step, a network, or a device.

#### What L1 decides

**Keyed on coordinate, never on name.** The basemap POI and the OSM relation spell the same station
differently — a name join fails on the first station of the motivating journey, and that same
disagreement already produced two `Place` rows in a real store (§32.17). The overlay is indexed by stop
position; the basemap supplies the display name, the overlay supplies the line, and neither has to
agree with the other about spelling.

**Nearest stop, not every stop in range.** ⚠⚠ Two stations can sit inside one coarse radius, and
unioning them invents a line set *no single station serves* — which the intersection downstream would
then answer with confidently. Pinned by `theNearestStopAnswersRatherThanEveryStopInRange`, which fails
when the rule is reverted to a union.

**The ambiguous case is decided, and it is a set.** `{U3, U6}` renders as **"U3/U6"** — the honest
default, and what a departure board does. Falling back to the bare mode throws away a true fact;
picking one arbitrarily is the only option that can be *wrong*. §17.6 required this be settled before
the pipeline existed, precisely so a plausible-but-wrong label could not be shipped by default.
⚠ Sorted, so two devices render the same string.

**Absence is not a claim.** An unknown end, a stop pair with no line in common, a missing or corrupt
artefact — all yield *nothing*, and the leg then reads exactly as it does today. ⚠⚠ `decode` returns
`null` rather than throwing for the same reason: an unreadable overlay must not turn "we cannot name
the line" into "the feed failed".

**A shared string table**, because a region has thousands of stops and tens of lines; the refs are the
one thing worth not repeating. Pinned by a size assertion rather than described.

#### ⚠ What L1 does not decide

The **radius** the lookup is called with is the caller's, exactly as `nearestTransitStop` takes the
caller's own uncertainty — L4 owns choosing it, and §32.14's 300 m transit-stop radius is the obvious
starting point since it is resolving the same endpoints.

Whether the line is attached by a **sweep** over stored traces or resolved **read-time** like §32.14.
⚠ Read-time is the cheaper bet and needs no schema change; §17.6's retroactive note (the line can be
attached to journeys already recorded, since the stop pair is what identifies it) holds either way.


#### §33.1 — L2, as built

`scripts/transit-overlay-build.sh` reduces OSM to `ref \t latE7 \t lngE7`;
`:vector-tile:buildTransitOverlay` turns that into the `.tlne`.

⚠⚠ **The encoder is deliberately not in the script.** `TransitLineIndex.encode` already exists, and a
second implementation in bash or jq would be two encoders for one format, free to drift — the failure
`PlaceProximity`'s "one rule, in the module both sides depend on" exists to prevent, and the same
shape as the accuracy rule that did drift (§32.16). So **jq does the JSON and Kotlin owns the bytes**.

⚠ The builder lives in `:vector-tile`'s **jvmTest** source set: it is a build-time tool no app links,
so keeping it out of `jvmMain` means it ships nowhere while still being compiled and unit-tested by
the ordinary gate. Same placement as the ONNX measurement harness in `:inference-service-proxy`.

##### ⚠⚠ The defect L2 actually exists to fix: a platform node is not a station

OSM models a station as many nodes — one per platform — and a `route=subway` relation lists the
*platform* nodes it stops at. At an interchange one platform node carries one pair of lines and the
other platform the other pair; **no node carries the station's real line set.**

That is easy to miss because the motivating journey still works: `linesAt` answers with the nearest
node, and the motivating leg only needs its one line to appear at both ends. Land on that line's
platform and the answer is right *by luck*; land on the other platform and the intersection is
**empty** — the leg
silently loses its line, indistinguishable from "no coverage". Pinned by
`withoutMergingTheLineIsSilentlyLostRatherThanWrong`.

⇒ Platform nodes within `STATION_CLUSTER_M` (**150 m**) merge into one stop with the **union** of
their lines. ⚠ That union is safe precisely where the *lookup's* was not: these nodes are one station,
so every line on them genuinely serves it. Merging two stations would reinvent the invented-line-set
bug, which is why the bar is tight — interchange platforms span ~100–200 m, distinct city-centre
stations ~500 m+ — and why `twoDistinctStationsAreNotMerged` and `aChainOfPlatformsDoesNotWalkAcross‐
TheGap` exist. ⚠⚠ The failure modes are **not symmetric**: clustering too little loses a label,
clustering too much invents a confident lie, so when in doubt the bar is small.

⚠ Deterministic: rows are sorted before clustering, so two builds of one region are byte-identical
and a rebuild does not churn the catalogue's hashes.

##### ⭐ Proven end to end against live OSM and the real leg (2026-08-13)

The script's jq reduction was run against live Overpass, and the artefact it produced was queried
with the **stored endpoints of the reported metro leg** — the trace §27 measured at 2 points with a
travelling span of 0 m. The start's nearest station (68 m) carried one line, the end's (176 m) four;
⭐ **the intersection was exactly the line ridden.**

163 `(line × platform)` rows over a city's four metro lines clustered to **63 stations**, a 754-byte
artefact. So the pipeline answers the question that started this, on real data, with no geometry.

⚠ Not delivered yet — L3 adds the catalogue entry and signature; nothing reads these artefacts on a
device until L4.

⚠ `bus` is deliberately outside `ROUTE_TYPES`: nothing upstream produces a bus leg to label (§13's
"buses read as `drive`"), so it would multiply the artefact for no answer.


#### §33.2 — L3, as built

The overlay is delivered as a **second artefact per region**, beside the tile bundle and under the
same dated, immutable prefix: `regions/<buildDate>/<id>.tlne`.

##### ⚠⚠ It needs no signature of its own

`catalog.json` is signed **as bytes**, so an `overlaySha256` sitting inside it is already covered by
the chain that protects a tile bundle. A second detached signature would be a second thing to rotate
for no extra guarantee. `theOverlayDigestIsCoveredByTheCatalogSignature` is that claim as a test:
swap the recorded digest and the catalogue's own signature stops verifying.

##### ⚠ Additive, in both directions — and only one of them was free

`ignoreUnknownKeys` already handles a **newer catalogue on an older client**: the extra fields are
dropped and it reads tiles exactly as before. It does **not** handle the other direction — a newer
client reading a catalogue published before overlays existed would fail to decode on a missing
required field and lose **every region**, a total map outage caused by a transit feature. That is
what the defaults are for, and reverting one fails three tests, not one.

⚠ No `schemaVersion` bump: the change is additive and both directions are covered by defaults, so a
bump would buy nothing and force a flag day.

##### ⚠⚠ A missing overlay is not an error — unlike a missing bundle

Plenty of regions have no rail, and a region whose overlay has not been built yet must still get a
tile catalogue. Making the overlay a precondition would let a transit feature block map delivery. So
the bundle keeps its hard error and the overlay is simply absent, in the builder, the build script
(`--overlays` is passed only when the directory exists) and the publisher (which says so and
continues).

##### The D11 split holds

`hashes.json` gained an `overlays` map for the same reason it has `bundles`: the machine that built
the artefact need not be the machine holding the release key. Local artefacts still win over
precomputed digests — hashing what you actually have is better evidence than being told.

⚠ `:tiles-signer` now depends on `:vector-tile` so the stamped `overlayFormatVersion` comes from
`TransitLineIndex.FORMAT_VERSION` itself. A restated constant here would be the same drift the
encoder is deliberately kept single for (§33.1).

⚠ The signer prints `transit-line overlays: N of M region(s)` **even when N is zero** — a build that
silently stopped carrying overlays is exactly what that number exists to expose.

⇒ Nothing on a device reads these yet: **L4** adds the `linesServing` op and the proxy tier.


#### §33.3 — L4, as built

`MapRegionService.linesServing(from, to, withinM)` returns the line refs serving **both** endpoints,
comma-separated and sorted, or `""`.

⚠ It returns the **data**, not a rendered label. The answer is often a set (`U3,U6` on a shared
trunk) and how to show that is the consumer's decision; `TransitLineIndex.label` is the settled rule
and lives where both sides can call it.

⚠⚠ `""` is **ignorance, never "no line"**. Every way of not knowing answers empty — no fetcher wired,
no region covering the point, a region with no artefact, a format version this build does not
understand, a failed fetch, a digest mismatch, bytes that do not decode. A line label is an
enrichment; letting it fail a timeline read would be a far worse trade than not showing one. Most of
`CatalogTransitLineOverlaysTest` is that list.

##### Whole-file fetch, not a range read

The tile tiers range-read because a PMTiles archive is hundreds of megabytes. An overlay is **754
bytes** for a real four-line city (measured in L2) — ranging into that is more round trips than
bytes. It is fetched whole, digest-verified, decoded once and cached per region.

⚠ The cache keeps **negative** results too (a region with no rail costs one catalogue read, not a
request per leg) but deliberately **not** network failures: those can change, and caching one would
make a bad moment permanent for the process.

##### ⚠⚠ The digest check is the whole integrity story — and it is wired

`catalog.json` is signed as bytes, so `overlaySha256` inside it is authenticated (§33.2). Verifying
the fetched bytes against it is sufficient *and* necessary: unlike a streamed tile range this artefact
is small enough to check in full.

⚠⚠ The tier is assembled in `MapRegionProxyComponents`, where the `RegionRangeFetcher` already
exists, and passed explicitly at all three `PlatformProxyModule` DI sites. Left to the factory's
default it would have silently built a tier with no fetcher that always answers `""` — this track has
shipped built-but-unwired machinery before (compaction, retention P3), and one field is what stops
`linesServing` becoming another.

⚠ One tier, no network fallback, unlike `nearestTransitStop`: no public reverse-geocode answers
"which lines serve these two coordinates", and §24.2 measured the shipped basemap carries no line
identity at all. There is exactly one source.

⚠ The new factory parameters are appended **after** `meteredNetwork` rather than beside their sibling
seams — several call sites pass positionally, and inserting mid-list silently rebound their arguments
(caught only by a type mismatch).

⇒ **L5** is the last slice: read the op at feed time and prefix the row.


#### §33.4 — L5, as built: the pipeline reaches the row

`TimelineProjectionRepository` resolves the line beside the §32.14 stops — same trace, same
endpoints, **same radius**, since it is the same uncertainty about the same two points and two
different numbers would be two different claims about one fact. It is cached per `traceId` for the
same reason the stops are: a trace's endpoints never move, and the feed re-renders constantly.

⚠ Resolved **independently** of the stops. The overlay joins by *coordinate* (§17.6), so a leg whose
endpoints the basemap cannot name can still be named by its line, and vice versa.

##### ⚠⚠ The line replaces the *mode word*, not the endpoints

"`<line>` to `<stop>`", never "`<line>` · Transit to `<stop>`". And deliberately **not** prefixed onto
the two-endpoint form: "`<stop A>` → `<stop B>`" already names both ends, §32.14 measured those as the
readable part, and adding "`<line>` · " to a row that also carries mode and distance in its meta line is
where this stops being information and becomes clutter. The line rides the forms that currently say
"Transit" — including the leg with **neither** endpoint named, which is exactly the underground hop
this whole track started from.

⚠ The meta line shows the line in place of the bare mode too: "`<line>` · 2.9 km" says strictly more than
"Transit · 2.9 km" for the same width.

##### ⚠⚠ Blank must change nothing, and that is the guard worth having

Every failure in the chain arrives here as `""`, and `noOverlayLeavesTheRowExactlyAsItWas` pins that
the row then reads as it did before overlays existed. A regression in the ordinary no-overlay case
would be a far worse outcome than never showing a line at all.

##### What remains before this shows anything on a device

⚠⚠ **No overlay has been published.** L2 builds artefacts and L3 delivers them, but a real build has
not been run against the production `regions.json` and signed with the release key. Until it is,
every device takes the blank path — correctly, and invisibly. The code is complete; the *pipeline
run* is a deploy-time step, and the signer prints `transit-line overlays: N of M region(s)` precisely
so a build that carries none is visible rather than assumed.

⚠ No visual verification: the row text is unit-tested, not screenshotted.


#### §33.5 — the line, never the service: the filter §17.6 asked for

The first real regional build (2026-08-14) proved the pipeline end to end **and** showed it was
carrying what §17.6 excluded. A major station's line set held 40 entries, among them EuroCity, night
and EuroNight train numbers and an operator brand — individual **trains**, not lines.

⚠⚠ **The intersection hid it, which is why this nearly shipped.** Both legs of the reported journey
resolved correctly anyway — the metro line, and a regional line pair — because the service refs did
not survive intersecting the two ends. "It works on my journey" was exactly the evidence that would
have let it through; a leg between two stations sharing long-distance services would have rendered a
set of EuroCity train numbers, a confident claim about *which train* the user was on.

OSM already separates them. Measured over the same build's 1 177 relations:

| kept | | dropped as a service | |
|---|---|---|---|
| `regional` | 547 | `long_distance` | 62 |
| *untagged* | 260 | `high_speed` | 43 |
| `commuter` | 190 | `national` | 23 |
| `light_rail`, `branch` | 3 | `tourism` · `night` · `international` · `industrial` · `event` | 43 |

⚠⚠ **A deny-list, not an allow-list, and that direction is load-bearing.** 260 relations carry *no*
`service` tag at all, and that untagged group is where the **metro and the trams live — the
motivating line included**. An allow-list would delete the very lines this track exists to name, silently, the first
time OSM introduced a value nobody had listed. So an unrecognised or absent value is **kept**: a stray
service ref surviving is visible, a missing line is indistinguishable from no coverage.

⚠ `service` may be a list (`long_distance;night`, `night;motorail` — both real in the region), so any
service-level component disqualifies the relation.

⚠ The decision lives in **tested Kotlin** (`isLine`), not in the jq filter. Which refs count as lines
is a product decision; jq stays a dumb reducer that passes the tag along, the same split that keeps
the encoder out of the script (§33.1).

**Measured effect** — that station **40 → 16** lines, 1 699 of 12 671 rows dropped, 384 → 291 distinct
refs, artefact 55 374 → 50 149 bytes, and both legs unchanged.

⚠ One oddity deliberately **not** fixed: a five-digit ref survives, and correctly — it is
`service=regional`, `route=train`, a real named line whose `ref` is a course number. Filtering it would
need a "long numbers are not line names" heuristic, which would also delete a city's numeric tram
lines. One ugly ref beats an unmeasured rule.

---

### §33.6 — the ride was two rows because nothing folds two legs with no stop between them

Reported 2026-08-14: a regional-train commute rendered as **two** rows.

Not a §32.15 regression — every leg was `transit` (v21) and rail-confirmed. The ride was four stored
traces: three separated by station dwells, and a **fourth (35.1 km) starting the instant the third
ended**.

`collapseTransitStationStops` folded the first three across their two station dwells and then stopped
dead: its walk stepped over **`[stop, leg]` pairs**, so `merged[last + 1]` had to be a `VISIT`. Two
legs with nothing between them broke the pattern at the first line of `isPassThroughStop`.

#### ⚠⚠ Where that shape comes from, and why it will keep happening

`ModeCoalescer` decides whether to join runs at **capture** time, before any tile is available. This
flush emitted `drive 17516m` + `transit 35084m` — a genuine disagreement then — so it correctly
refused. Seventeen minutes later the rail sweep refined the `drive` to `transit`.

⇒ **The sweep can relabel a trace; it has no way to un-split one.** S9's join decision is made against
pre-sweep labels and never revisited, so any ride whose runs classify differently at capture is
permanently two traces. That gap has existed since S9 and the sweep started coexisting; §32.15 only
changed *which* pairs disagree, which is why it surfaced now.

Folding at read time makes the capture-time decision **harmless rather than permanent**, and needs no
storage change — the traces stay as recorded and the merge is reversible.

#### The rule

The walk now handles both shapes in one pass: `leg → stop → leg` as before, and `leg → leg` for two
same-mode legs with no dwell between them.

⚠ Bounded by `ADJACENT_LEG_MAX_GAP_MS` (60 s), **not** `ENDPOINT_MAX_GAP_MS` (30 min). Legs from one
flush are contiguous — 0 ms apart on the measured journey — so this only tolerates rounding. A
generous bound would fold across a capture hole and invent a continuity nobody observed.

⚠ §32.12 / D-T1 survives: `isVehicleChange` still blocks, so an underground leg beside a surface one
stays two rows. That guard matters *more* here — with no station row between them, a wrongly folded
transfer would leave nothing to notice the interchange's absence.

⚠⚠ The leg list is now collected **as the walk proceeds**. With two strides in play the old
`(i..last step 2)` would silently take the wrong entries — a stop counted as a leg, a leg dropped from
the distance.

⚠ Not fixed here: the underlying coalescer/sweep ordering. Re-joining stored traces is the heavier
option (moment ids are content-addressed off the trace) and the read-time fold removes the symptom
for every already-recorded journey, including those in the store now.

---

## §34 — the metro ride was a 4.9 km "walk": the splitter votes on speed, and a frozen anchor has none

**Reported 2026-08-14.** A metro leg renders as `Walk to <street> · 4.9 km · 11 min` — 4.9 km at
27 km/h. Every downstream tier gates on `mode == TRANSIT`, so the stops (§32.14),
the line (§33 L5) and the whole published overlay were never reached. ⚠⚠ **The overlay is not the
problem**; three sessions were spent looking there.

Measured by replaying the device's own raw fix journal (`files/raw_fix_journal/fixes.csv`, six days,
21 591 fixes after `FixHygiene.inTimeOrder`) through the real `MovementSegmenter`. The device stored
one ~12-minute leg, `walk`, 4 856 m, classifier v13.

### §34.0 ⚠⚠ Two of the handover's three root causes were wrong — measure before you build

The diagnosis this work started from named three components. Replaying the flush falsified two of
them, and the one that was right was right for a different reason:

| claim | measured |
|---|---|
| `ModeSplitter` sees no mode change, so the walk and the ride stay one leg | ⚠ **False.** It cuts — but on a *cell handoff four minutes into the ride*, and the two halves then classify alike so `ModeCoalescer` re-joins them (§32.2) |
| `UndergroundHop` refuses because §32.7 requires the leg itself to be pinned | ⚠ **False.** `anchorIsFrozen(leg)` is **true** for that leg. The conjunct that refuses is the *span* test |
| the fix is a second voter from `anchorIsFrozen` | Right in shape, wrong as stated — see §34.2: `anchorIsFrozen` itself was misreading, and a *voter* is the one form the second detector must not take |

### §34.1 ⭐ The span test measured the whole flush, and the median measured the providers

Two independent defects in `UndergroundHop`, both of which had to go before any splitting mattered.

**The span is the flush, not the blackout.** §32.7 reasoned that `anchorIsFrozen(span)` "is close to
unconditionally true", because `previousDwellTail` contributes up to `DWELL_TAIL_FIXES` fixes at one
location and dominates the median. That holds only while the flush is short. On this journey one
flush carried a 57 km surface train *and then* the metro ride:

| window | fixes | median coarse step | frozen? |
|---|---|---|---|
| the whole span (dwell tail + train + hop) | 327 | **200.9 m** | false ⚠ |
| the COARSE interval the hop sits in | 51 | **0.0 m** | true |
| the metro leg itself | 21 | **0.0 m** | true |

A 40-fix tail cannot dominate a 287-fix path, so the train's marching fixes set the median and the
span test **vetoed the very ride the tier exists for**. The same measure is now asked of the
*interval* — the blackout the leg sits inside, bounded by the platform dwells rather than by however
much unrelated travel the flush happened to buffer.

⚠ Nothing is weakened: §32.7's train-tail case is still refused by `anchorIsFrozen` on the leg, and
the interval's own freeze test still refuses a pinned *pause* inside a marching blackout — dropping
that conjunct promoted a 467 m walk after a train into the train's `Transit` row.
⚠⚠ **Stated rather than papered over:** `coarseIntervals` builds maximal runs of non-fine fixes, so a
flush that never sees GPS end to end is *one* interval and the reading is the old whole-flush one
again. Every measured surface ride carries some GPS (§25: trains run 0–33 % fine), which is what
separates the train's blackout from the hop's here; a phone that never gets a fix loses the
promotion — the pre-§34 outcome, a missed promotion rather than a wrong one.

**The median was counting duplicates.** `activeProviders()` registers GPS and NETWORK separately
(§21.3), so a position is routinely delivered **twice, seconds apart, at zero displacement** — and
when both providers are coarse, as underground and in a covered stairwell, every second raw step is a
`0` by construction. Measured on the walk from the train to the metro platform:

| coarse steps | median | verdict |
|---|---|---|
| as delivered — `0, 3, 0, 76, 0, 111, 0, 48, 0` | **0 m** | frozen ⚠ — a walk |
| between samples ≥ `LegMode.MIN_LEG_MS` apart — `13, 76, 111, 48` | **62 m** | not frozen |

⚠⚠ Not academic: it promoted the *walk* to `TRANSIT` alongside the ride, and the two then coalesced
back into the single 4.9 km row. `anchorIsFrozen` now thins coarse fixes to one per `MIN_LEG_MS` —
the constant §21.4 already measured for this exact duplication, reused rather than fitted again.
Corpus-wide this removed a false `frozen` reading from six surface legs (a 4.8 km run, three walks, a
cycle) without changing any row.

### §34.2 ⭐ The second detector — and why it must not be a voter

`LegMode` votes on displacement and underground there is none: the phone is pinned to one cell
anchor, consecutive fixes are the identical coordinate, and the leg measures 0.00 m/s — *below*
`INDETERMINATE_SPEED_MPS`, so the leg is filtered out before the hysteresis loop sees it. The
7½-minute ride offers the splitter **eleven consecutive legs that do not vote at all**, punctuated by
handoffs voting `drive` at 87 and 91 m/s.

So `ModeSplitter` gains `pinnedBoundaries`, reading `UndergroundHop.legIsPinned`. Four things about
its shape were each forced by a measured regression, not chosen:

1. **A separate pass, never a third mode.** Folding "pinned" in as a vote puts an entry into the
   hysteresis sequence where there was none, breaking the runs of `drive`/`walk` votes the speed rule
   counts. Measured: a drive lost 1.5 km to the walk before it, and a run and a cycle leg
   merged into one row. A pinned leg instead **abstains** — the sequence is filtered, never extended,
   and an abstention removed cannot break a run the way a foreign vote inserted into one can.
2. **The abstention is the point, not a courtesy.** Underground a leg's speed is a handoff artefact.
   Leaving those votes in cut the ride four minutes in, and the far half then held **zero**
   displacement — every fix the same pinned coordinate — so it failed the §3 thresholds, folded back
   into the walk, and the hop reassembled as the one mislabelled row.
3. **A four-minute window, not a count of fixes.** Two consecutive handoffs (1 767 m and 944 m) are
   two of only three steps in an 8-fix window, so the median becomes 944 m and the ride reads
   *unpinned in its middle*. Over `BlindIntervalDetector.MIN_COARSE_INTERVAL_MS` the same two jumps
   are two steps out of eight and the median stays 0. ⚠ Blindness is asked of the **leg** and
   pinning of the **window**: a window entered from daylight cannot be all-dark until a whole window
   has passed, and the boundary would then land minutes into the ride.
4. **Blind *and* pinned, and only where something travelled.** "Pinned" alone means the anchor is not
   moving, which a tunnel and a coffee break satisfy equally — a real 4.8 km run in this corpus reads
   `anchorIsFrozen = true` over its own fixes, because its NETWORK samples repeat while its GPS
   samples march. So the leg must be entirely coarse *and* sit inside a COARSE interval that
   demonstrably travelled. ⚠⚠ Without that last conjunct the failure is a **deleted** row, not a
   spare one: a stop with no sky view opened a boundary 2.7 minutes into a real 594 m walk,
   both halves fell under the §3 thresholds, and the walk vanished from the day.

⚠ One further guard, `hasSurfaceTravelBefore`: when everything on the path before the candidate
boundary is *already* blind there is no surface travel to separate — the near side is the ride's own
first half. Measured on a hop that began the moment the platform dwell closed, cutting there
turned a correctly promoted `Transit · 2.8 km` into `Drive · 3.0 km` + `Walk · 645 m`. Refusing
leaves pre-§34 behaviour exactly, and §27 still promotes the leg whole.

⚠ A "one boundary per blackout" rule was written and then **removed**: once the above interval is
skipped it changed nothing anywhere in the corpus, and an inert guard is one nobody can test.

### §34.3 As built

`MovementDetectionConfig.splitOnPinnedAnchor` (default `true`) is a kill switch on the same terms as
`splitOnModeChange` — `false` restores the speed-only splitter exactly, which is what lets every
assertion be twinned in config without a test-only code path.

The reported journey, before and after:

| | rows |
|---|---|
| before | `Walk · 4 856 m` (v13) |
| after | `Walk · 348 m` (v13) + `Transit · 4 508 m` (**v20**, the §27 stamp) |

⚠ 4 508 m matches the figure measured independently by replaying the underground portion as its own
leg, which is the check that the boundary landed where the ride actually began.

**Corpus effects, all six days, stated including the ones that are not improvements:**

- ⭐ The reported defect: `Walk · 4 856 m` → `Walk · 348 m` + `Transit · 4 508 m`.
- ⭐ A second metro ride: `Transit · 3 093 m` → `Walk · 546 m` + `Transit · 2 969 m` — the walk to the
  station is no longer part of the ride.
- ⚠ `Transit · 5 022 m` → `Drive · 1 654 m` + `Walk · 370 m` + `Transit · 2 996 m`: three rows for one
  journey; the ride's 2 996 m now matches §32.6's independently measured hop, but the 1 654 m at
  30 km/h is a suburban train wearing a `drive` label.
- ⚠ `Transit · 79 277 m` + `Walk · 2 896 m` → `Transit · 82 174 m`: the 2.9 km "walk" at 6 m/s after a
  train is folded into it; the distance is now right and the D-T1 vehicle change is still invisible.
- ⚠ The tail of an 83 km drive gains `Transit · 1 191 m` (v20) — a **new, unverified** promotion. It
  is a candidate by construction — v20 sits below `RAIL_REFINED`, so `RailRefinementSweep` demotes it
  if the endpoints miss subway alignment.
- ⭐ Six surface legs go `frozen=true` → `frozen=false` — §34.1's duplicate fix; no row changed.

### §34.4 ⚠⚠ What the tests do and do not establish

Every guard above is pinned by a test that was **verified to fail when that guard is removed** — the
first draft of this file had four guards whose tests passed under every mutation. Two lessons worth
carrying:

- **A count of runs from `ModeSplitter.split` is blind to exactly these failures.** A boundary that
  produces a sub-threshold run is folded away by `mergeUnviable`, and two runs that classify alike are
  re-joined by `ModeCoalescer`, so the damage only becomes visible one stage later, as a *row*. The
  tests assert on rows through the whole flush pipeline.
- **A fixture without the provider duplicates cannot catch §34.1, and one without the handoffs cannot
  catch §34.2.** Every fixture is shaped to the measured ride's proportions for that reason.

⚠ **Not verified on a device.** All of the above is a desktop replay of a recorded fix journal. The
Android capture path has not been run with this change, and nothing here has been seen on a screen.

### §34.5 ⚠⚠ …and §34.2 cut a train in half — the metro gate was reading the wrong number

**Reported 2026-08-15, on the first real ride after §34 shipped.** The metro ride came out perfect —
`<stop A> → <stop B> · <line> · 2.8 km`, mode, both stops and the line — and the **regional train
before it split into `Drive · 9.4 km` + `Transit · 69.6 km`**. §34.2 caused it.

#### What the read side was doing, which was its job

Capture emitted **five** legs for that journey and `collapseTransitStationStops` folded four of them
(11.7 + 19.3 + 14.8 + 23.9 ≈ 69.6 km) — including across a station stop. Only the first leg was
stranded, because the fold requires `transit` on **both** sides and leg 1 read `drive`.

The rail sweep had already examined leg 1 and refused it, from the device's own log: the 9 km first
leg had 11 polyline points, **4** on rail, exclusive coverage **0.36** — drive→drive ⚠ — while the
other four legs reached 0.50–0.63 and were promoted drive→transit.

⚠ `EXCLUSIVE_PROMOTE_COVERAGE` is 0.5 and its KDoc records the calibration — real train traces reach
0.54–0.61, two real regional trains sat at 0.58 / 0.61. **0.36 is not a threshold to
lower**; that would go under the calibration floor to fit one leg. The coverage was low because the
leg was short, and it was short because it had been cut.

#### ⭐ The cause: `impliedSpeedMps` is a sub-span, and a veto needs the whole span

`travellingSpan` searches for the **longest stretch inside 4–15 m/s** and reports *that* as the
interval's speed. For [UndergroundHop.promotes] the trimming is right and load-bearing — an 8-minute
platform wait sits inside the blackout, and measuring across it put a real ride under the walk floor
(§27). As a **veto** it is useless, and `MAX_METRO_SPEED_MPS`'s own KDoc says so: *"a fast blackout
that happens to end in a stop can still offer one that averages into the band."* Measured on the
first leg's blackout:

| reading | value | verdict |
|---|---|---|
| the sub-span `travellingSpan` selected | 1 082 m @ **12.9 m/s** | in band ⚠ |
| the interval **end to end** | 7 528 m in 339 s = **22.2 m/s** | 80 km/h — no metro |

A surface train in open country holds one cell mast for minutes, so its coarse fixes repeat and the
anchor reads **pinned** exactly like a tunnel. With the blackout gate passing on the trimmed number,
§34.2 cut the ride; both halves were then too short for `TransitKinematics` to see a train and each
classified `drive`; and `ModeCoalescer` refused to undo the cut because its rule requires the union to
classify *the same* as the halves — and the union classifies `transit`, which is different.

**As built:** `pinnedBoundaries` now also requires the blackout to be metro-paced **end to end**,
against the same `MAX_METRO_SPEED_MPS` the promotion tier uses — so the splitter cannot cut where
that tier would refuse. Asked of a span that a wait can only make *slower*, never faster, no wait can
smuggle a train past it. On the device's exact flush buffer:

| | result |
|---|---|
| §34.2 as shipped | two runs → `drive · 9 401 m` + `drive · 11 668 m` (matches the stored traces to the metre) |
| with the pace guard | **one run → `transit · 20 245 m`** |

⚠ Every §34.2 gain is retained — both platform-walk splits and the metro rides are unchanged.

#### ⚠⚠ Why the §34 corpus A/B could never have caught this

The A/B replays the journal through `MovementSegmenter` in one continuous pass. **The device's dwell
boundaries do not land where the replay's do** — here the device closed a station dwell ~40 s
earlier — so the replay flushes a *different buffer* and the splitter makes a different
decision. Replaying this journey end to end yields one `transit` leg under **both** settings; the
regression is invisible. Only feeding the device's exact stored buffer (`startMs`..`endMs` off the
trace rows) reproduced it, and then it reproduced to the metre.

⚠ **So a corpus A/B is a test of the rules, not of what the device will store.** When a stored row
disagrees with a replay, the store is right and the replay is measuring a different segmentation.
Pull `objects.db` + `-wal` and drive the probe from the trace timestamps.

---

## §35 — Six days of the same commute, replayed: the bike is a "run" every morning (2026-08-15)

The first use of [`RawFixJournal`] as the instrument §18.9 designed it to be. A maintainer's real
corpus — **24 964 fixes over seven days, 87% carrying pedometer data** — replayed through the
live segmenter by `CommuteReplay`, a maintainer-invoked harness beside `SegmentationReplay`.

The journey is the same on every weekday: **bike → regional train → metro**.

### 35.1 The metro leg is transit on every day — ⚠⚠ but **not** because of §34.5

§34's defect — the underground leg read as a 4.9 km *walk* — **does not reproduce**. It comes back
as `transit` stamped **v20** on every replayed day (2.76–4.51 km, 23–34 km/h), and the evening
return likewise.

⚠⚠ **The first draft of this section credited §34.5's metro gate for that, and it was wrong.**
Replaying the identical corpus against `1e506dc6` — the commit *before* §34.5's metro gate merged — produces
**byte-identical output**, `diff` clean over all six days. The metro leg already read as transit
before the gate landed, so this corpus is evidence that the defect is absent, and evidence of
*nothing* about what fixed it. ⚖️ Whether an earlier §34 slice fixed it, or these six days simply
never reproduced the tunnel-plus-signal conditions that produced the original 4.9 km walk, is not
answerable from here.

⭐ The general rule the mistake illustrates: **"the defect does not reproduce" and "the fix works"
are different claims, and only the first one a replay can make.** Attributing an absence to a
specific commit needs the commit's absence tested too, which is one more `git worktree` and one more
run.

### 35.2 ⚠⚠ The bike ride is classified `run` every morning — and `cycle` every evening

The same 1.5–2.4 km ride, at the same speed, on the same route:

| | morning | evening |
|---|---|---|
| verdict | **`run` — 6 of 6 days** | **`cycle` — 5 of 5 days** |
| speed | 13.8–16.6 km/h | 13.6–26.0 km/h |
| steps on the leg | **550–719** | **52–247** |

⚠⚠ **Speed cannot be the discriminator — the ranges overlap almost completely.** The cadence is,
and it is doing precisely what LegMode designed it to do: ~90 steps/min reads as running, ~30 does
not. The rule is behaving correctly on an input that is wrong.

### 35.3 ⚠⚠ It is *not* a composite leg, and the thirds are how we know

The obvious hypothesis — that the morning leg swallows the walk to the bike and the walk onto the
platform, so a walk-ride-walk composite averages out as running — **is false**. The harness reports
steps as `total [first third | middle | last third]`, and the morning legs are near-uniform — e.g.
`steps=601 [193|222|186]` and `steps=575 [171|165|239]` on two 1.5–1.6 km legs at 14.7 km/h.

A walk-ride-walk would leave the middle near zero. Steps are being counted **continuously through
the ride**, so the pedometer is registering pedalling or road vibration as walking.

⚠ **Why the same bicycle produces ~3× the step rate in the morning is not answerable from this
corpus.** Phone placement — trouser pocket outbound, bag or jacket homebound — is the obvious
candidate and is exactly what a fix stream does not record. ⚖️ Recorded as unexplained rather than
guessed at; the fix does not depend on knowing.

**What this implies for the rule:** cadence is trusted as decisive where it is demonstrably
unreliable. A sustained 14 km/h with low speed variance is a bicycle whatever the step counter
says, and the corroboration has to run the other way — speed vetoing cadence, not only cadence
breaking a speed tie.

### 35.4 ⚠⚠ The train is read differently on every single day

Six days gave six decompositions of one journey: from a single 95.8 km `drive` leg on one day, to
`drive` + `transit` on others, to three-way splits, with the mode flipping between `drive` and
`transit` mid-journey.

⚠⚠ **The instability is the defect, more than any single misread.** Leg counts of 1, 2, 2, 3, 3 and
a mode that flips between `drive` and `transit` mid-journey mean the feed cannot show the same
journey the same way twice — and a user who takes one train sees between one and three rows for it
depending on the day. §32's "one commute, nine rows" was the same disease at a coarser grain; this
says it survived §32's fixes.

### 35.6 ⚠⚠ The §34.5 metro gate changed nothing on six days of real data — and the symptom it targets is still here

The pre/post comparison above was run to attribute the metro result. It established something
sharper about the fix that shipped hours earlier.

The gate targets §34.2's train-splitting: *"a surface train in open country holds ONE cell mast for
minutes at a time, so its coarse fixes repeat and the anchor reads pinned — exactly like a tunnel"*,
and the reported symptom was the regional train ride rendering as `Drive · 9.4 km` +
`Transit · 69.6 km`.

⚠⚠ **That symptom is present on four of the six replayed days** (§35.4) — `drive` + `transit` on
two, three-way splits on two more — **and the output is byte-identical before and after the fix.**
The new guard did not fire once on this corpus.

⚖️ Two readings, and this corpus cannot separate them: the guard's condition may not be met by these
particular splits, or these splits may have a different cause than §34.5's. What is certain is that
the fix is **unexercised here**, so the six-day reading is not evidence for or against it — and
whatever is cutting these trains is still cutting them.

⚠ Worth stating plainly because the timing invites the opposite conclusion: a fix merged the same
afternoon, against a symptom this corpus contains, that moves no line of the output, is a fix whose
scope is narrower than the symptom's.

### 35.5 What the instrument itself established

- ⭐ **The journal works, and the replay is now the cheapest diagnostic in this track.** Six days of
  ground truth cost one `adb exec-out` and one Gradle invocation, against §32's day of manual
  screenshot reading.
- ⚠⚠ **`CommuteReplay` prints no coordinates, deliberately.** A raw fix stream is the most
  revealing artefact the app produces (A35); modes, distances and clock times are sufficient to
  read every finding above, and a latitude in a console log is a location disclosure in every
  report that quotes it.
- ⚠ **It prints, it does not assert.** What a real journey *should* segment into is the
  maintainer's judgement about their own week, and a test pinning one person's commute would fail
  for everyone else. The assertions stay in `SegmentationReplayTest`.
- ⚠ 13% of fixes carry no pedometer reading, so a replay is not bit-identical to what ran live on
  those stretches — the speed-only fallback is a different rule.

---

## §35.7 — The train split is **two** defects, and the obvious fix for one of them fails

§35.4 reported that the train is decomposed differently on all six days. The harness now prints,
for each leg, whether it **abuts** the previous one or follows a **gap** — and that single column
separates two problems that look identical in a feed and are fixed in different files.

| shape | what it means | where it lives |
|---|---|---|
| legs abut exactly, no stay between | one buffer, several runs — `ModeSplitter` cut it | the splitter (§34.2's disease) |
| legs separated by a gap | the segmenter closed the move on a dwell it believed in | the visit detector |

⚠⚠ **Both are present, on different days.** One day's ride is a splitter cut; another's is a
phantom stay of 8m12s mid-journey. Reading one as the other wastes the investigation, which is why
the column exists.

### 35.7.1 What cuts the train: a 3-minute deceleration with **zero** steps

One split ride, leg by leg:

```
drive    31.02 km  84.3 km/h  steps=0 [0|0|0]
transit  26.43 km  76.3 km/h  steps=0 [0|0|0]   ← SPLITTER CUT
walk      0.44 km   8.4 km/h  steps=0 [0|0|0]   ← SPLITTER CUT
drive    23.36 km  87.0 km/h  steps=169         ← SPLITTER CUT
```

⭐ The `walk` is the train braking into an intermediate station: 2.33 m/s, and the pedometer counted
**not one step**. `LegMode.of` reads it as a walk because 2.33 m/s sits below the run floor and the
else-branch's honest answer for "no steps at walking pace" is `WALK`. `ModeCoalescer` then **correctly**
declines to re-join — its rule is that differing refined verdicts mean S2's cut did real work — so
one ride becomes four rows. The coalescer is faithfully preserving a distinction that should never
have been created.

### 35.7.2 ⚠⚠ And the obvious fix makes the best day worse

The apparently principled change: a leg whose pedometer **reported** and counted **exactly zero**
steps below the run floor argues for *nothing* (`null`, already the "does not vote" answer) rather
than for a walk. Walking below the run floor and producing no counted step is not something a
working pedometer does, and it is working — `null` is handled above.

Replayed over the same six days, it removed one leg on each of three days (the split ride above
became `drive 57` + `walk` + `drive 23`) — and turned the one day that already read as **a single
clean `drive 95.75 km`** into ⚠⚠ **`drive 67.6` + `walk 0.33` + `drive 27.8`**.

**Reverted.** ⚠⚠ **Abstention is not neutral: it re-groups the runs rather than merely suppressing
one boundary**, so removing a vote can *open* a boundary elsewhere. It improved three days and
destroyed the only day the pipeline already read perfectly — a single 95 km leg, which is the
outcome the whole track is trying to produce.

⭐ The negative result is worth more than the attempt: **a change to the splitter's vote must be
evaluated on days it was not designed for.** One-day fixes have been this track's failure mode since
§32, and this is the first time the replay caught one before it shipped rather than after.

### 35.7.4 ⭐ The boundary-level fix: absorb the brake, and every other day is byte-identical

`ModeSplitter.absorbBrakingRuns` folds away an **interior** run that is short, below the run floor,
and whose own pedometer counted **exactly zero** — when *both* neighbours are vehicle-paced.

Replayed over the same six days, the whole diff is the one day of §35.7.1, which went from 16 legs
to **11**; the other five days are **identical**, the clean 95 km day included. The diff is the
commute twice over: the outbound four-leg split becomes one `drive 81.24 km` 62-minute leg — the
train, whole — and the return's three-leg split, the same disease, becomes one `drive 86.12 km` leg.

⭐⭐ **Monotone, as predicted.** Suppressing a boundary can only reduce the run count, so the single
clean 95 km leg — the reading §35.7.2's vote change destroyed — cannot be touched. That property is
the whole argument for fixing this in the splitter rather than in [LegMode].

⚠ **It fixes the row count, not the mode.** Both merged legs come back `drive`, so an 81 km train
now reads as one confident car journey rather than four confused rows. That is a real improvement to
the *shape* and leaves §13's transit tiers exactly where they were — `TransitKinematics` still does
not recognise this train, which is a separate defect this rule makes more visible, not worse.

### 35.7.5 ⚠⚠ The shared boundary fix nearly made the rule lie

`aStretchWithNoPedometerAtAllIsUntouched` **passed before the rule existed and failed after it** —
the sharpest possible signal, and it caught a real defect in the predicate.

[sliceAt] emits runs that **share their boundary fixes** with their neighbours. Reading steps off
the whole run therefore lets a *neighbour's* reading answer for it: a cruise fix carrying `0` made a
stretch with no pedometer data at all look like a hard zero, and the rule absorbed a run it had no
evidence about. The predicate now reads the **interior** only.

⭐ Worth stating as a rule for this file: **anything that reasons about a run's contents must
exclude the shared boundary fixes**, because they belong to two runs and vouch for neither.

### 35.7.3 What a real fix has to do differently

- Suppress the **boundary**, not the vote. The information that resolves the split leg is its
  neighbours — 80 km/h either side — and that lives in `ModeSplitter`, which sees the run sequence,
  not in `LegMode.of`, which sees one leg.
- ⚠ Or teach `ModeCoalescer` that a short zero-step run *between two runs that agree* is absorbable
  regardless of its own verdict. That is closer to its existing shape and does not disturb voting.
- ⚖️ The phantom stay is untouched by either and needs the visit detector. Whether an 8-minute
  station stop should end a move at all is a **design** question — it is a real stop, and the answer
  may be that the read-time fold should join across it rather than that capture should not see it.

---

## §35.8 — A bicycle is not a run: stride length, and the third time the vote was the wrong lever

§35.2's defect. The same ride came back `run` on **6 of 6 mornings** and `cycle` on **5 of 5
evenings**, at overlapping speeds — 13.8–16.6 km/h against 13.6–26.0 — so speed was never the
discriminator. Cadence was, and it was **working correctly on an input that is wrong**: the
pedometer counts pedalling and road vibration, ~90 steps/min outbound against ~30 homebound, so
only the mornings cleared `PEDESTRIAN_MIN_SPM`.

### 35.8.1 Stride is a fact about bodies, not a threshold to tune

Speed and cadence together imply a **stride length**. 90 spm at 15 km/h is a **2.9 m** stride; a
real run at that speed is ~170 spm and ~1.5 m. Over the same corpus the separation is total:

| | metres per step |
|---|---|
| genuine walks (n=26) | 0.61 – 1.47 |
| genuine runs | 1.25, 1.80 |
| **bike rides labelled `run`** | **2.45 – 3.25** |

`MAX_ON_FOOT_STRIDE_M = 2.2` sits above every real stride measured and below every impostor. ⚠ It
is reasoned rather than fitted, like `PEDESTRIAN_MIN_SPM`: sustained running strides are ~1.2–2.0 m
and a sprinter's peak is ~2.5 m.

### 35.8.2 ⚠⚠ In `LegMode.of` it fixed four days and broke two

The obvious home is the shared rule. Implemented there and replayed:

- ✅ three mornings `run` → `cycle`; a fourth day's ride joined into one 4.57 km `cycle`
- ⚠⚠ one day **regressed twice** — the evening ride's first 1.09 km was absorbed into an 85 km
  `drive`, and a `drive` + `walk` merged into a bogus 19.3 km/h "run"

⚠ `LegMode.of` is shared with `ModeSplitter` **deliberately**, so a journey is never cut on a
boundary the classifier then disagrees with. That sharing is exactly why a vote change re-groups the
hysteresis runs — the third time this track has learned it, after §35.7.2 and §32.

### 35.8.3 ⭐⭐ At classification time it is pure win

`LegMode.withoutImplausibleRun` is applied in `classifyMode` only. Boundaries do not move; only the
label does — and `ModeCoalescer` then joins neighbours that now agree, which is precisely the
reconciliation path §32.2 built for splitter-vs-classifier disagreement.

The entire six-day replay diff: three morning legs `run` → `cycle`, and on two days a `run` +
`cycle` pair became one `cycle` (1.09 + 2.02 → 3.11 km, and 1.49 + 3.08 → 4.57 km).

**Nothing else changed on any day.** The two joins are a bonus the coalescer produced for free: the
ride to the station is now one leg instead of two.

⚠ The replay above was measured **before §36.8.2** landed `isAbsorbable`, on the seven-day corpus.
The label change is independent of that — `withoutImplausibleRun` runs in `classifyMode` and touches
no boundary — but *which* neighbours the coalescer then joins is a splitter question, so the two
joins are the part of this diff a re-replay could move. The corpus is device-private (§18.9), so a
re-run needs a maintainer's own pull.

⭐ The pattern worth keeping, now that it has held twice: **when a rule is shared between the
splitter and the classifier, apply a correction at the narrowest stage that can express it.** §35.7
moved a fix from the vote to the *boundary*; this moves one from the vote to the *label*. Both are
monotone in the structure; the vote never is.

### 35.8.4 What it does not fix

- ⚠ **A morning ride with no pedometer data is still `run`** —
  `null` is not a small cadence, there is no stride to compute, and the speed-only bands already
  ran. 13% of the corpus is in this state.
- ⚠ The rule only ever *withdraws* a `RUN`. A walk with an implausible stride is a pedometer that
  under-counted, not a vehicle, and below the run floor the honest answer is still `walk`.
- ⚖️ Why the same bicycle yields three times the step rate in one direction remains unanswered, and
  a fix stream cannot answer it. Phone placement is the obvious candidate.

---

## §36 — An 8.2 km "walk" at 47 km/h: the verdict came from a median the rule calls uninformative (2026-08-16)

A device pass. The maintainer's reading of the feed: the regional train's total length was exactly
right, but its row ended at an intermediate station rather than the terminus — and there was now an
8.2 km, 10-minute walk, which is obviously wrong. Everything else — **including the metro leg** —
read correctly.

Both reports are **one root cause**, and it is not the one either sentence points at.

### 36.1 What the feed actually shows

Read off the device, the outbound commute is **three rows**, not one: a 24.6 km, 21-minute
`transit` (v21) named origin → intermediate station, then a **8.2 km, 10-minute `walk`** (v13), then
a 49.9 km, 37-minute `transit` (v21).

⭐ **The first row is telling the truth.** 24.6 km really is the distance to that intermediate
station, and that really is where leg 1 ended. What is wrong is that the journey is in three pieces
at all — and the middle piece is what keeps them apart. 24.6 + 8.2 + 49.9 = **82.7 km**, which is
why the length reads as "exactly correct": the maintainer was summing the three rows.

⚠ So the reported *symptom* ("it ends at the intermediate station") and the known open defect
`§33.6 fold names the first leg's end` are **different bugs that produce the same sentence**. The
fold is not running here at all. See §36.4 — it becomes the live defect the moment §36.2 is fixed.

### 36.2 ⭐⭐ The middle leg: a median of 0.50 m/s over a path that moved at 13.0 m/s

The middle trace, straight out of the store:

| measurement | value |
|---|---|
| stored `mode` | **`walk`**, classifier v13, confidence 17 |
| stored `medianSpeedMmPerS` | **0.50 m/s** |
| stored `distanceM` / span | 8 169 m over 620 s = **13.2 m/s** |
| start→end displacement / span | 8 064 m over 620 s = **13.0 m/s ≈ 47 km/h** |

⚠⚠ **The contradiction is entirely inside one row.** No extra sensor, no tile, no sweep: the trace's
own displacement and its own median disagree by **26×**, and nothing in `classifyMode` ever compares
them.

**Why the median collapsed.** The raw journal for that window holds two stationary clusters — an
opening ~2.3 min at 0.0–0.34 m/s and a second ~1 min later at 0.1–1.3 m/s — i.e. **two station
stops inside one leg**, with a coarse-fix stretch (accuracy 300–1000 m) between them. A median over
per-leg speeds is a *majority* vote, and on this leg the majority of legs are a stopped train.

**And then the fallback made it a walk rather than an abstention.** `LegMode.INDETERMINATE_SPEED_MPS`
is 0.6, so `LegMode.of(0.50, …)` returns `null` — the rule's own way of saying *this number carries
no information*. `classifyMode` catches that and falls back to `LegMode.speedOnly(0.50)` → `WALK`:

```kotlin
val mode = LegMode.of(median, medianCadence) ?: LegMode.speedOnly(median)
```

⚠⚠ **The row is labelled by a number the rule has just declared uninformative.** The fallback exists
for a good reason (§17.4: "a very slow wander was a `walk` before S3 and must stay one") and is
correct for a *slow* leg; it is silently wrong for a leg that abstained because it was **stopped
half the time while covering 8 km**.

### 36.3 ⚠ Why none of the three existing guards catch it

Each declines correctly, which is worth recording so the fix is not attempted in one of them:

| guard | why it does not fire |
|---|---|
| `ModeSplitter.absorbBrakingRuns` (§35.7.4) | `isBraking` requires `meanSpeedMps(run) < RUN_MIN_SPEED_MPS`. This run is **13.2 m/s**. It is not a braking run — it is the opposite. |
| `refineWithTransitKinematics` (T0, §13.3) | promotes a **`drive`** baseline only. A `walk` baseline is never offered to the transit tier at all. |
| `ModeCoalescer` (S9, §32.2) | joins adjacent runs iff their refined verdicts **agree**. `transit` / `walk` / `transit` disagree, so it faithfully preserves a distinction that should never have been created — §35.7.1's disease exactly, one tier further on. |

### 36.4 ⚠⚠ The fix this section proposed was built, measured, and **dropped** — read §36.8 first

Everything below was the reasoning before the corpus was run. It is kept because the *constraint* it
states is still right and still binding, and because the way it failed is the finding: the rule was
implemented exactly as described, and **it fires on no line of eight real days.** §36.8 is what
shipped. ⭐ Same lesson as §35.6, now applied to this file's own proposal.

⚠⚠ **Not a change to `LegMode.of`.** §35.7.2 measured that: a vote change *re-groups the runs* rather
than merely suppressing one boundary, and it destroyed the only day the pipeline already read
perfectly. `LegMode.of` is shared with `ModeSplitter`, so touching it moves boundaries.

⭐ The narrow move is in **`classifyMode` only** — the whole-leg verdict, which no boundary depends
on: when the median **abstains** (`LegMode.of` returned `null`), do not fall back to
`speedOnly(median)` blind. Consult the leg's own **displacement over its span**, the one measurement
a station stop and a duplicate fix cannot drag down. A leg that abstained *and* covered its ground at
13 m/s is not a walk under any reading.

⭐⭐ **This is monotone in the way §35.7.4's rule was**: it can only change rows whose median already
abstained, so every leg with an informative median is byte-identical. That property is the whole
argument for putting it here.

⚖️ **Still to measure, and it decides how many rows the journey becomes:**

- With the veto the middle leg lands on `drive` (13.0 m/s ≥ `DRIVE_MIN_SPEED_MPS`). Whether
  `TransitKinematics` then promotes it to `transit` is **not** predictable from here — the fixes are
  coarse (300–1000 m) and the accuracy gate may refuse. If it promotes, `ModeCoalescer` joins all
  three runs at capture time and the journey is **one trace**, one row, correctly bracketed. If it
  does not, the row count is unchanged and only the *word* "Walk" is fixed.
- Second chance either way: `RailRefinementSweep` refined a `drive` leg to `transit` 17 minutes
  later on an earlier journey (§33.6), after which the read-time fold joins them.
- ⚠⚠ **Run `CommuteReplay` over the six-day corpus before shipping it.** §35.7.2 is the precedent:
  the obvious fix improved three days and destroyed the best one, and only the replay saw it.

### 36.5 ⚠⚠ The fold-endpoint defect is not reproducing — and that is not evidence it is fixed

`movementEndpoints` reads its transit stops off `mm.representative`, which for a collapsed ride is
**the first leg**:

```kotlin
val stops = trace(mm.representative)?.takeIf { it.mode == MovementModes.TRANSIT }
val destination = after?.let { visitLabel(it, subtitle, roles) }.orStop(stops?.destinationStop)
```

`CollapsedRide` recomputes mode, distance and trace ids — its own KDoc says *"every one of these must
be recomputed: the representative is only the first leg"* — but the **endpoints are not on that
list**, and `collapsedRideToUi` regenerates the title while inheriting them. So a folded ride is
named `<first leg's origin> → <first leg's end>`: whole-journey length, intermediate destination.

It does not fire on this day **only because the `walk` blocks the fold**. Fix §36.2 and it becomes
the live defect immediately, producing literally the reported sentence — 82.7 km, named for the
intermediate station.

**As built:** `CollapsedRide` now carries `lastLeg`, and `movementEndpoints` asks the two ends of two
*different* legs — origin of the first, destination of the last. An un-collapsed movement has
`ride == null`, so both resolve to the same trace and every ordinary row is untouched.
⚠ Both ends are asserted (`aFoldedRideIsNamedByItsFirstOriginAndItsLastDestination` plus
`anUncollapsedLegStillNamesItsOwnTwoEnds`) because a fix that merely moved `representative` to the
last leg would pass a destination-only test and break the origin in the mirror-image way. Deleting
the change turns the first test red, expecting the terminus and getting the intermediate station —
the reported sentence, in a test runner.

⭐ Same rule as §35.1: *"the defect does not reproduce" and "the fix works" are different claims.*
Here a defect is invisible because a **second** defect is standing in front of it.

### 36.6 ⚠ A third, smaller thing, unverified — ⚠⚠ and the hypothesis below is **wrong**, see §36.6a

Leg 3 renders as a bare `Transit` — no endpoint at either end — although it finishes **119 m** from
the point at which the metro leg's origin resolved to the interchange station, well inside
`TRANSIT_STOP_RADIUS_M` (300 m) *of that point*. That is not a contradiction on its own: the radius
is measured to the **stop POI**, which may sit several hundred metres from either trace endpoint
across a large interchange station. The bracketing dwell is also `Unnamed place`, so
`PlaceLabelEnricher` did not name it either.

⚖️ Recorded as an observation, **not** a diagnosis — it needs the actual POI position, which this
pass did not obtain. It matters because it decides whether a fixed fold reads
`<origin> → <terminus>` or `<line> from <origin>`.

### 36.6a ⭐ Answered by a reinstall: the radius was never the problem, and a *failed* lookup is cached

Reinstalling Timeline hours later, against the **same stored trace and the same endpoint
coordinates**, that row came back as **`Transit to <terminus>`**. Nothing about the data changed;
only the process did.

So §36.6's radius hypothesis is **wrong** — the stop is comfortably inside `TRANSIT_STOP_RADIUS_M`,
and the blank was a *failed lookup*, not an absent stop. Which leaves the question of why a failure
survived so long, and the answer is in `TimelineProjectionRepository.withTransitStops`:

```kotlin
val resolved = stopNear(trace.startLatE7, …) to stopNear(trace.endLatE7, …)
transitStopCache[trace.id] = resolved          // ⚠ stores ("", "") exactly like a real answer
```

⚠⚠ **`stopNear` collapses every failure to `""`** — `runCatching { … fold(onFailure = { "" }) }
.getOrDefault("")` — **and the cache memoises that blank as if it were the answer.** One tile fetch
that fails, and the leg is unnameable for the life of the process; only a restart re-asks. `lineCache`
is written on the same path and has the same shape, which is consistent with the row also carrying no
line before the restart and a line name on the leg beside it.

⚠ The cache's own comment — *"a trace's endpoints never move"* — is true of the **coordinates** and
was read as though it were true of the **lookup**. Those are different claims, and only the first one
justifies caching forever. ⭐ The general trap, worth carrying: **a memo keyed on immutable input is
only sound if the function is total**; the moment it can fail, the cache needs to distinguish
*"asked, and the answer is nothing"* from *"asked, and could not tell"* — the same
`unknown ≠ nothing` distinction [`stopNear`]'s own callers already respect (`orStop` treats blank as
"no claim", not as "no station").

⚖️ **Not the cause of anything user-visible on its own** — the honest failure mode is a row that
reads `Transit` instead of `Transit to <terminus>` until the next launch. It is recorded because
§36.6 stated the opposite hypothesis with a plausible-sounding argument. **Fixed in §36.6b.**

### 36.6b ⭐ As built: a bounded retry, because neither "cache forever" nor "never cache" is available

The obvious fix — stop memoising blanks — was rejected, and **not for performance reasons.**
`NearestTransitStop` is tiles-first with a **network geocoder fallback**, so re-asking a nameless leg
on every emission of the traces flow sends its coordinate to a third party over and over. A leg whose
endpoint sits mid-line between two stations is *legitimately* nameless and completely ordinary — it
is the common case, not the edge one. ⚠⚠ That would trade a cosmetic bug for repeated egress on the
timeline's most revealing rows, which is the exact thing §28.2 routed this through the proxy to
prevent.

⚠ Nor can the two cases be separated at this layer even in principle: the op returns `""` for *both*
"no tier could answer" and "no stop within the radius" — its own KDoc instructs the caller to read
blank as "no stop, never an error". Distinguishing them means changing a service operation's
contract, which is a larger change than the defect justifies.

So the cache asks a **bounded** number of times. `StopLookup` (new, `:timeline-shared-data`) holds the
two stops, the line and an attempt count; `shouldAsk` returns true while the entry is not fully
resolved and `attempts < MAX_ATTEMPTS` (3). A resolved leg still costs exactly one lookup per process,
so the cache keeps doing its original job.

⚠⚠ **A retry must never take a name away.** The three lookups fail independently, so an attempt that
resolved the origin can be followed by one that resolves nothing — writing that over the cache makes a
row *lose* a name it had already shown. `mergedOver` lays each attempt over the last with blank losing
to non-blank, so the cache is monotone in what is known. A self-healing cache that is not monotone is
just a flickering one; `aRetryNeverTakesANameAway` pins it.

⚠ **The honest limitation:** attempts are spread over however often the traces flow emits, which is
store-change driven and therefore minutes apart in practice — that is what gives a transient failure
time to clear. A burst of emissions inside one second would spend the budget without ever re-testing
the condition. That is why the bound is 3 rather than 2, and it is a real weakness rather than a
tuned-away one; a time-based retry needs a clock this class does not have.

⭐⭐ The rule worth carrying out of all of §36.6: **a memo keyed on immutable input is only sound while
the function is total.** "A trace's endpoints never move" was true, and it was a claim about the
*coordinates* — the cache read it as a claim about the *lookup*.

### 36.7 What this pass confirms is working

- ⭐ **The metro leg reads `<station> → <station> · <line> · 2.7 km`** — v22, confidence 95. §33's
  overlay, §32.14's stops and the line name all fire together.
- ⭐ Rows between dwells (a street → the departure station; a walk to a café) name both ends from
  dwells, and the line name rides the two-endpoint row correctly.
- ⚠ The morning bike leg is still `Run · 2.4 km` — §35.2 (cadence misreads it; the stride rule is §35.8).

---

## §36.8 — What shipped: the station stop is a *second* absorbable shape, and the classifier change was inert

Both fixes built against the corpus pulled the same day: **29 060 fixes over eight days**, which is
the first corpus in this track to contain the journey that produced the report.

### 36.8.1 ⚠⚠ The `classifyMode` veto of §36.4 moves nothing, and was dropped

Implemented as specified — displacement-over-span overruling a median that abstained, plus the
`MOVEMENT_CLASSIFIER_VERSION` 13 → 14 bump §12.1 requires. Replayed over the eight days it changed
**two lines**, both on the reported day: the 8.17 km `walk` became a 1.48 km `walk` and the boundary
before it moved. It shrank the lie; it did not remove it.

⭐⭐ **And with §36.8.2 in place it changes nothing at all** — `diff` clean over all eight days
against the identical corpus. The whole improvement is carried by the splitter rule, so the
classifier change is an unexercised rule that would nonetheless bump a **persisted** version stamp,
re-sweep every stored trace, and make v13 and v14 rows incomparable. **Reverted.**

⚠ Recorded rather than deleted because the underlying contradiction is real and this is the second
time this track has met it: `classifyMode` can still label a row from a median that
`LegMode.INDETERMINATE_SPEED_MPS` has just declared uninformative. What §36.8.2 removes is the
*cause* of the only instance anyone has seen — a splitter cut — not the class. If a non-interior
instance ever appears (a flush that *begins* with a station stop, where there is no preceding run to
vouch for it), §36.4 is the fix, already written and already measured.

### 36.8.2 ⭐⭐ What shipped: `isBraking` → `isAbsorbable`, and a run may hold a standstill

The residual 1.48 km leg measured out as a **3-minute run at a mean of 8.2 m/s holding a 59 s
standstill**, between a 75 km/h run and an 80 km/h run — i.e. an interior station stop, refused by
`absorbBrakingRuns` on exactly one conjunct: `meanSpeedMps(run) < RUN_MIN_SPEED_MPS`.

⚠⚠ **That conjunct could not simply be loosened** — it is what makes a *brake* a brake (§35.7.4),
and it is still doing its job. A station stop is a different claim on different evidence, so it is a
second **disjunct**, not a widened threshold:

| shape | the evidence |
|---|---|
| a brake | the run is itself below the run floor |
| a station stop | the run is fast on average and yet **holds a standstill** |

Everything else is shared and unchanged: interior only, both neighbours vehicle-paced, a pedometer
that *reported* and counted exactly zero, and a span under `MAX_BRAKING_MS`.

⚠ `holdsAStandstill` measures **displacement, not path distance**, over any window ≥ 45 s, against
`TransitKinematics.STOP_SPEED_MPS`. This is the thread running through all of §36: a stationary phone
jitters, so a standstill's summed path is not zero — on the measured leg it is over a kilometre —
while the displacement between the window's ends is metres.

⭐⭐ **Still monotone, exactly as §35.7.4 argued**: absorbing an interior run can only *reduce* the run
count, never re-group the survivors. That is what makes it safe to change here rather than in a vote.

**Replayed over all eight days, the entire diff is on the reported day:** `drive 29.03 km` · `walk
8.17 km` · `transit 49.91 km` becomes `drive 29.03 km` · `transit 58.01 km` (47 min). That day goes
6 legs → 5; **the other seven days are byte-identical**, the single clean 95.75 km leg included.

⚖️ **The two survivors abut exactly but are `drive` + `transit`, so `collapseTransitStationStops`
shape 2 does not fold them yet** — it needs the same mode. `RailRefinementSweep` refined precisely
this shape 17 minutes later on an earlier journey (§33.6), after which they fold into one row.
Not verified on device here, and it is the one claim in this section a replay cannot make.

### 36.8.3 ⚠⚠ The integration test passed before the rule existed — twice

`aTrainThatStandsAtAStationStaysOneLeg`, first draft: a platform stretch creeping at 0.7 m/s between
two cruises. Green with the rule **and green without it**, because the middle run covered 140 m and
`mergeUnviable` folded it back on the §3 thresholds. The test was measuring the wrong mechanism
entirely.

The fixture the rule actually needs has four simultaneous constraints, and they nearly exclude each
other:

| it must | because |
|---|---|
| be **viable** (≥ 250 m, ≥ 180 s) | else `mergeUnviable` absorbs it and the rule never runs |
| have a mean **≥ 2.5 m/s** | else `isBraking` absorbs it and the new disjunct is untested |
| hold a **≥ 45 s** standstill | that is the predicate |
| span **≤ 240 s** | `MAX_BRAKING_MS` |

⭐ A standstill drags the mean down, so the only way to satisfy the middle two together is a run that
*decelerates through* the station rather than creeping: 6 ticks at 5 m/s (which votes `cycle` — the
vote only has to differ from `drive` to open the boundary) then 4 ticks of standing. That run is
200 s / 611 m / **mean 3.1 m/s**, and the path comes back as **three** runs with the disjunct deleted
and **one** with it.

⚠⚠ Worth stating as a rule for this file: **in a test against `ModeSplitter`, "one run" is the answer
you get when nothing happened at all.** Four different mechanisms return a single run — splitting
off, too few fixes, no boundary surviving the guards, and `mergeUnviable` — so an assertion of
`1` is only evidence once the same fixture has been shown to return more than one without the rule
under test. Both new absorption tests were verified that way.

---

## §37 — A 44 km train read as a bike ride: the median measured the sampling, not the journey (2026-08-31)

**Report.** A regional train journey rendered as three rows — `Cycle · 44.3 km`, a `Visit` at an
intermediate station, `Cycle · 8.0 km`, `Transit · 21.3 km` — on a day the rest of the feed was
accurate. The identical journey the previous evening was one correct `Transit · 79.7 km` row.

⚠⚠ **One statistic closed three doors.** `cycle` is neither `drive` nor `transit`, and every tier
that could have rescued the journey gates on exactly that:

| tier | gate | what happened |
|---|---|---|
| T0 `refineWithTransitKinematics` | `if (baseline.mode != DRIVE) return baseline` | never ran |
| T1 `RailRefinement.needsRefinement` | `mode == DRIVE \|\| mode == TRANSIT` | never a candidate |
| read-time `collapseTransitStationStops` | guard 2: both movements `transit` | no fold |

The device's own `timeline.log` records door 2 verbatim: `move recorded: cycle 44292m` followed by
`rail sweep: 0 candidate(s) of 15 trace(s)`. The very next leg, which *did* land on `drive`, was
swept 30 s later and promoted — `drive→transit … cov=0.62 excl=0.57 kinds=[rail, tram]`. The map
would have settled the question instantly; it was never asked.

⭐ **The evidence for `transit` was already sitting in the trace.** Measured on that exact 44 km
path, T0's stop-shape conjuncts pass with room to spare — turn density **4.4 °/km** (bar ≤ 25),
cruise **81 km/h** (bar ≥ 72), **5** well-spaced station stops (bar ≥ 2), 11 usable legs (bar ≥ 8).
Nothing was missing except eligibility.

### §37.1 The cause: a median over a bimodal sample lands where nothing is

`classifyMode` reduced the path with an **unweighted** median of per-leg speeds. The leg-speed
histogram of the reported journey (33 legs, after `FixHygiene.geometryPath`):

| band | < 0.6 | 0.6–2.5 | 2.5–4 | **4–8 (cycle)** | 8–16 | 16+ |
|---|---|---|---|---|---|---|
| legs | 12 | 2 | 1 | **2** | 4 | 12 |

Standing at a platform, or running at 60–160 km/h, and almost nothing in between — so the median
falls in the *empty valley* between the two modes, and the valley is the cycle band. **2 of 33 legs
were anywhere near the verdict.** (12 of the stopped legs are genuine station stops plus one
two-minute stretch where the provider re-delivered a byte-identical cached position while the train
ran at 90 km/h — nothing downstream can tell those apart, and both vote as "stopped".)

⚠ The classifier already knew: those two traces carry `modeConfidence` **9** and **0**, against 56
for the previous evening's correct ride. Nothing acts on that. ⚠ And `confidenceOf`'s *margin* term
structurally cannot see this case — it measures distance from a bucket **boundary**, and a median
in an empty valley is comfortably mid-bucket. The low confidence came from the accuracy term
instead, by luck rather than design.

⚠⚠ **The deeper fault is that one leg was one vote whatever span it stood for.** `MIN_LEG_MS` is
only a floor; delivered legs on this device ranged 9 s to 383 s. So the statistic described *how
often the providers delivered* rather than how the journey went.

### §37.2 What shipped: a time-weighted median (classifier **v13 → v14**)

`classifyMode` now reduces legs with `timeWeightedMedianOrNull` — each leg counts for the time it
covers. `LegMode.Leg` carries `durationMs` for it. Cadence is weighted on the same basis, because
`withoutImplausibleRun` divides the one median by the other to get a stride and two differently
weighted populations do not make a length.

⭐ **Why duration is the *right* weight, not merely one that fixes this.** A leg's speed error is
the fixes' position error over its span, so `σ_v ≈ ε√2 / dt`: the same 400 m of position error
reads as 44 m/s across 9 s and 0.7 m/s across 600 s. Weighting by `dt` is therefore approximately
**inverse-variance weighting** — long legs win because they are the *better measurements*. It is
the fact `MIN_LEG_MS` already acts on at the bottom end, applied as a gradient instead of a cliff.

⚠ It reduces to the ordinary median exactly when the weights are equal, including the even-count
average, so a uniformly sampled path classifies as it always did and `MODE_BOUNDARIES` did not move.

⚠⚠ **v14 is the first bump on this line that is not promotion-only.** `9` and `13` moved which
`drive` rows become `transit`; this moves which *bucket* a trace lands in, so a v14 verdict is not
comparable with a v13 one on any journey whose legs were unevenly spaced.

⚠⚠ **Accepted limit, stated rather than guarded:** a leg holding more than half a move's duration
decides the verdict alone, and no median property prevents it. That is intended by the paragraph
above — it is the most precise estimate in the path — but a move that is essentially one long gap is
classified on that gap's straight-line speed. `maxResumeGapMs` (15 min) bounds it upstream, and a
straight line across a gap *under*-reports a real path, so the error runs toward the slower verdict.

### §37.3 Measured on the maintainer's raw-fix corpus

Replayed through the **real** `MovementSegmenter` over the 4 538-fix `RawFixJournal` pull (about 23
hours), changing only the weighting: the reported 44.38 km leg goes `cycle` → **`transit`**; a bike
ride split as `cycle` 1.53 km + `cycle` 1.08 km becomes **one `cycle` 2.61 km** (`ModeCoalescer`
joins); the other seven moves are **identical**.

⭐ The journey then reassembles by machinery that already existed: the two legs after the
intermediate station finalise to the same mode, so `ModeCoalescer` joins them into one 29.31 km
`transit` run, and the station stay clears every `collapseTransitStationStops` guard — 10.0 min
against the 12 min `STATION_STOP_MAX_MS`, `labelSource=geocoded` so no user label, no `PlaceRole`,
contiguous within 681 ms. Three rows become one `Transit · 73.7 km`, and the train → metro
interchange still correctly refuses to fold (v21 surface against v22 underground, so
`isVehicleChange`).

⚠ **The replay is not a reconstruction of the device's session** and must not be read as one: live
capture had journal recovery, watchdog restarts and different dwell boundaries, so several rows
differ from the stored ones under *both* weightings. It is a valid A/B of the classifier change —
same input, same code, one variable — and nothing more. The metro leg reads as one `walk` in the
replay under v13 and v14 alike; that is the artefact, not a regression.

⚠⚠ **Rows already stored keep their labels.** `RailRefinementSweep`'s candidate gate is still
`mode ∈ {drive, transit}`, so the two `cycle` traces from that day remain `cycle` after the bump.
`MovementTracePolyline` does carry timestamps and accuracy, so re-running `classifyMode` over a
stored trace is *possible* — but only over the **decimated** path, which is a different measurement
from the raw one capture used (§4). A re-classification sweep is therefore its own slice with its
own question to answer, not a free consequence of this one.

### §37.4 Open

- **The confidence signal is still unused.** A `modeConfidence` of 0–9 is the classifier saying it
  does not know, and `needsRefinement` gives it exactly the authority of a 95. Letting the map
  adjudicate a low-confidence wheeled verdict costs one tile fetch and the map may demote. ⚠ It
  needs the kinematic guard too — a bike path beside a railway is still a bike path, which is why
  `RailRefinement` treats a `cycle` baseline as pedestrian in the first place.
- **`STATION_STOP_MAX_MS` has two minutes of headroom.** The station wait was 10.0 min against
  the 12 min cap. A slightly longer crossing wait breaks the fold, and nothing warns.

---

## §38 — The map is the ground truth, and it is consulted last (2026-09-01)

**Status: design, no code.** Motivated by three consecutive days of the same commute, all captured
with the `RawFixJournal` on, plus every stored trace.

### §38.1 The observation: §37 was necessary and not sufficient

The v14 time-weighted median (§37) was replayed against the whole corpus — 8 259 raw fixes through
the real `MovementSegmenter`, changing only the weighting. Across **25 moves over three days it
changes exactly three things**, and every other verdict — three walks, a run, both 80 km evening
returns, both metro legs, every surface transit leg — is mode-identical:

| leg | v13 | v14 |
|---|---|---|
| §37's 44.4 km leg | `cycle` | **`transit`** ✅ |
| an 8.7 km train leg | `cycle` | **`drive`** — better, but not joined |
| a bike ride | two `cycle` rows | one `cycle` row (`ModeCoalescer` joins) |

⚠⚠ **The 8.7 km row is the finding.** The phantom bike ride is gone, and the journey is *still*
four rows, because `ModeCoalescer` joins adjacent runs only when their final modes are **equal** and
`drive ≠ transit`. Fixing the statistic moved the failure; it did not remove it.

### §38.2 The fault is ordering, not the algorithm

The tier stack is sound and the map tier demonstrably works: measured exclusive rail coverage on
this line was **0.52 / 0.54 / 0.54 / 0.57 / 0.58** across the three days, it promotes correctly, and
it is the one tier allowed to *demote*. The question "is this a train?" has an authoritative answer
on the device already.

⭐⭐ **But a heuristic gates the authority.** A median over per-leg speeds decides the mode; the mode
decides whether the map is ever asked. `RailRefinement.needsRefinement` admits a trace only when
`mode ∈ {drive, transit}`, so a `cycle` verdict is not a candidate — the device log reads
`rail sweep: 0 candidate(s) of 15 trace(s)` on §37's day and `2 candidate(s)` for another day's
**three** consecutive legs of one train. The strongest signal we have is placed behind the weakest.

⚠ The gate is not arbitrary — its KDoc is explicit that most traces are walks and each candidate
costs a tile-fetching op. The objection is not to *filtering*; it is to filtering on a **conclusion**
rather than on a **measurement**.

### §38.3 R1 — candidacy should be kinematic, not modal

Replace the mode test in `needsRefinement` with a physical one no median can poison: a minimum
distance and a minimum cruise (the 85th-percentile leg speed `TransitEvidence` already computes),
e.g. ≥ 3 km and ≥ 8 m/s. This preserves the cost property exactly — every walk is still excluded —
while making the map reachable for any trace fast and long enough to be a train.

⚠ It must stay a *promotion* path: R1 widens who may be **asked**, and changes nothing about what
the map is allowed to conclude. `EXCLUSIVE_PROMOTE_COVERAGE` and the demotion rule are untouched.
⚠ The pedestrian branch of `RailRefinement.refine` still applies — a `cycle` baseline admitted by R1
reaches the coverage tiers, so *"a bike path beside a railway is still a bike path"* has to be
re-argued there, not assumed away. This is the part of R1 that needs the most care.

### §38.4 R2 — a station stop is evidence, and two transit ends should bridge a middle

`isPassThroughStop` currently tests only that the stay is **not** user-meaningful — pure absence of
evidence. Two positive signals are available and unused:

1. **The stop is a station.** `NearestTransitStop` (`map-region-service`) already answers this from
   the tiles' POI layer, tag-filtered, with Control owning the egress. A stay whose centroid sits on
   a rail station, bracketed by two legs that follow rail, is about as strong a pass-through signal
   as exists. ⚠⚠ It returns `""` for *both* "no station" and "could not tell" (§36.6a) — so a blank
   must never be read as "not a station". Unknown is not elsewhere; it must fail toward today's
   behaviour. ⚠ And it costs egress, so it may only be asked for a stay already bracketed by two
   movement legs, never for stays in general.
2. **The ends agree.** Reassembly today requires *N* independent classifications to agree, so one
   short leg landing on `drive` because `TransitEvidence.usableLegCount < MIN_TRANSIT_LEGS` breaks
   the whole chain. Two `transit` ends on the same line should be allowed to **bridge** a
   non-pedestrian middle, rather than the middle having to reach `transit` on its own evidence.

⚠ R2's bridge is bounded by the guards that already exist and must keep them: `ADJACENT_LEG_MAX_GAP_MS`,
`STATION_STOP_MAX_MS`, the user-label and `PlaceRole` vetoes, and `isVehicleChange` — an interchange
is still two rows (§32.12), and a bridge must never cross one.

### §38.5 ⚠⚠ The promote bar is thinner than it looks

`EXCLUSIVE_PROMOTE_COVERAGE` is **0.5** and the measured values on this commute are **0.52–0.58** —
a margin as small as 0.02. Every leg clears it, and a worse GPS day would drop one below, fragmenting
a journey for a reason unrelated to any tier's logic and looking exactly like a fresh regression.

⚠ The bar is a flat constant applied to a path whose *credible* fraction varies: `geometryPath`
already discards coarse vertices, so coverage is measured over what survived, and how much survived
is not part of the judgement. Making the bar aware of that is worth doing independently of R1/R2.
⚠ Do **not** simply lower 0.5 — §3 of `timeline-recognition-and-place-naming.md` calibrated it
against real roads, and lowering it re-opens the road-on-rail case it was set to exclude.

### §38.6 Explicitly not doing

- **GTFS / timetables.** Real licensing and ops weight for a question the map already answers at
  0.52+ coverage. Revisit only if R1+R2 leave a measured gap.
- **A learned classifier.** There is no labelled corpus, and the failure is not discrimination — the
  evidence was correct and unread.
- **New sensors.** Cadence (S3) already separates pedestrian from wheeled and works.

### §38.7 Phases

- **TR-1** — R1, kinematic candidacy. **SHIPPED** — as-built in §38.8.
- **TR-2** — R2a, the station-POI signal in `isPassThroughStop`. **SHIPPED** — §38.10.
- **TR-3** — R2b, the transit-bridge in `collapseTransitStationStops`. **SHIPPED** — §38.9.
- **TR-4** — the credible-fraction-aware coverage bar (§38.5). ⚠⚠ **The design in §38.5 is
  falsified — see §38.11 before starting.**

⚠ Each wants the same instrument §37 used: replay the `RawFixJournal` corpus through the real
segmenter, toggling only the change. ⚠⚠ And per §37.3, that replay is **not** a reconstruction of a
device session — it is a valid A/B and nothing more.

### §38.8 TR-1 as built

`VehicularKinematics.isVehicular(fixes)` — **net displacement ÷ elapsed time ≥ 10 m/s (≈36 km/h),
over a net displacement of ≥ 3 km** — and it is consulted at **both** ends of the gate:

- `RailRefinement.needsRefinement(classification, fixes)` admits on it, alongside the existing
  blind-interval test, instead of on `mode ∈ {drive, transit}` alone;
- `RailRefinement.refine` computes `pedestrian` from it, so a trace admitted by the first rule is
  never then refused by the second.

⚠⚠ **Deriving it twice would be the defect, not the duplication.** Admitted-but-pedestrian is the
worst available outcome: the sweep spends a tile fetch per trace and can conclude nothing from any
of them. One predicate feeds both sites, and `VehicularKinematicsTest.admissionAndPedestrianAgree`
is what says so.

⭐ **Why speed *made good* and not any of the obvious alternatives.** Every other measure is inflated
by exactly the noise this module keeps tripping over — summed path length (§22: 129.8 km against an
≈85 km route), per-leg speed (error over a short `dt`), and the 85th-percentile leg speed that §38.2
originally proposed, which is a high percentile *of those same legs*. Net displacement over elapsed
time is the one statistic fix noise can only **understate**, bounded by the two endpoint errors —
metres against kilometres. A trace can fail this and still be a train; it cannot pass it by being
noisy, and for a rule that widens who gets asked that asymmetry is the whole design.

⚠ **The pedestrian rule was narrowed, not weakened** (the argument §38.3 said was owed).
`RailMatcher.DEFAULT_TOLERANCE_M` is 50 m, so a cycle path beside a railway genuinely matches track
— proximity has never been evidence about a cyclist, and that is *why* a pedestrian baseline is
ineligible for the coverage tiers. TR-1 does not touch that reasoning: a trace escapes the
pedestrian branch only by a fact about **speed**, never about position, and promotion still requires
the map to agree afterwards. Both conjuncts are load-bearing.

**Measured against the maintainer's stored corpus (34 traces, three days).** TR-1 admits **exactly
the three misread train legs** and refuses every walk and every bike ride. ⭐ For all but one the
refusal is *provable without coordinates*: net displacement can never exceed summed path length, so
a stored `distanceM` under 3 km cannot clear the floor — that covers all 13 walks (0.30–1.97 km) and
5 of the 6 rides (0.79–1.53 km). The sixth (3.49 km in 13.0 min) cannot reach 10 m/s even if it
ran perfectly straight. The three train legs (7.99 / 8.72 / 44.29 km, best possible made-good
12.0–17.4 m/s) are **admitted — the map decides**.

⚠ **This is an upper-bound argument over stored rows, not a run of the predicate over the raw
corpus.** The bound is airtight for the refusals, which is the safety-critical direction; the
admissions were confirmed against the two legs whose endpoints were measured. A full replay through
`MovementSegmenter` is still owed and needs the phone attached.

⚠ TR-1 changes **who may be asked**, and nothing about what the map may conclude:
`EXCLUSIVE_PROMOTE_COVERAGE`, the street-running bar and the demotion rule are untouched. It also
does not reassemble a split journey — that is TR-3, and §38.1's 8.7 km case still needs it.

### §38.9 TR-3 as built

`collapseTransitStationStops` gains a **third shape**, beside §33.6's two:

```
leg → stop  → leg   (1) a pass-through station stop
leg → leg           (2) two legs the segmenter emitted back to back
leg → drive → leg   (3) TR-3 — a leg of the same ride the map has not vouched for yet
```

`isBridgeableLeg` decides shape 3, and the bridged movement is added to the ride's legs so its
distance and trace id count — a 60.6 km journey reported as 51.8 km would be a new lie in place of
the old one.

**Why this was needed at all.** Reassembly demanded that *N* independent classifications **agree**:
`ModeCoalescer` joins runs only on equal modes, shape 2 folds only same-mode legs. One leg landing on
`drive` — a short stretch where `TransitEvidence.usableLegCount` fell under `MIN_TRANSIT_LEGS`, or
one the sweep had not reached — split the journey. TR-1 makes the map *reachable*; TR-3 is what
makes the row whole when the map cannot answer, which per §38.5 is the likelier failure.

⚠⚠ **The bridge never overrules the map.** `classifierVersion >= MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED`
means the map looked and said *not rail* — the one verdict the tier stack lets it demote — so the
bridge applies only while the map has not ruled: not yet swept, or swept with no tiles (`refine`
leaves the version unadvanced exactly so "retry later" is expressible). ⚠ The accepted consequence:
a ride folded before the sweep can split again after it. That is new evidence changing an answer,
which is the tier order working, not a flap to damp.

⭐ **The load-bearing guard is physical, not statistical: changing vehicle requires stopping.** Legs
contiguous within `ADJACENT_LEG_MAX_GAP_MS` cannot be two vehicles whatever their modes say, and
that is what makes bridging a *mode disagreement* safe here when it would not be across a gap.

The other three guards, each verified load-bearing by deletion:

| guard | what it stops |
|---|---|
| middle mode is **`drive`** only | "got off and pedalled" — bridging a `cycle` deletes a leg the user rode |
| middle **≤** the confirmed rail either side of it | train + taxi + train becoming one 84 km "Transit" |
| middle's trace must **resolve** | unknown ⇒ never silently swallow, as `moveDistanceM` already holds |

⚠ TR-3 widens what may sit **between** two transit legs and nothing about what may be an *end*: a
`drive → drive → transit` span is still three rows.

⚠⚠ **Still device-unverified, like TR-1.** Both are pinned by unit tests over the measured shape, and
neither has yet run against a real journey — §38.8's owed replay covers both.

### §38.10 TR-2 as built

⭐ **The station was already known, and nothing read it.** §38.4 proposed calling
`NearestTransitStop` on the *stay*. That turned out to be unnecessary:
`TimelineProjectionRepository.withTransitStops` already resolves every transit leg's `originStop`
and `destinationStop` from the POI layer (§32.14). So when the leg **arriving** names the same stop
the leg **departing** names, the stay between them *is* that station — established with no new
lookup, no new cache, and no extra egress, all of which a fresh call would have cost. The fields
were already on `TraceInput` and already populated.

**What it buys.** `STATION_STOP_MAX_MS` (12 min) was priced against *"swallowing a real 20-minute
errand between two train legs"* — a risk assessed without knowing *where* the stop was. Naming the
platform on both sides moves the question to *how long does a train sit at a platform*, which on a
single-track regional line runs past 12 minutes. So a confirmed station gets
`CONFIRMED_STATION_STOP_MAX_MS` (20 min).

⚠ Measured, and the reason this phase exists at all: the reported station wait was **10.0 minutes
against a 12-minute cap**. The fold worked with two minutes to spare; the same journey on a day with
a longer crossing wait would have split for a reason unrelated to the journey.

⚠⚠ **It may only ever widen, and the blank case is most of them.** `nearestTransitStop` returns
`""` for *both* "no stop nearby" and "could not tell" (§36.6a), and a leg ending mid-line between two
stations is legitimately nameless. Requiring station evidence would therefore stop folding the
ordinary case — so no evidence means `STATION_STOP_MAX_MS`, exactly today's behaviour. Unknown is not
elsewhere.

⚠ Both names must be non-blank **and equal**. Equality is what makes this evidence about *this stay*
rather than about the legs: two different stations either side means ground was covered during the
stop, which is not a pass-through however station-like both ends look.

⚠ Only guard 3 moves. `hasUserLabel`, the `PlaceRole` veto, the contiguity bounds and the ongoing-stay
veto are untouched — and a station that accrues meaning over time stops qualifying regardless of how
long the train waits.

⚠⚠ **`CONFIRMED_STATION_STOP_MAX_MS` is the weakest-reasoned constant in §38 and wants corpus
calibration.** 20 minutes is argued from operational holds, not measured from stops — the corpus
contains exactly one station wait.

### §38.11 ⚠⚠ TR-4's design is falsified — do not build §38.5 as written

§38.5 proposed a coverage bar aware of "how much of the path was credible", on the assumption that
`geometryPath` leaves the stored polyline mostly credible. **Measured on the maintainer's store
(12 swept vehicle traces, 255 points), that assumption is wrong:**

| accuracy of scored point | share |
|---|---|
| ≤ 50 m (`RailMatcher.DEFAULT_TOLERANCE_M`) | **28 %** |
| 51–200 m | 70 % |
| > 200 m | 2 % |

**72 % of the points the matcher scores are less accurate than the tolerance it scores them at**, and
on one leg only **2 of 13** points are inside it. Excluding the rest would leave most traces with two
to six points — too few for a coverage fraction to mean anything, so the rule would either no-op via
its own minimum-sample fallback or fire on noise.

⭐ **It also reframes §38.5's premise.** The observed 0.52–0.58 exclusive coverage is not a trace
"barely following rail" — it is most points being asked a question they cannot answer at 50 m. The
natural fix is then a **per-point tolerance scaled by that point's accuracy**, which has direct
precedent in `bearingLegBarFor` (§32.15, `max(floor, 5 × median accuracy)`).

⚠⚠ **That is not a safe change to make blind.** Widening tolerance raises coverage for a road beside
a railway exactly as it does for the railway, and the road-exclusion property is what
`EXCLUSIVE_PROMOTE_COVERAGE` rests on (§3 of `timeline-recognition-and-place-naming.md`: real roads
reach 0.8-exclusive only 0.83 % of the time **at 50 m**). Re-running that measurement at a scaled
tolerance needs the demo road archive, which is not reachable from a store dump.

**TR-4 is therefore blocked on an experiment, not on implementation time.** It needs the match
recomputed under tolerance variants against both the rail corpus and the road archive.

---

## §39 — The promote bar sits at the *median* of the true positives (2026-09-08)

Reported: the maintainer's usual regional-train commute had been recorded nearly correctly, except
on a few days. Measured against the raw-fix corpus (34 891 fixes, ten days), the store, and
`timeline.log`'s per-trace sweep verdicts.

### 39.1 ⚠⚠ The mid-ride stops are REAL — the segmenter is not at fault

Nine morning commutes; the ride is cut by a confirmed dwell on six of them, always ~5 min. The
tempting reading is §34's frozen anchor. **It is wrong for 7 of the 8 windows.** Ground truth from
fine fixes only (≤ 50 m), independent of what the pipeline kept: each stop held 2–9 fine fixes over
2.3–5.0 min, within an extent of 6–43 m.

The line is single-track and crosses trains at those loops. `minDwellMs` is behaving correctly, and
tightening dwell detection here would delete a fact. (One window is the genuine frozen anchor: 15
repeated fixes at ±2200 m — but the standstill was real there too.) ⭐ The rule from §36.5 in a new
shape: **a correct component can look defective when a later stage mishandles its output.**

### 39.2 The read side already folds them — and one demotion disables the whole walk

`collapseTransitStationStops` handles exactly this, which is why the ride reads correctly on five of
the nine days. Every remaining split traces to `RailRefinement` demoting a leg to `drive`, which
costs **two** things at once:

1. the walk *starts* at `transitModeOf(head) == transit`, so a demoted **head** leg means the fold
   loop never runs at all; and
2. a `drive` leg carries no `originStop`/`destinationStop`, so `stationStopCapFor` cannot reach
   `CONFIRMED_STATION_STOP_MAX_MS` (20 min) — and one day's **16.5-minute** crossing wait then also
   failed the plain 12-minute cap.

That day as stored: `drive 21 km` · a 16.5-minute station stay · `transit 55 km`. Both blockers, one
cause.

### 39.3 ⭐⭐ The measurement: the bar is inside the true-positive distribution

Ten legs ≥ 40 km — nothing else in the corpus is that long, so all ten are the same train:

```
0.39  0.44  0.46  0.47  0.50  0.52  0.53  0.54  0.60  0.67
mean 0.512   sd 0.081   → 4 of 10 demoted to `drive`
```

`EXCLUSIVE_PROMOTE_COVERAGE` is **0.50**, i.e. **−0.15 σ from the mean of the class it exists to
accept**. Its calibration — *"0.5 sits below the noisiest real train with margin"*, with two real
trains at 0.58/0.61 — was fitted to **two** samples; at n = 10 the noisiest real train is **0.39**
and the margin is negative. The same journey scores 0.44 one day and 0.53 the next: **that variance
is in the instrument, not the route.**

⚠ This does **not** contradict §34.5's *"the leg scored low because it had been cut — fix the cut,
not the bar"*. That was true of the leg it described. It is not true here: an **uncut** 82 km leg
scored 0.44 and an uncut 81 km leg 0.47. Both diagnoses are needed, and which applies is a question
about the specific leg.

### 39.4 What shipped: a second disjunct in metres, not a lower fraction

Lowering the fraction would drag every **short** trace down with it, and short traces are where the
false positive lives — a level crossing or a road running beside track reaches a high fraction
cheaply. So, on §36.8's pattern (`isBraking` → `isAbsorbable`), the strict rule keeps deciding every
case it already decided and a second disjunct is added beneath it:

```
excl >= 0.50  ||  (excl >= 0.30 && exclusiveMatchedMeters >= 8_000)
```

`RailMatch` gains `exclusiveMatchedMeters`: each matched point weighted by half the segment on either
side, so the weights over the whole path sum to its length exactly. ⚠ The `0.30` floor is load-bearing
— without it, 8 km of incidental contact spread along a 500 km drive would promote.

⭐ **This is also the route past §38.11's blocked experiment.** TR-4 wanted a *wider tolerance*, which
raises coverage for a road beside a railway exactly as it does for the railway — hence the need to
re-run the road archive first. Requiring an absolute length **at the unchanged 50 m tolerance** leaves
the road-exclusion property untouched: what changes is how much evidence is demanded, not what counts
as evidence. §38.11's reframing (most points are less accurate than the tolerance, so the fraction's
denominator is polluted) is precisely why a fraction was the wrong statistic here.

⚠ Honest limit: **the corpus contains no car journeys**, so 8 km is reasoned from grade separation —
a road vehicle cannot be on heavy rail or subway at all, let alone for kilometres — and is *not*
measured against a road false-positive rate. It sits an order of magnitude past incidental contact
and below the 9.2 km of the weakest genuine train leg observed.

`MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` 21 → **23** (and `…_UNDERGROUND` 22 → **24**, with `22`
joining `LEGACY_UNDERGROUND_VERSIONS` — since removed, §32.15) so every previously **demoted** trace is re-offered — those are
exactly the rows that need re-deciding.

### 39.5 Verified against the corpus

Simulated over all nine commutes against the real store rows, three changed: a `DRIVE 50km` ·
station stay · `TRANSIT 25km` split became **`TRANSIT 75km`**; a demoted `DRIVE 82km` became
**`TRANSIT 82km`**; and a `DRIVE 21km` · station stay · `TRANSIT 55km` split became
**`TRANSIT 76km`**.

Six days byte-identical; no regressions. `cycle`/`walk` legs are cv14 — never swept, and the
`pedestrian` guard keeps them unreachable from any sweep-side change.

### 39.6 Two things this pass found and did not fix

- **`FixHygiene.inTimeOrder` now drops 28.6 % of all fixes** (9 968 / 34 891), median lateness **60 s**,
  p90 91 s. §22 measured "up to 9 s" and rests on *"a dropped fix is never the only evidence of
  anything: it arrived late precisely because a fix for that moment already came from the other
  provider"* — a claim that does not survive a minute of skew. Measured **inert for these splits**
  (7 of 8 windows have no contradicting dropped evidence), so this is a re-derivation to schedule,
  not a defect to chase. The likely cause is `setMaxUpdateDelayMillis` batching per provider.
- **`isVehicleChange` is only underground-vs-surface**, so a surface train → suburban-rail change at
  an interchange folds the arrival away (on one day already; on a second once §39 lands). A train →
  metro change is correctly kept apart, because only that one differs in `classifierVersion`.

### 39.7 ⚠⚠ Two harness traps, both of which manufactured a false finding

- The committed `CommuteReplay` **sorts** fixes; production **drops** them
  (`AndroidLocationCaptureSource … .inTimeOrder()`). A replay that sorts is not the pipeline.
- Matching sweep-log rows to traces by `(km, pts)` is **not unique**, and the log **truncates** km.
  One collision produced an apparent `cycle → transit` regression that does not exist, and a rounding
  mismatch hid one day's improvement entirely. ⭐ Both were caught only by re-deriving the number a
  second way — see [`verification-harness-manufactures-findings`].

### §39.8 — the ride still split, and it was 231 m of coordinate error (2026-09-09)

After §39 shipped and the device re-swept, eight of nine commutes read as one row. **One still
split**, on the 16.5-minute crossing wait — and the reflex ("relax the caps further") is wrong,
because the mechanism built for exactly this case was already there and simply did not fire.

`stationStopCapFor` raises the cap 12 → 20 min when the arriving leg's `destinationStop` equals the
departing leg's `originStop`. 16.5 min is comfortably inside 20. Measured on the stored rows: the
arriving leg's **end** sits **217 m** from the station and resolves to its name; the departing leg's
**start** sits **531 m** away and resolves to `""`.

`TRANSIT_STOP_RADIUS_M` is 300 m, so one end named the platform and the other returned blank. The old
rule required **both**, so it fell back to the 12-minute cap and refused.

⚠⚠ **The departing endpoint is a fix reporting ±300 m accuracy**, kept as a terminal fix by §32.6
precisely because dropping it would truncate the journey. The ride was therefore split by a
coordinate missing the radius by **231 m — less than that fix's own stated uncertainty** — while the
other end named the same platform from 217 m away.

#### What shipped: blank is silence, not a competing claim

`stationStopCapFor`'s own KDoc already said *"unknown is not elsewhere"* — and then applied it only to
the both-blank case. Applied consistently:

| arriving | departing | cap |
|---|---|---|
| blank | blank | 12 min — no evidence, exactly the pre-TR-2 rule |
| named | blank *(or the mirror)* | **20 min — one confirmation, one silence** |
| named | same name | 20 min |
| named | **different** name | 12 min — a contradiction still refuses |

⚠⚠ **This reverses `StationStopEvidenceTest.oneNamedLegIsNotEvidence`** ("half a signal is not a
signal"), which is why that test is rewritten rather than deleted — the reasoning it encoded is
answered, not dropped. `twoDifferentStationsAreNotAPassThrough` is now the boundary of the new rule
and carries a comment saying so.

⚠ Explicitly **not** done: widening `TRANSIT_STOP_RADIUS_M` (its calibration records 300 m as where
the first false positive appears — an office 252 m from a metro station — so widening degrades naming
everywhere to fix one case), and **not** raising either cap (that would price an *unexplained* stop
the same as a *confirmed* one, erasing the distinction the two constants exist to draw).

What still bounds it: the stay is a `Visit`, so the phone did not move during it; and the
`hasUserLabel` veto, the `PlaceRole` veto, the both-legs-transit test and `isVehicleChange` all still
run. Each arm of the new `when` is mutation-tested — reverting any one of the three reddens exactly
one distinct test.
