# Flaky tests — every known one, by cause, and the harness change that removes each cause

**Status:** analysis 2026-11-13. Three harness changes shipped with it (B, C and G below), and a
fourth since (C: `RepositoryIdling`); the rest are named, not built. §6 lists the slowest tests and what to cut.

The rule this doc serves: **flakes go to zero deterministically.** A flaky test is never retried,
shuffled, `@Ignore`d or loosened. Each one is a mechanism. Find it, force it so it fails every time,
then fix it and show that the same forcing passes. A *class* of mechanism is fixed in the harness, so
the next instance cannot be written.

Sources: every flake entry in [`tech-debt.md`](tech-debt.md) (mostly *"The full gate is flaky under
parallel load"*, *"Public CI was red on every run"*, *"The gate flakes under concurrent load"*), the
test KDocs that record a past flake, and four recent cases from public Linux CI.
[`testing.md`](testing.md) §10–11 hold the rules that earlier fixes left behind. This doc sorts them.

## 1. The categories, with counts

91 distinct flakes. "Open" includes *watch* rows and rows that were never diagnosed.

| | Category | Fixed | Open | The harness change that removes the class | State |
|---|---|---:|---:|---|---|
| **A** | **Sampling race**: a test reads `.value` once, at a moment it cannot name, or awaits one signal and asserts another | 13 | 1 | `awaitState(what, timeout) { predicate }` (`:core-test-utils`): returns the value to assert, and on timeout reports the last state and `elapsed / budget`. Order of remedies: `testing.md` §10.3 | ✅ helper shipped; adoption is per test |
| **B** | **Leaked global state**: Koin, `Dispatchers.Main`, kotlinx-test's `ExceptionCollector`, uncancelled view models or scopes, a shared home directory | 11 | 5 | `scripts/check-test-koin.py` (gate + pre-commit): a test that starts the global Koin stops it, and none stops another class's. `OwnedViewModels` (§11). A per-run `user.home` (§7.2) | ✅ Koin check shipped; 11 sites fixed. Next: a teardown census that fails the *leaker* (`proposals/testing-leaked-scope-guard.md`) |
| **C** | **Wall-clock bound**: `elapsed < N`, a 1 000 ms `waitUntil`, Mocha's 2 s, `usleep` used as an ordering | 9 | 0 | `scripts/check-test-wallclock.py` (gate + pre-commit): a ratchet. No test file may gain a real-time bound, `Duration.parse` budget or sleep; the way out is a written `wall-clock:` reason. For a Compose test over a real proxy: an `IdlingResource` on the producer (`RepositoryIdling`, §4) | ✅ shipped; 200 lines grandfathered (§3) |
| **D** | **Test double diverges from production**: dispatcher, lock, replay, who else calls, a fixture that froze what varies | 5 | 1 | Production-shaped defaults in the shared fixtures: `TestProxy` defaults to a real `TestDatabaseLock`; hand-stepped fixtures use `startBackgroundLoops = false`. Next: no defaulted `coroutineScope` on `TestProxy` (`tech-debt.md` "Wandering NPE") | partly built |
| **E** | **Inert seam**: a harness parameter defaulting to null, so the test passes or hangs for the wrong reason | 1 | 0 | Rule: a seam the test depends on has no default. `RecentFilesFailureIntegrationTest` delegates to the platform chain instead of narrowing it | rule only |
| **F** | **Platform assumption**: wildcard bind vs loopback, `/tmp` owned by root, the JS regex `u` flag, JS `Float`, no tz database, a `JAVA_TOOL_OPTIONS` banner, `chmod` under root | 10 | 0 | Bind `127.0.0.1`; one JS exclusion list read by both gate and CI; the Linux-container method in `tech-debt.md` "Every JVM test on Linux, run ahead of CI" | method, no gate (no Linux lane, by decision) |
| **G** | **Third-party library race**: paging-testing's `asSnapshot` | 2 | 1 | `fromTheObservedGeneration()` (`:core-paging`), used by Drive's `boundedSnapshot`: a snapshot starts from the listing's observed generation, so the observer's confirmation can never land mid-scroll | ✅ shipped |
| **H** | **Real product defect** that read as a flake | 16 | 1 | None: these are bugs. They are why a red is a lead, not noise | n/a |
| **I** | **Build step**, not a test (`*:packageDebug`) | 1 | 1 | Outside the test harness | — |
| **R** | **Random test input**: a random swap that matched the checksum 1 time in 16 | 1 | 0 | Fixed vectors. A seeded-`Random` rule is cheap to check and not yet written | rule only |
| **U** | **Undiagnosed** | 0 | 12 | Instrument, don't hypothesise: `elapsed / budget` on every wait (`awaitState`, `TestSystem.advanceUntil`), `PagingSettleProbe` at the next hang site | — |
| | **Total** | **69** | **22** | | |

Read the table this way:

- **H is the largest fixed bucket**, as `tech-debt.md` already concluded: most members that were
  diagnosed turned out not to be load. So the harness does not make a red go away. It makes a red
  *about the code*.
- **A, B, C and G are the classes a harness can close**, and together they are 31 of the 65 fixed.
  Each fix used to be one test at a time. Each now has a mechanism that stops the next instance.
- **Nothing here retries, shuffles or loosens.** The two verdicts this doc rules out by method are
  "flaky, retry it" and "slow machine, raise the timeout". `tech-debt.md`'s method section says
  why: a 24 ms wait that dies at 8 s is a dropped signal.

## 2. The four recent cases, and what each one generalises to

| Case | Category | Fixed by | Generalised as |
|---|---|---|---|
| `FilesListingEfficiencyTest.openAndScroll_isOnePassPlusPageWindows`: `IndexOutOfBoundsException: Index: 20, Size: 20` in `SnapshotLoader.awaitLoad` (Linux CI) | G | A `pagingSource` listing always emits a second generation when its observer is confirmed. `SnapshotLoader` holds an index across that, so the snapshot now starts from the observed generation | Every `boundedSnapshot` site. `ObservedGenerationSnapshotTest` forces it: plain `asSnapshot` throws every time. ⚠ The same race was the test's "5 or 7 windows" read-count spread. With one generation the count is 1189 / 1219 on every run |
| `ImportRowLayoutTest` → `TimelineEmptyStateRenderTest` (Linux CI, class order) | B | `ImportRowLayoutTest` stops Koin after each test | `check-test-koin.py`. It found one more leaker (`RecoveryPhraseScreenTest`) and ten `stopKoin()`-before-`startKoin` absorbers that would have hidden the next one |
| `StreamRegistryConcurrentRegistrationTest.aRegistrationIsExclusiveOfAnotherSessionsRegistration` (`:core-common`): `both streams send their snapshot expected:<[1, 2]> but was:<[1]>`, once in a full gate run (cloud container), 5/5 green alone | A | The test waited for the second registration to **reach** its array listener, then read the snapshots. But `setDatabaseListener` sends its snapshot after it releases the registry lock, still on the pool thread. The test now joins that registration's `Future` first. A 300 ms delay on stream 2's `send` reproduced it every time; with the join it is green, and 10/10 under a 4-core busy loop. Removing the registry lock still fails the case | A latch inside a call says the call got **there**, not that it **finished**. Join the call itself before asserting what it does on its way out |
| `ModelsLedgerTest`, read once, 1 in 9 on Linux | A | Await the settled state | `awaitState`, and `PairingVmFlowSystemTest` now uses it rather than a private copy |
| `MomentSliceRowMaxSharedReplicaTest` (both cases, iOS shard 3): `ReplicaReplaced: … fenced: COMMIT_FAILED` | C | One save on the starved 3-core runner held the write lock 1 002 ms, past the store's real `HOLD_MAX` (1 s), and the watchdog fenced the handle. Locally no hold passes 500 ms. `SharedReplicaBinding` now takes `limits`, attached at launch, and the test passes a 30 s `HOLD_MAX` | A test over the real iOS store that is not *about* a watchdog passes its own `ReplicaLimits` (the store's design already said so; the binding had no way in) |
| `SyncActivityRowWriteWakesNobodyTest` (iOS): `the row's own reader followed it: 6` | C | It asserted the reader saw ≥ 10 of 40 writes. The reader is conflated, so that count is the scheduler's. Now ≥ 1: the claim is that it is woken; the feed and sibling equalities are the test | A conflated reader's count of values is a measure of the machine. Assert that it was woken, or the value it ends on |
| `PhotosOnDeviceAiTest.anExpiredWakeExpiresTheTokenStopsThePassAndIsCompletedOnce` (iOS): `Finished(deferred)`, expected `Stopped` | A | It expired the wake once the pass was `Running`, which is before the pass's own bar check. Expired there, the pass ends deferred. It now waits for the pass to be parked on the photograph | `Running` is a state the pass publishes on its way in, not the place the test needs it to be. Wait for the gate the producer must pass |
| `PhotosPeopleGroupingTest.aPhoneTheFacePreflightFoundUnsuitableGroupsNoFaces` (iOS shard 3, four sightings), and `SyncActivityRowWriteWakesNobodyTest` timing out at 30 s on its first `catchUp` in the same runs | B | Every graph a `:tenshu-sdk-ios-photos` test assembled outlived its test: about 130 per run, with sync loops, scheduler and index runner all on `Dispatchers.Default`. The instrumentation answered `Default answers within 2 s=false`, and the red test took 65 s for 22 s of waits. Measured with `getrusage` over a 5 s idle sleep: **0.00 cores busy at the start of the suite, 2.99 at the end**. That is the whole pool on CI's 3-core runner, and ~1/4 of a Mac's, which is why it never failed locally. `TestGraphs.stopAll()` (`:tenshu-sdk-ios-core`) is called last in the `@AfterTest` of every iOS test class that assembles a runtime. It cancels and joins each graph `assembleBase` built over a test replica (its `ProxyGraphJob`) and the scopes the app runtime registered through `Base.stopsWithTestGraph`. With it the end-of-suite figure is 0.03 cores | A test that assembles a runtime stops it: a new iOS test class that assembles one calls `TestGraphs.stopAll()` last in its `@AfterTest`, and an app runtime registers any scope it makes outside the graph. A suite's wall time that grows 10–30× on CI but not locally (here `PhotosOwnedInferenceStoresTest`: 9 s → 4 m 26 s) is load the suite made itself |
| `IosFileDatabaseRangedReadMemoryTest.walkingALargeFileInWindowsDoesNotHoldTheFile` (iOS), once on CI | U | Not diagnosed. CI measured 36 MiB of growth against a 24 MiB bound; a Mac measures ~0 MiB alone and in the full `:core-common` suite. The defect it guards holds the whole 96 MiB file, so this is something else growing the process on the runner | The red now splits the growth into Kotlin heap and the rest, and re-measures after a second collect. The bound stayed a quarter of the file |
| `VCardCodecTest` "linear", a wall-clock bound on CI's iOS simulator | C | A growth ratio | `check-test-wallclock.py`. ⚠ The ratio still runs on the wall clock (it is grandfathered). A count of operations is the deterministic form |
| `PairingVmFlowSystemTest.offerFlow_phoneDenies_desktopShowsDeclinedNotSpinner`, timed out at load ~20 | U | Not diagnosed. A 20 s real-time budget over a real relay and a 2 s offer poll (`OFFER_POLL_MS`) | The wait now reports `elapsed / budget` and the last state. The next red says whether a signal was dropped or the box was slow |
| Desktop socket tests and `/tmp` ownership (Linux CI) | F | Root-owned `/tmp` counts as ours | Not a flake: a deterministic platform red, and a product defect |
| `SingleInstanceLockTest.theLockDiesWithItsProcessAndTheLeftoverFileIsInert`, found by this pass (cloud container) | F | Its child JVM's first line is the protocol (`HOLDING`), and with `JAVA_TOOL_OPTIONS` set the JVM prints `Picked up …` on the merged stderr first. The probe is now started without the three banner variables | A child process a test reads from gets an environment the test controls. Red in a container, green on a dev Mac: the same shape as `/tmp`, and invisible to the Mac gate |
| `HarnessArgumentsTest.suppliedPhotosThatIsUnreadableFails` and `…ManifestFileThatIsMissingOrUnreadableFails` (`:inference-service-proxy`), found by this pass | F | `setReadable(false)` does nothing for root (`CAP_DAC_OVERRIDE`), and containers run as root. `HarnessArguments` now takes a `readable` seam that defaults to `File::canRead`, and the tests use the seam instead of `chmod` | A test fixture must not depend on who runs the test. Never "unreadable via permissions": no permission is unreadable to root |

## 3. The wall-clock ratchet's baseline — where the remaining real time is

`scripts/check-test-wallclock.py --list` shows it. At the cut there were 200 lines:

| kind | lines | of which iOS-only test sources |
|---|---:|---:|
| `Thread.sleep` / `usleep` / `withContext(Dispatchers.Default) { delay(…) }` | 162 | 95 |
| an assertion comparing a real-time measure | 33 | 28 |
| `Duration.parse(…)` budget | 5 | 0 |

⚠ **Three in five sit in iOS test sources, which the gate cannot run off macOS and public CI runs only
on a 3-core runner.** `ReplicaProtectionTest` (34) and `SharedReplicaHostTest` (38) are most of
it, and `ReplicaProtectionTest` has already flaked there (`tech-debt.md` "Public CI was red").
Most of these are *upper bounds far from the alternative they rule out*, for example "the drain
was cut to 2 s, not 10 s: `tookMs < 3_000`". That is the honest form of a real-time assertion. It
should carry a `wall-clock:` reason, and so leave the baseline as an exemption rather than as debt.
The `usleep`-as-ordering ones need a happens-before (`Setup.holdAccess`'s shape).

`--update` lowers the baseline as files are cleaned. Only a rise fails.

## 4. What is still open, and the next harness step for each

- **B, five rows.** Photos `anAbsentThumbnailIsNotRemembered_…`, `MultiProxySyncIntegrationTest`,
  the wandering `ClosedScopeException` after `stopKoin`, and the other `*LocalRuntimeTest`s on iOS.
  ⇒ The leaked-scope census from `proposals/testing-leaked-scope-guard.md`, failing the class that
  *leaked* rather than the next one to run, and `Assembly.close()` in every iOS local-runtime test.
  ⚠ The fifth, `SharesPage.test.tsx` (watch, moved from U), is a leak of a different kind: **a test
  body that outlives its timeout.** Vitest fails a test at its 5 s timeout but does not cancel its
  async body. The body keeps querying the global `screen`, now the next test's DOM, and calls that
  test's freshly reset mocks. Forced: a 1 ms timeout on the revoke test fails the cancel test, which
  never fails unforced. ⇒ Keep cold work out of a test body: import at module scope, never
  `await import(…)` inside `it(…)`. ⚠ Rendering the same fixture in two adjacent tests is what lets
  a leaked body act at all.
- **C, none.** ✅ The last two, `FilesScreenIntegrationTest.testTwoUploadsShowCorrectDetailImage`
  and `….testFileUploadConflictShowsDialog`, waited on a real-time `waitUntil` for state made on the
  proxy's threads and the Swing EDT, which `waitForIdle()` cannot see. ⚠⚠ "Await the upload, then
  `waitForIdle()`" was not enough: the listing re-emits after the call returns, and paging-compose
  presents on `Dispatchers.Main`. `RepositoryIdling` (`drive-shared-ui` `integration/utils`) is an
  `IdlingResource` that is busy while a repository call is in flight or a live listing owes an
  emission, and that runs an EDT barrier on every poll. So `waitForIdle()` itself is the
  happens-before. ⚠ Register it through `composeDesktopVmTest`: the plain v2 `runComposeUiTest`
  never polls a resource. Forced pair in `tech-debt.md` (the flaky-gate entry's closed members).
- **D, one row (watch).** `RetiredBlockDisposalTest` / `MultiRealmPushDrainTest` `Entry not found`.
  A scratch run with a lock-checked store under every `:sync-service-proxy` fixture recorded no
  unlocked access (2026-11-15). So the `TestDatabaseLock` default most likely closed it, but nothing
  forced it. ⚠ The same run found a production defect, not this flake: the retired-block sweeper read
  and wrote its cursor without the lock (`tech-debt.md`, its own entry). ⇒ Make the lock-checked
  store permanent under `TestProxy`.
- **F, none.** ✅ The wildcard-vs-loopback family is closed. `S3BlobStoreRealS3ContractTest` dials
  S3Mock's HTTPS connector, bound to `127.0.0.1`. `OperatorRouteNetworkExposureTest` binds the two
  addresses it dials rather than `0.0.0.0`, because the guard it tests reads the socket peer, not the
  bind (`tech-debt.md`, the closed members). ⚠ A test that must model a wildcard bind can bind each
  address it dials instead, whenever the code under test reads the peer. ⚠ A bind proof needs two
  probes, because each OS discriminates on a different one. A `127.0.0.1` decoy beside the listener
  binds only on macOS, and Linux refuses it whatever the listener's bind. A connect on a
  non-loopback interface is answered by a wildcard listener on either OS. Keep the wildcard
  connector in the same run as the forced arm, or the probe tests nothing.
- **G, one row (watch).** The `FilesPagingIntegrationTest` hang, moved from U: a teardown race in
  `asSnapshot` that needs a second generation landing after it has settled on the first. Every
  `asSnapshot` site now gets one generation from `fromTheObservedGeneration()`, so the trigger is
  gone. ⚠ That is shown by reproduction counts, not by a forced pair: the window sits between two
  1 ms debounce timers and never opens on virtual time (`tech-debt.md` § *The hang, narrowed to one
  seam*). A new hang at a `boundedSnapshot` site is a different mechanism.
- **U, eleven rows.** Each needs a forced pair or an in-memory probe. ⚠ The most frequent,
  `ContactHandshakeSystemTest` `COMPLETED`/`REDEEMED` (~1 in 5 on 2026-11-01), moved to **H**. It was
  a real defect: a device's own echoed older block replaced its newer local write, because ingest
  compared against the last *ingested* write only (`tech-debt.md`, its own entry). ⚠⚠ Lesson for the
  rest: a read "one state stale" under load can be a write-back from sync ingest, and a unit test
  of `SyncProcessor` forced it deterministically where the system test could not. ⚠ `println` perturbs the
  timing (`tech-debt.md`). ⇒ For the `:system-test-utils` members, the stepped `TestSystem`
  (`testing.md` §12) is the forcing tool: a suspected interleaving becomes a sequence of
  `sync(…)` calls, run in both orders.
  ⚠ When a red cannot be reproduced, make its next occurrence say why. `SyncLaneTest`'s stranded
  journal row is the case: the emit consumer swallowed every exception and acked the lane event
  anyway, so a throw read as an off-by-one. `SyncObserver.consumerError` lets a test fail on the
  throw itself (`tech-debt.md`, its row).

## 5. How to use this doc

- A new flake gets a row in `tech-debt.md` (the evidence) and a category here (the class). If it
  fits no category, that is a finding: add the category and ask what harness change would make it
  unwritable.
- Adding a check: make it a ratchet (a baseline that only falls) or fix every existing site in the
  same PR. A check that is red on day one gets switched off.
- The checks are static on purpose: the full gate is already too heavy to add a Linux lane or a
  shuffle run to it, so the cheapest place to stop a class is before the test exists.

## 6. Slow tests — the gate's slowest classes, and what to cut

Measured from one `scripts/run-gate.sh` test line (2026-11-13, a 4-core Linux container,
`TENSHU_GATE_SKIP_LANES=1`): 181 test tasks, 2 135 classes, 14 493 cases. The test line took
**12 min 26 s** wall time, and the classes summed to **1 297 s**. 27 classes take ≥ 10 s and
together cost 413 s, about a third of the total. Absolute numbers from a container are not a Mac's.
The ranking is what to act on.

The slowest are slow for four reasons. Every reason except the first is also a flake mechanism:
real time is what makes a test both slow and load-sensitive.

| Class (module) | Time | Why it is slow | Cut |
|---|---:|---|---|
| `PhotoTileRequestSizeTest` (`:photos-shared-ui`) | 18.2 s → ~3 s | `mainClock.advanceTimeBy(600_000)`. Compose's test clock steps every 16 ms frame, so that is ~37 500 frames | ✅ **Done**: advance one retry ceiling (`PREVIEW_RETRY_MAX_MS` + 1 s), the same coverage at 0.96 s. ⚠ Size every `mainClock.advanceTimeBy` to the timer it must pass, never "a long time" |
| `StreamSubscribeRetryTest` (`:core-common`, **twice**: `desktopTest` and `testAndroidHostTest`) | 2 × 19.1 s | A real 7.5 s `withContext(Dispatchers.Default) { delay }` that counted retries in the window, as `3..5` | ✅ **Done, 2 × 19 s → 2 × 0.6 s.** No backoff clock was needed: the backoff is a plain `delay` on the collector's dispatcher. The one real dispatcher on the path was `InProcessProxy`'s `DefaultInteractorProvider` scope, now a `clientScope` parameter (production default unchanged). The class runs on `backgroundScope`, and the count is exactly 4; a flat 1 s backoff fails it with `expected:<4> but was:<8>`. 10/10 repeats green |
| `CalendarDeleteSyncSystemTest`, `CalendarMirrorStopSystemTest`, `ConcurrentMoveVsDeleteSystemTest`, `RelayAmnesiaRepairSystemTest`, `PhotoDeleteSyncSystemTest`, `PairingVmFlowSystemTest`, `SyncedThumbnailSystemTest` (`:system-test-utils`) | 11–25 s each; the module was 198 s, the largest | Two or three devices converging over a live in-process relay at a real 100 ms–1 s poll, waited on by `advanceUntil`, plus 3 s real-time settles | ✅ **Done for all seven**: a stepped `TestSystem` (`testing.md` §12) drives each device's `drainNow()` in an order the test writes, and bounds waits by rounds, not time. 21.9 → 2.6 s, 19.3 → 0.9 s, 14.4 → 1.1 s, 11.0 → 1.4 s, 10.6 → 6.0 s; the module 177 → 111–124 s (same container, `--rerun`). Each race ordering is its own case, forced by the sequence. `RelayAmnesiaRepair` 14.3 → 1.7 s with the stepped sync clock (`TestSyncClock`), where a backoff window passes when the test advances it, and with `ForgetThisDevice`'s wait stopping on the scheduler's `401` state instead of its 8 s timeout (a product fix: a removed joiner waited it too). `PairingVmFlow` 11.7 → 0.7 s: its three own polls (offer status, host requests, `AwaitPairing`) take their pause from a `StepPacer`, so each poll is one the test takes. ⚠ A gated stepped joiner pulls only once `realmRelayRegistered` has latched (`testing.md` §12) |
| `FilesScreenIntegrationTest` (`:drive-shared-ui`) | 33.0 s / 10 → 16.9 s / 7 (gate); alone 13.2 s → 8.9 s | Compose over a real in-process proxy, with real-time `waitUntil`s | ✅ **Done**: no polling (`RepositoryIdling`, §4); `testClickOnFileTriggersDownload`, which never clicked, is now `UploadFileIntegrationTest.uploadFile_thenGetRemoteFile_returnsTheUploadedBytes`; three tests that each created a folder or uploaded a file through the UI to reach their subject are folded into one round trip or seeded through the repository. ⚠ What is left is ~2.5 s of first-test warm-up and 150–350 ms per interaction, rendering menu and dialog animations to completion |
| `S3BlobStoreRealS3ContractTest` / `S3BlobStoreContractTest` (`:sync-relay-server`) | 18.7 s + 12.2 s → 7.5 s + 12.4 s | An S3 server (S3Mock or the Netty fake) started per **test**, not per class | ✅ **Done**: one lazily started server per test JVM, and a fresh bucket per store. Isolation is pinned by the contract's `aFreshStore_sharesNothingWithAnEarlierOne`, run by every backend, and the single start by `everyStoreInThisJvm_sharesOne…`. Same-module `--rerun`: 6.5 s → 3.9 s and 5.0 s → 3.9 s, with 5 more tests between them. ⚠ Under gate load the fake did not get faster: its time is its two 1 500-PUT tests (deliberate; one pins the batching ratio), not its server start. ✅ **Cut too** (2026-11-16): the ratio test seeds `S3BlobStore.BATCH_SIZE + 1` keys straight into the fake's bucket instead of 1 500 HTTP PUTs (6.2 s → 0.2 s alone), and the contract's `deleteAll_spansMoreThanOneBatch` uses S3's limit + 1 (1 001 PUTs, not 1 500). The fake class went 14.7 → 7.5 s. A per-key `deleteAll` fails the ratio test (1 001 requests, not 2), and so does a lost split (1, not 2). ⚠ Only the ratio test can catch the split: neither the fake nor S3Mock 3.9.1 rejects an oversized `DeleteObjects` (`S3BlobStoreRealS3ContractTest`'s KDoc) |
| `ViewerDwellMintTest` (`:photos-shared-ui`) | 9.5 s → 6.7–7.2 s alone; 17.1 s in the gate | `mainClock.advanceTimeBy` steps of 2–5 s around a 1 s dwell, ~1 160 frames in all | ✅ **Done**: every step is derived from `DWELL_MS` (now `internal`): `DWELL_MS + 500` past it, `DWELL_MS / 2` for a glance, a late subscriber at `DWELL_MS + 500`. Five policy breaks (no settle check, no dwell, no `take(1)`, a captured licence, a dwell timed from subscription) each still fail the case written for it. What is left is the first case's warm-up |
| `PhotoEditorScreenTest`, `CollectionsScreensTest`, `AboutSheetTest` (`:photos-shared-ui`); `ContactsScreensTest` (`:contacts-shared-ui`); every Compose UI class | 8.3 → 4.5 s, 12.7 → 5.9 s, 11.6 → 7.9 s; 16.9 → 10.7 s. The ten UI modules 321 → 241 s | **Motion**, profiled: 150–550 ms per interaction rendering a dialog's, menu's or sheet's animation to completion, one 16 ms frame at a time. The waits were cheap and the images tiny | ✅ **Done**: `NoMotion` (a zero `MotionDurationScale`, `:ui-test-utils`) is the effect context of every runner and every `composeVmTest`, so animations snap to their end (`testing.md` §13). Five broken UI rules (an `animateTo` skipped, a confirm skipped, a stray dismiss button, a self-offered restore, a dead Delete) still fail. ⚠ It scales animations, never `delay`s. ⚠ Classes whose subject is animation timing (`FeedScrubberTest`, `SyncActivityCapsuleTest`, `ViewerDwellMintTest`, `AppAutoContinueButtonTest`) and the three paging reactivity guards stay on real motion. What is left is per-class first-composition warm-up (~1–3 s on the first case) |
| `DuplicatesBoundedReadTest` (`:photos-shared-logic`); `SyncedFolderControllerTest.theSkipAsksTheRealmInBoundedReplies_andStillSkipsWhatTheRealmHolds` (`:drive-shared-ui`) | 8.1 s → 4.5 s; 3.7 s → 3.5 s | A large N, on purpose: reading the realm whole must overflow the reply budget, and Duplicates' groups must straddle hash pages | ✅ **Done**: N is derived from `RealmContentReads.REPLY_BYTE_BUDGET` over a 190 B hash-only row (1 380 files) and from `HASH_PAGE_SIZE` (groups at the page edges, three pages). A precondition asserts that the narrowest whole-realm read really overflows the budget. ⚠ Derive N from a size the test **measures**, never from a row-size constant alone: rows measure ~268 B (path + hash) and ~200 B (hash only), lighter than `HASH_ROW_BYTES` and the ~205 B in the docs, so an N taken from those lands under the budget, where an unbounded read passes |
| `WorkoutActivityRollupMaintainerTest.backfillOverALargeHistoryStaysCorrect` (`:health-service-proxy`) | 7.7 s (4.7 s alone) → 0.06 s; the class 6.2 → 1.1 s | 10 000 saves, but no boundary to cross, and it **never reached the backfill** it was named for: the proxy's init backfill ran over an empty store, and the 10 000 went through the live maintainer | ✅ **Done**: now `backfillOverAMixedHistoryCountsAndAttributesEveryWorkout`. Three workouts of each of four types, `clearRollups` to make the store pre-rollup, then the backfill, then every count and **every** attribution. A scan that drops every second attribution fails it, while `backfillScansAPreRollupStoreAndDerivesEveryCount` and the old 10 000-workout case both stayed green under that break. ⚠ N was never the subject: `deriveIfStale` reads the whole array once and counts into a map, with no window, page or batch |
| `AddsBeforeRemovesTest`, `FreedContentRepairTest`, `EdgesOnPushTest`, `TombstoneCascadeOnTheWireTest` (`:sync-service-proxy`) | 15.3 s → 0.8 s, 12.4 s → 0.25 s, 9.9 s → 0.10 s, 2.1 s → 0.09 s | **Not N**: `MockSyncRelay`'s simulated latency (`delay(500)` a push, tombstone or read) ran on a real clock, because each rig built `TestProxy` on its default `Dispatchers.Default` scope, so the drains it ran were off `runTest`'s scheduler | ✅ **Done**: each rig passes `coroutineScope = backgroundScope`, so the latency is virtual. A sweep of every `MockSyncRelay` user with the latency at 0 found no test that needs it. ⚠ A rig over `MockSyncRelay` belongs on the test's scope: on the default scope every relay call costs 500 ms of real time. ⚠ `aCascadeIsRecordedBeforeItsTombstoneCanBePushed` keeps its real dispatcher on purpose: it races a concurrent drain, and costs 0.02 s |
| `PairingHandshakeIntegrationTest` (`:control-shared`) | 8.8–9.2 s → 1.7–1.8 s | **Not N**: the same `MockSyncRelay` latency, plus `HostPairingMonitor`'s 2 s poll cadence, on a real clock that **cannot** be made virtual: `ProxyGateway.call` blocks internally, so the harness deadlocks under `runTest` | ✅ **Done**: the test binds `MockSyncRelay(…, simulateLatency = false)` and passes `HostPairingMonitor(…, pollIntervalMs = 50)`. Both defaults are unchanged. ⚠ Turning the latency off is the fallback for a harness that must run on real time; everywhere else, put the rig on the test's scope. ⚠⚠ A faster poll exposed a race the 2 s one hid: `approveActiveRequest` raises `pairingComplete` **before** it retires the session (deliberately), and the retirement takes `sessionLock`, which a 50 ms poll contends 40× as often. Asserting at completion what only retirement clears failed under gate load; the test now awaits `isPairing` going false first. A `delay(300)` between the two reproduced it every time |
| `SqliteObjectStoreCommitFailureTest` (`:core-common`) | 10.9 s → 2.1 s (alone) | **Not N**: ten cases held the write lock from a second connection in a `Thread.sleep(1_000)` or `(2_000)` and joined it, though the store gives up in ~80 ms (20 ms `busy_timeout`, two 10 ms backoffs) | ✅ **Done**: the hold ends on an event — the failed `flush()`/`close()`, or the store's first traced retry — not a guessed time. The retried-and-lands case now retries **by construction**. One case keeps a timed hold (600 ms), because the wait on the lock is its subject. ⚠ That case once checked only the lock, and the lock's release sits in a nested `finally` that runs even when a cancelled teardown throws at its first suspension, so removing the teardown's `NonCancellable` failed **no** case. It now also asserts `connectionsClosed` (an internal probe set only once both connections have closed), and removing the guard fails it: "a cancelled close must still close its connections" |
| `HomeDeviceSystemTest` (`:hub`) | 12.9 s → 6.2 s alone (14.9 s in the gate) | **Not N**: 9.6 s of `delay` proving that something did **not** happen (a re-delivered command, a stale command, sub-quantum drift, a re-announce) — "wait several sync cycles, then assert nothing changed" | ✅ **Done**: each negative claim now follows a positive one, from `DeviceBridgeHost` probes beside `executedCommandCount`: `handledCommand(id)` (the gate's decision, for which `issuedAt`) and `handledState(deviceId)` / `hasPendingFlush()`. The rename check reads the hub's replica the moment `publishInventory` returns. Four product breaks (journal, stale window, quantum, clobber) fail the same case before and after. ⚠ A wait proves nothing about absence: it passes just as well when the command never arrived. ⚠ Not on the stepped `TestSystem` (`testing.md` §12): `HubRuntime` runs its own sync loop, outside it. `HomeDeviceControlSystemTest` (5.2 s alone) has no such waits and is left |
| `SelfEchoConvergenceSystemTest` (`:system-test-utils`) | 7.4 s → 2.9 s alone (6.7 s in the gate) | **Not N**: two `delay(3.seconds)` settles proving that nothing crossed a partition and that nothing flipped back, on a polled system at a 200 ms poll | ✅ **Done**: stepped (`testing.md` §12). The partition is checked after one gated tick of each device and against the relay's `lastArrivalSeq`; the release is `sync(older, newer)`; the end state is asserted after `syncUntilQuiet()`. Reversing LWW (`< 0`) fails it in 3.4 s with a named state, where the polled version took 66 s to time out. What is left is the system's start-up. ⚠⚠ **It no longer guards its headline claim**, before or after: since `LocalWriteMetadata` the older block does not overwrite `newer`'s unpushed write, so dropping a device's own blocks leaves it green on master too. That property is pinned by `SyncProcessorOwnEchoTest.aQueuedWriteOverwrittenByAnIngest_stillConvergesFromItsEcho` and `ListingNotificationSystemTest.aWipedDeviceRefillingItsOwnHistory_…`; the KDoc now says so |
| `ArrivedPhotoPathsTest.aColdStartQueuesOnlyWhatArrivedPastThePersistedCursor` (`:control-shared-proxy`) | 2.3 s → 0.14 s alone (7.6 s in the gate) | A large N: 1 000 uploaded photographs before the cold start | ✅ **Done**: `maxOf(CURSOR_TAIL, NEW_ARRIVALS_BATCH_CAP) + 1` (51): a log longer than the cursor's own tail, and more history than one batch takes. A reader that re-offers the history and a cursor searched only within the log's first `CURSOR_TAIL` rows both fail "a cold start re-offered the log". ⚠ At `CURSOR_TAIL - 1` that assertion **passes** under the second break: the tail is a real boundary, not a guess |
| `PairingResponseInboxTest.aRetryRacingTheAck_neverLeavesAConsumedHashServingARefill` (`:sync-relay-server`) | 7.7 s (gate) → 3 ms | **Not N**: 2 000 rounds of two threads racing `post` against `ack`, which by its own comment could pass without the lock on an unlucky schedule | ✅ **Done**: forced, not stressed. An internal `afterConsumedCheck` seam (`null` in production) runs inside `post`'s transition between the consumed check and the insert; the test starts the ack there and waits for its **state**: `BLOCKED` on the monitor (the lock holds) or `TERMINATED` (it ran through the window). Removing the lock from either `post` or `ack` fails it on every run, "an ack completed between a retry's consumed check and its insert"; 10/10 green under a 4-core busy loop. The stress version caught both breaks too, at rounds 236 and 654, by luck of the schedule. ⚠ Wait on a thread's state, not a time: a `BLOCKED`/`TERMINATED` spin is a positive signal and needs no `wall-clock:` exemption |
| `ReapRemovalOutboxTest.aLargeSubtreeGoesOutInBatchesOfAtMostOneThousand` (`:sync-service-proxy`) | 3.8 s (gate) / 1.4 s alone → 0.6 s | A large N: 2 500 reaps, two full batches and a partial | ✅ **Done**: `ContentBlobStore.MAX_REAP_BATCH + 1`, the smallest subtree that must split, and the case now asserts it **is** two requests, not only that none exceeds the cap. A drain that sends one unchunked batch fails it: `[1001]` |
| `ThumbnailLadderParityTest` (`:file-service-proxy`) | 10.6 s → 5.1 s alone | Pixels, not N: the per-rung path decodes the original once per rung, and each upload runs twice | ✅ **Done**: each fixture is sized from the top rung's edge (`GetStoredThumbnail.XXLARGE_PX`) to the boundary it is about. Production branches only on "larger than this rung" and on each rung's cap. The 15 MP landscape is now 2 560 × 1 536 and the noise frame 2 112². Same declines, step-down and pixel margins (worst 1.62 of 4). ⚠ The stripes stay at 4 200: twice the rung is where a subsampled read would pick factor 2 and turn them black. `LadderFixtures` writes pixels in bulk, byte-identical, but the encode is most of a fixture's cost |

**One structural cost:** 287 `commonTest` classes run on two JVM targets, the module's JVM/desktop
target and `testAndroidHostTest`. The second copy costs **68 s**, 32 s of it in `:core-common`.
It is not pure duplication, because the host run reaches `androidMain` actuals. But most of
those classes touch no `expect` declaration. Running only the classes that do, or a tag, would
cut most of it. That is a decision, not a fix, because it narrows Android coverage.

⚠ Not counted: two Robolectric classes (`:control-shared-proxy`, `:inference-service-proxy`) went
red in the container because it could not download `android-all-instrumented`. That is an
environment limit, and they did not run.
