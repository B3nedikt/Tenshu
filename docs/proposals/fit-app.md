# Fit — the Tenshu health & fitness app (UI-only v1)

**Status:** v0.1 — **UI-only v1 COMPLETE (Ph0–Ph8)**. All 11 screens build over
`MockFitRepository` on desktop + Android; on-device Android pass pending.
**Design input:** `docs/design/fit/` (local-only handoff, gitignored — same convention as
Timeline): `fit-brief.md`, `fit-agent.md` (build spec), runnable mockup + `fit-data.js`.
This proposal is the durable record; decisions distilled from the handoff live here.
**Precedent:** `timeline-app.md` — same UI-over-mock module recipe, same phasing.
**Service substrate:** `timeline-services.md` §4 — `:health-service` is already on master
(`Workout` schema, `RecordWorkout` op); the real store later replaces the mock behind one
Koin binding.
**Companion:** [`fit-automatic-activity-detection.md`](fit-automatic-activity-detection.md) —
every workout in this doc begins with a tap; that one designs the workouts that don't. ⚠ Its D3
adds a **`Detected`** value to `Workout.source` and a supersession pass to both importers
(§9.5 Health Connect; P-iOS-2 HealthKit, §9.12), and its D1 supersedes the reading of §13.3's *"suggestion,
never automatic"* that would extend that rule to auto-logging — idle trim is destructive,
auto-logging is additive and reversible.
⚠⚠ Its FA-0 closed a gap that was **Fit's, not that proposal's**: until it landed, **a `Workout`
could not be deleted at all.** `:health-service` was nine ops, all upserts or blob reads, and the
only deletion path was whole-type realm-wide `DeleteRealmData` — whose `createdBefore` narrowing
matches nothing for workouts (`HealthServiceCommonModule` — `Workout.Start` is a field alias
`Entity.getProperty` cannot read). A user could not remove a mis-recorded workout and no screen
offered to. Now `deleteWorkout(workoutId)` deletes the row, its streams blob and emits a
tombstone, and the detail overflow offers it. ⚠ The proxy factory gained a `tombstoneEmitter`
parameter for it — passing `null` silently makes the delete device-local, which the next pull
undoes.

---

## 1. Goal & scope

Fit is the platform's health & fitness app — the privacy-respecting replacement for
Google Fit / Apple Health. Three jobs: **import** existing health data losslessly (Health
Connect / HealthKit / FIT·TCX·GPX files), **record** workouts first-party (GPS, steps,
BLE HR — zero Google dependency), and **feed the realm** (Timeline's activity layer).

v1 here is **UI-only over a `MockFitRepository`**: all 11 screens, desktop + Android,
no live import, no real sensors. The seam is real; the data is mock.

### Hard product constraints (from the brief — load-bearing)

- **Fully de-Googled path is first-class.** No Play Services required; Health Connect
  import *offered when present*, never required. Nothing blocks on a Google surface.
- **No Heart Points.** Headline metric = **Active Minutes** (WHO moderate+vigorous,
  computed on-device) with a daily goal ring; steps / distance / energy beneath.
- **No background location.** Location only during an explicitly started workout.
- **No map tiles in v1.** Routes render as an abstract polyline on a neutral surface.
- **Medical records are import-only + read-only** (FHIR via Health Connect / HealthKit).
- **Provenance always shown** — every workout/sample/record displays its `source`.

## 2. The 11 screens

1. **Onboarding — welcome + source choice** (runtime-detected: Health Connect if present /
   file import / start fresh; Apple Health on iOS later).
2. **Onboarding — permissions + what to import** (per-category consent w/ estimated counts).
3. **Onboarding — first import progress** (overall ring + per-category live counts).
4. **Home** — Active Minutes goal ring (locked: single ring, three-ring variant dropped),
   steps/distance/energy strip, 7-day trend bars + week delta, latest workout card, trust cue.
5. **History** — week summary card + workout feed with per-day headers, source badges. ⚠ The
   Compose feed is **paged** since 2026-08-12 (`fit-history-paging-handover.md` §8), so it reaches
   past the repository's list window and there is no "Earlier weeks" link any more; the day header
   is a per-row comparison against the previous row, because a day straddles page boundaries.
   SwiftUI History still renders the day-grouped list read (§8.2 — Drive-iOS's paging has a live
   append defect).
6. **Workout detail** — activity-tinted hero band, 6-cell stat grid, abstract route +
   ascent chip, HR curve with avg line, per-km laps table (best highlighted), provenance footer.
7. **Record — pre-start** — activity picker (segmented), sensor status rows, offline cue, big Start.
8. **Record — live** — **always dark** regardless of app theme; hero + 2×2 grid numeral
   variants; Pause/Finish round buttons.
9. **Browse** — 7 category cards (Activity · Body · Vitals · Sleep · Nutrition · Cycle ·
   Medical records), latest-value teaser + last-updated; empty dims to "Not set up".
10. **Category detail** — generic screen, Steps as the worked exemplar: D/W/M/Y segmented
    switcher, bar graph + goal line, entries list w/ per-entry source, data-sources row.
11. **Medical records** — read-only banner, sectioned Medications / Immunizations /
    Allergies / Conditions with issuer + effective date. **Profile** — goals, personal
    data, units, data sources.

(Profile is the 11th top-level surface; the three onboarding steps count as one flow.)

## 3. Decisions

- **D1 — Standalone app modules mirroring Timeline/Drive:** `:fit-shared-logic`
  (Compose-free, iOS-ready), `:fit-shared-ui`, `:fit-desktop`, `:fit-android`.
  Package root `app.tenshu.fit`.
- **D2 — Bottom-nav app via `:ui-core` `AdaptiveNavScaffold`** (Drive's pattern, NOT
  Timeline's in-screen TabRow): five destinations Home · History · **Record** · Browse ·
  Profile. Record is the emphasized center item **inline in the NavigationBar** (M3: a
  bar takes a destination; the docked FAB belongs to a Bottom App Bar). Implemented as a
  strictly additive, default-off `emphasized` flag on `AdaptiveNavItem`.
- **D3 — Sans-serif only.** The editorial italic serif is Timeline's exclusive voice
  (ADR 0021). Fit's one type addition is **tabular numerals** for metrics
  (`fontFeatureSettings = "tnum"`).
- **D4 — Model aligns to `:health-service` names** (`workoutId`-keyed, `activityType` /
  `source` strings, `durationSec` / `distanceM` / `activeEnergyKj` / `avgHr`). The mock
  additionally carries fields the service defers: `maxHr`, `ascentM`, `laps[]`,
  `hasRoute`, and **inline** `route` / `hrSeries`. In the real store those dense streams
  are a stored `Workout.Streams` blob (§9.7 — the `File.Thumbnail` pattern, **not** the
  content plane; the earlier ADR-0017 framing is retracted there) decoded on a detail read;
  the mock inlines them for convenience only; the projector hides the difference behind
  `FitRepository`.
- **D5 — Onboarding is a nav gate**, not a platform splash: the back stack starts at
  `Destination.Onboarding` while `FitRepository.onboarded()` is false. Source detection
  is **data** (`availableSources()`), not expect/actual — the mock returns a fixed list;
  the real impl consults the platform.
- **D6 — Recording is mock-live**: a `RecordingEngine` in shared-logic ticks
  elapsed/distance/HR deterministically; Finish appends a `Recorded`-source `Workout` to
  the repository, so a recorded session immediately appears in Home/History. The engine
  interface is the seam the real sensor pipeline later implements.
- **D7 — Always-dark live recording**: `RecordLiveScreen` renders inside a nested
  `TenshuTheme(useDarkTheme = true)` with nav chrome hidden (`showNav = false`).
- **D8 — Charts are Fit-local** (`ui-core` two-consumer rule): `Ring`, `TrendBars`,
  `HrChart`, `RoutePath`, `SourceBadge`, `StatCell` live in `:fit-shared-ui`; the chart
  *math* is pure functions in `:fit-shared-logic` with unit tests.
  - **D8a — Bar charts scrub** (2026-11-01, Google Fit / Apple Health pattern): while a finger is
    on `TrendBars` a pill above the plot shows that bar's value and date, a sideways slide moves
    it, and lifting hides it — nothing stays selected. Compose shows it on touch-down and gives a
    mostly-vertical drag back to the page scroll; iOS needs a 0.15 s hold first, like Stocks. Both
    hit-test through `ChartMath.barIndexAt`. ⚠ On iOS the hold is a `UILongPressGestureRecognizer`
    (`ScrubRecognizer`), not a SwiftUI gesture: any SwiftUI drag gesture on the chart, exclusive
    or simultaneous, swallows a vertical swipe that starts on it, so the page stops scrolling.
    ⚠ Right-to-left: Compose mirrors the bar `Row`, so the hit-test, guide line and pill take
    `rtl` (`ChartMath.barIndexAt`/`barCenterX`); iOS pins the chart LTR instead (en/de only).
- **D9 — By-activity accent tint (handoff §3) deferred.** v1 ships pure Amethyst — the
  handoff requires the default to be a clean no-op anyway. The `ActivityAccents` seam is
  catalogued in §8; implementing it must not introduce hex literals outside `:ui-core`.
- **D10 — Icons**: material-icons-extended stand-ins (`FitIcons.kt`), mapped 1:1 to the
  Lucide set named in the handoff; the Lucide swap is deferred (same as Timeline).

## 4. Module wiring

Copied by analogy from the Timeline modules (which copied Drive): same plugins, targets,
Compose `resolutionStrategy` force/exclude blocks, `fixComposeResourcesForAndroid`
workaround, desktop VM/SavedState owner shims, bare `ComponentActivity` shell,
`android:Theme.Material.NoActionBar` launch theme (not `.Light` — the dark-mode fix).
`compose.resources` package: `app.tenshu.fit.shared.resources`.

VMs live in `:fit-shared-logic` under `ui/screens/*` so a screen and its VM share a leaf
package across the module boundary (Timeline convention).

## 5. Domain model (`:fit-shared-logic` `model/`)

```
ActivityType   Running | Walking | Cycling | Strength | Other
Source         Recorded | HealthConnect | AppleHealth | File | Manual
Workout        workoutId, activityType, source, title, device, start, end, durationSec,
               distanceM?, activeEnergyKj?, avgHr?, maxHr?, ascentM?,
               avgPaceSecPerKm?/avgSpeedKmh?, laps[WorkoutLap], hasRoute,
               route[RoutePoint 0..1 normalized], hrSeries[HrPoint]
WorkoutLap     index, distanceM, durationSec, avgHr, paceSecPerKm, partial
DailyAggregate date, steps, distanceM, activeEnergyKj, activeMinutes
SampleLatest   metric, unit, time, value, source          (Browse teasers)
SleepSession   date, start, end, durationSec, stages[{stage, min}]
MedicalRecord  kind (Medication|Immunization|Allergy|Condition), title, detail,
               effectiveDate, issuer, source
Goals          activeMinutes, steps
FitProfile     name, heightCm, weightKg, birthDate, sex, units
```

Logic emits **data, not display strings** (Timeline discipline): `CategoryTeaser` is a
sealed type (ActiveMinutesToday / LatestValue / SleepDuration / RecordCount / NotSetUp /
NoEntries) the UI localises; `FitFormat` returns decision parts (duration form, pace
parts, m-vs-km, kJ→kcal) that pick `strings.xml` templates. Units are presentational —
storage is SI.

## 6. Phases

- **Ph0 — Scaffolding + shells**: four modules, settings includes, `.gitignore` handoff
  entry, this proposal, shells boot to a placeholder.
- **Ph1 — Domain model**: `FitModel` / `FitFormat` / `ChartMath` (ring sweep, bar
  normalisation, HR polyline mapping, route scaling, D/W/M/Y bucketing) + tests.
- **Ph2 — Mock data + repository + recording engine**: `FitRepository` seam,
  `FitMockData` (port of `fit-data.js` incl. its deterministic generators),
  `MockFitRepository`, `RecordingEngine` + tests.
- **Ph3 — ViewModels**: Home, History, WorkoutDetail(id), Record (pre+live), Browse,
  CategoryDetail(categoryId), Medical, Profile, Onboarding + tests.
- **Ph4 — Shared composables** ✅ **DONE**: `Ring`, `TrendBars`, `HrChart`, `RoutePath`,
  `SourceBadge`, `StatCell`, `WorkoutRow`, `ActivityGlyph`, `CategoryCard`, `TrustCue`,
  `FitCard`/`Eyebrow`/`SectionHeader`, `FitIcons`, plus the localisation seam
  (`FitFormatting` / `FitDates` / `CategoryText` — logic-parts → `strings.xml` templates)
  and `tabular()`. Charts are thin Canvas renderers over `ChartMath` (D8).
- **Ph5+6 — Screens + navigation + DI** ✅ **DONE**: all 11 screens
  (`OnboardingScreen` welcome/permissions/importing, `HomeScreen`, `HistoryScreen`,
  `WorkoutDetailScreen`, `RecordPreScreen` + always-dark `RecordLiveScreen`,
  `BrowseScreen`, `CategoryDetailScreen`, `MedicalScreen`, `ProfileScreen`),
  `Destination` (KMP `KmpNavHost` back stack), `App()` = onboarding gate → `MainShell`
  (`AdaptiveNavScaffold` + `KmpNavHost`), `fitModules()`. The `AdaptiveNavItem.emphasized`
  flag landed in `:ui-core` (strictly additive, default-off) — Record is the emphasized
  centre bar item. One shared `RecordViewModel` (fixed koin key) spans pre-start + live;
  the live surface nests `TenshuTheme(useDarkTheme = true)` and `showNav = false` (D7).
- **Ph7 — Shells**: landed with Ph0.
- **Ph8 — Verify + close-out** ✅ **DONE**: `:fit-shared-ui` (desktop + android),
  `:fit-desktop`, `:fit-android`, `:ui-core` all compile; `:fit-shared-logic` +
  `:ui-core` desktop tests green; desktop app smoke-launched (Koin graph resolves,
  onboarding gate composes). On-device (Android) pass pending.

## 7. Localisation

ADR 0016: every user-facing string via `stringResource` from
`app.tenshu.fit.shared.resources.Res`; logic emits enums/ints, UI picks templates.
Apostrophes: bare `'`, not `\'` (CMP 1.7.3 formatter).

## 8. Deferred (affordance catalogued, not built)

- By-activity accent tint (D9) behind a single setting; Amethyst default stays a no-op.
- Lucide icon set swap (D10); source brand marks where licensing allows.
- Apple Health onboarding row (iOS shell itself is deferred, as in Timeline).
- Live import (Health Connect / HealthKit / file parsing) + the real recorder
  (GPS / BLE HR / step counter `actual`s) — the surfaces exist, the capture layer lands
  with `:health-service` P5 (`timeline-services.md` §7).
- Nutrition / Cycle category details (Browse shows "Not set up"); Sleep / Vitals / Body
  details follow the Steps exemplar when data exists.
- Blur/translucent sticky detail bars (v1 ships solid).
- Threat-model amendment: health asset class (GDPR Art. 9) lands with the first real
  `:health-service` import/record PR, not with this UI-only v1.
- Persistent onboarding state (mock resets per launch by design).

## 9. As-built — the real store (projector + in-process bridge)

**Shipped 2026-07-06.** The Timeline recipe (`timeline-services.md` §6/§7 P3b) applied
to Fit, closing the loop from UI-only-over-mock to *running over the real `:health-service`*.
New module **`:fit-shared-data`** (Compose-free, mirrors `:fit-shared-logic`'s targets), holding:

- **`FitProjection`** — the pure mapping, over a lightweight `WorkoutInput` (no Tenshu entity
  types, no client), so it unit-tests in isolation (`FitProjectionTest`). Covers what the
  scaffold store can back: `Workout` header→UI `Workout` (activity/source string⇄enum,
  `0`-sentinel→null), per-workout-day `DailyAggregate` derivation (`activeMinutes` = summed
  workout minutes; `steps` = `0`, no source yet), and the Browse `CategorySummary` list.
- **`FitProjectionRepository(client, context)`** — the real `FitRepository` over one
  `TenshuClient`: reads `client.healthService.workouts.select{…}.flow()` → `WorkoutInput` →
  `FitProjection`; `addWorkout` → `recordWorkout` (the Finish write path). **Honest thinness:**
  `samplesLatest`/`sleepSessions`/`medicalRecords` return empty (no store yet); `goals`/`profile`
  are in-session `MutableStateFlow` (a synced preference store is a later slice); onboarding +
  live import are no-ops with `onboarded()` = `true` (a real store implies setup done, so the app
  boots straight to the shell — live import is `:health-service` P5).
- **`InProcessFitProxy`** — the production in-process proxy (over the reusable `:core-common`
  `InProcessProxy`) hosting `:health-service` on an in-memory store + first-run `FitSeeder` (a few
  workouts, one dated *today* so Home's ring renders). No IPC/sync/pairing — the pragmatic first
  client graph. The Koin binding flips in `fit-shared-ui` `realDataModule()` (mock kept as
  `mockDataModule()` for a one-line revert).

Verified: `:fit-shared-data` compiles on desktop + iOS-sim + Android; `FitProjectionTest` +
`FitPipelineIntegrationTest` (seed → service → relation maintainer → client `.flow()` → projector,
in one in-process `TestProxy`) green; `:fit-desktop:run` boots clean (DI resolves, proxy
constructs, seeder runs, no crash). ⚠ Seeded screens are **thinner than the hand-built mock** (no
route/HR curve/laps, no steps/sleep/vitals) until the deferred `:health-service` entities + import
seam land — this proves the *pipe*, not pixel parity. On-device (Android/iOS) pass pending; a
synced/paired Fit is later.

### 9.1 First deferred health entity — `DailyActivity` (steps / distance / energy)

**Shipped 2026-07-06.** The biggest visible gap in §9 was `steps` = `0` on Home (a `Workout`
carries no step count). Closed by a new stored entity in `:health-service`, following the
`Workout` 5-module pattern exactly:

- **`DailyActivity`** (`:health-service`) — a per-day aggregate (`date` ISO key + `steps` /
  `distanceM` / `activeEnergyKj` / `activeMinutes`), `Syncable(Eager)`, content-addressed by
  `date` (`DailyActivityDataIdProvider`) so a re-import of a day folds to one row.
  `RecordDailyActivity` op + `dailyActivities` relation + a second relation-maintainer
  (`DailyActivityRelationMaintainer`) in `initHealthService`. Unit-tested
  (`RecordDailyActivityUnitTest`: round-trip + same-day fold).
- **Projector** — `FitProjection.dailyAggregates(stored, workouts)` makes a **stored
  `DailyActivity` win** for its day (real steps), falling back to the workout-derived roll-up
  (`workoutDerived`, steps `0`) only for workout-days with no stored aggregate.
  `FitProjectionRepository.aggregates()` `combine`s the two service reads. **Zero VM/UI change**
  — Home's step strip + trend and the Steps category detail read the same `aggregates()` flow.
- **Seeder** — a week of `DailyActivity` (ported from the mockup's `fit-data.js`, one dated
  today), so the projected Home renders real steps out of the box.

Verified: all `:health-service` + `:fit-shared-data` modules compile on desktop + iOS-sim +
Android; `RecordDailyActivityUnitTest` + the extended `FitProjectionTest` (stored-wins merge) +
`FitPipelineIntegrationTest` (asserts today's `steps` = 6 120 flows seed→projector) green;
`:fit-desktop:run` boots clean over the real store. No threat-model amendment: `DailyActivity` is
another feature-service entity riding the existing sealed sync plane (no relay data-shape / A1–A4
/ protocol change); the health-asset (GDPR Art. 9) amendment still lands with the first PR that
*captures* real health data (live import, `:health-service` P5) or syncs it to the relay. Next
deferred entities (`SleepSession`, vitals/body `Sample`, `MedicalRecord`) follow this same pattern.

### 9.2 Second deferred health entity — `SleepSession` (nightly sleep + stages)

**Shipped 2026-07-06.** The next Browse gap after steps was Sleep dimming to "No entries" (a
`Workout` / `DailyActivity` carries no sleep). Closed by a third stored entity in
`:health-service`, following the `DailyActivity` 5-module pattern exactly:

- **`SleepSession`** (`:health-service`) — one night's sleep (`date` ISO key + `start` / `end`
  timestamps + `durationSec` + `source` + the four stage minutes `awakeMin` / `remMin` /
  `lightMin` / `deepMin`), `Syncable(Eager)`, content-addressed by `date`
  (`SleepSessionDataIdProvider`) so a re-import of a night folds to one row — matching the app's
  `maxByOrNull { it.date }` "last night" read. **Stage shape decision:** because the stage set is
  a *fixed enum* (`SleepStageKind`), the four stage totals are stored as four scalar `int` fields
  rather than an embedded list-of-composite — mirroring `DailyActivity`'s all-scalar shape and
  deliberately sidestepping the laps-style "array element needs a `DataIdProvider`" decision
  `Workout` explicitly deferred. `RecordSleepSession` op + `sleepSessions` relation + a third
  `SleepSessionRelationMaintainer` in `initHealthService`. Unit-tested (`RecordSleepSessionUnitTest`:
  round-trip + same-night fold).
- **Projector** — `FitProjection.sleep(input)` reconstructs the UI `SleepSession`'s
  `List<SleepStage>` from the four scalars (dropping any zero-minute stage = unknown breakdown);
  `sleeps(inputs)` sorts newest-night-first. `FitProjection.categories(aggregates, sleep, today)`
  gained a real Sleep teaser (`SleepDuration(durationSec / 60)` + `Today`/`DaysAgo` stamp) over
  the last night. `FitProjectionRepository.sleepSessions()` now reads the store (was empty) and
  `categories()` `combine`s aggregates with sleep. **Zero VM/UI change** — the Sleep category read
  the same `sleepSessions()` flow already.
- **Seeder** — a week of `SleepSession` (last night's stages ported from the mockup, the rest
  varied around a healthy 6–8 h), so the projected Sleep category renders a real teaser out of
  the box.

Verified: all `:health-service` + `:fit-shared-data` modules compile on desktop + iOS-sim +
Android; `RecordSleepSessionUnitTest` + the extended `FitProjectionTest` (stage mapping + last-night
teaser) + `FitPipelineIntegrationTest` (a week of sleep flows seed→projector, 432 min last night)
green; `:fit-desktop:run` boots clean over the real store. No threat-model amendment: `SleepSession`
is another feature-service entity on the existing sealed sync plane (no relay data-shape / A1–A4 /
protocol change); the health-asset (GDPR Art. 9) amendment still lands with the first PR that
*captures* real health data (live import, `:health-service` P5) or syncs it to the relay. Next
deferred entities (vitals/body `Sample`, `MedicalRecord`) follow this same pattern.

### 9.3 Body / Vitals / Medical entities — `HealthSample` + `MedicalRecord`

**Shipped 2026-07-06.** The last three "No entries" Browse categories (Body / Vitals / Medical)
closed by two more stored entities, so **every category now has real backing** (bar Nutrition +
Cycle, which have no store at all). Both follow the established 5-module pattern:

- **`HealthSample`** (`:health-service`) — the latest reading of one metric (`metric` key +
  `value` + `unit` + `time` + `source` + `resting`). **Latest-per-metric by construction:**
  content-addressed by `metric` alone (`HealthSampleDataIdProvider`), so a newer reading folds
  onto the same row — the store holds exactly one entry per metric, which *is* the `SampleLatest`
  surface the Body + Vitals cards read (v1 shows the latest, not history; a time-series sample
  store keyed by `metric`-`::`-`time` is a later slice). **Value shape:** the schema DSL has no
  floating-point kind (only `int` = `Long`) and readings are fractional (weight `74.2`), so
  `value` rides as its plain decimal **string** (lossless, no scaling convention) — the projector
  `toDouble()`s it. `resting` uses the `boolean()` field kind (first health entity to). Op
  `RecordHealthSample` + `healthSamples` relation + maintainer.
- **`MedicalRecord`** (`:health-service`) — a FHIR-backed record (`recordId` key + `kind` +
  `title` + `detail` + `effectiveDate` ISO string + `issuer` + `source`), keyed like `Workout`'s
  `workoutId`. **Import-only + read-only in the Fit UI** (`fit-agent.md` §0.7) — `RecordMedicalRecord`
  is the *import* write path (the seeder stands in for the FHIR/Health-Connect import seam), never
  authored from the app. Relation + maintainer as above.
- **Projector** — `FitProjection.samples`/`sample` (unknown metric strings dropped) +
  `medicalRecords`/`medicalRecord` (newest effective-date first); `metric`/`medicalKind` enum
  mappings. `categories(aggregates, sleep, samples, medical, today)` now derives the Body (weight),
  Vitals (resting HR), and Medical (record count) teasers over the stored data — the same
  derivation the mock's `buildCategories` did. `FitProjectionRepository.samplesLatest()` /
  `medicalRecords()` read the store (were empty); `categories()` `combine`s four service reads.
  **Zero VM/UI change.**
- **Seeder** — the mockup's four samples + six medical records, so the cards render out of the box.

Verified: all `:health-service` + `:fit-shared-data` modules compile on desktop + iOS-sim +
Android; `RecordHealthSampleUnitTest` + `RecordMedicalRecordUnitTest` (round-trip + fold) + the
extended `FitProjectionTest` (metric/kind mapping + Body/Vitals/Medical teasers) +
`FitPipelineIntegrationTest` (samples + six medical records flow seed→projector) green;
`:fit-desktop:run` boots clean over the real store. No threat-model amendment: two more
feature-service entities on the existing sealed sync plane (no relay data-shape / A1–A4 / protocol
change); the health-asset (GDPR Art. 9) amendment still lands with the first PR that *captures*
real health data (live import, `:health-service` P5) or syncs it to the relay. With this slice the
**stored-entity roadmap is complete** — the remaining Fit work is live import (P5) and the
Nutrition/Cycle detail surfaces.

#### 9.3a Why no `TFloat` (yet) — the decimal-string vs. numeric-primitive call

The `HealthSample.value`-as-decimal-string choice (§9.3) periodically prompts the question *"a
fractional value stored as a string is inefficient — shouldn't we just add a `TFloat`/`TDouble`
schema primitive?"* The considered answer is **no, not on efficiency grounds** — and the reasoning
is worth pinning so it isn't re-litigated per entity:

- **Storage/wire cost is a wash at these row counts.** In `DbEntryCodec` a string field is
  `TAG_STRING` + a 4-byte length prefix + UTF-8 bytes; a hypothetical double would be `TAG_DOUBLE`
  + 8 bytes. So `"74.2"` costs 9 B ≈ the 9 B a double would (`"5"` is *cheaper* as a string,
  `"123.456"` a few bytes dearer). More to the point, the tables are tiny: `HealthSample` is
  latest-per-metric (~a dozen rows, ever), `DailyActivity`/`SleepSession` are one row per day. A few
  bytes and a `toDouble()` per row, read a handful at a time into a projector for on-screen display,
  is unmeasurable. **The high-volume case that *would* matter — dense sample/route streams — never
  becomes many primitive rows: it rides the content plane (ADR 0017) as a blob** (`Workout` route +
  `hrSeries`). So there is no schema-row path where float density scales.
- **The real payoff of a numeric primitive is capability, not size** — numeric range/compare/sort
  and arithmetic *in the schema/chain-DSL layer* (`search where value > 70`; string compares
  lexicographically, so `"9" > "70"`), plus no locale/precision ambiguity (`"74,2"`). **None of the
  current health entities need any of that** — the projector reads the latest value and renders it;
  all filtering/derivation is client-side in Kotlin over already-parsed `Double`s.
- **A `TFloat` is a genuinely cross-cutting primitive**, not a local add: a `core/number/TFloat`
  type mirroring `TInteger`, a `TFloatDto`/`TFloatRo` pair, a `float()`/`double()` DSL helper, a new
  `DbValue.DoubleValue` + wire tag in `DbEntryCodec`, factory registration in `SchemaRegistry`, and
  the IPC binary-parser registration (the wire-registration gap — miss it and proxy parse fails at
  runtime, not compile time). It should be built once, deliberately, with a serialize→parse
  round-trip test — not folded into a feature-entity PR.

**The trigger to actually build it** is therefore *capability*, not efficiency: the first time we
want to **numerically query or compute on a fractional value inside a synced query** rather than
filter client-side. Concrete candidates that would flip the decision, if/when they land as
schema-layer queries rather than projector-side Kotlin:

- **`Workout.avgPaceSecPerKm` / `avgSpeedKmh`** (present in the mock model, not yet in the landed
  schema) surfaced as *"days where avg pace < 5:00/km"* as a **synced** query — though note these
  are also cleanly scaled-integer-able (milli-km/h, integer sec/km), so they'd only force a float if
  a genuinely irreducible fractional field rides alongside.
- **`HealthSample.value` range/threshold queries** — *"all readings where weight > 70 kg"* or
  *"resting HR trend"* — pushed into the store instead of parsed-then-filtered in the projector.
- A **time-series sample store** (keyed by `metric`-`::`-`time`, the deferred history slice noted in
  §9.3) if it ever needs server-/schema-side numeric aggregation rather than blob-plane streaming.

Until one of those is real, decimal-string (irreducible fractionals) and scaled-integer (values with
a natural small unit — `distanceM`, `activeEnergyKj`, `durationSec`) fully cover the domain.

##### Evidence since (2026-07-20) — the question was re-asked, and the answer held

Timeline's movement track (M6–M9, [`timeline-movement-capture.md`](timeline-movement-capture.md))
added two more fractional-looking fields and prompted the question again. Recording them here so the
next person can see the trigger stayed un-tripped rather than re-deriving it:

- **`MovementTrace.medianSpeedMmPerS`** — millimetres/second. A natural small unit, exact, and the
  value is read for display only; a double buys nothing.
- **`MovementTrace.modeConfidence`** — 0–100 integer. Arguably *better* than a 0..1 float here: the
  bounded percent form removes the "is this 0.8 or 80?" ambiguity at every call site.

Two near-misses worth naming, because both look like float pressure and are not:

- **E7 coordinates** (`MovementTrace`'s bbox + endpoints, and the `railLinesIn` op params) are
  **not** a workaround — `Place.kt` and `WorkoutStreams.kt` already say so, and it holds doubly here:
  the values feed content-addressed rows and cross-device merges, where float representation drift
  would be a correctness hazard rather than a rounding annoyance.
- **`TimelineFormat.distanceParts`** emits whole + tenths as separate integers, which reads like a
  missing float but is a **localisation** decision — the decimal separator belongs in the string
  template so a translator can change it (`%1$d.%2$d km` → `%1$d,%2$d km`). A `TFloat` would not
  have changed that code.

**Two constraints to fold into the design when the trigger does fire**, both surfaced by that track:

- ⚠ **Ban floats as `DataId` field refs by construction.** `DataId.kt`'s `LongDataId` path shows a
  numeric key is already representable, and a float-keyed id would be a genuine cross-device
  determinism hazard (`NaN != NaN`, `-0.0` vs `0.0`, platform `toString` divergence). Nothing uses
  one today; the type system should keep it that way rather than relying on nobody trying.
- ⚠ **Mergers compare numeric fields.** `MomentMerger.intField` is exact-Long today; a
  `doubleField` needs an explicit total-order and NaN policy before any merger reaches for it.

And one point in favour, for balance: **a double is the one numeric type that crosses to JS
losslessly** — JS `number` *is* an IEEE-754 double, while `TInteger` is already documented as lossy
beyond 2^53 (`CompositeSnapshot.kt`, `docs/web/architecture.md`). The web precision debt is on the
integer side, not the float side.

### 9.4 Live import — the seam foundation (`HealthImporter`)

**Shipped 2026-07-06 (P5.1).** With every stored entity landed (§9.1–9.3), the next step is
*capture*: reading real health data from the platform. This slice lands the **portable import
seam + contract + writer + reference importer + the threat-model amendment**, all verifiable
in-process; the real platform importers are the on-device follow-ups.

- **`HealthImporter`** (`:fit-shared-data`, `commonMain`) — the acquisition contract
  (`timeline-services.md` §4.2): `availableSources(): List<ImportSource>` +
  `suspend import(source, categories): ImportedHealth`. Deliberately a **plain interface bound via
  DI**, not `expect`/`actual` — each app binds the importer its platform offers (Android → Health
  Connect, iOS → HealthKit, desktop/web → FIT/TCX/GPX file import), mirroring the Koin-seam
  convention and avoiding a premature `actual` stub in every target.
- **`ImportedHealth`** — the neutral, platform-free result (the five entity lists as plain DTOs,
  epoch-millis + ISO-date strings). Acquisition maps *source model → these DTOs* in `commonMain`;
  only the read itself is platform-specific.
- **`HealthImportWriter`** — the shared write path: maps `ImportedHealth` onto the `:health-service`
  schema and persists each entity via its record op. Idempotent (content-addressed), so re-import
  folds. Every importer (real or reference) shares it.
- **`ReferenceHealthImporter`** — a deterministic in-process importer emitting the recent-history
  fixture (the former `FitSeeder`'s data, now produced *through* the contract). **This unifies
  seeding and importing:** first-run data is `HealthImportWriter.write(ReferenceHealthImporter.import(…))`,
  the exact pipe a real import will drive. `FitSeeder` is deleted. Advertised as
  `ImportSource.HealthConnect`; the real Health Connect importer is a DI swap, no consumer change.
- **Repository wiring** — `FitProjectionRepository` takes a `HealthImporter`; `availableSources()`
  / `startImport()` / `importProgress()` are now real over the seam (were no-ops). `onboarded()`
  stays `true` (a real store boots to the shell; the import surface is reachable but not gated on).

Verified: all touched modules compile on desktop + iOS-sim + Android; `HealthImportTest`
(category-filtered acquisition + writer→projector round-trip + idempotent re-import) + the
seam-refactored `FitPipelineIntegrationTest` green; `:fit-desktop:run` boots clean over the seam.
**Threat-model amendment landed (House Rule #2): asset A34** — health & fitness data as GDPR
Article 9 special-category, its sealed-plane custody (HR#1 unchanged — no health field is
relay-visible), the acquisition-consent contract, and the HealthKit no-ads/AI platform rule.

**Next (P5.2+, on-device):** the real `HealthConnectImporter` (Android, `androidx.health.connect`
+ permission flow), `HealthKitImporter` (iOS cinterop), and desktop/web FIT/TCX/GPX file import —
each a drop-in `HealthImporter` binding — plus the recorder (GPS / BLE HR / step counter) and the
route/HR-stream content-plane wiring.

### 9.5 Android live import — the Health Connect actual (P5.2)

**Shipped 2026-07-06.** The first real capture: a `HealthImporter` over **Health Connect**
(`androidx.health.connect:connect-client` 1.1.0), dropping in behind the P5.1 seam with no
consumer change. Google Fit shuts down end-2026, so Health Connect is *the* Android source.

- **`HealthConnectImporter`** (`:fit-shared-data` `androidMain`) — reads `ExerciseSessionRecord`
  → workouts; `StepsRecord`/`DistanceRecord`/`TotalCaloriesBurnedRecord` grouped per day →
  `DailyActivity`; `SleepSessionRecord` (+ stages) → sleep; latest `WeightRecord` /
  `RestingHeartRateRecord` / `OxygenSaturationRecord` / `RespiratoryRateRecord` → the Body/Vitals
  samples. Maps into `ImportedHealth`; the shared `HealthImportWriter` persists it. Each per-type
  read is resilient (a missing permission for one type yields empty, not a dead import). Medical
  records are **not** a Health Connect data type, so they never come from here.
- **Fallback semantics (no reference/real mixing):** HC *unavailable* (no provider / <API 26) →
  **no source**, `SourceUnavailable` (⚠ it delegated to `ReferenceHealthImporter` until §9.15's
  Android follow-up — a manual import wrote that fixture into the realm); HC *available but unpermissioned* →
  **empty** (honest awaiting-consent state, no fixture pollution of a real store); HC available +
  granted → real data. `minSdk` raised 24 → 26 (HC's floor; Fit is the only consumer).
- **DI + consent:** `fit-android`'s `fitAndroidModule()` binds `HealthImporter` →
  `HealthConnectImporter`; `InProcessFitProxy` takes the importer via Koin `getOrNull()` (reference
  default). `MainActivity` requests the read `PERMISSIONS` via the platform HC permission contract
  on launch and re-imports on grant. Manifest declares the ten `android.permission.health.READ_*`
  perms + the HC package `<queries>` + the rationale intent-filters (API ≤33 + the API-34
  `VIEW_PERMISSION_USAGE` alias).

Verified: the record→DTO mapping **compiles against the real Health Connect SDK**; `fit-android`
**assembles a debug APK** (manifest merge + permission contract + DI wired); the P5.1 seam tests +
desktop/iOS-sim compiles stay green; `:fit-desktop:run` still boots over the reference importer
(desktop binds no `HealthImporter`). **On-device pending** (unavoidable — HC reads + the permission
grant + the privacy-dashboard rationale need a device with Health Connect installed); no
threat-model change beyond A34 (landed P5.1). **Next:** desktop/web FIT/TCX/GPX **file import** (a
Kotlin `desktopMain`/JS `HealthImporter` — fully verifiable without a device); **iOS HealthKit read
in native Swift** (not cinterop — our iOS is native SwiftUI over the exported `*-shared-logic` seams,
so a Swift `HealthKitImporter` feeds the shared `HealthImporter`/writer, mirroring
`HealthConnectImporter`; gated on exporting the Fit seam into the iOS XCFramework + a `fit-ios/`
shell); then the recorder and route/HR-stream content-plane wiring.

### 9.5b Import status — the app says what the import did (P5.2b)

**Shipped 2026-07-13, on-device verified (CPH2581 / Android 16).** §9.5's "honest empty" turned out
to be honest only in the code: on screen, *every* import outcome rendered as the same blank Home.
Reported as "the Google Fit import does not work" — and it was unfalsifiable from the app.

**What it actually was.** The chain works. On the maintainer's phone Health Connect is present, all
ten grants held, 315 `StepsRecord`s read, three `DailyActivity` rows written and rendered. There is
simply no Google Fit data *in* Health Connect: **Google Fit holds none of its 46 `WRITE_*` health
permissions** (all `granted=false`), so it never feeds HC — the only writer is the platform
pedometer (`writers=[android]`). Fit does not sync to Health Connect unless the user connects it.
The import was right; the app just never said any of this.

**The fix is to make the outcome speak.**

- **`ImportOutcome`** (`:fit-shared-data`) — `HealthImporter.import` returns `Success` / `Sample` /
  `PermissionsRequired` / `SourceUnavailable` instead of a bare `ImportedHealth`, which had
  collapsed four situations into one empty list. Failure is deliberately **not** a case: an importer
  must *throw*, or the seam invites the error-swallowing this whole slice exists to undo.
- **`ImportStatus`** (`:fit-shared-logic` model) + `FitRepository.importStatus()` (defaulted to
  `Idle`, so only the real projector overrides) — `Imported` / `NoData` / `PermissionsMissing` /
  `SourceUnavailable` / `Sample` / `Failed`, and `needsAttention` to decide whether to speak.
- **`ImportStatusBanner`** on Home (over the ring *and* in the empty state) + a live source row in
  Profile → Data sources. A healthy import stays silent.
- **`Sample` is the load-bearing one.** When no live source exists the app falls back to the
  reference fixture, filling the screens with convincing invented numbers — *worse* than an empty
  screen, because the user believes them. It is now always labelled.
- **`HealthPermissionRequester`** (DI seam, Android binds it via `MainActivity`'s launcher) so the
  banner's "Try again" re-prompts on `PermissionsMissing` rather than re-running an import that
  cannot succeed — a button that looks like a remedy and does nothing is worse than no button.
- Two real defects fell out: HC reads were **single-shot against a 1000-record page cap** (30 days
  of heart rate silently truncated) → `readAll` pages; and `MainActivity` **skipped the import
  entirely on a denied grant**, leaving `importStatus` at `Idle` — so a refusal produced an empty
  app and no reason. It now imports either way and lets the status report the refusal.
- Diagnostics: a `fitLog` seam tags the chain `TenshuFit` (`adb logcat -s TenshuFit`) — SDK status,
  the permission gate, per-type record counts **and their writer packages**, and each write step.
  **Count-level only, never sample values** — these rows are GDPR Art. 9 (A34).

Verified on-device end to end: declined the grant → banner explains + offers "Try again" → tapping
it re-opens the Health Connect sheet → grant → import runs → real steps render, banner gone.
`ImportStatusTest` pins each outcome→status mapping. **The remaining user-facing gap is real Google
Fit history**, which Health Connect will never hold: that wants a **Takeout file import** (§9.6's
parser exists; Android has no file-pick path yet). No threat-model change beyond A34.

### 9.6b Google Takeout import — the only route to real Fit history (P5.4)

**Built 2026-07-13; on-device pass pending a real export.** §9.5b established that Health Connect
holds *no* Google Fit data and never will — Fit owns none of Health Connect's write permissions. So
a user's years of Fit history are reachable only through a **Takeout export**, and this is that path.

- **`TakeoutParser`** (`:fit-shared-data` commonMain) — routes an archive's members **by content, not
  by path**: Takeout's folder names are localised and have been renamed across versions, so matching
  on `Fit/Activities/…` would silently import nothing the day Google renames a directory (the same
  silent-empty failure mode this whole track has been about). `.tcx`/`.gpx`/`.fit` → the existing
  [`ActivityFileParser`] (workouts, with route + HR streams); `.csv` → `DailyMetricsCsv`.
- **`DailyMetricsCsv`** — the daily-metrics CSV is the long tail (a row per day: steps, distance,
  calories, move minutes) and the part Health Connect can never give back. Read **column-name-driven,
  never positional**: Google adds/reorders these columns between exports, and a positional reader maps
  the wrong number into the wrong metric — plausible garbage rather than an error, which is the worst
  available outcome for health data. Only the *aggregate* CSV (the one with a `Date` column) is read;
  Takeout also writes per-day segment files restating the same totals, and parsing both double-counts.
  A day Google exported empty is **skipped, not written as a zero** — the phone was not carried; the
  user did not stand still.
- **`JvmArchiveReader`** (new shared `jvmMain` source set) — **streams the zip one member at a time**
  and decides each by name *before* reading its bytes. A Takeout export is whatever the user ticked
  and can be tens of GB of photos and mail; reading the archive into a list (the obvious shape) held
  ~96 MB of a real 10-year export's usable members at once — this holds one file. Per-member size +
  count ceilings guard the rest.
- **`jvmMain` source set** — `JvmXmlReader` used to be desktop-only, which is why **Android could not
  parse an activity file at all**. Android and desktop now share it (and the archive reader).
- **Streaming + progress (as-built).** The import runs the whole pipe file-by-file: `ActivityFileSource`
  yields one `ActivityFile`, `FitRepository.importFiles(source)` parses **and writes** it, then it is
  dropped before the next is read — so peak memory is one member, not the archive, and each file's
  records land as they are read. That per-file write also gives progress an honest number:
  `ImportStatus.Running(imported)` counts up ("Importing… N so far") on the Home banner (a spinner row)
  and the Profile source row — a bare "Importing…" on a ~13,500-record, minutes-long import reads as a
  hang. Count-so-far, not a percentage (the total is unknown until the archive is fully parsed).
  Cancellation mid-stream → `Idle`, not `Failed`: rows already written stay, and content-addressed ops
  fold on re-import, so a cancelled run resumes cleanly.
- **Seams:** `FitRepository.importFiles(ActivityFileSource)` (a separate entry point from `startImport`
  — the user hands us bytes rather than us polling a source — but sharing `importStatus`) +
  `HealthFilePicker` (DI-bound; Android = SAF `OpenDocument` in `MainActivity`, desktop = the File
  menu, accepting `.zip`). `FileHealthImporter` is **deleted** — it wrote through the writer without
  touching `importStatus` (the silent-write trap this track closes); both shells now go through the
  repository.
- **UI:** Profile → Data sources → **"Import from Google Takeout"**, shown only where a picker is
  bound (a row that cannot do anything is worse than no row).

Tests: `TakeoutParserTest` (column-name-not-position, quoted cells, empty-day skip, segment-file
rejection, one-bad-member-does-not-sink-the-import, date-named-segment rejection) + `JvmArchiveReaderTest`
(a real zip streamed; the photo and the JSON dump are never read; re-consumable) + `FileImportPipelineTest`
(streaming round-trip → `importStatus`). Fixtures are **synthetic** — a test is a bad place for anyone's
real health rows. **Verified against the maintainer's real 10-year export** (device-free, counts only):
8,442 files → 2,690 relevant → 10,745 workouts (Google buckets ≤42 activities per TCX file) + 2,791 days,
2016→2026, streamed with flat peak memory. That run **caught a real bug the synthetic fixtures missed**:
2,843 per-day segment CSVs plus the aggregate sorting last blew past the member cap and silently dropped
the whole daily history — now the date-named segments are rejected by name and the cap is raised.
On-device: the row + SAF picker verified; the on-device Takeout run is hand-back-to-the-user (driving a
file picker through personal files is the user's call). **Still unknown:** other users' column sets —
the parser logs the header of any CSV it cannot read, the first thing to check if an import under-delivers.

### 9.6 Desktop file import — GPX / TCX (P5.3)

**Shipped 2026-07-06.** The desktop/JVM acquisition path (`timeline-services.md` §4.2: "no live
health API on desktop → file import is the honest desktop story"), and the one importer **fully
verifiable without a device**.

- **`ActivityFileParser`** (`:fit-shared-data` `desktopMain`) — sniffs an `ActivityFile` (by
  extension, else by root element) as **GPX** or **TCX** and normalises it into the neutral
  `ImportedHealth` (one workout per track/activity). Pure over bytes via the JVM's `javax.xml`
  (**zero new dependencies**): GPX → workout with start/end/duration from the trackpoint times +
  distance from the haversine sum; TCX → workout with the lap-summed duration / distance / calories
  (kcal → kJ) / averaged HR; activity type mapped from the `<type>`/`Sport` text; `File` source.
  Malformed input yields empty, never throws. FIT (binary, Garmin SDK) + route/HR-stream extraction
  are later slices; v1 fills the `Workout` **summary** (matching the schema's summary-on-the-session
  shape).
- **`FileHealthImporter`** — parses picked files via the parser and persists through the shared
  `HealthImportWriter` (idempotent — workouts are content-addressed by a start-time id), so a
  file-imported workout reads back through the projector identically to any other import.
  **Deliberately not a `HealthImporter`:** file import is a *user-hands-us-bytes* action, not a
  pollable source, so it's a parallel path over the writer — the reference importer stays the boot
  source, so desktop still populates without a file.

Verified: `ActivityFileParserTest` (GPX + TCX field-level parsing, format sniff, garbage → empty) +
`FileImportPipelineTest` (parse GPX + TCX → writer → `FitProjectionRepository` reads two workouts)
green on desktop; no Android/iOS/common change (desktop-only source set). No threat-model change:
another acquisition path under **A34** (still GDPR Art. 9, sealed plane; a picked file is explicit
user consent). Web (JS) FIT/GPX decoders are a separate slice (the parser would move to `commonMain`
behind a KMP XML lib).

**P5.3c — binary FIT, shipped 2026-07-06.** `ActivityFileParser` gained a third format: **FIT**
(the Garmin/Wahoo/etc. binary interchange), decoded via the **Garmin FIT Java SDK** (`com.garmin:fit`,
`desktopMain` only). `FitDecoder` maps each `SessionMesg` (a FIT session = a workout) into an
`ImportedWorkout` — sport → activity type, start time, total elapsed → duration, total distance,
total calories → kJ, avg HR. Format sniff added: the `.fit` extension **or** the `.FIT` ASCII marker
at bytes 8–11. Fully verified without a fixture file: `FitActivityParseTest` **encodes** a one-session
`.fit` with the SDK's `FileEncoder` then **decodes** it back through the parser. The desktop File
picker (P5.3b) now accepts `.fit` too — no extra wiring, it already routes through the parser. No
threat-model change (A34). GPX/TCX stay dependency-free (JDK XML); only FIT pulls the SDK, and only
on desktop.

**P5.3d — TCX + FIT dense-stream extraction, shipped 2026-07-08.** With the §9.7 stream substrate in
place, the two remaining desktop importers now fill the route + HR streams (GPX already did in §9.7):
**TCX** reads each `<Trackpoint>`'s `<Position>` (lat/lng → E7) + `<AltitudeMeters>` + `<HeartRateBpm>`
(a trackpoint's only `<Value>`) → `RouteSample`/`HrSample`; **FIT** collects each per-second
`RecordMesg` (position in **semicircles** → decimal degrees via `× 180 / 2³¹`, `enhancedAltitude`/
`altitude`, `heartRate`), partitioning records into each session's `[start, start+elapsed]` window
(records precede the summary `SessionMesg` in the stream). All three formats now produce full
`ImportedHealth` streams that ride `Workout.Streams` through the shared writer unchanged. Verified by
extending the encode→decode round-trips: `ActivityFileParserTest` (TCX trackpoint route + HR) +
`FitActivityParseTest` (two `RecordMesg`s → route + HR, semicircle round-trip). Desktop-only, no
new deps, no threat-model change (A34).

**P5.3e — GPX/TCX parsing lifted to `commonMain` behind an `XmlReader` seam.** Preparation for a
future web (JS) file-import path: the two XML formats were written against `javax.xml`, a JVM API, so
they were stuck in `desktopMain`. The parse *logic* is portable; only the DOM is platform-specific.
So the DOM is now abstracted behind a tiny **`XmlReader` / `XmlNode`** interface (`commonMain`,
`app.tenshu.fit.data.xml`) and `ActivityFileParser` (GPX + TCX + sniffing) moves up to `commonMain`,
over `kotlinx-datetime` (`kotlin.time.Instant.parse`) + `kotlin.math` in place of `java.time` /
`Math`. Binary **FIT** stays JVM-only (Garmin SDK) behind an optional `FitBinaryDecoder` seam —
absent on platforms that can't decode it, so the FIT branch yields empty rather than failing. The
reader is **bound via DI, not `expect`/`actual`** — the same call the `HealthImporter` seam made
(§9.4): the JVM binds `JvmXmlReader` (javax.xml) where `FileHealthImporter` builds the parser; a web
consumer would bind a browser-`DOMParser` reader; Android/iOS (Health Connect / HealthKit) bind
none. `expect`/`actual` was rejected because `:fit-shared-data`'s `commonMain` includes iOS, so it
would force a stub iOS `actual` for a file-import path iOS doesn't use. The GPX/TCX tests stay in
`desktopTest` (they need a concrete reader; `commonTest` has no XML parser) but now exercise the
`commonMain` code through `JvmXmlReader`. **Standing up the actual `js` target is the follow-up
slice** — it also needs `:fit-shared-logic` (a `commonMain` `api` dep) JS-enabled and a web Fit
consumer to exist, so it's deliberately not built here. Green: `:fit-shared-data` desktop tests +
compiles for desktop/Android/iOS-sim; `:fit-desktop` compiles. No new deps, no threat-model change
(A34).

**P5.3b — the desktop picker UI, shipped 2026-07-06.** A native **File → "Import activity file…"**
menu (`:fit-desktop` `MenuBar`) opens an AWT `FileDialog` filtered to GPX/TCX, reads the bytes, and
runs them through `FileHealthImporter` (parser → shared `HealthImportWriter`); the reactive projector
flows re-emit as the workouts land, so no explicit refresh. Kept **entirely in `:fit-desktop`** (a
desktop-only native menu) rather than an `expect`/`actual` picker + a shared Settings row — the
shared Compose UI is untouched, and Android already has a live source (Health Connect), so it needs
no file path yet. `InProcessFitProxy` now exposes its `client` + `tContext` narrowly so the app can
build the `desktopMain`-only `FileHealthImporter`. Verified: `:fit-desktop` compiles + boots clean
with the menu wired (the native file dialog itself is manual — not unit-testable, same posture as the
Android permission grant). A shared cross-platform Settings "Import" entry can follow if Android/web
file import is ever wanted.

### 9.7 Workout route + HR streams — the stored-property substrate (recorder prerequisite)

**Shipped 2026-07-08.** The dense per-sample payload of a workout — the **GPS route** + the
**heart-rate series** — the first slice of the recorder track (GPS / BLE-HR / step counter).
It is the *storage substrate* both the first-party recorder and a high-fidelity import feed;
building it first (fully verifiable in-process) unblocks the Android recorder actual, whose
capture is unverifiable without a device.

- **Storage decision — a stored `TByteArray` property, NOT the file content plane.** The doc
  previously said route/HR "ride the content plane (ADR 0017)" by analogy to File bytes. That
  analogy was **wrong and is retracted**: the content plane as built is a *large, opaque file
  bytes reassembled into a `FileDatabase` at a path* channel (`FileBytesPropertyWriter` resolves
  a real `File.path`). A workout's streams are small (~tens of KB — a 1 Hz hour ≈ 63 KB),
  structured, and written once. So they are `Workout.Streams` — a stored value property, the
  **`File.Thumbnail` pattern**: it rides `Workout`'s eager (sealed/AEAD) entity block to every
  paired device, is excluded from the History list select (byte-less selection), and is decoded
  only on a workout-**detail** open. `null` (unset) for a summary-only workout. Minting a fake
  `/.health/<id>/streams` path just to reuse chunking would be the tail wagging the dog.
- **`WorkoutStreams` + `WorkoutStreamsCodec`** (`:health-service-common`, `commonMain`) — the
  blob shape (route `{tSec, latE7, lngE7, altCm?}` + HR `{tSec, bpm}`) and a **versioned,
  dependency-free** binary codec (fixed-width big-endian ints; `decode` is total — `null` /
  truncated / forward-version → `EMPTY`, never throws). Storage is **SI truth**: real GPS as E7
  fixed-point degrees (the `:place-service` idiom — the DSL has no float kind), altitude in cm,
  time in whole seconds from start. The abstract **0..1 polyline the UI draws** is a
  *presentation* transform the projector derives (`FitProjection.projectRoute` — cos-latitude
  equirectangular + bounding-box fit, feeding `ChartMath.fitRoute`); it is never stored, so a
  future real-map render / GPX export keeps the true coordinates.
- **Write path** — `RecordWorkout` gains an **optional** `streams` byteArray param (additive on
  the op wire); `RecordWorkoutImplementation` wraps the workout in an `Entity` and
  `set(Workout.Streams, …)` before `saveData` (mirrors `UploadFileImplementation`'s thumbnail
  set). `HealthImportWriter` encodes an import's route/HR onto it; the desktop **GPX** parser now
  fills real route points (E7 lat/lng + `<ele>` altitude) and the HR series from
  `<gpxtpx:hr>` trackpoint extensions (TCX/FIT stream extraction is a later slice). `addWorkout`
  (the mock recorder's Finish) stores its **real HR series** but not its route (the mock route is
  the abstract 0..1 form, not real GPS — the real recorder writes real fixes here).
- **Read path** — `FitProjectionRepository.workout(id)` selects the one matching entity **with**
  `relations(Workout.Streams)`, decodes the blob, and hands the `WorkoutStreams` to
  `FitProjection.workout(…)`, which projects the route + HR curve and derives `maxHr` from the
  stream. The reactive `workouts()` History list stays byte-less.

Verified: `WorkoutStreamsTest` (codec round-trip incl. no-altitude sentinel + empty/garbage →
empty; `projectRoute` unit-frame normalisation), `FitPipelineIntegrationTest`.
`workoutStreamsRoundTripThroughTheStore` (import a workout with route+HR → detail read decodes 3
route points + the HR curve + derived maxHr; the list projection stays streams-less), and the
GPX parser's route+HR assertions in `ActivityFileParserTest`. Compiles desktop + Android +
iOS-sim. **No threat-model change beyond A34** (landed P5.1): route + HR are the sensitive
payload A34 already covers — sealed plane, never a relay-visible field; a stored property is the
same sealed entity block as any other field, so House Rule #1 is untouched.

**Next (the recorder proper):** the Android `RecordingEngine` actual — while-in-use GPS
(`LocationManager`, zero Play Services), on-device step counter (`SensorManager`
`TYPE_STEP_COUNTER`), BLE HR strap (GATT Heart-Rate Service `0x180D`), a foreground service —
producing real `WorkoutStreams` into this substrate on Finish (unverifiable without a device,
like the Health Connect importer); then TCX/FIT stream extraction, and the iOS/web recorders.

### 9.8 The first-party Android recorder (GPS / BLE-HR / step counter)

**Shipped 2026-07-08.** The recorder proper — capturing a workout on-device with **zero Google
Play Services** — over the §9.7 stream substrate. Sensor/BLE/GPS I/O is Android framework so it is
**on-device pending** (like the Health Connect importer); the capture wiring compiles against the
real SDK and the APK assembles.

- **The write-path seam (commonMain, verifiable).** `FitRepository.addWorkout` gains an optional
  `track: RecordedTrack?` — a **neutral** capture model (`GpsFix{tSec, lat, lng, altM?}` real
  decimal degrees + `HrPoint`) in `:fit-shared-logic`, deliberately *not* the `:health-service`
  schema so the presentation layer stays free of the storage module. The real
  `FitProjectionRepository` maps a present track → `WorkoutStreams` (real lat/lng → E7) → the
  `Workout.Streams` blob; the mock keeps its in-memory workout. `RecordingEngineFactory` (a
  `fun interface` in `:fit-shared-logic`) is the DI seam that lets a platform swap the real engine
  in without `fitModules()` naming a platform type — the exact `getOrNull()` idiom the optional
  `HealthImporter` uses. Tested: `recordedTrackPersistsRealRoute` (record with a real GPS track →
  detail read decodes the route + HR).
- **`AndroidRecordingEngine`** (`:fit-android`) — the `RecordingEngine` actual over three
  first-party sources, each independently optional (a missing permission/sensor degrades that
  stream, never fails the recording): **GPS** via `LocationManager.GPS_PROVIDER` (not the fused
  Play-Services provider) → route + a haversine distance/pace; **heart rate** via a **BLE strap**
  (GATT Heart Rate Service `0x180D` / Measurement `0x2A37`, the standard chest-strap profile);
  **steps** via the on-device `Sensor.TYPE_STEP_COUNTER`. Location is acquired **only during an
  active workout** (the "no background location" constraint). On Finish it builds the summary
  `Workout` + a `RecordedTrack` and persists real streams via `addWorkout(workout, track)`.
- **`RecordingService`** — a foreground service (`location|connectedDevice` types) started for the
  duration of a recording so capture survives the app backgrounding; carries no capture logic, just
  the foreground promotion + the ongoing notification the OS requires.
- **Manifest + permissions:** `ACCESS_FINE_LOCATION`, `ACTIVITY_RECOGNITION`,
  `BLUETOOTH_SCAN`(`neverForLocation`)/`BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`(+`_LOCATION`/`_CONNECTED_DEVICE`); requested **just-in-time** — see the JIT
  gate below. `fitAndroidModule` binds the `RecordingEngineFactory`; desktop/iOS keep the mock
  engine.

**Just-in-time permission request (shipped 2026-07-08).** The recorder's runtime permissions are
requested **on entering the Record flow**, not at app launch — so location / BLE are asked for only
when the user actually goes to record (the launch-time request in `MainActivity` is removed). The
seam is a `RecorderPermissionGate` (`expect`/`actual` in `:fit-shared-ui`, which is android+desktop
only so no iOS actual): the Android actual wraps `rememberLauncherForActivityResult`
(`RequestMultiplePermissions`) + a `checkSelfPermission` fast path (no dialog when already held); the
desktop actual is a no-op that reports granted. `RecordPreScreen` calls `gate.ensureGranted { … }`
in a `LaunchedEffect` on entry, then `RecordViewModel.rearmSensors()` so the sensor rows flip to
Ready on the fresh grant. A denied stream simply degrades (the engine guards each source).

Verified: `:fit-android` **assembles a debug APK** (recorder + service + DI + manifest merge + the
JIT gate); the commonMain seam compiles desktop + Android + iOS-sim; `:fit-shared-ui` compiles both
targets; `recordedTrackPersistsRealRoute` + `RecordingEngineTest` (mock unaffected) green.
**On-device pending** (unavoidable — real GPS fixes, a paired BLE strap, the step sensor, the FGS +
the runtime-grant dialog need a device). **No threat-model change beyond A34** (route + HR are
exactly what A34 covers; sealed plane, no relay-visible field; GPS is foreground-only). **Next:** the
iOS recorder (`CoreLocation` + HealthKit BLE) once the `fit-ios` shell exports the seam.

### 9.9 iOS live-EPC data path — the real `FitProjectionRepository` over the channel

**Shipped 2026-07-09.** `fit-ios` shipped UI-complete over `MockFitRepository`; this slice lands the
**Kotlin assembly seam** that swaps it onto the real store — the Fit twin of Drive's `EpcDriveClient`
+ `DriveReadSurface.installRealClient` (`ios-drive-live-client-handover.md` §3c-consume). It is the
last step verifiable without a device; the live op→reply round-trip stays the on-device gate (a
Control serving EPC over a reachable relay), same posture as Drive.

- **`EpcFitClient` (`:tenshu-sdk-ios` iosMain)** — `assembleClient(channel: TaskTransport): FitClient`
  hand-wires what Android/desktop get from `:fit-shared-ui`'s Koin graph (the iOS umbrella has none):
  `coreCommonModule()` + every service `-common`/`-client` schema config **plus
  `healthServiceCommonModule` + `healthServiceClientModule`**, a `RemoteBinaryTaskExecutor` over the
  channel, a remote `TenshuEnvironment`/`TenshuClient`, and a real `FitProjectionRepository` over the
  resolved `TContext`. Mirrors `EpcDriveClient` exactly (same module recipe + `assembleConsume` twin).
- **`FitSurface.installRealClient(channel)`** — assembles via `EpcFitClient` and swaps `installed`
  onto the projector; every screen built afterwards reads the live store with **zero SwiftUI change**.
  `installMock()` restores the fallback.
- **`FitLiveClientSession`** — the channel-lifecycle owner (Fit twin of `LiveClientSession`):
  `activate(pairing)` installs the real client + retains the `EpcRendezvousChannel`; `teardown()`
  unbinds + restores the mock. Session-scoped, nothing persisted (ephemeral X25519 session keys +
  one-shot rendezvous — the same re-handshake-vs-persist decision as Drive).
- **Build wiring** — `:fit-shared-data` + `:health-service-common` + `:health-service-client` added to
  the umbrella's iosMain deps (**not** the `export {}` block — Swift drives them through `FitSurface`
  Kotlin methods, mirroring `:control-shared-proxy`). The `:health-service-proxy` transitive dep rides
  along as it does for `:control-shared-proxy` — the master key / op-impls stay off the client (HR#1);
  only the client-facing schema + repository are used.

Verified on the `iosSimulatorArm64Test` gate: `EpcFitClientTest` proves (1) the full client graph
(every schema incl. health + environment + client + projector) **assembles on iOS**, (2)
`installRealClient` swaps `FitSurface` off the mock, and (3) a real health op (`addWorkout` →
`recordWorkout`) **marshals down to `TaskTransport.call`**. The umbrella `TenshuSdk.framework`
(iosSimulatorArm64) links clean and its ObjC header exports the additive API. No SwiftUI change, so no
`xcodebuild` app run needed (this is a seam, not a UI slice). No threat-model change (A34; the client
sees only what it already sees over EPC). Also fixed a latent pre-existing `iosTest` inference break
(`SyncPreferencesTest`'s `MutableStateFlow(SyncPolicy.ONLINE_ONLY)` inferred the singleton subtype) so
the gate compiles — orthogonal, surfaced only because this slice is the first to run the gate since.
**Next (device-gated):** a `fit-ios` pairing dev-harness (mirror Drive's `EpcPairingView`) calling
`FitLiveClientSession.activate`, then the live round-trip against a Control serving EPC; the shipping
path rides a Control-provided local channel (App Group / Keychain, §11.6 shape).

### 9.10 Web (JS) file-import parser — standing up the `js` target (P5.3e follow-up)

**Shipped 2026-07-09.** ⚠ **Amended 2026-08-12 — the capability is unchanged, the module layout is
not; see §9.10a.** The bullet below about JS-enabling `:fit-shared-logic` + `:fit-shared-data`
"together" describes what shipped and why, and is kept for that reasoning — but neither module
targets JS any more.

The follow-up §9.6 P5.3e deferred: the `commonMain` GPX/TCX
`ActivityFileParser` now compiles + runs on the **web**. P5.3e did the hard part — lifting the parse
logic to `commonMain` behind the `XmlReader` seam — so this slice is purely *standing up the target
+ the browser reader binding*, no parser-logic change:

- **`js(IR) { browser() }` added to `:fit-shared-logic` + `:fit-shared-data`.** `:fit-shared-data`'s
  `commonMain` has an `api` dep on `:fit-shared-logic`, so both must be JS-enabled together. Every
  other `commonMain` dep (`core*`, `health-service*`) already had a JS target (the web SDK track);
  `:fit-shared-logic` is the repo's **first JS consumer of `androidx.lifecycle:lifecycle-viewmodel`**
  (2.10.0 publishes `js`/`wasmJs` from 2.8+). The logic layer is Compose-free and already iOS-clean
  (only `kotlinx.datetime`/`coroutines`), so it ports straight across.
- **`JsXmlReader` (`:fit-shared-data` `jsMain`)** — the browser `DOMParser` binding of the `XmlReader`
  seam, the web twin of the desktop `JvmXmlReader` (`javax.xml`). Uses `org.w3c.dom.parsing.DOMParser`
  + `SupportedType.application_xml` (kotlin-dom-api-compat, auto-provided by the JS plugin — no new
  dep), walks the DOM by *local* name off `nodeName.substringAfterLast(':')` (identical result whether
  the parse is namespace-aware or not), and returns `null` on a `<parsererror>` document (`DOMParser`
  signals malformed XML that way rather than throwing). Bound via DI by a web Fit consumer, exactly as
  the seam intends (Android/iOS bind none). Binary **FIT** stays JVM-only (Garmin SDK) behind the
  optional `FitBinaryDecoder` — absent on JS, so the FIT branch yields empty, never fails.
- **Namespace-awareness divergence (decision worth pinning).** `DOMParser` parses `application/xml`
  **namespace-aware**; `javax.xml` here runs **non**-namespace-aware. Both resolve nodes by local
  name, so a *well-formed* file behaves identically — but a browser **rejects an undeclared namespace
  prefix** as a parse error where the JVM tolerates it. Real-world GPX/TCX always declare their
  namespaces (`xmlns:gpxtpx`, …), so this only bites hand-rolled fixtures that omit them. The JVM
  `ActivityFileParserTest` GPX omits `xmlns:gpxtpx` (tolerated); the new `JsActivityFileParserTest`
  declares it (the honest web fixture). No production-file impact.
- **`JsActivityFileParserTest` (`jsTest`)** — the web twin of the desktop parser test: same GPX/TCX
  fixtures + assertions through `JsXmlReader` (route/HR streams, format sniff, garbage→empty), plus a
  `fitWithoutADecoderYieldsEmpty` case proving the decoder-less FIT branch degrades cleanly.

⚠ **Not machine-verified in this session.** The change follows the P5.3e plan and mirrors the desktop
reader/test one-to-one, but the sandbox could not run Gradle (the Gradle 9.4.1 distribution + Google
Maven for AGP are egress-blocked, caches empty), so `:fit-shared-data:compileKotlinJs` /
`jsBrowserTest` need a CI run to confirm — chiefly the first-JS-consumer `lifecycle-viewmodel`
resolution. No new deps, no threat-model change (A34; this is a compile-target + reader binding, no
data-shape/relay change).

**What "Fit on the web" still needs (NOT this slice).** This lands the *parser* on JS — the honest web
**acquisition** primitive (a browser `<input type=file>` → bytes → `ActivityFileParser` → the shared
`HealthImportWriter`). It does **not** make Fit a web app: there is still no web Fit **consumer** — no
React `fit.tenshu.app` subdomain, no `@tenshu/fit` SDK bridge exposing the Fit operations, no JS DI
that binds `JsXmlReader`/wires a `TenshuClient`. That is the Drive-web recipe (`drive-web-app.md`)
applied to Fit, and it is unbuilt. So: **the parser is web-ready; a web Fit app is not.**

#### 9.10a The parser moved to `:fit-shared-file`; Fit's logic layer no longer targets JS (2026-08-12)

§9.10 shipped the web parser by adding `js(IR) { browser() }` to `:fit-shared-data` — which has an
`api` dep on `:fit-shared-logic`, so **both** had to be JS-enabled together. That meant Fit's entire
logic layer, every ViewModel and the `FitRepository` seam included, carried a JS target so that
~470 lines of parser could compile.

⚠ **That was a local exception to House Rule #5**, and it is worth naming because it read as normal.
Drive follows the rule exactly: `drive-shared-logic` targets android/desktop/iOS only, and
Drive-on-web is the TypeScript rebuild over the thin SDK — the KMP→JS sharing happens at the
*protocol* layer (`core*`, `*-service-*`, `tenshu-sdk-js`) and never at an app's logic layer. Of the
~95 JS-targeting modules, the only *app logic* layers were `fit-shared-logic`, `fit-shared-data` and
`calendar-shared-logic`.

**What it cost.** `androidx.paging` publishes no `paging-common-js` (verified: `-android`,
`-desktop`, `-iosarm64`, `-iossimulatorarm64`, no `-js`), so `:fit-shared-logic` could not take
`:core-paging` while it targeted JS — and `fit-read-scaling.md` Phase 2 (paging the History feed)
was blocked by module topology rather than by any design question.

**The fix**: `:fit-shared-file` — `ActivityFileParser`, the `ImportedHealth` DTOs, `ActivityFile`,
and the `XmlReader` seam with its `javax.xml` / `DOMParser` / `NSXMLParser` bindings. Targets
android · desktop · js · ios; depends on `:health-service-common` alone (already JS-capable, which
is what makes its JS target free). `:fit-shared-logic` and `:fit-shared-data` depend on it and have
**dropped `js`**. The package stayed `app.tenshu.fit.data`, so not one call site changed.

⚠ **`export(project(":app-list-state"))` had to be added to the iOS umbrella** in the same change —
`export(...)` is not transitive (the same trap `:preference-service` is exported for), and
`HomeUiState.load` is a `ListLoadPhase`. Without it the Swift headers omit the type and `fit-ios`
does not compile, which no Kotlin gate would have caught.

⚠ The `XmlReader` seam is **not** Fit-specific and knowingly lives in a Fit-named module. Moving it
alone would not have unblocked anything (the *parser* is what must reach JS), and XML appears
nowhere else in the repo — the `app-*` family earns a module on the **second** consumer. It is
already behind an interface, so promoting it to an `:app-xml` later is a file move.

⚠ `calendar-shared-logic` has the same shape and will hit the same wall the day it wants paging.

**Verified**: `:fit-shared-file:compileKotlinJs` + `jsTestClasses` green (§9.10's capability intact),
274 Fit tests green, the full `desktopTest jvmTest test` gate green, and `scripts/ios-smoke.sh
--build-only` passing all eight iOS apps. The unblock itself was proved by temporarily adding
`:core-paging` to `:fit-shared-logic` and compiling desktop + iosArm64 — it resolves; the dependency
was then reverted, because paging History is a separate decision (see `fit-read-scaling.md`).
**Taken up and shipped 2026-08-12** — `:fit-shared-logic` now carries `:core-paging` for real
(`fit-history-paging-handover.md`).

### 9.11 Recorder permissions — per-capability just-in-time + priming (§9.8 UX refinement)

**Shipped 2026-07-11.** The §9.8 "just-in-time" gate requested **all** the recorder's runtime
permissions in one batch on *entering* the Record flow. Because those permissions span four OS
permission groups (Location, Physical activity, Nearby devices, Notifications) and Android renders
one system dialog *per group*, the user hit **four cold dialogs back-to-back** the moment they
opened the Record tab — before doing anything. The batch is replaced with a **per-capability,
point-of-use** flow, each preceded by an in-app rationale — the Google-Fit pattern (prime, then
defer Bluetooth/location to the feature that needs them).

- **`RecorderCapability` enum** (`:fit-shared-ui` `commonMain`) splits the set into **`Motion`**
  (`ACCESS_FINE_LOCATION` + `ACTIVITY_RECOGNITION` + `POST_NOTIFICATIONS`) and **`HeartRate`**
  (`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`). The gate API changes from `ensureGranted(onResult)` to
  **`isGranted(capability)`** + **`request(capability, onResult)`**. `isGranted` keys off each
  capability's **critical** permission (location for Motion, Bluetooth-connect for HeartRate) so a
  denied notification/step grant does not nag on every Start; the rest degrades per stream. The
  Android actual reuses one `RequestMultiplePermissions` launcher, tracking the pending capability;
  the desktop actual reports every capability granted (no runtime-perm model), so the whole
  priming/request flow collapses to an immediate proceed there.
- **`RecorderPermissionPrimer`** (`commonMain`) — a Material3 `AlertDialog` shown *before* the OS
  dialog, with capability-specific copy (Motion: "map the route, on-device step counter, ongoing
  notification — nothing leaves your device"; HeartRate: "pairs with a Bluetooth strap, only during
  recording"). Rendered only when `!isGranted`, so it never appears on desktop.
- **Wiring** (`RecordPreScreen`): the batch-on-entry `LaunchedEffect` is **removed**. **Start**
  requests `Motion` (immediately if held, else primer → dialog → start); the recording begins
  regardless of the outcome (graceful degrade). The **HR sensor row** grows a **Connect** action,
  visible only while HeartRate's grant is outstanding, that requests `HeartRate` behind its primer
  — so the ~majority who own no BLE strap are **never asked for Bluetooth**. `MainActivity` was
  already free of the launch-time request (§9.8 v0.12); no manifest/permission-set change (same
  declared perms, requested at different moments).

Net: from *four cold dialogs on tab entry* to *at most the Motion group asked at Start (primed) +
Bluetooth only if the user opts into a strap*. ⚠ **Not machine-verified in this session** — the
sandbox cannot provision the repo's JDK-17 toolchain (`api.foojay.io` is an org-policy egress
denial), the same Gradle limitation §9.10 hit, so `:fit-shared-ui` (desktop + Android) compile and
the `:fit-android` APK assemble need a CI run to confirm; the change is a pure UI/seam edit
(no new deps, no schema/wire change) mirroring the existing `expect`/`actual` gate one-to-one.
On-device dialog sequencing is the manual pass regardless (same posture as the rest of §9.8 — the
runtime-grant UI needs a device). No threat-model change (A34; this is request *timing/UX*, not a
new permission or data path).

### 9.12 iOS track sequencing (settled with maintainer 2026-07-17)

On-device signing landed (free personal team) with a standing smoke gate
(`scripts/ios-smoke.sh`), so §9.9's device gates are now reachable. Sequencing:

- **P-iOS-0 — free-tier proofs** (`ios-signing.md` §4/§5, manual): the C3 Control→Drive
  shared-Keychain handshake on hardware, and the `FileAdvisoryLock` two-process exclusion.
  These decide the paid-membership question — run them *before* paying (handover doc §C4d).
- **P-iOS-1 — live-EPC round trip on hardware** (§9.9's stated gate): `FitIos-Dev` harness
  paired to the shipping Control's *Settings → Connect an app* (relay invite → QR/paste →
  SAS → `FitLiveClientSession.activate`); prove ops flow live against a real Control.
  Timeline's twin seam rides the identical recipe afterwards.
  **✅ PASSED on hardware 2026-07-17** (maintainer, iPhone 17e): the SAS ceremony against the
  shipping Control succeeded and the harness swapped onto the live client — closing the
  device gate §9.9 shipped with. The shipping-UX follow-up **landed 2026-07-17**: the
  Keychain-invite auto-connect (`ControlAutoConnect` in fit-ios; Control's "Connect an app"
  host publishes via `publishEpcInvite`) — the shipping Fit silently goes live when Control
  is hosting, no pairing screen; the `-Dev` Pair tab stays as the manual fallback. Fit gained
  shared-Keychain entitlements (sim/device variants). Free-tier compatible (Keychain, not
  App Group); threat-model "Co-resident EPC auto-connect" amendment in the same PR.
  As-built pin: ios-proxy-architecture.md §12.
  ⚠ **`ControlAutoConnect` deleted 2026-11-05**, and the amendment retired with it. Route 1
  (P2, [`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) §8) is the
  co-resident path. Fit's shipping gate is route-1-only now, and the `-Dev` Pair tab (SAS) is
  the only EPC entry left.
- **P-iOS-2 — HealthKit importer** (the §8-deferred "Apple Health" row): a Swift
  `HealthKitImporter` mirroring `HealthConnectImporter`; iOS `availableSources()` finally
  emits `ImportSource.AppleHealth`; `NSHealthShareUsageDescription` + HealthKit capability
  (available on the free tier). Ordered *after* P-iOS-1 — an import into the mock store
  evaporates at process death.
  **BUILT 2026-07-17 (as-built deltas):** the importer is **Kotlin, not Swift** —
  `HealthKitImporter` in `:fit-shared-data` **iosMain** over the `platform.HealthKit`
  cinterop, exactly where Android's importer lives in androidMain (no Swift bridge, no seam
  export). Maps workouts / daily steps+distance+energy / sleep stages ("core"→light) / latest
  weight·resting-HR·SpO₂·resp-rate; active minutes read **directly from `appleExerciseTime`**
  (no HR-zone derivation). ⚠ HealthKit hides read denial by design → the importer never
  returns `PermissionsRequired`; empty `Success` covers both "no data" and "not granted".
  `EpcFitClient` binds it + a `HealthImportBootstrap` (per-live-session refresh, gated on a
  real store so the reference fixture never writes into the synced realm). Shared VM fix:
  a live platform source with empty `importCategories()` **skips the dead-end category
  picker** and imports the full selection (OS sheet = the consent surface) — fixes the same
  dead-end on Android live. ⚠ K/N interop: `HKUnit` factory class-methods don't resolve —
  use `HKUnit.unitFromString(…)`; HK identifier constants are `String?`. On-device pass
  pending (consent sheet + real data).
- **P-iOS-3 — the $99 decision gate**, taken on P-iOS-0's evidence. ⚠ Correction recorded
  here: the *shipping* first-party live path is **paid-tier-gated** — route-1's read side is
  the shared **App-Group** replica (handover §C4e), which a free personal team cannot
  provision. On the free tier, "live Fit on iOS" therefore means the EPC/relay client
  (P-iOS-1), not route-1; productionizing route-1 (for Drive first, then Fit/Timeline) lands
  behind this gate, alongside App Attest (C5) and the >3-apps-per-device limit.
- **Deferred behind those:** the iOS recorder (CoreLocation + HealthKit live capture),
  `BGTaskScheduler` background import refresh, and C6 pairing/background windows.

### 9.13 Durable recorder outbox + save-outcome UX (§9.8 durability refinement)

**Landed 2026-07-18.** Closes the two top items from the 2026-07-18 handoff: a finished workout
held only in the recording engine until the save succeeded died with the process, and a failed
save was a bare `println` (silent non-navigation, no error slot in `RecordUiState`).

**The outbox.** Timeline's visit outbox (`CapturedVisitOutbox`) was generalised into a new shared
module **`:app-outbox`** (mirrors `:app-liveness`: extracted on the second consumer, not forked) —
`OutboxStore`/`OutboxEntry` + a generic `JsonOutbox<T>` (journal-before-write, remove-on-confirm,
corrupt-entry purge) in `commonMain`, plus a `FileOutboxStore` shared by Android + desktop via a
`jvmFileMain` source set. Timeline's types are now thin fronts/typealiases over it (zero call-site
change); Fit adds **`RecordedWorkoutOutbox`**, journalling the recorder save as the same
**`ImportedHealth`** shape every importer produces (the DTOs became `@Serializable` — local
journal only, never wire; `RouteSample`/`HrSample` likewise).

**One mapping, one write path.** `FitProjectionRepository.addWorkout` no longer hand-rolls its own
`recordWorkout` call: it maps `(Workout, RecordedTrack?)` → `ImportedWorkout` (E7 conversion
included; `ImportedWorkout` gained `steps`, which the shared writer now persists — a latent gap
for every importer) and persists through the same `HealthImportWriter` as every import. With an
outbox bound the recipe is `CapturedVisitWriter`'s: journal → drain oldest-first → report
`WorkoutSaveOutcome.Synced` (entry confirmed gone) or **`SavedLocally`** (Control unreachable;
entry retained). `drainPendingWorkouts()` (mutex-guarded, stops at first failure, never throws)
replays on every launch via `HealthImportBootstrap` — which now runs the drain *regardless* of the
importer gate — and ahead of every new save. Replay is idempotent (`recordWorkout` is
content-addressed by `workoutId`).

**Engine + UX.** `RecordingEngine.finish()` returns `FinishedRecording(workout, outcome)`; on
either outcome the engine resets to Idle (the journal owns durability now). The throw path
remains for repositories without a journal: the engine keeps the session, and
`RecordUiState.saveFailed` shows "Couldn't save your workout … tap Finish again" (dismiss = keep
recording). `SavedLocally` sets `RecordUiState.savedLocally` → an always-dark dialog "Saved on
this device … will finish saving automatically", acknowledge → back to the pre-start screen (no
detail navigation — nothing is readable from the store yet). Strings via `stringResource` (ADR
0016). DI: `fitAndroidModule` binds the store at `filesDir/workout_outbox/`; `fitDesktopModule`
under `DesktopAppPaths.dataDir("app.tenshu.fit.desktop")`; iOS binds none yet (no fit-ios shell)
→ legacy throw path.

**Verified:** `JsonOutboxTest` + `FileOutboxStoreTest` (`:app-outbox`); RecordViewModel
saved-locally/save-failed branch tests; `FitPipelineIntegrationTest.recorderOutboxJournalsAndReplays`
(healthy proxy → `Synced` + empty journal; dead proxy → `SavedLocally` + entry retained; "next
launch" drain over a healthy proxy → workout lands with steps intact, entry removed); timeline
suite green post-refactor; all targets compile (desktop/Android/iOS-sim/JS), both APKs assemble.
Threat-model **A34 amended** (plaintext route+HR journal in app-private storage until replay —
the A35 visit-outbox posture). Known limit: a `SavedLocally` workout is invisible to History until
replay (the dialog says so); surfacing pending entries as ghost rows is a possible later slice.

### 9.14 Control-unreachable signal + shell banner (closes the proxy move's known gap)

**Landed 2026-07-18.** Fit has no store of its own — every read goes over IPC to Control — so an
unreachable Control (not installed / disabled / Control-desktop not running) folded every read to
empty and the app was **silently blank** with no explanation (the gap called out when Fit moved
onto the proxy).

**Signal.** `FitRepository.controlReachable(): StateFlow<Boolean?>` (`null` unknown → first
outcome; default always-`true` for non-IPC impls — mock, previews). The real repository derives
it three ways, because the failure surfaces are *not* uniform:

- **The probe owns detection.** The key discovery: a reactive `.flow()` read against a dead
  transport does not reliably fail — the stream registration's dispatch error can be swallowed
  and the collector **hangs silently** (that IS the blank screen). So detection can't wait on
  reads: a slow probe (5 s; the cheapest one-row/one-field select) runs whenever the state is
  not known-good (`null` at launch, `false` while down), on the app scope via DI.
- **Organic outcomes accelerate it**: every one-shot read (`workout(id)`), each input flow's
  fold, a thrown stream read (`.catch`), and the outbox drain's write outcome all mark the
  signal when they do surface (the executor folds transport failures into `TResult.Failure`).
- **Recovery is active, not passive.** On a `false → true` probe flip the repository (a) bumps an
  internal `readEpoch` that every input flow `flatMapLatest`s on — hung/dead streams are
  **re-subscribed and live screens repopulate without re-entry**; and (b) **drains the recorder
  outbox** (§9.13) — Control being back is exactly when journalled saves can land.

**UI.** One shell-level banner (`ControlUnreachableBanner`, above the nav host in `MainShell`) —
"Can't reach Tenshu Control — your health data can't load. Make sure Control is installed and
running." `errorContainer` (not Drive's Offline `secondaryContainer`: that means "queued, app
usable"; this means "nothing loads"). No action button — recovery is automatic. Suppressed on
the always-dark live-record surface (§9.13 dialogs own that flow).

**Verified.** `FitPipelineIntegrationTest.controlReachabilitySignalsAndRecovers`: journal while
dead (no-impl proxy) → probed repo signals `false` → flip → `true` + epoch bump + journal
drained + workout readable. The probe is a constructor **seam** in the test — the in-process
harness executes ops through the shared `TContext`, so a real transport death is not simulable
there; the real probe's failure path is the transport's production Failure folding. **Live
on-emulator round trip passed**: `pm disable` Control → launch Fit → banner renders (uiautomator
match); `pm enable` Control → same Fit process, probe reconnects, banner clears. No threat-model
change (client-side signal; no data shape, no relay surface).

**Extraction pin (2026-07-18):** the §9.14 engine is now the shared **`:app-reachability`**
module (`ControlReachability`: probe-owned detection, `markReachable`/`markUnreachable` organic
hooks, `readEpoch` + `resubscribing` recovery, `onRecovered` callback — Fit's replays the §9.13
outbox) and the banner form is `:ui-core`'s `ErrorBanner`.
⚠ **The `markReachable` hook is gone as of 2026-09-03** (`core-client-resubscription.md` §4.4b):
it cancelled the probe and skipped the recovery, so Fit's three call sites could stop the very
`onRecovered` that drains the outbox. `TenshuClient.proxyAnswered` wakes the probe instead. Timeline and Maps — which shared the
same silent-blank gap from the move onto Control's proxy — now run the identical recipe
(timeline-app.md §10 / maps-app.md §7 carry the pins). `FitProjectionRepository` keeps only the
Fit-specific plumbing (the `foldTracked`/`unreachable` folds and the one-row probe).

#### 9.14a Fit stopped re-subscribing its own reads (2026-08-11)

⚠ **The third bullet above is no longer how Fit recovers a read, and the `.catch` in the second
is gone.** Both were written against a platform that could not recover a broken read on its own.
It can now, and running both meant doing the work twice:

- **R1** (`DefaultInteractorProvider.resubscribeAll`) replays every live stream against a
  restarted proxy, self-wired to the executor's reconnect edge.
- **R1b** (2026-08-03) made `TList.flow()` retry its *own* failed subscribe — 1/2/4/8/15 s
  backoff, and sleeping on the reconnect **edge** rather than the timer while the channel is down
  (`Stream.kt` `awaitNextSubscribeAttempt`). It also reports the failure as a `TResult.Failure`
  **emission** rather than throwing, so `foldTracked` — not `catch` — is what sees it.

So `readEpoch.flatMapLatest { … }` was tearing down and rebuilding **nine** streams per recovery
— one of them the 300-workout History window — including streams that were healthy or already
mid-retry, re-shipping a full snapshot each time. Removed. `ControlReachability` keeps everything
else: the probe, the banner signal, and `onRecovered` → the §9.13 outbox drain.

The `.catch { unreachable(it) }` that wrapped every read is replaced by
`FitProjectionRepository.retryingOnThrow(read)`, because `catch` was wrong in two ways once it
stopped being the recovery path: it **completed** the flow (so if it ever fired, that screen was
dead until an epoch bump revived it), and it marked Control unreachable for what — post-R1b — can
only be a client-side throw, parking the banner on a decode or projection bug. The replacement
retries with the same backoff table, keeps the last good rows on screen, and names the read in the
log line.

⚖️ **The one thing that got slower.** `flow()`'s retry has no edge to wait on when the channel is
*up* and the read is refused anyway — the announce-gate skew case — so it falls back to the 15 s
timer. Previously the probe's recovery flip could rebuild sooner than that. Recovery in that case
is now bounded by 15 s rather than the ~5 s probe interval; nothing else regressed.

**Verified.** `FitPipelineIntegrationTest.aReadStreamRecoversFromAFailedSubscribeWithNoFitSideResubscriber`
— an `InProcessProxy` that returns no `Stream` implementation while a flag is set, so the subscribe
genuinely fails; the collector stays alive and empty, and recovers to the seeded row on its own
once the flag clears. ⚠ Deliberately **not** the §9.14 probe seam: over a healthy proxy the first
successful read marks the engine reachable, which stops the probe loop, so no epoch flip is
reachable and the assertion would have held against either implementation. The platform primitive
itself is pinned by `StreamSubscribeRetryTest`.

⚠ **`:contacts-shared-data` still wraps its reads in `resubscribing`** and has not been
re-examined; the epoch stays on the engine for it.

#### 9.14b The banner explains an outage; it cannot explain a failed read (2026-08-11)

§9.14's banner answers *"is Control reachable?"*. That is the common cause of a blank Fit and it is
now well covered — but it is not the only one, and the two surfaces that draw a **conclusion** from
emptiness were stating it as fact regardless:

- **Home**: `today == null` with `loaded = true` rendered *"No activity yet — record a workout or
  connect a health source."*
- **Browse**: every category `isEmpty` rendered seven "Not set up" cards **and** the *connect a
  data source* prompt — which additionally fired on the first frame of every launch, before any
  read had answered, because "no categories yet" and "every category empty" are the same list.

A reachable proxy can still refuse or fail a read (announce-gate skew, `NotFound`, a decode
error), and `foldTracked` folded all of it to `emptyList()`. So Fit adopted Drive's
`ListLoadState` / `ListLoadPhase`, extracted for the purpose into **`:app-list-state`** (neither
app can depend on the other, and `:ui-core` is Compose while the `*-shared-logic` modules must
stay Compose-free for iOS).

**Shape.** The nine `*Inputs` streams carry `ListLoadState` via `loadTracked`. `aggregates()` and
`categories()` expose it; every other read flattens with `itemsOrEmpty()` at the last mile, and
their `*State()` halves sit right beside them so a surface that later wants the three states
promotes the public one rather than re-deriving where the failure went. A multi-read surface takes
the **worse** phase (`mergedLoad`) — these are all IPC reads against the same proxy so they fail
together, and a partially-merged answer rendered as complete is the same lie in a smaller font.
`HomeUiState.loaded: Boolean` becomes `load: ListLoadPhase`; a failed read **keeps the previous
figures** and moves only the phase, because a ring reading zero is a claim about the user's day,
not a neutral placeholder.

**Verified.** `FitViewModelsTest.home_failedRead_isNotTheEmptyStore` +
`browse_failedRead_keepsTheCardsAndFlagsThem` + the two first-frame tests. ⚠ `EmptyFitRepository`
could not catch this on its own — an answered-empty store and a failed read produce the *same*
Home, so a fake that models only the happy path made the broken screen look correct; hence the
sibling `FailingFitRepository`. Discrimination checked by flipping the FAILED arm to LOADED and
watching the test fail.

⚠ **Not swept**: `CategoryDetailViewModel` (its chart has no failure affordance of its own), and
the sleep / samples / medical / nutrition / cycle surfaces. `tech-debt.md` carries the list.

### 9.15 Auto-import gate — `autoRefreshReady` (grant-less launch loop closed)

**Landed 2026-07-18.** The once-per-launch refresh (`HealthImportBootstrap`) fired
`startImport` on every launch wherever an importer was bound — on a grant-less device it could
only abort into `PermissionsMissing`, parking a permanent "needs permission / try again" banner
the user never asked for (the emulator's standing state). Worse, the gate-by-binding
(`enabled = importer bound`) missed a real hazard: Android always binds `HealthConnectImporter`,
and on a device **without** Health Connect its fallback yields the reference **fixture** as a
`Sample` outcome — which `startImport` *writes*. An unattended launch import on a no-HC device
would have pushed fixture workouts into the durable, synced store (the sharp edge of moving onto Control's durable store,
resurfaced through the fallback).

**Shape.** `HealthImporter.autoRefreshReady(): Boolean` (default `true`): whether the
*unattended* refresh should run — distinct from `availableSources()` (offerable ≠ silently
importable). `ReferenceHealthImporter` → `false` (a fixture is never a source to refresh from
unattended); `HealthConnectImporter` → `hasAllPermissions()` (false when HC absent too, closing
the fixture hazard). `HealthImportBootstrap` now takes the importer (replacing the `enabled`
flag) and gates only the refresh — the §9.13 outbox drain stays unconditional; a throwing
readiness check counts as not-ready without taking the drain down.

**Deliberately NOT gated:** manual imports (`startImport` from onboarding / Profile / the
permission-prompt result in `MainActivity.onPermissionResult`). There the user acted, so an
abort-with-banner or a labelled `Sample` outcome is an answer, not noise — in particular the
prompt-then-deny path still sets `PermissionsMissing` on purpose (a denied grant must not leave
an empty app with no reason why, the fix that came with the recorder-crash / empty-workout-detail repair).

**Verified:** `HealthImportBootstrapTest` (null importer / not-ready / ready / throwing
readiness / reference-never-ready); Fit suites green; iOS-sim compiles; APK assembles; live on
the grant-less emulator: "[fit] startup refresh skipped (importer=HealthConnectImporter not
auto-refresh-ready)" with 0/10 grants, no unattended import, no crash. The permission banner
that remains there comes from the *prompted* MainActivity flow (by design, above).
`HealthConnectImporter.autoRefreshReady` on a granted device = on-device pending (same posture
as the rest of P5.2).

**iOS amendment (2026-10-17, App Store review prep).** `HealthKitImporter.autoRefreshReady` was
`store != null && NSHealthShareUsageDescription declared` — true on a fresh install, so the launch
pass's `import()` called `requestAuthorization` and the all-types HealthKit sheet appeared before
any tap (5.1.1 / 5.1.3 want the request tied to a user action). It now also requires
`getRequestStatusForAuthorization` = `.unnecessary` for the full read set: HealthKit hides *what*
was answered but not *whether*, which is exactly the Android `hasAllPermissions()` shape. The first
import is the explicit *Import from Apple Health* (Home's empty state, Profile → Data sources —
`ProfileViewModel.importFromHealthSource`, a manual pass). ⚠ A read type added in a later build
reads as unanswered, pausing the unattended refresh until the next manual import — honest, and
cheaper than a launch-time sheet. The fixture fallback is gone from iOS in the same change: with no
health store (iPadOS 16) `HealthKitImporter` offers no source and answers `SourceUnavailable`,
because the manual path above is ungated and would have written the `Sample` fixture into the realm.
Pinned by `HealthKitImporterGateTest` (iosTest).

**Android follow-up — DONE (2026-10-18).** `HealthConnectImporter` had the same fallback: with
Health Connect not installed, needing a provider update, or unable to run, `availableSources()`
offered the fixture's `HealthConnect` and `import()` answered `Sample(fixture)`, which
`FitProjectionRepository.runImport` wrote through `gatedHealthWriter()` into the realm on any manual
import (onboarding's Health Connect row, Profile, Home's *Try again*). Desktop was exposed the same
way: `realDataModule` bound `ReferenceHealthImporter` wherever no importer was bound. Three changes:
- `HealthConnectImporter` offers **no source** and answers `SourceUnavailable` without Health
  Connect (no `fallback` parameter any more); desktop binds `NoHealthSource`, which is also
  `FitProjectionRepository`'s default. The fixture is now test/preview-only.
- ⚠⚠ **The store guard is shared:** `runImport` refuses every `ImportOutcome.Sample`, manual or
  automatic, on every platform. It writes nothing, records no run, and publishes `SourceUnavailable`.
  So `ImportStatus.Sample` is no longer produced. Tests that need the fixture *in* the store use the
  commonTest-only `FixtureAsRealImporter`, which dresses it as a `Success`.
- Home's empty state follows iOS: without a health source it says *"Health Connect isn't available
  on this device. Record a workout or import a file to get started."* and adds *Import a file*
  (batch import) where a `HealthFilePicker` is bound. There is **no** install route: the sharing
  wizard's `InstallStore` step is deliberately not actionable, because on Android 14+ Health Connect
  is a system module. This change did not add one.

Pinned by `HealthImportTest.aManualImportOfSampleDataWritesNothingToTheStore` /
`withNoHealthSourceAManualImportOffersNothingAndWritesNothing` (commonTest, over the real
repository and an in-process proxy), `HealthConnectImporterUnavailableTest` (`:fit-android` unit
test), and `FitViewModelsTest.home_withoutAHealthSource_saysSoAndOffersFileImport`.

### 9.16 Batched import — `RecordImportedHealth` (one round-trip; the iOS SIGKILL race)

**Landed 2026-07-17 (with the P-iOS-2 on-device import).** The E2E HealthKit import on iOS kept
aborting mid-flight: the write side fired one op per record (five record ops × N records, each
its own EPC round-trip with a 10 s timeout), and iOS SIGKILLs the backgrounded Control seconds
after Fit comes to the foreground — run 5 landed 2 of 4 `DailyActivity` rows before the host
died. The per-record stream was losing a race whose window is fixed by the OS; the fix is to
need only **one** round-trip.

**Shape.** New `:health-service` op `RecordImportedHealth` (full 5-module split like the record
ops): five **required** `TList` params — workouts / dailyActivities / sleepSessions /
healthSamples / medicalRecords — an empty list meaning "none of this kind" (no optional-param
presence encoding, no empty-vs-absent ambiguity). The proxy implementation `saveData`s each
record exactly as the per-record ops do (same relation maintainers, same SyncObserver emission)
— only the number of IPC round-trips changes. Batch idempotence falls out of the per-record
content addressing (`workoutId` / `date` / `metric` / `recordId`).

**Deliberately summary-only:** a workout carrying a dense route/HR stream blob stays on
per-record `recordWorkout` — one blob per op frame. Batching many stream blobs into one frame
would trade the timeout race for a payload memory ceiling (the whole-`ByteArray` lesson from
D2a/SKIE); a phone-API import (HealthKit / Health Connect) is summary-only anyway, so its whole
payload is genuinely one call.

**Writer.** `HealthImportWriter.write` partitions on stream presence, maps every summary record,
and sends everything ≤ 500 records as **one** `recordImportedHealth` call; a bulk import beyond
that falls back to per-kind 500-record chunks (extra round-trips are irrelevant on the
desktop/Android bulk paths — the chunk bound is about proxy-side payload + save-loop size, and
the `ImportPacer` backpressure contract survives: the per-record `onRecord` hook now fires
per record *after its chunk lands*, so pauses aggregate between chunk ops).

**Verified:** `RecordImportedHealthUnitTest` — a real client RO drives the op through
**genuine wire framing** (callstack → `BinarySerializer` → `ProxyGateway` parse → execute →
serialized `TResult` back, the `RemoteBinaryTaskExecutor` shape) for all five kinds + the
empty-lists case; `FitPipelineIntegrationTest` (the whole import pipe now rides the batch op) +
`FileImportPipelineTest` (dense-stream partition) green; Android + iOS-sim + umbrella compile.
⚠ Note for the next wire test: hand-assembling request bytes with standalone-serialized `TList`
params does NOT match callstack framing (a nested list is length-prefixed and header-elided) —
drive the client RO instead.

### 9.17 Workouts read: sort by a field-selector lambda, not the property overload

**Landed 2026-07-18.** The reactive workouts read (`recentWorkoutInputs`, feeding History + the
Home teaser) died with `tenshu/core/error/internal` on the EPC hop — the read path counterpart of
§9.16's write fix. The write completes (`import: DONE`), but `workouts.sortBy(...).take(...)
.select(...).flow()` yielded nothing, so History stayed empty.

**Root cause (a wire-registration gap the in-process test can't see).** The read used the property
overload `sortByDescending(Workout.Start)`, which ships a raw `StaticPropertyRelation` whose
reference is the `START` **schema field** id. The wire reference-weight system (`FieldReferenceDto`
weight 3 > `PropertyTypeReferenceDto` weight 2) always decodes a field id as a *field* reference,
so the `StaticPropertyRelation` can't be reconstructed and the whole callstack dies on the proxy's
`BinaryParser`. `File.LastModifiedAt` avoids this only because it is a **pure** value property with
a dedicated id — Drive never sorts by a schema field. Registering `Workout.Start` as a wire value
property does **not** help: the weight system keeps the field reference.

**Fix.** Sort by the **lambda field-selector** — `workouts.sortByDescending { start }` — which ships
a plain field read (a `FieldReference`, which round-trips), exactly the shape
`notifications.sortByDescending { timestamp }` already uses. Two parts: (1) the `Workout` client RO
must be a **typed** RO (`object : CompositeRo(...), Workout {}`, mirroring Notification) so the
lambda can call `Workout.start` (its default accessor builds the field-read chain) — a bare
`CompositeRo` can't be cast to `Workout`; (2) the repo call switches from the property overload to
the lambda. The proxy-side `sortBy → take → select` window optimisation is preserved (still no
client-side full-list sort).

**Verified.** `WorkoutSortWireTest` drives `workouts.sortByDescending { start }.take(2).select(...)`
through genuine serialization (client RO → `BinarySerializer` → `ProxyGateway` parse → execute →
`TResult`) and asserts newest-first windowing — the exact EPC hop the in-process
`FitPipelineIntegrationTest` cannot reproduce (the local connection skips the parser). On-device:
Fit's History populates from the synced workouts, no `internal` error.

### 9.18 "Today" over yesterday's workouts — a process that outlived midnight

**Landed 2026-08-13.** Reported as a grouping/locale bug: at 10:39 on 13 Aug the top row of
History's **Today** section was a run at *18:54*, and the bottom one a ride at *08:26* the user
was sure had happened at 09:26. Neither reading was a time-zone fault.

**What the store actually said.** Decoded off the maintainer's phone (`run-as` → `objects.db`
+ WAL → positional blob walk, per `feedback_read_android_store_via_runas`), the two rows were
`start = 1786553692124` → 2026-08-12 16:54:52Z → **18:54:52 CEST**, and
`start = 1786515996416` → 2026-08-12 06:26:36Z → **08:26:36 CEST**. Both stamps are exactly right,
and both are **12 Aug**. The newest row in the whole store was 2026-08-12 17:03 UTC — *nothing*
from 13 Aug existed.

**Two independent defects, one shared cause: nothing in Fit re-reads the clock.**

1. **`today` was frozen.** `FitProjectionRepository.today` was
   `val today = Clock.System.todayIn(zone)` — evaluated in the constructor, and the repository is
   a **process-scoped Koin singleton**. The Fit process had been up **13 h 26 m** (started the
   previous evening, never restarted), so `today` was still 12 Aug and
   `dayLabel(daysAgo <= 0 → "Today")` drew a `Today` header over the whole of the previous day.
   ⚠ The tell was on screen the whole time: `DayHeader` renders `dateMedium(date)` beside the
   label, so the row read **"Today · Aug 12"**. `zone` was frozen the same way — the same defect
   would have shifted every displayed time by an hour across a DST change or a flight.
   ⚠⚠ The **view models froze it a second time**: `HistoryViewModel.today` and
   `CategoryDetailViewModel`'s `observe*(today)` parameters captured `repository.today` at
   construction, so fixing the repository alone would have left the headers pinned to whenever the
   screen was first opened. A `ViewModel` outlives midnight as readily as the process does.

2. **The import was once per *process*, not per launch.** `HealthImportBootstrap` fires from
   `startKoin` (`createdAtStart = true`), and `MainActivity` deliberately does not import on resume
   (§9.15's anti-double-import note). On Android "launch" and "process start" are different
   things — a Fit process survives being swiped away and lives for days — so the morning's ride was
   never pulled out of Health Connect at all. What the user read as "my 09:26 ride logged at 08:26"
   was yesterday's 08:26 commute ride mislabelled *Today*, with today's ride simply absent.

**Fix.** `today` / `zone` / `coverageFromMs` become getters (the repository takes an injectable
`Clock` so a test can cross midnight); `HistoryViewModel.today` and `CategoryDetailViewModel.today`
become getters read per emission; `HealthImportBootstrap` grows a `refresh(reason)` that the
Android shell calls from `onStart()`. `refresh` debounces on a **monotonic** `TimeSource`
(`minRefreshInterval`, 60 s) so the cold-start pair — this class's `init` plus the first
`ON_START` — and a rotation's `ON_START` collapse to one pass; `startImport`'s own in-flight
`tryLock` (§9.15) still catches genuine overlap.

⚠ **Not fixed here:** nothing recomposes *at* midnight while the screen is open, so a feed left
on screen across the boundary still shows the old header until the next emission or foreground.

#### 9.18a iOS — what transferred for free, and the one part that did not

The `today` freeze **needed no iOS work at all**, which is worth stating because the instinct is
that it would. Both layers are common KMP that iOS already consumes: `FitProjectionRepository` is
`:fit-shared-data/commonMain` and route-1 builds a real one (`FitLocalRuntime.assemble`); the view
models are `:fit-shared-logic/commonMain`, exported through `FitSurface.historyViewModel()`, and
SwiftUI reads *their* `daysAgo` (`HistoryView.swift:47`, `CategoryDetailView.swift:238`) rather
than deriving a day label of its own. Desktop is covered the same way — and it matters there, since
a `fit-desktop` process routinely runs for days.

The **foreground refresh** was the part that did not transfer, for a reason that is easy to miss:
both iOS tiers *constructed the bootstrap and threw the reference away* —

```kotlin
HealthImportBootstrap(repository = …, scope = …, importer = …)   // result unused
```

— so there was no object left to ask for a second pass, and `FitLocalRuntime.install()` is
idempotent. ⚠⚠ iOS was **not** covered by `FitBackgroundRefresh` either: that is a
`BGAppRefreshTask`, so the OS picks the moment (≥15 min apart, never on the simulator), and on the
EPC tier it no-ops unless Control happens to be co-resident and hosting. Neither path can answer
"the user just opened the app".

**Fix.** Both tiers now name the bootstrap and hand it to `FitSurface.installForegroundRefresh { … }`;
`FitSurface.refreshHealthImport()` is the Swift-facing entry, a no-op when nothing live is
installed. `FitIosApp.swift` calls it from `scenePhase == .active` in both the shipping root and
the dev harness, right after `connect()`. The debounce is the same one Android's `onStart` relies
on, so firing on every activation — including the one immediately after launch — is safe.

⚠ Deliberately left alone: the two hand-rolled copies of the pass inside `buildBackgroundRefresh`
(one per tier), which the `BGAppRefreshTask` still drives directly and which therefore bypass the
debounce. Folding them into the bootstrap is the obvious follow-up, but background wake behaviour
is device-gated and unverifiable from here; a wake and a foreground colliding is bounded by
`startImport`'s in-flight `tryLock` in any case.

**Verified.** `FitTodayRolloverTest` (repository crosses midnight under an injected clock),
`FitViewModelsTest.history_todayFollowsTheRepositoryAcrossMidnight`, and two new
`HealthImportBootstrapTest` cases (a foreground past the window refreshes; one inside it folds).
⚠ Each was falsified first — reverting the corresponding fix turns exactly that assertion red
(`expected:<2026-08-13> but was:<2026-08-12>`, and `expected:<1> but was:<2>`). Full gate green;
Android smoke gate run.

## 10. As-built — Fit runs on the Control proxy (the in-process store is gone)

**Landed 2026-07-13.** Fit is now a Control client like Drive. `:health-service` reads and writes go
over IPC to Control's proxy; Fit hosts no proxy and no store of its own.

### 10.1 The bug that forced it

Recorded workouts never appeared in History after an app restart, while Home kept showing step
counts — so the app looked half-broken rather than obviously broken. The cause was not in the Fit
UI or the projector: **`InProcessFitProxy` built its `InProcessProxy` over an `InMemoryObjectStore`**
(`:core-common`, hardcoded — not a constructor seam). Fit's entire health store was RAM. On the
device this was visible from the outside: Fit's app-private directory contained no database at all,
while Control's held an 8.7 MB `objects.db`.

Home only *looked* durable because `InProcessFitProxy.init` re-ran the Health Connect import at
every launch, which re-created the `DailyActivity` rows. Nothing re-created a *recorded* workout —
Fit never writes back to Health Connect — so History was empty forever after the process died. The
two symptoms had one cause.

### 10.2 Why the standalone proxy existed, and why it no longer should

It was bring-up scaffolding. Fit was built UI-first over a mock; the in-process proxy lit up the
real projector without wiring Fit into Control, and §3 D1 parked the rest ("a synced/paired Fit is a
later, larger concern"). Both halves of that concern have since landed **independently**:

- Control's proxy **already hosts `:health-service`** on Android, desktop and iOS — schema modules
  registered, `initHealthService()` (the relation maintainers) called, and
  `healthServiceProxyImplementationFactory` in the op factory, over its durable store and sync.
- Fit **already had a `TenshuClient` reaching Control** over Binder / UDS (`fitClientModule`), used
  only for offline map regions — including the reverse push channel that reactive reads need.

So Fit was already a Control client that declined to use Control for its own data. iOS had in fact
already taken the right path (`EpcFitClient` builds a `FitProjectionRepository` over the EPC channel);
Android and desktop were the outliers.

### 10.3 What changed

- `fitClientModule` registers `healthServiceCommonModule` + `healthServiceClientModule` alongside the
  region modules. One client, two service surfaces.
- `realDataModule` binds `FitProjectionRepository` over **that** client. The `FitRepository` seam is
  untouched — no VM, screen or projector moved.
- **`InProcessFitProxy` is deleted.** `:health-service-proxy` drops from `:fit-shared-data`'s main
  dependencies to test-only: Fit is a client and ships no proxy-side code.
- The per-launch import moves to `HealthImportBootstrap` (`createdAtStart`). It is now a *refresh*,
  not seeding — the record ops are content-addressed, so a re-import folds onto existing rows.
- Desktop's file-import menu writes through the same Control-backed client.

**Fit now requires Control** (Drive's contract). This is the deliberate trade: a fallback to a local
store is what produced this bug — data landing in two places, one of which silently evaporates.

### 10.4 Verified on device (2026-07-13)

Against a real phone with Control installed: Home renders live Health Connect steps read back
through Control; a Walk recorded in Fit lands in Control's store (`recordWorkout` over Binder),
appears in History, and — the point of the exercise — **is still in History after
`am force-stop` + relaunch**. Fit's private dir holds no entity store; Control's `objects.db` takes
the write. Health rows now also sync to paired devices like any other feature-service entity, which
is what A34 always said they did.

**Follow-up (not in this PR):** when Control is unreachable the reads fold to empty and the app
renders blank screens with no explanation — the honest-empty-state work in flight (`ImportStatus`)
is the right home for that signal.

## 11. Remaining work — the iOS recorder track

This section expands §9.12's one-line deferral ("*the iOS recorder (CoreLocation + HealthKit
live capture), `BGTaskScheduler` background import refresh, and C6 pairing/background windows*")
into shippable phases, after a full sweep of what iOS has and hasn't got.

### 11.0 Where iOS is now

**Live and on-device proven:** all 11 SwiftUI screens over the real `FitProjectionRepository`
(EPC → Control, `EpcFitClient`/`FitSurface`, Keychain auto-connect); the **HealthKit importer**
(`HealthKitImporter`, Kotlin `iosMain` cinterop — workouts / activity / sleep / vitals; §9.12
P-iOS-2); the **batched import** op that closes the SIGKILL race (§9.16); the
**workouts read** fix (§9.17); the **reachability** signal + banner (`:app-reachability`).

**The gap called out — recording was a mock.** `FitSurface.recordViewModel()` built its
`RecordViewModel` over `MockRecordingEngine` unconditionally (unlike Android, whose
`fitAndroidModule` binds `AndroidRecordingEngine` through the `RecordingEngineFactory` seam). So
the Record tab on iOS ticked synthesized elapsed/distance/HR and wrote a *fabricated* workout —
no CoreLocation / CoreMotion / CoreBluetooth capture. Everything downstream of capture already
exists: the always-dark live surface (`RecordView.swift`), the write-path seam
`FitRepository.addWorkout(workout, RecordedTrack?)`, the neutral `RecordedTrack`/`GpsFix`/`HrPoint`
models (`:fit-shared-logic` commonMain, iOS-clean), the `Workout.Streams` blob mapping, the durable
recorder outbox contract (`:app-outbox`, §9.13), and reachability-driven replay. The recorder is
the one missing piece; the rest is plumbing it in.

**Architecture decision (follows precedent, not a new call).** The engine is **Kotlin in
`:fit-shared-data` iosMain over platform cinterops** — the exact shape as `HealthKitImporter`
(Kotlin, not a Swift bridge; §9.12 P-iOS-2 corrected the earlier "native Swift" framing). It
implements the same `RecordingEngine` interface `AndroidRecordingEngine` does, over the iOS
equivalents of Android's three de-Googled sources — so it inherits the per-source-optional
resilience, the foreground-only-GPS constraint, and the `addWorkout(workout, track)` write path
unchanged. No SwiftUI recording logic; Swift only drives the shared `RecordViewModel` (it already
does) and owns the OS permission prompts.

### 11.1 R-iOS-1 — the `IosRecordingEngine` (Kotlin cinterop; sim-verifiable seam)

**LANDED 2026-07-18.** `IosRecordingEngine` (`:fit-shared-data` iosMain) implements
`RecordingEngine` over three first-party sources, each independently optional (a missing
authorization/hardware degrades that stream, never fails the recording — `AndroidRecordingEngine`'s
posture):

- **GPS** via `platform.CoreLocation` `CLLocationManager` (`desiredAccuracy = kCLLocationAccuracyBest`)
  — real fixes → the `RecordedTrack` route + a haversine distance/pace accumulator (the Android
  engine's math ported; `verticalAccuracy < 0` ⇒ no altitude). Started in `start()`, stopped in
  `finish()`/`discard()` (foreground-only, no standing location; `allowsBackgroundLocationUpdates`
  is R-iOS-2, gated on the Info.plist background mode).
- **Steps** via `platform.CoreMotion` `CMPedometer` (`startPedometerUpdatesFromDate`) — the on-device
  motion coprocessor, the iOS twin of `TYPE_STEP_COUNTER`.
- **Heart rate** via `platform.CoreBluetooth` `CBCentralManager` — GATT Heart Rate Service `0x180D` /
  Measurement `0x2A37`, the **same** standard chest-strap profile Android speaks (the flags-bit0
  8/16-bit BPM parse ports verbatim). BLE, not HealthKit, keeps parity with Android and sidesteps
  the watchOS-only `HKLiveWorkoutBuilder`.

**Seam wiring.** `FitSurface` gains a `RecordingEngineFactory` install slot (default
`MockRecordingEngine`); `installRealClient(channel)` swaps in `IosRecordingEngine` over the
just-installed live `FitProjectionRepository`, `installMock()` restores the mock — mirroring how
`installRepository`/`installRealClient` already swap the data source and how Android's
`realDataModule` binds the factory. `recordViewModel()` now builds over the installed factory, so
the SwiftUI Record flow (`RecordView.swift`) is unchanged.

**Side-effect-free construction (the sim-safety property).** The CL/CM/CB managers are `by lazy`,
created only when `armSensors()`/`start()` runs — so assembling the engine and observing its
`state()`/`sensors()` flows never prompt for Location/Bluetooth authorization. A new
`FitSurface.buildRecordingEngine()` (internal) lets the test assert the factory swap **without**
constructing a `RecordViewModel` (whose init arms the sensors).

**Verified** on `iosSimulatorArm64Test`: `:fit-shared-data:compileKotlinIosSimulatorArm64` clean;
the umbrella `TenshuSdk.framework` links; `EpcFitClientTest.installRealClient_swaps_the_recorder_
onto_the_real_engine` proves the live client binds `IosRecordingEngine` and `installMock` restores
`MockRecordingEngine`. Sensor/BLE/GPS I/O is Apple-framework, device-gated → **R-iOS-4**. No SwiftUI
change (a seam, not a UI slice — the §9.9/§9.13 posture; no `xcodebuild` app run needed). No
threat-model change beyond A34 (route + HR are exactly what A34 covers; sealed plane, no
relay-visible field; GPS foreground-only).

### 11.2 R-iOS-2 — capabilities, usage descriptions, JIT permission flow

**LANDED 2026-07-18.**

- **Capabilities (`project.yml` → generated Info.plist).** `NSLocationWhenInUseUsageDescription`,
  `NSMotionUsageDescription`, `NSBluetoothAlwaysUsageDescription`, and `UIBackgroundModes: [location]`
  so GPS survives the user leaving the app mid-workout (`allowsBackgroundLocationUpdates = true` set
  only while a recording is active — the FGS analog, cleared on teardown). ⚠ Decision: **no
  `NSLocationAlwaysAndWhenInUseUsageDescription`** — background updates work under *when-in-use*
  authorization once `UIBackgroundModes:location` is declared, and we never call
  `requestAlwaysAuthorization`, so the Always string would only invite an unnecessary review
  question. HealthKit + Keychain entitlements already present; no App Group (free-tier compatible).
- **No cold prompts on tab entry (the core fix).** R-iOS-1's `armSensors()` requested location and
  created the `CBCentralManager` — which, since `RecordViewModel.init` arms on entering Record,
  would cold-prompt for Location *and* Bluetooth the moment the tab opened (the exact anti-pattern
  §9.11 removed on Android). `armSensors()` now only *reflects* current authorization (reading
  `authorizationStatus` and constructing a `CLLocationManager` are silent); it requests nothing and
  never touches the radio.
- **Per-capability JIT (Swift-side, mirrors §9.11).** iOS is native SwiftUI, so the
  `RecorderPermissionGate`/`RecorderCapability` seam (`:fit-shared-ui`, android+desktop-only) does
  **not** apply. `RecordView.swift`: **Start** primes **Motion** (Location via
  `requestWhenInUseAuthorization` + CoreMotion via the first pedometer update) behind an in-app
  rationale alert, shown only when `CLLocationManager().authorizationStatus == .notDetermined` (once
  iOS has an answer, Start goes straight through); the HR sensor row shows a **Connect** action
  (instead of a status) while the strap isn't ready, which primes **Bluetooth** behind its own
  rationale — so the majority who own no strap are never asked for it.
- **The Bluetooth opt-in seam.** A new default-no-op `RecordingEngine.connectHeartRate()`
  (`:fit-shared-logic`, additive; the mock/Android engines keep scanning HR in `armSensors`, so
  they ignore it) + a `RecordViewModel.connectHeartRate()` passthrough. `IosRecordingEngine`
  overrides it: the `CBCentralManager` is now a nullable field created **only** on that call, so the
  Bluetooth prompt fires on the explicit Connect tap and `start()` re-scans HR only if a strap was
  already opted into.

**Verified:** `:tenshu-sdk-ios:iosSimulatorArm64Test` green (the interface addition is additive;
engine construction stays side-effect-free); `:fit-android` + `:fit-desktop` + `:fit-shared-logic`
JS compile (the common `connectHeartRate` default no-op breaks no consumer); the **fit-ios
simulator `xcodebuild` BUILD SUCCEEDED** (Swift priming + `connectHeartRate` SKIE bridge + the new
Info.plist keys). On-device prompt sequencing (the OS sheets, the background-location indicator, a
real strap) is R-iOS-4. No threat-model change beyond A34.

### 11.3 R-iOS-3 — durable recorder outbox on iOS

**LANDED 2026-07-18.** §9.13 shipped the outbox for Android/desktop but "*iOS binds none yet (no
fit-ios shell) → legacy throw path*." iOS is exactly where durability matters most: iOS SIGKILLs
the backgrounded Control seconds after Fit foregrounds (§9.16), so a Finish-time `recordWorkout`
can lose its host mid-write.

- **`NsFileOutboxStore`** (`:app-outbox` **`iosMain`**) — the `NSFileManager`-backed `OutboxStore`,
  the iOS twin of the java.io `FileOutboxStore`: one atomic-written file per entry
  (`writeToFile:atomically:` = write-temp-then-rename), same filename sanitisation and `.json`
  suffix, so `remove` finds what `append` wrote and a timestamp-prefixed id keeps store order =
  capture order. ⚠ As-built: a plain **`iosMain`** source set (the `applyDefaultHierarchyTemplate`
  already gives one from the module's `iosArm64`/`iosSimulatorArm64` targets), **not** the "new
  `nsFileMain`" the plan guessed — no build-file change needed.
- **`iosRecordedWorkoutOutbox()`** (`:fit-shared-data` `iosMain`) — resolves the app's
  **Application Support** container (not Caches — iOS never reclaims a pending journal under
  storage pressure) and builds the `RecordedWorkoutOutbox` over the store. `EpcFitClient` now passes
  it to `FitProjectionRepository(outbox = …)`, so `addWorkout` takes the durable-first path
  (journal → drain → `Synced`/`SavedLocally`) instead of the throw path. The already-built recovery
  drains it: `HealthImportBootstrap` on launch (unconditional, §9.15) and the reachability
  `onRecovered` when Control comes back (§9.14) — no extra wiring.

**Verified:** `:app-outbox:iosSimulatorArm64Test` — `NsFileOutboxStoreTest` (append/entries/remove
round-trip, id sanitisation, foreign-file skip, missing-dir → empty; 4/4); `:tenshu-sdk-ios:
iosSimulatorArm64Test` green (`EpcFitClient` now assembles the repository with the outbox bound).
A seam (no SwiftUI / `project.yml` change), so no `xcodebuild` run. The end-to-end
journal-survives-SIGKILL-and-replays flow is device-gated → R-iOS-4. No threat-model change beyond
A34/A35 (app-private plaintext journal until replay — the visit-outbox posture §9.13 already
amended).

### 11.4 R-iOS-4 — on-device recorder pass (device-gated)

The unavoidable hardware pass (same posture as the Android recorder, never merged as "done" from
a sim): real GPS fixes on a walk/run, `CMPedometer` steps, a paired BLE strap's HR, background
capture across an app switch, Finish → `recordWorkout` over EPC with outbox durability, and the
route + HR rendering in the SwiftUI Workout detail. Watch the Control SIGKILL window; confirm a
`SavedLocally` workout replays on the next foreground.

### 11.5 Adjacent / optional (not on the recorder critical path)

- **R-iOS-5 — background HealthKit import refresh (`BGTaskScheduler`). LANDED 2026-07-18.** The Fit
  twin of Drive's BGAppRefresh (route-1 P6a). **Kotlin:** `EpcFitClient` builds a `backgroundRefresh`
  closure (over the repo + importer — the same drain-then-gated-import work `HealthImportBootstrap`
  does per launch) and hands it to `FitSurface`; `FitSurface.runBackgroundRefresh(maxWaitSeconds)`
  runs it (or no-ops "not live" over the mock) and holds the window open. **Swift:**
  `FitBackgroundRefresh` (register at `App.init`, arm on scenePhase → background, re-arm at the
  start of each wake) — the handler attempts `ControlAutoConnect` then awaits the Kotlin refresh
  (⚠ since 2026-11-05: route-1 `FitLocalRuntime.runBackgroundRefresh` when provisioned, else the
  Kotlin no-op; no auto-connect).
  **Plist:** `UIBackgroundModes` gains `fetch`; `BGTaskSchedulerPermittedIdentifiers:
  [app.tenshu.fit.refresh]`. ⚠ **The EPC-tier limit is real and by design:** unlike Drive (route-1,
  the iOS background *syncer*), Fit is an EPC client, so in the background Control is usually not
  hosting → auto-connect fails → the refresh no-ops. It does real work only when Control *is*
  co-resident and hosting; true unattended background wants route-1 (§9.12 P-iOS-3). Verified:
  `EpcFitClientTest` (no-op over mock; drain status over a live client, import gated off in the bare
  test host; 6/6) + **fit-ios simulator `xcodebuild` BUILD SUCCEEDED** (both schemes). The live OS
  wake is device-gated (sim `submit` → `BGTaskSchedulerErrorDomain Code=1`) → R-iOS-4. No
  threat-model change beyond A34.
- **R-iOS-6 — HealthKit write-back. LANDED 2026-07-18.** A *courtesy export* so a workout **recorded
  in Fit** also appears in Apple Health. **`IosHealthKitWorkoutWriter`** (`:fit-shared-data` iosMain)
  builds an `HKWorkout` via `HKWorkoutBuilder` (⚠ K/N exposes neither the deprecated `HKWorkout`
  convenience initializer nor `HKWorkoutConfiguration.workoutActivityType` — the config member is
  `activityType`/`setActivityType:`) and saves it; consent-gated by a HealthKit *share*
  authorization sheet + `NSHealthUpdateUsageDescription` (absent → clean no-op, never a process
  abort — the read-side lesson). `IosRecordingEngine.finish()` fires it best-effort (fire-and-forget,
  so the summary navigation isn't blocked). ⚠ **Loop avoidance:** `HealthKitImporter.readWorkouts`
  now skips workouts whose HealthKit source is Fit's own bundle, so an exported recording is **not**
  re-imported into the realm as a duplicate `AppleHealth` row (it would arrive under a fresh HK UUID,
  dodging content-addressing). Our store of record stays the realm — this is additive. Energy/distance
  totals + route are a later refinement (v1 = activity + duration). Verified:
  `IosHealthKitWorkoutWriterTest` (no-op without the update usage string; 1/1) + fit-ios simulator
  `xcodebuild`. Real HK write is consent + device-gated → R-iOS-4. No threat-model change (a local
  device→Apple Health export; A34 governs the read side).
- **Cross-platform (not iOS-specific, called out for completeness):** the **Nutrition / Cycle**
  Browse categories are still `NotSetUp` stubs on *every* platform; the recorder **accuracy gating**
  follow-up (drop fixes with `acc > N m` + a step noise floor, the GPS-distance fix's note) applies to both
  `AndroidRecordingEngine` and the new `IosRecordingEngine`.

### 11.6 Sequencing & dependencies

R-iOS-1 (done) → R-iOS-2 are the minimum for a *foreground* recording that persists (R-iOS-3
hardens the save; R-iOS-4 proves it). R-iOS-2 is Info.plist + Swift priming; R-iOS-3 is a
shared-module source set + a bind. R-iOS-5/6 and the cross-platform items are independent and can
land in any order after the recorder is real.

### 11.7 The mock is retired from the iOS runtime — honest "not connected" state

**LANDED 2026-07-18.** iOS Fit has **no store of its own** — every read goes over EPC to Control
(§10). `FitSurface` defaulted `installed` to `MockFitRepository`, and `FitLiveClientSession.teardown()`
restored it, so a Fit that hadn't connected (cold start, Control not hosting, a **failed handshake**
— the on-device `401`, or after teardown) rendered **fabricated** workouts/steps the user would
believe. That is the `ImportStatus.Sample` anti-pattern (§9.5b: "convincing invented numbers are
worse than empty"), and — since the intended "brief mock flash until connect" becomes *permanent*
whenever the connection can't complete — it is what a not-live Fit actually showed.

**Why not just delete the mock?** It's the fixture for the whole test + preview surface —
`MockFitRepositoryTest`, `RecordingEngineTest`, `FitViewModelsTest`, the five `:fit-shared-ui`
desktop screen tests, `FitSurfaceTest`/`EpcFitClientTest`, and `mockDataModule()` for previews /
a Control-less demo. On Android/desktop the mock is already *not* a runtime path (they bind
`realDataModule()`). So the mock is legitimate where it lives; it was only wrong as the iOS
*runtime* default.

**The change (iOS-only):**
- **`NotConnectedFitRepository`** (`:tenshu-sdk-ios` iosMain) — every read **empty**, `onboarded =
  true` (route to the shell, not onboarding), and **`controlReachable = false`**. `addWorkout`
  throws (nothing to save when disconnected).
- **`FitSurface`** now defaults to it and `installNotConnected()` is the teardown target
  (`FitLiveClientSession.teardown()` → `installNotConnected`, was `installMock`). `installMock()`
  stays — **tests/previews only**, no longer a runtime surface. `installRealClient` is unchanged.
- **`ControlNotConnectedBanner`** (SwiftUI, `MainShell`) — the iOS twin of the Compose
  `ControlUnreachableBanner` (§9.14): shown above the tabs when `!FitLiveClientSession.isActive`,
  message-only (recovery is automatic — `ControlAutoConnect` retries every foreground; ⚠ since
  2026-11-05 the route-1 gate's foreground re-probe is the only retry). The shell
  remounts on `.liveSessionChanged`, so it clears the instant the live client installs.

Net: a not-live Fit shows **empty screens + an honest banner**, never fake data. Verified:
`EpcFitClientTest.default_and_teardown_surface_is_not_connected_not_mock` (empty +
`controlReachable=false`) + umbrella `iosSimulatorArm64Test` + fit-ios simulator `xcodebuild`. No
threat-model change.

### 11.8 Decision — EPC is the third-party pattern; first-party Fit belongs on route-1 (2026-07-18)

**The permanent "Can't reach Tenshu Control" banner is not a bug — it is the cost of running a
first-party app on the *third-party* connection protocol.** This settled a design direction, not
just a fix.

**How it surfaced.** A user reported the banner as permanent regardless of Control's lock state.
Diagnosed on-device (iPhone 17e, launched via `devicectl … --console`): the very first
`ControlAutoConnect` tick logged
`[fit-autoconnect] presence token only (len 32) — Control is not hosting`. Control *is* co-resident
and healthy — it wrote a 32-byte C3 presence token into the shared Keychain — but Fit's auto-connect
acts only on a `tenshu://epc/pair#…` **invite**, and that invite is published **only while Control's
"Connect an app" screen is on-screen** (`EpcAppPairingView.onAppear{ model.start() }` →
`ControlSurface.publishEpcInvite`; `onDisappear{ model.stop() }` reverts to the presence token). So a
Control that is merely installed/unlocked never satisfies Fit — the symptom matches the mechanism
exactly. (Contrast the R-iOS-4 on-device failure at §11-R-iOS-4, which was a *different* EPC failure:
a `401` from stale realm state. Both are EPC-transport failure modes Fit is exposed to precisely
because it depends on a live channel.)

**Why the pattern is wrong for Fit.** EPC (`ios-proxy-architecture.md` §2) pairs *one untrusted app*
with a proxy, and its whole ceremony — QR/NFC + **SAS** human-compare ("no third party trusted"),
**App Attest**, **Rei** permission evaluation — exists to safely admit an app you do *not* trust.
Fit is *our own* app: same Apple Team ID, same signing, co-resident. Pointing the untrusted-app
protocol at a trusted app is backwards, and the babysitting requirement (Control must actively host)
is the direct symptom.

**Why we are *not* building a process-lifetime EPC host.** The obvious patch — have Control republish
the invite for its whole process lifetime instead of only while `EpcAppPairingView` is visible — was
considered and **rejected as polishing the wrong pattern**:
- It only accelerates the **shared-Keychain** invite handoff, which is **first-party-only by
  construction** (a third-party app signed under its own Team ID can never read `app.tenshu.shared`
  — arch §1). So it buys the third-party story *nothing*.
- It does **not** fix the suspended-Control case: iOS suspends Control in the background (arch §3
  "cannot hold a persistent local socket server"), so the relay rendezvous lapses and Fit still
  can't reach it. Process-lifetime only helps "Control running but you're not on the Connect screen."
- The genuine third-party need is a **durable per-app reconnection credential + relay wake**
  (push / `BGTaskScheduler`; session id = `HKDF(pairingSecret)`, arch §10.1), **not** a persistent
  broadcast invite (one long-lived pairing secret gated only by SAS-at-pair-time is a weaker posture
  the Keychain gate was hiding).

**The decision.** The correct fix for Fit is the same one Drive already took: **route-1**, an
in-process proxy runtime inside Fit's own process over the shared App-Group replica (a
`FitLocalRuntime` mirroring `DriveLocalRuntime`), gated on provisioned key material, needing **no
live Control channel**. Since Control's proxy already hosts `:health-service` on iOS, the in-process
graph just installs the Fit repos instead of Drive's — same recipe as Drive's P1a
([`ios-drive-parity.md`](ios-drive-parity.md) §6, and the generalisation in its §20). Once that
lands, **EPC narrows back to purely the third-party story**, where its ceremony is load-bearing.

**Gating & interim.** Route-1's clean form uses the shared App-Group replica → a **paid Apple
account** ($99; App Groups aren't provisionable on a free personal team — see
[[project_ios_appgroup_fallback_free_signing]]). That gate is largely moot for the shipping product
(App Store distribution needs a paid account regardless). Until route-1-for-Fit lands, EPC-over-relay
stays the free-tier dev stopgap; the only cheap improvement worth making now is **honest banner copy**
— "open Control's *Connect an app* screen", not the misleading "open Control to connect". ⚠ **Scope:**
Timeline and Maps on iOS are in the *same* boat (all three moved onto Control's proxy, so on
iOS they share Fit's EPC/route-2 dependency). Build the generic first-party local-runtime **once**
(Drive's is the template) and light up Fit/Timeline/Maps on it — not three bespoke ports.

**Record gated on connection (follow-up landed 2026-07-18).** The Record tab built a
`MockRecordingEngine` when not-live — its live numbers ticked (fake) and Finish then failed against
`NotConnectedFitRepository`. `RecordView.swift` now gates the pre-start flow on
`FitLiveClientSession.isActive`: not connected → a **"Not connected — open Tenshu Control to
connect"** placeholder instead of the activity picker + Start, so no fake recording can begin. A
recording already under way is untouched (the live engine captures locally and the R-iOS-3 outbox
journals the save even if Control drops mid-session). SwiftUI-only; fit-ios simulator `xcodebuild`
green.

## 12. Browse — closing the category gap

**Investigation 2026-07-19; B1 shipped 2026-07-19.** The Browse tab showed only **Activity** as an
active card on a real device, and the category *detail* screen was Steps-only on every platform.
This section separates the two distinct causes and phases the fixes.

### 12.1 What was wrong (two independent layers)

**Layer 1 — the cards (teasers) were mostly *correct*, just unpopulated.** `FitProjection.categories`
(`:fit-shared-data`) derives all seven teasers, and Body/Vitals/Sleep/Medical **do** have real
backing entities (§9.1–9.3): `HealthSample` (Body weight / Vitals resting-HR), `SleepSession`,
`MedicalRecord`. They dim to `NoEntries`/`NotSetUp` only because no data has landed yet:

- The one always-on write path on a bare device is the recorder-outbox drain in
  `HealthImportBootstrap` (→ `Workout`s → Activity). That is why **Activity is the only lit card**.
- The reference fixture that would fill Body/Vitals/Sleep/Medical is deliberately gated off
  (`ReferenceHealthImporter.autoRefreshReady() = false` — §9.15: auto-writing fabricated data into
  Control's *synced* realm would push fakes to every device). Android's Health Connect auto-import is
  gated behind a permission grant; Medical is never a Health Connect type at all.
- **Nutrition** (`NoEntries`) and **Cycle** (`NotSetUp`) are **hardcoded** in `FitProjection` (no
  store exists for either).

So the cards are honest, not broken. The teaser layer needs no fix beyond the import/empty-state UX
(Phase 2) and the Nutrition/Cycle decision (Phase 3).

**Layer 2 — the category *detail* screen was Steps-only (the real structural gap).** It was not
per-category at all: `Destination.CategoryDetail` was a bare `data object` (no `CategoryId`),
`CategoryDetailViewModel` took no id and hardcoded `it.steps`, and the title was hardcoded "Steps".
Tapping Body/Vitals/Sleep (which have real teasers + data) therefore landed on a screen **titled
"Steps" showing step counts**. Only **Medical** routed to a dedicated screen. This was identical on
Android, desktop, and iOS — all shared `:fit-shared-logic` / `:fit-shared-ui`.

### 12.2 iOS is *not* "mocked" — it is honest-empty until EPC connects

The iOS Browse tab is genuinely native SwiftUI (`fit-ios/Sources/BrowseView.swift`) bound to the same
shared `BrowseViewModel`. The runtime default is `NotConnectedFitRepository` (all
`flowOf(emptyList())`) — the mock was retired from the iOS runtime (§11.7). An unconnected Fit shows
blank cards + the "Can't reach Tenshu Control" banner, **not** fake data. Real data appears only when
an EPC live client installs, which §11.8 diagnosed as fragile-by-design. So iOS Browse inherited both
the Layer-2 detail gap (shared) and the EPC reachability problem — the latter is what §11.8's route-1
`FitLocalRuntime` addresses (still unbuilt).

### 12.3 Phases

- **Phase B1 — generic category detail (shared logic; fixes Android + iOS + desktop at once).**
  SHIPPED (§12.4).
- **Phase B2 — time-series `HealthSample` store.** SHIPPED (§12.5). **B2b COMPLETE** — importer
  history (§12.6), trend-line viz + empty-state affordance (§12.7).
- **Phase B3 — Nutrition / Cycle (product call: build). COMPLETE.** Decision 2026-07-19: **build both**
  (both importable from Health Connect / HealthKit). **B3a Nutrition SHIPPED** (§12.8); **B3b Cycle
  SHIPPED** (§12.9) — a **data-driven card** (shown only when cycle data exists, *not* gated on a
  profile sex field; inclusive, matching Apple / Health Connect, which don't sex-gate cycle tracking).
  With B3 done, **all seven Browse categories are backed** — the §12 Browse arc is complete bar the iOS
  route-1 runtime.
- **Phase iOS (`FitLocalRuntime`, route-1)** — the settled §11.8 direction, tracked there, not a
  Browse-specific phase.

### 12.4 B1 as-built (shipped 2026-07-19)

Per-category detail, shared-logic-only (no protocol/wire/store change — the entities already exist):

- **`Destination.CategoryDetail(val categoryId: CategoryId)`** (was a bare `data object`); `CategoryId`
  gains `@Serializable` (the module already applies the serialization plugin) to ride the nav arg.
  `App.kt` pushes `CategoryDetail(id)` and builds the VM with `parametersOf(destination.categoryId)` +
  a `category_<id>` koin key — mirroring `WorkoutDetail`'s `workoutId` threading exactly. The Koin
  binding becomes a parameterised `viewModel { (categoryId: CategoryId) -> CategoryDetailViewModel(…) }`.
- **`CategoryDetailViewModel(categoryId, repository)`** now projects **per metric**: Activity → the
  steps series (the original exemplar, unchanged), Sleep → the per-night duration series, Body → the
  latest weight, Vitals → the latest resting HR. Nutrition/Cycle (disabled cards) + Medical (own
  screen) never reach here → `Empty`.
- **Data-shape reality (§9.3):** `HealthSample` is *latest-per-metric* (one row, keyed by `metric`) —
  Body/Vitals have **no history to chart**. So the state carries a `CategoryDetailMode`
  (`Series`/`Latest`/`Empty`) + a `CategoryMetric`: Series (Activity/Sleep) renders the D/W/M/Y chart +
  entries; **Latest (Body/Vitals) renders the single reading + provenance, no chart** — honestly, until
  B2's time-series store. Value formatting follows the metric (steps group as thousands; sleep minutes
  render as a duration).
- **UI:** `CategoryDetailScreen` branches on `mode` (Series / Latest / Empty), titles per metric
  (added `fit_category_{sleep,weight,resting_hr}_title` + `_no_data` strings; genericised the
  steps-specific data-sources subtitle). The iOS `CategoryDetailView` + `BrowseView` + `FitRoute`
  thread the `CategoryId` through and render the same three modes; `FitSurface.categoryViewModel`
  gains the `categoryId` param.

Verified: `:fit-shared-logic:desktopTest` (new `categoryDetail_{activityWindowsByRange,
sleepShowsDurationSeries,bodyShowsLatestWeight,vitalsShowsLatestRestingHr}` + the existing suite),
`:fit-shared-ui:desktopTest` (screen tests), `:fit-android:assembleDebug`, `:fit-desktop`
`compileKotlinJvm`, iOS umbrella `iosSimulatorArm64` compile + **fit-ios simulator `xcodebuild` BUILD
SUCCEEDED**. On-device Android/iOS pass pending (visual). No threat-model change (A34; no new capture,
no relay/wire/store change).

### 12.5 B2 as-built (shipped 2026-07-19)

`HealthSample` re-keyed from latest-per-metric to a **time-series**, so Body/Vitals detail retains a
metric's history. Touches `:health-service` (schema) + the projector + the mock, with a storage-format
bump (HR#9):

- **Re-key (`:health-service`).** `HealthSample` gains a synthesized **`sampleId` key field** =
  `"$metric::$timeMs"` (`HealthSample.create` computes it; `HealthSampleDataIdProvider.fieldRef` →
  `SAMPLE_ID`). Distinct-time readings of a metric now accrue as separate rows; a same-instant
  re-import still folds (idempotent). The relation-maintainer keys off the **stored `sampleId` field**
  (not a re-derived string) so the DataId provider and the maintainer stay in lockstep by construction.
  Chosen over a multi-`fieldRef` composite key because `stringDataId` takes a single ref — the synthesized
  key reuses the `workoutId`/`recordId` single-field-key pattern. **`TENSHU_STORAGE_FORMAT_VERSION` 3 → 4** (rebased over the Timeline
  MovementTrace v3 bump on master)
  (positional-composite field add + content-address change; the Phase-0 reset wipes pre-v4 sample rows —
  relays redeploy with the bump per the 426 gate).
- **Derived latest.** "Latest per metric" is no longer a store invariant — `FitProjection.latestPerMetric`
  reduces the series to the newest per `(metric, resting)` for the Browse teaser + the detail's
  current-value header; `categories()` picks newest-by-time (robust to either a series or a reduced list).
  `FitProjectionRepository.samplesLatest()` derives it; a new **`sampleHistory(metric)`** reads the full
  series newest-first. ⚠ **The read projection must include the new key field** — `HEALTH_SAMPLE_FIELDS`
  now lists `SAMPLE_ID`, or the entity can't be keyed and the read silently drops every sample (caught by
  `HealthImportTest`; every sibling relation already projects its key field).
- **Detail history (VM + UI).** `CategoryDetailViewModel` Body/Vitals read `sampleHistory` → the `Latest`
  state gains a `history: List<SampleReading>` (fractional-value readings, newest first). Compose
  `CategoryDetailScreen` + iOS `CategoryDetailView` render a **History list** (date · value · source) under
  the current-value header when there's more than one reading. The `ReferenceHealthImporter` (dev/desktop
  fixture) + the mock now emit a weight + resting-HR series so the list is populated out of the box.

⚠ **B2b (§12.6):** the live importers emit real history now; the trend-line viz + the empty-state
"import a source" affordance are the B2b remainder.

Verified: `:health-service-proxy:jvmTest` (rewritten fold tests — `distinctTimesOfSameMetricRetainAsSeparateRows`
+ `reRecordingSameMetricAndInstantFoldsToOne`), `:fit-shared-data:desktopTest` (new
`latestPerMetricPicksNewestByTime` + `referenceImportRetainsAWeightTimeSeries` + existing suite),
`:fit-shared-logic:desktopTest` (VM history), `:fit-shared-ui:desktopTest`, `:core-common:desktopTest`
(format gate), `:fit-android:assembleDebug`, `:fit-desktop` compile, iOS umbrella `iosSimulatorArm64` +
**fit-ios simulator `xcodebuild` BUILD SUCCEEDED**. On-device Android/iOS pass pending. No threat-model
change (A34; no new relay data-shape — a re-keyed feature-service entity on the existing sealed plane).

### 12.6 B2b as-built — live importers emit history (shipped 2026-07-19)

The B2 store retains a time-series, but every live importer still wrote *one latest reading* per metric,
so on a real device the Body/Vitals history had a single point. B2b makes the importers emit the whole
(bounded) window for the two charted metrics — Weight + resting HR — so real history reaches the store:

- **Google Takeout (`DailyMetricsCsv`, commonMain — fully tested).** The daily-metrics CSV already
  parsed every day's `Average weight`; it discarded all but the newest. Now it emits **one `Weight`
  reading per day that carried one** (a day with no weight carries none). This is the load-bearing one:
  Takeout is the *only* route to a user's real Google Fit history (Google Fit never feeds Health Connect,
  §9.5b). Test: `theWeightColumnBecomesATimeSeriesNotJustTheLatestReading`.
- **Health Connect (`HealthConnectImporter`, androidMain — compile-verified).** `readSamples` switched
  Weight + `RestingHeartRateRecord` from `readLatest` (`pageSize=1`) to the paged `readAll` (bounded by
  the existing `MAX_RECORDS_PER_TYPE` ceiling), one `ImportedSample` per record. SpO₂ / respiratory rate
  stay latest-only (not charted).
- **HealthKit (`HealthKitImporter`, iosMain — compile-verified).** Same shape: Weight +
  `RestingHeartRate` read via the bounded `readAllSamples` (oldest-first, `MAX_RECORDS_PER_TYPE` limit)
  instead of `readLatest`; SpO₂ / respiratory rate stay latest-only.

Content-addressing does the rest: distinct-time readings retain as distinct rows, a re-import of the same
reading folds, and the reference importer + mock already ship a series (§12.5), so desktop/dev were already
exercising the list. On-device (Android HC grant / iOS HealthKit) pass pending — the platform reads are
device-gated, unchanged posture from §9.5. No threat-model change (A34; count-level `fitLog` only, never
values).

### 12.7 B2b as-built — trend-line viz + empty-state affordance (shipped 2026-07-19)

The last of B2b: the history charts, and an empty Browse offers a way in.

- **Trend line.** A **min–max-scaled** polyline over the Body/Vitals series — `ChartMath.minMaxPolyline`
  (pure, unit-tested) maps each reading onto its own `[lo, hi]` range padded by headroom (**not**
  zero-anchored — a 73–75 kg weight trend must fill the plot, where the zero-based `TrendBars` would draw
  near-identical full columns). The VM's `Latest` state gains `trend: List<NormalizedPoint>` (oldest→newest,
  computed only with ≥2 readings). Rendered above the history list by a pure `SampleTrend` composable
  (Compose, `HrChart`'s area+line+dots pattern) + its iOS twin (`SampleTrend.swift`, a SwiftUI `Path` over
  the same bridged points). ⚠ SwiftUI's `ViewBuilder` forbids a local `func` in the closure — the point
  mapping is a `private` method.
- **Empty-state "connect a source".** A prompt card at the top of Browse — shown while any **store-backed**
  category (anything but Nutrition/Cycle, which have no store) is still empty — routes to Profile → Data
  sources (where import lives): Compose via the existing `selectTop(Profile)`; iOS by adding a
  `TabView(selection:)` tag per tab and jumping to Profile (tag 4). No cross-tab plumbing existed before on
  iOS; this adds it minimally.

Verified: `:fit-shared-logic:desktopTest` (new `minMaxPolyline{ScalesToTheValueRange, HandlesFlatAndTooFew}`
+ VM trend), `:fit-shared-ui:desktopTest` (BrowseScreen), `:fit-shared-data:desktopTest`, `:fit-android:assembleDebug`,
iOS umbrella `iosSimulatorArm64` + XCFramework + **fit-ios simulator `xcodebuild` BUILD SUCCEEDED**. The
chart + prompt are visual → on-device/simulator visual pass pending. No threat-model change (A34).

### 12.8 B3a as-built — Nutrition (shipped 2026-07-19)

**Decision (product call):** build Nutrition + Cycle rather than hide them — both are importable from
Health Connect / HealthKit, so they earn a card. B3a is Nutrition; Cycle (B3b) follows.

A new `:health-service` entity mirroring `DailyActivity` field-for-field (§9.1 pattern), end to end:

- **`DailyNutrition`** (`:health-service`) — a per-day aggregate: `date` ISO key + `energyKj` +
  `hydrationMl`, `Syncable(Eager)`, content-addressed by `date` (`DailyNutritionDataIdProvider`), so a
  re-import of a day folds. Energy stored as **kJ** (like `DailyActivity.activeEnergyKj`; the UI renders
  kcal via the existing `FitFormat.kjToKcal`). Full 5-module wiring: `RecordDailyNutrition` op + impl +
  factory registration, `HealthService.dailyNutritions` relation + `DailyNutritionRelationMaintainer`,
  common `composite`/`trait`/`serviceDataRelation` + Dto + DataIdProviderImpl, client `type`/`operation`
  + Ro + the `HealthServiceRo.dailyNutritions` accessor, and a **sixth** `RecordImportedHealth` batch
  param (Ro + impl `saveAll`). **`TENSHU_STORAGE_FORMAT_VERSION` 4 → 5** (HR#9: new type + relation).
- **Projector + repo** — `FitProjection.DailyNutritionInput` + `nutritionDays()`; `categories()` gains a
  `nutrition` param and derives the Nutrition teaser (`CategoryTeaser.Energy(kj)` = today's energy, else
  `NoEntries`); `FitProjectionRepository.nutrition()` reads the relation (⚠ `DAILY_NUTRITION_FIELDS`
  includes the `date` key field — the §12 B2 gotcha).
- **Import** — `ReferenceHealthImporter` seeds a nutrition week; `HealthConnectImporter` reads
  `NutritionRecord` (energy) + `HydrationRecord` per day (+ the two `READ_NUTRITION`/`READ_HYDRATION`
  perms + manifest entries); `HealthKitImporter` sums `dietaryEnergyConsumed` + `dietaryWater` per day.
- **UI** — Nutrition detail is a Series (like Steps): a daily-energy D/W/M/Y trend, kcal-formatted
  (`CategoryMetric.Energy`); the Browse teaser shows "N kcal today". Compose + iOS both render it; the
  Nutrition card is now live (dims when empty, like the other universal categories).

Verified: `:health-service-proxy:jvmTest` (`RecordDailyNutritionUnitTest` round-trip + same-day fold),
`:fit-shared-data:desktopTest` (`nutritionTeaserReflectsTodayEnergy` + `referenceImportPopulatesNutrition…`),
`:fit-shared-logic:desktopTest` (`categoryDetail_nutritionShowsEnergySeries` + the updated mock test),
`:fit-shared-ui:desktopTest`, `:fit-android:assembleDebug` (HC nutrition + manifest), iOS umbrella
`iosSimulatorArm64` + XCFramework + **fit-ios simulator `xcodebuild` BUILD SUCCEEDED**. On-device
(Android HC / iOS HealthKit) import pass pending. No threat-model change (A34; nutrition + hydration are
GDPR Art. 9 like every health field, on the existing sealed plane — no new relay data-shape).

### 12.9 B3b as-built — Cycle (shipped 2026-07-19)

The last Browse category. A new `:health-service` entity `MenstruationDay` (mirroring `DailyNutrition`),
plus the two decisions from the product call:

- **`MenstruationDay`** (`:health-service`) — one logged bleeding day: `date` ISO key + `flow` level
  string (`Light`/`Medium`/`Heavy`/`Unspecified`) + `periodStart` boolean (the cycle-day anchor).
  `Syncable(Eager)`, content-addressed by `date`. Full 5-module wiring exactly as B3a's `DailyNutrition`
  + a **seventh** `RecordImportedHealth` batch param. **`TENSHU_STORAGE_FORMAT_VERSION` 5 → 6.**
- **Data-driven visibility (not sex-gated).** `FitProjection.categories()` now takes a `cycle` param and
  **omits the Cycle card entirely when there is no cycle data** (`.filterNot { it.id == Cycle && cycle.isEmpty() }`)
  — never a `FitProfile.sex` gate. Anyone who tracks a cycle sees it; anyone who doesn't never sees an
  irrelevant empty card. Inclusive, and matches how Apple Health / Health Connect surface cycle tracking.
  So the Browse grid is 6 cards on a fresh store, 7 once cycle data exists.
- **Teaser + detail.** Teaser = `CategoryTeaser.CycleDay(n)` ("Cycle day N", counted from the most recent
  `periodStart`, or the most recent logged day if none). The detail is a new `CategoryDetailMode.Cycle`:
  a "Cycle day N" header + a **log** of recent bleeding days (date · flow · a period-start marker) — no
  numeric chart, since flow is categorical. Compose + iOS both render it.
- **Import.** `HealthConnectImporter` reads `MenstruationFlowRecord` (per-day flow) + `MenstruationPeriodRecord`
  (period-start days), + the `READ_MENSTRUATION` perm/manifest entry; `HealthKitImporter` reads
  `menstrualFlow` category samples (flow from the value, `periodStart` from the `HKMetadataKeyMenstrualCycleStart`
  metadata); the reference importer seeds a period ~2 weeks ago.

⚠ `combine` maxes at five typed flows, so the day-aggregate trio (aggregates + nutrition + cycle) is folded
into a `Triple` first, both in the repo and the mock.

Verified: `:health-service-proxy:jvmTest` (`RecordMenstruationDayUnitTest` round-trip + same-day fold),
`:fit-shared-data:desktopTest` (`cycleCardAppearsOnlyWithData` + `referenceImportPopulatesCycle…`),
`:fit-shared-logic:desktopTest` (`categoryDetail_cycleShowsDayAndLog` + the data-driven mock test),
`:fit-shared-ui:desktopTest`, `:fit-shared-data:iosSimulatorArm64` (HealthKit), `:fit-android:assembleDebug`
(HC + manifest), iOS umbrella + XCFramework + **fit-ios simulator `xcodebuild` BUILD SUCCEEDED**. On-device
import pending. No threat-model change (A34; menstruation is GDPR Art. 9 on the existing sealed plane — no
new relay data-shape; the entity KDoc + the data-driven, non-sex-gated card capture the sensitivity posture).

## 13. Workout completion — notification controls, finish→review flow, trim suggestion, honest stats

**Designed 2026-07-25 (with maintainer); ALL SLICES BUILT SAME-DAY — RC-0, RC-1+RC-2,
RC-3, RC-4+RC-5 — and the iOS tail (in-flight journal + SwiftUI review
surface, §13.5/§13.6) landed same-day too. The arc is platform-complete in code; the on-device
pass (Android + iOS) is the remaining gate.** The §9.8
recorder defined the *capture* side and stopped at "on Finish, persist the streams". Everything
after the user decides to stop was never designed, and the first on-device use surfaced it: the
FGS notification was not even tappable. This section is the design for the whole completion arc.

### 13.0 The gap, concretely (audit 2026-07-25)

- ⚠⚠ **Notification tap did nothing** (`RecordingService`): the content `PendingIntent` lacked
  `FLAG_ACTIVITY_NEW_TASK` — an activity PendingIntent fired from a notification launches with no
  activity context, and without the flag the launch is *silently rejected*. Compounded by a
  `requestCode 0` collision with `FitImportForegroundService`'s otherwise-filterEqual intent
  (PendingIntent equality ignores flags → one shared record whose flags depend on creation order,
  which made the bug look intermittent). **Fixed — RC-0, this PR.**
- No notification actions (Stop/Pause), no live content updates, no `CATEGORY_WORKOUT`; built once
  in `onStartCommand` and never touched again.
- Stop in `RecordLiveScreen` → `engine.finish()` → **instant save**. No review, no editable title
  (`title`/`device` persist as `""`), no confirmation, straight to detail on success.
- **No trimming**: every raw fix from the Start tap onward enters the stream — the standing-around
  tail at both ends and the low-accuracy first fixes included; `onLocation` never checks
  `loc.accuracy`, so a bad first fix also inflates distance.
- **Pause is cosmetic on the real engine**: `pause()` sets a flag, but `advance()` recomputes
  `elapsedSec` from `SystemClock.elapsedRealtime() - startElapsedMs` (paused time comes back on
  resume) and `onLocation` doesn't check the flag (route + distance accrue while "paused"; only HR
  respects it). The mock engine genuinely stops — the tests test the wrong semantics.
- `discard()` exists on the engine + VM but **no UI calls it**; no back-handler on `RecordLive`.
- Samples buffer **in RAM only** until finish — process death mid-workout loses the entire
  recording (the §9.13 outbox only covers the post-finish save). And `START_STICKY` means an
  OS-restarted service re-shows "Recording…" over an `Idle` engine until the app is opened.

### 13.1 RC-1 — the notification as a control surface

The notification is the recorder's remote control, not a static badge.

- **Tap → the live screen, not just the app.** The content intent carries an
  `EXTRA_OPEN_RECORDING` extra; `MainActivity` routes it (new-intent + launch) to
  `Destination.RecordLive` when the engine is `Active`. The shared `RECORD_VM_KEY` ViewModel means
  the session state is already there — this is navigation only.
- **Actions**: **Pause/Resume** and **Stop** as `PendingIntent.getService` actions
  (`ACTION_PAUSE`/`ACTION_RESUME`/`ACTION_STOP` handled in `onStartCommand`; the engine is a DI
  singleton in-process, so the service just calls it). **Stop routes through review** (§13.2): it
  calls `engine.stop()` (capture teardown → draft) and fires the full-screen content intent — it
  never commits a save from the shade.
- **Live content**: `setUsesChronometer(true)` + `setWhen(startWallMs)` gives a ticking elapsed
  time for free; distance/HR line refreshed via a throttled `notify()` (≥ 30 s or on km
  boundary — `IMPORTANCE_LOW` + `setOnlyAlertOnce(true)` keep it silent). Add
  `setCategory(CATEGORY_WORKOUT)` + `FOREGROUND_SERVICE_IMMEDIATE`.
- **Orphan fix**: return `START_NOT_STICKY`; additionally `onStartCommand` with an `Idle` engine
  (OS restart race) → `stopSelf()`.

**As-built (2026-07-25).** All of the above, with three deltas. (1) The tap deep-link rides a
**latched** `RecordSurfaceRequests` StateFlow in `:fit-shared-logic` (MainActivity raises it from
`onCreate`/`onNewIntent` on `EXTRA_OPEN_RECORDING`; the shared shell consumes it and routes
Active→live / Reviewing→review) — latched, not an event flow, so a cold-start tap survives until
the shell composes. (2) ⚠ The **Stop action cannot open the review screen directly** — Android's
notification-trampoline restriction forbids `startActivity` from an action handler — so shade-Stop
parks the draft (`engine.stop()`, which tears the FGS down) and posts a separate **auto-cancel
"Workout recorded — tap to review and save" notification** (id 43); the tap then routes via (1).
(3) The orphan fix needs no explicit Idle probe: `START_NOT_STICKY` alone prevents the restart, and
the service's state collector only posts updates while Active. Live line = `setWhen(now −
elapsed)` + `setUsesChronometer` (paused → static "Recording paused" title, no chronometer),
distance/HR text re-notified on 0.1 km buckets or 30 s, `setOnlyAlertOnce` + `IMPORTANCE_LOW` keep
it silent. Service reaches the engine via Koin (`by inject()` — same-process DI singleton).
Notification behaviour is framework code → **on-device pending**.

### 13.2 RC-2 — finish → review → save (the engine API split)

`finish()` conflates "stop capturing" with "commit the workout". Split it:

- `RecordingEngine.stop(): RecordingDraft` — tears capture down (sensors, FGS), computes the raw
  summary, holds the session as an immutable **draft** (route, HR, per-second timeline, raw
  `Workout` fields). State: `Active → Reviewing(draft)`.
- `RecordingEngine.commit(edits: DraftEdits): FinishedRecording` — applies edits (title, activity
  type, trim window §13.3), rebuilds the `Workout` + `RecordedTrack` from the (possibly trimmed)
  window and runs today's `addWorkout` path unchanged (outbox, `Synced`/`SavedLocally` outcomes).
- `discard()` finally gets its UI home.

New **`RecordSummaryScreen`** (pushed on Stop, replaces the straight-to-detail jump): route sketch
(the existing detail map/polyline composable over the draft), stats grid (duration, distance, pace,
avg/max HR, steps, energy), **editable title** (prefilled "{Activity} — {weekday}") + activity-type
correction, the trim-suggestion card (§13.3), then **Save** / **Discard** (discard confirms). Save
failure keeps the draft (today's retry dialog semantics). A back-gesture on `RecordLive` no longer
falls off the screen with a live session: it minimizes to the notification (the FGS is the session
owner); `Reviewing` survives navigation because the draft lives on the engine singleton.

**As-built (2026-07-25).** The split landed exactly as `stop(): RecordingDraft?` /
`suspend commit(edits: DraftEdits): FinishedRecording?` on `RecordingEngine`, with five deltas:

- **`finish()` stays on the interface as a default method** (`stop(); commit()`) — the one-shot
  seam the iOS `RecordView` keeps calling through `RecordViewModel.finish()`, so **zero Swift
  change** (fit-ios sim xcodebuild green); the §13.6 "iOS adopts after RC-2" now means the *UI*
  slice only — **all three engines** (mock / Android / iOS) implement the split at the engine
  level, the iOS HealthKit courtesy export moving into `commit`. `RecordingState` gains
  `Reviewing(draft)`; `RecordingDraft(workout, track)` derives `routeSketch` (the
  `projectRoute` fold over raw `GpsFix`es, so the review sketch needs no store round-trip);
  `Workout.applying(DraftEdits)` single-sources the edit merge.
- ⚠ **`start()` is now gated on `Idle`** in all three engines (was `!is Active`) — starting over a
  Reviewing draft would have silently clobbered the unsaved session.
- **Navigation is shell-level, engine-state-driven** (one authority for in-app Stop *and* the
  shade's): `MainShell` collects `engine.state()`; a `Reviewing` flip replaces `RecordLive` with
  (or pushes) `RecordSummary`. Back-gesture off the summary parks the draft; the pre-start screen's
  big button then reads **Review workout** (Active session in the background reads **Return to
  workout**) instead of a clobbering Start.
- **Title prefill is a placeholder, not a value**: the field is empty with the localized activity
  name as placeholder; blank commits `title = ""` → the existing fallback naming. (The designed
  "{Activity} — {weekday}" prefill would have hard-coded a locale join in the Compose-free VM.)
- **Failed-commit semantics**: the session is retained as `Reviewing` (capture already torn down)
  rather than the old zombie `Active` (sensors dead, tick cancelled) — the save-outcome dialogs
  (§9.13) moved from the live screen to the summary screen. `FitViewModelsTest` updated + new
  stop/edit-commit/discard coverage in both test suites.

Green: `:fit-shared-logic:desktopTest` (engine + VM incl. new stop/commit/edits/discard tests),
`:fit-shared-ui:desktopTest`, `:fit-android:assembleDebug`, `:fit-shared-data`
iosSimulatorArm64, fit-ios sim `xcodebuild`. On-device pending (notification actions, deep-link,
review round-trip).

### 13.3 RC-3 — the trim suggestion (start/end idle removal)

The ask: "remove the not-activity time at both ends" — fumbling with the phone before moving,
standing at the crosswalk after. Same shape as Timeline's dwell-vs-move problem, but deliberately
**not** a `:timeline-*` dependency (cross-app module coupling for ~80 lines of pure math): a small
pure **`IdleTrimDetector`** in `:fit-shared-logic` commonMain — walk the fix list from each end,
find the first/last sustained movement (rolling window leaves a ~30 m radius; span counts only if
the trimmed idle ≥ 45 s so we never nag over 10 s), emit `TrimSuggestion(startSec, endSec)` or
null. Fully unit-testable with synthetic tracks.

- **Suggestion, never automatic.** The card reads "Remove 2:14 of idle time at the start?" with
  one-tap Apply per end (and Undo); stats + the route sketch recompute live from the trimmed
  window. Auto-trimming silently is how competitors eat real warm-up data — the user decides.
- **Destructive-at-save v1**: the streams blob is written once at commit, so v1 simply commits the
  trimmed window (untrimmed draft discarded after save). Non-destructive trim offsets on the stored
  entity would be a `Workout` schema change (positional composites ⇒ format bump, HR #9) for
  little gain — deferred until an "edit after save" story exists.
- Trim recomputation = the RC-4 finalize pass re-run over the sub-window (one code path).

**As-built (2026-07-25).** `IdleTrim.kt` (`:fit-shared-logic` commonMain, pure) — as designed
with these deltas:

- **Detection**: rolling-centroid fold (30 m radius; a lone out-of-radius spike with an in-radius
  successor is jitter, skipped unfolded; two consecutive out = sustained departure). ⚠ The
  boundary **anchors at a 10 m tight radius**, not the 30 m detection radius — anchoring at the
  crossing would eat the first radius-worth of *real* movement (~20 s of walking); the tight
  anchor keeps the dwell→movement ramp. Guards: idle ≥ 45 s per end, ≥ 3 fixes, never the whole
  recording (all-idle → null), suggested window keeps ≥ 30 s. A steady walk from t=0 self-filters:
  the lagging mean-centroid puts the tight boundary at ≈ 20 m / speed seconds — under the 45 s
  floor for anything faster than ~0.4 m/s.
- **One recompute, two consumers**: `RecordingDraft.trimmed(trimStartSec, trimEndSec)` re-windows
  fixes/HR (re-based to the new zero) and recomputes duration, start/end stamps, distance
  (re-summed haversine — the dwell jitter no longer inflates it), avg/max HR, pace, and energy
  (linear per-second rate, matching the engines' duration-based model). **Steps stay the session
  total** (the counter is cumulative-only; idle spans contribute ~none). The review screen
  previews `draft.trimmed(applied…)` live (sketch + stat grid recompute on Apply/Undo) and
  `commit` runs the *same* fold — `DraftEdits` gained `trimStartSec`/`trimEndSec`, and all three
  engines now commit via a shared `RecordingDraft.applying(edits)` (trim first, then
  title/activity), so what persists is exactly what was previewed. iOS shifts the HealthKit
  courtesy-export wall window by the leading trim.
- **Card**: one row per trimmable end ("Remove 2:14 of idle time at the start?"), each with its
  own Remove/Undo toggle, directly under the stat grid so Apply visibly updates the numbers.
  Suggestion computed once on entering review (`RecordViewModel` state collector); the mock
  engine has no dense track → no card on desktop (real-track drafts only).

Tests: `IdleTrimTest` — synthetic 1 Hz tracks (leading/trailing/both dwells, sub-45 s idle,
jitter spike, all-idle, short route) + `trimmed` re-window/recompute + `applying` composition.
Green: `:fit-shared-logic:desktopTest`, `:fit-shared-ui:desktopTest`, `:fit-android:assembleDebug`,
`:fit-shared-data` iosSimulatorArm64, fit-ios sim `xcodebuild`. Threshold tuning (45 s floor,
30/10 m radii) is an expected on-device follow-up.

### 13.4 RC-4 — honest stats finalize pass

- **Real pause semantics**: the engine accumulates `pausedMs`; `elapsedSec = wall − paused`;
  `onLocation`/steps gate on `paused` (freeze distance + route, matching HR's existing guard).
  `durationSec` keeps meaning "active duration" — **no schema change** (a separate
  moving-vs-elapsed pair is deferred with the same format-bump reasoning as §13.3). Fix the mock
  drift the other way: the mock already stops ticking, so the shared tests finally describe both
  engines.
- **Accuracy gate**: drop fixes with `accuracy > 50 m`; don't start the track until the first
  accepted fix (kills the classic first-fix distance spike). Distance sums only over accepted
  fixes.
- **Calories stay a documented placeholder** (flat per-activity constant) until profile weight
  exists; auto-pause (speed-gated) explicitly deferred — the manual pause + trim suggestion cover
  the v1 need. Laps on the real engine: deferred.

**As-built (2026-07-25).** As designed, on **both** real engines (Android + iOS, not
Android-only):

- The pause maths live in a shared pure **`PauseClock`** (`:fit-shared-logic` commonMain,
  unit-tested) over each platform's monotonic source (`SystemClock.elapsedRealtime()` /
  `NSProcessInfo.systemUptime`) — active time freezes at pause, accumulates across repeated
  pauses, and drives `elapsedSec` **and every sample's `tSec`** (route + HR now share the
  active-time axis with `durationSec`, which keeps the §13.3 trim windowing coherent).
- `onLocation` gates on paused; **resume re-anchors the distance accumulator** (`lastLat/lastLng`
  nulled) so the segment moved while paused adds no phantom distance jump. Steps freeze via a
  sliding baseline/offset (Android `stepBaseline`, iOS `stepOffset`) — steps taken while paused
  are excluded from the total.
- Accuracy gate at 50 m on both engines (`Location.accuracy` / `CLLocation.horizontalAccuracy`,
  the latter also rejecting `< 0` invalid fixes); the track starts at the first accepted fix by
  construction. `end` deliberately stays `start + durationSec` (active time) — a wall-clock end
  field would be a schema change and is deferred with the same reasoning as moving-vs-elapsed.

### 13.5 RC-5 — crash durability (in-flight sample journal)

Append-only journal (filesDir, binary, flushed every ~15 s / N fixes) written during capture;
`start()` finding a leftover journal → `Reviewing(recoveredDraft)` → the summary screen opens with
a "Recovered workout" note. Closes the RAM-loss hazard in §13.0. Independent of RC-1..4; last
because it is the only slice with no user-visible ask behind it yet.

**As-built (2026-07-25).** Codec + recovery shape are commonMain; the file wiring landed
Android-first and the **iOS twin followed same-day** (see the iOS delta at the end):

- **`RecordingJournalCodec`** (`:fit-shared-logic`, pure, unit-tested): line-oriented
  `|`-separated events — `H` header (version/activity/startWallMs), `F` fix, `R` HR, `S`
  cumulative steps, `T` clock tick (recovers duration for sample-less strength workouts) —
  **torn-tail-tolerant** (malformed lines skipped, so a mid-write crash costs one line, not the
  file). Plain lines, not the designed "binary" — append-friendly and debuggable, and the volume
  (~1 line/s) doesn't justify a binary format.
- **`RecoveredRecording.toDraft()`** rebuilds the same summary shape the engines produce at stop
  (distance re-summed from the journalled fixes, energy from the shared per-activity rate now
  single-sourced as `recordedEnergyKjPerSec`) and marks the draft `recovered` —
  `RecordingDraft.recovered` is new; the review screen shows a "Recovered from an interrupted
  recording" note. The dense track survives, so the **RC-3 trim card works on recovered drafts**.
- **Android wiring** (`AndroidRecordingEngine` + `FileRecordingJournal`): events buffer in memory
  and flush every 15 ticks (≈15 s crash window) plus once at stop — the journal outlives stop and
  is cleared only at **commit/discard**, so a crash *during review* also recovers. ⚠ All journal
  I/O rides one `limitedParallelism(1)` lane in submission order — without it, a stop-time flush
  racing a commit-time clear could resurrect a saved workout as a duplicate draft. Recovery runs
  off-thread at engine construction and lands via `compareAndSet(Idle, Reviewing)`, closing the
  race with a fast Start. Recovery surfaces automatically: the existing shell effect navigates to
  the review screen off the Reviewing flip at launch.
- **Threat model**: A34 amended in the same PR — the in-flight journal is plaintext route+HR in
  app-private storage, the exact §9.13 outbox posture (local-only, never a wire format, deleted at
  save/discard).
- **iOS twin (same-day follow-up).** `IosRecordingJournal` (`:fit-shared-data` iosMain) rooted in
  Application Support like the §11.3 outbox; same lifecycle (clear at start, flush every 15 ticks
  + at stop, clear at commit/discard; single `limitedParallelism(1)` lane; recovery via
  `compareAndSet(Idle, Reviewing)` at engine construction). ⚠ One deliberate divergence: iOS
  **rewrites the whole snapshot atomically** per flush (`writeToFile(atomically = true)`) instead
  of appending — the engine already holds the full session buffers, a 2 h workout is a few
  hundred KB, and an atomic replace can never leave a torn tail (the codec's tolerance then only
  guards Android's append path). A34's journal note covers both platforms.

### 13.5b RC-5 in the field — an OEM kill, and what the journal could not say (2026-08-15)

The first real evidence of §13.5 doing its job, and of two gaps it left. A 21-minute run recorded on
a ColorOS phone (CPH2581) came back as an **unclosed loop** — the trace ends 915 m from where it
started. The capture itself was blameless: the stored `Workout.Streams` blob held **1278 fixes for
1278 active seconds**, max gap 2 s, no jump over 27 m, distance re-summing to the displayed 2.3 km.
Nothing was thinned by the RC-4 accuracy gate or the 1 Hz request.

What ended it was the OS. `dumpsys activity exit-info` for `app.tenshu.fit.android`:

```
timestamp=2026-08-15 13:54:40.233  reason=13 (OTHER KILLS BY SYSTEM)  subreason=2006
importance=125  description=o-kill(6)
```

**1.4 s after the last GPS fix**, and `importance=125` means the system killed a process that was
holding a *foreground service*. The same history shows `o-kill(322)` and `bgLimit_10[(service)]` on
neighbouring days: on this OEM the recorder is killed routinely. Two fixes followed:

- **The exemption ask is now the recorder's, not just Timeline's** (`BackgroundRunGate`,
  `:fit-shared-ui` expect/actual + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in `:fit-android`). Fired
  once per install at Start, mirroring `timeline-android`'s pref-guarded prompt, plus a persistent
  tap-through cue on the pre-start screen while unexempt — without that cue the single most
  damaging setting for a recorder is invisible from inside the app, and a user who dismissed the
  one-time dialog has no way back. ⚠ Exemption is **necessary, not sufficient**: ColorOS/MIUI keep a
  separate auto-launch list this intent does not touch.
- **The recovered draft now dates its interruption.** "Recovered from an interrupted recording — the
  last few seconds may be missing" was *true only if the user returns promptly*. Here the kill was
  at 13:54 and the draft was saved at 14:06, so what was missing was the rest of the run — and the
  note read as if it were a rounding error. The `T` journal line carries an **optional third field,
  the flush wall clock** (purely additive: a v1 line still decodes, and a v1 journal still recovers,
  so no version bump), `RecordingDraft.interruptedAtWallMs` carries it to the UI, and past 60 s the
  review screen says *"Recording was interrupted at 16:34, 2 min ago — nothing after that was
  captured."* The wall stamp beats deriving it from `start + activeSec`, which a paused span
  understates.

⚠ The lesson generalises past Fit: **a foreground service is a request, not a guarantee**, and any
capture whose loss is silent needs to say *when* it stopped, not merely *that* it did.

### 13.5c Naming the killer — the OS's own exit record (2026-08-15)

§13.5b left the review screen saying a recording "was interrupted", which is honest but passive: it
invites the reader to suspect the app. The phone keeps the answer. `ApplicationExitInfo` (API 30+,
`ActivityManager.getHistoricalProcessExitReasons`) needs **no permission to read about yourself**,
survives reboots, and is the same data `adb shell dumpsys activity exit-info` prints — the record
that convicted ColorOS in §13.5b. So a recovered draft now asks it, and where the answer lines up,
the note reads **"Your phone stopped the recording at 17:53, 2 min ago"**.

- **`ProcessDeathReporter`** (`:fit-shared-ui` expect/actual, desktop → null) reports the wall clock
  of the most recent **system-caused** death. The reason filter is the load-bearing part, and its
  *exclusions* more than its inclusions: `REASON_OTHER` (what an OEM killer reports), `SIGNALED`,
  `LOW_MEMORY` and `EXCESSIVE_RESOURCE_USAGE` count — while `USER_REQUESTED` (force-stop, swipe
  away), `CRASH`/`CRASH_NATIVE`, `ANR` and `PACKAGE_UPDATED` (every reinstall!) are *us or the
  user*. ⚠ Blaming the phone for our own crash would be worse than saying nothing.
- **`systemKillExplains(killAt, interruptedAt)`** (`:fit-shared-logic`, pure + unit-tested) decides
  whether that death explains *this* draft. ⚠ The window is deliberately **asymmetric** — a kill can
  only follow the flush it interrupted, so it allows 2 min after and 5 s of jitter before. The OS
  keeps 16 records; a symmetric window would pin yesterday evening's kill on this morning's run.
- **Verified on-device both ways** (the negative case is the one worth keeping): a `kill -9` mid
  recording → "Your phone stopped the recording at 17:53"; a `force-stop` six minutes later → back
  to "Recording was interrupted at 17:59", *with the earlier SIGKILL still on the record*.

Remaining from that thread, unbuilt: a `Build.MANUFACTURER`-keyed deep link into the OEM auto-start
screen (⚠ untestable except on hardware we own, and it rots with each skin release), and the larger
question of whether a killed session should **resume** rather than merely be recoverable — Timeline
already does the equivalent with `CaptureRearm` + a boot receiver + a watchdog, and the journal now
makes the reconstruction §13.1's `START_NOT_STICKY` rightly refused to attempt.

### 13.5d The vendor auto-start list — measured, and mostly a dead end (2026-08-15)

The third lever from the §13.5b thread: battery-optimisation exemption is AOSP and addressable, but
ColorOS/MIUI/EMUI keep a *separate* auto-start list on top of it that no API can read or set. The
plan was to deep-link into it. **Measurement changed the design.**

On the ColorOS device that killed the run, the auto-start screen resolves perfectly —
`com.oplus.battery.permission.startup.StartupAppListActivity` → `com.oplus.startupapp.view.StartupAppListActivity`,
`enabled=true exported=true`, category DEFAULT. Starting it throws:

```
SecurityException: Permission Denial: starting Intent { … } requires oplus.permission.OPLUS_COMPONENT_SAFE
```

⚠⚠ A vendor **signature** permission no third-party app can hold — and enforced in the vendor's
`checkStartAnyActivityPermission` hook, *not* declared on the activity, so `PackageManager` cannot
see it. `queryIntentActivities` says yes, `exported` says yes, and the launch still fails. That
kills "check, then offer": the check that would gate the offer is structurally unable to be right.

What shipped instead is **attempt, then fall back** (`OemBackgroundSettings`, `:fit-shared-ui`
expect/actual): try each candidate vendor screen, and on any refusal open the app's own
`ACTION_APPLICATION_DETAILS_SETTINGS` page — always present, and on ColorOS the route to
*Battery usage → Allow background activity*. The user always lands somewhere they can act, and the
wording promises only that ("open your phone's background settings", never "allow auto-start").

Where it is offered is **evidence-gated**, reusing §13.5c: the review screen shows it only under a
"your phone stopped the recording" note, and the pre-start screen only when the app is already
battery-exempt *and* the OS's exit record shows a system kill within 30 days. A user whose phone
behaves is never nagged about a setting they do not need.

⚠ The candidate table will rot — each row is a private vendor surface renamed at their convenience —
but a stale row costs one caught exception, and only the ColorOS row is verified here (verified, and
verified to be *unlaunchable*). ⚠ Verified on-device: the cue appears, the vendor screen is refused,
and App info opens instead.
### 13.5e Picking a killed session back up (2026-08-15)

§13.5b–d made an OEM kill *legible*: the draft says the phone stopped it, and offers a way into the
settings. This makes the run **continue**. RC-5's journal always held enough to rebuild the session;
§13.1 could not risk acting on it, because an OS-restarted service would have fronted an engine whose
session died with the process. It can now.

- **The journal learns it was stopped.** A `P` line, written at `stop()` before the final flush. The
  journal deliberately outlives stop until commit/discard, so ⚠ **without that marker every parked
  draft would resume capture on the next launch** — nothing else in the file distinguishes "the user
  pressed Stop" from "the process died mid-capture".
- **`isResumable`** (pure, tested): not stopped, and the last flush within `RESUME_WINDOW_MS` (15
  min). ⚠ A session whose journal cannot be dated (pre-§13.5b, no wall clock) is never resumed —
  resuming a run abandoned hours ago would weld a second leg onto a finished workout.
- **`PauseClock.resumeFrom`** re-anchors active seconds onto the new process's monotonic clock. The
  dead stretch counts as neither active nor paused: the route simply has a hole, which is the truth.
- **A 2-minute AlarmManager watchdog** (`RecorderWatchdog`), armed while a session is in flight and
  cancelled at stop/save/discard. ⚠ Not Timeline's 15-minute `PeriodicWorkRequest` — that floor would
  routinely lose half a run — and not WorkManager at all: adding `work-runtime` to `:fit-android`
  breaks its resource link (`@bool/enable_system_job_service_default` fails to resolve here though the
  same dependency is fine in `:timeline-android`; undiagnosed, tracked in `tech-debt.md`). An alarm
  needs no dependency and survives the kill.

⚠⚠ **The watchdog notifies; it must not restart capture itself.** The obvious version was built and
measured first: a background start of the recorder's `location`-typed FGS throws
`SecurityException: Starting FGS with type location … must be in the eligible state`, because
location is a **while-in-use** permission — and the uncaught throw inside `onStartCommand`
**crash-looped the app**. Battery exemption does not lift it; only `ACCESS_BACKGROUND_LOCATION`
would, and this app deliberately refuses it (`AndroidManifest.xml`: "GPS is acquired ONLY during an
active workout"). So the alarm posts *"Your phone stopped the recording — tap to pick it up"*, and
the tap is what makes the resume legal. `RecordingService.promoteToForeground` now also treats a
refused promotion as `stopSelf()`, never a throw.

⚠ **A notification tap must wait for the session, not sample for it.** The RC-1 routing read
`engine.state().first()` the instant the request arrived; after a cold start the journal restore is
still in flight, so it saw `Idle`, consumed the request and left the user on Home while their
recording quietly resumed behind it. It now waits up to 3 s for a non-`Idle` state.

**Verified end-to-end on the ColorOS phone**: `kill -9` mid-recording → notification 2½ minutes
later → tap → live screen at 1:15, continuing from the last flush with the dead 40 s excluded and
distance carried over, foreground service back up, journal growing again.

### 13.6 Sequencing + platform notes

RC-0 (shipped) → RC-1 → RC-2 → RC-3 → RC-4 → RC-5, each independently shippable; RC-3 needs RC-2
(the review screen is the trim's home), RC-4's pause fix can land any time. Notification work is
`:fit-android`-only; the engine split + summary screen + trim + finalize are commonMain/shared-UI
and reach desktop's mock engine for free. **iOS (as-built, 2026-07-25)**: the SwiftUI review
surface landed as a `RecordView.swift` branch on the `Reviewing` state (Stop parks the draft;
summary = route sketch via the reused `RoutePathView`, stat grid, title field + activity picker,
RC-3 trim card with per-end Remove/Undo, Save/Discard-with-confirm, recovered-provenance note,
§9.13 saved-locally/save-failed alerts — previously unsurfaced on iOS). ⚠ The preview rides a new
`RecordUiState.reviewPreview` computed property rather than Swift calling the `trimmed` extension
— top-level extensions of non-exported modules don't cross the framework boundary cleanly. Title/
activity edits batch into the VM at Save (a live TextField↔StateFlow round-trip would fight the
cursor); trim toggles go through the VM immediately (live preview). `finish()` stays on the
interface as the one-shot default but no shipped UI calls it any more. Live Activities = separate,
optional. All of this is presentation/local-capture — **no
relay data-shape change, no threat-model delta beyond the existing A34 posture**; no
`TENSHU_STORAGE_FORMAT_VERSION` bump anywhere in RC-0..5 (deliberately schema-free). Notification
behavior + FGS actions are framework code ⇒ each RC slice ends **on-device pending**, same posture
as §9.8.

## 14. `DailyActivity` is date-keyed — partial sources must fold, not clobber

### 14.1 The bug (shipped; found 2026-07-27)

`DailyActivity` is content-addressed by `date` alone (`DailyActivityDataIdProvider`), so every
write for a day targets the same `DataId` and a plain `saveData` **replaces the whole row**. That
is right for the identity ("one row per day") and wrong for the content, because the sources that
write a day are *partial in different ways*:

| source | `steps` | `distanceM` | `activeEnergyKj` | `activeMinutes` |
|---|---|---|---|---|
| Google Takeout (`Daily activity metrics.csv`) | ✓ | ✓ | ✓ | ✓ |
| Health Connect, pedometer-only device | ✓ | 0 | 0 | 0 |

So a Takeout import followed by the next launch's Health Connect auto-refresh silently reverted
every overlapping day to "0 m, 0 kcal". The per-field `orDerived` merge in `FitProjection`
(§9.1) does **not** cover this — it reconciles *stored-vs-workout* at read time, never
*stored-vs-stored* at write time.

⚠ The trigger is not exotic: `HealthImportBootstrap` refreshes from Health Connect on every
launch, so the clobber fires the first time the app opens after a Takeout import.

### 14.2 The rule

**A field is only overwritten by a value that says something.** `DailyActivity`'s schema defines
`0` as "unknown / no source" for all four stats, so `0` is exactly the value that must not win.
Each stat takes the incoming figure when non-zero and otherwise keeps what is stored; fields no
source has ever populated stay `0`.

Implemented once in `DailyActivityFold` (`:health-service-proxy`) and applied at **both** write
seams:

- **local** — `RecordDailyActivityImplementation` and `RecordImportedHealthImplementation` (the
  bulk path the importers actually use) fold onto the stored day before `saveData`, reading it
  back by the same date-keyed `DataId` the save will target (the `upsertIfNewer` lookup shape from
  `HealthProxyInitialization`);
- **cross-device** — `DailyActivityMerger : SemanticMerger`, so a peer's block for a day this
  device already holds becomes the union of what both devices know instead of last-writer-wins.
  ⚠ Registered in **production** wiring (all three `PlatformProxyModule`s + `SystemTestPlatformModule`),
  not only in tests — an unregistered merger fails silently back into the bug, which is the
  standing `MomentMerger`/`TripMerger` trap.

### 14.3 Deliberately *not* a join-semilattice

Unlike `RealmSchemaEpochMerger`'s MAX, the fold is **not commutative** when both sides carry a
non-zero value for the same stat: the incoming one wins, so the effective policy is "last writer
wins per contested field, union over uncontested ones". MAX would converge unconditionally but
would make a legitimate *downward correction* inexpressible and pin every day to the highest
figure any source ever reported. Ordering is not a correctness problem here because both seams are
already ordered — the local path folds onto what is committed, and `SyncProcessor` only consults a
merger for an HLC-newer block — so two devices applying the same set of blocks converge.

No schema change, no new field, no wire change ⇒ **no `TENSHU_STORAGE_FORMAT_VERSION` bump** (HR#9
untriggered) and no threat-model delta beyond the existing A34 posture. Tests:
`RecordDailyActivityUnitTest` (steps-only re-record preserves the rest / all-zero erases nothing /
first write verbatim), `RecordImportedHealthUnitTest.aStepsOnlyBatchDoesNotClobberAnEarlierRichBatch`,
`DailyActivityMergerTest` (union both directions, idempotent, different days not blended).

## 15. Full-history import — the platform's 30-day floor, and the grant that lifts it

### 15.1 Why History looked empty (2026-07-27)

Reported as "History is empty even though we imported from Google Fit", suspected as slow sync.
It was neither. Measured on the device:

```
read ExerciseSessionRecord: 0 record(s) over 1 page(s), writers=[]
read StepsRecord:        2270 record(s) over 3 page(s), writers=[android]
import: DONE — 14 item(s): workouts=0 daily=14 …
```

`writers=[]` on exercise sessions means **no app has ever written a workout to Health Connect on
that device** — `com.google.android.apps.fitness` holds 0 of its 46 `WRITE_*` permissions, so
Google Fit feeds Health Connect nothing. Health Connect is a live relay between apps, not an
archive of a Google Fit account; nothing appears in it retroactively. The only writer present is
the platform pedometer, hence steps-and-nothing-else. Sync was healthy throughout (both replicas
identical, fence `current`, epoch matched).

So the History gap is **not fixable by importing harder** — it is `drive-folder`-style missing
source data, and Google Takeout stays the only route to real Google Fit history (§9.6b).

### 15.2 The separate, real gap this slice closes

Chasing it surfaced something we *had* silently limited: **`HealthConnectImporter` only ever read
30 days**, from two independent caps —

1. **ours** — `historyDays: Long = 30`, a hardcoded constructor default with no UI over it;
2. **the platform's** — Health Connect refuses to return anything older than *30 days before the
   app's first health-permission grant* unless the app holds
   `android.permission.health.READ_HEALTH_DATA_HISTORY`, which we declared nowhere.

⚠ (2) is the load-bearing one: **raising `historyDays` alone changes nothing**, the reads stay
silently clamped. Anyone with a watch, Strava or Samsung Health feeding Health Connect was getting
one month of their data and no indication that a limit existed.

⚠ The platform cutoff is anchored at the **first grant**, not rolling from today — so reinstalling
the app moves the floor *forward* and permanently strands everything before the new anchor unless
the permission is held.

### 15.3 Shape

`ImportWindow` (`Recent` | `FullHistory`) threads from the UI to `HealthImporter.import`, and
`HistoryAccess` (`Unsupported` | `Grantable` | `Granted`) reports whether the depth gate exists and
whether it is open. Three states rather than a `Boolean` because "we cannot" and "you have not said
yes yet" are different answers: the first is never offered, the second is one tap.

- **`HISTORY_PERMISSION` is deliberately NOT in `PERMISSIONS`.** It is a separate toggle in the
  consent sheet and may reasonably be declined; folding it into the required set would make
  `hasAllPermissions` — and therefore `autoRefreshReady` and the entire ordinary refresh — fail
  forever on that refusal. A declined deep history must cost the user deep history, nothing else.
- **Feature-probed, not assumed** (`HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY`): the
  capability ships with the Health Connect *provider*, so a device can hold a client that knows the
  constant and a provider that ignores it.
- **The widening is gated on the grant**, so the log line names the window it actually used rather
  than claiming ten years and reading one month.
- Depth is 10 years, not `Instant.EPOCH` — covers any realistic personal history (the maintainer's
  own Takeout spans 2016→2026) at values the platform handles unremarkably.

**Two surfaces, one offer** (the maintainer's ask — "both in settings and onboarding so the user
can skip it"):

- **Onboarding** — a new `OnboardingStep.DeepHistory` between the source choice and the import,
  because the ask has to precede the read: a `Recent` import that already ran cannot retroactively
  deepen itself. **Self-skipping** — reached only when `HistoryAccess.Grantable`, so `Unsupported`
  and already-`Granted` both go straight through and the step is never an unanswerable question.
  Equal-weight "Not now" plus a footnote pointing at Profile.
- **Profile** — the same offer, permanently. Hidden outright when `Unsupported` (a disabled row
  invites a support question nothing in the app can answer); once `Granted` the row remains as the
  way to re-run the deep read on demand.

⚠ **"Allow" deliberately starts no import.** The shell owns the grant result and starts the read
there (full-history on a grant, `Recent` on a refusal), so a read started in the view model would
race it — and *lose*, because `startImport` holds a lock that makes the second caller step aside,
silently downgrading the very request the user just made. `MainActivity` imports on **both**
answers, or a refusal would park the user on a progress screen that never moves.

### 15.4 Scope + platform notes

⚠ **This recovers no workouts for the reporting device** — with `writers=[]` on exercise there are
zero sessions at any depth; it deepens steps/daily activity and pays off for anyone whose Health
Connect actually has a writer. Takeout remains the only path to Google Fit history.

iOS **honours `window`** (HealthKit has no 30-day floor — the authorisation grant is the only gate)
but keeps `historyAccess() = Unsupported`: there is nothing for the user to grant, so an entry
would be a button with nothing behind it. Surfacing "import everything" on iOS is a separate slice;
the depth is honoured so the plumbing is not silently lying about `window`.

⚖️ **Open before release:** whether `READ_HEALTH_DATA_HISTORY` carries its own Play Console
health-permissions declaration requirement. Not verified here.

### 15.5 The Google Fit notice — §15.4's warning, said to the user (SHIPPED 2026-08-09)

§15.4 predicted the outcome exactly, and predicting it in a design doc turned out not to help the
person holding the phone. The report: *"Import your full history says 30 items imported, but I only
see one workout, from January — Google Fit shows far more."* On-device logs (`adb logcat -s
TenshuFit`) showed **every layer working**:

```
import: reading window 2016-08-11 .. 2026-08-09 (full history)   ← grant held AND honoured
read ExerciseSessionRecord: 1 record(s), writers=[com.alltrails.alltrails]
read StepsRecord:        4854 record(s) over 5 page(s), writers=[android]
import: DONE — 30 item(s): workouts=1 daily=29 …
```

Three things the user could not have known: **"30 items" is every record kind summed** (1 workout +
29 days of activity, never 30 workouts); the one session came from **AllTrails**, not Google Fit;
and `com.google.android.apps.fitness` is a writer of **nothing**. The import was truthful and
useless — it knew the answer and said none of it, so the only available reading was "this is
broken".

⚠⚠ **The first cut of this notice was WRONG, and the maintainer's push-back is what found it.** It
shipped one message — "Google Fit does not share with Health Connect… this is not a permission you
can grant" — on the assumption that Fit simply has no Health Connect integration. Asked *"could I
just be missing a permission?"*, a `dumpsys` on the reporting device settled it in one read:

```
android.permission.health.WRITE_EXERCISE:       granted=false
android.permission.health.WRITE_STEPS:          granted=false
android.permission.health.WRITE_DISTANCE:       granted=false
android.permission.health.WRITE_EXERCISE_ROUTE: granted=false     (…and WRITE_SLEEP/SPEED/WEIGHT/…)
```

Google Fit **declares** those permissions — the integration exists — and holds **none** of them.
⚠⚠ So the common case is *a switch the user can flip*, and the shipped copy was both false and
actively harmful: it talked the one user who could fix it out of the only action that works.

**Shape.** `ImportNotice` (`:fit-shared-logic`) rides `ImportOutcome.Success` → the two completed
`ImportStatus` cases (`Imported` **and** `NoData` — an entirely empty Health Connect is the same
question from the user's side) → a `SourceNotice` block under the Health Connect row in Profile.
**Two verdicts, two different remedies:**

| Verdict | Condition | Remedy offered |
|---|---|---|
| `GoogleFitNotPermitted` | installed · wrote nothing · **no `WRITE_EXERCISE` grant** | *Open Health Connect* |
| `GoogleFitNotSharing` | installed · wrote nothing · **grant held** | *Import a Google Takeout export* |

- ⚠⚠ **`WRITE_EXERCISE` specifically, not any-of-the-write-set.** Workouts are what the user is
  looking at and missing; a Google Fit permitted to write only *steps* would still show an empty
  history, so treating that as "allowed" would route them to the wrong remedy.
- ⚠ **The permission probe fails *open*** (`getOrDefault(true)`). A false there produces "go and
  allow it", which would send the user to a screen where the switch is already on. Being unable to
  check is not evidence that it is off.
- ⚠ **Writers outrank the probe**: data that demonstrably arrived wins over a permission read
  saying it could not have. Test-pinned in both directions.
- ⚠⚠ **The predicate is never a row count.** Workouts arrive *in quantity* from other writers
  (`writers=[com.alltrails.alltrails]`) while every Google Fit session stays invisible — the exact
  reported case, and exactly what a "suspiciously few rows" heuristic misses. The writer half also
  means the claim **retires itself**: if Fit ever does feed Health Connect it appears there and the
  notice stops, with no constant anyone must remember to delete.
- ⚠ The **decision is `commonMain` and tested; only the observations are Android.** Health Connect
  exposes no "which apps contribute here" API, so writers accumulate from the reads we already
  perform (a per-import `ReadScan`, not importer state — two overlapping imports must not pool
  findings).
- ⚠ **`<queries>` entry required** for `com.google.android.apps.fitness` — it gates *both*
  `getPackageInfo` and `checkPermission`. Without it an installed Google Fit reads as absent and
  neither notice ever appears.
- ⚠ `openHealthSettings()` is **navigation, not a request** — the missing grant belongs to Google
  Fit and no app may prompt for another's permissions. It joins `HealthPermissionRequester` as a
  defaulted no-op (the `requestFullHistory` precedent) and `canOpenHealthSettings` mirrors
  `canImportFiles`, so neither notice names a destination its shell cannot reach.
- ⚠ Each **remedy sentence is separate from its explanation** and rendered only where that remedy
  exists. Found by a render test failing: the first cut told every shell to run a Takeout import,
  including ones that offer no such row — replacing one dead end with another.
- ⚠ Deliberately **not** `needsAttention`: an explanation beside the source it is about, not an
  interrupting Home banner. The import genuinely succeeded, and `HealthImportBootstrap` refreshes
  every launch — a banner would nag forever.

**Where Takeout still stands.** Once permitted, a grant governs data written *from then on*; years
already inside Google Fit do not retroactively appear — which is what `GoogleFitNotSharing` is for.
And there is no API route at any point: developer signups for the Fit APIs closed **2024-05-01** (no
credentials obtainable, at any price), the APIs die at the **end of 2026**, and the cloud
replacement reads health data server-side, which House Rule #1 forbids.

No schema, wire or relay change ⇒ **no version bump** (HR#9 untriggered; full gate green). Tests:
the three-way verdict plus both retire-cases (`ImportStatusTest`), the seam through to
`ImportStatus`, and five render tests — including that the *not-permitted* notice offers Health
Connect and **not** the export even with a picker bound, which is the way to get this wrong that
costs the user the fix. **Verified on the reporting device**: `notice=GoogleFitNotPermitted`, the
notice rendered, and its tap resolved to `com.google.android.healthconnect.controller`.

No schema, wire or relay change ⇒ **no `TENSHU_STORAGE_FORMAT_VERSION` bump**; no threat-model
delta beyond the existing A34 posture (the grant *widens* the A34 surface in depth, not in kind —
same categories, same consent mechanism, same content plane). Tests: five new view-model cases
(step self-skips on each `HistoryAccess`; allow defers to the grant result; skip imports `Recent`;
no-requester falls back; Profile prompts-or-imports by access).

### 15.6 The sharing wizard — SHIPPED 2026-08-09 (W1–W3; W4 deferred)

§15.5 ends at a door. `GoogleFitNotPermitted` correctly says "Google Fit has not been allowed to
share" and opens Health Connect — and then the user is standing in someone else's app, three taps
from a switch we cannot press, with no way for us to know whether they found it. This section
designs what comes after the door.

It was written down before it was built because the shape turns on one capability question that had
to be answered empirically, and the answer changes the design completely. **As-built notes are in
§15.6.7**; the design below stands as written except where that section says otherwise.

#### 15.6.1 The capability boundary (probed, not assumed)

| | Available to us? | Mechanism / evidence |
|---|---|---|
| Health Connect present | ✅ | `HealthConnectClient.getSdkStatus()` |
| **Our** read grants | ✅ | `permissionController.getGrantedPermissions()` |
| Deep-history grant | ✅ | `FEATURE_READ_HEALTH_DATA_HISTORY` + grant check (§15.3) |
| Another app is installed | ✅ | `getPackageInfo` + a `<queries>` entry |
| **Another app's HC write grants** | ✅ | `PackageManager.checkPermission(perm, pkg)` — §15.5 |
| Who wrote each record | ✅ | `record.metadata.dataOrigin.packageName` |
| Prompt for **our own** permissions | ✅ | the AndroidX contract; the system launches it for us |
| Open Health Connect (generic) | ✅ | `ACTION_HEALTH_CONNECT_SETTINGS` — verified on device |
| **Deep-link to another app's permission page** | ❌ | see below |
| Callback when the user changes something in HC | ❌ | none exists; re-probing on resume is equivalent |
| Grant, or prompt for, another app's permission | ❌ | not a thing, and should not be |

⚠⚠ **The per-app deep link is privileged, and that single fact sets the design.** The obvious
shortcut — land the user directly on Google Fit's permission page — resolves but cannot be used:

```
$ cmd package resolve-activity -a android.health.connect.action.MANAGE_HEALTH_PERMISSIONS
  name=com.android.healthconnect.controller.PermissionControllerEntryPoint
  permission=android.permission.GRANT_RUNTIME_PERMISSIONS      ← signature-level
```

This is correct platform behaviour, not an oversight: an app that could deep-link users into
granting permissions *to a third app* is a phishing primitive. So we land on Health Connect's home
and the user walks the last two taps themselves.

⭐ **Therefore: we cannot shorten the journey, but we can eliminate the need to describe it.** The
wizard is a **verifier, not a tutorial.**

#### 15.6.2 Why "verifier, not tutorial" is the whole design

The tempting alternative is click-by-click copy — *"tap App permissions, then Google Fit, then
turn on Exercise"*. Rejected on three counts, each independently sufficient:

1. **It goes stale.** Health Connect's UI is Google's and has already been restructured more than
   once. Our instructions would rot silently, and the failure mode is a user following confident
   directions that no longer match their screen.
2. **It cannot be localised honestly.** Those labels are Health Connect's own localised strings;
   ours would be an independent translation that must coincidentally match. At n=1 (ADR 0016) we
   would be shipping English instructions over a German system UI.
3. **It is untestable.** Nothing in our gate can assert that another app's screen still says what
   we claim.

Re-probing has none of these properties. `checkPermission` answers the same question the
instructions were trying to get the user to bring back, and it answers it in every locale, on every
Health Connect version, testably. So the copy shrinks to **one orienting sentence**, and
correctness moves into the probe.

#### 15.6.3 The step model

Each step is a **probe with an action attached**. The wizard advances on *observation* — never on
the user asserting they did something — by re-running the probe on `ON_RESUME`.

| Step | Probe | If failing | Who acts |
|---|---|---|---|
| **A** · Health Connect available | `getSdkStatus()` | Play Store, or terminal "not on this device" | user installs |
| **B** · We can read | granted vs `PERMISSIONS` | **our own** permission sheet | one tap |
| **C** · Deep history | `historyAccess()` | **our own** sheet (§15.3) | one tap |
| **D** · Fit permitted to write | `checkPermission(WRITE_EXERCISE, fit)` | open HC + one orienting line; re-probe on resume | user navigates |
| **E** · Data actually arriving | writers from the next read | re-import | automatic |
| **E′** · Permitted, still silent | Fit absent from writers *after* a grant | Takeout — **terminal** | user exports |

Properties that are load-bearing rather than nice:

- ⚠ **Self-skipping**, on the existing `OnboardingStep.DeepHistory` precedent (§15.3): a step is
  reached only when its probe fails. The wizard is only ever as long as the actual problem, and a
  healthy user never sees it. A fixed six-screen flow would be a worse experience than the notice
  it replaces.
- ⚠⚠ **D must not block on E.** A write grant does not make data appear — Google Fit syncs on its
  own schedule, and E may not turn green for minutes or until its next sync. D therefore completes
  on the *permission*; E is non-blocking ("we will pick this up on the next import") with a manual
  re-check. Building E as a spinner turns the wizard into a hang, which is the single most likely
  way to get this wrong.
- ⚠ **Honest terminal states.** A (no Health Connect) and E′ (permitted and still empty) must be
  able to *end* the flow. A wizard with no exit loops the user through steps that cannot help —
  and E′ is precisely where permissions stop being the answer and §15.5's `GoogleFitNotSharing`
  copy takes over.
- ⚠ **E′ is only reachable after a grant.** Reaching it any other way would tell a user to go and
  export their data when a switch would have done — the §15.5 mistake, re-made one layer up.

#### 15.6.4 Worth generalising: a writer list, not a Google Fit special case

The probe is package-agnostic. On the reporting device alone, `pm list packages` shows
`com.sec.android.app.shealth` and `com.oplus.healthservice` beside Google Fit — each equally
capable of writing to Health Connect and equally likely to be switched off.

So **step D generalises to a list**: *"Health apps that can share with Health Connect"*, one row per
known writer, each with its own probed state. That is the real feature; the Google Fit notice is
its degenerate one-app case, and both can share the probe.

- ⚠ Each candidate package needs its own `<queries>` entry — the list is a **fixed, declared** set,
  not a discovery mechanism (there is no "enumerate health apps" API, and a `<queries>` wildcard
  would be both refused and wrong).
- ⚠ **A34 note:** the result is a local read of which health apps the user has installed. It is
  rendered and discarded — never stored, never projected into the schema, never synced. Worth
  stating explicitly in the implementing PR rather than leaving a reader to work it out.

#### 15.6.5 Where it lives

Not a new subsystem: **this is the existing onboarding flow, made re-runnable and given one more
step.** `OnboardingViewModel` already carries a stepped flow with self-skipping (§15.3), and every
probe above already exists in shipped code. The work is a step model plus screens.

Entry points: the §15.5 `GoogleFitNotPermitted` notice (its action becomes *Fix this* → wizard
rather than a bare *Open Health Connect*), and a permanent Profile row beside *Import your full
history*.

#### 15.6.6 Phasing + open questions

- **W1** — the step model + A/B/C over existing probes (no new platform work; this is the part that
  is purely a re-arrangement of what §15.3 and §15.5 already do).
- **W2** — D + the resume re-probe, replacing the notice's direct settings jump.
- **W3** — E/E′, including the non-blocking re-check and the hand-off to Takeout.
- **W4** — the §15.6.4 writer list, if W2 proves the probe carries its weight.

⚖️ **Open:** (1) whether the resume re-probe should also re-run the *import* automatically or only
offer it — an automatic import on every return from settings risks a loop with
`HealthImportBootstrap`; (2) whether W1 should replace the onboarding path outright or sit beside
it, which is really the question of whether onboarding should ever have been a separate flow;
(3) whether E′ should be reachable manually for a user who wants Takeout without walking D first.

No schema, wire, relay or threat-model delta is anticipated — every input is an existing local
probe, and the A34 posture is unchanged (§15.6.4 note aside).

#### 15.6.7 As built (W1–W3 shipped 2026-08-09)

The design above survived contact with the code; three things are worth pinning.

**⚠⚠ The transition detection moved, because the first version was ordering-dependent.** "Google Fit
just became allowed" cannot be derived by reading the repository's current value immediately before
re-probing it — that only works while the repository happens not to have updated yet, which is true
in production and false in a test with a settable fake. A test caught it on the first run; a device
would have caught it much later, and intermittently. It is now detected **in the collector**, by
comparing each emission against the previous one.

**⚠⚠ The confirmation must not be cleared by a completed import that predates the grant.** When the
grant lands, `ImportStatus` is *already* a completed import — from before it. Clearing on "the
status is a completed one" cancels the confirmation in the same breath as raising it and jumps
straight to a verdict about data never re-read. Cleared on the **`Running` → not-running
transition** instead, which additionally survives a re-import returning a value *equal* to the last
one (a `!=` comparison would have silently missed that and left the wizard asking forever).
Test-pinned as `aCompletedImportFromBeforeTheGrantDoesNotCountAsConfirmation`.

**The three ⚖️ open questions are resolved as:** (1) the resume probe re-reads **permissions only**
and never starts an import — `HealthImportBootstrap` already imports every launch, and an import per
return-from-settings compounds with it into a loop the user cannot escape while the wizard is open;
confirmation is an explicit tap. (2) W1 **sits beside** onboarding rather than replacing it — the
merge is a real question but not this change's to answer, and doing it here would put a working
onboarding flow at risk for a refactor. (3) E′ needs no manual route: Profile's Takeout row is
already permanent and unconditional.

**Also as built:** `SharingReadiness` + `HealthImporter.sharingReadiness()` (defaulted, so iOS and
the reference importer simply never offer the wizard) → `FitRepository.sharingReadiness()` +
`refreshSharingReadiness()` → `SharingWizardViewModel` → `SharingWizardScreen` behind
`Destination.SharingWizard`. ⚠ A failed probe **leaves the previous verdict standing** rather than
resetting to the default, which reads as "nothing to fix" and would close the wizard on the user
mid-flow. ⚠ The §15.5 notice's action becomes *Fix this* → the wizard rather than a direct jump into
Health Connect: the jump was honest but one-way, since nothing confirmed the switch was found and
any *other* unmet step stayed invisible behind it.

**Verified on device** (emulator, Health Connect present, Google Fit absent): the wizard opened at
`AllowReading` — **not** `AllowGoogleFit`, so dependency ordering holds against a real probe — its
button reached Health Connect, and granting the read permissions **out of band** then returning
advanced it to `AllowHistory` with no user assertion of any kind. That last step is the whole
design working: `sharingReadiness: … canRead=false` → `canRead=true` across a resume.

Tests: 11 view-model cases (step order, both terminal states, the two transition traps above, and
the no-requester degradation) + 3 render tests — one of which asserts the copy **never quotes Health
Connect's own labels**, pinning §15.6.2's decision rather than trusting a future editor to remember
it. Full gate green (1779 tasks). W4 (the writer list) remains deferred.

## 16. iOS file import — the Takeout path iOS never had

### 16.1 The gap

Google Fit feeds **neither** Health Connect nor HealthKit, so a Takeout export is the only route to
real Google Fit history on any platform (§9.6b). Android and desktop had it; **iOS had no file
import at all** — no picker, and `FitProjectionRepository` was constructed with `fileParser = null`,
so even a wired picker would have reported `SourceUnavailable`. On an iPhone the years of history
were simply unreachable.

Two `jvmMain`-only pieces stood in the way, and both had to be written for iOS before the seam above
them could do anything:

| piece | JVM | iOS (this slice) |
|---|---|---|
| archive container | `JvmArchiveReader` (`java.util.zip`) | `IosArchiveReader` |
| XML DOM for GPX/TCX | `JvmXmlReader` (`javax.xml`) | `IosXmlReader` |

⚠ The XML half is easy to miss and expensive to skip: with the archive reader alone, an import would
have yielded the daily-metrics CSV (the day totals) and **zero workouts**, because
`ActivityFileParser` is portable only down to the `XmlReader` seam — which is precisely the half of
the history the user came for.

### 16.2 `IosArchiveReader` — no third-party dependency

Kotlin/Native ships **`platform.zlib` for every Apple target**, so the DEFLATE half is the system
zlib and only the container (central directory, local headers, ZIP64) is parsed here. That decided
it against ZIPFoundation or any SPM package: a health importer is not worth a supply-chain edge, and
the format is small enough to read directly.

Two deliberate departures from the JVM twin:

- **Central directory, not a sequential scan.** `ZipInputStream` forces the JVM reader front-to-back;
  seeking the directory instead is both more robust (a streamed local header may defer its sizes to
  a trailing data descriptor, which a sequential reader has to infer) and cheaper — every member's
  *name* is known before a single compressed byte is touched, so `TakeoutParser.isRelevant` filters
  out a mixed export's photos and mail for free. It also makes `count` knowable, so progress reads
  "n of m" instead of an open-ended count-up.
- **Memory-mapped** (`NSDataReadingMappedIfSafe`): a 96 MB — or 10 GB — export costs address space,
  not resident memory. One member's inflated bytes are live at a time, matching the JVM contract.

Same two caps as the JVM twin (64 MiB per member, 50 000 members) guard a hostile archive.

### 16.3 `IosXmlReader` — semantics matched, not approximated

`NSXMLParser` is SAX, so the reader builds the small tree the `XmlNode` interface describes as events
arrive (affordable: the documents are per-workout activity files, kilobytes, never the archive).

⚠ The parsers *above* this seam are shared, so a semantic divergence would surface as a
platform-specific import bug rather than as a failure in this file. The test is therefore
`IosActivityFileParserTest` — **byte-identical assertions to the web twin**, run through the real
`ActivityFileParser`: local-name stripping (`<gpxtpx:hr>` answers to `"hr"`), descendants at any
depth in document order excluding self, and `text()` taking the first such descendant's full nested
`textContent`. It passed unchanged on the first run, which is the evidence that matters.

### 16.4 Wiring

`FitSurface.importPickedFile(fileUrl)` is the Swift-facing entry: `.zip` → `IosArchiveReader`,
anything else → a single activity file; an archive that will not parse **falls back** to the
single-file path rather than failing the import. Outcome + progress flow back through the ordinary
`importStatus`, so the SwiftUI surface needs no import-specific state.

⚠ **The Swift side copies the picked document while the security scope is held** — the P3a lesson
from Drive, and non-negotiable here: the scope is released the moment the `.fileImporter` callback
returns, and this import runs async for *minutes* afterwards, so reading the original there fails
for any out-of-sandbox document, invisibly, because the pick itself succeeded. The cost is a second
copy of a large archive on disk, deleted as soon as the import finishes.

Also closed here: `OnboardingView.swift` now handles `.deepHistory` explicitly. That step is
Android-only today (HealthKit has no deep-history grant, so §15's `historyAccess` is `Unsupported`
on iOS), but the `default: ProgressView()` arm would have rendered it as a spinner that never
resolves — a trap for whoever gives iOS a real `historyAccess` later.

### 16.5 Scope

No schema, wire or relay change ⇒ no `TENSHU_STORAGE_FORMAT_VERSION` bump. No threat-model delta
beyond A34: the bytes are user-picked, parsed in-process, and land on the same sealed content plane
as every other import; the temp copy is app-container-private and deleted after use (the A5 shape
already covered by Drive's picker).

⚠ **On-device pass pending** — `.fileImporter`, the security scope and a real multi-GB export are
framework/hardware territory; the simulator build and the unit round-trips are what is proven here.
⚠ **Pre-existing, NOT introduced by this slice:** `:fit-shared-data:iosSimulatorArm64Test` has **12
failing tests** (`FitPipelineIntegrationTest`, `HealthImportTest` — every read returns empty),
verified failing identically at `6746dca4`, before any of §14–§16. The desktop twins of those tests
pass, so it is iOS-specific and unrelated to the import path. Worth its own investigation.

## 17. Why workout detail showed no route — two independent causes, both on the *acquisition* side

**Shipped 2026-07-28.** Reported as "Fit doesn't display routes in the detail screen — do we still
need to integrate the map?" The map was never the gap: `WorkoutRouteMap` over `:ui-map`'s
`RouteBasemap` has rendered in `WorkoutDetailScreen`'s route section since
`maps-region-delivery.md` Ph2/Ph3c, gated on `geoRoute.size >= 2`. **Nothing was reaching that
gate.** Two unrelated acquisition bugs, one per import source.

### 17.1 Health Connect — routes were never asked for

`READ_EXERCISE` yields a session's *summary*; the GPS track is gated behind its own permission and
comes back as `ExerciseRouteResult.ConsentRequired` until it is held. `readWorkouts` never touched
`exerciseRouteResult` and the manifest never declared the grant, so **every** Health-Connect-imported
workout was summary-only by construction.

- `ROUTES_PERMISSION` (`android.permission.health.READ_EXERCISE_ROUTES`, a literal — the AndroidX
  SDK publishes `WRITE_EXERCISE_ROUTE` but no read twin) + the manifest declaration.
- ⚠ Deliberately **not** in `PERMISSIONS`, for `HISTORY_PERMISSION`'s reason: a declined route grant
  must cost routes, not break `hasAllPermissions` → `autoRefreshReady` → every refresh, forever. It
  rides the same consent sheet via the new `REQUESTED_PERMISSIONS` (request set ≠ verdict set).
- `routeOf` maps `ExerciseRoute.Location` → `RouteSample` (E7, `tSec` from session start, altitude
  cm); `NoData` is the ordinary indoor case, not a failure. The import log now counts sessions with a
  route and sessions withheld for consent.
- ⚖️ **Google gates `READ_EXERCISE_ROUTES` behind a Play declaration** at publish time. The
  per-session `ExerciseRouteRequestContract` needs no declaration but prompts *per workout*, which is
  unusable for a bulk history import — hence the manifest path, with the declaration as a release
  prerequisite.

### 17.2 Google Takeout TCX — a real mapping failure, found on a live archive

Diagnosed against the maintainer's own export (2,689 activity files) on the connected device.

- ⚠⚠ **One session imported as many workouts.** Google Fit writes a session as a *run* of
  `<Activity>` segments — one per continuous stretch of movement. Taken literally, one 15-minute walk
  became **8 workouts** of 30 s–7 min, and its 5 GPS fixes scattered one-or-none per fragment, so no
  fragment cleared the two-point floor: **the walk lost its map by being cut up.** `parseTcx` now
  stitches same-sport segments back into the session (samples re-based onto the session start;
  distinct sports stay distinct; single-`<Activity>` files unaffected). `durationSec` stays the
  **sum of active time**, not the wall-clock span — the gaps are the standing-still the exporter cut.
- ⚠⚠ **Distance was always zero.** `XmlNode.text(tag)` is *first descendant*. A `<Lap>` carries a
  summary `<DistanceMeters>` **and** contains a `<Track>` whose every `<Trackpoint>` carries its own
  — and Google writes the `<Track>` **first**, so the lap read the first trackpoint's running total:
  `0.0`. New `XmlNode.childText(tag)` (direct children only, all three readers) is what lap-level
  summary fields are read with. `TotalTimeSeconds`/`Calories` were only ever right by luck — no
  trackpoint happens to share those names.
- **Measured over the real archive** (2,689 files): before — 10,745 history rows, distance 0 on all
  of them, 1,051 sessions where *some* fragment showed a map; after — 3,002 rows (312 files are
  genuinely multi-sport), **2,244 with a real distance**, **1,192 sessions rendering a map** (+141
  recovered purely by un-splitting; 1,340 sessions carry any GPS at all).
- ⚠ **A re-import does not clean up the old fragments.** Rows are content-addressed by `workoutId`;
  the merged row reuses the *first* fragment's id, so the other fragments from a previous import
  survive as orphans. A history already imported the old way needs Fit's data cleared before
  re-importing, or it will carry both shapes — and **there is no way to clear it**:
  `:health-service` has record and read ops only, and the rows live in Control's store, so neither
  clearing Fit's app data nor wiping Control's replica removes them (the latter deletes one
  device's copy while peers keep theirs). Designed out in
  [`control-data-reset.md`](control-data-reset.md); until R1 of that lands, the fragments stay.

⚠ The FIT-binary branch stays desktop-only (`FitBinaryDecoder` is unbound on Android — the Garmin
Java SDK rides `desktopMain`); it did not matter here because Takeout writes `.tcx`, but a
hand-picked `.fit` file on Android still parses to nothing.

## 18. Profile — the placeholder identity, and the rows that never opened

### 18.1 What is wrong (audit 2026-08-01, on a live device)

Profile shows `You · Male · 178 cm · 74.2 kg · May 30, 1992 · 30 min · 8,000 steps`. **None of it
is the user's.** Every row carries a `>` chevron; **one** of seven does anything.

- **The chevrons are a dead affordance.** `ProfileRow` (`ProfileScreen.kt:264`) renders
  `FitIcons.ChevronRight` unconditionally while `clickable` is applied only when `onClick != null`
  (`:256`). Only Units → Measurement passes one (`:138`). Goals, Height, Weight, Birth date and
  Sex all promise a destination that does not exist.
- **The values are hardcoded — but *not* from the mock.** Android binds `realDataModule()`
  (`FitModules.kt:41`); `MockFitRepository` is previews-only. The constants live inside the *real*
  repository (`FitProjectionRepository.kt:207-210`) and are byte-identical to
  `FitMockData.kt:365-374` — copied over when the projector was written. The class KDoc (`:118`)
  calls this out honestly ("in-session, non-persistent … a synced preference store is a later
  slice"); the practical effect is that the app asserts a fabricated body to the user.
- **Even the one live row does not stick.** `updateUnits` (`:669`) sets a `MutableStateFlow` and
  nothing persists it, so metric/imperial reverts on every launch.
- **`saveGoals` exists with no Compose caller.** `ProfileViewModel.kt:133` is reached only from
  `FitViewModelsTest.kt:375`.
- ⚠ **iOS is ahead of Compose on both counts.** `ProfileView.swift` already ships goal steppers +
  *Save goals* (`:36-41`), and its `LabeledRow` deliberately drops the chevron — "minus the chevron
  these rows never earn — none of them is editable in v1" (`:171`). iOS diagnosed this and designed
  around it; Compose did not. Its goals still evaporate on restart, because the gap underneath is
  the same one.

### 18.2 Where each field lives — three domains, not one app

The instinct is "persist `FitProfile`". That is wrong twice over: for a third of the fields the app
*already holds the data*, and for another third the data is not Fit's at all.

**Body measurements — already real, already synced (weight), or a measurement waiting to be one
(height).** `HealthConnectImporter` reads `WeightRecord` (`:417`) into
`HealthSample(metric = Weight)`; Browse → Body renders its history (`CategoryDetailViewModel.kt:117`)
and `FitProjection.kt:432` already resolves the latest reading. So Profile's `74.2 kg` is a hardcoded
constant **sitting beside a real, synced time series of the same quantity in the same app**. A
`fit.profile.weight` preference row would diverge from it on the first refresh.

**Height belongs in the same bucket**, and this supersedes the "declared, by recommendation" steer of
the first draft. Health Connect has `HeightRecord` and HealthKit has
`HKQuantityTypeIdentifierHeight`; the only reason height looks like a settings field is that our
importer never asked for it. Giving it a preference row would recreate the weight divergence the
moment anyone imports one. → `Metric.Height` joins the enum, both importers read it, and a
user-entered height **appends a `HealthSample`** exactly as a user-entered weight would. One
mechanism for both, no second truth.

**The person — not Fit's data at all.** Name, date of birth, sex and units describe *the user*, not
their fitness. The repo already shows the fragmentation this causes:

- `DrivePreferences.ContactDisplayName` (`drive.contact.selfName`, REALM) is the user's own name,
  stored in **Drive's** namespace because Drive needed it first for contact handshakes.
- Its KDoc defers to "Control's pairing `ownerMetadata.displayName` seam" — ⚠ **that seam does not
  exist**; the only two hits in the repo are this KDoc and its twin in `ContactsRepository.kt:109`.
- `:contact-service`'s `Person` models *other* people (petnames); `:identity-service`'s `Member` is a
  per-shared-realm pseudonym. Neither is "me".
- ⚠ **Timeline hardcodes kilometres** (`TimelineText.kt:74`, `timeline_distance_km`) with no units
  preference anywhere in the repo. A Fit-only units toggle leaves an imperial user with an imperial
  Fit and a metric Timeline forever.

A `fit.profile.name` row would make that three. → **name, birth date, sex and units become
`OwnerPreferences` in `:preference-service`**, beside the existing `ControlPreferences` (whose KDoc
already establishes the pattern: Control's own preferences live in the always-installed module,
app-specific ones in the app). No new module, no schema.

**Fit keeps goals**, and only goals — a step target means nothing to Drive.

### 18.3 Acquisition: import if the platform has it, ask if it does not

The first draft framed this as "declared *vs* imported". That was a false choice — the maintainer is
right that it is simply *both*, and the split falls out of what each platform actually exposes.
Verified, not assumed:

| | Health Connect 1.1.0 | HealthKit (iphoneos SDK) |
|---|---|---|
| Height | `HeightRecord` ✓ | `HKQuantityTypeIdentifierHeight` ✓ |
| Weight | `WeightRecord` ✓ (already imported) | ✓ (already imported) |
| Date of birth | **absent** | `dateOfBirthComponentsWithError:` ✓ |
| Sex | **absent** | `biologicalSexWithError:` → `HKBiologicalSex` ✓ |

⚠⚠ **Health Connect has no demographics API whatsoever** — verified against the 1.1.0 sources jar:
no characteristic type, no `biologicalSex`, no `dateOfBirth` anywhere in the artifact. The only
matches for "sex" and "birth" are `SexualActivityRecord` (a cycle-tracking record) and
`HeightRecord`. Google Fit does not fill the gap either: it never fed Health Connect (§15.1), and its
own profile data lived on the Google account, not in the fitness store. **So on Android these values
can only come from the user.** iOS can seed both, and should.

This makes the ask unavoidable rather than optional, which the maintainer identified independently:
**recorded-workout calories are a flat per-activity constant today** — `energyKjPerSec` returns
`0.9 / 0.3 / 0.65 / 0.4 / 0.35` kJ·s⁻¹ by activity type and nothing else
(`RecordingEngine.kt:415`, `AndroidRecordingEngine.kt:507`, flagged as a placeholder at §13.4). A
MET-based estimate needs body mass; anything resting- or HR-derived additionally needs age and sex.
Fit cannot get honest about energy without this data, so the onboarding ask pays for itself rather
than being a form for a form's sake.

### 18.4 Sex — a closed enum, named for what it is used for

**Apple's is an enum**, and it is the only import source that exists: `HKBiologicalSex` =
`NotSet(0) / Female(1) / Male(2) / Other(3)`. Health Connect has nothing to be compatible with. So
the mappability constraint is exactly one four-valued enum.

→ **A closed enum mirroring those four values, stored as a stable lowercase token** (`"female"`,
`"male"`, `"other"`; absent = not set) rather than an ordinal — the wire stays readable, and adding a
value later never renumbers an existing one.

Free text was the alternative and it fails the actual use: a physiological formula has to *branch*,
so a free string pushes fuzzy matching into every consumer, and a value we cannot map is worse than
one the user declined to give.

⚠ **Name the field for its purpose.** What the calculation wants is a physiological parameter, not an
identity — and those are genuinely different things for a growing number of people. Apple separates
them for exactly this reason. So: ask narrowly ("used to estimate energy expenditure"), make it
skippable, and have `Other` / not-set select a **sex-neutral formula** rather than silently guessing.
If gender identity is ever wanted for presentation, it is a *different field*, added later, with no
migration owed.

⚠ It must not become a gate: §12.9 deliberately does **not** sex-gate Cycle ("never a
`FitProfile.sex` gate", `:1733`). That decision stands and this enum does not reopen it.

### 18.5 Shape

**`OwnerPreferences`** in `:preference-service`, all `PreferenceScope.REALM` (the same person on
every device):

```
owner.name        String  ""          StringAdapter
owner.birthDate   String  ""          StringAdapter  (ISO-8601; no LocalDate adapter exists)
owner.sex         String  ""          StringAdapter  (token per §18.4)
owner.units       String  "metric"    StringAdapter
```

**`FitPreferences`** in `:fit-shared-logic`, mirroring `DrivePreferences`
(`core-preference-service.md` §3.8):

```
fit.goals.activeMinutes  Int  30    IntAdapter
fit.goals.steps          Int  8000  IntAdapter
```

Scalar rows rather than one composite blob, for two load-bearing reasons: the scalar adapters keep
each value **self-describing on the wire** (a composite or list rides as opaque bytes and loses
cross-app readability — `PreferenceAdapter` KDoc), which is precisely what lets Timeline read
`owner.units` without depending on Fit; and per-field rows mean two devices editing different fields
converge instead of clobbering.

**Seams.** `OwnerProfile` (interface + `TenshuOwnerProfile` + `NoopOwnerProfile`) beside
`OwnerPreferences`; `FitGoalPreferences` in Fit. Both copy `DisplayPreferences.kt` exactly — and REALM
scope makes them *simpler* than Drive's, with no `DeviceIdentity` await and so none of the
"seed first, then await identity" hazard.

⚠ **`read()` is silent until a row exists** (`PreferenceServiceExtensions.kt:113`): a realm that has
never held the row emits nothing, so every flow needs `onStart { emit(default) }` or a cold device
strands the screen on nothing. Drive learned this the hard way; copy it.

**Repository wiring.** `FitProjectionRepository` takes both seams; `profile()` / `goals()` stop being
`MutableStateFlow`s and become derived flows, with `heightCm` and `weightKg` folded in from
`samplesLatest()`. `updateGoals` / `updateUnits` write through instead of assigning.

⚠ **`:fit-shared-data` has no preference dependency** and `fitClientModule` (`FitClientModule.kt:45`)
does not register `preferenceServiceClientModule` — Fit is the one app that never needed it. Both
must be added. *Checked:* Control hosts `:preference-service-proxy`
(`control-shared/build.gradle.kts:132`), so Fit declaring the service in its announce vector meets a
proxy that already knows it — no announce-gate skew, no Control change. iOS already registers it
(`EpcFitClient.kt:116`).

⚠ **Drive's `drive.contact.selfName` is superseded by `owner.name`.** Pre-release, the cheap move is
to point Drive at the new row and let the old one rot — the self-name is already re-prompted inline
on first use, so the worst case is one extra prompt. Do **not** leave both live: two writable
self-names is the bug this section exists to stop.

⚠⚠ **Unset is not zero, and this is the change that ripples.** Today's defaults *are* a fabricated
person; persisting them unchanged would make the fabrication durable and synced. A never-configured
profile must render "Not set", so `FitProfile`'s declared fields go nullable — touching `Identity()`
(`profile.name.take(1)` on `""` renders a blank avatar), `heightText` / `weightText`, `FitMockData`
and iOS `ProfileView`. Land it in FP-1; retrofitting nullability after editors ship means touching
every editor twice.

### 18.6 Editors, and the "About you" onboarding step

**Onboarding.** A new `OnboardingStep.AboutYou` **after** `Importing` — the maintainer's placement,
and the right one: the import may already have answered the question, so asking first would be asking
for data we are about to be given. It follows `DeepHistory`'s established self-skip pattern (§15.3):
shown only for the values still missing once the import has settled, so iOS (which seeds DOB + sex
from HealthKit, and height from a `HeightRecord`) typically shows nothing at all, and Android shows
only what Health Connect structurally cannot supply. Every field skippable — a skipped profile costs
the user calorie accuracy and nothing else.

**Profile rows** become per-row edit dialogs in `:fit-shared-ui` (five fields, each one value — a
separate edit screen is more navigation than the content earns):

- **Goals** — stepper dialog over active minutes + steps, matching iOS's existing steppers
  (`5…240` step 5, `1000…30000` step 500). Wires `saveGoals`, which already exists and is tested.
- **Height / Weight** — a number entry that **writes a `HealthSample`**, not a setting (§18.2), and a
  chevron that offers Browse → Body for the history. The cross-tab jump already exists (§12.7).
- **Birth date** — date picker. **Sex** — the four-value single-select of §18.4. **Name** — text field.
- The chevron becomes conditional on `onClick`. Every row will earn one, but the condition stays so
  the next non-interactive row cannot silently re-open this bug.
- **i18n**: every new string via `stringResource` (ADR 0016). "Not set" and the sex options are new.

### 18.7 Phases

- **FP-1 — persistence (no new UI).** `OwnerPreferences` + `FitPreferences` + both seams + repository
  wiring + the nullable/unset model + "Not set" rendering + the conditional chevron + Drive
  re-pointed at `owner.name`. **Ships value alone:** units survive a restart, the six false
  affordances go, and the app stops asserting a body that is not the user's.
- **FP-2 — height + weight as measurements. SHIPPED** (as-built §18.9).
- **FP-3 — editors + the `AboutYou` onboarding step (Compose). SHIPPED** (as-built §18.10).
- **FP-4 — iOS parity. SHIPPED** (as-built §18.11).
- **FP-5 — HealthKit characteristic seed. SHIPPED** (as-built §18.12).
- **FP-6 — `owner.units` consumed outside Fit. SHIPPED** (as-built §18.13).

### 18.8 Scope notes

**No schema, wire or relay change in FP-1.** Preference rows are existing `RealmSetting` composites,
so `TENSHU_STORAGE_FORMAT_VERSION` does not move (HR#9 untriggered) and no relay redeploy is owed
(`core-schema-evolution.md` §7.5/§7.8). ⚠ **FP-2 is different**: `Metric.Height` widens a stored
enum's value set, so it follows the §12 precedent for adding a metric and is the phase to check HR#9
against, not FP-1.

⚠ **Threat model (HR#2).** FP-1 puts **date of birth and sex** into the synced realm, and does so at
the *platform* level rather than inside a health app. They ride the same E2E path as every other row
and A34 already covers Fit's health categories under the same content/consent posture, so this is not
a new asset class — but demographics are not obviously "fitness data", and the placement in
`:preference-service` means they are readable by any app on the device. A34 should name them
explicitly in the FP-1 PR rather than leaving a reader to infer the coverage.

**Gates.** FP-1 changes DI reachable from `:fit-android` ⇒ `scripts/android-smoke.sh` (HR#8). Tests:
`FitViewModelsTest` already drives `saveGoals`; add preference round-trips through
`FitPipelineIntegrationTest`'s `TestProxy` stack — and ⚠ assert a **serialize→parse** round-trip, not
just in-process reads, or a wire-registration gap passes unnoticed.

**Resolved from the first draft.** Height → a measurement, not a preference (§18.2). Sex → a closed
four-value enum mirroring `HKBiologicalSex`, stored as a token (§18.4). Ownership → the person-level
fields leave Fit entirely (§18.2). Nothing in this section is left open for the maintainer.

### 18.9 FP-2 as-built — height joins weight in the health domain

`Metric.Height` + both importers + the Profile join, as designed. Deltas worth recording:

- **`readLatest`, not `readAll`.** Weight rides as a time-series because a weight *chart* is the
  point; an adult's height is effectively a constant, so a series of it would be noise and the one
  value Profile needs is the newest. Both platforms read the latest reading only.
- **Stored in centimetres, unit `"cm"`.** The unit travels on the sample exactly as `"kg"` does for
  weight, so the row is self-describing; `FitProfile.heightCm` rounds to whole centimetres (the
  model is an `Int` and no surface renders a fraction of one — the imperial path re-derives ft/in).
  Health Connect gives metres (`inMeters * 100`), HealthKit is asked for `cm` directly.
- **Height rides the `Body` category**, so it is gated by the same consent the user already grants
  for weight and can never arrive through a category they declined. Asserted both ways round in
  `HealthImportTest` — the pre-existing `setOf("Weight")` pin became `setOf("Weight", "Height")`,
  and the Vitals-only case now excludes *both*.
- ⚠ **Height deliberately gets NO chevron yet** — this reverses §18.6's "Height / Weight → Browse →
  Body". Body detail charts **weight only** (`CategoryDetailViewModel` → `observeSamples(Weight)`),
  so pointing Height there would land the user on a screen that never mentions height: the same
  "chevron that lies" §18.1 exists to remove. Weight navigates; Height stays inert until FP-3 gives
  it an editor (which writes a `HealthSample`, per §18.6) and earns one back.

**No format bump, and this was the phase to check** (§18.8 flagged it). `Metric` is a Fit *UI*
enum; the stored field is `HealthSample.metric`, a `string(...)`. Adding a new **value** to an
existing string field adds no field and reorders nothing, so no composite is reshaped and HR#9 is
untriggered. (An old peer would parse a `Height` row fine and drop it at
`FitProjection.metric(raw)`, which folds unmodelled metrics to `null` by design — graceful, not
garbling. Moot in practice: clients move in lockstep.)

Gates: full `desktopTest jvmTest test`, `fit-android:compileDebugKotlin`,
`fit-shared-data:compileKotlinIosSimulatorArm64` (the HealthKit half), Android smoke gate.

### 18.10 FP-3 as-built — every row earns its chevron back

Six editors + the onboarding step, as designed. Deltas worth recording:

- ⚠ **`AboutYou` asks the calorie inputs only — birth date, sex, height, weight — not the name.**
  §18.6 implied "the missing fields"; on implementation the honest scope is *what the app cannot
  compute without*. A name is an input to nothing, so asking for it at onboarding is pure friction;
  it stays a Profile edit. Self-skipping is per field, so on iOS (where HealthKit can fill all four
  once FP-5 lands) the step never appears, and on Android only the two Health Connect structurally
  cannot supply are ever asked.
- **The name editor is the header, not a "Name" row.** A row under Personal data would be a second
  place to change the thing already displayed at the top of the screen.
- **Weight's history link lives inside its dialog**, not on the row. The row's tap now belongs to
  the entry dialog, and a long-press for "view history" is an undiscoverable gesture — the same
  class of dishonesty as a chevron with nothing behind it. Height offers no such link (Body charts
  weight only), so **FP-2's removal of Height's chevron is reversed here**: it has an editor now.
- ⚠ **Measurement entries are plausibility-guarded** (30–280 cm, 2–640 kg) and a rejected value is
  simply not recorded. Deliberately wide — the job is catching a slipped decimal point, not policing
  anyone's body — but it matters more than a normal form check because these **append to a series**:
  one bad reading would skew the Body chart permanently.
- Material 3 ships no stepper control, so the goals dialog hand-rolls one, matching iOS's bounds and
  increments exactly (5–240 by 5, 1 000–30 000 by 500).
- ⚠⚠ **iOS had to be handled in this slice, not FP-4.** `OnboardingView`'s `switch` ends in
  `default: ProgressView()`, so the new step would have rendered an **eternal spinner** — precisely
  the trap that file's own comment warns about for `.deepHistory`. iOS passes straight through
  `.aboutYou` (nothing is asked, so nothing is skipped by the user) until FP-4 builds its editors.

**No format bump.** Every write rides an existing op — `WriteSetting` for the declared fields,
`RecordHealthSample` for the measurements — so no composite is reshaped and HR#9 is untriggered.

Gates: full `desktopTest jvmTest test` (⚠ two unrelated system tests flaked across runs —
`MapRegionServiceTest.downloadProgressEndsWhenNoDownloadIsInFlight` and
`EntitlementEnforcementSystemTest`, both green in isolation and on re-run, neither in a module this
slice touches); `fit-android:compileDebugKotlin`; `fit-ios` xcodebuild; Android smoke gate;
five new view-model cases. Verified on the emulator: every row carries a chevron, the Sex dialog
offers "Not set" as a first-class option, and a save round-trips through the real preference store
into both the row and the header subtitle.

### 18.11 FP-4 as-built — iOS parity, one editor implementation

Swift-only. `LabeledRow` is deleted and its "none of them is editable in v1" comment retires; the
goals steppers stay exactly as they were (FP-3's Compose steppers were built to match them).

- ⚠ **One sheet, two callers.** Profile and the About-you onboarding step edit the same values, so
  `ProfileEditorSheet` takes a plain `ProfileEditorValues` bag and hands back a single
  `ProfileEdit`, rather than binding to either screen's model. The first draft had onboarding
  growing its own `AboutYouEditor`; a second copy of five field editors was the real cost, and this
  shape removes it. Each caller's `apply(_:)` is the one place a write happens.
- **The header is the name editor**, as in Compose — a "Name" row under Personal data would be a
  second place to change what the top of the screen already shows.
- **Birth date offers a "Set a birth date" toggle** rather than a Clear button: unset has to stay
  reachable, and on a date wheel there is no other way back to it.
- ⚠ **`Kotlinx_datetimeLocalDate`'s Swift initialiser is `(year:month:day:)`**, not the
  `monthNumber`/`dayOfMonth` the *property* names suggest. Only `xcodebuild` catches this — the
  Kotlin side compiles regardless, the same class of gap as the `KotlinInt?`/`KotlinDouble?`
  unwrapping in §18.9.
- ⚠ **A new Swift file needs `xcodegen generate`.** `fit-ios` is a generated project, so
  `ProfileEditors.swift` was invisible to the build until the project was regenerated — it failed
  as "cannot find type in scope", which reads like a missing import.

**Gates:** `fit-ios` xcodebuild (generic + the booted simulator destination) and the app installs
and launches to its honest not-connected surface. ⚠ **No interactive click-through** — `simctl` has
no tap subcommand and no `idb` is installed here, so exercising the sheets is a maintainer step,
the same posture as every other iOS UI slice in this repo. No Kotlin changed, so the Android and
Kotlin gates are untouched by this slice; no schema, wire or format change.

⚠ **Still open after FP-4:** `AboutYou` is reachable on iOS only because HealthKit demographics are
not read yet — **FP-5** closes that, and once it lands the step self-skips on iOS in the ordinary
case, which is the behaviour §18.6 describes.

### 18.12 FP-5 as-built — the platform gets one chance, then it is the user's

`HealthImporter.readCharacteristics()` (defaulting to "knows nothing") + a HealthKit
implementation over `dateOfBirthComponentsWithError:` / `biologicalSexWithError:` + a seed step
after every import. §18.3's asymmetry, finally exploited: iOS can answer, Android structurally
cannot.

- ⚠⚠ **A one-shot marker, not "fill if unset".** The obvious rule — seed any field that is
  currently blank — silently re-fills a value the user **deliberately cleared**, and FP-3 made
  "Not set" reachable precisely so clearing is a real choice. `OwnerPreferences.DemographicsSeeded`
  spends the platform's single chance; after that the value is the user's. Proven by
  `theSeedIsOneShotSoAClearedValueStaysCleared`, which is the test this design exists for.
- **Knowing nothing does not spend the one-shot** (so a grant obtained later still gets its
  chance); knowing *part* does — the rest is then the user's to give, which is the right default
  once they have already seen the About-you step.
- ⚠⚠ **New `readOnce` terminal on the preference client, and it is load-bearing.** `read()` is a
  *stream*: on a realm holding no settings at all it never emits, so `.first()` hangs; and
  `OwnerProfile.observe()` seeds defaults on subscribe, so *its* first emission reports "unset" for
  values the store actually holds. Deciding "has the user already answered?" off either would
  overwrite a real answer. `readOnce` reads the relation once via `getAll()` — no race, no hang.
  (This is the snapshot read §18.5's ⚠ note said consumers must not fake with `first()`.)
- **Demographics ride the `Body` grant**, like height and weight (§18.9), so they inherit consent
  the user already gives rather than adding a separate ask. The HealthKit sheet names them
  explicitly, and `NSHealthShareUsageDescription` was updated to say so — the purpose string is
  what the user actually reads.
- ⚠ **A refusal is indistinguishable from a blank.** Both HealthKit accessors report "not
  authorised" as an *error*, not a null, which is the same shape as "the user left it empty in
  Health". Neither is worth surfacing, so both resolve to "not known" and the profile stays unset.
- The seed runs **after** the import: the grant that makes the read possible is the one the import
  just obtained, and About-you is evaluated after the import too — so a seeded value is in place
  before the step decides whether it has anything left to ask. ⇒ **`AboutYou` now self-skips on
  iOS** in the ordinary case, which is the behaviour §18.6 described and FP-4 could not yet deliver.

**No format bump** — one new preference row on an existing `RealmSetting` composite, plus an
interface method with a default. Gates: full `desktopTest jvmTest test`;
`fit-android:compileDebugKotlin`; `fit-shared-data:compileKotlinIosSimulatorArm64` (the HealthKit
half); `fit-ios` xcodebuild. Five new cases, incl. both the never-overwrite and the
cleared-stays-cleared guarantees. ⚠ The live HealthKit read is hardware/authorisation-gated — the
simulator has no Health data — so the on-device pass is a maintainer step.

### 18.13 FP-6 as-built — the payoff for putting units at platform level

Timeline reads `OwnerPreferences.Units` and renders miles/feet. §18.2 predicted this customer; it
is the proof that the placement was right rather than speculative generality — nothing in Timeline
knows Fit exists, and nothing in Fit knows Timeline does.

- **A `CompositionLocal`, not a parameter.** `LocalMeasurementSystem` lands in `:ui-core` beside the
  theme, because units are the textbook ambient concern: every distance formatter needs them, no
  call site in between has an opinion, and threading a `units` argument through the tree would put
  the plumbing everywhere the decision is not. `static`, so a flip re-composes every distance —
  which is the intent. Defaults to metric so previews and tests render.
- **The pure formatter branches; the Composable only picks a template.** `TimelineFormat.
  distanceParts(distanceM, units)` gains `Feet`/`Miles` forms with the existing separator-in-the-
  template discipline, so the imperial decimal is a translator's to change too.
- ⚠ **The imperial changeover is a tenth of a mile, not a mile.** The direct mirror of the metric
  rule would print "1,847 ft" for the walk the metric build renders as "1.8 km" — precision nobody
  asked for on an estimate. A tenth of a mile (~161 m) is Google Maps' rule and reads the way people
  speak: an 850 m stroll is "half a mile", not "2,788 feet". ⚠ It does mean imperial is coarser
  just above the changeover (0.1 mi ≈ 161 m vs 1.0 km's 100 m) — accepted, and the convention.
- ⚠ **`DistanceParts`' field names stay metric-flavoured** while carrying either system's numbers
  (`kilometres`+`tenths` hold miles, `metres` holds feet). Deliberate: the alternative is four
  near-identical fields of which two are always zero, and `form` already says which to read.
- **Timeline gains no setting of its own** — `TimelineRepository.units()` reads the platform row
  straight through `OwnerProfile`, defaulting to metric for the mock / not-connected / preview
  implementations exactly as `controlReachable()` does.

**No format bump** — a read of an existing preference row. ⚠ Timeline is the third app to declare
`:preference-service` in its announce vector; Control already hosts the proxy half, so no Control
change (smoke gate confirms `app.tenshu.timeline schema session admitted`).

Gates: full `desktopTest jvmTest test`; `timeline-android` + `fit-android` compileDebugKotlin;
`timeline-ios` xcodebuild; Android smoke gate PASSED (six apps at v18). Three new formatter cases.
⚠ **The end-to-end visual is unverified**: flipping Fit to Imperial writes the row and Timeline
launches admitted, but the emulator has no captured movement, so no distance renders there — seeing
"1.5 mi" in the feed needs a device with real capture.

**§18 is complete.** The remaining consumers of `owner.units` (Maps, Drive file sizes) are ordinary
follow-ons now that the local exists: provide it at the shell and read it in the formatter.

### 18.14 Profile's header — a real app bar, and the identity block that wasn't one

Opened by a screenshot: scrolled, Profile's rows ran flush into the "Profile" headline and read as a
collision. The first fix moved a gap around and did not help, because the gap was never the problem.

**⚠⚠ Fit's five tab screens had no app bar at all.** They put a bare `Text` headline above a
`verticalScroll` — `App.kt` says so outright ("Fit's screens are bare headline Columns with no
TopAppBar/SearchBar of their own"), and the shell compensates by applying `WindowInsets.systemBars`
on their behalf. Because there was no bar, there was no **container**, and a container is M3's
actual mechanism for separating a bar from content scrolled beneath it. The clipped row had nothing
to be clipped *against*. ⚠ Fit is the outlier repo-wide: `ui-core/.../chrome/AppTopAppBar.kt` exists
and Drive, Control, Contacts, Home and Maps all use it.

**Shipped:** `LargeTopAppBar` + `exitUntilCollapsedScrollBehavior`, hosted in a `Scaffold`, with a
**transparent container and a hairline divider** shown only once content is actually beneath it.
Google Fit's shrinking headline is this component — it is stock M3, not a Google invention.

#### What measurement settled (and what it overturned)

Three plausible diagnoses were wrong, each costing a build. They are recorded because the *method*
is the lesson: two `uiautomator` dumps ended an argument that four screenshots could not.

- ⚠⚠ **The bar displaces no content.** With the bar and with the old bare headline, the first row
  lands on the **same pixel** (`Weight` at y=363). What read as "huge padding" was the tonal band
  *painting* chrome that had always been there — hence the divider treatment, which marks the same
  boundary at a fraction of the weight.
- ⚠⚠ **`expandedHeight` does not move the expanded title.** 112.dp and 88.dp put it on the identical
  pixel and only changed the space *below*. M3 reserves a fixed **64.dp collapsed-title row** above
  the expanded title — the slot it cross-fades into — so the band above is **structural, not
  padding**. No parameter aligns a large-bar title vertically (expressive adds
  `titleHorizontalAlignment`, i.e. *horizontal*, only). ⇒ **A large bar structurally cannot have a
  vertically centred title.** If that band is unacceptable the large bar is the wrong component:
  a plain `TopAppBar` centres its title in one 64.dp row, at the cost of the collapsing headline.
- ⚠ **The stable bars DO take heights** — via a second overload
  (`LargeTopAppBar(…, collapsedHeight: Dp, expandedHeight: Dp, …)`) that a glance at the first
  signature misses.
- ⚠ **`LargeTopAppBar` keeps TWO title nodes** in the semantics tree (collapsed + expanded,
  cross-fading), so `onNodeWithText(title)` finds two and fails. Tests must use `onAllNodesWithText`.

#### Material 3 Expressive is present but closed

⚠⚠ `LargeFlexibleTopAppBar`, `MaterialExpressiveTheme` and `MotionScheme` are **compiled into**
`org.jetbrains.compose.material3:material3:1.9.0` — and marked **internal**. `javap` shows them;
the compiler refuses them. JetBrains removed the expressive APIs from the stable release in step
with Jetpack's 1.4.0-beta01 and keeps them on the **alpha** channel only
(`material3:1.9.0-alpha04`). ⚖️ **Not taken:** one alpha dependency across five shipping apps to buy
a subtitle slot and a horizontal title alignment is a poor trade, and this repo has scar tissue
exactly there (the CMP 1.7.3 pin needed 11 `force()`/`exclude()` blocks and its own proposal to
escape). Revisit when expressive goes stable.

⚠ **Google Fit is not a reference implementation.** "Match Fit" and "follow the spec" can diverge;
the authorities are m3.material.io and the androidx `material3-catalog`.

#### The identity block, removed

The header was a monogram + name + a `sex · height` subtitle. Audited on the way past:

- the circle **was never an avatar** — `name.take(1).uppercase()` on a tint, a letter restating the
  word printed beside it, wired to nothing;
- the subtitle **duplicated two rows** visible a few hundred pixels below, and did it across two
  stores — sex is an `OwnerProfile` **preference**, height is a `HealthSample` **measurement** — a
  seam no header should paper over;
- ⚠⚠ but the name was **load-bearing**: the row was the only editor for it (there was deliberately
  no Name row), and Home greets the user by it.

So the block is gone and **Name moved into Personal data as an ordinary row**. Deleting the header
without that would have quietly removed the ability to set your own name — the kind of regression a
render test does not catch because nothing renders differently until you look for the affordance.
An unset name now reads "Not set" like every other unset row; the "You" greeting fallback retires
with the header that justified it (a placeholder in a greeting is hospitality, in a data row a claim).

⚠ **Profile only.** The other four tab screens keep their bare headlines, so Fit is now internally
inconsistent until they follow. That is deliberate — one screen is the honest size for a change this
opinionated — but it should not sit half-done for long.

> **Superseded by §18.15 (2026-08-13).** The large bar is gone; every Fit screen now carries the M3
> **small** bar. §18.14's measurements all stand — read them, they are why §18.15 went the way it
> did — but two of its conclusions were drawn inside too narrow a range. See §18.15.

### 18.15 The whole sweep — one bar for every Fit screen, and the inset flip that forced it

§18.14 ended with "it should not sit half-done for long". This is the other half, plus the thing
that made half-done untenable.

**Reported:** the padding above *Profile* still felt wrong. §18.14 had already established that the
bar displaces no content — so this was not a measurement error, it was the **wrong component**, and
the maintainer's own instinct named the right one: *"the header text should be vertically centred,
but still left aligned."* That sentence is a description of the M3 **small top app bar**.

#### What the tokens say (read out of the artifact, not the spec site)

`javap` over `org.jetbrains.compose.material3:material3:1.9.0`:

| component | container height | title type scale |
|---|---|---|
| `TopAppBar` (small) | **64** | `titleLarge` |
| `MediumTopAppBar` | **112** (collapsed 64) | `headlineSmall` |
| `LargeTopAppBar` | **152** (collapsed 64) | `headlineMedium` |
| `MediumFlexibleTopAppBar` | 112 / 136 w/ subtitle | `headlineMedium` |
| `LargeFlexibleTopAppBar` | 120 / 152 w/ subtitle | `displaySmall` |

⚠⚠ **Profile was off-spec even as a large bar.** It shipped `LargeTopAppBar(expandedHeight = 112.dp)`
— 112 is the **medium** token — so it rendered `headlineMedium` in a container sized for
`headlineSmall`. M3 pairs the height *with* the type scale and has no such variant. The screen was
neither a compliant medium bar nor a compliant large one.

⚠ **The 64.dp band is the collapsed bar, and on Profile it was empty.** That row is where a
navigation icon and actions live. Profile has neither, so it drew nothing at all — which is the
whole of the "huge padding". M3 reaches for medium/large when a title wants emphasis *and* that row
has occupants; a tab destination with no back arrow and no actions is the small bar's case.

#### Two corrections to §18.14

- ⚠ **"`expandedHeight` does not move the title" was measured inside the clamped region.** The
  expanded title is bottom-aligned with `LargeTitleBottomPadding` = 28.dp; a `headlineMedium` line
  box plus that padding needs ~64.dp of second row. §18.14 tested 88 and 112 — second rows of 24
  and 48 — so in *both* the title clamped to the top of the row and landed on the same pixel. At
  the real 152 token it would have moved. The parameter is not inert; it was never given room.
  ⇒ The headline conclusion is unchanged and is the load-bearing one: **a two-row bar's title is
  bottom-aligned by construction, so no parameter centres it.**
- ⚠ **`LargeFlexibleTopAppBar` is public, not internal.** §18.14 recorded it as "compiled in and
  marked internal". `javap` shows `LargeFlexibleTopAppBar-eXZ4JBQ` as `ACC_PUBLIC` with an
  inline-class mangling suffix, not the `$material3` suffix an `internal` declaration carries.
  Moot either way — the expressive bars were **not adopted**, because §18.14's actual reason for
  declining them (a subtitle slot and a *horizontal* alignment knob do not solve a *vertical*
  problem) is untouched by their visibility.

#### As built

- **`AppTopAppBar` gained `scrollBehavior`** (`ui-core/.../chrome/AppTopAppBar.kt`). ⚠⚠ Before this
  **no screen in the repo passed one** — three `scrollBehavior` references existed and all three
  were Fit's Profile. So every app had the container and never the signal: `AppBarTokens` defines
  `ContainerColor = Surface` and `OnScrollContainerColor = SurfaceContainer`, and that shift is
  M3's mechanism for separating a bar from content scrolled beneath it, but it only fires with a
  behaviour wired up. ⚠ **Pinned, not `enterAlways`** — pinned is a no-op at rest, so adopting it
  changes nothing until the user scrolls.
- **`FitTopBar`** (`fit-shared-ui/.../components/FitTopBar.kt`) wraps it to keep Fit's
  `ChevronLeft` in one place instead of five. Delete it if Fit ever adopts the platform arrow.
- **All five tabs** (Home, History, Record, Browse, Profile) now host a `Scaffold` + `FitTopBar` +
  `pinnedScrollBehavior`. ⚠ Home's title is the **nav label**, not the date: the eyebrow + date
  block is *content* naming the day the aggregates describe, and only the loaded state has a day to
  name — in the bar it would flicker between states or vanish. Home's bar therefore sits at screen
  level so its empty and failed states wear it too.
- **The pushed screens** (workout detail, route map, category detail, medical, sharing wizard,
  record summary) drop their hand-rolled `Row` of `IconButton` + `titleLarge` + `HorizontalDivider`
  for the same bar. ⚠⚠ Those were re-implementations of the small bar — `titleLarge` is even the
  exact `AppBarSmallTokens` title token — that re-implemented only what shows in a screenshot.
- **The divider is gone everywhere.** The container colour does that job now; a permanent rule under
  an unscrolled bar is just a box.

#### ⚠⚠ The inset flip is what made this all-or-nothing

`App.kt` passed `contentWindowInsets = WindowInsets.systemBars` *because* Fit's screens had no bars
and nothing consumed the status-bar inset. A `TopAppBar` applies `TopAppBarDefaults.windowInsets`
itself, so the moment any screen gains one, the shell double-pads it — which is exactly what
§18.14's `windowInsets = WindowInsets(0,0,0,0)` on Profile was working around. **One screen could
opt out; five could not.** Hence:

- the shell now passes `WindowInsets.systemBars.only(WindowInsetsSides.Bottom)`. ⚠ **Not symmetric,
  deliberately**: on a tab the `NavigationBar` already consumes the bottom, but a pushed screen has
  no bottom chrome and nothing else would claim it. Dropping it wholesale (Drive's posture) would
  need every Fit screen to host a `Scaffold` purely for an inset the shell already supplies.
- ⚠⚠ **`RecordLiveScreen` is the one screen with no bar** — `LiveTopBar` is an activity readout on a
  deliberately full-bleed dark surface, not an app bar — so it spells its inset out
  (`windowInsetsPadding(WindowInsets.statusBars)`). Without that the activity name draws under the
  status bar.
- ⚠⚠ **The Control-unreachable banner sits above every screen**, so after the flip it is the other
  thing with no bar to own its inset. It gets `windowInsetsPadding(WindowInsets.statusBars)` *only
  while visible* — which both pads it and **consumes** the inset for everything nested inside, which
  is what stops the screen's own bar adding a second status bar's worth beneath it.

#### ⚠⚠ Widening `AppTopAppBar` propagated an opt-in to every caller

`TopAppBarScrollBehavior` is `@ExperimentalMaterial3Api`, and **naming it in the signature is enough
to require the opt-in at call sites that do not pass it** — the `@OptIn` on the declaration covers
the body, not the callers. `TopAppBarColors` is not experimental, so this wrapper had never leaked
before. Most call sites already carried the opt-in (they host a `Scaffold`); **six did not** — one
in Timeline, three in Contacts, two in Calendar — and were annotated. ⚠ The compiler found them all,
but only after `:fit-shared-ui` had compiled clean: *widen an interface, compile every implementor.*

#### Verification

`./gradlew desktopTest jvmTest test` green. `ProfileScreenTest`'s `onAllNodesWithText(...)[0]`
workaround — needed because the large bar kept **two** cross-fading title nodes — is now a strict
`onNodeWithText(...)`, which passes and would fail if a second title ever came back.

⚠⚠ **The Android smoke gate cannot cover this sweep.** Fit is one of the deliberately un-built
canaries (House Rule #8), so the gate installs whatever Fit APK is already on the device. It
validates the `ui-core` change across the six built apps and nothing else. **The Fit sweep owes a
real on-device pass** — insets are precisely what a desktop render test cannot judge.

#### 18.15a ⚠⚠ The regression it shipped — Record's Start button vanished

Reported the same day, on device: the big **Start** button was gone from Record.

**⚠⚠ `Scaffold`'s content slot is not a `Column`.** `ScaffoldLayout` measures the body as a list of
measurables and places **every one of them at (0, 0)** — two children there *overlap*, they do not
stack. Record was the one converted screen whose body had **two** children:

```kotlin
Column(Modifier.fillMaxSize()) {          // ← the outer Column §18.15 deleted
    Text(header)
    Column(Modifier.weight(1f).verticalScroll(…)) { … }   // scroll area
    Box(Modifier.padding(16.dp)) { Button("Start") }      // pinned to the bottom
}
```

Replacing that outer `Column` with the `Scaffold` slot removed the vertical stacking, and changing
the scroll area's `weight(1f)` to `fillMaxSize()` removed the space Start had been occupying. Both
halves of the same edit; either alone would have been enough. Measured by the new test: Start's top
landed at **33 dp**, *above* the activity chips' bottom at **142 dp** — drawn at the top of the
screen, behind them.

⚠ **Every other converted screen had exactly one child in that slot**, which is why only Record
broke. Checked, not assumed: History (`LazyColumn`), Browse (`Column`), Profile (`Column`), Home
(`Box`) each emit a single body composable.

⚠⚠ **Why nothing caught it.** Record was the only one of the five tabs with **no render test** —
Browse, History, Home and Profile all have one, and all four still passed. The desktop gate, the
Android smoke gate and `:fit-android:compileDebugKotlin` were all green, because none of them
asserts *where* anything is. `RecordPreScreenTest` now exists and pins the position.

⚠ **`assertIsDisplayed` alone does not catch this** — the button was still composed and still within
the viewport, just at the wrong coordinates and underneath other content. The test asserts Start's
top is below the activity chips' bottom *and* that it sits in the lower part of the root. Verified
non-vacuous by reverting the fix and watching it go red. Do not simplify those assertions away.

⇒ **Rule for the next `Column` → `Scaffold` conversion: count the children of the body slot first.**
More than one means the outer `Column` was doing layout work, and it has to survive inside the slot.

⚠ **Not done:** the pushed screens get the bar but **no scroll behaviour** — they host it in a
`Column`, where content scrolls *beside* the bar rather than under it, so a nested-scroll connection
would need a `Scaffold` per screen. Scroll response is on the five tabs only. That is a scope line,
not a discovery.

### 18.16 Home scrolls as one page (2026-11-01)

**Reported:** on Home, the "Home" bar and the date under it stayed put while everything else
scrolled beneath them — Google Fit scrolls its whole home page, and that felt more natural.

**Shipped, Home only:** the bar uses `exitUntilCollapsedScrollBehavior(snapAnimationSpec = null)`
and the eyebrow + date block moved inside the scroll column. The bar now leaves with the content
and returns only when the content is back at the top.

- ⚠ **§18.15's AppTopAppBar note was wrong**: `exitUntilCollapsed` *does* work on the small bar.
  With no headline to collapse, it collapses the whole row (`heightOffsetLimit` = the bar's own
  height), which is exactly a page that scrolls as one.
- ⚠ **`snapAnimationSpec = null` is load-bearing.** The default snap finishes a half-collapsed bar
  by itself after a fling, so the bar would move without the content it is meant to move with.
- ⚠ **The date stays outside the module column's 12.dp spacing** — it keeps its old 8/4 padding,
  so moving it into the scroll does not shift anything below it. (Getting this wrong pushed the
  latest-workout card out of `HomeScreenTest`'s window.)
- ⚖️ **The Journal (History) and the other tabs keep the pinned bar** — on purpose. A list reads
  better with its header in place, and Google Fit's Journal does the same. Don't "make it
  consistent" across tabs.
- Pinned by `HomeScreenTest.homeScrollsAsOnePageBarAndDateIncluded`, which goes red with the
  pinned bar (checked) and with the date hoisted back out of the scroll.

### 18.17 Softer marks — a thinner ring, a one-colour week chart (2026-11-01)

Follow-on to the theme's surface ramp (ADR 0001 §1a), from the same report: Fit felt harsh next to
Google Fit's softer, "paper" look. With the surfaces fixed, the two heaviest marks on Home were:

- **The ring** — an 18.dp full circle in `primary`, the darkest mass on the screen. `Ring`'s default
  stroke is now **12.dp** (onboarding's ring already used 12). Colour unchanged: the accent should
  still be the accent, just less of it.
- **The week chart** — today's bar in solid `primary` beside grey (`surfaceContainerHighest`) bars,
  two unrelated colours. Past bars are now **`accent` at 24 % alpha**, so the chart reads as one
  colour with today emphasised. ⚠ Translucent on purpose: it tints whichever card `TrendBars` sits
  on (Home and Category detail), where an opaque role would have to match each card.

Judged by eye on light/dark renders of Home, not by a test — this is taste, so there is nothing for
a test to pin beyond "it renders", which the existing render tests already cover.

### 18.18 Home's four week cards, and every number opens its detail (2026-11-01)

**Reported:** Home's single *Active minutes* chart was minimal next to Google Fit, and nothing on
Home led to a day-by-day view except through Browse → Activity, which showed steps only.

**Shipped:**

- **Four week cards, in Google Fit's order:** energy burned, steps, active minutes, distance — one
  `WeekTrendCard` composable over the existing `TrendBars`, so they cannot drift apart in style.
  Goal lines where a goal exists (steps, active minutes); the week-over-week delta stays on active
  minutes only, the one series `HomeViewModel` computes it for.
- **Each card opens its series' detail**, and so do the three values under the ring (steps,
  distance, energy). ⚠ The **whole card** is the button, as in Google Fit; that does not fight the
  chart's scrubbing — `TrendBars` consumes a horizontal drag, which cancels the click, and a
  vertical drag is the page scroll. Only a plain tap opens.
- **The detail is the existing category screen**, now with three more Activity series:
  `CategoryMetric.ActiveMinutes`, `.Distance`, `.ActiveEnergy` — each one column of the same
  `DailyAggregate` row as steps, so they share its D/W/M/Y windowing and day-by-day entries.
  `Destination.CategoryDetail` gained `metric` (defaulting per category, so Browse is unchanged).

⚠⚠ **`ActiveEnergy` is energy BURNED; `Energy` is Nutrition's energy EATEN.** Different series,
different titles ("Energy burned" vs "Energy") — never merge them because both show kcal.

⚠ Only Activity has more than one series; any other category ignores a requested metric
(`categoryDetail_nonActivityIgnoresARequestedMetric`). ⚠ Card titles reuse the stat-strip labels,
so on Home "Steps"/"Distance"/"Energy" each appear twice — tests take the last match.

Not done: the ring itself opens nothing, and distance/energy have no goals (none exist in `Goals`).

### 18.19 iOS parity for §18.16–§18.18 (2026-11-01)

`fit-ios` is its own SwiftUI app (D7: no Compose reaches iOS), so the three Home changes above were
ported by hand:

- **One page** — Home hides its navigation bar and draws "Home" + TODAY + the date as the first rows
  of the scroll. ⚠ A large title is **not** the iOS equivalent: it collapses into an inline bar that
  stays pinned, which is the behaviour §18.16 removed. `.navigationTitle("Home")` stays set as the
  back-button label. ⚖️ History and the other tabs keep their bar, as on Compose.
- **Softer marks** — `GoalRing` 16 → 12 pt; `TrendBars` lights today (or the held bar) and draws the
  rest as the tint at 24 %, replacing iOS's own goal-met/unmet colouring; it also gained the dashed
  goal line Compose already drew.
- **Four week cards** (`WeekTrendCard`) and the three tappable values under the ring, all
  opening Category detail on that series. ⚠ A card cannot be a bare `NavigationLink`: the chart is a
  UIKit view, so a tap on the plot never reaches a SwiftUI button around it (seen on the simulator) —
  `ScrubRecognizer` reports its own tap, gated on its hold failing, and the card pushes onto a typed
  `homePath`. ⚠ Not one `navigationDestination(isPresented:)` per card: with four in one stack, a
  tap on the Energy plot opened Steps. `FitSurface` gained a
  `categoryViewModel(categoryId:metric:)` overload — an overload, not a default argument, because
  Obj-C has no defaults and Browse's one-argument call must keep compiling.
- **Surface ramp (ADR 0001 §1a)** — iOS has no copy of the Amethyst palette to match; it uses system
  colours. The ramp's *defect* did have an iOS twin, though: cards in
  `secondarySystemGroupedBackground` sat on a plain `systemBackground` page, which is **white on
  white** in light mode. Home and Category detail now use `systemGroupedBackground` as the page.
  ⚠ The other iOS screens with such cards (Record, Workout detail, Onboarding, History) still sit on the plain page — a sweep of its own.
- Verified on the iPhone 18 Pro simulator (iOS 27.2) over the mock, via the DEV_HARNESS
  `-mockHome` launch argument (the real `MainShell`, no onboarding gate): plot tap opens the right
  series and pops in one step, hold-and-slide scrubs without opening, light + dark. ⚠ Not seen: the
  status-bar strip with the not-connected banner gone — the mock always shows the banner.

## 19. Home read 0 steps — the record ceiling truncated the *newest* records (fixed 2026-08-11)

### 19.1 The report, and what it actually was

Home's stat strip showed **0 steps, always**, while Distance and Energy beside it were fine. Nothing
had been "disconnected": the projection, the write path and the store were all correct, and both
defects behind it were in one place — the Health Connect **read**.

Verified against the maintainer's live store (33 `tenshu/health/dailyActivity` rows pulled off the
device): the recent days carried `steps = 0` with `distanceM` and `activeEnergyKj` populated, and
the zeros started exactly where the step read ran out of budget.

### 19.2 Defect 1 — the ceiling drops the newest, not the oldest

`readAll` pages a record type and stops at `MAX_RECORDS_PER_TYPE` (10 000) — a deliberate OOM
guard, and it *bites on a real device*: 30 days of step records is ~10 000 records over 10 pages.

⚠⚠ **Health Connect pages oldest-first by default** (`ReadRecordsRequest.ascendingOrder = true`),
so the records the ceiling discarded were the **most recent** ones — the last four days, including
today. `readDailyActivities` then wrote today's row with `steps = 0`, and because `0` is the
schema's "unknown" sentinel, [`DailyActivityFold`](#14-dailyactivity-is-date-keyed--partial-sources-must-fold-not-clobber)
correctly kept it out of the way of nothing — there was no better value to keep. Distance and energy
have an order of magnitude fewer records, never hit the ceiling, and so were never affected: that
asymmetry is the whole reason this looked like a *steps* bug rather than an import bug.

⚠ The log line had said *"older records in the window were skipped"* since the ceiling landed. It
was never true. The fix (`ascendingOrder = false`) makes the code match the sentence, and nothing
downstream depends on record order — `StepBoutDetector.detect` sorts, every other consumer groups
or maps.

### 19.3 Defect 2 — summing raw records double-counts every writer

Found while verifying the first fix: the daily figures were also **~2× reality** (20–45 k steps a
day against 4–18 km of distance). A phone usually has more than one writer of `StepsRecord` — here
Google Fit *and* the platform `android` source, both mirroring the same pedometer — and a record
read returns every record from every writer, verbatim and by design.

Health Connect resolves precisely this with its per-data-type **app priority list**, but only in the
**aggregation** API. So the daily roll-up now calls `aggregateGroupByPeriod` over
`COUNT_TOTAL` / `DISTANCE_TOTAL` / `ENERGY_TOTAL` sliced by `Period.ofDays(1)` instead of summing
records. That also removes the ceiling from this read *by construction* (one bucket per day, whatever
the record count) and made the import's daily pass ~10× faster on the device (3.1 s → 0.36 s).
`AggregationResult.dataOrigins` keeps the writer diagnostic §15.5's Google Fit notice depends on.

⚠ An aggregation failure yields **no rows**, deliberately not a fallback to the raw sum: the raw sum
is the inflated figure this change exists to stop reporting, and a day with no stored aggregate
degrades to the projection's workout-derived roll-up.

⚠⚠ **The aggregation window must be anchored to local midnight** — `Period` slicing counts days from
wherever the filter starts, so the obvious `TimeRangeFilter.between(windowStart, now)` produces
15:12→15:12 buckets that straddle two calendar days, label each with the earlier one, and give
**today no bucket at all**. Caught by re-reading the store after the change, not by the compiler:
the import logged a healthy `30 day(s) over 30 aggregate bucket(s)` while today's row silently kept
its previous value.

### 19.4 Scope + verification

`HealthConnectImporter` only (androidMain) — no schema, wire, op or format movement, so House Rule
#9 does not fire, and the store self-heals on the next import (`DailyActivityFold` takes any
non-zero incoming figure over a stored one). Framework-level code with no unit-testable seam, so the
gate is the device pass: store dumped before and after, today's row went `steps = 0` → `7 840`,
per-day stride ratios landed at a plausible 0.5–0.76 m/step across the window, and Home renders the
figure.

### 19.5 The second victim: auto-detection was blind to the last four days

Home was the *reported* symptom, but `readStepBouts` — the tier-1 detector's whole input (§9.2) —
reads `StepsRecord` through the same `readAll`, hits the same ceiling, and was therefore being fed
the **oldest** slice of the window. Measured on the device by temporarily logging the retained
sample and bout range, and mutation-checked by flipping the ordering back:

| | sample / bout window | newest three bouts |
|---|---|---|
| `ascendingOrder = true` (before) | 2026-07-12 → **2026-08-07** | Aug 7 · Aug 7 · Aug 6 |
| `ascendingOrder = false` (now) | 2026-07-17 → **2026-08-11** | **today** ×3 (5 min/1 220 st, 5 min/1 065 st, 7 min/1 444 st) |

⚠⚠ So a walk taken **today could never be auto-detected** — only one from a week ago — and the
feature would have looked merely conservative rather than broken. Nothing in the pass said so: it
logged `162 bout(s) from 10000 step sample(s)` either way, and the promotion outcomes it *does*
narrate (`auto-detect: refused 18 of 190 …`) describe Timeline traces, a different tier. **A count
is not a window**; the only way to see this was to log the range.

⚠ The ceiling still bites, just at the correct end: 10 000 step records spans ~25 days, so the
oldest five days of the nominal 30-day window are now the ones dropped. That is the right trade for
a detector looking for recent activity — and it is what `readStepBouts`'s KDoc has always claimed —
but a backfill genuinely has not seen everything and must not claim to.

## Web scope

**Status:** DECIDED 2026-10-28 (see "Decided" below). Was a question PR (`web-app-suite.md` §8.1, Wave 3 Fit run A); run B cites the answers.
Run A, the SDK boundary and bridge, builds:

- the dashboard's daily aggregates, the paged workout history, workout detail and its streams (HR
  series and route points, as data for W-MAP), goals, profile, and the data-source rows read-only;
- the pure-data writes: goals, units, name, birth date, sex, a hand-entered height or weight,
  deleting a workout, and a GPX / TCX import from bytes the app hands over.

What every item shares: a browser tab is a **foreground-only sync peer**. It runs while the tab is
open and visible (Page Visibility; a hidden tab's timers are throttled to about once a minute and a
frozen or discarded tab runs nothing), it has no OS sensors and no health store, and per
`web-app-suite.md` §7 an app makes **no third-party fetch**. The browser runtime installs no health
background work (`HealthPlugin`; `NoBackgroundWorkInATabTest`).

### 1. Record a workout without GPS (timer, sets, manual HR)

- **Can:** keep a timer in a foreground tab and keep the screen on with the Screen Wake Lock API
  (`navigator.wakeLock.request('screen')`; Chromium and Safari 16.4+, Firefox 126+). It must be
  re-requested on every `visibilitychange` back to visible, because the browser releases it when the
  tab is hidden. Heart rate could come from a chest strap over Web Bluetooth (the standard GATT Heart
  Rate service, `0x180D`), but only in Chromium, never in Safari or Firefox, and only after a user
  gesture each session. Typing an average HR by hand works everywhere.
- **Cannot:** survive the tab being hidden, the laptop sleeping or the phone locking. Timers in a
  hidden tab are throttled and a frozen tab runs nothing; there is no foreground service, no
  ongoing notification with pause/stop (§13.1), and no crash journal (§13.5), because nothing runs
  after the tab is gone. No step counter, no motion sensors (the Generic Sensor API is Chromium-only,
  needs a secure origin and a permission, and has no pedometer).
- ⚠ A recording is a **write path**: natively it goes through `addWorkout` and the durable outbox
  (§9.13), which is what keeps a save from being lost when Control is away. A tab has no outbox; it
  would write straight into the runtime and lose an unsaved session on any reload.
- **Recommendation: build reduced, later.** Not a live recorder: a **"Log a workout"** form (type,
  start, duration, optional distance / energy / avg HR) that writes one summary `Workout` with
  `source = "Manual"`. It has no timer, so nothing is lost when the tab closes, and it needs no
  sensor. Drop the live timer and Web Bluetooth HR for v1.

### 2. Record a workout with GPS

- **Can:** Geolocation `watchPosition`, after a permission prompt, on a secure origin, while the page
  is visible. On a phone browser the fix is real GPS; on a laptop it is Wi-Fi / IP-derived (20 m to
  several km, often no altitude or speed).
- **Cannot:** keep tracking when the tab is hidden or the screen locks. Mobile Safari and Chrome both
  stop delivering positions to a background or locked tab. There is no background geolocation and
  no Background Sync for location. A run with the phone in a pocket and the screen off would record
  the first few hundred metres and the last few, joined by a straight line. A Wake Lock keeps the
  screen on but not in a pocket, and drains the battery.
- **Recommendation: drop.** A GPS route on web comes from a GPX / TCX import (item 4) or from a
  phone recording that syncs.

### 3. ⚠⚠ Rollups and latest samples on web

`HealthService.latestSamples` (`LatestSample`) and `workoutActivityRollups`
(`WorkoutActivityRollup`) are **locally derived and never synced**. Both schemas carry no
`Syncable` trait: every device derives its own rows from the `HealthSample` / `Workout` saves it
observes. That changes the three options this brief named:

1. **Read what the phones last synced: not possible.** The rows never leave a phone. In a tab both
   relations are empty, so a reader would show "no weight, no height" for a user who has years of
   them.
2. **Derive in the tab without writing.** Read a bounded window of the source relation and fold it
   on the client, the way `FitProjection.latestPerMetric` already folds a list.
3. **Run the maintainers** (`initHealthService`).

⚠ Correction to `HealthPlugin`'s KDoc: it says running the maintainers puts "a second writer on rows
every phone derives for itself". Because the rows never sync, a tab would be writing its **own** local
rows, not a second copy of a phone's. The real cost of option 3 is the one it also names: background
work. `LatestSampleMaintainer.backfillIfEmpty()` and `WorkoutActivityRollupMaintainer.deriveIfStale()`
scan the whole sample and workout history **at runtime start** and write the derived rows. In a fresh
tab that is every time (the store starts empty, or is evicted, `drive-web-parity.md` §1.2d), over up
to ~4.5 k workouts and tens of thousands of samples, competing with the first pull on the page's one
thread. Each sync-ingested sample then fires the maintainer too. `ImportRunRetentionObserver` is a
separate case: it deletes rows and emits tombstones, which are real writes that leave the device.

What run A's surface needs from them:

- **Profile** reads height and weight from the newest `Height` / `Weight` sample, which natively is
  `latestSamples`.
- **Nothing else.** The dashboard's aggregates are `DailyActivity` rows (synced) plus a date-bounded
  workout read, and History pages the `workouts` relation. Neither reads a rollup.
  `workoutActivityRollups` has no consumer in the Fit app at all; Browse's categories and the
  per-type counts are not on run A's surface.

**Recommendation: 2.** Run A builds it, because the alternative is a profile that reads blank:
`profile` reads each metric with `healthSamples.searchAll { metric equalTo X }.sortByDescending { time }.take(1)`.
That is the bounded per-metric shape `FitProjectionRepository.sampleInputsFor` already uses, with a
window of one, and it writes nothing. If the Browse categories come to web later, the same per-metric
read covers their teasers (five metrics, one row each). Keep `initHealthService` out of the tab;
`NoBackgroundWorkInATabTest` keeps the maintainers named as forbidden. If you prefer option 3, the
change is one line in `HealthPlugin.initialize` plus moving the names in that test, and the profile
read would switch back to `latestSamples`.

### 4. Import formats

- **GPX / TCX: build** (run A does). `:fit-shared-file`'s `ActivityFileParser` runs on JS over the
  browser `DOMParser` (`JsXmlReader`, §9.10). The app hands the bytes over from an
  `<input type="file">`; nothing is fetched. ⚠ The parser folds every failure into an empty result
  (`parse` never throws), so the bridge tells the cases apart itself before parsing: unknown
  format, malformed XML, and well-formed XML with no track or activity each get their own typed
  error, so a broken file does not read as "imported nothing".
- **Binary `.FIT`:** the only decoder is the Garmin FIT **Java** SDK (`FitBinaryDecoder`, desktop
  only). Options:
  1. **drop** on web, with a typed `error_fit_binary_unsupported` that names the format;
  2. **port** a decoder to Kotlin common. The protocol is published and the subset Fit reads
     (`session`, `record` messages: time, position, HR, distance) is small, but it is a new parser
     to own, test against real device files, and keep in step with the Java one;
  3. use a JS FIT library from npm. That is a third-party dependency in the bundle, and the licence
     and supply chain need review.

  **Recommendation: 1 now, 2 later.** Garmin Connect, Strava and most watches also export GPX or
  TCX. If `.FIT` turns out to matter, port it into `:fit-shared-file` so desktop, iOS and web share
  one decoder.
- **Google Takeout zip:** needs a JS unzip. Kotlin has none in common; the native readers are
  `java.util.zip` and a hand-written iOS reader (§16.2). A real 10-year export is ~96 MB (§9.6b).
  A browser can read it member by member (`Blob.slice` plus `DecompressionStream('deflate-raw')`,
  Chromium 80+, Safari 16.4+, Firefox 113+), so memory need not hold the whole archive. But it
  needs a zip central-directory reader (new code, or a dependency), and the import writes tens of
  thousands of rows from a tab that must stay open and visible for minutes. Natively that import
  has an `ImportPacer` because it once OOM'd Control (§9.6b).
  **Recommendation: drop for v1**; Takeout stays a phone or desktop import. Revisit only together
  with `takeout-import-and-recall.md`, which already owns "the zip is the unit of import".

### 5. Data sources / Health Connect settings on web

- **What the rows are:** the `HealthSource` registry (one row per connected device × platform:
  "Health Connect on Pixel 8") and the `HealthImportRun` history. Both sync, so a tab can read them.
  `DataSourceProjection` is pure and owns every "what does this row say" rule; the projection
  refactor PR moves it into the JS-compiled `:fit-projection`, so a web reader says exactly what
  native says.
- **Cannot:** connect, grant, re-grant or run any of them. Health Connect and HealthKit are
  on-device APIs with no web surface, so "Import now", the history-access grant (§15.3) and the
  sharing wizard (§15.6) have nothing to call in a browser.
- **The auto-import switch** (`setSourceAutoImport`) is a synced write to a phone's registry row, so a
  tab *could* flip it, and the phone would honour it on its next pass. But the user would be
  changing a phone's behaviour from a page that can't show what that phone is doing.
- **Recommendation: show read-only** (run A builds the read). The rows, their last run and their
  status, plus a footnote that sources are managed on the device. No switch, no "Import now", no
  wizard. The **Tenshu Health** row (DS-6) reads `FitPreferences.AutoDetectActivities`, which is
  REALM-scoped and synced, so web shows it too; it says what the phones will do, and changing it
  stays on the phone for v1.
- ⚠ **This browser's own file imports are not recorded as runs** in run A. Native records every file
  import as a `HealthImportRun` (`KIND_TAKEOUT`, `WINDOW_FILE`), and `ImportRunRetentionObserver`
  trims each (kind, device) group to its cap, but only for `USER`-origin saves on the writing device.
  A tab does not install that observer (item 3), so web-written runs would never be trimmed. Options:
  record them and install only the retention observer (it is save-triggered, not a scan); or do not
  record them. **Recommendation: record them, with the retention observer allowed** (it runs only
  inside a run save), as a follow-up once you agree. Until then a web import is visible as its
  workouts, not as a run.

### 6. Anything else

**6a. Units.** Units are a platform preference (`OwnerPreferences.MeasurementSystem`, §18.13), so
they sync and run A exposes them on `profile` and as a write. **Build.** Run B formats with them; the
one web nuance is the first-run default before the realm has pulled. The proposal is metric, as
natively (`FitProfile.units` is the one non-null field), never a guess from the browser's locale.

**6b. Edit a workout.** Native Fit has **no** edit: `FitRepository` has `addWorkout` (the recorder's
save) and `deleteWorkout`, and no screen changes a stored workout's title, type or times. Run A
builds delete only. **Recommendation: drop edit**, unless native gets it first; an edit only on
the web would be a second writer whose rules (does a retitle keep `source`?) exist nowhere else.

**6c. The sharing wizard and onboarding.** Both are about granting a platform health store. **Drop**:
a tab boots straight to the dashboard, as a real native store does (`onboarded()` is `true`).

**6d. Auto-detected workouts** (`fit-automatic-activity-detection.md` FA-1/FA-2). Promotion reads
Timeline's traces and writes `Detected` workouts; supersession deletes them when a real one lands.
Both are writes a phone runs at open/refresh. **Drop on web** (no promotion, no supersession); a
detected workout that a phone promoted shows in History like any other, with its `Detected` source.

**6e. Browse categories** (sleep, body, vitals, nutrition, cycle, medical). Not on run A's surface.
Their reads are all synced rows except the teasers' latest samples (item 3). **Build later, read-only**,
once item 3 is answered.

**6f. Day boundaries.** Native groups days in the device zone. On web, run A takes the zone as an
argument: blank means the browser's zone (as Calendar and Timeline do), and an unknown zone is
`error_fit_invalid_zone`, never a silent UTC. A workout at 23:30 and one at 00:30 land on different
days in the reader's zone; that is the contract test.

### Decided (maintainer, 2026-10-28)

All recommendations accepted:

- **1** No live recorder. Later, a reduced **"Log a workout"** form writing one summary `Workout`
  (`source = "Manual"`); no timer, no Web Bluetooth HR.
- **2** No GPS recording on web; routes come from GPX/TCX import or a phone.
- **3** Option 2: derive in the tab without writing (`profile` reads the newest sample per metric);
  `initHealthService` stays out of the tab. Correct `HealthPlugin`'s "second writer" KDoc: the rows
  never sync, so the cost is the start-up scan, not a second writer.
- **4** GPX/TCX yes, with typed errors per failure kind. Binary `.FIT`: typed
  `error_fit_binary_unsupported` now, a common-Kotlin port into `:fit-shared-file` later if it matters.
  Takeout zip: phone/desktop only.
- **5** Data sources read-only, with "managed on the device". Recording web imports as
  `HealthImportRun`s with only the retention observer installed: accepted, as a follow-up.
- **6** Units: build (synced; metric before the first pull). No workout edit. No onboarding or sharing
  wizard. No auto-detect promotion or supersession. Browse categories later, read-only. Days group in
  the reader's zone; an unknown zone is a typed error.

### As built (run A)

- ⚠ **Import ids collide, natively too.** A GPX workout's id is `gpx-<first fix ms>`, so two
  different files whose first fix is the same millisecond are one workout: the second import
  upserts over the first, silently. The same holds for two `<trk>` elements in one file with the
  same start. The web answer lists each **stored** id once, carrying the write that won, so it
  never claims two workouts where one is stored. Native behaves the same way and was not changed.
  A real fix needs an id that is not derived from the start time alone, which would make a
  re-import of the same file a duplicate.
- ⚠ **A route import can be saved with its upload still pending.** Offline, or with the relay
  unreachable, `RecordWorkout` saves the row and the local streams blob, and journals the upload,
  before the emit fails. On web that failure escapes the op (`tech-debt.md` "A JS fetch failure
  escapes the content emit as an op failure"). So the import answers `ok` with the id in
  `streamsPending`: saved, readable on this device, and uploaded by a later sync tick. A retry is
  safe and queues nothing extra. A workout that did not save stops the import with its error,
  naming the ones from the same file that did.
- **Write domains.** Goals take native's stepper bounds (5–240 active minutes, 1 000–30 000
  steps). Height is 1–300 cm, weight above 0 and at most 1 000 kg, and a birth date runs from
  1900-01-01 to today. Native has no caps on the last three. The gateway also refuses any integer
  outside int32 before it reaches a Kotlin `Int`.

### As built (run B, `web/fit`)

- **Screens.** Dashboard (today against the goals, 7 / 30 days with the period before for the
  change figure, 12 months without it: two years exceed the SDK's 400-day window), History
  (virtualised over `w1.` keyset pages of 50, head re-read on `fit.workouts.changes`), workout detail
  (stats, HR curve, route map, delete with a confirm), GPX/TCX import, and Profile (goals, personal
  data, units, the data sources read-only). Charts are the app's own SVG over a port of `ChartMath`.
- ⚠ **A day the aggregates read does not name is a zero, not a gap.** `FitProjection.dailyAggregates`
  answers only days with a stored row or a workout; the dashboard fills the window (`windowDays`).
  Only a SUCCESSFUL read is filled: a failed one renders by `kind`, never as zeros.
- ⚠ **"Today" is the browser zone's calendar date**, the zone the SDK groups in (`zone: ''`). Native
  Home takes the last aggregate, which is yesterday on a day with nothing recorded yet; the web shows
  today's zero instead.
- ⚠ **Workout streams on web needed the writer.** The JS runtime's `ExternalPropertyWriter` fan-out
  lacked `WorkoutStreamsPropertyWriter`, so a phone's content-plane route and HR never resolved in a
  tab and a web import journalled entity-keyed. Wired in run B (`WorkoutStreamsOnWebTest`); the pass
  against a real relay is still owed.
- **The route map** is `<TenshuMap>` in its own lazy chunk: the dashboard and History never load
  map-kit or MapLibre (`bundleSplit.test.ts`). Tiles are live since W.1; a map that
  cannot draw says why (tiles failed, no region, no WebGL). A workout without a route shows no map.

## Version

v0.55 · 2026-08-11 · **§19 — Home always read 0 steps: the record ceiling truncated the NEWEST
records.** Not a disconnection — the projection, write path and store were all correct. ⚠⚠ Health
Connect pages **oldest-first** by default, so `readAll`'s 10 000-record OOM guard (which *is* reached
in a 30-day step window on a real device) discarded the last four days including today;
`readDailyActivities` wrote today's row with `steps = 0` and Home read that forever. Distance and
energy have far fewer records, never hit the ceiling, and so were fine — which is what made it look
like a steps bug. ⚠ The log line claiming "older records were skipped" had never been true.
Second defect, found while verifying the first: raw record sums **double-count every writer** (Google
Fit *and* the platform `android` source both mirror the pedometer) — daily figures were ~2× reality.
Health Connect dedupes via its app priority list **only in the aggregation API**, so the daily
roll-up moved to `aggregateGroupByPeriod` (also removes the ceiling by construction; 3.1 s → 0.36 s).
⚠⚠ Its window must be anchored to **local midnight** or `Period` slicing straddles calendar days and
gives today no bucket — caught by re-reading the store, while the log claimed 30 healthy buckets.
Device-verified: today `0` → `7 840` steps, 0.5–0.76 m/step across the window.
⭐ **§19.5 (added 2026-08-11, measured after the merge): auto-detection was the second victim** —
`readStepBouts` rides the same ceiling, so tier-1 detection had been fed the OLDEST slice and a walk
taken *today could never be detected*. Mutation-checked on device: bouts `Jul 12 → Aug 7` before vs
`Jul 17 → today` after, newest three now all from today. ⚠⚠ **A count is not a window** — the pass
logged `162 bout(s) from 10000 step sample(s)` either way; only logging the range showed it.

v0.54 · 2026-08-09 · **§18.14 — Profile gets a real top app bar; the identity block goes.** Fit's
five tab screens had **no app bar at all** (bare `Text` over a `verticalScroll`), so they had no
container — M3's actual mechanism for separating a bar from content scrolled under it — which is why
a clipped row read as colliding with the title. Now `LargeTopAppBar` +
`exitUntilCollapsedScrollBehavior` in a `Scaffold`, transparent container + hairline divider only
once content is beneath it. ⚠⚠ **Measured, and it overturned three guesses:** the bar displaces NO
content (first row lands on the same pixel as before — the "huge padding" was the tonal band
*painting* chrome that was always there); `expandedHeight` does NOT move the expanded title (112.dp
and 88.dp are identical) because M3 reserves a fixed **64.dp collapsed-title row** above it, so the
band is **structural** and **a large bar cannot have a vertically centred title** — no parameter
does that, expressive adds *horizontal* alignment only. ⚠ The stable bars DO take heights, via a
second overload. ⚠ `LargeTopAppBar` keeps **two** title nodes in the semantics tree, so
`onNodeWithText(title)` fails. ⚠⚠ **Expressive is compiled into material3 1.9.0 but `internal`** —
JetBrains keeps it on the alpha channel; ⚖️ not taken (an alpha across five apps for a subtitle slot,
against this repo's CMP-pin history). Identity header deleted: the circle was never an avatar
(`name.take(1)` on a tint), the subtitle duplicated two rows *across two different stores*, but the
name was load-bearing — so **Name moved to a Personal data row**, or deleting the header would have
silently removed the only way to set it. ⚠ Profile only; four tab screens still hand-rolled.

v0.53 · 2026-08-09 · **§15.6.7 — the sharing wizard, BUILT (W1–W3).** ⚠⚠ **Two ordering traps, both
caught by tests rather than by a device.** (1) "Google Fit just became allowed" cannot be derived by
reading the repository immediately before re-probing it — that holds only while the repository has
not updated yet, true in production and false against a settable fake; detection moved **into the
collector**, comparing successive emissions. (2) The confirmation must be cleared on the **`Running`
→ not-running transition**, never on "the status is completed": when the grant lands the last import
is *already* completed from before it, so the naive version cancels the confirmation in the same
breath as raising it — and the transition form also survives a re-import returning a value equal to
the last. The three ⚖️ open questions resolve as: resume re-probes **permissions only** (an import
per return-from-settings loops against `HealthImportBootstrap`), W1 **sits beside** onboarding, and
E′ needs no manual route (Profile's Takeout row is already unconditional). ⚠ A failed probe leaves
the previous verdict standing — the default reads as "nothing to fix" and would close the wizard
mid-flow. ⚠ §15.5's notice now hands off to the wizard (*Fix this*) instead of jumping into Health
Connect: that jump was one-way and hid every other unmet step. **Verified on device**: opened at
`AllowReading` not `AllowGoogleFit` (ordering holds), and an out-of-band grant + return advanced it
with no user assertion. A render test pins that the copy never quotes Health Connect's own labels.
W4 (writer list) deferred. No fmt bump.

v0.52 · 2026-08-09 · **§15.6 — the sharing wizard: DESIGN ONLY, nothing built.** What comes after
§15.5's notice opens a door the user then has to walk through alone. ⚠⚠ **One probed fact sets the
whole design**: the per-app deep link (`MANAGE_HEALTH_PERMISSIONS`) is guarded by signature-level
`GRANT_RUNTIME_PERMISSIONS`, so we *cannot* land the user on Google Fit's permission page — correct
platform behaviour, since otherwise it is a phishing primitive. ⭐ We cannot shorten the journey,
but we can remove the need to describe it: the wizard is a **verifier, not a tutorial**, advancing
on `checkPermission` re-probes at `ON_RESUME` rather than on the user asserting they did something.
Click-by-click copy rejected on three independently sufficient counts (goes stale · cannot be
localised honestly against Health Connect's own strings · untestable). ⚠⚠ **Step D must not block
on E** — a grant does not make data appear, Fit syncs on its own schedule, so a spinner there is a
hang. ⚠ Self-skipping per the `OnboardingStep.DeepHistory` precedent; A and E′ must be able to
*end* the flow. §15.6.4: the probe is package-agnostic, so it generalises to a **writer list**
(Samsung Health and `com.oplus.healthservice` are on the same device) — a fixed `<queries>` set,
never discovery, read locally and never synced (A34). Lives as the existing onboarding flow made
re-runnable. Phasing W1–W4 + three open questions in §15.6.6.

v0.51 · 2026-08-09 · **§15.5 — the Google Fit notice, and the wrong version of it I shipped first.**
"Import your full history: 30 items, one workout" turned out to be truthful — `writers=[]` for
exercise — so the import now *says* why. ⚠⚠ **The first cut asserted Google Fit "does not share
with Health Connect… not a permission you can grant", and the maintainer's push-back refuted it in
one `dumpsys`:** Fit **declares** `WRITE_EXERCISE`/`WRITE_STEPS`/`WRITE_DISTANCE`/… and holds
**none** granted — the integration exists and ships switched off. ⚠⚠ Two consents, never conflate
them: ours to READ (14/14 granted, fine) vs Fit's to WRITE (all denied). Now two verdicts with
different remedies — `GoogleFitNotPermitted` → *Open Health Connect*, `GoogleFitNotSharing` →
*Takeout*. ⚠ probes `WRITE_EXERCISE` specifically (steps-only would still show an empty history)
and **fails open**; writers outrank the probe; never a row count. ⚠ `<queries>` gates both
`getPackageInfo` and `checkPermission`. Bundled drive-by on **Profile's header** (§18): the gap
below the title lived *inside* the `verticalScroll`, so it scrolled away and rows ran flush into
the title at any scroll position — moved outside, matching History and Browse, which had it right.
Notice verified on the reporting device (`notice=GoogleFitNotPermitted`, rendered, tap resolved to
the Health Connect controller); ⚠ the header fix is not yet visually re-checked. No fmt bump.

v0.50 · 2026-08-01 · **§18.13 — FP-6: Timeline reads `owner.units`. §18 COMPLETE.** New
`LocalMeasurementSystem` in `:ui-core` (a `CompositionLocal` beside the theme — units are the
textbook ambient concern), `TimelineFormat.distanceParts(m, units)` gains `Feet`/`Miles`, and
`TimelineRepository.units()` reads the platform row through `OwnerProfile` — Timeline gains no
setting of its own. ⚠ The imperial changeover is **a tenth of a mile, not a mile**: the direct
mirror would print "1,847 ft" for what metric shows as "1.8 km". This is the payoff §18.2 predicted
for putting units at platform level — nothing in Timeline knows Fit exists. No fmt bump. ⚠ The
end-to-end visual is unverified (the emulator has no captured movement).

v0.49 · 2026-08-01 · **§18.12 — FP-5: the HealthKit demographics seed.** `readCharacteristics()` on
the importer seam (default: knows nothing) + a HealthKit implementation + a seed after every
import, so ⇒ **`AboutYou` now self-skips on iOS** as §18.6 described. ⚠⚠ **A one-shot marker, not
"fill if unset"** — the obvious rule silently re-fills a value the user deliberately cleared, which
FP-3 made reachable on purpose. ⚠⚠ New `readOnce` snapshot terminal on the preference client: both
`read().first()` (hangs on an empty realm) and `observe().first()` (reports its own seeded default)
would have overwritten real answers. Demographics ride the Body grant and are now named in
`NSHealthShareUsageDescription`. No fmt bump.

v0.48 · 2026-08-01 · **§18.11 — FP-4: iOS editor parity.** Swift-only. Every Profile row becomes an
editor and `LabeledRow` is deleted along with its "none of them is editable in v1" comment; the
About-you step gets a real screen instead of FP-3's pass-through. ⚠ **One sheet, two callers** —
`ProfileEditorSheet` takes a value bag and returns a single `ProfileEdit`, so onboarding did not
grow a second copy of five field editors. ⚠ `Kotlinx_datetimeLocalDate`'s Swift init is
`(year:month:day:)` despite the `monthNumber`/`dayOfMonth` property names, and a new Swift file is
invisible until `xcodegen generate` — both only `xcodebuild` catches. No Kotlin, schema, wire or
format change. ⚠ Interactive click-through pending (no `simctl` tap).

v0.47 · 2026-08-01 · **§18.10 — FP-3: the editors, and the onboarding step that asks only what it
must.** Six dialogs (goals / name / birth date / sex / height / weight) + a self-skipping
`OnboardingStep.AboutYou` after the import. ⚠ It asks the **calorie inputs only** — a name is an
input to nothing, so it stays a Profile edit rather than onboarding friction. Height's chevron
returns (FP-2 removed it) now that it has an editor; weight's history link moved **into** its
dialog rather than onto a hidden long-press. ⚠ Measurement entries are plausibility-guarded because
they **append to a series** — one bad reading would skew the Body chart forever. ⚠⚠ iOS had to be
handled here, not in FP-4: `OnboardingView`'s `default: ProgressView()` would have turned the new
step into an eternal spinner. No fmt bump (existing `WriteSetting` / `RecordHealthSample` ops).

v0.46 · 2026-08-01 · **§18.9 — FP-2: height joins weight in the health domain.** `Metric.Height` +
`HeightRecord` (Health Connect) / `HKQuantityTypeIdentifierHeight` (HealthKit) + the Profile join,
so both body values are now *read* rather than declared. ⚠ `readLatest` not `readAll` (an adult's
height is a constant — a series of it is noise); stored in cm with the unit on the sample, as weight
stores kg. Height rides the **Body** category so it inherits weight's consent, asserted both ways in
`HealthImportTest`. ⚠ **Height deliberately gets no chevron**, reversing §18.6: Body detail charts
weight only, so sending Height there would be the same lying chevron §18.1 removes — FP-3's editor
earns it back. **No fmt bump, and this was the phase to check**: `HealthSample.metric` is a
`string(...)` field, so a new *value* reshapes no composite (HR#9 untriggered).

v0.45 · 2026-08-01 · **§18 — Profile persistence + editors (scoped, no code).** On-device audit found
every Profile row carrying a `>` while one of seven is clickable, over values hardcoded in the *real*
repository (`FitProjectionRepository.kt:207-210`, byte-identical to the mock's). Scoped FP-1..FP-6
around three splits: **body measurements** (weight — already real synced `HealthSample` data; height —
a measurement our importer simply never asked for) are read from the store, never stored as
preferences; **the person** (name / DOB / sex / units) leaves Fit for `OwnerPreferences` in
`:preference-service`, because Drive already stores a self-name, the `ownerMetadata.displayName` seam
its KDoc defers to **does not exist**, and Timeline hardcodes kilometres; **goals** stay Fit's.
Acquisition is import-then-ask, not either/or — ⚠⚠ verified that **Health Connect 1.1.0 has no
demographics API at all** (1.1.0 sources jar) while HealthKit exposes `dateOfBirthComponents` +
`HKBiologicalSex`, so a self-skipping `AboutYou` onboarding step after the import shows nothing on
iOS and only the unfillable fields on Android. Sex → a closed four-value enum mirroring
`HKBiologicalSex`, stored as a token, named for the physiological use and never a gate (§12.9 holds).
⚠⚠ Unset must not render as today's fabricated defaults ⇒ nullable `FitProfile` in FP-1. ⚠ Fit is the
only app whose client never registered `preferenceServiceClientModule`. No schema/wire/fmt change in
FP-1 (⚠ FP-2's `Metric.Height` is the phase to check HR#9 against); A34 gains a demographics note.

v0.44 · 2026-07-28 · **§17 — the missing routes were two acquisition bugs, not a missing map.**
Health Connect never requested `READ_EXERCISE_ROUTES` (every session summary-only); Takeout TCX
fragmented one session into N workouts *and* read every lap's distance as `0.0` off the first
trackpoint. Adds the route grant + `routeOf` mapping, same-sport segment merging, and
`XmlNode.childText` (direct-child reads) across the JVM/iOS/JS readers. Verified against the
maintainer's real 2,689-file export: distance 0 → 2,244 sessions, map on 1,051 → 1,192 sessions,
10,745 rows → 3,002. No schema/wire/fmt change. ⚖️ Play declaration for `READ_EXERCISE_ROUTES` is a
release prerequisite; ⚠ re-import leaves old fragment rows behind.

v0.43 · 2026-07-27 · **§16 — iOS file import (the Takeout path iOS never had).** Google Fit feeds
neither HC nor HealthKit, so Takeout is the only route to real history — and iOS had no picker and a
null `fileParser`. Adds `IosArchiveReader` (central-directory ZIP + ZIP64 over `platform.zlib`,
memory-mapped, no third-party dependency) and `IosXmlReader` (`NSXMLParser`) — the second is what
makes *workouts* import rather than day-totals only — plus `FitSurface.importPickedFile`, a
SwiftUI `.fileImporter` in Profile (copy-while-scoped, per Drive P3a) and an explicit `.deepHistory`
case in `OnboardingView`. No schema/wire/fmt change. Green: 11 new `iosSimulatorArm64Test` cases
(zip round-trip incl. deflate + stored; parser assertions byte-identical to the web twin),
`fit-ios` xcodebuild, desktop + Android untouched. ⚠ On-device pass pending; ⚠ 12 iOS test failures
are pre-existing (verified at `6746dca4`).

v0.42 · 2026-07-27 · **§15 — full-history Health Connect import (+ the "History is empty"
diagnosis).** The reported symptom was *no workouts in Health Connect at all* (`writers=[]`; Google
Fit never writes to HC), not a sync or import defect. Chasing it exposed a real limit: we read 30
days and could not read more, because `READ_HEALTH_DATA_HISTORY` was undeclared and the platform
silently clamps without it. Adds `ImportWindow`/`HistoryAccess` through the importer seam, the
feature-probed optional grant, a self-skipping `DeepHistory` onboarding step and a permanent
Profile entry. No schema/wire/fmt change. Green: fit-shared-logic desktopTest 82/82, fit-shared-data
+ fit-shared-ui desktopTest, fit-android compileDebugKotlin, iosSimulatorArm64.

v0.41 · 2026-07-27 · **§14 — `DailyActivity` per-field fold (clobber fix).** A day's row is keyed
by date alone, so a steps-only Health Connect refresh was replacing a Takeout import's
distance/energy/active-minutes with zeros on the next launch. New `DailyActivityFold` applied at
both write seams (`RecordDailyActivity` + the bulk `RecordImportedHealth`) plus a
`DailyActivityMerger` registered in production wiring for the cross-device half. Rule: `0` means
"unknown", so only a non-zero value may overwrite. No schema/wire change, no fmt bump. Green:
`:health-service-proxy:jvmTest` 28/28; android/desktop/ios `control-shared-proxy` compile.

v0.40 · 2026-07-25 · **iOS journal + SwiftUI review surface (§13.5 iOS twin + §13.6 as-built) —
the §13 arc is platform-complete in code.** `IosRecordingJournal` (Application Support,
whole-snapshot atomic rewrite per 15 s flush — no torn tail possible; same lane/lifecycle as
Android) wired into `IosRecordingEngine` with `compareAndSet` recovery. `RecordView.swift` gains
the Reviewing branch: route sketch (reused `RoutePathView`), stat grid, title + activity picker,
trim card, Save/Discard-with-confirm, recovered note, and the previously-unsurfaced §9.13
saved-locally/save-failed alerts; Stop now parks the draft (one-shot `finish()` remains only as
the interface default). New `RecordUiState.reviewPreview` feeds Swift the trim preview across the
framework boundary. Green: fit-shared-logic/ui desktopTest, fit-android assembleDebug,
fit-shared-data iosSimulatorArm64, fit-ios sim xcodebuild. On-device pass (both platforms) = the
remaining gate.

v0.39 · 2026-07-25 · **RC-4 + RC-5 built (§13.4/§13.5 as-built) — the §13 completion arc is
code-complete.** RC-4: shared pure `PauseClock` (paused time never comes back on resume; drives
elapsed AND every sample's tSec so streams share the active-time axis), paused-gated
route/distance/steps with resume re-anchoring (no phantom distance jump), 50 m accuracy gate —
on BOTH real engines. RC-5: `RecordingJournalCodec` (line-oriented, torn-tail-tolerant) +
`RecoveredRecording.toDraft()` (commonMain, unit-tested) + Android `FileRecordingJournal` wiring
(15 s flush window; journal lives until commit/discard so review-crashes recover too; ⚠ single
`limitedParallelism(1)` I/O lane prevents a stop-flush racing a commit-clear into a duplicate
draft); recovered drafts flow through the ordinary review screen (trim card included) with a
provenance note. Threat-model A34 amended (in-flight journal = §9.13 outbox posture). iOS journal
+ iOS review UI = follow-up slices. Green: fit-shared-logic desktopTest (PauseClockTest +
RecordingJournalTest), fit-shared-ui desktopTest, fit-android assembleDebug, fit-shared-data
iosSimulatorArm64, fit-ios sim xcodebuild. On-device pending.

v0.38 · 2026-07-25 · **RC-3 built (§13.3 as-built)** — pure `IdleTrim.kt` in `:fit-shared-logic`:
rolling-centroid dwell detection (30 m radius, jitter-spike skip, ⚠ 10 m tight-radius boundary
anchor so the dwell→movement ramp survives; 45 s floor per end, all-idle → null) +
`RecordingDraft.trimmed()` as the single re-window/recompute both the review preview and the
commit share (`DraftEdits` gained the trim window; engines commit via shared
`RecordingDraft.applying`; steps deliberately keep the session total; iOS HK export window
shifts with a leading trim). Review screen gains the per-end Remove/Undo suggestion card under
the stat grid. `IdleTrimTest` (synthetic 1 Hz tracks) + all RC-1/2 gates green. On-device
threshold tuning pending.

v0.37 · 2026-07-25 · **RC-1 + RC-2 built (§13.1/§13.2 as-built)** — the notification is a control
surface (tap deep-links via a latched `RecordSurfaceRequests`; Pause/Resume/Stop actions; shade-Stop
parks the draft + posts an auto-cancel review cue — trampoline restriction; chronometer + throttled
distance/HR line; `START_NOT_STICKY`), and the engine finish is split `stop()/commit(edits)` with
`RecordingState.Reviewing(draft)` + `RecordSummaryScreen` (route sketch, stat grid, editable
title/activity, Save/Discard-with-confirm). `finish()` survives as an interface-default one-shot so
iOS Swift is untouched; all three engines implement the split; `start()` now gated on Idle (a start
over a draft clobbered it). Shell-level navigation off the `Reviewing` flip covers in-app and
notification Stop with one authority. Green: fit-shared-logic/ui desktopTest, fit-android
assembleDebug, fit-shared-data iosSimulatorArm64, fit-ios sim xcodebuild. On-device pending.

v0.36 · 2026-07-25 · **Workout completion designed (§13) + RC-0 notification-tap fix shipped** —
the recording notification's content intent gained `FLAG_ACTIVITY_NEW_TASK` (silently-rejected
launch = the "can't click it" report) + a distinct request code (collision with the import
service's filterEqual PendingIntent made it intermittent). Design locked for RC-1 notification
controls (tap→live screen, Pause/Stop actions, chronometer) → RC-2 `stop()/commit()/discard()`
engine split + `RecordSummaryScreen` (editable title, Save/Discard) → RC-3 `IdleTrimDetector`
suggestion (suggest-never-auto, destructive-at-save v1) → RC-4 honest stats (real pause, accuracy
gate) → RC-5 crash journal. No schema/format change in any RC slice. Green: `:fit-android:assembleDebug`.
On-device tap verify pending.

v0.35 · 2026-07-19 · **Cycle category — B3 complete** (§12 B3b) — `MenstruationDay` (date-keyed flow +
period-start) as a full 5-module `:health-service` entity + a **seventh** `RecordImportedHealth` param;
`TENSHU_STORAGE_FORMAT_VERSION` 5 → 6. The Cycle card is **data-driven** (present only when cycle data
exists, never sex-gated — inclusive, matching Apple/Health Connect) and shows a "Cycle day N" teaser + a
new `CategoryDetailMode.Cycle` log detail. Import from Health Connect (`MenstruationFlowRecord`/`PeriodRecord`
+ `READ_MENSTRUATION`) + HealthKit (`menstrualFlow` category samples + cycle-start metadata) + reference
seed. Compose + iOS render it. **With B3 done, all seven Browse categories are backed.** Green:
health-service-proxy jvmTest (new `RecordMenstruationDayUnitTest`), fit-shared-{data,logic,ui} desktopTest
(new cycle tests), fit-shared-data iosSimulatorArm64, fit-android assembleDebug, fit-ios sim xcodebuild.
On-device import pending. No TM change (A34).

v0.34 · 2026-07-19 · **Nutrition category** (§12 B3a) — product call: **build** Nutrition + Cycle (both
importable), not hide. B3a adds `DailyNutrition` (date-keyed energy kJ + hydration mL) as a full 5-module
`:health-service` entity mirroring `DailyActivity`, incl. a sixth `RecordImportedHealth` batch param;
`TENSHU_STORAGE_FORMAT_VERSION` 4 → 5. Projector derives the Nutrition teaser (`CategoryTeaser.Energy`,
kcal-rendered) + a daily-energy trend detail; import from Health Connect (`NutritionRecord`/`HydrationRecord`
+ perms) + HealthKit (`dietaryEnergyConsumed`/`dietaryWater`) + reference seed. Compose + iOS render it.
Green: health-service-proxy jvmTest (new `RecordDailyNutritionUnitTest`), fit-shared-{data,logic,ui}
desktopTest (new nutrition tests), fit-android assembleDebug, fit-ios sim xcodebuild. On-device import
pending. **B3b Cycle next** (data-driven card, not sex-gated). No TM change (A34).

v0.33 · 2026-07-19 · **B2b complete — trend line + empty-state prompt** (§12.7) — Body/Vitals history now
charts as a **min–max-scaled trend line** (`ChartMath.minMaxPolyline`, unit-tested; value-ranged axis, not
zero-anchored bars) above the list, on Compose (`SampleTrend`) + iOS (`SampleTrend.swift`); the VM state
carries `trend: List<NormalizedPoint>`. An empty Browse shows a **"Connect a data source"** prompt (while a
store-backed category is still empty) that jumps to Profile → Data sources — Compose `selectTop(Profile)`,
iOS a new `TabView(selection:)` (tag 4). Green: fit-shared-{logic,ui,data} desktopTest, fit-android
assembleDebug, fit-ios sim xcodebuild. Visual pass pending. No TM change (A34).

v0.32 · 2026-07-19 · **Live importers emit sample history** (§12 B2b) — the Weight + resting-HR importers
now emit the whole bounded window instead of one latest reading, so real device history reaches the B2
time-series store. **Takeout `DailyMetricsCsv`** (commonMain, tested) emits one weight reading per day
recorded — the load-bearing path, since Takeout is the only route to real Google Fit history;
**`HealthConnectImporter`** (Android) + **`HealthKitImporter`** (iOS) switch Weight + resting HR from
`readLatest` to the bounded `readAll`/`readAllSamples` (SpO₂/RR stay latest-only, not charted). B2b
remaining: trend-line viz + empty-state "import a source". Green: fit-shared-data desktopTest (new Takeout
series test), fit-android assembleDebug, fit iosSimulatorArm64 compile. On-device pending. No TM change (A34).

v0.31 · 2026-07-19 · **Browse Body/Vitals history — time-series `HealthSample`** (§12 B2) — `HealthSample`
re-keyed from latest-per-metric to a synthesized `sampleId` = `"$metric::$timeMs"`, so distinct-time
readings accrue (a same-instant re-import still folds); the relation-maintainer keys off the stored
`sampleId` field. `latestPerMetric` derives the teaser/current-value; new `sampleHistory(metric)` feeds a
Body/Vitals **History list** (Compose + iOS) under the current value. ⚠ `HEALTH_SAMPLE_FIELDS` must project
the new key field or the read drops every sample (the sibling-relation rule; caught by a test).
`TENSHU_STORAGE_FORMAT_VERSION` 3 → 4 (HR#9; relays redeploy). Reference importer + mock emit a weight +
resting-HR series. **B2b outstanding:** live importers still emit latest-only (real history needs a windowed
read), the history is a list not a trend line, and the empty-state "import a source" affordance. Green:
health-service-proxy jvmTest, fit-shared-{data,logic,ui} desktopTest, core-common desktopTest, fit-android
assembleDebug, fit-ios sim xcodebuild. No threat-model change (A34).

v0.30 · 2026-07-19 · **Browse category detail made per-category** (§12 B1) — the Steps-only category
detail is fixed: `Destination.CategoryDetail` carries a `@Serializable CategoryId`, threaded into
`CategoryDetailViewModel(categoryId, repo)`, which projects per metric — Activity steps trend, Sleep
per-night duration trend, Body latest weight, Vitals latest resting HR. New `CategoryDetailMode`
(Series/Latest/Empty) + `CategoryMetric`; Latest (Body/Vitals) shows the single reading + provenance,
no chart (their `HealthSample` store is latest-per-metric until B2). Compose + iOS SwiftUI both render
the three modes; `FitSurface.categoryViewModel(categoryId)` + `FitRoute.category(id)` threaded. §12
records the investigation + phases (B2 time-series store, B3 Nutrition/Cycle build-or-hide, iOS
`FitLocalRuntime` per §11.8). Shared-logic-only; no protocol/wire/store/threat-model change (A34).
Green: fit-shared-logic/ui desktopTest, fit-android assembleDebug, fit-ios sim xcodebuild.

v0.29 · 2026-07-18 · **Decision: EPC is the third-party pattern; first-party Fit belongs on route-1**
(§11.8) — the permanent "Can't reach Tenshu Control" banner was diagnosed on-device (iPhone 17e) as
`presence token only — Control is not hosting`: Control publishes the EPC invite only while its
"Connect an app" screen is open, so a co-resident first-party Fit on the *untrusted-app* protocol can
never stay connected. Process-lifetime EPC host **rejected** (first-party-only, doesn't fix suspended
Control, wrong primitive for third-party). Direction: `FitLocalRuntime` mirroring `DriveLocalRuntime`
(route-1 in-process over the App-Group replica); EPC narrows to third-party only. Same for
Timeline/Maps → build the first-party local-runtime once. Interim: honest banner copy. No code/TM
change (decision doc).

v0.28 · 2026-07-18 · **iOS Record gated on connection** (§11.7 follow-up) — `RecordView` shows a
"Not connected — open Tenshu Control" placeholder instead of the activity picker + Start when
`!FitLiveClientSession.isActive`, so a not-live Fit can't begin a fake mock recording; an in-flight
recording is untouched. SwiftUI-only; fit-ios sim xcodebuild green. No TM change.

v0.27 · 2026-07-18 · **iOS mock retired from the runtime — honest "not connected" state** (§11.7) —
`NotConnectedFitRepository` (empty reads + `controlReachable=false`) replaces `MockFitRepository` as
the iOS `FitSurface` default + teardown target, so a not-live Fit shows empty screens + a SwiftUI
`ControlNotConnectedBanner` instead of fabricated data (the `ImportStatus.Sample` lesson). The mock
stays for tests/previews only (`installMock`). Verified: `EpcFitClientTest` + umbrella
`iosSimulatorArm64Test` + fit-ios sim xcodebuild. No TM change.


v0.26 · 2026-07-18 · **iOS recorder R-iOS-6 — HealthKit write-back** (§11.5) — `IosHealthKitWorkoutWriter`
(`HKWorkoutBuilder`; ⚠ config member is `activityType`, deprecated `HKWorkout` init not K/N-exposed)
courtesy-exports a Fit-recorded workout to Apple Health, consent-gated (share sheet +
`NSHealthUpdateUsageDescription`); `IosRecordingEngine.finish()` fires it best-effort. Loop avoidance:
`HealthKitImporter` skips own-bundle-sourced workouts so an export isn't re-imported as a dup. Verified:
`IosHealthKitWorkoutWriterTest` 1/1 + fit-ios sim xcodebuild. Real HK write consent+device-gated →
R-iOS-4. No TM change. **All sim-buildable iOS recorder phases (1/2/3/5/6) complete.**

v0.25 · 2026-07-18 · **iOS recorder R-iOS-5 — background import refresh** (§11.5) — `BGAppRefreshTask`
driver (`FitBackgroundRefresh`: register at launch, arm on background) + `FitSurface.runBackgroundRefresh`
over an `EpcFitClient`-built refresh closure (recorder-outbox drain + `autoRefreshReady`-gated HealthKit
import); `UIBackgroundModes:[location, fetch]` + `BGTaskSchedulerPermittedIdentifiers`. ⚠ EPC-tier limit:
in the background Control usually isn't hosting → the refresh no-ops unless co-resident+hosting (true
unattended background wants route-1). Verified: `EpcFitClientTest` 6/6 + fit-ios sim xcodebuild (both
schemes). Live OS wake device-gated → R-iOS-4. No TM change beyond A34.

v0.24 · 2026-07-18 · **iOS recorder R-iOS-3 — durable outbox** (§11.3) — `NsFileOutboxStore`
(`:app-outbox` iosMain, `NSFileManager`, the iOS twin of the java.io `FileOutboxStore`) +
`iosRecordedWorkoutOutbox()` (`:fit-shared-data` iosMain, Application Support container); `EpcFitClient`
now binds it to `FitProjectionRepository`, so a finished recording journals before its `recordWorkout`
op and survives the iOS Control SIGKILL race — replayed by the existing launch/reachability drains.
Verified: `NsFileOutboxStoreTest` 4/4 + umbrella `iosSimulatorArm64Test`. Seam (no Swift change).
End-to-end SIGKILL-survival → R-iOS-4. No TM change beyond A34/A35.

v0.23 · 2026-07-18 · **iOS recorder R-iOS-2 — capabilities + JIT permissions** (§11.2) — recorder
usage descriptions (`NSLocationWhenInUse`/`NSMotion`/`NSBluetoothAlways`) + `UIBackgroundModes:
[location]` in `project.yml`; `IosRecordingEngine.armSensors()` no longer cold-prompts on Record-tab
entry (reflects authorization only); Location + Motion primed at **Start**, Bluetooth only on the HR
row's **Connect** action, each behind an in-app rationale (`RecordView.swift`). New additive
default-no-op `RecordingEngine.connectHeartRate()` seam so the BLE central (the Bluetooth prompt) is
created only on the explicit opt-in. Background location enabled only while recording. Verified:
iosSimulatorArm64Test + Android/desktop/JS compile + **fit-ios simulator xcodebuild**. Device prompt
sequencing → R-iOS-4. No TM change beyond A34.

v0.22 · 2026-07-18 · **iOS recorder R-iOS-1 — `IosRecordingEngine`** (§11.1) — the first-party iOS
recorder actual (`:fit-shared-data` iosMain) over CoreLocation (GPS route + haversine distance) +
CoreMotion `CMPedometer` (steps) + CoreBluetooth BLE HR strap (GATT `0x180D`/`0x2A37`, the same
profile Android speaks); mirrors `AndroidRecordingEngine`. `FitSurface` gains a
`RecordingEngineFactory` slot — `installRealClient` binds the real engine, `installMock` restores
the mock. Managers are lazy so construction is side-effect-free (assembly never prompts for
Location/Bluetooth). Verified on `iosSimulatorArm64Test` (compile + link + factory-swap test);
sensor I/O device-gated → R-iOS-4. No SwiftUI change (a seam); no threat-model change beyond A34.

v0.21 · 2026-07-17 · **batched import `RecordImportedHealth`** (§9.16) — every summary record of
an import in one op round-trip (dense-stream workouts stay per-record); closes the iOS
SIGKILL-vs-per-record-op race that aborted the P-iOS-2 E2E import.

v0.20 · 2026-07-18 · **auto-import gate `autoRefreshReady`** (§9.15) — the unattended launch
refresh runs only where it can succeed (HC present + fully granted); closes the grant-less
abort-every-launch loop AND a latent no-HC hazard (the fallback's `Sample` fixture would have
been auto-written into the durable, synced store). Manual imports deliberately ungated.

v0.19 · 2026-07-18 · **Control-unreachable signal + shell banner** (§9.14) —
`FitRepository.controlReachable()` derived from read/write outcomes + a slow probe (which owns
detection: dead streams hang silently, they don't fail); recovery re-subscribes the read streams
(epoch) + drains the recorder outbox; one shell-level banner. Live disable/enable round trip
passed on emulator. Closes the proxy move's silent-blank gap.

v0.18 · 2026-07-18 · **durable recorder outbox + save-outcome UX** (§9.13) — `:app-outbox` shared
module generalised from Timeline's visit outbox; recorder saves journal-then-drain through the
shared `HealthImportWriter` (`WorkoutSaveOutcome.Synced`/`SavedLocally`); Record screen gains
saved-locally + save-failed dialogs; `ImportedWorkout.steps` now persisted by the shared writer;
threat-model A34 amended (app-private journal).

v0.17 · 2026-07-13 · **Fit moved onto the Control proxy; `InProcessFitProxy` deleted** (§10) — the
app's in-memory `:health-service` store was losing every recorded workout at process death (History
empty while Home kept re-importing steps). Health data now lives in Control's durable, synced store,
reached over the client Fit already had. Verified on device (record → force-stop → History intact).
Threat-model A34 amended (as-built custody note).

v0.16 · 2026-07-11 · **recorder permissions — per-capability JIT + priming** (§9.8 UX refinement;
§9.11) — the batched all-at-once request on Record-screen entry (four cold OS dialogs) is replaced
with a `RecorderCapability` split (`Motion` = location+steps+notification, asked at Start; `HeartRate`
= Bluetooth, asked only when connecting a strap). Gate API `ensureGranted` → `isGranted(cap)` +
`request(cap, onResult)`; new `RecorderPermissionPrimer` in-app rationale precedes each OS dialog;
HR sensor row grows a *Connect* action so non-strap users are never asked for Bluetooth. Pure
UI/seam edit — no new deps, no schema/wire/manifest change, no TM change (A34). ⚠ Not machine-verified
(sandbox can't provision the JDK-17 toolchain — foojay egress-blocked, same as §9.10); CI must confirm
the `:fit-shared-ui`/`:fit-android` compile. On-device dialog sequencing pending.

v0.15 · 2026-07-09 · **web (JS) file-import parser — `js` target stood up** (P5.3e follow-up; §9.10) —
`js(IR){browser()}` added to `:fit-shared-logic` + `:fit-shared-data`; `JsXmlReader` (browser
`DOMParser`) binds the `XmlReader` seam as the web twin of `JvmXmlReader`, so the `commonMain` GPX/TCX
`ActivityFileParser` runs on the web (binary FIT stays JVM-only, degrades empty on JS). Namespace
divergence pinned (browser ns-aware/strict vs JVM ns-blind; real files declare namespaces so no
impact). `JsActivityFileParserTest` mirrors the desktop test with a namespaced GPX fixture. ⚠ Not
machine-verified (sandbox can't run Gradle — dist + Google Maven egress-blocked); CI must confirm the
first-JS-consumer `lifecycle-viewmodel` resolution. A web Fit *consumer* (React subdomain + SDK bridge)
is still unbuilt — the parser is web-ready, a web Fit app is not. No new deps, no TM change (A34).

v0.14 · 2026-07-09 · **iOS live-EPC data path — the Kotlin assembly seam** (§9.9) — `EpcFitClient`
assembles a real `TenshuClient` + `FitProjectionRepository` over the EPC channel (full schema incl.
`:health-service`), `FitSurface.installRealClient` swaps `fit-ios` off `MockFitRepository` with zero
SwiftUI change, `FitLiveClientSession` owns the channel lifecycle. The Fit twin of Drive's
`EpcDriveClient`/`installRealClient`. `:fit-shared-data` + health `-common`/`-client` added to the
umbrella iosMain (not exported). Green: `EpcFitClientTest` (assemble + swap + op-marshals-to-transport)
on `iosSimulatorArm64Test`; framework links + header exports additively. Live round-trip device-gated.
No new relay/TM change (A34). Incidentally fixed a latent `SyncPreferencesTest` inference break.

v0.13 · 2026-07-09 · **GPX/TCX parsing lifted to `commonMain` behind an `XmlReader` seam** (P5.3e;
§9.6) — the DOM is abstracted behind a DI-bound `XmlReader`/`XmlNode` interface (JVM = `javax.xml`
`JvmXmlReader`; web = future `DOMParser`; Android/iOS bind none), so `ActivityFileParser`'s GPX/TCX
logic moves up to `commonMain` (`kotlin.time.Instant` + `kotlin.math`). Binary FIT stays JVM-only
behind an optional `FitBinaryDecoder` seam. `expect`/`actual` rejected (would force a stub iOS
`actual`). Enabling step for a future web file-import path; the `js` target itself is deferred (needs
`:fit-shared-logic` JS-enabled + a web consumer). Green: desktop tests + desktop/Android/iOS-sim
compile; no new deps, no TM change.

v0.12 · 2026-07-08 · **Record-screen just-in-time permissions landed** (§9.8) — the recorder's
runtime permissions are requested on entering the Record flow (not at launch) via a
`RecorderPermissionGate` `expect`/`actual` (`:fit-shared-ui`; Android = `rememberLauncherForActivityResult`
+ `checkSelfPermission` fast path, desktop = no-op); `RecordPreScreen` requests on entry + re-arms
sensors on grant; launch-time request removed from `MainActivity`. APK assembles; both UI targets
compile. On-device dialog pending.
v0.11 · 2026-07-08 · **TCX + FIT dense-stream extraction landed** (P5.3d; §9.6) — the two
remaining desktop importers now fill the §9.7 route + HR streams: TCX from `<Trackpoint>`
positions/HR, FIT from per-second `RecordMesg` (semicircles → E7, window-partitioned per session).
All three file formats now produce full `Workout.Streams`; verified by extended encode→decode
round-trips. Desktop-only, no new deps, no threat-model change.
v0.10 · 2026-07-08 · **first-party Android recorder landed** (§9.8) — `AndroidRecordingEngine`
(GPS via `LocationManager`, BLE-HR via GATT `0x180D`, steps via `TYPE_STEP_COUNTER`, zero Play
Services) + `RecordingService` FGS + manifest perms; the write-path seam
`FitRepository.addWorkout(workout, RecordedTrack?)` (neutral capture model in `:fit-shared-logic`)
maps real GPS → the §9.7 `Workout.Streams` blob; `RecordingEngineFactory` DI seam (desktop/iOS keep
the mock). APK assembles + `recordedTrackPersistsRealRoute` green; sensor I/O on-device pending. No
threat-model change beyond A34.
v0.9 · 2026-07-08 · **workout route + HR stream substrate landed** (§9.7) — `Workout.Streams`
stored `TByteArray` property (the `File.Thumbnail` pattern, **not** the content plane — the
ADR-0017 analogy retracted) + `WorkoutStreams`/`WorkoutStreamsCodec` (`:health-service-common`,
E7 GPS + HR, versioned dep-free codec) + optional `RecordWorkout.streams` param; projector
decodes on detail read (`FitProjection.projectRoute` → abstract 0..1 polyline), History list
stays byte-less; desktop GPX importer fills real route + HR. The recorder prerequisite; Android
sensor capture is the next (device-gated) slice. No threat-model change beyond A34.
v0.8.2 · 2026-07-06 · **binary FIT import landed** (P5.3c; §9.6) — `FitDecoder` over the Garmin FIT
Java SDK (`com.garmin:fit`, desktopMain) maps `SessionMesg` → workout; `.fit` ext / `.FIT` magic
sniff; picker accepts `.fit`. Verified by an encode-then-decode round-trip (`FitActivityParseTest`).
GPX/TCX stay dependency-free; only FIT pulls the SDK, desktop-only.
v0.8.1 · 2026-07-06 · **desktop file-picker UI landed** (P5.3b; §9.6) — native File →
"Import activity file…" menu (`:fit-desktop`) → AWT `FileDialog` → `FileHealthImporter`; kept
desktop-only (no shared-UI/expect-actual change). `InProcessFitProxy` exposes `client`/`tContext`.
Compiles + boots; native dialog is manual. Binary FIT + web decoders follow.
v0.8 · 2026-07-06 · **desktop file import landed** (P5.3; §9.6) — `ActivityFileParser` (GPX/TCX →
`ImportedHealth`, `javax.xml`, no deps) + `FileHealthImporter` over the shared writer; fully
unit-tested (parser + parse→write→projector round-trip). Desktop picker UI + FIT + web decoders
follow.
v0.7 · 2026-07-06 · **Android Health Connect actual landed** (P5.2; §9.5) — `HealthConnectImporter`
over `connect-client` 1.1.0 (workouts / daily activity / sleep / body+vitals samples), bound on
Android with the permission contract; reference fallback when HC absent. Compiles + APK assembles;
on-device reads pending. `minSdk` 24 → 26.
v0.6 · 2026-07-06 · **live-import seam foundation landed** (P5.1; §9.4) — the `HealthImporter`
contract + `ImportedHealth` + shared `HealthImportWriter` + `ReferenceHealthImporter` (seeding is
now importing); repository import surface real; threat-model A34 (GDPR Art. 9). Real Health Connect
/ HealthKit / file importers are the on-device follow-ups.
v0.5 · 2026-07-06 · **Body / Vitals / Medical entities landed** (`HealthSample` + `MedicalRecord`
in `:health-service`; §9.3) — every Browse category now renders real backing data (bar Nutrition +
Cycle). Stored-entity roadmap complete; next is live import (P5).
v0.4 · 2026-07-06 · **second deferred health entity landed** (`SleepSession` nightly sleep + stages
in `:health-service`; §9.2) — the Sleep Browse category now renders a real "last night" teaser.
v0.3 · 2026-07-06 · **first deferred health entity landed** (`DailyActivity` steps/distance/energy
in `:health-service`; §9.1) — Home's step strip + Steps category now render real synced data.
v0.2 · 2026-07-06 · **projector + in-process bridge landed** (`:fit-shared-data`; §9) — the app now
runs over `:health-service` behind the `FitRepository` seam, seeded in-process.
v0.1 · 2026-07-05 · **Ph0–Ph8 complete** (UI-only v1). Handoff snapshot: Fit design v0.1
(2026-07-05), direction locked: single-ring Home, inline-Record NavigationBar, always-dark
live. As-built notes: the `:ui-core` `AdaptiveNavItem.emphasized` flag is the one shared-UI
change (additive, default-off); the localisation seam lives in `:fit-shared-ui`
(`FitFormatting`/`FitDates`/`CategoryText`) since it needs `stringResource`, keeping
`:fit-shared-logic` Compose-free. Next: on-device Android pass; then the real
`:health-service` projector swaps in behind `FitRepository` (§3 D1).
