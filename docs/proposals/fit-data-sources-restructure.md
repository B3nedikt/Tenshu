# Fit Data sources — a source is a synced row, an import is a recorded run

**Status:** v1.0 — **DS-1…DS-7 ALL SHIPPED (2026-08-13).** ⚠ Q2 is closed as
**unanswerable**, not solved — see §17. ⚠⚠ No visual verification on either platform yet. The registry, the
recording, the shared projection and both UIs are in. ⚠ **No visual verification of any of it
yet** — the tests assert the semantics tree, `--build-only` proves compilation, and Fit is an
un-built Android smoke canary.
Phase map §9; §10 is the DS-1 implementation handoff, §11–§17 the as-built notes (the
`sync-merge-strategies.md` §5b convention). Companion to
[`fit-app.md`](fit-app.md) (the recorder and import tracks this restructures the *surface* of),
[`fit-automatic-activity-detection.md`](fit-automatic-activity-detection.md) (whose `Detected`
provenance becomes a source row here) and
[`ios-fit-timeline-maps-parity.md`](ios-fit-timeline-maps-parity.md) (the SwiftUI twin, DS-5).

**Trigger (2026-08-13):** *"I am still not too happy about the 'Data Sources' setting in
'Profile', I think it is confusing… it seems cluttered… I want the batch import features split
out to a separate 'Batch import' section… Health Connect and Import your full history and Check
sharing setup should be merged… I think we should also show the Apple Health data here in the
data sources list, even though we can't really trigger it actively on android."* Extended
mid-conversation with: *"maybe even add our own 'Tenshu Health Service' as another Data Source."*

---

## 0. The finding that reframes the whole question

**Nothing about an import is persisted anywhere. There is no import history in the store, and a
"source" is not a thing the platform knows about — it is inferred from workout rows.**

Two concrete facts, both verified:

1. `FitProjectionRepository.kt:259-262` holds import state in a `MutableStateFlow<ImportStatus>`.
   It is process-scoped and resets to `ImportStatus.Idle` on every launch. So *"165 items
   imported"* is not a fact about the user's data — it is a fact about **this process**, and it
   vanishes on restart. There is no `lastImport` row, no run log, no byte count, nowhere.
2. The other rows in the section are **derived by scanning workouts' `source` field**
   (`ProfileViewModel.kt:116-131`). A source only exists once it has produced a workout, on
   *this* device.

That second fact is decisive for the Apple-Health-on-Android ask: it is not a UI gap. As long as
sources are inferred from local rows, an Android device **structurally cannot** know that an
iPhone imported from Apple Health last Tuesday. No amount of SwiftUI or Compose closes it.

So the work is **a schema change plus a rendering change**, and the schema change is small.

---

## 1. Why the section reads as cluttered

`ProfileScreen.kt:237-347` renders four categorically different things as visually identical
rows:

| Row | What it actually is |
|-----|---------------------|
| Health Connect | a **source** |
| Import from Google Takeout | an **action** |
| Import your full history | an **action** (and a permission grant) |
| Check sharing setup | a **diagnostic** |

Plus a `SourceNotice` card (`:259-285`) that is a fifth thing — an alert.

The reason the actions sit at top level is mechanical: **the Health Connect row has no
`onClick`.** There is nowhere for them to go, so they became siblings of the source they belong
to. Fix the source row's destination and three of the four rows have a home.

A second-order symptom worth naming: a user who only ever records in-app sees **"No sources
connected"** while holding a year of workouts, because `Recorded` / `Manual` / `Detected` are
explicitly excluded from the derived list (`ProfileScreen.kt:288`). The section claims the user
has no data sources when the app itself is one.

---

## 2. The model

Two new composites in `:health-service`, both `Syncable(SyncStrategy.Eager)`, both keyed
per-device — copying `IngestSource`'s established `"$kind@$deviceId"` shape
(`ingest-service/.../IngestSource.kt:110-134`).

### 2.1 `HealthSource` — one row per standing connection, per device

```
key           "$kind@$deviceId"
kind          HEALTH_CONNECT | APPLE_HEALTH        ← only these two (D11)
deviceId
deviceLabel   human name, stamped at register time (D3)
autoImport    boolean — the kill switch, and it crosses devices (D4)
lastRunAt?    denormalised so the list is one read (D5)
lastStatus?
lastItemCount?
```

**A `HealthSource` row means: a platform health store this device can import from, and can be
told to stop importing from.** That is the whole invariant, and it is why Takeout and Tenshu
Health are not rows (D11).

### 2.2 `HealthImportRun` — one row per import that did something

```
key           "$deviceId:$startedAt"
deviceId
kind          HEALTH_CONNECT | APPLE_HEALTH | TAKEOUT   ← Takeout runs exist; Takeout sources don't
startedAt, finishedAt
window        RECENT | FULL_HISTORY | FILE
status        SUCCEEDED | FAILED | PERMISSIONS_MISSING | NO_DATA
itemCount, workoutCount
byteCount?    file imports only (D6)
fileName?     file imports only
error?        typed code only — redaction by construction, A19
```

Three operations, all `IoOperation` + `TerminalOperation`:
`registerHealthSource(kind, deviceId, deviceLabel)`, `recordImportRun(…)`,
`setSourceAutoImport(kind, deviceId, enabled)`.

### 2.3 What this does *not* change

`Workout.source` stays exactly as it is. Per-row provenance and the source registry answer
different questions ("where did *this* row come from" vs "what is feeding this realm"), and
collapsing them is what produced the derived-list behaviour in the first place. The registry is
additive.

---

## 3. Decisions

**D1 — A source is a synced per-device row, not a local inference.**
The alternative (keep deriving, sync a summary) was rejected: a derived list cannot represent a
source that has produced nothing yet, cannot carry an enable flag, and cannot describe a device
you are not holding. All three are requirements.

**D2 — Reuse `IngestSource`'s *shape*, not `IngestSource` itself.**
`ingest-service` already has a per-device source registry with `enabled`, `lastRunAt`,
`lastStatus` and even a `KIND_VITALS` constant, and it is essentially unused (its only consumers
are its own proxy and the JS SDK). It was still rejected as the home: it is a *file-watching*
registry (`appId`, `watchedPaths`), its statuses are ingest-engine statuses
(`OUT_OF_SPACE`, `PAUSED_POLICY`), and adopting it would put Fit's health-source list under a
foreign service's version and migration chain for the sake of one boolean. Copy the shape,
including the `"a@b"` key and the `Syncable(Eager)` + LastWriterWins rationale in its KDoc.

**D3 — `deviceLabel` is stamped on the row, not joined from `:identity-service`.**
`identity-service`'s `Device.name` is the "right" name, but Fit depends on neither
`:identity-service` nor its client module, and a join makes the row un-renderable whenever the
roster has not loaded. Stamping one string keeps the row self-describing. DS-7 may replace the
stamp with a join; until then the label comes from the platform device name.
⚠ `deviceId` itself is *not* optional and must never be defaulted — `resolveDeviceId` already
exists in Fit (`TenshuAutoDetectPreferences.kt:29-32`, wired at `FitModules.kt:112-118`), and a
blank id written under a borrowed key silently mutates **another device's** row.

**D4 — `autoImport` is ours, so it crosses devices; the trigger does not.**
The trigger asked for the toggle *"of course we can't actively deactivate stuff"* on a remote
source. We can, and the distinction matters: we cannot revoke HealthKit's permission from
Android, but `autoImport` is a field we own and it syncs — the iPhone's `HealthImportBootstrap`
reads it and stops importing. What is genuinely unavailable remotely is **triggering** an import
(that device must be foregrounded) and **fixing** permissions. So a non-local source's detail
screen offers the toggle, disables "Import now" with *"Open Fit on iPhone 15"*, and renders
readiness **as of a timestamp** rather than as live truth.
⚠ Rendering a remote device's stale readiness as current is the Control-on-iOS mistake in a new
place: a fabricated claim about the user's realm is worse than an honest absence.

**D5 — `lastRunAt`/`lastStatus`/`lastItemCount` are denormalised onto `HealthSource`.**
The list renders N rows; a join to "the newest run per source" is N reads plus a sort for text
that fits in three fields. The run rows stay authoritative — the denormalised copy is written in
the same op that records the run.

**D6 — Megabytes are a Takeout-only figure, and the UI says so.**
Health Connect and HealthKit hand us objects, not bytes; there is no honest MB to show. The
trigger asked for *"date, number of workouts and mega byte"* — the first two are universal, the
third is the archive size and appears only on batch-import runs.

**D7 — Only a run that did something is recorded.**
`HealthImportBootstrap.kt:104-114` fires on **every foreground**, debounced 60 s. Recording a row
per foreground turns a synced type into a heartbeat log — the Timeline broadcast shape, where two devices
broadcast a derived projection into the sync log and it became 99.8 % of realm traffic. A refresh
that imported nothing writes **no run row**; it may update `HealthSource.lastRunAt` only if that
write is itself gated on a change. Failures *are* recorded — a failure is information.

**D8 — Runs are capped per (kind, device), and only the originating device trims.**
Copy `FileModification.RETENTION_CAP` + `FileHistoryPolicyObserver`
(`file-service-proxy/.../FileServiceProxyInitialization.kt:117-170`), including its load-bearing
detail: the trim runs only when `origin == WriteOrigin.USER`, so peers converge via the
propagated tombstone instead of each re-trimming on ingest and doubling tombstone traffic. Cap
50, matching `FileModification`.
⚠ D7 and D8 ship **in the same slice that starts recording** (DS-2). A retention cap added later
is a cap on a log that has already grown.

**D9 — Tenshu Health is a source row.**
It is the row that makes the list coherent: it names where `Recorded` / `Manual` / `Detected`
workouts come from, it is the only source that exists on desktop and web, and it removes the
"No sources connected" lie. It needs **no new schema** — a projection over existing workouts plus
the existing `AutoDetectActivities` preference. Its detail screen is the natural home for the
auto-detect toggle that currently floats in its own Profile section.

**D11 — Only a *standing connection* gets a `HealthSource` row. Takeout and Tenshu Health do
not.**
The three things the section lists are not the same kind of thing, and forcing them into one
table produces fields that are meaningless for two thirds of the rows — a `TAKEOUT` source with
an `autoImport` flag it can never honour, a `TENSHU` source with a `lastRunAt` for an import that
never happens. So:

| Section row | Backed by |
|-------------|-----------|
| Health Connect, Apple Health | a `HealthSource` row (per device) |
| Google Takeout | the newest `HealthImportRun(kind = TAKEOUT)` — no source row |
| Tenshu Health | a projection over existing `Workout` rows + the auto-detect preference (D9) |

`HealthImportRun.kind` keeps `TAKEOUT` — a Takeout import is very much a run, it just is not a
connection. "Last import: 01.08.2026" is then a `sortBy(startedAt).take(1)` over file runs, which
is one streamed bounded pass since `core-ordered-window-fusion.md` F1.

⭐ This is what makes DS-1 small: the schema only has to describe the two things that genuinely
are platform connections, and it also tightens D10 — three different backings converge into one
row type **in the shared projection**, which is precisely the layer that gets desktop-tested.

**D10 — The projection lives in `commonMain`, not in either UI.**
`dataSourceRows()`, `importHistory(kind)` and `sourceDetail(kind)` go in `:fit-shared-logic`
commonMain and are desktop-tested. Compose and SwiftUI render only. This is the
`ios-control-notifications-and-shares.md` §8 lesson stated positively: the parts that can be
*quietly wrong* — which status text wins, whether a source is stale, what "last import" means
when the newest run failed — are tested once and shared, and it is why DS-5 is small.

---

## 4. The surface, after

```
Data sources
  Tenshu Health      42 workouts · today
  Health Connect     165 items · 3 Aug        Pixel 8
  Apple Health       89 items · 1 Aug         iPhone 15

Batch import
  Google Takeout     Last import: 01.08.2026
```

Every row is clickable. "Import your full history", "Check sharing setup" and the Google Fit
`SourceNotice` all move **inside** the Health Connect detail — they are Health Connect's
problems, not the section's.

⚠ One deliberate exception to the move: if any source `needsAttention`, the notice card stays at
the **section header**. It is the only element that actively prompts, and burying every prompt
one tap down trades clutter for silence. The row's highlighted status text ("Permission needed")
is the passive half; the card is the active half.

**`DataSourceDetailScreen(kind)`** — readiness block (today's `SharingWizard`, re-parented, not
rewritten), auto-import toggle, "Import now", run history list.
**`BatchImportScreen`** — run history with date / workouts / MB / filename, and "Import again".

### 4.1 Live status vs recorded status

The row prefers the live `ImportStatus` while an import is running, and falls back to the
persisted last run otherwise. This fixes the *"165 items imported" vanishes on restart* behaviour
as a side effect, and it keeps `ImportStatus` doing the one job it is good at — in-flight
progress — instead of standing in for a durable fact it cannot hold.

---

## 5. What each of the four asks costs

| Ask | Cost |
|-----|------|
| Batch import as its own section with "Last import: …" | DS-1 + DS-2 (the date has to exist) + DS-4 |
| Health Connect collapsed to one row with a detail screen | DS-4 only — the wizard is re-parented, not rewritten |
| Apple Health visible on Android | DS-1 + DS-2. **Not a UI change at all** (§0) |
| Tenshu Health as a source | DS-6, no schema (D9) |

---

## 6. Schema-evolution obligations (HR #9)

`:health-service` is at **implicit `version = 1`** (`HealthService.kt:64`, the DSL default at
`Dsl.kt:395-399`) and has **no registered migration chain** — `HealthServiceCommonModule.kt:64`
registers `service(HealthService.Schema)` and nothing else.

DS-1 therefore owes all three of:

1. `service(id = ID, version = 2, relations = …)`
2. `schemaMigration(schemaMigration(HealthService.ID, fromVersion = 1) {})` registered in
   `HealthServiceCommonModule` — an **empty step is correct here**, the change is purely additive
   (two new composites, two new relations; no existing composite is reshaped).
3. Regenerate **both** baselines in `system-test-utils/src/test/resources/`:
   - `schema-shape-baseline.txt` (`-Dtenshu.schemaBaseline.regenerate=true`) — positional layout
   - `property-kind-baseline.txt` (`-Dtenshu.propertyKindBaseline.regenerate=true`) — how each
     property's value is *stored*

   ⚠ The second one is easy to miss: it lives in the same test class family, fires only in a
   full-suite run, and a new **service relation** counts as a new property (`CHILD_ROW`). It is
   the guard for the property-kind *flip* that stranded 743/823 thumbnail rows, so a
   clean run that reports only additions — as DS-1's did — is the evidence that nothing flipped.

⚠⚠ **A bumped service with no registered chain sits below its own derived floor, and the store
is wiped.** The readable floor is derived from the chain, so the bump and the (empty) chain are
one change, never two PRs.

⚠ `TENSHU_STORAGE_FORMAT_VERSION` must **not** move — this is a service-owned type, and moving
the core constant wipes every store, which is what used to make the migration dead code.

---

## 7. Traps a compile will not catch

- ⚠⚠ **A new op needs a `BundledAppManifestSource` permission entry or Fit cannot call it**, and
  it fails at *dispatch on a device* — not at compile. ⭐ **DS-1 tried to add the three entries
  up front and a test refused them**: `fitManifest_declaresOnlyTheOpsFitActuallyDispatches`
  (`BundledAppTablesTest`) pins the permission set exactly, and DS-1 ships the operations with
  no caller. So the entries land in **DS-2, with their callers** — which is the correct
  boundary, but it means the obligation now lives one phase away from the code that creates it.
  A pointer sits in `FIT_MANIFEST`'s KDoc naming the three ops; ⚠ nothing else will catch a
  missing entry, since Fit is an un-built smoke canary.
- ⚠⚠ **`int` in the schema DSL is a `Long` on the wire.** `itemCount` / `workoutCount` /
  `byteCount` / `lastRunAt` are all `TInteger` and take `Long`. An `Int` fails at SAVE time.
- ⚠⚠ **A composite an op *returns* needs a `dtoFactory` in the `*CommonModule`**, and a type used
  as a **predicate receiver** needs an `roFactory` — where a client module shadows the common one
  (the geocode locality-tier finding, and the Mia MQ-1 finding).
- ⚠ **`:fit-shared-logic` is deliberately client-free.** Preference *declarations* live there;
  every proxy-backed read lives in `:fit-shared-data`, which already carries `:health-service-*`,
  `:timeline-service-*`, `:preference-service-*` and `:sync-service-*` clients. The new reads go
  in `-data`; the models and the D10 projection go in `-logic`.
- ⚠ **`deleteWorkout` is the delete precedent to follow** — a delete that emits no tombstone is
  resurrected by the next pull (`DeleteRoutine` shipped that way until 2026-11-04). DS-2's retention
  trim must emit one.
- ⚠⚠ **Fit is one of the deliberately un-built canaries in `scripts/android-smoke.sh`** (HR #8).
  A Fit change is therefore **not gated by the smoke script** — that is the point of the canary,
  but it means the on-device pass here is a manual install, or a temporary addition of Fit to the
  built set for the duration of the track. Do not read a green smoke run as covering DS-4.
- ⚠ **iOS shells target 16.** `ContentUnavailableView` is iOS 17 and is now the most-repeated iOS
  mistake in the repo (5 occurrences). There is no shared stand-in — grep for a sibling.
- ⚠ **Sealed leaves do not cross the SKIE bridge.** DS-5 passes a `kind` string and re-resolves
  where the `when` is exhaustive.

---

## 8. Threat model

No amendment required, and the reason should be stated so the next reader does not re-derive it:
the new rows are ordinary realm data, encrypted like every other composite, and the relay sees
ciphertext (HR #1). No new asset, no new surface.

⚠ One privacy note that is *not* a threat-model change but is a review point: `deviceLabel`
places a human-readable device name on a synced row. `identity-service`'s `Device.name` already
does exactly this in the same realm, so this adds no new exposure — but `error` must carry a
**typed code only**, never a path or a filename (A19), and `fileName` on a `HealthImportRun` is
user-chosen content that stays inside the realm.

---

## 9. Phase map

| Phase | Scope | Gate |
|-------|-------|------|
| **DS-1** ✅ | `HealthSource` + `HealthImportRun` composites, three ops, service `version = 2` + empty migration chain, baseline regen. No UI, no callers. **SHIPPED** — as-built §11. | `./gradlew desktopTest jvmTest test` |
| **DS-2** ✅ | Record runs at every import exit; register source rows; gate *every* unattended caller on `autoImport`; D7 no-op rule; D8 retention observer; the three `FIT_MANIFEST` entries; archive name + size through `ActivityFileSource`. **SHIPPED** — as-built §12. | `./gradlew desktopTest jvmTest test` |
| **DS-3** ✅ | D10 projection + its inputs in `:fit-shared-logic`, the `FitRepository` read seam, extraction in `:fit-shared-data`. **SHIPPED** — as-built §13. | desktopTest |
| **DS-4** ✅ | Compose: Profile split into *Data sources* / *Batch import*, `DataSourceDetailScreen`, `BatchImportScreen`, `SharingWizard` re-parented. **SHIPPED** — as-built §14. ⚠ Owes the manual install. | `ProfileScreenTest` + `DataSourceSectionTest` |
| **DS-5** ✅ | iOS SwiftUI twin — `ProfileView` sections, `DataSourceDetailView`, `BatchImportView`, two `FitSurface` accessors. **SHIPPED** — as-built §15. ⚠ Owes the device pass. | `ios-smoke.sh --build-only fit` |
| **DS-6** ✅ | Tenshu Health source row + the auto-detect toggle moved into its detail (Q1). ⚠ The row shows the **detection setting**, not a count — §16 says why. **SHIPPED** — as-built §16. | desktopTest + `ios-smoke.sh --build-only fit` |
| **DS-7** ✅ | The roster join, so a renamed device shows its new name. ⚠ Goal (b) — Q2's retired-device treatment — **declined with evidence**, not built. **SHIPPED** — as-built §17. | desktopTest |

DS-1 → DS-2 → DS-3 are strictly ordered. DS-4 and DS-5 both depend on DS-3 and are independent of
each other. DS-6 depends on DS-4.

---

## 10. Implementation handoff — DS-1

New files in `:health-service` (`src/commonMain/kotlin/org/tenshu/core/health/`):

| File | Contents |
|------|----------|
| `HealthSource.kt` | composite + `HealthSourceDataIdProvider` + `keyOf(kind, deviceId)` + kind/status constants + `create(...)`. Model on `IngestSource.kt`. |
| `HealthImportRun.kt` | composite + `HealthImportRunDataIdProvider` + `keyOf(deviceId, startedAt)` + window/status constants + `create(...)`. |
| `RegisterHealthSource.kt` | `operation(id, receiver = HealthService.Schema.toType(), traits = [IoOperation, TerminalOperation])`, upsert semantics. |
| `RecordImportRun.kt` | records the run **and** updates the owning `HealthSource`'s denormalised trio (D5) in one op. |
| `SetSourceAutoImport.kt` | flips `autoImport` on an existing row; a no-op if the row is absent. |

Edited:

| File | Change |
|------|--------|
| `HealthService.kt` | `version = 2`; two `serviceDataRelation`s (`sources`, `importRuns`), both `maintained = true`; two `Relations` constants; two interface properties. |
| `HealthServiceCommonModule.kt` | `composite(...)` for both new types, `operation(...)` for the three new ops, and the `schemaMigration(HealthService.ID, fromVersion = 1) {}` registration. |
| `health-service-common` impls | `HealthSourceImpl` / `HealthImportRunImpl` + DTO/RO impls per the module's existing per-composite pattern. |
| `health-service-client` | request objects for the three ops (`RecordImportRunRo.kt` etc.), mirroring `RecordImportedHealthRo.kt`. |
| `health-service-proxy` | three executors, registered in `healthServiceProxyImplementationFactory`. |
| `BundledAppManifestSource.kt` | ⚠ **DS-2, not DS-1** — see §7. DS-1 only leaves the pointer in `FIT_MANIFEST`'s KDoc. |
| `schema-shape-baseline.txt` | regenerate. |

`maintained = true` on both relations is deliberate: both are membership relations keyed by the
composite's own id provider, which is exactly what generic maintenance derives
(`core-relation-system.md` §9.2.1) — so `:health-service-proxy` registers no observer, the same
reasoning already recorded at `HealthService.kt:60-63`.

---

## 11. DS-1 as built (2026-08-13)

Shipped as designed, with one boundary correction and two things worth recording.

**Files.** `HealthSource.kt`, `HealthImportRun.kt`, `RegisterHealthSource.kt`, `RecordImportRun.kt`,
`SetSourceAutoImport.kt` in `:health-service`; Dto + DataIdProvider impls and the module
registrations in `:health-service-common`; three `…Ro`s plus **typed** `type(...)` registrations
in `:health-service-client`; three executors in `:health-service-proxy`; `HealthServiceRo` gained
the two relation accessors. `HealthDataSourceRegistryTest` (9 tests) is the gate.

**The version bump behaved exactly as HR #9 says.** `SchemaShapeBaselineEnforcementTest` failed
with *"7 new type(s) are not pinned yet"* and named all seven — the two composites, their two id
providers, the three operations — and **no existing composite was listed as reshaped**, which is
the confirmation that the change really is additive and the empty migration step is honest.
⭐ Regenerating the baseline is the *last* step, not the first: the failure list is the evidence.

**⚠⚠ There are TWO baselines, and only one of them is obvious.** Targeted module runs were green
and the schema-shape baseline was regenerated, but the **full** suite then failed on
`PropertyKindBaselineEnforcementTest` — a new *service relation* is also a new **property**, and
`property-kind-baseline.txt` pins how each property's value is stored. It reported exactly the
two new relations as `CHILD_ROW`, matching all nine siblings, and **no flips** — which is the
whole point of that gate, since a property-kind flip is what stranded 743/823 thumbnail rows.
§6 now names both. ⚠ It fires only in a full-suite run, so a module-scoped green means
nothing here.

**⚠⚠ The `FIT_MANIFEST` entries were refused, and correctly.**
`fitManifest_declaresOnlyTheOpsFitActuallyDispatches` pins Fit's permission set exactly, so
declaring the three new ops before DS-2 wires a caller is over-declaring. They move to DS-2 (§7).
The lesson generalises: **in this repo an operation's permission entry belongs with its caller,
not with its declaration** — which means a schema-only phase can never be "complete" on the
permission axis, and the pointer in the KDoc is doing real work.

**⚠ The two preservation tests were verified non-vacuous.** `RegisterHealthSource` preserving
`autoImport` is the load-bearing behaviour — it runs on every launch, and re-defaulting the flag
would silently re-enable a source the user disabled, from another device. Breaking the
implementation (`autoImport = true`, run figures nulled) turns
`reRegisteringPreservesAutoImportAndRefreshesTheLabel` and
`reRegisteringPreservesTheDenormalisedRunFigures` red; restoring it turns them green. A test that
only ever passes proves nothing about the rule it claims to hold.

**Unrelated:** `PlaceDetailOfflineOfferTest.aFailedDownloadLeavesTheOfferStanding_becauseTheMapKeepsStreamingAnyway`
(`:maps-shared-logic`) failed once in a full-gate run and passes on rerun, on master and on this
branch. A flake, not a regression here — but it is now the second maps-offline test to flake, so
it is worth a look on its own.

---

## 12. DS-2 as built (2026-08-13)

Shipped as scoped. Three things the design did not know, and one mistake worth keeping written
down.

**⚠⚠ The `autoImport` gate could not go where the phase map put it.** DS-2 was specified as
"gate `HealthImportBootstrap`" — and that would have been **dead on iOS**, which is exactly where
Apple Health lives. `FitLocalRuntime` and `EpcFitClient` each re-implement the foreground refresh
and call `repository.startImport(...)` directly, bypassing the bootstrap entirely. The gate
therefore lives in `startImport` itself, behind an explicit `automatic: Boolean` that every
unattended caller passes. ⚠ A manual import is never gated: switching off automatic passes is not
the same as switching off the source, and the DS-4 detail screen offers both.

⭐ The general shape: **when a rule must hold for "every automatic X", put it where X happens and
make callers declare intent** — a gate at the call sites is only as good as your list of call
sites, and this list was wrong by two.

**⚠⚠ D7 was written backwards on the first pass, and it reads perfectly well wrong.** The
plausible spelling is *"record anything that is not SUCCEEDED"*. That inverts the rule:
`NO_DATA` on a `RECENT` window **is** the empty every-foreground refresh the rule exists to
suppress. The predicate now matches that one combination exactly, with a table test over all five
cases. If this ever needs re-deriving: the question is not "did it succeed" but "is this
*information*", and an automatic pass that found nothing is not.

**⭐ The archive's name and size were already at every pick and thrown away at one seam.** All
three shells (Android SAF, desktop `FileDialog`, iOS `FitSurface`) have both at the moment the
user picks; `ActivityFileSource` simply had nowhere to put them, so D6's megabyte figure cost two
optional properties and one extra `OpenableColumns.SIZE` column rather than any new plumbing.

**⚠ The interface widening caught six test fakes, and only in a full-suite run.** Adding
`automatic` to `FitRepository.startImport` compiled cleanly across every *main* source set and
broke seven overrides in `commonTest`. Module-scoped compiles do not see them — the same lesson
as DS-1's second baseline, in a different disguise.

**Verified non-vacuous:** breaking `worthRecording` to `true` and disabling the gate turns
`anEmptyAutomaticRefreshRecordsNoRun`, `theNoOpRuleMatchesTheEmptyAutomaticCaseExactly` and
`theAutoImportSwitchStopsAutomaticPassesOnly` red; restoring them turns all three green.

**Not covered by a test, and worth knowing:** `HealthImportBootstrapTest` pins that *the
bootstrap* declares its passes unattended. ⚠ The two iOS callers have no equivalent guard — they
live in `tenshu-sdk-ios` with no desktop-runnable test — so the iOS half of D4 rests on reading
the code, until the DS-5 device pass.

---

## 13. DS-3 as built (2026-08-13)

The projection (`DataSourceProjection`) and its plain inputs live in `:fit-shared-logic`; the
extraction (`DataSourceReads`) and the `FitRepository` overrides live in `:fit-shared-data`. Three
things worth keeping.

**⚠⚠ A projected read over a relation MUST keep the id-provider's field.** `SOURCE_FIELDS` /
`RUN_FIELDS` first shipped without `KEY`, and the failure mode is vicious: the stream merge
resolves each row's `DataId` to diff snapshots, so every emission throws
`Field tenshu/health/importRun/key not found` — and `retryingOnThrow` turns that into a flow that
**retries forever and never emits**. On screen that is a section that loads indefinitely, not an
error; in a test it is a 60-second `runTest` timeout with no useful message. ⭐ It surfaced only
because the end-to-end read test existed: `DataSourceProjectionTest` was green throughout, because
a projection fed by a broken extractor is correct in both halves and empty in practice.

⚠ It also hid itself once: `dataSources()` appeared to work while `importHistory()` did not,
purely because the `sources` relation was **empty** in that test — zero rows means zero
`DataId` resolutions means no throw. An empty relation is not evidence that a read works.

**⚠ The vocabulary is restated, so it needs a join test.** `:fit-shared-logic` cannot see
`:health-service`, so `DataSourceProjection` re-declares `HEALTH_CONNECT` / `SUCCEEDED` / `FILE`
and the rest. A rename upstream would keep compiling, every `when` would fall through, and the
section would silently go empty. `DataSourceVocabularyTest` in `:fit-shared-data` — the lowest
module that can see both sides — pins each constant against the real one.

**⚠ A run's identity makes same-millisecond imports the same row.** The key is
`"$deviceId:$startedAt"`, so two imports starting in the same millisecond fold — content
addressing working as designed, and unreachable in life (`importLock` serialises imports; a real
one takes far longer than a millisecond). It *is* reachable with two **empty** archives back to
back: a DS-3 test hit it, passing in isolation and failing in a warm full-suite run. ⭐ A test
recording several runs must drive the repository's injectable `clock` rather than race the wall
clock; production needs no change. Noted on `HealthImportRun`, whose KDoc previously asserted the
collision could not happen.

**⭐ The live-vs-stored precedence is narrower than it looks (§4.1).** Only `Running`,
`PermissionsMissing`, `SourceUnavailable` and `Sample` come from the live channel. A *completed*
`ImportStatus.Imported` deliberately does **not** win: it says what the stored row says, but only
until the process dies, so taking it would reintroduce the vanishing figure through the back door.
And the live status is applied to **this device's row only** — colouring a remote row with local
progress would be a fabricated claim about hardware the user is not holding.

---

## 14. DS-4 as built (2026-08-13)

The section now renders exactly what §4 drew. Four things worth keeping.

**⭐ Profile stopped reading `workouts()` entirely.** That read existed only to derive the
provenance badges — its own comment called it *"the heaviest read in the app"*, for a handful of
rows — and the registry replaces it. A side-effect of the restructure rather than a goal, but a
real one: the screen's remaining reads are profile, goals, import status and the two registry
flows.

**⚠⚠ The old state-assignment shape would not have survived two more fields.** `ProfileUiState`
was written wholesale (`_uiState.value = next.copy(autoDetect = …)`) with exactly one field
carried forward by hand, because `combine` had run out of arity. That works until someone adds a
field and forgets. Three collectors now each `update { copy(…) }` **their own** fields, which
makes the omission unrepresentable rather than merely uncommitted.

**⚠⚠ A ViewModel test with no `Dispatchers.setMain` reads as missing data, not as an error.**
`DataSourceDetailViewModelTest` first failed with `row == null` — indistinguishable from *"the
registry has no such source"* — because `viewModelScope` is `Main.immediate` and never ran. Same
class as the `cachedIn` hang on iOS. If a VM test asserts a default and you cannot see why,
check the dispatcher before you doubt the data.

**⚠ Two test-only traps in this UI layer.** `SectionHeader` **uppercases**, so a section-header
assertion must match `"DATA SOURCES"`, not the string resource; and a date fixture must be built
at **midday** UTC, because `formatRunDate` renders in the system zone and a midnight instant
prints as the previous day west of it. Both cost a red run.

**What DS-4 deliberately did not do:** the auto-detect toggle stays in its own Profile section.
Q1 moves it into Tenshu Health's detail, and that row does not exist until DS-6 — moving the
control first would leave it homeless.

⚠⚠ **Nothing here has been looked at.** The tests assert the semantics tree, which says a row
exists and carries the right text; it says nothing about layout, spacing or whether the detail
screen reads well. Fit is a deliberately un-built `android-smoke.sh` canary, so the smoke gate
does not cover it either. DS-4 owes a manual install.

---

## 15. DS-5 as built (2026-08-13)

⭐ **The port really was SwiftUI-only, as D10 predicted.** Every rule — which state a row shows,
whether this device may import, whether the switch applies here or elsewhere — was already
decided in `commonMain` and desktop-tested, so `DataSourceText.swift` is a translation table and
the two views are layout. Two `FitSurface` accessors were the whole Kotlin cost.

**⚠⚠ DS-4 shipped with `fit-ios` uncompilable, and every Kotlin gate stayed green.**
`ProfileView.swift` still read `state.sources` and still had a `lastSync(_ c: ConnectedSource)`
helper — both deleted by DS-4. Nothing caught it: `ios-smoke.sh --build-only` is the only thing
in this repo that compiles Swift, and it is not part of `desktopTest jvmTest test`. ⭐ **The rule
this yields: a phase that changes a shared `ProfileUiState` owes the Swift build, even when its
own work is entirely Kotlin.** DS-5 is what repaired it.

**⚠ The picker stays Swift's.** `BatchImportViewModel.canImportFiles` is false on iOS because no
`HealthFilePicker` is bound — but the shell has owned `.fileImporter` since P3a, so
`BatchImportView` ignores the flag and renders its own button. `importPicked` moved across
**verbatim** rather than being paraphrased, because its comment carries the reason the picked
document is copied into the container first: the security scope dies when the callback returns
while the import runs for minutes afterwards, and the failure is invisible because the *picker*
succeeded.

**⚠ No `HealthPermissionRequester` on iOS**, so the deep-history grant and the sharing check are
**absent** from the iOS detail screen rather than shown inert. That is correct rather than a gap:
HealthKit imposes no 30-day window to lift and offers no settings screen to send anyone to.

**iOS-16 floor honoured**: no `ContentUnavailableView` (iOS 17 — `FitEmptyState` is the house
stand-in), no two-parameter `.onChange`, no `navigationDestination(item:)`. The smoke script's own
header names an iOS-17 navigation API as one of the three failures `--build-only` exists to catch.

⚠⚠ **Still no visual verification on either platform.** `--build-only` proves the Swift compiles;
it says nothing about layout. DS-4 and DS-5 both owe a look on a real device.

---

## 16. DS-6 as built (2026-08-13)

**⚠⚠ The row cannot show a count, and the reason is correctness rather than cost.** D9 assumed
"N workouts"; DS-3 even shipped `DataSourceState.Recorded(workoutCount: Long, …)` for it. It is
not available:

| route | why not |
|---|---|
| `WorkoutActivityRollup` | keys on activity type; provenance is in neither key nor payload |
| `count()` | O(1) short-circuit is disarmed by any preceding filter, and it carries no `TerminalOperation`, so no client can execute it |
| `workouts()` | capped at `WORKOUT_WINDOW = 300` and recency-shaped — the figure would **shrink as imports arrive** |
| `workoutsPaged()` | `itemsBefore/After` undefined, placeholders off — *"summing a `PagingData` is not possible"* |
| a source predicate | no `OR` in the DSL, so {Recorded, Manual, Detected} is three chains |

⚠⚠ And the disqualifier, which no amount of engineering fixes: `FitProjection.source(raw)` maps
**`Manual` as its `else` branch** — every unrecognised string. No store-side `equalTo "Manual"`
can reproduce that, so a counted figure and a rendered one would disagree **by construction**.

So `Recorded(workoutCount, at)` became **`Recording(autoDetect)`**: the row describes what this
source is *configured to do*, which for Fit is the meaningful fact — there is no import to report
on. A real count needs provenance in the rollup: a schema change plus a `ROLLUP_GENERATION` bump
and a full re-derive scan per device. Available if wanted; not worth a subtitle.

**⚠⚠ The Noop preference nearly shipped a fabricated claim.** `autoDetect` was first defaulted to
`NoopAutoDetectPreferences`, whose `enabled()` emits the preference's *default* (`false`). That
renders **"Detection off"** on every surface with no preference store — a confident statement
about the user's configuration, made by something that cannot read it. The dependency is now
nullable and **absent omits the row**, the same rule the rest of this surface follows. ⚠ A "Noop"
that answers with a plausible default is not a no-op; it is a liar with good manners.

**Q1 landed in full**: `AutoDetectPreferences` left `ProfileViewModel` entirely — state, setter
and dependency — the *Automatic* section is gone from Profile on both platforms, and the switch
sits in Tenshu Health's detail. ⚠ A test pins that it writes the **detection preference** and
never `setSourceAutoImport`: same control on screen, different thing underneath, and routing it
wrong would write to a registry row this source does not have and silently do nothing.

**⚠⚠ A second gate gap, sibling to DS-5's.** `./gradlew desktopTest jvmTest test` does **not
compile `tenshu-sdk-ios/src/iosMain`** — it is Kotlin, so it looks covered, but the gate builds
JVM/desktop/Android and the iOS source set is only built by `assembleTenshuSdkDebugXCFramework`.
A `FitLocalRuntime.kt` edit with three missing imports passed the full gate ("1893 tasks, all
executed") and failed only at `ios-smoke: FAIL — XCFramework assembly failed`. ⚠ The script
prints only that line; re-run `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64` to see the `e:`
lines.

---

## 17. DS-7 as built, and why Q2 cannot be answered (2026-08-13)

DS-7 had two goals. One shipped; the other turned out to be **unanswerable with the data the
platform has**, and saying so is the more useful result.

### (a) Shipped — the roster join

`HealthSource.deviceLabel` is still stamped from the platform name at register time, and is now
**upgraded** to `Device.name` wherever the roster answers. So renaming a device in Control renames
it in Fit's Data sources.

⚠ **An upgrade, never a gate**, and per-device rather than all-or-nothing: a realm where one
device has synced its `Device` row and another has not renders both, one renamed and one stamped.
A roster read failure emits an empty map rather than propagating — the stamped labels are
perfectly serviceable alone, and a Data sources section must not go down with the device list.

⭐ The build cost was near zero: `:identity-service` is already on Fit's classpath transitively,
because `:sync-service` and `:sync-service-client` both `api` it. The only new edges are
`:identity-service-client` (+ `-common` for the UI module) and — ⚠⚠ the load-bearing half — the
**runtime registration** in `fitClientModule`. Without that the read fails at *dispatch on a
device*, not at compile, and the label silently falls back to the stamp.

⚠⚠ **`client.identityService` throws *synchronously* when the service is not registered**, so the
guard has to wrap the flow's **construction**, not just its emissions. The first cut put a
`.catch {}` downstream and six read tests died on
`No schema registered for service id: tenshu/identity` **before a single row rendered** — i.e. an
older Control, or any runtime that never declared the client module, would have lost the whole
Data sources section rather than just the labels. `deviceNames()` now builds the upstream inside
`flow { }` behind a `runCatching`, emits the empty map first, and degrades to the stamps.
`theSectionRendersWithNoIdentityServiceAtAll` pins it — and the harness registers no identity
module, so the test is the degraded state rather than a simulation of it.

### (b) Declined — Q2 has no honest answer

Q2 asked what a source row shows for a device that has left the realm. It cannot be answered,
for three independent reasons:

1. **`isAuthorized == false` does not mean revoked — it means *pending pairing approval*.** No
   production writer ever sets it false; all five `Device.create` call sites pass `true`.
2. **Revocation deletes the row.** `RevokeDeviceImplementation` removes the `Device` from the
   relation, deletes the entity and emits a tombstone. So "gone" presents as **absence** — which
   is indistinguishable from "the roster has not synced yet", exactly D3's original objection,
   now confirmed rather than hypothetical.
3. **`DeviceLastSeen` cannot disambiguate, and says so.** It carries no `Syncable` trait — it is
   this replica's own vantage point — and its KDoc states outright: *"Absence means 'never heard
   from', not 'gone' … which is why this drives display only and must never drive automatic
   removal."*

⚠ A fourth trap for anyone who retries this: lineage-revoked subtree members are deliberately
**kept** in the roster with `isAuthorized = true`, so a naive presence-join would render a
revoked device as live.

**So the retired phone's `HealthSource` row is immortal** — `Syncable(Eager)`, and nothing prunes
rows keyed by a departed `deviceId` anywhere in the platform (grep confirms: zero deletes of a
`HealthSource`). That is a real gap, but it is a **platform** gap about identity lifetime, not a
Fit one, and the honest fix is a reaper triggered by revocation rather than a grey-out in one
app's settings screen. Greying on absence would tell a user with a slow-syncing realm that a
perfectly live phone had been retired.

The precedent to copy if it is ever wanted is Control's, which answers the same question
informationally rather than as a verdict: `LastSeenFormat` renders *"No activity seen yet"* rather
than "never", with the comment *"a device can be perfectly live and simply have written nothing
since this replica started observing. Saying 'never' would read as a verdict on the device."*

---

## 18. Open questions

**Q1 — CLOSED (2026-08-13). The auto-detect toggle moves into Tenshu Health's detail (D9).**
Maintainer's decision. It was a coherence-vs-findability trade — the toggle is that source's own
setting, but it is also something users go looking for — and coherence won. It lands in **DS-6**,
where the Tenshu Health row itself is built; until then it stays in its own Profile section rather
than being homeless.

**Q2 — CLOSED as unanswerable (2026-08-13), see §17.** A source row cannot distinguish a
retired device from an unsynced roster: revocation *deletes* the `Device` row, and
`DeviceLastSeen` is local-only and documented as unable to tell "gone" from "quiet". The row is
immortal and nothing prunes it — a real gap, but a **platform** one about identity lifetime,
whose honest fix is a reaper on revocation rather than a grey-out in one app's settings.

**Q3 — Is `HealthSource` the right home for a *disabled* platform store the user has never
granted?** Today a source row is minted at register time, which happens when the importer reports
itself available. A device where Health Connect is installed but has granted nothing will
therefore show a row reading "Permission needed" — which is right. A device where Health Connect
is **not installed** mints no row and shows nothing, which is also right. The unresolved case is
a device that *had* it and no longer does: the row persists with a stale `lastRunAt`. Same
underlying gap as Q2, and the same DS-7 answer.

*(An earlier Q3 — whether `TAKEOUT` should be a source kind — is resolved as D11.)*
