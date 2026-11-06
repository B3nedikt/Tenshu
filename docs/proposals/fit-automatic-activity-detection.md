# Fit automatic activity detection — logging the run you didn't press start on

**Status:** v0.4 — **design + file-level implementation plan; FA-0 and FA-1 SHIPPED (2026-08-09).**
**FA-4's storage field SHIPPED (2026-10-29):** `MovementTrace.steps` — the move's total pedometer
steps, `-1` = not counted (never `0`), appended in place at timeline-service **v1**, before the
production reset; promoted workouts carry it. ⚠ It stores the *count*, not cadence (cadence =
steps ÷ span), and every "timeline-service 2→3" note below is stale — that numbering predates the
2026-10-27 version reset. §14 is the
canonical handoff (the `sync-merge-strategies.md` §5b convention); §§1–13 are the design.
Companion to
[`fit-app.md`](fit-app.md) (the recorder and import tracks this layers on top of) and
[`timeline-movement-capture.md`](timeline-movement-capture.md) (whose M/S-track classifier is
the detector this doc consumes rather than rebuilds). Closes the gap named in
`fit-app.md` only by its absence: **every workout in Fit today begins with a tap.**

**Trigger (2026-08-09):** *"Our Fit app does not have the 'automatic' recognition of runs, walks
etc the Google one has. What would we need for this feature? How to achieve similar battery
efficiency then Google?"*

**v0.2 (same day)** — reworked after two review questions: *"we basically extract the
recognition logic so it is usable by both"* and *"what should the behaviour be if the user
installed Timeline but not Fit and vice versa — we don't want to record stuff double?"*
That surfaced a **double-write hole in v0.1** (§5.0), split one decision into two
(§7 extraction vs §6 ownership), and — via the op sweep in §8 — turned up a prerequisite
that v0.1's central argument silently depended on: ⚠⚠ **there is no way to delete a
`Workout`** (§5.2).

---

## 0. The finding that reframes the whole question

**The detector is already built. It is in Timeline, and Fit cannot see it.**

`MovementSegmenter` + `LegMode` (`:timeline-shared-data` `commonMain/.../capture/`) already
classify walk / run / cycle / drive / transit / flight on-device, from a pedometer-plus-speed
2-D rule, with adaptive duty-cycled sensing and a significant-motion wake — the S1–S8 track,
complete. That is functionally the same job Google's Activity Recognition
API does, minus the parts only Google's process placement can buy (§9).

Meanwhile Fit has **no** automatic detection of any kind. `Source` has five values
(`FitModel.kt:31`) and the closest to "detected" is `Recorded`, which means *the user pressed
start*. `RecordViewModel.start()` is `engine.start(selectedActivity)` — an explicit tap on a
chosen `ActivityType`.

So the work is a **bridge plus a promotion policy**, not a sensing stack.

| | Movement classifier | Background sensing | Writes `Workout` |
|---|---|---|---|
| **Timeline** | ✅ `MovementSegmenter`/`LegMode` | ✅ location FGS + pedometer | ✅ (`TimelineImportWriter.kt:104`) |
| **Fit** | ❌ | ❌ (and must stay that way — §2) | ✅ (`FitProjectionRepository.kt:340`) |

---

## 1. What is actually missing

### 1.1 Fit cannot read `timeline-service`

Not wired, but nothing architectural is in the way:

- `FitClientModule.kt:48-67` registers `health`, `mapRegion`, `sync`, `preference` — no timeline.
- `:timeline-service*` is on no fit module's classpath (`fit-shared-data/build.gradle.kts:44-75`).
- **Control's proxy already hosts both** (`control-shared-proxy/.../ProxyModule.kt` ~119). There
  is **no per-app service allowlist** — the only allowlist (`ProxyModule.kt:431`) maps
  clientId→appId for *registration*.
- The mirror image already ships: `timeline-shared-data/build.gradle.kts:49-50` depends on
  `:health-service` + `-client`, and `TimelineProjectionRepository.kt:967-985` reads workouts
  cross-service. `SubjectRef.kt:70-75` already declares a `WORKOUT` pointer from
  timeline-service into health-service. Nothing points the other way.

Cost: three gradle deps and two lines in `FitClientModule.kt:49`.

### 1.2 `MovementTrace` carries geometry, not effort

Every field is bounding box, endpoints, `distanceM`, `startAt`/`endAt`, `medianSpeedMmPerS`,
`mode`, `modeConfidence`, `classifierVersion`, and the decimated `polyline`
(`MovementTrace.kt:129-155`). **No step count, no cadence, no heart rate.**

`Workout` (`Workout.kt:108-135`) needs six non-defaulted fields — `workoutId`, `activityType`,
`source`, `start`, `end`, `durationSec` — all derivable. `distanceM` maps directly. `steps`,
`avgHr`, `activeEnergyKj` default to `0`.

So a promoted workout is **thin**: no HR (there is no strap), no steps, energy at best
estimated. Google Fit's auto-detected sessions are equally thin. `steps` is recoverable
(§1.5).

⚠ Adding a cadence field to `MovementTrace` is a positional reshape ⇒ **HR#9 fires**:
`timeline-service` is `version = 2` (`TimelineService.kt:52`), so bump to 3 and register the
`SchemaMigration` step in the same PR. `SchemaShapeBaselineEnforcementTest` names the field if
it is forgotten.

### 1.3 Nothing decides when a trace is workout-worthy

`MovementDetectionConfig` gates at `minMoveDistanceM = 250.0` / `minMoveDurationMs = 3 min` —
a *trip* threshold. A workout threshold is stricter and is a product call (§10 Q1).

### 1.4 Dedup does not exist

`MomentReconciler.kt:63-67` folds `KIND_VISIT` and `KIND_ACTIVITY`; `MOVEMENT` falls to `else`
and is always its own canonical. The designed fix (`timeline-movement-capture.md:452-464`,
"I6 extension") is unbuilt, and §5 shows auto-writing changes its shape.

### 1.5 ⭐ Intraday steps are read today and thrown away in the same expression

The single cheapest finding in this doc. Both importers already read step data at exactly the
granularity a detector needs, then collapse it before anything is stored:

```kotlin
// HealthConnectImporter.kt:357-358
readAll(c, StepsRecord::class, scan)
    .groupBy { it.startTime.day() }.mapValues { (_, l) -> l.sumOf { it.count } }

// HealthKitImporter.kt:418-420 — same shape over HKQuantitySample
readAllSamples(hk, type, predicate)
    .groupBy { it.startDate.day() }.mapValues { (_, l) -> l.sumOf { … } }
```

`StepsRecord.startTime`/`endTime` and `HKQuantitySample.startDate` survive right up to
`.groupBy`. The grant, the query shape and the paging all exist and are exercised in
production (a real device log shows `read StepsRecord: 4854 record(s) over 5 page(s)`).

And the destination exists too: **`HealthSample`** (`"$metric::$timeMs"`, `time: UnixInstant`,
string `value` + `unit`, `HealthSample.kt:94`) already carries timestamped series for
`HeartRate`/`Weight`/`Height`/`BloodOxygen`/`RespiratoryRate`. Nothing has ever written a step
metric into it. **Adding one is a new metric *value*, not a schema change** (§8.3).

⚠ Today's only step-bearing persisted types are `DailyActivity` (keyed `YYYY-MM-DD`, so
sub-daily rows are structurally impossible) and `Workout.steps` (one scalar per session).

⚠ iOS never calls `queryPedometerData(from:to:)` anywhere in the repo — `IosRecordingEngine.kt:205`
uses `startPedometerUpdatesFromDate(NSDate())` only. The historical path is unbuilt but standard.

---

## 2. Why the answer is not "add detection to Fit"

`milestones.md` (~500): **no background location in Fit, ever** — *"Passive movement capture
belongs to Timeline… concentrating the Health grant **and** background location in one app is
exactly the blast-radius concentration that decision rejects."* `fit-app.md` §9.8 repeats it.

⚠ So Fit's `ACTIVITY_RECOGNITION` declaration — present today for the recorder's
`TYPE_STEP_COUNTER` — is the only sensing grant this feature may assume in Fit's process. §9.2
shows it is also the only one it needs, and §6 shows it is mostly not needed either.

---

## 3. D1 — Ask once, then never again

**The frictionless requirement is load-bearing, and the obvious design fails it.**

The first shape considered was a per-detection suggestion card — *"Looks like you ran 5.2 km.
Save as workout?"* — justified by the RC-3 idle-trim precedent (`fit-app.md` §13.3,
*"Suggestion, never automatic"*).

**That precedent was misapplied.** Idle trim is a *destructive* edit (it discards recorded
fixes) inside a flow the user already started; asking is correct there. Auto-logging is
**additive and reversible** — the remedy for a wrong row is deleting it. Different risk class.

And a per-detection card fails the product goal: a prompt seen three times a week becomes a
prompt dismissed without reading, which is *more* friction than Google Fit, not less.

**Decision: one prompt in the app's lifetime, then silent forever** — what Google does.

Ask at the first moment a detection *would* have fired, when the context is concrete:

> **We noticed a 32-minute walk this morning.**
> `[Log these automatically]` · `[Just this one]` · `[No thanks]`

- Backed by a **`RealmSetting`** in Fit's settings, so it stays changeable — never re-*asked*.
- `RealmSetting` not `DeviceSetting`: a user preference, not a device capability. Multi-device
  promotion is safe by D2.
- Rides the existing `PreferenceService` (§8.2) ⇒ **no schema change, no new op**.

⚠⚠ **This decision has a hard dependency v0.1 did not notice.** "Auto-logging is fine because
it is trivially reversible" is the entire argument for not asking — and **Fit has no delete
path for a workout, nor does any op exist to build one on** (§5.2). Until FA-0 lands, D1 is
unjustified. It is not merely a missing convenience; it is the premise.

⚠ Open: what a dismissed prompt means (§10 Q3).

---

## 4. D2 — Promotion is a deterministic, content-addressed function of the trace

The hazard with any derived row in this realm is the derived-verdict broadcast loop (99.8 % of realm traffic): two devices recomputing a projection
and broadcasting the results at each other. **Make that unrepresentable rather than policing it.**

```
workoutId    = "detected:${trace.traceId}"     // traceId is itself cap-trace:<startMs>
activityType = map(trace.mode)                 // run|walk|cycle only — never drive/transit/flight
source       = Detected
start/end    = trace.startAt / trace.endAt
durationSec  = endAt - startAt
distanceM    = trace.distanceM
```

Every field derives from trace content. `WorkoutDataIdProvider` is content-addressed, so two
devices promoting the same synced trace mint the **same row id with the same content** —
convergent and idempotent, no election, no receipt, no dedup pass. This is
`core-schema-evolution.md` §8.3's compaction argument in miniature: re-emission is free when it
is convergent.

⚠ `MovementTrace` is `Syncable(Eager)`, so traces reach every device — which is what makes
cross-device promotion converge. It also means the promoter must be a pure function of the
*trace*, never of device-local state (which phone was in the pocket, what the local pedometer
baseline was). Any device-local input reintroduces the broadcast loop.

⚠ `classifierVersion` is stamped on every trace and the S-track has already reached 11. A
re-classification changes the correct `mode` without changing `traceId`. Record the
`classifierVersion` acted on so a later sweep can revise its own output; **do not put it in the
`workoutId`**, or re-classification mints a second workout instead of correcting the first.

### 4.1 ⭐ The route is not copied

`workoutId` embeds `traceId`, so the trace is recoverable from the workout by string
manipulation alone. A promoted workout therefore needs **no `Workout.Streams` blob** — Fit
reads the route from `MovementTrace.polyline` on detail open, exactly as
`TimelineProjectionRepository.kt:497-503` already does. No duplication of the geometry, and
`recordWorkout`'s one-blob-per-frame constraint never binds (§8.1).

---

## 5. D3 — Supersession, not pre-checking

### 5.0 ⚠⚠ v0.1 had a double-write hole

v0.1 paired trace promotion with an independent Tier-1 pedometer detector in Fit. With both
apps installed, one walk yields **two** workouts — `detected:cap-trace:…` from the trace and
`detected:steps:…` from the pedometer. v0.1 noted this in an open question and filed it as an
edge case. It is the common case: it fires for every user who installs both apps. §6 is the fix.

### 5.1 The ordering problem

Auto-writing makes dedup against *imports* an ordering problem:

> The phone detects the run and writes it at 08:40. The watch syncs the same run to Health
> Connect at 09:15. There are now two.

A read-and-skip check at write time **cannot fix this** — the competing row does not exist yet.
Delaying promotion until "nothing else has claimed it" is worse: an arbitrary window, wrong
whenever a watch syncs late, and it makes the feature feel broken precisely when it works.

**Apply the priority rule the M-track already wrote down**
(`timeline-movement-capture.md:452-464`): *"a health `Workout` supersedes the captured
`MOVEMENT` … unless the workout has no GPS and the capture has a route."* Lifted from Moments
to `Workout` rows:

1. Add **`Detected`** to `Workout.source`. ⚠ This is a *value*, not a field — `source` is
   already a `string` carrying `HealthKit`/`HealthConnect`/`Fit`/`Tcx`/`Gpx`/`Manual`. **No
   positional reshape; HR#9 does not fire.**
2. The Health Connect / HealthKit importers gain a supersession pass: an incoming workout
   overlapping a `Detected` one **removes the detected row** — unless the import has no route
   and the detected one does, in which case the route wins.

Order-independent, so a watch syncing six hours late still resolves correctly.

⚠ Overlap needs a tolerance, not equality — a watch's start and a phone's first fix are minutes
apart. Recommend "any temporal intersection plus mode compatibility", not a centre-distance metric.

⚠ Removal must not be an in-place rewrite of the detected row to match the import: the row is
content-addressed on `traceId`, so a second device would promote it straight back (D2).

### 5.2 ⚠⚠ The prerequisite: a `Workout` cannot be deleted

The op sweep (§8.1) found that `:health-service` exposes **nine operations, all upserts or blob
reads. There is no `DeleteWorkout`, no tombstone op, and no per-row delete anywhere.**

The only deletion path is the generic core op
`DeleteRealmData(typeId, createdBefore)` (`core/.../realmdata/DeleteRealmData.kt:54`), which is
**whole-type and realm-wide** — `typeId = "tenshu/health/workout"` deletes *every* workout. And
its narrowing does not work here: `HealthServiceCommonModule.kt:87-94` records that
`Workout.Start` is a field alias rather than a real value property, so `Entity.getProperty`
cannot read it and **`createdBefore`-narrowed deletion of workouts matches nothing.** Fit has no
delete call site at all.

So D3 is unimplementable today, and — more importantly — **so is D1** (§3).

**Decision: `DeleteWorkout(workoutId)` is FA-0, a prerequisite phase, not a follow-up.**
**One row per call** — no list parameter (§10 Q5).

It is independently valuable and arguably overdue: a user cannot currently delete a workout
they recorded by mistake. Auto-detection makes it mandatory, because false positives are
inherent to detection and "just delete it" is the whole reason D1 gets to skip the prompt.

Alternatives considered and rejected:

- **A `superseded` flag on `Workout`** — a positional reshape (HR#9: health-service is
  unversioned/v1, so it would take the first version bump *and* a migration), and it forces
  every existing reader to filter or silently show hidden rows.
- **Client-side read filtering, leaving the row** — no op, no schema change, but it leaves rows
  that sync forever and are never shown, and every future reader must re-derive the filter.
  This is the shape that rots.
- **Never write; render detections as a view over traces** — kills the delete problem, the
  convergence problem and the bloat problem in one move, and was genuinely tempting. **Rejected
  because it does not deliver the feature**: `WorkoutActivityRollup` and active-minutes are
  maintained proxy-side over stored `Workout` rows (`HealthProxyInitialization.kt:394-477`), so
  a client-side view would show detected walks that never count toward the rings. An
  auto-logged workout that does not count is not auto-logging.

### 5.3 Timeline-side rendering is a separate, smaller problem

Promotion means Timeline reads the workout back and renders an `ACTIVITY` moment beside its own
`MOVEMENT` moment for the same run. That is `MomentReconciler`'s cross-kind fold (§1.4), it
lands on the Timeline side which is already wired to read health, and it is a *rendering*
duplicate rather than a data duplicate. It does not block FA-2.

---

## 6. D5 — Ownership: one sensor, and the install matrix

### 6.1 Extraction does not solve double recording

The review framed this as *"extract the recognition logic so both can use it."* Extraction is
right (§7) and it solves **code** duplication. It does not solve **runtime** duplication —
two apps each linking the same library and each running their own capture loop means two
location subscriptions and two pedometer listeners, which is worse than today.

These are separate decisions: §7 asks *where does the code live*, §6 asks *which process senses,
once*.

### 6.2 The move that dissolves the coordination problem

**Fit's detector is retrospective, never live.** Timeline senses in real time; Fit looks
*backwards* over step history at open/refresh. There is then no race — by the time Fit looks,
the trace either exists or it does not, and a plain read-check replaces process coordination.

§1.5 is what makes this nearly free: wherever Health Connect or HealthKit is present, the
intraday step data is **already being read** and merely discarded. No new background sensing at
all. Only de-Googled Android without Health Connect needs actual polling (§9.2).

**The rule:** a step-derived detection is suppressed when a `MovementTrace` already covers that
window. Cheap, because Fit is looking backwards.

### 6.3 The install matrix

| Installed | Who senses | Written | Fit shows |
|---|---|---|---|
| **Timeline only** | Timeline (location FGS + pedometer) | `MovementTrace` only — **no `Workout`** (§6.4) | — |
| **Fit only** | nobody continuously; Fit reads back step history | `Workout(Detected)`, no route | routeless detections |
| **Both** | Timeline only; Fit's step detector stands down where a trace covers the window | trace → promoted `Workout` | detections with route |
| **Neither** | nobody | — | — |

### 6.4 Timeline-without-Fit: do not promote

**The trace is the durable fact; `Workout` is a lens-specific projection.** Timeline should not
write `Workout` rows nobody will read — that is exactly the derived-row traffic the broadcast loop punished,
and it would put health rows in the realm of a user who never installed a health app.

⭐ The consequence is a *better* product outcome, not a compromise: install Fit six months later
and it promotes the whole trace history at once — *"here's your last six months of walks"*
instead of an empty screen. Nothing was lost by not writing it, because the trace is the source
of truth and syncs regardless.

⚠ Backfill is the one place this costs something: promoting a long trace history is a
full-relation scan (there is **no search op on `traces`** — §8.1) plus N writes. Use
`recordImportedHealth`'s batched path (§8.1), and note §4.1 means no blob rides along.

### 6.5 Why not "Control owns capture"

The obvious shared answer, and rejected:

- **It does not translate to iOS.** On route-1 each first-party app *is* its own in-process
  proxy (`FirstPartyLocalRuntime`); there is no shared Control doing background work for other
  apps. You would end up with two architectures — Control-owned on Android/desktop, app-owned on
  iOS — for one feature.
- **It concentrates the worst pair.** Control holds A1. Adding continuous background location
  to that process is the same argument `milestones.md` uses to keep location out of Fit, only
  stronger.
- Timeline would also gain a hard dependency on Control for its core function, which
  `timeline-ingestion.md` I3b deliberately avoided (Timeline runs `InProcessTimelineProxy`
  standalone, with no scheduler substrate).

---

## 7. D6 — What actually gets extracted

Smaller than "the ingestion logic", because §6.2 means Fit never does live capture.

| Component | Today | Moves? |
|---|---|---|
| `LegMode` / `classifyMode` / `MODE_BOUNDARIES` / `PEDESTRIAN_MIN_SPM` | `:timeline-shared-data` commonMain | ✅ **shared** — both consumers classify |
| Promotion policy (trace\|steps → `Workout`, thresholds, suppression rule) | does not exist | ✅ **shared, new** |
| `MovementSegmenter` / `ModeSplitter` / `MovementTracePolyline` | `:timeline-shared-data` commonMain | ❌ stays — only Timeline segments a live fix stream |
| `LocationCaptureSource` / `LocationCaptureEngine` / `CapturePace` | `:timeline-shared-data` | ❌ stays — Fit does no live capture |
| `VisitDetector` / `FixClustering` / `CapturedVisitWriter` | `:timeline-shared-data` | ❌ stays — dwells are a Timeline concern |
| `StepCounter` / `AndroidStepCounter` | `:timeline-shared-data` | ❌ stays; ⚠ §9.2 needs a *different* component |

The shared half is already pure `commonMain` with no Compose and no platform dependency — it is
in a Timeline-named module, not a Timeline-shaped one. A new Compose-free
`:activity-recognition` (or a rename of the existing seam) is a mechanical move, on the
`:basemap-core` precedent (`ios-fit-timeline-maps-parity.md` P2a — *"splitting the module, not
the framework"*).

⚠ Do not extract the sensor seams "for symmetry". A shared `StepCounter` interface with a
streaming Android actual invites the second live detector §6.1 is trying to prevent.

---

## 8. The Tenshu surface — services, ops, and what is new

### 8.1 Operations

⭐ **The core feature needs exactly one new op, and it is one that should exist anyway.**

| Op | Service | Status | Used for |
|---|---|---|---|
| `recordMovementTrace` (`tenshu/timeline/service/recordMovementTrace`) | timeline | ✅ exists | Timeline writes the trace (unchanged) |
| — *(chain DSL read on `timelineService.traces`)* | timeline | ✅ no op needed | Fit reads traces to promote |
| `recordWorkout` (`tenshu/health/service/recordWorkout`) | health | ✅ exists | writes one promoted `Workout` |
| `recordImportedHealth` (`tenshu/health/service/recordImportedHealth`) | health | ✅ exists | **batched backfill** — 7 lists, `BATCH_LIMIT` 500 (`HealthImportWriter.kt:268`) |
| `recordHealthSample` (`tenshu/health/service/recordHealthSample`) | health | ✅ exists | intraday step samples (§8.3) |
| `writeSetting` (`tenshu/preference/service/writeSetting`) | preference | ✅ exists | the D1 toggle, via `preferenceService.set(...)` |
| **`deleteWorkout(workoutId)`** | health | ⚠⚠ **NEW — FA-0** | §5.2; D1 and D3 both depend on it |

⚠ `recordImportedHealth` is **deliberately summary-only** (`RecordImportedHealth.kt:27-29`) — a
workout carrying a blob must go through `recordWorkout` one at a time. §4.1 means promoted
workouts carry no blob, so the batched path is available to the whole backfill. That is not a
coincidence worth relying on silently: if §4.1 is ever reversed and routes get copied into
`Workout.Streams`, backfill drops to per-record writes.

⚠ **There is no search op on `traces`** (`TimelineProjectionRepository.kt:479-481`). Reading
traces is a full-relation scan with a field projection, filtered client-side. Acceptable for a
retrospective pass; ⚠ it does not scale the way a keyed read does, and the F1 ordered-window
fusion (`sortBy().take()`) is the lever if it becomes hot.

⚠ `GetWorkoutStreams` is the only non-`IoOperation` in health-service and is registered on
`Workout.Schema`, not the service. Unused here (§4.1), but relevant if routes are ever copied.

### 8.2 Reads

All plain chain DSL — **no new read surface**:

```kotlin
// Fit → timeline (new dependency, existing mechanism). ⚠ polyline deliberately not projected.
client.timelineService.traces
    .select { fields(TRACE_ID, MODE, MODE_CONFIDENCE, DISTANCE_M, START_AT, END_AT) }
    .flow()

// Fit → health, existing shape (FitProjectionRepository.kt:1213)
client.healthService.workouts.sortByDescending { start }.take(limit)
    .select { fields(*WORKOUT_FIELDS) }.flow()

// the D1 toggle, existing shape (TenshuFitGoalPreferences.kt:18)
prefs.read(FitPreferences.AutoDetectActivities)
    .onStart { emit(FitPreferences.AutoDetectActivities.default) }   // ⚠ read() emits nothing on an unset realm
```

### 8.3 Schema changes

**Two new string *values*, zero positional reshapes** — HR#9 does not fire for FA-1..FA-3:

| Change | Kind | HR#9 |
|---|---|---|
| `Detected` added to `Workout.source` | new value of an existing `string` field | ❌ no bump |
| `Steps` added to the `HealthSample.metric` vocabulary | new value; `HealthSample` is already `metric`+`time`+`value`+`unit` | ❌ no bump |
| `AutoDetectActivities` preference | a `RealmSetting` row over `genericData` | ❌ no bump |
| **FA-4 only:** `steps` field on `MovementTrace` | positional reshape | ✅ shipped in place at v1 pre-reset (see header); post-reset a nested reshape is blocked |

⚠ `deleteWorkout` is a new *op*, not a new type — no format movement, but it is permanent wire
surface and should be reviewed as such.

### 8.4 The picture

```
┌─ TIMELINE app process ────────────────────── only if installed ─┐
│                                                                  │
│   location FGS ─┬─► AndroidLocationCaptureSource                 │
│   pedometer ────┘         │                                      │   LIVE
│                           ▼                                      │   sensing
│                    MovementSegmenter ─── LegMode.classify        │
│                           │              ▲                       │
│                           ▼              └── :activity-recognition│
│                    CapturedMoveWriter        (D6: shared)         │
└───────────────────────────┼──────────────────────────────────────┘
                            │ op  recordMovementTrace
                            ▼
╔═══════════════════════════════════════════════════════════════════╗
║  CONTROL — the proxy, hosting every service                       ║
║                                                                    ║
║   timeline-service  ▸ MovementTrace   ◄── the durable fact        ║
║   health-service    ▸ Workout   ▸ HealthSample   ▸ DailyActivity  ║
║   preference-service ▸ RealmSetting (AutoDetectActivities)        ║
╚═══════════════════════════════════════════════════════════════════╝
        ▲    ▲                                     │
        │    │ op  deleteWorkout  ⚠ NEW (FA-0)     │ chain DSL — no op
        │    │                                     ▼
┌───────┼────┼─────────────────────────────── only if installed ───┐
│  FIT app process                                                  │
│       │                                                           │
│       │  WorkoutPromoter ── :activity-recognition (D6: shared)    │  RETROSPECTIVE
│       │      ├─ traces ─────────► promote  (D2: detected:<trace>) │  at open / refresh
│       │      └─ step samples ───► promote ONLY where no trace     │  — never live
│       │                                    covers the window (D5) │
│       │                                                           │
│       └── recordWorkout / recordImportedHealth (batched backfill) │
│                                                                   │
│   HealthConnectImporter / HealthKitImporter                       │
│      ├─ §1.5 stop collapsing steps ─► recordHealthSample          │
│      └─ supersede overlapping Detected rows ─► deleteWorkout      │
└───────────────────────────────────────────────────────────────────┘
```

⚠ On **iOS route-1** the two app boxes are each their own in-process proxy and the Control box
is not a separate process — but the service/op surface is identical, which is why §6.5 rejects
process-level ownership in favour of the retrospective rule.

---

## 9. D4 — Battery: two tiers, because Google's efficiency has three sources

### 9.1 What Google actually does

| Mechanism | Google | Us |
|---|---|---|
| Detection on the **sensor hub / CHRE**, accelerometer only, sub-mW | ✅ | ⚠ partially — pedometer yes, accel classification no |
| **GNSS never runs for detection**; auto-logged sessions often have no route | ✅ | ✅ via §6.2 (was ❌ in v0.1) |
| Cost **amortized across every app** — one AR subscription serves Maps, Fit, Wellbeing | ✅ | ❌ structurally unavailable |

The third is unavailable by stance (no Play Services — `AndroidLocationCaptureSource.kt:33`, and
`timeline-ingestion.md` §4.4's reversal) and should stop being a target. **It is also not where
the power goes.** GNSS is roughly an order of magnitude more expensive than a sensor-hub
pedometer, which makes the second row the whole game — and §6.2 wins it outright.

### 9.2 Tier 1 — detection without a route

Wherever Health Connect or HealthKit is present, **this costs nothing new**: the samples are
already read (§1.5) and merely need to stop being collapsed. A window at ~90–130 spm is a walk;
~150–190 spm is a run.

Only **de-Googled Android with no Health Connect** needs real sensing. There,
`TYPE_STEP_COUNTER` is cumulative-since-boot, so a ~15-minute WorkManager job can register, take
one sample, unregister, and diff against a persisted baseline — no continuous listener, no FGS,
survives Doze. **No new permission**: `fit-android` already declares `ACTIVITY_RECOGNITION`.

⚠⚠ **Cycling is undetectable in this tier** — zero steps is equally a bike, a car and a train,
and without GNSS there is no speed to disambiguate. Google Fit has the same limitation for the
same reason. Accept it; do not guess.

⚠ `AndroidStepCounter` is **not reusable** for the polling path: its contract returns `null` on
the first reading after `start()` (no baseline) and re-baselines on a backwards counter (reboot).
A polling consumer must persist its baseline across job invocations — a different lifetime, so
this is a new component beside it, not a reuse. (§7's warning against extracting sensor seams.)

⚠⚠ **Permission timing.** `fit-app.md` §9.11 requests the `Motion` bundle just-in-time *at
Start*. A user who has never recorded a workout has never granted `ACTIVITY_RECOGNITION` — so
the detector meant to catch their first walk cannot run. The D1 prompt is the natural place to
request it, coupling §3's UX moment to this tier's permission acquisition (§10 Q4).

⚠ Writing needs the Control connection, which a WorkManager job may not have.
`RecordedWorkoutOutbox` already exists for this shape (durable queue, drains when the proxy is
reachable) — reuse it rather than requiring liveness.

### 9.3 Tier 2 — detection with a route

Timeline's existing capture, promoted retrospectively. Adds **nothing** to the power budget: the
FGS, the pace policy and the classifier all run already.

This is a better product split than Google's: *"log my walks"* costs a pedometer, and *"draw my
route"* is a separate, visible opt-in with its own notification.

### 9.4 What not to build

**Do not build the deferred accelerometer-window classifier**
(`timeline-movement-capture.md` §17.4 — 2–4 s windows, mean/variance/energy/FFT-peak, small
decision tree). It would close the Tier-1 cycling gap, but it needs continuous sampling at
milliamps and there is no portable way to push it onto the sensor hub without GMS. That spends
Google's power budget without Google's placement. `LegMode.kt:9` makes the same argument against
the gyroscope.

### 9.5 An honest caveat on Tier 2's numbers

`CapturePace.kt:42`: *"⚠⚠ **Every interval here is reasoned, not fitted, and awaits an on-device
wakeup measurement.**"* And `CapturePace.kt:56`: a 10 s interval on a device that cannot batch is
**strictly worse** than the old 60 s. Nothing here changes those intervals, and nothing should
until someone measures real wakeup cadence on real hardware.

---

## 10. Open questions

- **Q1 — The duration / confidence floor.** A 12-minute walk to the station *is* a detected
  walk. Google logs those; users find them noisy but tolerable. `modeConfidence` (0–100) and
  `distanceM` are both available to gate on. Wants a real corpus, not a guess — the S-track's
  `RawFixJournal` may already hold enough.
- **Q2 — Energy estimation.** `activeEnergyKj` defaults to `0`. A MET-table estimate from mode +
  duration + the FP-track's profile weight is cheap and standard; `0` is arguably more honest.
- **Q3 — Dismissal semantics** for the one-time prompt (§3).
- **Q4 — Where `ACTIVITY_RECOGNITION` is requested**, given §9.2's timing wrinkle.
- **Q5 — RESOLVED (2026-08-09): `deleteWorkout` takes one `workoutId`.** Per-row, no list
  variant. The `TimelineProjectionRepository.kt:960-966` precedent holds even though it was a
  read: a batch parameter is permanent wire surface bought against a cost nobody has measured,
  and supersession is a background pass with no user waiting on it. ⚠ If a long backfill ever
  makes the round-trips hurt, the fix is a batch op *then*, on evidence — not a list parameter
  now, on speculation.
- **Q6 — Does Timeline's own step data want to land in `HealthSample` too?** §1.5 routes Fit's
  importers there. Timeline's live pedometer (`AndroidStepCounter`) currently persists cadence
  only to the debug `RawFixJournal` (`timeline-movement-capture.md` §19.4). Unifying is
  tempting and out of scope here.

---

## 11. Phasing

| Phase | Scope | Gates |
|---|---|---|
| **FA-0** ✅ | **`deleteWorkout(workoutId)` op + a delete affordance in Fit** | ⚠⚠ blocks D1 and D3 (§5.2); independently overdue |
| **FA-1** ✅ | Bridge Fit → `timeline-service` (deps + `FitClientModule` registration + a `traces` read) — as built §14.2 | — |
| **FA-2** ✅ | Deterministic promotion (D2) + `source = Detected` + the one-time prompt (D1) + the `RealmSetting` | FA-0, FA-1 |
| **FA-3** ✅ | Supersession pass in the HC / HealthKit importers (D3) — as built §14.4 | FA-0, FA-2 |
| **FA-4** ✅ | Persist **steps** (`MovementTrace.steps`, `-1` = not counted) ⇒ real `steps` on promoted workouts | Shipped in place at timeline-service v1 before the production reset — no bump (the 2→3 note was pre-reset numbering) |
| **FA-5** ✅ | Tier 1 (§9.2): step-bout detector over the importers' own reads; ⚠ no `HealthSample` persistence needed — detection happens in the import pass | Q4 |
| **FA-6** ✅ | Cross-kind `MomentReconciler` fold (§5.3) — Timeline-side rendering dedup, **derived pairs only** | independent; the fuzzy case stays I6 |
| **D6** | Extract the classifier + promotion policy into `:activity-recognition` (§7) | mechanical; fold into FA-2 or take separately |

FA-0 + FA-1 + FA-2 + FA-3 is the shippable unit. FA-5 is what makes the feature work for a user
who never installs Timeline — and, per §1.5, it is far cheaper than v0.1 assumed.

---

## 12. Threat model

**No amendment anticipated for FA-0..FA-4.** No new sensor, no new permission, no new relay
interaction; the realm already holds `Workout` and `MovementTrace` rows, and promotion is a
local derivation between two types that already sync.

**FA-5 needs a check, not obviously an amendment:** it reads a pedometer Fit already holds the
grant for, holds no location, and adds no egress. ⚠ Verify before landing — a background
detector that writes health rows with no explicit user action per row is a posture change even
where it is not a data-flow change, and A35's reasoning about capture indicators may extend
from location capture to health capture.

⚠ §1.5 stores **intraday** step samples where only daily totals were stored before. That is a
genuine granularity increase in the realm (it reveals *when* you walked, not just how much), and
it should be stated in the amendment check even though both figures are already E2E and
device-local.

---

## 13. Decisions

| | Decision |
|---|---|
| **D1** | **Ask once, then never again** — one prompt at the first would-be detection, backed by a `RealmSetting`. Per-detection cards are rejected: auto-logging is additive and reversible, so RC-3's *"suggestion, never automatic"* does not transfer, and a recurring prompt is *more* friction than Google. ⚠⚠ Depends on FA-0: without a delete, "reversible" is false and this decision is unjustified. |
| **D2** | **Promotion is a deterministic, content-addressed function of the trace** (`workoutId = "detected:${traceId}"`), so multi-device promotion converges by construction instead of re-creating the derived-verdict broadcast loop. No device-local input, ever. The route is *not* copied — `traceId` is recoverable from `workoutId` (§4.1). |
| **D3** | **Supersession, not pre-checking** — a `Detected` workout is removed by an overlapping import, unless the import has no route and the detected one does. Order-independent; the only shape that survives a watch syncing hours late. Requires FA-0. |
| **D4** | **Two tiers.** Tier 1 = pedometer-only, no GNSS, no Timeline dependency — Google's actual mechanism, and per §1.5 nearly free because the samples are already read. Tier 2 = route, riding Timeline's capture at zero marginal cost. The accelerometer classifier and any attempt to match Google's cross-app amortization are explicitly not built. |
| **D5** | **One sensor: Timeline senses live, Fit detects retrospectively.** Making Fit's detector backwards-looking removes the race, so a read-check ("does a trace already cover this window?") replaces process-level coordination — and it works identically on Android and iOS, which Control-owned capture would not (§6.5). Timeline does **not** promote when Fit is absent: the trace is the durable fact, and a later Fit install backfills the whole history. |
| **D6** | **Extract the classifier and the promotion policy, not the capture engine.** `LegMode` + thresholds + promotion are shared (`:activity-recognition`, the `:basemap-core` precedent); the segmenter, capture engine, pace policy, dwell detector and sensor seams stay with Timeline — extracting those would invite the second live detector D5 exists to prevent. |

---

## 14. Implementation notes for Claude Code

The canonical handoff section (the `sync-merge-strategies.md` §5b convention). §§1–13 are the
design; this is the file-level plan. Every path below was verified against the tree on
2026-08-09.

### 14.0 The op-registration checklist — read before FA-0

Adding one operation touches five places, and **a missed registration fails at runtime as an
opaque `error/internal`, not at compile time**. `RoutineServiceClientModule.kt:19-25` states it
outright: *"An operation whose Ro is registered only in the common module dies as an opaque
`error/internal` at every call site."*

| # | Module | What | Skippable? |
|---|---|---|---|
| 1 | `:health-service` | new op file: client extension fun + `interface : Operation` + `const val ID` + `operation(id, receiver = HealthService.Schema.toType(), params, traits)` + `object Params` | no |
| 2 | `:health-service-common` | **nothing** for a plain op (only proxy/on-demand-resolved ops register there — `GetWorkoutStreams` is the sole entry, `HealthServiceCommonModule.kt:107`) | yes |
| 3 | `:health-service-client` | new `impl/XRo.kt` **and** `operation(interfaceClass, protocolSchema, roFactory)` in `HealthServiceClientModule.kt` | ⚠⚠ **never** |
| 4 | `:health-service-proxy` | new `executor/XImplementation.kt` **and** the `X.ID -> XImplementation()` branch in `HealthServiceProxyImplementationFactory.kt` | no |
| 5 | `:health-service-test-utils` | nothing — the harness is registration-generic | yes |

⚠ `receiver = HealthService.Schema.toType()` is load-bearing: `ModuleConfigurationImpl.kt:297-302`
only slots the factory into `operations[receiverTypeId][opId]` when a receiver is declared.
Without it, `findOperation(ID)` never resolves.

⚠ The DSL form in `docs/how-to/add-composites-and-operations.md:235` (`operation(Login.Schema) { … }`)
is **stale**. Follow `HealthServiceClientModule.kt:141-145`.

Plus two out-of-module registrations that are easy to miss:

- ⚠⚠ **`BundledAppManifestSource.kt:127-148`** — Fit's `AppPermission` list is keyed by
  `operationId`, and only `recordWorkout` (:136) and `recordImportedHealth` (:143) are listed.
  **A new op Fit dispatches needs an entry here or Fit cannot call it.** Pinned by
  `BundledAppTablesTest.kt:153-154`.
- ⚠ **`system-test-utils/src/test/resources/schema-shape-baseline.txt`** — a new op's parameter
  object is a new composite ⇒ `Violation.Unpinned` ⇒ `SchemaShapeBaselineEnforcementTest` fails.
  Regenerate with
  `./gradlew :system-test-utils:test --tests '*SchemaShapeBaselineEnforcementTest*' -Dtenshu.schemaBaseline.regenerate=true`
  (it deliberately fails once; re-run without the flag). Adding an op is `Unpinned` — *recorded*,
  not a version bump. Only **reshaping** an existing op is `ReshapedWithoutBump`.

### 14.1 FA-0 — `deleteWorkout(workoutId)`

⚠⚠ **Do not copy `DeleteRoutine` as it stood here** (fixed 2026-11-04, `RoutineDeleteSyncSystemTest`).
`DeleteRoutineImplementation.kt` was the smallest structural template but it was
**sync-incorrect**: it emitted no tombstone, so the delete is device-local and
the next pull resurrects the row. `SyncObserver.kt:750-754` makes this explicit — *"Tombstones
are emitted explicitly by operation implementations via `emit` — not on every database delete."*
`DeleteEventImplementation.kt` had the same gap (fixed: Calendar deletes now emit tombstones).

**Follow `DeleteFileImplementation.kt`** — the one sync-correct per-row delete in the tree.

| File | Change |
|---|---|
| `health-service/.../health/DeleteWorkout.kt` | **new** — mirrors `RecordHealthSample.kt`; `params = { string(Params.WORKOUT_ID) }`, `traits = IoOperation + TerminalOperation` |
| `health-service-client/.../impl/DeleteWorkoutRo.kt` | **new** — sibling of `RecordWorkoutRo.kt` |
| `health-service-client/.../HealthServiceClientModule.kt` | register the op (the :141-145 block shape) |
| `health-service-proxy/.../executor/DeleteWorkoutImplementation.kt` | **new** — `executionContext.deleteData(DataId.StringDataId.create(context, type = Workout.ID, keyValue = workoutId))` then `tombstoneEmitter?.emit(targetEntityIdHash = dataId.getDataIdHash().toString(), targetEntityType = Workout.ID)` |
| `health-service-proxy/.../HealthServiceProxyImplementationFactory.kt` | ⚠⚠ **add a `tombstoneEmitter: TombstoneEmitter? = null` parameter** — it has none today — plus the `DeleteWorkout.ID ->` branch |
| 5 factory call sites | `control-shared-proxy` `ProxyModule.kt` + `PlatformProxyModule` ×3 (android/desktop/ios), `SystemTestPlatformModule.kt:476`, `HealthTestProxy.kt:14` |
| `control-shared-logic/.../BundledAppManifestSource.kt` | Fit `AppPermission` entry for `deleteWorkout` |
| `schema-shape-baseline.txt` | regenerate |

Client + UI:

| File | Change |
|---|---|
| `fit-shared-logic/.../data/FitRepository.kt` | `suspend fun deleteWorkout(id: String) = Unit` — the defaulted-no-op convention (:221-234) so no implementer breaks |
| `fit-shared-data/.../FitProjectionRepository.kt` | real impl; mirror the `workout(id)` read's `foldTracked` / `reach.markUnreachable()` discipline (:314-338), **not** `addWorkout` (which routes through `HealthImportWriter` + the outbox — a delete has neither) |
| `fit-shared-logic/.../data/MockFitRepository.kt` | `workouts.value = workouts.value.filterNot { it.workoutId == id }` beside `addWorkout` (:101) |
| `fit-shared-logic/.../workout/WorkoutDetailViewModel.kt` | first mutation on a read-only VM (its whole surface is one `StateFlow` today) |
| `fit-shared-ui/.../workout/WorkoutDetailScreen.kt` | ⭐ `DetailTopBar` (:111-139) already renders `FitIcons.More` as a **decorative `Icon` with no `onClick`** — make it an `IconButton` + `DropdownMenu`. `showOverflow: Boolean = true` already exists and `WorkoutRouteMapScreen` already passes `false`. |
| confirm dialog | `ui-core`'s `AppDialog` (`chrome/AppDialog.kt:48`) is the right primitive; ⚠ Fit uses raw `AlertDialog` everywhere and may not depend on `:ui-core` — check `fit-shared-ui/build.gradle.kts` first |
| `App.kt:235-243` | thread the existing `pop` into the screen for post-delete navigation |

⚠ `NotConnectedFitRepository.kt:43` (iOS) inherits the defaulted no-op — **gate the menu item on
something real**, or it is an affordance that appears to work and silently does nothing.

✅ Head start: `WorkoutActivityRollupMaintainer.onDataDeleted` (`HealthProxyInitialization.kt:316-325`)
already handles workout deletion correctly, so the rollups need no change. ⚠ This is *not* true of
`LatestSampleMaintainer.onDataDeleted` (:132-134), a deliberate no-op justified by *"samples have
no delete surface"* — relevant only if a `deleteHealthSample` ever follows.

**Tests:** `DeleteWorkoutUnitTest` in `:health-service-proxy/commonTest` mirroring
`RecordHealthSampleUnitTest.kt` (⚠ it builds the wire chain by hand via `idToUuid` — copy that, it
is the only thing covering the wire-registration gap); `MockFitRepositoryTest`; a
`WorkoutDetailScreenTest` for overflow → confirm → delete.

### 14.2 FA-1 — bridge Fit → `timeline-service` ✅ SHIPPED

**As built (2026-08-09).** The wiring was the five lines §14.2 predicted; the read needed three
things the plan did not say:

- ⚠ `getFieldValue<T>(ref)` is a **member of `Entity`**, not an extension — no import, and the
  `e.get(...) as TString` shape guessed at first is wrong. `TimelineProjectionRepository.traceInput`
  is the accessor precedent.
- ⚠ `TInteger.get()` returns **`Long`**; `MovementTrace.create` likewise takes `Long` for every
  numeric. `DetectedMovement` narrows to `Int` at the boundary, deliberately — a UI-facing
  candidate has no use for 64-bit step counts.
- ⚠ `FitProjectionRepository`'s companion had to become `internal` (was `private`) so the bridge
  test can assert `POLYLINE` stays out of `TRACE_FIELDS`. That guard needs to read the list,
  because omission is the *only* mechanism keeping the blob off a per-candidate read.

New surface: `DetectedMovement` (fit model) + `FitRepository.detectedMovements(): Flow<List<…>>`,
defaulted to empty — the honest answer when Timeline is absent, when there is no proxy, or when
nothing has been captured. `MockFitRepository` inherits the default rather than inventing
fixtures: a mock that manufactured detections would make FA-2's promotion look like it worked.

⚠ **Test-harness note.** `FitTimelineBridgeTest` stands both services up in one `TestProxy` and is
**mutation-checked**: dropping `timelineServiceClientModule` from the registry fails all three
tests, which is the point — a missing client-module registration is a runtime `error/internal`,
never a compile failure. Do **not** call `proxy.coroutineScope.cancel()` when the proxy was built
with the `TestScope`; the pipeline test cancels a *separate* app scope, and cancelling the
`TestScope` fails the test after its assertions have already passed.

#### Original plan

| File | Change |
|---|---|
| `fit-shared-data/build.gradle.kts` | `implementation(project(":timeline-service"))` + `":timeline-service-client"` (mirror `timeline-shared-data/build.gradle.kts:49-50`) |
| `fit-shared-ui/build.gradle.kts` | same, for the DI module |
| `fit-shared-ui/.../di/FitClientModule.kt:49` | add `timelineServiceCommonModule, timelineServiceClientModule` to `coreSchemaConfigs` |
| `fit-shared-data/.../FitProjectionRepository.kt` | a `traces` read — ⚠ project fields explicitly and **omit `POLYLINE`**; `TimelineProjectionRepository.kt:1211-1216` is the precedent and says why |

No proxy change, no new op, no permission change.

### 14.3 FA-2 — promotion, the prompt, the toggle

⚠⚠ **Fit has no Boolean toggle anywhere** — no `Switch`, no `Checkbox`, no `BooleanAdapter`
preference. `FitPreferences` declares exactly two, both `IntAdapter`. This phase establishes the
pattern rather than following one. `DrivePreferences.kt:73,117,142,155` has four real Boolean
examples.

⚠⚠ **And Fit has no persisted "already asked" flag.** `onboarded()` is `flowOf(true)` on the real
repo (`FitProjectionRepository.kt:305`) and an in-memory `MutableStateFlow(false)` on the mock —
it resets every launch. D1 needs real persistence. The *shape* to copy is
`onboarded()` → `AppViewModel` → the `when` gate in `App.kt:70-73`; the persistence is new.

| File | Change |
|---|---|
| `fit-shared-logic/.../data/FitPreferences.kt` | `AutoDetectActivities` (REALM, `BooleanAdapter`) + `AutoDetectPromptAnswered` — ⚠ the second is arguably **DEVICE**-scoped ("have we asked *here*"), a real decision (§10 Q3) |
| `fit-shared-data/.../TenshuFitAutoDetectPreferences.kt` | **new**, on the `TenshuFitGoalPreferences.kt:16-34` template; ⚠ `read()` emits nothing on a realm that never held the row — `.onStart { emit(default) }` or a fresh install renders nothing |
| `fit-shared-logic/.../model/FitModel.kt:31` | add `Detected` to `Source` |
| `FitIcons.source(...)` / `sourceLabel(...)` | new branches — `SourceBadge` (`components/SourceBadge.kt:27`) then propagates to `WorkoutRow.kt:58` and `WorkoutDetailScreen.kt:199` for free |
| ⚠ `ProfileViewModel.kt:104-112` | add `Detected` to the exclusion filter beside `Recorded`/`Manual`, **or it renders as a phantom "connected data source"** on Profile |
| `fit-shared-ui/.../profile/ProfileScreen.kt` | a `ProfileToggleRow` sibling of `ProfileRow` (:369-394) with a trailing `Switch` instead of the chevron |
| promoter | in `:activity-recognition` (D6) or `fit-shared-data`; writes via existing `recordWorkout` / `recordImportedHealth` |

⚠ Profile **is** Fit's settings screen — there is no separate Settings destination.

### 14.4 FA-3 — supersession in the importers ✅ SHIPPED

**As built (2026-08-10).** Two deviations from the plan, both load-bearing:

- ⭐ **The M-track's "unless" clause dissolves at this layer.** The rule says an import supersedes
  a capture *"unless the workout has no GPS and the capture has a route"* — a real caveat for
  Moments, where dropping the capture drops the only geometry. It does not apply to `Workout`
  rows, because a detected workout **stores no route**: the geometry lives on the `MovementTrace`,
  which supersession never touches (§4.1). Removing a detected workout is lossless, so the branch
  was never written.
- ⚠ **The pass runs AFTER the write, not before.** §14.4 said before; that would delete detected
  workouts on an import that then fails, losing them for nothing. After a successful write, a
  failure leaves a duplicate the next import retries away.

⚠ Also: no activity-type match. The two sides speak different vocabularies (Health Connect's
`Hiking` lands as `Other` where ours says `Walking`), so a match test would mostly fail and leave
the duplicate it exists to remove. Overlap is half-open — back-to-back sessions touch at an
instant and must not swallow each other.

⚠ `supersededIds` takes an explicit `TimeZone` rather than defaulting to the system one: a
`Workout` carries `LocalDateTime` while an import carries epoch millis, and a mismatch shifts the
comparison by hours near midnight.

#### Original plan

🎯 **Hook: `FitProjectionRepository.runImport`, between lines 664 and 668** — after the outcome is
unwrapped to `ImportedHealth`, before `HealthImportWriter.write`. You have the full incoming set,
the `client`, and you are already inside `importLock`.

Rejected seams: inside `HealthImportWriter.write` (also catches `addWorkout` and the outbox
drain — broader than intended) and `addWorkout` itself (only if a *first-party recording* should
supersede a detection, which is a separate question).

⚠ **Respect the `autoRefreshReady()` gate** (`HealthImportBootstrap.kt:47-50`). It exists so a
*fixture* importer never writes fabricated rows into the durable synced realm — a detection or
supersession pass must not route around it.

### 14.5 FA-5 — Tier 1

The importer change is two expressions (`HealthConnectImporter.kt:357-358`,
`HealthKitImporter.kt:418-420`): stop collapsing, emit `HealthSample` rows with
`metric = Steps`. Then a retrospective detector over those samples, suppressed where a
`MovementTrace` covers the window (D5).

⚠ `AndroidStepCounter` is **not** reusable for the no-HC polling fallback (§9.2) — different
baseline lifetime. New component.

### 14.6 D6 — the extraction

Mechanical, on the `:basemap-core` precedent. Move `LegMode` + thresholds (already pure
`commonMain`, no Compose, no platform dependency) into a new Compose-free `:activity-recognition`
and put the promotion policy beside it. ⚠ §7's table is the cut line — do not bring the sensor
seams.

### 14.7 Gates

| Phase | Gate |
|---|---|
| FA-0 | `./gradlew desktopTest jvmTest test`; **regenerate `schema-shape-baseline.txt`**; ⚠ `scripts/android-smoke.sh` (HR#8 — it touches `:control-shared-proxy` DI wiring) |
| FA-1 | `./gradlew :fit-shared-data:desktopTest`; ⚠ smoke gate (DI graph change) |
| FA-2 | full suite + `:fit-shared-ui:desktopTest`; smoke gate |
| FA-3, FA-5 | full suite; FA-5 also `:fit-android` smoke |
| any | ⚠⚠ CI has been dark since 2026-06-30 — verify locally, a green tick carries no information |
