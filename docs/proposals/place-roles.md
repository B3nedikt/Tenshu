# Place roles — Home, Work, and the places that anchor a timeline

**Status: Draft v0.1 (2026-07-30) — design settled. PR-1 … PR-4 all implemented 2026-07-31.
⚠ The inference remains unverified end-to-end on a device; see §7.**

Owner-facing question: *"I think we need a kind of 'special location' marker like Home and Work —
derived, user-defined, or a combination?"* → **combination: infer a candidate, the user confirms,
and the user's answer is final.**

Related: [`place-geocoding.md`](place-geocoding.md) (the `labelSource` precedent this generalises),
[`timeline-recognition-and-place-naming.md`](timeline-recognition-and-place-naming.md) (place
identity — the prerequisite), [`timeline-app.md`](timeline-app.md) §11 (the feed headline that
becomes a consumer), [`fit-read-scaling.md`](fit-read-scaling.md) (the read-scaling incident this
design is shaped to avoid).

---

## 1. Why this is worth building

Three things already in the tree want it, and each is currently guessing or hardcoding:

- **`TripSuggester.deriveHome()`** (`timeline-service-common/.../TripSuggester.kt:53-66`) computes
  home as *"the centroid of the most-frequented grid cell"* on every call, and its own KDoc says:
  *"A stored home setting, when one lands, should take precedence over this heuristic."*
- **Maps** has a `PlaceCategory { Home, Work, Cafe, … }` enum
  (`maps-shared-logic/.../MapsModel.kt:28`) that is **mock-only** — the real projection hardcodes
  `category = PlaceCategory.Store` (`MapsPlaceProjection.kt:37`) with a KDoc admitting it "has no
  home".
- **The Timeline feed** now renders movements as `Home → Work`
  ([`timeline-app.md`](timeline-app.md) §11) — but only when the bracketing places happen to be
  *named* "Home" and "Work" by hand. A role makes that the norm rather than a coincidence.

A role is also the cheapest available **canary for place identity**: if `Home` resolves to one row
rather than three, the `PlaceMerge` work (`timeline-recognition-and-place-naming.md` §8–§10) is genuinely converging.

## 2. Where the role can live — three candidate shapes

### 2.1 Not a field

⚠⚠ **`Place` cannot gain a field.** `Place` rides as an operation parameter of `RecordPlace`
(`RecordPlace.kt:42`), and `SchemaMigrationRegistry.verify` rejects any added field outright:

> Nested reshapes are not yet supported: migrated type 'tenshu/place/place' is embedded as a
> composite field of 'tenshu/place/service/recordPlace'

(recorded at `place-service-common/.../PlaceServiceCommonModule.kt:86-97`).

### 2.2 Not a property either — and this is the *load-bearing* reason

⚠ An earlier draft of this doc said the field constraint made a sibling row "forced". That was
too strong, and worth correcting because the instinct behind the objection is right: **a property
is not a field**, and both property kinds sidestep §2.1 entirely. Properties live in the entity's
**string-keyed, order-free `properties` map** (`Entity.kt:41-58`), never in the positional field
list — `DataFactory.collectFields` walks `schema.values` only (`DataFactory.kt:169-172`), and
`SchemaMigrationRegistry.verify`'s `structuralShape()` / `embedsComposite()` read `values` too
(`SchemaMigrationRegistry.kt:142-169`). Adding a property to `Place` needs no `CompositeMigration`,
so `Place` never enters the verify pass at all. So the real question is not "can it be a property"
— it can — but "does a property *survive*".

**`dynamicProperty` — cannot hold the user's answer.** It is chain-computed per read and never
persisted on the entity. There is no write API: `EntityPropertiesBuilder.set` is typed
`set(property: Property.Value<*>, …)`, so a `Property.Dynamic` is rejected **at compile time**. And
`SyncObserver.processSavedData` (`SyncObserver.kt:536-560`) emits property blocks by walking the
saved entity's `properties` map — a dynamic property is never in it, so nothing syncs. `File.Preview`
states this directly (`File.kt:262-268`): *"a dynamic property … is never written into the entity, so
`SyncObserver` never sees a value to emit — a `Syncable` trait here would be inert."* The only two
dynamic properties carrying synced values (`File.Data`, `Workout.Streams`) do it as
`Syncable(OnDemand)` **blobs in an external `FileDatabase`**, written by a bespoke operation plus an
`ExternalPropertyWriter` on the content plane — a megabyte-blob path, not a vehicle for a scalar.

**`valueProperty` — the right *kind*, wrong *host*.** A static `valueProperty` + `Syncable` genuinely
is the codebase's vehicle for a user-authored synced scalar (`File.Thumbnail`, `File.ContentId`), and
on any other type it would be the cheapest shape here by a wide margin. ⚠⚠ **On `Place` it would be
silently wiped.** `RecordPlaceImplementation` saves the **bare `Place` composite** it read out of the
op parameters (`RecordPlaceImplementation.kt:28`) — a composite has no `properties` map at all, so
the save replaces the stored row and every attached property with it. That write path is not an edge
case; it is *the* way places are written, from at least three callers: the geocoder's enrichment, the
user's rename (`TimelineProjectionRepository.kt:302-333`), and `PlaceReconciler`. A role set on
Monday would vanish the next time the geocoder filled in a city.

This is precisely deletion by omission — a replace-write dropping what it does not know — the class `MoveFileImplementation` guards against:

> a move must carry EVERY stored value property … or it silently drops. The source loaded above with
> `asEntity = false` has an **EMPTY `.properties` map** … Reload it as a full entity … killing the
> silent-loss-on-new-prop class.

Adopting a `valueProperty` here would mean re-introducing that bug deliberately, then paying to fix
it at every `recordPlace` caller (read-as-entity, re-attach, re-save) — on a type whose whole write
idiom is replace-the-row.

### 2.3 → A keyed sibling row

A separately-keyed row is **structurally immune to the replace-write**: `recordPlace` addresses the
`Place` row and cannot touch a row under a different `DataId`. That — not the field constraint — is
the reason, and it is the same reason `PlaceMerge` and `PlaceNetworkFingerprint` are sibling rows
rather than properties. It also costs nothing for the overwhelming majority of places that will never
carry a role.

⚖️ The honest trade: a sibling row is **more registration work** than a property (§4 PR-1's 16 sites
vs roughly three), and that cost buys exactly one thing — durability against `Place`'s own write
path. Worth it, because a role that silently disappears is worse than no role feature at all.

**Considered and not recommended: a `dynamicProperty` on `Place` reading the sibling row**, to
recover the `place.get(Place.Role)` read ergonomics the property shape would have given. It works
(the row stays the source of truth, the property is a pure derived read, so §2.2's objection does
not apply) but it costs one chain execution *per place per read* — paid on every row of the Places
tab — to replace what is otherwise a single read of a ~2-row relation folded into a `Map`. Revisit
only if a consumer outside Timeline wants roles without knowing about the relation.

---

## 3. Decisions

### D1 — A keyed sibling row (§2), and one row type covers suggestion, confirmation **and** rejection

```
PlaceRole  (tenshu/place/placeRole)   key = placeId
  placeId    : string     the place this is about
  role       : string     PlaceRoles.HOME | WORK | NONE  (open vocabulary)
  roleSource : string     RoleSource.INFERRED | USER
  decidedAt  : timestamp  when this assignment was made
```

- `roleSource = INFERRED` → the UI renders a **confirm prompt** ("Is this your Home?").
- `roleSource = USER` → the UI renders a **badge**, and inference never touches the row again.
- A dismissal is not a third concept: it is `role = NONE, roleSource = USER` — *"I am telling you
  this place has no special role."* One rule falls out of it: **inference never writes over a
  `USER` row.**

This is [`place-geocoding.md`](place-geocoding.md)'s shipped `labelSource ∈ {geocoded, user}`
marker generalised — same rule ("the enricher never overwrites a user-authored value"), same
degradation posture.

`role` and `roleSource` are **strings, not enums**, following `MovementModes` and
`FingerprintProvenance`: a newer peer can introduce `gym` or `school` with no schema change, and
this build degrades to "no role I recognise" rather than failing. Vocabulary lives in an
`object PlaceRoles` with `isHome()` / `isWork()` helpers (`FingerprintProvenance.kt:120-129` shape).

### D2 — ⚠ This is **not** an inference-service concern

I steered wrong on this in conversation; correcting it here because it changes where the code goes.

[`inference-service.md`](inference-service.md) IN-D1 defines the seam as **task-shaped over
content** — every operation takes a `contentRef` (an image, an audio blob):
`InferImageEmbedding`, `RecognizeText`, `DetectFaces`, `TagImage`, `TranscribeAudio`. There is **no
registration point for "read stored rows → write a derived row"**, and none is designed. Nothing of
`:inference-service` is built (no module in `settings.gradle.kts`); waiting for it would block this
slice on an unrelated, unstarted track.

The shipped home for row-derived facts is the **proxy-side `DataObserver`**. The inference doc
itself points at it as *its* precedent (IN-D5): *"Fit's `LatestSample` rollup — locally derived,
maintained by an observer… Derived data does not automatically deserve the wire."*

### D3 — ⭐ A bounded recent window, **not** an all-history rollup

The obvious design is a maintained `PlaceDwellRollup` in the `WorkoutActivityRollup` mould. **Don't
build it.** Home and Work are questions about *current* life: a flat you moved out of a year ago is
not your Home, and an all-history aggregate would keep insisting it is. **Recency is semantically
correct here, so a bounded window is not a compromise — it is the better answer.**

Everything follows from that:

- **No `PlaceDwellRollup`, no attribution row, no `ROLLUP_GENERATION`, no backfill.** The Fit rollup
  needs all three because a *count* is not idempotent under re-save; a **recompute from a window is
  a pure function of that window**, so re-running it is free and self-correcting.
- ⚠⚠ It is therefore **structurally immune** to the drift class that cost Fit 625 of 10 517 rows on
  a real device (aggregate committed, attribution left in the write mailbox when the process died).
  There is no incremental bookkeeping to get wrong.
- **The repair lever is free.** Change the heuristic, the window size or the timezone rule, and the
  next pass simply produces the right answer — there is no stored derived state to invalidate.

Read via `ExecutionContext.readOrderedWindow(...)`
(`core-common/.../proxy/OrderedWindowFusion.kt:273`, landed 2026-07-30), ordered by
`Moment.Fields.TIMESTAMP` with a `keepFields` projection. `MomentReconciliationPass.kt:118-128` is
the working precedent, in the same module.

⚠ **What this must never do is ride the client-side moments read.** `TimelineProjectionRepository`'s
`momentsFlow()` is unbounded and `places()` is O(places × moments) over it — that read is what
blanked Timeline Home at ~10.5k rows ([`fit-read-scaling.md`](fit-read-scaling.md) §Opened by),
and `timeline-recognition-and-place-naming.md:1232` records a place-side pass that was abandoned
for exactly this reason. Computing roles in the projection would walk straight back into it.

### D4 — It runs in `:timeline-service-proxy`

The derivation needs `Moment` rows (timeline) and writes a `PlaceRole` row (place). Only one
direction of that edge exists, and it is the one we need: **`:timeline-service-proxy` already
depends on `:place-service`** (`timeline-service-proxy/build.gradle.kts:43`), and
`MomentReconciliationPass` already imports `Place` + `PlaceService`. `:place-service-proxy` has no
timeline dependency and should not grow one.

The inferred write goes through `executionContext.saveData(placeRole)` directly — no operation
needed, following `PlaceReconciler.kt:119-128`, which writes `PlaceMerge` the same way. ⚠ Never
`Database.saveData`, which bypasses both the `SyncObserver` and the `RelationMaintainer`.

The **user** write does need an op (it originates in the client): `RecordPlaceRole`, a copy of
`RecordPlaceMerge.kt`.

### D5 — The heuristic (de-Googled, no model)

Over the window, per place, sum dwell seconds into two buckets:

| role | bucket | gates |
|---|---|---|
| `HOME` | 22:00–06:00 local, any day | ≥ `MIN_DISTINCT_DAYS` (5) distinct local dates, and ≥ 30 % of all night dwell in the window |
| `WORK` | 09:00–17:00 local, Mon–Fri | same gates, **and** not the place chosen as Home |

Below a gate → **suggest nothing**. A wrong confident guess costs more than silence: the user has to
actively refuse it, and it teaches them the app guesses badly. Dwell comes from
`Moment.durationSec` on `kind == Visit` moments whose `subject.kind == place`; a visit spanning a
bucket boundary is split proportionally across buckets.

### D6 — ⚠ Timezone: derive under the device zone, and accept the noise

`Moment` carries **no timezone or UTC-offset field** (`Moment.kt:48-83`) — a visit recorded abroad
has no stored local-time context. Bucketing uses `TimeZone.currentSystemDefault()` at derivation
time, matching what `TimelineProjectionRepository` already does everywhere.

The failure mode is travel: a week in Tokyo buckets under the home zone, so a hotel accrues
"night" dwell. The `MIN_DISTINCT_DAYS` + 30 %-share gates make it very unlikely to unseat a real
Home, and D3 means a bad pass is corrected by the next one rather than being baked in. A proper fix
is a stored offset on `Moment` — ⚠ blocked by the same op-parameter rule as `Place`, so it would be
its own sibling row; **out of scope, recorded here so the constraint is not rediscovered.**

### D7 — Synced, with a merger that prefers the user

Roles belong on every device — `Syncable(SyncStrategy.Eager, ConflictStrategy.Merge)`, same traits
as `PlaceMerge`. `PlaceRoleMerger` resolves: **`USER` beats `INFERRED`**; same source → newest
`decidedAt` wins.

Two devices independently inferring is safe: the derivation is a pure function of `Moment` rows,
which sync eagerly, so they converge. A device that is still catching up is held back by the
`MIN_DISTINCT_DAYS` gate rather than writing churn.

⚠⚠ **The merger must be registered in `PlaceProxyInitialization`, not only in tests.**
`PlaceProxyInitialization.kt:24-30` records the precedent scar: *"`MomentMerger` and `TripMerger`
were built, registered in tests only, and left out of production — so every timeline type silently
ran on last-writer-wins."*

### D8 — Dismissal persists (unlike the trip-suggestion precedent)

The UI shape is borrowed from `InterludeCard` (`TimelineCards.kt:64-103`) — an inline prompt with
a dismiss `OutlinedButton` and an accent accept `TextButton`. **The persistence is not.** The trip
suggestion's "dismissed" is an in-memory `Set<String>` that resets on app restart
(`TimelineProjectionRepository.kt:236-238`) — deliberate, because `timeline-services.md:409` locks
pending trip suggestions as per-device and non-syncing.

A role dismissal is a durable statement about a place, so it takes the **write** shape from
`renamePlace` instead: a real row, synced, `roleSource = USER`. Take the interaction from the
Interlude, the persistence from rename.

### D9 — A role outranks the label in the feed

When a place has a `USER` role, the Timeline feed shows **`Home`** rather than the place's own label
(a street address). This is what makes §11's `Home → Work` movement titles read the way
the maintainer asked for, on real data rather than on hand-named places. The place *detail* screen
still shows the real name with the role as a badge — the short form is a feed affordance, not a
rename.

---

## 4. Phases

Each is independently shippable and independently revertible.

### PR-1 — the row (no producer, no UI)

The 16-site registration checklist, which is the bulk of the work and where silent failures live:

| # | Site | Add |
|---|---|---|
| 1 | `place-service/.../PlaceRole.kt` *(new)* | interface + `Schema` + `create` + `Fields` + `PlaceRoleDataIdProvider` (string key on `PLACE_ID`) + `object PlaceRoles` / `object RoleSource` |
| 2 | `PlaceService.kt:34,61-74` | `val roles: TList<PlaceRole>` + `serviceDataRelation(ROLES, maintained = true)` + `Relations.ROLES` |
| 3 | `PlaceService.kt:49` | `version = 2` → **3** |
| 4 | `PlaceServiceCommonModule.kt:64-68` | `composite(PlaceRole::class, PlaceRole.Schema, ::PlaceRoleDto)` |
| 5 | `PlaceServiceCommonModule.kt:70-76` | `trait(...)` for the DataIdProvider |
| 6 | `PlaceServiceCommonModule.kt:98` | **new** `schemaMigration(PlaceService.ID, fromVersion = 2) {}` *alongside* the existing `fromVersion = 1` line — do not edit that one |
| 7 | `place-service-common/.../impl/PlaceRoleDto.kt` *(new)* | 13-line copy of `PlaceMergeDto` |
| 8 | `place-service-common/.../impl/PlaceRoleDataIdProviderImpl.kt` *(new)* | verbatim copy |
| 9 | `place-service/.../RecordPlaceRole.kt` *(new)* | op, copy of `RecordPlaceMerge` |
| 10 | `PlaceServiceClientModule.kt:49-53` | ⚠ `type(PlaceRole::class, …)` — **client** module. Omitting it kills every call as an opaque `error/internal` (the Workout.Streams / TS-4 scar, quoted at `:34-37`) |
| 11 | `PlaceServiceClientModule.kt:55-65` | `operation(RecordPlaceRole::class, …, ::RecordPlaceRoleRo)` |
| 12 | `place-service-client/.../impl/PlaceServiceRo.kt:30` | `override val roles get() = serviceRelation(...)` |
| 13 | `place-service-client/.../impl/RecordPlaceRoleRo.kt` *(new)* | copy |
| 14 | `PlaceServiceProxyImplementationFactory.kt:17` | `RecordPlaceRole.ID -> RecordPlaceRoleImplementation()` |
| 15 | `place-service-proxy/.../executor/RecordPlaceRoleImplementation.kt` *(new)* | copy |
| 16 | `PlaceProxyInitialization.kt:53-55` | ⚠⚠ `mergerRegistry.register(PlaceRoleMerger())` + the merger itself |

Plus: `TENSHU_STORAGE_FORMAT_VERSION` **16 → 17** in `core-common/.../StorageFormat.kt:164` with a
history bullet (HR #9 — a brand-new synced type is exactly the v3/v5/v15 precedent: a peer that
predates the type parses the block to null and silently drops it). **No relay redeploy** — since E2e
the 426 gate reads `RELAY_PROTOCOL_VERSION` only.

**Tests:** `PlaceRoleMergerTest` (user-beats-inferred, newest-wins), and add `PlaceRole` to
`MapsPlacePipelineIntegrationTest` — per the research that is the one test exercising common +
client + proxy factory + `initPlaceService` together, i.e. the cheapest guard against a missed
registration.

#### PR-1 as built (2026-07-31)

All 16 sites plus the `StorageFormat` 16 → 17 bump landed as specified. Green on the full
`desktopTest jvmTest test` gate, with the affected modules re-run under `--rerun-tasks` (70 tasks
executed) rather than trusted up-to-date. Two additions beyond the spec, both kept:

- **`RoleSource.isUser(source)`** rather than raw `== USER` comparisons — it makes the
  degrade-to-the-weaker rule (anything not exactly `user` is not user-authored) a single place
  rather than a convention every call site has to remember. Mirrors `FingerprintProvenance.isUser`.
- **A third, lexicographic tie-break in `PlaceRoleMerger`** for the residual case of equal
  `roleSource` *and* equal `decidedAt` but differing `role`. The spec's two rules leave that tie
  unresolved, and an unresolved tie in a merger is not cosmetic: it resolves by arrival order, so
  two devices converge to *different* answers. Arbitrary but identical everywhere is the property
  that matters. ⭐ Worth generalising — every rule in a `SemanticMerger` must be symmetric in
  `existing`/`incoming`, since the merger fires only when the incoming block is strictly newer and
  so cannot see "which side is mine".

### PR-2 — the detector + the pass

- **`PlaceRoleDetector`** in `:timeline-service-common` — pure, dependency-free, `commonMain`,
  mirroring `TripSuggester`/`TripDetector`. Input: a list of `(placeId, startMs, durationSec)` +
  a `TimeZone`. Output: `RoleCandidate(placeId, role, confidence)` or nothing. **Fully unit
  testable with no proxy**, which is where the heuristic's real coverage lives (night shift,
  a hotel week, ties, insufficient days, a place that is both).
- **`PlaceRoleInferencePass`** in `:timeline-service-proxy` — a `DataObserver` alongside
  `MomentReconciliationPass`: on a `Visit` moment save, increment an in-memory counter; every
  `RECOMPUTE_EVERY_N_VISITS` (20) and once at `initTimelineService`, read the window via
  `readOrderedWindow`, run the detector, and write `INFERRED` rows.
  - ⚠ **Change-gated**: write only when the verdict actually differs from the stored row, or the
    pass re-enters its own observer. `MomentReconciliationPass.kt:49-53` states the same
    discipline ("the second run over a settled window writes nothing").
  - ⚠ **Change-gated**: write only when the verdict actually differs from the stored row.
  - ⚠ Skip any placeId already carrying a `USER` row.
  - The counter is in-memory on purpose: a proxy restart triggers a pass anyway, so it needs no row.

#### PR-2 as built (2026-07-31)

Shape as specified. Four things the build settled that the spec had not:

- ⭐ **The detector takes wall-clock millis, not `(epochMs, TimeZone)`.** `:timeline-service-common`
  is deliberately dependency-free (`TripSuggester`: *"this module deliberately stays dep-free"*), so
  it cannot resolve a zone. Pushing the conversion to the proxy boundary turned out better than a
  workaround: **D6's known soft spot is now an explicit, testable input** rather than something
  buried inside an hour-of-day helper. `:timeline-service-proxy` gains `kotlinx.datetime`
  (`control-shared-proxy` precedent) and does the conversion per instant, so DST is handled.
- ⚠⚠ **A verdict must be able to withdraw itself.** Not in the spec, and load-bearing: without it a
  home that *moved* leaves its old row saying `home` forever and the user has two. The pass now
  reconciles over `candidates ∪ previously-inferred`, writing `role = none, roleSource = inferred`
  for a place that lost its role. Two guards keep that from spamming: never touch a `USER` row, and
  never mint a `none` row for a place that had none (or every place ever visited accumulates one).
- ⚠ **Superseded moments are excluded.** `MomentReconciliationPass` folds two devices' captures of
  one visit; counting both would double that place's dwell and could hand it a role it has not
  earned. Covered by a test.
- ⚠ **`recomputeEvery` 20 → 5, found by a test.** The wiring test failed at 20 and the constant was
  wrong, not the test: **visits are low-frequency events**, a handful a day, so 20 of them is several
  days — nothing like 20 file writes. The eager run at `initTimelineService` happens *before* those
  visits exist, so a fresh device would sit on sufficient evidence for days saying nothing. 5 bounds
  first-verdict latency to about a day at a cost of a few passes per day.

Tests: `PlaceRoleDetectorTest` (12 — the heuristic, including midnight-spanning stays, edge-clipped
visits, the hotel week, four-way splits, weekend-only places, and order-independence, which two
devices depend on) + `PlaceRoleInferencePassTest` (8 — user-wins, dismissal-not-re-suggested, the
withdraw case, settled-window silence, superseded exclusion, and the production wiring). Full
`desktopTest jvmTest test` green. No schema, wire or `TENSHU_STORAGE_FORMAT_VERSION` change — PR-1
shipped the row; this only writes to it.

⚠ Still unverified on device: the window size (2 000) wants one look at real capture density, and
the whole feature wants a real timeline behind it before PR-3 renders a prompt from it.

### PR-3 — the UI (Timeline)

- `TimelineRepository`: `placeRoles(): Flow<Map<String, PlaceRoleModel>>` and
  `suspend fun setPlaceRole(placeId, role)`. ⚠ Give both **default no-op bodies** — the interface's
  own convention (`:69`, `:78`) so the iOS EPC read-only impl stays source-compatible.
- **Place detail** — the prompt sits between the stats line (`PlaceDetailScreen.kt:126-130`) and
  `TagChips` (`:131`): still inside the identity `Column`, below the facts that justify the guess,
  above the full-bleed history section. A confirmed role renders as a chip in the `TagChips` row.
- **Places tab** — the role rides the third, accent-coloured metadata line of `PlaceRow`
  (`TimelineHomeScreen.kt:204-235`), which already carries `"N visits · last …"`.
- **Feed** — D9: `momentTitleText` prefers a `USER` role name over the place label.
- All strings via `stringResource` (ADR 0016; note `InterludeCard`'s hardcoded strings are a
  pre-existing gap, not a licence).
- HR #8: this phase touches Timeline Android UI → **run `scripts/android-smoke.sh`**.

#### PR-3 as built (2026-07-31)

Shape as specified — repository surface, the confirm prompt on place detail, the role on the
Places-tab row, and D9 in the feed. Notes worth keeping:

- **Two model fields, not one.** `PlaceRoleState(role, isSuggestion)` rather than a bare string:
  an inferred role is a **question** the UI asks, a user-authored one is a **fact** it shows, and
  collapsing the two would let a guess render as an answer — the exact overreach the confirm step
  exists to prevent. `isConfirmed` (user-authored *and* not `none`) is the only thing allowed to
  stand in for a place's name.
- ⚠ **The feed carries role *tokens*, not display names** (`Moment.placeRole` / `originRole` /
  `destinationRole`). Localisation happens in the UI, so "Home → Work" reads correctly in every
  locale. Carrying the name through the projection would have re-introduced exactly the
  unlocalised-title bug §11 removed.
- ⚠ **An unrecognised role falls back to the place's own name.** `placeRoleName()` returns `null`
  for anything but `home`/`work`, so a `gym` synced by a newer peer is stored, synced and ignored
  rather than printed at the user as a wire token. Same degrade-don't-guess rule as `movementMode`.
- ⚠ **`places()` filters to confirmed roles; `place(id)` does not.** The detail screen is the one
  surface that *asks*, so it needs the un-filtered state; every other surface must never rename a
  user's rows on a guess.
- ⚠ **Dismissal is a real answer, so the button says "Not my home", not "Not now".** The UI shape is
  borrowed from `InterludeCard`, but its persistence deliberately is not: the trip suggestion's
  `resolvedSuggestions` is an in-memory per-session set, which here would make the prompt reappear
  on every launch.
- **`combine` caps at five typed sources**, so places and roles are paired into one
  (`placesWithRoles`) rather than passed as a sixth — the untyped vararg form would cost every
  lambda parameter its type. The roles flow follows `mergesFlow`'s `onStart { emit(emptyMap()) }` +
  `catch { emit(emptyMap()) }` discipline, for the documented reason: one silent `combine` source is
  what blanked Home at ~10.5k rows.

⚠⚠ **One bug the tests caught, worth recording as a class**: the `roles` map was threaded into
`mergedToUi` but its single-moment delegation still called `moment(...)` without it, so every
*un-merged* visit — i.e. almost all of them — silently carried no role. It was a patch whose anchor
did not match and which therefore did nothing. Tests over `days()` rather than over `moment()` are
what surfaced it; a unit test of the inner function would have passed.

Tests: 3 new `TimelineProjectionTest` cases (role replaces the name on a visit, endpoints carry
roles alongside names, no role → null). Full `desktopTest jvmTest test` green; Android smoke gate
run per HR #8. No schema, wire or format change.

⚠ Remaining: the on-device pass. The gates (5 distinct days, 30% share) and the 2 000-moment window
are still guesses, and this is the slice where a miscalibration becomes visible — the app
confidently asking the wrong question.

### PR-4 — consumers

- `TripSuggester.deriveHome()` prefers a stored `HOME` role, falling back to today's grid-cell
  heuristic — closing the TODO its own KDoc has been carrying.
- Maps' `MapsPlaceProjection.kt:37` stops hardcoding `PlaceCategory.Store` for roled places.

---

#### PR-4 as built (2026-07-31), with the window correction

- **`TripSuggester.suggest(..., storedHome)`** — a confirmed `home` role takes precedence over
  `deriveHome`'s most-frequented-cell heuristic, closing the TODO that KDoc has carried since I5c.
  The heuristic stays as the fallback, because on a fresh device it is what runs: roles need visit
  history the device does not have yet. ⭐ The test is worth reading — a stored home does not merely
  *refine* the answer, it **inverts** it: for someone whose recorded life is mostly in Lisbon but
  who lives in Berlin, the heuristic reports the Berlin days as the trip.
- **Maps** stops hardcoding `PlaceCategory.Store`: a confirmed role projects to `Home` / `Work`, so
  the map icon and accent become real. ⚠ Category only, not `label` — the chip text is user-facing
  and `:maps-shared-data` is string-free by construction. A place with **no** role keeps `Store`,
  because "we do not know" is what that default has always meant.
- ⚠ The role token is duplicated as a private constant in `:maps-shared-data` rather than imported:
  Maps deliberately does not depend on Timeline's model, and the place-service constants are wire
  strings. Two consumers agreeing on a string is the `MovementModes` posture.

**The window fix, and why it became two constants.** `DEFAULT_WINDOW` drops 2 000 → 400, and a new
`maxAgeDays = 90` does the work the count was pretending to do. ⭐ A row count is a
**density-dependent** proxy for "recent" and therefore a bad one: at a few visits a day, 2 000
moments spans *years* — which makes D3's premise ("a flat you moved out of a year ago must stop
being Home") vacuous — while on a busy device the same number spans weeks. The count now bounds only
the read cost; the age cut expresses recency in the unit it actually means. Covered by
`aHomeYouMovedOutOfExpires`.

⚠ Landing the age cut broke five PR-2 tests, correctly: their fixture placed visits in 1970 with
"now" in 2027. Anchoring the fixture to the real clock is the right fix — the production wiring test
exercises the default `Clock.System.now()`, so an epoch-era fixture could only ever have passed
while the cut did not exist.

## 5. Explicitly out of scope

- **A general place taxonomy** (`cafe`, `gym`, `restaurant`). That is
  [`place-geocoding.md`](place-geocoding.md) §6's deferred `category` question — a *geocoder-derived
  classification*, orthogonal to a *user-declared role*. The open vocabulary means it can land later
  without a schema change, but it is a different feature with a different source of truth.
- **A stored timezone on `Moment`** (D6) — blocked by the same op-parameter rule; own slice.
- **Auto-applying a role without confirmation.** Explicitly rejected in conversation.
- **Geofenced automation** ("when I arrive Home…"). That is the routines engine's business; a role
  is a fact, not a trigger.

## 6. Threat model

**No amendment owed.** `PlaceRole` adds no new asset class and changes nothing the relay can see —
it is an ordinary E2E-encrypted place row, and `Place` already carries the coordinates and labels of
where the user lives, which is strictly more revealing than the fact that one of them is flagged
`home`. HR #2's triggers (A1–A4, A9, the relay's data shape, the sync protocol) are untouched.

⚠ Worth a maintainer sanity check regardless: the row makes "which of these is the user's home" a
*single-field lookup* for anyone who obtains a decrypted store, where before it required inference
over visit history. That is a change in convenience, not in exposure — but it is the kind of thing
worth being deliberate about, and A35 (location capture) is the neighbouring entry.

## 7. Open questions

1. **Window size.** Proposed 2 000 moments / ~6 months, whichever is smaller. Wants one look at real
   capture density on device before fixing.
2. **Should an `INFERRED` row sync at all?** D7 says yes (simplest, one type, converges). The
   alternative — inferred stays local, only `USER` rows sync — needs two types, because `Syncable`
   is a type-level trait. Revisit only if inferred churn shows up on device.
3. **Multiple homes.** A weekday flat and a weekend house is a real pattern that the current
   single-winner heuristic renders as a coin flip between them. v1 suggests one; if this bites,
   the key is already `placeId`-not-`role`, so N places can carry `HOME` without a schema change.

## 7. The device pass (2026-07-31) — what it did and did not establish

Run against the maintainer's real phone (ColorOS, live capture) **without installing** PR-1..PR-4,
because the device sat on storage format v16 and today's build is v17: a core-version bump wipes the
local store and, per `SyncProxyInitialization`, *"a core bump wipes every replica at the same
instant: no peer retains a copy to re-sync"*. Getting the code onto the phone would have destroyed
the capture history it was meant to be validated against.

**Established:**

- ⭐ The §11 feed headlines are correct on real data: the venue's name rather than `Visit`,
  `Drive to <venue>`, `Walk to <street>`, with `Drive · 26.2 km · 33 min` as the meta line and no
  duplication. The partial-route form (`Drive to X`, origin unresolved) is the adjacency + gap guard
  behaving as designed.
- ⚠⚠ **54 of ~63 places carry zero visits**, and a sampled one shows `HISTORY · 0 OF 0` with a blank
  `since` — i.e. it has *never* had a visit moment, not a superseded one. Every creation path was
  ruled out as the cause: capture (`VisitDetector`) and the Google import both write place + moment
  1:1, `MovementMapper` mints no places, `PlaceLabelEnricher` only upserts rows it has read,
  `PlaceIdentityResolver` repoints moments *and* drops folded places, `MapsPlaceSeeder` seeds
  Firenze mocks, and the capture outbox is file-backed so a process kill replays rather than
  half-writes. The moments were therefore **deleted underneath the places** — most plausibly by the
  2026-07-29 data reset that also emptied Fit's history — leaving orphans nothing reclaims. ⚠ Not
  confirmed: Control's Data screen does not enumerate Timeline's types, so there is no count to read.

**NOT established, and a correction.** An earlier reading of this data concluded that
`minDistinctDays = 5` "can never fire" and treated 2–7 moments/day as steady-state capture density.
That was wrong: the store holds roughly **two days** of post-reset history, so the denominator was
broken. The gates are **untested, not disproven**, and no window size can be derived from a two-day
sample. The window change above rests on the count-vs-duration argument, which holds at any density,
not on that measurement.

**Still owed:** an end-to-end run of the inference against real history — which needs either a device
willing to take the v17 wipe, or a few weeks of capture accumulating first.

## Version

Place roles v0.1 · 2026-07-30 · design only, no code.
