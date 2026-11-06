# Fit read scaling — aggregates-over-scans, paged lists

**Status:** **Phases 1 + 1b + 3/4 SHIPPED; ordered-window fusion (the §Latency primitive) SHIPPED
as `core-ordered-window-fusion.md` F1.** Phase 1 (windowed workout read) on-device verified
2026-07-14. 2026-07-25, in sequence: the §Latency investigation; Phase 1b (flow sharing + Profile
un-gate); the ordered-window fusion; **Phases 3/4 (maintained rollups + sample/sleep read
windowing — as-built in §Rollups below)**. Also 2026-07-29: `WorkoutActivityRollup` (§Rollups as built) and the F2 ordered window.
**2026-08-12: `sampleHistory` is windowed** —
per-metric and server-side (§Revised fix ladder), which was the last unbounded read in Fit; every
list read now has a bound. **Phase 2 (paged History feed) SHIPPED 2026-08-12** — as-built in
[`fit-history-paging-handover.md`](fit-history-paging-handover.md) §8; ⚠⚠ it bought *reach*, not
speed, and the two workout reads it sits beside are both still there on purpose (§3 of that doc).
Remaining: the §9.17 property-sort wire fix (API consistency only).

---

## Rollups as built (Phases 3/4, 2026-07-25)

**`LatestSample`** (`:health-service`): one maintained row per `(metric, resting)` holding the
newest reading — the Browse Body/Vitals teasers + detail current-value headers read ≤ a handful
of rows instead of scanning the whole `HealthSample` time-series (the unbounded read Phase 4
flagged as the next OOM candidate; a 10-year import is tens of thousands of samples, and
Browse's `categories()` rode it).

- **Locally derived, never synced** — deliberately NOT `Syncable`. The maintainer is a
  `DataObserver` (`LatestSampleMaintainer`, `initHealthService`), and the save path it observes
  carries *both* the importing device's writes *and* rows arriving via sync — so every device
  derives an identical rollup locally, exactly the relation-membership doctrine. Syncing a
  derived row would only add convergence noise.
- **Upsert-iff-newer**: the rollup row is overwritten only when the incoming reading's time is
  ≥ the stored one — an out-of-order historical import after live captures never regresses the
  teaser.
- **Backfill** (`backfillLatestSamples`): a store that held samples before the rollup shipped
  derives it once at proxy init (member-hash emptiness probe, then one O(N) scan). A fresh
  store syncing from scratch never scans — arriving samples build the rollup incrementally
  through the observer.
- **Known limit** (documented on the type): deleting the sample that *was* the latest leaves
  the rollup stale until that metric's next write. Samples are append-mostly; no delete surface
  exists.
- **Format**: purely additive (new type + new relation, no existing row reshaped, rollup rows
  never on the wire) → no `TENSHU_STORAGE_FORMAT_VERSION` bump (the `MovementTrace` precedent).

**Sleep windowed** (`FitProjectionRepository`): `sortByDescending { date }.take(366).select` —
the ordered-window-fusion shape, one projected pass. Needed a SleepSession-TYPED client RO
(the Workout recipe — a sort selector lambda can't read a field off a bare `CompositeRo`).

**`WorkoutActivityRollup`** (`:health-service`, 2026-07-29): one maintained row per `activityType`
holding its count — the lifetime "how many of each" aggregate. Same doctrine as `LatestSample`
(locally derived, never `Syncable`, `DataObserver` on the save path, backfill-if-empty at proxy
init, purely additive ⇒ no format bump).

- **Opened by a live blank screen, not a profile**: Timeline's Activities tab grouped *every*
  workout by type just to count them, and its Home feed `combine`d that read with four others. At
  ~10.5k workouts (one full Health-Connect import) the read stopped completing — measured on device
  at 200 rows ≈ 2 s, 2 000 ≈ 338 s, 10.5k never, pegging the proxy CPU — and because `combine` waits
  for every source, the one unfinished read blanked the whole feed.
- ⚠⚠ **A count is not a max, so it is not idempotent for free.** `LatestSample` gets away with a
  blind upsert because "newest wins"; a counter re-incremented on every sync re-delivery and
  re-import would inflate without bound. Exactness rides a second locally-derived row,
  **`WorkoutRollupAttribution`** (one per workout, recording the type it was last counted under),
  which makes first-sight / re-save / type-changed distinguishable in O(1) with no rescan.
- **Deletes are handled** (unlike `LatestSample`'s documented stale-on-delete): the attribution
  names the bucket to decrement, so a `DeleteRealmData` reset of `Workout` leaves the rollup at
  zero rather than advertising counts for rows that are gone. Zeroed types are dropped by the
  presentation layer.
- ⚠⚠ **The count and its attribution commit in ONE transaction.** Found on the device pass, not in
  tests: 625 of 10 517 workouts were counted but carried no attribution row, because the rollup
  write was transactional while the attribution was a bare `saveData` still sitting in the store's
  write mailbox when the process was killed. A committed increment with no attribution reads as
  "never counted" on the next save and increments a second time — silent, permanent upward drift.
  Pairing them makes it all-or-nothing; "neither" self-corrects on the next save.
  (`writeRollup` therefore opens no transaction of its own — nesting one inside the pairing was
  the mechanism.)
- ⚠ Test note: saving workouts through a live maintainer and *then* calling the backfill does not
  exercise the scan — the rollup rows already exist, so the emptiness probe short-circuits. The
  scan needs a store deliberately reset to its pre-rollup shape.
- **Cost**: one extra small row per workout; ~0.19 ms per save end-to-end (10k saves through the
  full observer chain in 1.9 s, JVM).
- **Repair lever**: `WorkoutRollupState` stamps which `ROLLUP_GENERATION` derived the current rows.
  A derived view keeps whatever *past* code wrote — fixing the maintainer does not fix rows already
  on disk — so **bump the generation whenever the derivation changes** and every device re-derives
  once at proxy init. A re-derive REPLACES (clears first), so a type whose workouts have all gone
  loses its stale count instead of keeping it forever.
- **Device-verified 2026-07-29** on a 10 517-workout store: Home and Activities both render;
  counts sum to exactly the stored workout count and stay put across Control restarts. The
  generation bump then repaired the drift in place: attribution rows went 9 892 → 10 442, exactly
  1:1 with the workouts, with the counts still summing correctly (8332 + 1137 + 839 + 134).

**Weekly rollup deliberately NOT built**, per this proposal's own guidance: the daily
`DailyActivity` read (~3.6k rows for a 10-year history) is cheap and week math stays in the
VMs. Revisit only if the daily read shows up in a profile.

---

## Latency investigation (2026-07-25) — why Phase 1 wasn't enough

Maintainer report: Home / History / Profile all load noticeably slower than Drive; Profile —
"mostly static data" — worst of all. Code-path analysis (not a profile run) found four compounding
causes:

1. **The `take(300)` window sits *above* a sort, and sort defeats every windowing optimisation.**
   `SortByImplementation` resolves the whole array (`resolveList` → `getArrayData` with **no
   projection** — every workout row fully materialised), then extracts the key per element. Because
   the property-selector form dies on the wire (`fit-app.md` §9.17), Fit uses the lambda form
   (`sortByDescending { start }`), so each row's key is an **interpreted `evaluateCallstack` walk**.
   Only then does `take(300)` discard the rest. The engine's window fusion + `captureProjection`
   pushdown apply only to a chain whose *leading* steps are `drop`/`take` (Drive's `PagingUtil`
   shape: `list.drop(pos).take(n)` then project, one-shot `getAll()`). A sort-first chain gets
   neither — the Phase-1 status line's "materialises only the recent window" was true of `select`,
   not of the sort feeding it. Drive hits the same wall and dodges it
   (`TenshuFilesRepository.getFilesPaged` documents that a *sorted* listing punts to
   whole-folder-single-page) — but a folder is naturally small; Fit's list is the whole store, on
   every screen, by default.

2. **Profile's first paint is gated on the heaviest read, for almost nothing.** The VM `combine`s
   `profile()`/`goals()` (instant in-memory StateFlows) with `workouts()` — and `combine` emits
   nothing until *all* sources emit. All Profile derives from the 300-workout read is
   `map { it.source }.distinct()`: two or three provenance badges.

3. **No flow sharing; every tab switch re-pays everything.** Home opened the workout sort **twice**
   (the `aggregates()` fallback + `latestWorkout`) plus the full `DailyActivity` table; History and
   Profile each again; Browse's `categories()` opens ~7 streams including the **unbounded
   full-table `healthSamples` read** (the per-metric time-series — the Phase-4 prediction, still the
   next OOM candidate). The repository did no `shareIn`, and `koinViewModel` is destination-scoped,
   so each tab visit recreated the VM → fresh proxy-side stream → full re-sort → 300 entities
   re-serialised over IPC before the screen's `combine` could emit once.

4. **Reactive re-runs multiply the compute.** Each live stream re-executes its entire chain (full
   sort included) on every store change; the `StreamRegistry` per-item diff bounds IPC traffic, not
   proxy compute.

### Revised fix ladder

- **Phase 1b — quick wins, no core change (implemented 2026-07-25):**
  `FitProjectionRepository` shares each store-input stream via
  `shareIn(WhileSubscribed, replay = 1)` (one proxy-side subscription per source across all
  screens; tab revisits render instantly from replay; Home's double workout subscription
  collapses), and Profile no longer gates on `workouts()` (the sources flow starts with an
  immediate empty emission, so profile/goals paint instantly and badges fill in).
- **§9.17 fix (medium):** make the property-selector sort (`sortByDescending(Workout.Start)`)
  round-trip on the wire so the per-row key is a direct stored-value read, not an interpreted
  callstack. Still O(N), but a much cheaper N.
- **Maintained rollups (Phases 3/4 — SHIPPED, see §Rollups as built):** latest-per-metric as a
  maintained record (the `DailyActivity` pattern); kills Browse's full-table samples read. Note
  for the recurring "use `@defer` DynamicProperties like Drive" intuition: deferred properties
  solve *per-row expensive projections in a list* (Drive's `File.Preview`); Fit's problem is
  *cross-row aggregates*, which want a precomputed row read in O(1).
- **Ordered-window fusion — F1 SHIPPED (see
  [`core-ordered-window-fusion.md`](core-ordered-window-fusion.md) §8 as-built):** a fourth
  walker peephole (beside window fold / count / by-key search) fusing `sortBy(key).take(n)` +
  downstream `select` into a single projected pass (merged key+select projection, bounded
  stable top-N) — O(N thin reads) instead of O(N full-entity + per-row interpretation). Fit's
  chain fuses as-is with no call-site change (both selector forms accepted → §9.17 stops being
  perf-critical); reactive re-runs take the fused path via the StreamRegistry re-evaluation.
  Drive's sorted listings becoming pageable is the optional F2 follow-up.
- **Phase 2 paging — SHIPPED 2026-08-12** (as-built:
  [`fit-history-paging-handover.md`](fit-history-paging-handover.md) §8). What follows is the
  sequencing argument that got it here; it is history now, and the two ⚠ gates below are both
  closed.
  Unchanged in intent, but explicitly *sequenced after* the sort read is cheap —
  plain `:core-paging` fuses only a leading window, so paging a sorted feed today would re-sort the
  whole store per page. ⚠ **That gate is closed** — F1 (2026-07-25) fuses `sortBy(key)` with the
  page's `drop(pos).take(n)` window. (This line credited F2's `orderedSource = true` and its
  `take(pos + n).drop(pos)` cut as "exactly the shape F1 fuses"; both orders fold the same, and the
  flag was retired 2026-09-15 — `drive-sorted-folder-paging.md` §10.6.) Phase 2 is viable now; see
  the status header.
  ⚠ A **second** gate existed and was invisible until someone tried: `:fit-shared-logic` targeted JS
  (`fit-app.md` §9.10) and `androidx.paging` publishes no `paging-common-js`, so it could not take
  `:core-paging` at all. Closed 2026-08-12 by extracting `:fit-shared-file` (§9.10a) — the unblock
  is verified, not assumed.
  ⚠⚠ **But do not expect it to save a read.** Home is the default tab and reads `aggregates()`,
  whose own workout window is fetched at launch whatever History does — so paging History **adds**
  a read rather than replacing one. Its case is uniformity with Drive, no magic constant on the
  feed, and making `fit_history_earlier` (today a dead label) implementable. Perf is a wash.

- **⚠⚠ The workout window was bounded on the wrong axis for `aggregates()` — FIXED 2026-08-12.**
  `dailyAggregates` merges workout-derived values into the stored `DailyActivity` rows **per
  field**, notably `activeMinutes`, which Health Connect always imports as 0. So a user who trained
  more than 300 times in a year had the oldest days of the **Year chart** silently lose their
  corrections — the ring and the trend fell back to a stored 0 on days they had actually trained.
  A count bound cannot express what this consumer needs. `aggregates()` now reads
  `searchAll { start inRange (today − ChartRange.Year, +∞) }`, so the bound is derived from the
  widest range any chart renders rather than from a constant.

  ⚠⚠ **It is a SECOND read over `workouts`, and that duplication is the finding, not an
  oversight.** History wants *the most recent N whatever their age*; `aggregates()` wants
  *everything inside the last year however many that is*. `max(date, count)` is not expressible as
  one chain. The first attempt at this fix changed the **shared** read to a date bound and broke
  two tests whose fixtures are dated 2023 — precisely the truncation a real user with an older
  import would have seen in History. That failure is what established the split as necessary, and
  it is why the obvious "just make it one read again" is wrong.

  ⚠ Neither chain fuses now (a filter ahead of the sort disarms the peephole, gate 2). Accepted: a
  bounded correct read beats an unbounded fused one. Pinned by
  `FitPipelineIntegrationTest.aggregatesCoverTheWholeYearChart_evenWhenAWorkoutCountWindowWouldNotReach`
  — 400 workouts over 200 days; point `aggregates()` back at the shared count read and it fails
  with the oldest trained day missing.

  ⭐ It also **unblocks Phase 2 cleanly**: `recentWorkoutInputs()` now has one consumer class
  (History, Profile sources, detection dedup, Home's latest card) and no longer owes the Year
  chart anything, so paging it cannot silently shorten what the aggregates see.
  ⭐ The brief for that work is [`fit-history-paging-handover.md`](fit-history-paging-handover.md).

- **Rendering, as distinct from reading (2026-08-11).** `HistoryScreen` was a
  `Column(verticalScroll)` over `state.days` — it composed the *whole* 300-workout window on first
  paint **and again on every return to the tab**, because a nav entry below the top of the back
  stack leaves composition and the pop re-runs the subtree (behind a 300 ms fade, which is what
  made it read as "the list reloads"). Now a `LazyColumn`, one item per day.
  ⚠ This is not Phase 2 and does not substitute for it: the *read* is still the whole 300-row
  window, and "Earlier" beyond it still has no source. It only stops the UI paying for rows nobody
  is looking at. Pinned by `HistoryScreenTest.distantDaysAreNotComposedUntilScrolledTo`, which
  asserts a far-off day is **absent from the semantics tree** — asserting on visible rows would
  have passed against the eager version too.
  ⚠ Every other Fit screen is still an eager `verticalScroll` Column. That is fine for the fixed
  short ones (Home, Profile, Record).

- **`MedicalScreen` + `CategoryDetailScreen` (2026-08-12) — and the trap in converting them.**
  ⚠⚠ History's recipe does **not** transfer, and applying it looks like a fix while being a no-op.
  History's repeating unit is the card (one per day, many days). These two are the other way round:
  a *fixed, small* number of cards — four `MedicalRecordKind` sections; one reading-history card —
  each holding an **unbounded** row list. One lazy item per card composes every row of that card in
  a single item, so a user with 500 medications still pays for 500 rows. (Written that way first,
  and caught only by asking what the test would actually assert: with ≤4 sections, "a distant
  section is not composed" is vacuously false and passes against the eager version too.)
  The rows therefore have to be the items, which means the card's surface has to span them —
  `FitPrimitives`' companion `groupedRows` emits each row with a position-dependent shape (rounded
  top, square middles, rounded bottom) and no inter-item spacing, so the seams are invisible.
  ⚠ Its one silent-failure contract: the enclosing `LazyColumn` must not set `verticalArrangement`,
  which would apply between grouped rows and split the card. Block items pad themselves instead.
  Pinned by `MedicalScreenTest.distantRecordsWithinASectionAreNotComposed`, which asserts at the
  **record** level and fails against a single-item group.
  ⚠ In `CategoryDetailScreen` only the **Latest** branch is lazy. Series is bounded by
  `ChartMath.windowAggregates(range)` (365 at the widest), Cycle is a handful of days, Empty is a
  message — a `LazyColumn` there would be ceremony.

- **`sampleHistory` — the last unbounded Fit read. CLOSED 2026-08-12.** Latest mode's history and
  its trend polyline both came from the whole retained series — tens of thousands of readings after
  a decade-long import, every one shipped over IPC and projected to open one category detail.
  Rendering it lazily bounded the *composition* cost only.

  It is now `healthSamples.searchAll { metric equalTo … }.sortByDescending { time }.take(366)`.

  ⚠⚠ **The predicate is the whole point, not the `take`.** The obvious bound — a global
  `sortByDescending { time }.take(n)` like the workout window — is *wrong* here, because the
  relation holds every metric: a metric whose readings are all older than the newest N of some
  other metric disappears entirely. Weight measured monthly vanishes behind daily heart rate and
  the screen shows an empty history for data the user has. Pinned by
  `FitPipelineIntegrationTest.sampleHistoryIsWindowedPerMetricAndNeverDropsASparseOne`, which seeds
  exactly that shape; dropping the predicate makes it fail `expected:<5> but was:<366>` — the
  sparse metric replaced by another metric's rows.

  ⚠ **This chain deliberately does not fuse.** §gate 2 above is explicit — "no filter pushdown …
  same for any filtered sort" — so the proxy materialises and sorts that metric's rows rather than
  streaming a bounded top-N. Accepted knowingly: the costs that mattered were the ship and the
  client projection, and both are now bounded. A filter-aware fused path is this document's own
  "separate, later peephole if ever needed", and now has its first named consumer.

  ⚠ It bounds the **trend** as well as the list — both derive from this one series — so a metric
  sampled more often than daily trends over less than a year. Deliberate trade.

  ⚠ Not shared through `shared()`, unlike the other reads: there would be one stream per metric,
  and a mutable per-metric cache would need locking for a read one screen opens at a time.
  Re-entering re-reads, which is cheap precisely because the read is now bounded.

---

Surfaced by the Google Takeout import (`fit-app.md` §9.6b):
a real 10-year export is ~10,745 workouts + 2,791 daily rows, and the app's screens **do not read at
that scale**. This is the read-side counterpart to the import work (which fixed the *write* side —
streaming, backpressure, durability); it is the current blocker for actually *using* an imported
history and deserves its own phase.

## The problem

Every Fit screen subscribes to a query that materialises the **whole** dataset:

- `FitProjectionRepository.aggregates()` = `combine(dailyActivityInputs(), workoutInputs())` — loads
  **all** daily-activity rows *and* **all** workouts, then computes in `FitProjection`.
- `workouts()` loads every workout for the History list.
- Home, History and Profile all subscribe to these.

At a few dozen records (the fixture / a fresh install) this is fine. At 10k+ it is not: on-device,
querying the full workout set **OOM'd Control** (the proxy process, 256 MB heap) — 7 `OutOfMemoryError`s,
heap to 638 MB PSS, and the screens went blank. Even a *fully successful* import would break the same
way the moment a screen opened. Loading everything to show a weekly total is the wrong shape.

## The direction (maintainer's steer, 2026-07-14)

Two moves, both over mechanisms that already exist:

### 1. Aggregates are precomputed and read cheaply — not scanned

Most of what Fit *displays* is an aggregate: Home's weekly active-minutes/distance ring and 7-day
trend, History's "this week" totals, Browse category teasers (latest weight, resting HR, sleep
duration, record counts). These must not require loading the raw rows they summarise.

- **`DailyActivity` is already a precomputed daily aggregate** (steps / distance / energy / active
  minutes per day), maintained incrementally by `RecordDailyActivityImplementation`. `aggregates()`
  only loads all *workouts* on top of it for the **fallback** — deriving a day's aggregate from that
  day's workouts when no `DailyActivity` row exists. After an import that writes daily rows, that
  fallback is dead weight: the workouts should not be loaded for the aggregate at all.
- **Cross-day rollups** (a week's totals, a category's record count, latest-per-metric) should be
  **maintained records**, updated on write by a **relation maintainer** (the health-service already
  registers these — `HealthProxyInitialization`), or exposed as a **`DynamicProperty`**
  (`core-deferred-properties.md`) computed once and patched on new data — rather than recomputed by
  re-scanning every read. This is the "precompute + update on new data" the maintainer asked for; the
  incremental-update substrate is the relation-maintainer pass that already runs.

### 2. Lists are paged, windowed, never whole

- The **History workout list** reads through **`:core-paging`** (`TenshuPagingSource` — the same
  mechanism Drive's file list uses), a page at a time.
- **Home / History "this week"** query a **date window** (the trailing 7 days), not the full history.
- Detail reads (`workout(id)`, with its stream blob) stay one-row — already the case.

## Rough phasing (each independently shippable)

1. **Window the aggregate reads.** `aggregates()` reads only `DailyActivity` (drop the load-all-workouts
   fallback, or scope it to days lacking a row); Home/History scope to the last N days. Biggest win for
   least work — kills the dominant scan.
2. **Page the History workout list** over `:core-paging`.
3. **Maintained rollups / `DynamicProperty`** for the remaining cross-row aggregates (weekly totals,
   category counts) so even the windowed reads become single cheap reads.
4. **Category teasers** to latest-per-metric + count queries (cheap), off the full-scan `combine`.

## Open questions

- Do weekly/category rollups want a **new maintained schema record** (like `DailyActivity`) or a
  **`DynamicProperty`** on an existing entity? The former survives sync + is queryable; the latter is
  cheaper to add. Likely: reuse `DailyActivity` for the daily layer (done), add a thin weekly rollup
  only if the windowed daily read proves too heavy.
- Does the paged workout list need server-side (proxy) paging, or is client-side windowing over the
  chain DSL enough? `:core-paging` + a bounded `select` should suffice; measure before adding a proxy op.

See `fit-app.md` §9.6b (import) and `core-deferred-properties.md` (`DynamicProperty`).
