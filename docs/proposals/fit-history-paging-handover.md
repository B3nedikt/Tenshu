# Handover — Phase 2: page the Fit History feed

**Status: SHIPPED 2026-08-12.** §§1–7 are the brief as written by the session that cleared the
blockers; **§8 is what was actually built** — including where the brief over-stated the blast
radius (§8.1) and where "`HistoryDayGroup` cannot survive" turned out to be true of Compose only
(§8.2/§8.4). Read §8 before changing anything here.

Read [`fit-read-scaling.md`](fit-read-scaling.md) §Revised fix ladder first — this file is the
delta, not a replacement.

---

## 1. Why this is worth doing, and what it is NOT worth doing for

⚠⚠ **It will not save a read, and anyone who pitches it as a performance fix has misread the
system.** Home is the default tab; it reads `aggregates()`, whose own workout window is fetched at
launch whatever History does. Paging History **adds** a read rather than replacing one.

The real case:

- **Uniformity.** Drive pages its listings through `:core-paging`; Fit hand-rolls a 300-row window.
- **No magic constant on the feed.** `WORKOUT_WINDOW = 300` is arbitrary *for History* (it is not
  arbitrary for aggregates — see §3).
- **`fit_history_earlier` is a dead label.** "Earlier →" renders at the bottom of History and goes
  nowhere. Paging is what makes it implementable.

Perf is a wash, possibly marginally worse at launch (window + first page). Say so in the PR.

## 2. What was cleared to make this possible

| Blocker | Cleared by |
|---|---|
| `:fit-shared-logic` targeted JS; `androidx.paging` publishes no `paging-common-js`, so `:core-paging` could not be a dependency | The parser moved to `:fit-shared-file`, the logic layer dropped `js`. **Verified** by adding `:core-paging` and compiling `jvm` + `iosArm64`, then reverting. |
| Sorted paging re-sorted the whole store per page | F1 (2026-07-25) fuses `sortBy(key)` with the page's window. ⚠ This cell credited F2's `orderedSource = true` and its `take(pos + n).drop(pos)` window as "the only order F1 fuses" — false when written; both orders fold identically (`core-keyset-paging.md` §6.3), and the flag was retired on 2026-09-15 (`drive-sorted-folder-paging.md` §10.6) |
| `aggregates()` shared History's window, so shortening it would silently break the Year chart | `aggregates()` has its own date-bounded read |

## 3. ⚠⚠ The trap that will bite you

**Do not "simplify" the two workout reads back into one.** There are two:

- `buildRecentWorkoutInputs()` — `sortByDescending { start }.take(300)`. History, Profile's
  connected-sources list, Home's latest-workout card, and two detection dedup passes.
- `buildAggregateWorkoutInputs()` — `searchAll { start inRange (today − ChartRange.Year, +∞) }`.
  `aggregates()` only.

They look like duplication. They are not. History wants *the most recent N whatever their age*;
aggregates wants *everything inside the last year however many that is*. `max(date, count)` is not
expressible as one chain. A previous attempt merged them onto the date bound and broke two tests
whose fixtures are dated 2023 — the same truncation a real user with an old import would see.

## 4. Shape

```kotlin
// :fit-shared-data
override fun workoutsPaged(): Flow<PagingData<UiWorkout>> =
    client.healthService.workouts
        .sortByDescending { start }              // no .take() — Paging cuts its own window
        .toPagingData(
            mapper = { entity -> /* workoutInput → FitProjection.workout */ },
            // (`orderedSource = true` stood here until 2026-09-15 — retired, see §4)
        ) { window -> window.select { fields(*WORKOUT_FIELDS) } }
```

Non-negotiables, each with a scar behind it:

- ~~**`orderedSource = true`.** Without it the page is `drop(pos).take(n)`, which F1 cannot fuse, and
  every page re-sorts the store.~~ ⚠ **Wrong when written, and retired 2026-09-15.** F1 folds
  `drop(pos).take(n)` under a `sortBy` exactly as it folds the other order
  (`core-keyset-paging.md` §6.3); the flag changed nothing, and `TenshuPagingSource` now takes an
  `ordering` instead (`drive-sorted-folder-paging.md` §10.6). What genuinely does not fuse is an
  unbounded window — a `drop` with no `take`.
- **The `select` rides the `projection` slot, never the base chain.** A baked select re-runs
  relation resolution over the whole list on every change — the 2k-file folder ANR
  (`TenshuPagingSource` KDoc).
- **No `cachedIn`.** It drops the refresh `load()` on every generation after the first
  (`PagingUtil.kt:221-232`). Drive's `FilesScreenViewModel` collects without it.
- **No `.take()` in the chain.** Paging computes its own window.

## 5. The UI is the bigger half

`HistoryScreen` currently renders `List<HistoryDayGroup>` from `HistoryViewModel`. Under Paging:

- **`HistoryDayGroup` cannot survive.** A day straddles page boundaries. Emit a header item when an
  item's date differs from its predecessor, inside `LazyColumn` + `collectAsLazyPagingItems()`.
- **The week roll-up must not come from the paged feed.** Keep it on the existing bounded read (or
  `WorkoutActivityRollup`). Summing a `PagingData` is not possible and not meaningful.
- **Loading/failed state comes from Paging's `LoadState`**, not `ListLoadPhase`. `ListLoadPhase`
  stays right for Home and Browse, which are not paged.
- The screen is already a `LazyColumn` with one item per day group — the structure is close.

## 6. Blast radius

`FitRepository` is exported through `tenshu-sdk-ios`, so a new `Flow<PagingData<…>>` member enters
the iOS framework surface. Drive already puts paging types there, so this is not new ground — but:

- `MockFitRepository` and `NotConnectedFitRepository` (iOS) both need the member. The latter should
  return empty paging data, consistent with its `Failed` reads.
- ⚠ **Drive-iOS's paging has a live defect**: listings stop at 20 rows because no Swift caller
  drives `presenter.get(index)` (`project_ios_paging_never_appends`). Fit-iOS will inherit it.
  Decide deliberately whether fit-ios keeps the list read and only Compose pages.
- `fit-shared-logic` gains `:core-paging`; `fit-shared-ui` gains `libs.paging.compose`.

## 7. Gates

```
./gradlew desktopTest jvmTest test --continue    # capture the exit code directly, never through a pipe
./scripts/ios-smoke.sh --build-only              # nine apps; FitRepository is exported
```
⚠ `--rerun-tasks` breaks `:fit-shared-ui`'s test compile (Compose resource accessors) — use
`--rerun`. Android smoke is not owed unless `androidMain` changes.

**Write the test so it fails first.** Every screen test in this area passed against the broken
version until it was written to assert absence rather than presence — see
`HistoryScreenTest.distantDaysAreNotComposedUntilScrolledTo` and the `MedicalScreenTest` note about
section-vs-record granularity.

---

## 8. As built (2026-08-12)

### 8.1 The seam is a second method, not a replacement

`FitRepository` gained `workoutsPaged(): Flow<PagingData<Workout>>` **beside** `workouts()`, with a
**default implementation** that wraps whatever `workouts()` returns in a static `PagingData`. That
default is what closes §6's blast radius: `MockFitRepository`, `NotConnectedFitRepository`, the
previews and every test fake needed **no change at all** and still render a correct (just
non-appending) feed. `FitProjectionRepository` is the only override.

⚠ `PagingData.from(rows)` alone is deprecated and leaves the load-state indicators pinned in
`Loading` — a feed that looks forever about to fetch more. The default states them explicitly as
`NotLoading(endOfPaginationReached = true)`.

⚠⚠ **Kotlin class delegation defeats the default.** `class X : FitRepository by delegate` forwards
*every* member to the delegate, defaults included — so a wrapper that overrides `workouts()` alone
gets the **delegate's** feed, silently. It cost a test failure that looked like a rendering bug
(`ManyDaysRepository` in `HistoryScreenTest`, which now overrides both and says why). Any future
decorator over `FitRepository` has the same trap.

### 8.2 iOS deliberately did NOT move

§6 asked for the decision; the decision is **SwiftUI History keeps the list read**. `HistoryUiState.days`
is still produced and `fit-ios/HistoryView.swift` is untouched, because Drive-iOS's paging has the
live `presenter.get(index)` defect (`project_ios_paging_never_appends`) — moving Fit-iOS onto
`Flow<PagingData<…>>` today would trade a 300-row window for a **20**-row one. The type still enters
the framework surface (the umbrella already exports `:core-paging`), it is simply not consumed yet.

### 8.3 The week roll-up and `days` both stay on the list read

As §5 required, and it is now pinned: `HistoryScreenTest.theWeekRollUpDoesNotComeFromThePagedFeed`
drives a repository whose paged feed is **empty** while its list read is not, so wiring the card to
the feed zeroes the card.

### 8.4 Day headers are a per-row predecessor comparison

`HistoryDayGroup` did not survive in the Compose feed (it survives for iOS, §8.2). A row emits a
header when its date differs from `peek(index - 1)`'s.

- ⚠ **`peek`, not `get`.** `get` *records* the access, which is what tells Paging to append — so
  reading a neighbour to decide a corner radius would prefetch a page per row.
- The card's corners are squared where it meets a same-day neighbour, so a run of per-row cards
  renders as the one card the grouped feed drew (`FitCard` gained a `shape` parameter). The last
  *loaded* row of a day rounds off early and squares again when the next page lands — honest, and
  invisible in practice.
- Pinned by `aDayStraddlingAPageBoundaryStillGetsExactlyOneHeader`: three workouts on each of two
  days cut into pages of **two**, so a day cannot align with a page. ⚠ The assertion is on
  *absence* — exactly one header per day. The obvious wrong port (group each page, emit its
  headers) draws the same day's header once per page and passes any aligned fixture.

### 8.5 "Earlier weeks" is gone, and three states replaced it

`fit_history_earlier` is deleted — the feed simply continues. In its place, from Paging's
`LoadState` rather than `ListLoadPhase` (which stays right for Home and Browse):

- append `Loading` → an inline indicator; `Error` → a message + **Try again**.
- ⚠ A paged refresh error is **terminal until retried**, unlike the repository's self-retrying list
  reads — so this surface offers the action instead of promising "still trying" the way
  `fit_home_unavailable` does.
- an empty settled feed → an empty state, which History never had.

### 8.6 What it cost, honestly

Perf is a wash as §1 predicted: History now holds a paged feed *and* the list read (week card).
The win is reach — pinned by
`FitPipelineIntegrationTest.pagedHistoryReachesPastTheListWindowTheListReadIsCappedAt`, which seeds
`WORKOUT_WINDOW + 20` workouts, **asserts the list read cannot see the oldest**, and then pages to
it through `TenshuPagingPresenter`. Point `workoutsPaged()` back at the list read and it fails.

### 8.7 Left undone

- **The on-device pass.** Nothing here has run on a phone; `androidMain` is untouched, so the smoke
  gate was not owed, and a paged feed over real Binder is exactly where Drive found its
  first-page-over-Binder race (`TenshuPagingSource.load`'s `getAll()` comment).
- ~~⚠ **`TenshuEventRepository` (Control Home) still pages a `sortByDescending` chain without
  `orderedSource = true`** — the shape §4 calls non-negotiable.~~ Not a bug: the flag never changed
  what fused (see the §4 correction), so Control's pages fused all along.

### 8.8 The round trip — reported, halved, measured (2026-08-12)

Reported the day this landed: scroll to *4 days ago*, open a workout, come back — the feed is at
the top and the week roll-up counts up from 0. Two separate causes wear one symptom.

1. ⚠⚠ **Not paging at all.** `AdaptiveNavScaffold` called its `content` lambda from a different
   source position when the chrome was hidden, and Fit hides the chrome for a non-top-level
   destination (`showNav = current is Destination.TopLevel`) — so opening a workout discarded the
   whole nav host, its `ViewModelStore`s and its saveable state. Fixed in `:ui-core`; the full
   write-up is `ui-adaptive-navigation.md` §8. ⭐ **Drive was the one app that never reproduced it
   because it is the one app that never passes `showNav`** — so "but it works in Drive" was pointing
   at exactly the right difference, and it was not a difference in paging.
2. **The re-anchor is paging, and is the trade-off §4 already bought.** No `cachedIn` ⇒ leaving
   composition drops the loaded pages ⇒ re-entry refreshes from page 0, and `LazyListState` restores
   its saved index into a list ten rows long and clamps. Drive has this identically
   (`FilesScreenViewModel.pagedFiles`); a folder that fits on one page just never shows it.

`HistoryNavRoundTripTest` (`fit-shared-ui/src/desktopTest`) drives the reported flow through the
real scaffold + nav-host pairing and pins both — the first as a fix (revert the scaffold and it
fails `expected:<1> but was:<2>`), the second as today's honest behaviour.

⭐ **Both halves are now fixed.** (2) turned out to be `cachedIn` after all: the rule that forbade it
— *it drops the refresh `load()` on every generation after the first* — was put under test and
**no longer reproduces** (`core-paging-scroll-restoration.md` §0). `HistoryViewModel.workouts` is
`cachedIn(viewModelScope)` as of SR-3; `HistoryNavRoundTripTest`'s second assertion flipped from
"re-anchors near the top" to "keeps its place", and `HistoryFeedReactivityTest` guards the half the
ban was about. ✅ **The on-device pass is done** (maintainer, by hand, 2026-08-12) — which is what actually settles
it, since every measurement behind the reversal was desktop and the finding reversed was
on-device-over-Binder. ⚠ Q1 (`cachedIn` retains every loaded page for the view model's life) is a
separate *memory* measurement and stays open.
