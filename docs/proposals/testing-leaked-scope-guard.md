# Leaked test scopes — make the leak fail its own test

**Status**: v0.6 — **Arm A is built, proven and extracted**: `OwnedViewModels` in
`:viewmodel-test-utils`, adopted by `:photos-shared-ui` (199 live coroutines at test-JVM exit
became 1), `:drive-shared-ui` and `:maps-shared-logic` — **3 of 19 modules** (§4.8). Phase 0 done
(§4.5); the cheaper alternative to Arm B spiked and rejected (§4.6).
⚠⚠ **Arm B is NOT superseded by Arm A's guard**, which is blind to exactly the tests B exists to
find (§4.8.2) — but Phase 2's sizing is stale and must be re-measured before it is built (D5).
**Owner**: unassigned.
**Related**: **`docs/testing.md` §11 — the reference for the fixture Arm A shipped; this doc is the
design record, that one is how you use it.** Also `docs/testing.md` §7 (the gate wrapper) and
`docs/tech-debt.md` § "The full gate is flaky under parallel load" (the incident record this doc is
sized from).

---

## 0. The problem in one sentence

A test builds a coroutine scope it never cancels, the scope outlives the test, and the damage
lands on **some other test** — so the gate reports a class that is not the culprit, and the person
who reads the report starts in the wrong file.

## 1. Four incidents, all 2026-09, all the same shape

| Where | What the gate printed | Who was actually at fault |
|---|---|---|
| `:system-test-utils` (P0) | `UncaughtExceptionsBeforeTest` in `TestClientGraphResolutionTest` | `PairingVmFlowSystemTest`, two `PairingWizardViewModel`s whose `viewModelScope` was never cancelled |
| `:photos-shared-ui` | `UncaughtExceptionsBeforeTest` in `PreviewCacheTest` | **still unknown** — one of three compose classes building an uncancelled `PhotosFeedViewModel` |
| `:drive-shared-ui` (video preview) | `Dispatchers.Main is used concurrently with setting it` in `streamingSupported_opensStream_asVideoSource` | the *same class*'s other tests, whose load was still parked on `Dispatchers.Default` |
| `:system-test-utils` (older) | a paired-device convergence test timing out | orphaned `sessionScope` coroutines starving `Dispatchers.Default` — the comment is still in `ClientRegistry.mintSession` |

⚠ Three distinct symptoms, one cause. That is what makes this a family rather than four bugs:
a leaked scope can **replay an exception** into the next `runTest`, **collide with `setMain`**, or
**starve the dispatcher** — and which one you get is a property of the machine, not the defect.

⚠⚠ **The cost is not the flake, it is the misattribution.** Every one of these took a session to
trace, and the `:photos-shared-ui` one is still open after a day of work precisely because the
report names a file with nothing wrong in it. `runTest` already fails a test whose *own* children
are unfinished; the entire gap is scopes that are not parented to the test.

## 2. Why it keeps happening — nothing pushes back

`viewModelScope` is created on first access and cancelled only by `ViewModel.onCleared()`, which
nothing calls unless a `ViewModelStore` is cleared. A test can therefore construct a view model,
assert on it, and walk away — and that reads as perfectly ordinary Kotlin.

**Census, measured 2026-09-07** (`grep` over `*/src/*Test`, so approximate by construction — it
counts files that *construct* a view model, not files that leak one):

| | files |
|---|---|
| test files constructing a `ViewModel(` | **134**, across 19 modules |
| …of those, containing any clearing idiom | **10** |
| …of those, that also `Dispatchers.setMain` | **94** |
| files creating a `ViewModelStore` | 13 |
| …of those, that actually call `.clear()` | **4** — all in `:control-shared` |

⚠ The 134 is an upper bound on the population and a poor estimate of the *leak* count: a view
model with no long-lived work in flight at teardown leaks nothing observable. The 94 is the number
that matters for the `setMain` collision shape, and the 4 is the encouraging part — the idiom
already exists in the tree (`HomeScreenTest`: a class-level `viewModelStore`, `@After { clear() }`).

## 3. Two arms, and they are not alternatives

### Arm A — prevention: a shared fixture that owns the view models

✅ **Built — `OwnedViewModels` in `:viewmodel-test-utils`** (§4.8; reference docs in
`docs/testing.md` §11). A test registers every construction — `own(vm)`, `ownScope(scope)`,
`ownStore(store)` — and a wrapper cancels **and joins** each one at the end.

- ✅ Cheap, no new dependency, works in `commonTest` (so it survives on K/N).
- ✅ Fixes the `viewModelScope` half of the family outright, and `ownScope` covers the raw
  `CoroutineScope(...)` case v0.1 thought it could not.
- ⚠ **Opt-in.** It helps the tests that adopt it and is invisible to the ones that do not, which is
  exactly how we got here — and the assertion it carries does **not** fix that (§4.8.2).
- ⚠ **`cancelAndJoin`, not `cancel`.** v0.1–v0.5 specified a `ViewModelStore.clear()`, which
  cancels without waiting; cancellation is cooperative, so that returns while a collector is still
  unwinding and loses the same race. The join is the fix.
- ⚠ Does **not** cover a coroutine parked on a real dispatcher (the video-preview test's shape needed an
  injectable dispatcher, not a cancel — a scope cancellation still has to resume onto `Main` to
  propagate).

### Arm B — detection: fail the *build* on a leak, and name where it was created

A teardown census that fails a test which leaves a live coroutine behind.

- ✅ Not opt-in: it finds the tests nobody thought to migrate, which is the whole point.
- ✅ Names the culprit **in the culprit's own class**, which is the single thing that would have
  saved the four investigations above.
- ⚠⚠ **JVM-only.** `kotlinx-coroutines-debug`'s `DebugProbes` is the only way to enumerate live
  coroutines and it does not exist on K/N — so this can only be a JVM/desktop-lane guard, and a
  `commonTest` file that leaks would go uncaught on the iOS lane. Acceptable (the flakes we have
  all surfaced on the JVM lanes) but it must be stated, not discovered later.
- ⚠⚠ **It cannot be per-test, and that is a property of this repo, not of the idea.** There is no
  global per-test hook here: the build is JUnit 4 (no `useJUnitPlatform` anywhere, so no
  ServiceLoader `TestExecutionListener`), and Gradle's own `TestListener` runs in the **build** JVM
  where it cannot see the test JVM's coroutines. A per-test census would have to be adopted class
  by class — which makes it opt-in, i.e. exactly the property Arm A already has and Arm B was
  supposed to add. **So Arm B is a per-JVM (per-module) census at exit**, via a shutdown hook. It
  loses per-test attribution and keeps the thing that matters: the *creation site* (§4.5).

## 4. Decisions

- **D1. Arm A first, Arm B behind a measurement.** Prevention is cheap and certain; detection is
  valuable but its feasibility is unmeasured. Shipping A while B is being sized costs nothing.
  ⭐ Vindicated: A shipped in three modules and was extracted while B remains unbuilt.
- **D2. The guard fails the test, it does not warn.** A warning in a 1500-class gate is not read.
  If B cannot be made quiet enough to fail, B does not ship — a noisy guard is worse than none,
  because the next person learns to ignore it.
- **D3. No production code changes for A.** The video-preview leak already showed the one case where production
  *should* change (an injectable dispatcher), and that is a per-case judgement, not a sweep.
- **D4. Migration is opportunistic, not a big-bang.** 130 files is not a reviewable diff. Adopt the
  helper in a module when that module is touched for another reason — and let B (if it ships) set
  the deadline by failing what has not moved.
- **D5. B ships as a ratchet against a checked-in baseline, never as a binary gate** (§4.5). At 149
  sites in one module a binary gate cannot be turned on, and a guard that cannot be turned on is
  not a guard. ⚠ This supersedes v0.1's implicit assumption that a clean census was reachable.
  ⚠⚠ **The 149 is now stale and D5 rests on it.** It was measured on `:drive-shared-ui` before that
  module was swept (144 ownership sites, §4.8). If the post-sweep number is small, a binary gate
  becomes reachable for swept modules and the ratchet is unnecessary complexity there — so
  re-measuring is the entry condition for Phase 2, not a refinement of it. D5 stands only for
  modules that have not adopted Arm A.
- **D6. Per-JVM, not per-test.** Forced by the toolchain, not chosen (§3, Arm B). The creation stack
  trace carries the attribution instead, and the spike shows it is enough.

## 4.5 Phase 0 — measured 2026-09-07 on `:drive-shared-ui` (75 classes / 429 tests)

⚠⚠ **First, a retraction.** v0.2 of this doc reported a **noise floor of 0** from a two-test spike
and called it "the encouraging half of D2". That number was an artifact of running two synthetic
tests: with nothing else in the JVM there is nothing else to see. At module scale the census is
**not** empty, and the design consequence is large enough that the rest of this section replaces
v0.2's rather than extending it.

### What a whole-JVM exit census actually returns

`DebugProbes.enableCreationStackTraces = true` + `install()` in a static initializer, dumped from a
JVM shutdown hook, whole module:

| | |
|---|---|
| live coroutines at JVM exit | **164** (also seen: 127, 173 — it moves run to run) |
| attributable to an `app.tenshu` frame | **149** |
| unattributable (harness: `IntrinsicsJvm`, `startCoroutineUndispatched`) | **15** |

⭐⭐ **And the attribution is at constructor granularity, which is the finding that makes Arm B
worth building:**

```
n=41  app.tenshu.drive.ui.selection.SelectionViewModel.<init>
n=30  app.tenshu.drive.ui.screens.files.FilesScreenViewModel.<init>
n=17  app.tenshu.drive.ui.screens.shared.SharedScreenViewModel.<init>
n=12  app.tenshu.drive.ui.screens.settings.SyncPreferencesViewModel.<init>
n=11  app.tenshu.drive.ui.screens.movepicker.MovePickerViewModel.<init>
n=10  app.tenshu.drive.ui.common.search.FilesSearchBarViewModel.<init>
n=7   app.tenshu.drive.ui.screens.home.HomeScreenViewModel.<init>
n=3   …RecentFilesLoadStateTest.aFailureAfterAGoodReadKeepsTheRowsOnScreen
```

The dump names the view model *and*, where the coroutine was launched from a test body, the test
method. A human reading that does not need per-test attribution — the creation site is the answer.

### Cost: not measurable at this scale

One run each, same module, no CPU burners: **1m 12s with probes + creation stack traces, 1m 16s
without.** The probe run was the faster of the two, so the honest reading is "no difference this
measurement can see", not "4 s cheaper". Creation stack traces are the expensive option and they
were on.

### ⚠⚠ The consequence: this is a ratchet, not a gate

**149 leaked coroutines in one module** means a binary "fail if anything is live" guard fails
every module on day one, and D2 (fail, do not warn) then makes the whole suite red. The shape that
survives contact with that number is the one this repo already uses for
`SchemaShapeBaseline`: **a checked-in baseline of leak sites, and a failure only when a new site
appears or a count grows.** Arm A then burns the baseline down.

⚠ The 149 are mostly *harmless* — a suspended coroutine nobody ever resumes hurts no one, which is
why the suite is green today. The four incidents in §1 are the handful that happened to resume into
someone else's test. A census cannot tell those apart, and should not try: the ratchet stops the
population growing without needing to.

## 4.6 Q3 — the cheaper guard, spiked 2026-09-07, and it does not replace B

Before building Arm B it was worth asking whether the specific symptom that bit us — a leaked
coroutine coming back to `Dispatchers.Main` around `setMain`/`resetMain` — could be caught without
`DebugProbes` at all. Two mechanisms, three cases, measured **in `@AfterTest`** (the point a real
guard asserts at, *after* `runTest` has advanced the scheduler):

| | **Q3b** counting Main: outstanding dispatches at teardown | **Q3d** sentinel Main + wait: arrivals | **B** exit census |
|---|---|---|---|
| virtual-time leak (`delay` on the test scheduler — benign, `runTest` drains it) | 0 | 0 | 0 |
| **real-dispatcher leak** (`withContext(Dispatchers.Default)` — the video-preview shape) | **0 — misses it** | **1 — detects** | **1, named** |
| no leak | 0 | 0 | 0 |

⚠⚠ **Q3b is dead, and structurally so.** A coroutine parked in `withContext(Dispatchers.Default)`
has *no* pending Main dispatch at teardown — it will only queue one when it resumes, which is after
the test is over. Counting what is queued cannot see what is coming.
⚠ An earlier measurement of Q3b at the wrong point (inside the test body, before `runTest`'s final
advance) read `outstanding=1` for **both** leak shapes and looked like a detector. It was reading
"queued but not yet advanced". Measure in teardown or not at all.

⭐ **Q3d does work as a detector** — correct on all three cases, no dependency, no instrumentation.
It loses to B on two counts that matter more than its cost:

1. ⚠⚠ **It cannot say what leaked.** The captured stack is pure machinery, with not one `app.tenshu`
   frame in it: `TestMainDispatcher.dispatch | DispatchedContinuationKt.safeDispatch |
   resumeCancellableWithInternal | DispatchedCoroutine.afterResume | AbstractCoroutine.resumeWith |
   BaseContinuationImpl.resumeWith`. An arriving continuation carries no creation site — which is
   exactly what `DebugProbes` exists to add. B answered `SelectionViewModel.<init>`; Q3d answers
   "something".
2. ⚠⚠ **It needs an arbitrary wait.** The spike slept 3 s per class. That is both a cost (75 classes
   in one module) and unsound: a leak that resumes at 5 s escapes a 3 s sentinel, so the guard's
   answer depends on a number nobody can justify. B needs no wait — a live coroutine is live.

**Verdict: build B.** Q3d is recorded because it is a genuinely cheaper *detector* and may be worth
having if `DebugProbes` ever becomes unaffordable — but as the primary guard it trades the one
property (attribution) that made the four incidents in §1 expensive.

## 4.7 Phase 1 as built — `:photos-shared-ui`, 2026-09-07

`TestViewModelOwner` is 3 lines of behaviour and 40 of KDoc: a `ViewModelStore`, an `own(vm)` that
registers into it, and a `clear()` that cancels every registered `viewModelScope`. Three test
classes adopted it (`FeedScrubberTest`, `PhotosFeedScreenTest`, `PhotosNavRoundTripTest`), each
wrapping its `remember { PhotosFeedViewModel(…) }` and calling `clear()` in teardown.

⚠ **`clear()` goes before `Dispatchers.resetMain()`, not after.** Cancelling a `viewModelScope`
dispatches onto `Main`; reset it first and the cancellation has nowhere to go.

**Measured with the §4.5 census, same module, before and after:**

| | before | after |
|---|---|---|
| live coroutines at JVM exit | **199** | **1** |
| attributable to `app.tenshu` | 111 | 1 |
| `PhotosFeedViewModel.<init>` | **110** | **0** |

⭐ The 88 unattributable ones went with them — they were downstream of the leaked view models, not
harness noise, which the §4.5 numbers could not have told apart.

⚠ **The one residual is not a view model**: `AboutSheetTest.thePagerStaysFreeWithThePanelOpen`
leaves a coroutine from Compose's own pager/fling machinery. Out of scope for Arm A, and left
deliberately — 1 of 199 is where this slice stops.

⚠⚠ **This does not *prove* the `PreviewCacheTest` flake is fixed**, and the entry in `tech-debt.md`
says so. That flake was never reproduced (0/10 under burners, and the leading hypothesis was
disproved), so there is no red arm to turn green. What can be said is exact: the leak population it
would have needed is gone from this module.

⚠ **Superseded by §4.8.** `TestViewModelOwner` was deleted: its `clear()` cancelled
without joining, which is the half of the race that matters. The numbers above still stand — they
measure the leak population, which the replacement removes at least as well.

## 4.8 Arm A completed and extracted — 2026-09-11/12

Three modules now run on one fixture, and it is the same object in all three.

| module | how |
|---|---|
| `:maps-shared-logic` | `vmTest` |
| `:drive-shared-ui` | `vmTest` / `vmBlocking` / `composeVmTest`, 33 classes, 144 ownership sites |
| `:photos-shared-ui` | `closeAndResetMain`, 3 classes |
| all three | extracted to `:viewmodel-test-utils` |

⚠ **Compose stays out of the shared module.** `composeVmTest` needs `runComposeUiTest`, which would
put Compose on `:maps-shared-logic` — a module that is Compose-free by design and uses the fixture
*because* of it. The owner, the join and the guard are shared; the UI builder is not.

### 4.8.1 The fixture's own guard, and its measured reach

`assertNothingActive()` fails a test whose registered scopes outlived it. Three forced pairs,
measured on `:photos-shared-ui` (112 tests) and now pinned by `OwnedViewModelsGuardTest`:

| forced pair | result | |
|---|---|---|
| `closeAll()` made a no-op | 24 / 24 owning tests **red**, deterministically | ✅ |
| `cancelAndJoin` → bare `cancel` | 112 / 112 green | ❌ |
| a view model built without `own(…)` | 112 / 112 green | ❌ |

⚠⚠ **Three copies of this fixture claimed all three rows, and two of them are false** (now corrected).
A cancelled `Job` reads `isActive == false` at once while its collectors are still
unwinding; and the guard only ever inspects what it was handed.

⭐ The join is pinned instead by `aJoinWaitsForTheCollectorToFinishUnwinding`, whose body takes real
time to unwind: it is the only test that goes red on the bare-`cancel` swap, and the only pair
`cancelAndJoin` has ever had.

### 4.8.2 ⚠⚠ Arm A's guard does not become Arm B

It is tempting to read `assertNothingActive()` as the detection arm arriving for free — a guard
that fails a test on a leak, in the leaking test's own class, with no `DebugProbes` and no baseline.
It is not, and row 3 above is why: **the guard is blind to a construction that never called
`own(…)`.**

That is not a gap to be patched. It is the same property Arm A has always had — opt-in — restated
one level down. A module that adopts the fixture gets a durable guarantee that its teardown cannot
silently rot; a module that has not adopted it is exactly as invisible as before, and those are
precisely the tests Arm B exists to find (§3). **So D1's sequencing holds, and Phase 2 is still
Phase 2.**

### 4.8.3 What is left — a static census, 2026-09-12

Not the §4.5 live-coroutine census (that harness was a spike and was never checked in). This counts
**test files that construct a `…ViewModel(…)`**, split by whether they go through the fixture — a
scope measure, not a leak measure:

| | 2026-09-12 | photos-logic | control-shared | + control-logic & fit-ui | + contacts-logic | **close-out** |
|---|---|---|---|---|---|---|
| through the fixture | 35 | 51 | 58 | 84 | 92 | **127** |
| not | 93 | 77 | 70 | 44 | 36 | **1** |
| modules fully swept | 3 of 19 | 4 of 19 | 5 of 19 | 7 of 19 | 8 of 19 | **18 of 19** |

⭐⭐ **Arm A is complete on every module that can be verified here.** The single remaining file is
`:tenshu-sdk-ios`'s `SignInSurfaceTest`, deliberately left — see §4.8.9.

⚠ A file counted here is not a proven leak: a test may construct a view model whose `init` starts
nothing. The number sizes the *migration*, not the damage.

### 4.8.4 `:photos-shared-logic` — 2026-09-13

16 classes, 87 ownership sites, `vmTest` throughout. Completes the Photos pair with `:photos-shared-ui`.

⭐ **Red arm: 103 of 216 tests red across all 16 classes** with `closeAll()` made a no-op, 216/216
green with it. The sweep is visible to the guard rather than merely present.

⚠ **`vmTest` gained a `context` parameter for this**, mirroring `runTest(context)`. All 88 bodies
here were `runTest(dispatcher)` where `dispatcher` is the same `StandardTestDispatcher` the class
hands to `setMain` — so a `viewModelScope` runs on the test's own scheduler and `advanceUntilIdle()`
drains it (§10). Converting to a no-arg `vmTest` would have moved every one of them to a different
scheduler silently; that is a migration hazard for every remaining module, not a quirk of this one.

⚠ Three class-level factory functions (`feed`, `vm`, `viewModel`) build view models outside any test
body, so they take the `OwnedViewModels` as a parameter. A grep for helpers missed all three,
because each puts the constructor on the line *after* the `=`. The compiler found them; a regex
sweep of the next module should expect the same shape.

### 4.8.5 `:control-shared` — 2026-09-14

7 classes, 37 ownership sites. ⭐ **Red arm: 48 of 397 tests red across exactly the 7 swept classes**,
397/397 green restored.

⭐ **This is the module §3 named as the pattern Arm A was extracted from**, and the one that
exercises [ownStore] for real. Three Compose classes hand a `ViewModelStore` to
`LocalViewModelStoreOwner` and cleared it in a JUnit `@After`; `clear()` cancels each
`viewModelScope` without waiting for it, so the join was exactly what was missing. All three go red
in the arm, which is the evidence that `ownStore` registers rather than decorates.

⚠ **`closeBlocking()` was added for this.** These classes never install their own `Main`, so
[closeAndResetMain] is wrong for them — it would reset a dispatcher the class never set. And a JUnit
`@After` cannot be `suspend`, so `closeAll()` is not callable directly. The two teardowns now split
by whether the class owns `Main`, which is the question that decides the ordering anyway.

⚠ The join replaces `clear()` **in place**, before `stopKoin()` / `stopAppIntegrationKoin()` — the
`vmBlocking` rule from the `:drive-shared-ui` sweep: unwind the view models before the graph they read from goes away.

### 4.8.6 `:control-shared-logic` and `:fit-shared-ui` — 2026-09-14

Two modules in one pass, 26 classes, 130 ownership sites.

| module | classes | shape | red arm |
|---|---|---|---|
| `:control-shared-logic` | 15 | `vmTest` over `runTest` | **122 of 282** red, all 15 classes |
| `:fit-shared-ui` | 11 | `composeVmTest` over `runComposeUiTest` | **27 of 32** red, 10 of 11 classes |

⭐ **The Compose wrapper stopped being a copy.** `withOwnedViewModels` in the shared module now
carries the own-join-assert logic, so each UI module's wrapper is one line over its own builder:

```kotlin
internal fun composeVmTest(body: ComposeUiTest.(OwnedViewModels) -> Unit) =
    withOwnedViewModels { owned -> runComposeUiTest { body(owned) } }
```

Compose still cannot enter `:viewmodel-test-utils` (§4.8, the `:maps-shared-logic` constraint), but
only the *builder* is per-module now. `:drive-shared-ui`'s wrapper collapsed to the same line.

⭐ **`HistoryNavRoundTripTest` is the one swept class that does NOT go red, and that is a finding
rather than a gap.** Its view models are built inside a nav entry's `viewModel { }` factory. A probe
that failed the guard whenever *nothing* had been registered passed for this class, so registration
happens; they are simply **already inactive** at assert time, because `KmpNavHost` clears its
per-key `ViewModelStore` when the composition is disposed. Nav-hosted view models were never part of
the leak population.

⚠⚠ **A third rewriter shape: a constructor with a trailing lambda.**
`OfflineMapsSuggestionViewModel(prefs, flowOf(apps)) { regionInstalled }` became
`own(VM(prefs, flowOf(apps))) { regionInstalled }` — the lambda handed to `own`, not the
constructor. It fails to compile every time (`own` has no lambda parameter), so it cannot ship
silently, but a sweep should expect it alongside the "constructor on the line after the `=`" shape
from §4.8.4.

⚠ `:control-shared-logic` also had three **synchronous** tests (`fun x() { … }`, no `runTest`) that
needed wrapping — a shape the earlier modules did not have.

### 4.8.7 `:contacts-shared-logic` — 2026-09-14

8 classes, 64 ownership sites, `vmTest` throughout. ⭐ **Red arm: 64 of 95 tests red across all 8
classes**, 95/95 green restored.

⚠ The first module in the sweep to convert **first time, with no hand-fixing**: every body was
`runTest {`, every construction `val vm = SomeViewModel(…)` on one line, and there were no
class-level factories, no trailing-lambda constructors and no synchronous tests. Worth recording as
the shape the mechanical rewrite is actually safe on — §4.8.4 and §4.8.6 are the exceptions, not the
rule.

⚠ `commonTest`, so only the `commonMain` half of the fixture is reachable (`vmTest`); the module's
targets — android, desktop, iosArm64, iosSimulatorArm64 — match `:viewmodel-test-utils`' exactly,
which is what makes that dependency resolve at all. A module with `iosX64` would not.

### 4.8.8 The close-out — ten modules, 2026-09-14

| module | classes | shape | red arm |
|---|---|---|---|
| `:calendar-shared-logic` | 4 | `vmTest` | 164 of 399 |
| `:fit-shared-logic` | 4 | `vmTest` | 61 of 192 |
| `:home-shared-logic` | 4 | `vmTest` | 23 of 81 |
| `:timeline-shared-logic` | 5 | `vmTest` | 34 of 56 |
| `:mia-shared-logic` | 4 | `vmTest` | 25 of 28 |
| `:timeline-shared-ui` | 4 | `composeVmTest` | 16 of 43 |
| `:contacts-shared-ui` | 2 | `composeVmTest` | 21 of 22 |
| `:home-shared-ui` | 3 | `composeVmTest` | 8 of 9 |
| `:mia-shared-ui` | 3 | `composeVmTest` | 3 of 4 |
| `:calendar-shared-ui` | 2 | `desktopComposeVmTest` | 5 of 25 |

⭐ **360 of 859 tests red with `closeAll()` made a no-op; 859/859 green restored.** Every module
fires.

⚠ That figure was **338** before the §4.8.8a review fixes. Wrapping the fourteen inline
constructions brought 22 more tests under the guard — which is the measurement that says those
fixes registered real view models rather than merely satisfying a scan.

⚠ `:calendar-shared-ui`'s `CalendarNavRoundTripTest` does not, for the §4.8.6 reason: its view
models live in a `KmpNavHost` entry's store, which the nav host clears on disposal. That is now the
second confirmed instance of nav-hosted view models being outside the leak population, so it is a
property of `KmpNavHost` rather than a quirk of one test.

### 4.8.8a ⚠⚠ Five rewriter shapes, and the rule they imply

A mechanical `= SomeViewModel(…)` → `vms.own(…)` rewrite handles the common case and **misses five
shapes**, all found by the compiler rather than by inspection:

1. the constructor on the line *after* the `=` (§4.8.4);
2. a constructor with a **trailing lambda** — `own(VM(…)) { … }` hands the lambda to `own` (§4.8.6);
3. a **synchronous** test (`fun x() { … }`, no `runTest`) — `vms` is not in scope;
4. a constructor followed by a **scope function** — `VM(…).also { }` / `.apply { }`, so the
   construction is not the whole right-hand side;
5. a constructor inside a larger expression — `return VM(…) to repo`, where there is no `=` at all.

⚠⚠ **A sixth shape, and it breaks the rule the first five suggested.** Those five all failed to
*compile*, which made the compiler look like a sufficient oracle. It is not. A construction used
**inline** — `assertIs<…>(SomeViewModel(repo).uiState.value)`, `Screen(SomeViewModel(repo))`,
`return SomeViewModel(…)` from a helper — has no `=` for the rewrite to find, is simply never
wrapped, **compiles perfectly**, and leaves exactly the leak the sweep exists to remove.

Codex found five such sites reviewing the close-out; a scan then found **fourteen** across the
swept modules, in `:timeline-shared-logic`, `:mia-shared-logic`, `:home-shared-logic`,
`:fit-shared-logic` and `:timeline-shared-ui`. All are fixed.

⭐ This is the same blindness [assertNothingActive] has by construction (§4.8.1, row 3: the guard
only ever sees what it was handed) — so a sweep this shape needs a **static** counterpart, and
`scripts/check-owned-view-models.py` is it. It scans the swept modules for any `SomeViewModel(`
outside an `own(` call, exempting only a Koin/Compose `viewModel { }` factory whose instances land
in an `ownStore`d store. ⚠ Not wired into `run-gate.sh` — that is the maintainer's call; run it
after any sweep, and add a module to its `SWEPT` list the day it is migrated.

⚠ The honest rule, then: **the compiler catches a rewrite that went wrong; only a scan catches a
rewrite that never happened.** Relying on the first alone is what let fourteen sites through.

⚠⚠ And a rewriter that *detects* these shapes must beware the same substring trap twice over:
`jvmTest` contains `vmTest`, and `desktopComposeVmTest` contains `VmTest`. The second one
double-wrapped two already-converted Compose tests in this very sweep.

### 4.8.9 ⚠ The one module deliberately left: `:tenshu-sdk-ios`

One file, `SignInSurfaceTest`, in an **`iosTest`** source set. The module declares `iosArm64` and
`iosSimulatorArm64` and nothing else — no JVM, no Android — so on Linux it can be neither compiled
nor run: K/N cannot cross-compile an Apple target, and the lane reports success having done nothing.

Converting it would mean shipping a rewrite that **nothing had checked**, in the one place where the
usual oracle (the compiler) is unavailable. `:viewmodel-test-utils` does declare both of those
targets, so the dependency would resolve; the conversion is a ten-minute job **on a macOS host**,
and should be done there with `./gradlew :tenshu-sdk-ios:iosSimulatorArm64Test` as the check.

## 5. Phases

| Phase | What | Gate |
|---|---|---|
| **0** | ✅ **Done, §4.5.** Census works, attributes to the constructor, costs nothing measurable — and the population is ~149 in one module, which forces the ratchet shape (D5) and the per-JVM shape (D6). | — |
| **1** | ✅ **Done, §4.8.** Arm A built, adopted by `:photos-shared-ui`, `:drive-shared-ui` and `:maps-shared-logic`, and extracted to `:viewmodel-test-utils`. | ⭐ Met: photos' census dropped 199 → 1 and the residual is not a view model; the fixture's own contract is pinned by `OwnedViewModelsGuardTest`. |
| **1b** | ✅ **Done, §4.8.4–§4.8.9 — 18 of 19 modules, 127 of 128 files.** The one exception is `:tenshu-sdk-ios`, deferred to a macOS host with its reason recorded (§4.8.9). | ⭐ Met: every swept module's suite is green and every one of them goes red with the guard armed. |
| **2** | Ship B on one module: exit census + a checked-in baseline of creation sites, failing only on a new site. | ⚠⚠ **Re-measure §4.5 first** — its 149 predates the `:drive-shared-ui` sweep and is the number D5's whole ratchet-vs-gate argument rests on. Then: 0 false failures across 10 whole-module runs under load, and a baseline regenerable by a script or it rots like any pinned file. |
| **3** | Roll B out per module as each is migrated; the baseline shrinks as Arm A lands. | The gate stays green for a week with B on. |

⚠ Phase 1 does not depend on Phase 2. That is deliberate: the two arms are sequenced so the cheap
half never waits on the speculative half — and it paid off, since Phase 1 shipped in full while
Phase 2 has not started. ⚠⚠ The reverse is now the risk: Arm A's guard reads like detection and is
not (§4.8.2), so the argument for Phase 2 has to be made on §4.8.3's 93 unowned files rather than
assumed to have been won.

## 6. What this does not fix

- **A coroutine parked on a real dispatcher.** The video-preview leak survived scope cancellation in
  principle — the fix was to stop hopping off the test's scheduler at all. B would *detect* it;
  neither arm fixes it automatically. ⚠ Arm A's `cancelAndJoin` narrows this: the join waits for the
  unwinding a bare cancel raced (§4.8.1). It does not close it — a coroutine that never observes
  cancellation still never returns.
- **The iOS lane.** See the JVM-only note in §3.
- **Non-coroutine leaks** — an un-closed Koin graph, a `TestApplication` still bound. Out of scope;
  `TestSystem.shutdown` already owns that in `:system-test-utils`.

## 7. Open questions

- **Q1.** ✅ **Answered by §4.5, and not the way v0.1 or v0.2 expected.** No per-test snapshot is
  needed and none is available; a per-JVM exit census attributes to the creation site, costs
  nothing measurable, and returns ~149 real sites in one module. The open part is no longer
  feasibility but *policy*: where a ratchet baseline lives, and who is allowed to raise it.
- **Q2.** ✅ **Answered by §4.8 — both, and the Compose half is where the fixture had to split.**
  `composeVmTest` wraps the `runComposeUiTest` body and `ownStore(…)` covers the case where the
  view models live in a composition's store and the test never holds a reference. ⚠ But
  `runComposeUiTest` cannot live in the shared module without putting Compose on
  `:maps-shared-logic`, so that one wrapper stays in each UI module. The seam is the answer, not a
  compromise.
- **Q3.** ✅ **Answered by §4.6 — spiked and rejected as the primary guard.** The counting form
  (`Q3b`) cannot see the leak that actually bit us; the sentinel form (`Q3d`) can, but names nothing
  and needs an arbitrary sleep. B stands.
- **Q4.** Are the ~149 worth burning down at all, or only the ones that can resume? §4.5 argues the
  ratchet sidesteps the question. If someone disagrees, that is the argument to have before Phase 2,
  not after. ⚠ Partly overtaken: `:drive-shared-ui` burned its population down anyway, as a side
  effect of adopting Arm A, so for the three swept modules the question is moot. It is live for the
  other 16.
- **Q5.** Does Arm B earn its cost once Arm A has swept everything? A fully-adopted Arm A leaves only
  the leaks nobody registered, and Arm B's value is precisely finding those — but at 0 unowned files
  the two arms converge and B becomes a guard against future non-adoption rather than present debt.
  ⚠ That is a real question at 93 unowned files (§4.8.3) and a different one at 0; whichever number
  holds when Phase 2 starts is the one to decide on.
