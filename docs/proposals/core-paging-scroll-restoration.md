# Paged listings lose their place — and the rule that made it hard is gone

**Status:** v0.7 — **COMPLETE. SR-1…SR-5 done (2026-08-12); Q1 measured and closed (2026-08-13).** The maintainer ran the on-device pass by hand and confirms the fix works on a device; retention is 308 B/row (§5). SR-1 re-scoped everything after it: SR-2…SR-4
are one line per view model plus their gates, and the `PagedListing` split v0.1 proposed is
**not needed** (§0, §7). ⚖️ SR-5, the on-device pass, is still owed and is still the gate.

Written off the Fit History round-trip report (`ui-adaptive-navigation.md` §8,
`fit-history-paging-handover.md` §8.8).

---

## 0. ⚠⚠ What SR-1 found — read this before the rest

v0.1 was built on a rule from `drive-folder-listing-consistency.md` (2026-07-10): **`cachedIn`
drops the refresh `load()` on new `PagingData` generations**, so a folder created while the listing
is open never appears until re-entry. Drive's and Fit's paged listings omitted `cachedIn` because of
it, and losing the loaded pages across a navigation round-trip was the price.

⚠ **Correction (SR-4):** earlier revisions of this line said *every* paged listing omitted it. That
was false — **Control never did**, and never was swept. See §4.3, which is the strongest evidence in
this whole document and was sitting in the repo the entire time.

SR-1's first gate was to turn that rule into a test before building anything on it. **It does not
reproduce.**

| measurement | result |
|---|---|
| `refresh()` reaches the source through `cachedIn` (v0.1's load-bearing claim) | ✅ holds |
| `cachedIn` swallows a new upstream generation — isolated paging-3.5.0 fixture | ❌ **does not reproduce** — the mutation lands |
| the same, against the **real** `getFilesPaged` pipeline through the **real** `LazyPagingItems` differ | ❌ **does not reproduce** — `/beta` and `/gamma` both appear live, no re-navigation |
| does `cachedIn` alone restore the scroll? (Fit History, real round trip) | ✅ **yes** — and reverting it puts the row back at 0 |

Tests: `CachedInRefreshContractTest` (`:core-paging` `commonTest`, so the `iosSimulatorArm64` gate
runs it too) and, for the real repository, `FilesPagingReactivityUiTest` — which SR-2 turned into
the permanent guard on this (§4.1). ⚠ A standalone `FilesPagingCachedInProbeTest` carried the
second measurement for exactly one PR, while production was still uncached; SR-2 deleted it.

So the elaborate part of v0.1 — split the change signal out of the data flow, drive reloads with an
explicit `refresh()`, add a collection seam per platform — solves a problem that is no longer
there. **The candidate fix is `.cachedIn(viewModelScope)`, one line per view model.**

### How the rule came to be wrong without anyone noticing

⭐ Worth recording, because the mechanism will recur:

1. The 2026-07-10 finding was **real** and correctly diagnosed. Nothing here says otherwise.
2. It was **on-device only**. The desktop twin (`FilesPagingReactivityUiTest`) was `@Ignore`d and
   hanging at the time — it only went green with the one-shot `getAll` page-window read, *after* the rule was written. So the rule
   never had a test that could notice the day it stopped being true.
3. It predates the 2026-08-10 dependency sweep. Paging is on **3.5.0** now.

A rule whose evidence lives only in a device session and a doc paragraph decays silently. Both
measurements above are now permanent tests, which is the actual fix for that.

⚠⚠ **None of this is licence to drop the ban on a code review.** Both measurements are *desktop*,
and the finding being overturned is an **on-device-over-Binder** finding. §6's G5 is unchanged and
is still the gate. What SR-1 changes is only which candidate goes to the device.

---

## 1. The symptom

Scroll a paged listing deep, open a detail screen, come back: the list is near the top.

Two causes wore this one symptom on Fit History. **The first is fixed** and was not paging at all —
`AdaptiveNavScaffold` rebuilt its whole content subtree when the nav chrome was hidden, so the nav
host, its `ViewModelStore`s and its saveable state all died on every push and pop
(`ui-adaptive-navigation.md` §8). That was the "0 → 41" flash.

What is left is paging, and **Drive has it identically** — a folder that fits on one page simply
never shows it:

- The paged flow is collected **without `cachedIn`**, so leaving composition drops every loaded page.
- Re-entry constructs a fresh `LazyPagingItems`, which refreshes from key `0` — one page.
- `rememberSaveable` faithfully restores `LazyListState`'s index (say 41) into a ten-row list, and
  `LazyColumn` clamps it to the end.

The scroll *is* saved. There is simply nothing to scroll to by the time it is restored.

---

## 2. The fix

```kotlin
val workouts: Flow<PagingData<Workout>> = repository.workoutsPaged().cachedIn(viewModelScope)
```

That is the whole change per surface. Measured on Fit History through the real
`AdaptiveNavScaffold` + `KmpNavHost` + `HistoryScreen` stack
(`HistoryNavRoundTripTest.aDeepScrolledFeedReAnchorsNearTheTopAfterARoundTrip`): with the line, row
39 is still on screen after the round trip; without it, 0 rows match. Both directions verified.

⚠ **Two consequences of `cachedIn` that are *not* bugs and must not be "fixed" later:**

- **A reload now re-anchors instead of jumping.** With one live `PageFetcher`,
  `TenshuPagingSource.getRefreshKey` finally has anchor state to read — it is written today and is
  effectively dead code, because a per-change `Pager` starts with none. The trade-off recorded at
  `PagingUtil.kt` (*"a reactive reload restarts from the refresh key, so a create while
  deep-scrolled re-anchors near the top"*) stops applying.
- **The per-change `Pager` stays.** `TList.pagingSource`'s `flatMapLatest` is unchanged; SR-1
  measured that `cachedIn` forwards those generations. ⚠ Do not also convert reloads to `refresh()`
  — that was v0.1's design and it is now unnecessary complexity. §7 keeps it as the contingency if
  G5 disagrees with the desktop measurements.

---

## 3. Blast radius

| consumer | change |
|---|---|
| `FilesScreenViewModel.pagedFiles` | ✅ SR-2 |
| `MovePickerViewModel.pagedFolders` | ✅ SR-2 |
| `HistoryViewModel.workouts` (Fit) | ✅ SR-3 |
| Control Home events | ✅ SR-4 — **already `cachedIn` since 2026-03-26**; only the missing guard test was added. ⚠ This cell used to say it was "still missing `orderedSource = true`"; that flag never changed what fused and is retired (`drive-sorted-folder-paging.md` §10.6) — Control's chain was never the worse for it. |
| iOS | **almost nothing — but not nothing (corrected 2026-08-13).** `TenshuPagingPresenter` needed no change and `cachedIn` does sit above it, so the *product* claim holds. ⚠⚠ But it broke `DriveLocalRuntimeTest.pagedListingDeliversRowsOverTheLocalRuntime`, which then sat red on master for a day — see §3.1. ⚠⚠ It also does not fix `ios-paging-never-appends` (no Swift caller invokes `presenter.get(index)`), and must not be sold as doing so. |

Doc amendments owed in the same PRs, because each states the rule as present-tense fact. ✅ Done in
SR-2: `drive-folder-listing-consistency.md`'s `Status:` header, `PagingUtil.kt`'s "⚠ Load-bearing
pairing" comment, and both Drive KDocs. ✅ Also done in SR-3:
`FitProjectionRepository.workoutsPaged`' third bullet and `HistoryViewModel.workouts`' KDoc. Every
site that stated the ban as fact now points at §0 instead.

No schema, no wire, no relay — HR #9 and HR #2 are not engaged.

### 3.1 The iOS test `cachedIn` broke, and why "iOS: nothing" was the wrong reading

⚠⚠ `.cachedIn(viewModelScope)` hung `DriveLocalRuntimeTest
.pagedListingDeliversRowsOverTheLocalRuntime` for its full 30 s timeout with a permanently empty
snapshot, while the plain `getFiles` tests in the same class stayed green. Fixed by giving the test's `viewModelScope` a dispatcher that runs —
**in the test, not the product**.

**The mechanism.** That test uses `runBlocking` *deliberately* (its KDoc: the proxy graph owns live
`Dispatchers.Default` scopes, so virtual time has nothing to pump). On Kotlin/Native `runBlocking`
runs the body on the test thread, and that thread **is** main — so for as long as a test body runs,
anything dispatched to `Dispatchers.Main` never runs. A `ViewModel`'s `viewModelScope` is
`Main.immediate`. So `cachedIn`'s sharing coroutine had nowhere to run, and the flow the presenter
was collecting never emitted. The fix is one `Dispatchers.setMain(Dispatchers.Default)` pair.

⭐ **Why this is a test fix and not a product bug.** On a device the surface is built over a real,
running main queue, so `cachedIn` shares normally; Drive-on-iOS listings were never affected. The
harness was the only place where Main is blocked — the test was *less* real than the device, not
more.

⚠ **Diagnosed by removing the line, not by reading it.** Deleting `.cachedIn(viewModelScope)` made
the test pass and restoring it made it hang again, which is what turned a plausible story into a
proven cause. Worth repeating for anything in this area: the failure is a *hang*, and a hang names
nothing.

⚠⚠ **It was red on `master` for a day and no gate said so** — the GitHub Actions lanes have been
dark on billing since 2026-06-30, so the only thing that catches an iOS test is someone running
`:tenshu-sdk-ios:iosSimulatorArm64Test` by hand. It was found incidentally, while verifying an
unrelated change in a different module.

⚠ Scanned for siblings: no other `iosTest` combines `runBlocking` with a `ViewModel`, so this was
the one instance rather than the first of many.

---

## 4. Phases

| # | what | size | state |
|---|---|---|---|
| **SR-1** | Turn the rule into tests before building on it. | S | ✅ **DONE** — and it deleted SR-2…SR-5 as written. |
| **SR-2** | Drive: `cachedIn` on `FilesScreenViewModel` + `MovePickerViewModel`, the four ban-stating comments corrected, `FilesPagingRoundTripTest` added. | S | ✅ **DONE** — see §4.1. |
| **SR-3** | Fit: `cachedIn` on `HistoryViewModel`; `HistoryNavRoundTripTest`'s second assertion flipped; `HistoryFeedReactivityTest` added. | S | ✅ **DONE** — see §4.2. |
| **SR-4** | Control events. ⭐ Turned out to need **no production change at all** — and to be the best evidence in the doc. | XS | ✅ **DONE** — see §4.3. |
| **SR-5** | ⚖️ On-device pass (§6 G5) across Drive + Fit. | S | ✅ **DONE** — maintainer, by hand, 2026-08-12. See §4.4. |

⚠ **Drive before Fit is still deliberate**, and now matters more rather than less: Drive is where
the rule was instrumented, so Drive is where the reversal has to survive. Fit is only where the fix
is *wanted*, which is the weaker claim.

⚠ Each phase is one line and is independently revertible, so there is no wrapper to keep as a
rollback any more — `git revert` is the rollback.

### 4.1 As built — SR-2 (2026-08-12)

`.cachedIn(viewModelScope)` on `FilesScreenViewModel.pagedFiles` and `MovePickerViewModel
.pagedFolders`. Two things about *where* it went:

- ⚠ On `pagedFiles` it sits **downstream of the sort `flatMapLatest`**, deliberately. A sort change
  is a genuinely different query and *should* build a new `Pager` and reset to the top (Q2, now
  resolved by construction rather than by assumption); only the per-change reload inside
  `getFilesPaged` is cached over.
- ⚠ `MovePickerViewModel` gets a **fresh VM per level** as the user drills in, so each level's cache
  dies with its own `viewModelScope` instead of accumulating — Q1 does not compound there.

**`FilesPagingRoundTripTest`** (new, `:drive-shared-ui`) is Drive's G3. It asserts the **page window**
rather than a scroll offset, because the offset was never the bug: `LazyListState`'s index is saved
and restored correctly and always was — the *list* had shrunk back to one page by the time the offset
was applied, so `LazyColumn` clamped it. Cancelling a `TenshuPagingPresenter`'s collection and
building a fresh one over the same view-model flow is exactly what a nav round-trip does (the
`LazyPagingItems` is `remember`ed, not saved). Remove the `cachedIn` line and it fails
`expected:<55> but was:<20>` — verified.

⚠⚠ Its first draft paged forward by treating *"no growth within 2 s"* as *"no more data"*. That is a
race, not a signal: green in isolation, **red under the full gate**, where the appends run through
the real in-process proxy on a loaded machine — *"never paged past the first window (loaded=20)"*.
It now loops to the fixture's **known total**, so a slow append costs another poke instead of a
false verdict. ⭐ A paging test whose exit condition is a timeout will eventually lie to you.

**`FilesPagingCachedInProbeTest` was deleted.** It existed for one PR, while production was still
uncached and adding `cachedIn` in the test was the only way to ask the question. With production
cached, `FilesPagingReactivityUiTest` — which collects `pagedFiles` through the real differ —
*is* that test, and the probe would have been it with a redundant second `cachedIn` on top.
⚠⚠ `FilesPagingReactivityUiTest` is therefore now the live guard on the reactivity half: **if it
goes red, put the ban back** (§7 holds the design that routes around it) rather than weakening it.

Comments corrected, each of which stated the ban as present-tense fact: `PagingUtil.kt`'s
"⚠ Load-bearing pairing", `FilesScreenViewModel.pagedFiles`' KDoc, `MovePickerViewModel
.pagedFolders`' KDoc, and `drive-folder-listing-consistency.md`'s `Status:` header — the last with
part (1) (`flatMapLatest`) explicitly called out as **unchanged and still load-bearing**, so the
amendment cannot be read as undoing the whole fix.

### 4.2 As built — SR-3 (2026-08-12)

`.cachedIn(viewModelScope)` on `HistoryViewModel.workouts`, the surface the whole report came from.

**`HistoryNavRoundTripTest`'s second test was flipped**, not deleted:
`aDeepScrolledFeedReAnchorsNearTheTopAfterARoundTrip` → `aDeepScrolledFeedKeepsItsPlaceAfterARoundTrip`.
⚠ It asserted the *old* behaviour on purpose — that is what made a silent change to the paging
contract impossible — so flipping it is the intended end of that test's life, not an admission it
was wrong. Its post-pop wait also moved from `"Session 0"` to the screen **title**: whether the top
row is composed is precisely what is under test, so waiting for it built the expected answer into
the harness, and once the scroll is restored `"Session 0"` never appears and the test times out
instead of failing readably.

**`HistoryFeedReactivityTest`** (new) is Fit's counterpart to `FilesPagingReactivityUiTest`: a
workout saved while History is open must appear without leaving the tab. Fit needs its own because
its feed is a different chain with a different page size, and because Fit is the surface the bug was
reported against.

⚠⚠ Its fixture is deliberately **reactive** — a fresh `Pager` per change, the shape
`TList.pagingSource` emits — and that was *verified, not assumed*: swapping it for a static
`PagingData.from(...)` makes the test fail, because there is then no second generation for `cachedIn`
to forward. A fixture that cannot produce the failure cannot guard against it.

Both directions checked: removing the `cachedIn` line fails the round-trip test
(`expected:<1> but was:<0>`), and the reactivity test passes either way today — which is the point,
since it exists to catch the day that stops being true.

### 4.3 As built — SR-4 (2026-08-12): ⭐ the evidence that was in the repo all along

SR-4 was scoped as "Control events, if its consumer has a scope". It has one — and it has had
`cachedIn(viewModelScope)` on `HomeScreenViewModel.eventFlow` **since 2026-03-26**. The ban
landed **2026-07-10** and swept **Drive only**. Control was never touched.

⚠⚠ So Control has been shipping the supposedly-broken configuration for **four and a half months**,
right through the period the ban was nominally in force, on the one screen whose entire job is an
ambient event feed that updates while you watch it.

⭐ **That reframes the ban.** It was never a platform-wide rule that Control violated; it was a Drive
fix that got *read* as a platform-wide rule, and Control simply never got swept. Three of the four
paged surfaces omitted `cachedIn`; the fourth quietly demonstrated for months that it did not need
to. ⚠ The honest caveat: *"nobody reported it"* is weak evidence — we cannot know how closely anyone
watched Control Home. It is corroboration, not proof, and it does not replace G5.

**No production change.** What SR-4 actually adds is the coverage whose absence let this sit
unnoticed: **nothing guarded Control's `cachedIn`.** `EventsFeedIntegrationTest` exercises
`getEvents()` at the *repository* level via `asSnapshot()`, below the view model, so it never sees
`cachedIn` at all; `HomeScreenTest` swaps in a fake repository. New
`EventsFeedReactivityUiTest` drives the real proxy → the real `HomeScreenViewModel` → the real
`LazyPagingItems` differ.

⚠⚠ Its **non-vacuity was verified, not assumed** — a reactivity test that cannot fail is worse than
none, because it reads as coverage. Inserting `.take(1)` before `collectAsLazyPagingItems()`, so only
the first generation is ever delivered, makes it fail at the `/beta` wait. That is the shape of the
swallow it exists to catch.

All three surfaces now have the same guard: `FilesPagingReactivityUiTest` (Drive),
`HistoryFeedReactivityTest` (Fit), `EventsFeedReactivityUiTest` (Control).

### 4.4 As built — SR-5 (2026-08-12): the pass that actually settles it

The maintainer ran the on-device pass by hand and reports the fix works. ⭐ That is the evidence
that matters here, and it is the *only* evidence that could be: the rule being reversed was
established by on-device-over-Binder instrumentation, and every measurement SR-1…SR-4 could produce
was desktop. Desktop-green was necessary and never sufficient.

So the reversal stands on its own terms rather than on an argument, and the round trip a user
reported on 2026-08-12 — scroll History to *4 days ago*, open a workout, come back — now behaves.

⚠ **What this does not record.** The pass was a hand-run, not an itemised walk of G5.1–G5.5, so this
section claims exactly what was reported and no more:

- ✅ The fix works on a device.
- ✅ **Q1 has since been measured separately** — see §5. It is not what the hand-run covered ("it
  works" is a correctness observation, not a memory one), so it was measured on its own and closed.

⚠ The three reactivity guards are what keep this honest from here: `FilesPagingReactivityUiTest`,
`HistoryFeedReactivityTest`, `EventsFeedReactivityUiTest`. **If one goes red the ban is back** — §7
holds the design that routes around it. That is the fix for the decay described in §0, and it is the
part of this work most worth keeping.

---

## 5. Open questions

- **Q1 — retention. ✅ MEASURED AND CLOSED (2026-08-13): 308 B/row; 5 000 cached rows cost 1.5 MB.**
  `PagedRowRetentionTest` drives the real `Pager` → `cachedIn` → `PagingDataPresenter` chain over
  5 000 rows and reads the heap delta.

  ⚠⚠ **The question was written on a false premise, and the correction is the interesting part.**
  It said *"and for Drive those rows carry thumbnails"*. **They do not.** Drive's paged projection is
  metadata-only by design — no `File.Preview` — and
  `FilesPagingIntegrationTest.pagedListingIsMetadataOnly_previewLoadsPerRow` has pinned that all
  along: `previewBytes` is `null` on the paged path. `FileUiModel` *has* the field, which is what
  made the claim plausible when read quickly, but only the per-row loader ever fills it — and that
  path is bounded by `RowPrefetchLimiter`, a semaphore released when the row leaves composition.
  ⭐ **Bitmap memory is bounded by composition, not by the cache**, so it was never part of Q1 at
  all. The same holds on the other two surfaces: Fit's window deliberately excludes
  `Workout.Streams` (the byte carrier) and Control's `Event`s are small.

  What `cachedIn` actually retains is three strings, an enum reference and a boolean per row. For
  scale, the Control OOM crash-loop was a 257 MB store against a 256 MB heap — this is two orders of
  magnitude below the things that have actually hurt here.

  ⚠ `PagingConfig.maxSize` stays **rejected**, and now for a second reason: with placeholders off it
  bounds retention only by dropping pages, which shifts every index — the defect class this proposal
  removed — and it would be buying that for 1.5 MB.

  ⚠⚠ The measurement doubles as a **regression guard**: its ceiling is loose (heap deltas are not
  deterministic) but sized so that one 8 KB thumbnail per row blows it by orders of magnitude. If
  `File.Preview` ever creeps back into the paging projection, it fails there rather than on a phone.

- **Q2 — RESOLVED by SR-2.** ~~The sort-change path.~~ `cachedIn` is applied downstream of the sort
  `flatMapLatest`, so a sort change still builds a new `Pager` and still resets to the top, which is
  the correct behaviour for a different query.
- **Q3 — RESOLVED by SR-1.** ~~Is `reloads` the right thing to hand a ViewModel?~~ There is no
  `reloads`.

---

## 6. Gates

The rule being reversed was established on a device, and the failure it prevents is invisible to
every unit test we had at the time. Reversing it needs the same evidence.

- **G1 — ✅ done.** `CachedInRefreshContractTest`, plus the real-repository measurement now held
  permanently by `FilesPagingReactivityUiTest` (§4.1). See §0.
- **G2 — `FilesPagingReactivityUiTest` stays green.** The exact regression, through the real
  repository and the real differ, in its *current* (uncached) shape.
- **G3 — a scroll-restoration test per migrated surface**: deep-scroll → round-trip → still there.
  ✅ Drive: `FilesPagingRoundTripTest`. ✅ Fit: `HistoryNavRoundTripTest`
  (`aDeepScrolledFeedKeepsItsPlaceAfterARoundTrip`).
- **G4 — `./gradlew desktopTest jvmTest test`**, and `scripts/android-smoke.sh` (androidMain by
  transitivity).
- **G5 — ✅ PASSED (maintainer, by hand, 2026-08-12).** ⚠ Not itemised case-by-case; see §4.4 for
  what is and is not claimed. G5.3 (Q1) was measured separately — §5. The cases as scoped were:
  1. Drive: create a folder while the listing is open — single, spaced, **and rapid bursts**. All
     three appear without re-navigating.
  2. Drive: same, while **deep-scrolled** in a large folder. It must appear *and* not jump.
  3. Drive: Q1 — watch memory in a folder scrolled to a few thousand rows. ✅ Superseded by
     the §5 measurement, which answers the same question deterministically and off-device.
  4. Fit: record a workout while History is open; then the reported round trip, deep-scrolled.
  5. Control Home: a notification arriving while the events list is open.

⚠ G5.1's *rapid burst* case is the one that found the original bug. Do not skip it because the
single-create case passed — that is exactly the reasoning that let the rule decay.

---

## 7. Alternatives considered

- **v0.1's `PagedListing` split** — take the change signal out of the data flow, keep one `Pager`
  per screen, and drive reloads with an explicit `refresh()` on it. Correct, and SR-1 proved its
  key mechanism works (`refresh()` does forward through `cachedIn`). ⚠ **Kept as the contingency:**
  if G5 finds the swallow alive on-device after all, this is the fix, and `CachedInRefreshContractTest
  .refreshThroughCachedInReachesTheSource` is already the evidence it rests on. Not built, because
  building it now would be paying for a problem no measurement can currently find.
- **Remember the anchor and pass it as `Pager(initialKey = …)`.** With `enablePlaceholders = false`
  the anchor page becomes index 0, so the restored `LazyListState` index points at the wrong row.
  Rejected.
- **Drive appends on re-entry until the saved index is reachable, then `scrollToItem`.** N sequential
  page loads, visible as a stutter, and it fights Paging rather than using it. Rejected.
- **Do nothing.** Weaker than it was in v0.1: the fix is now one line, and it makes an existing piece
  of written-but-dead code (`getRefreshKey`) start doing its job.
