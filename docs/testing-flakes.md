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

85 distinct flakes. "Open" includes *watch* rows and rows that were never diagnosed.

| | Category | Fixed | Open | The harness change that removes the class | State |
|---|---|---:|---:|---|---|
| **A** | **Sampling race**: a test reads `.value` once, at a moment it cannot name, or awaits one signal and asserts another | 11 | 1 | `awaitState(what, timeout) { predicate }` (`:core-test-utils`): returns the value to assert, and on timeout reports the last state and `elapsed / budget`. Order of remedies: `testing.md` §10.3 | ✅ helper shipped; adoption is per test |
| **B** | **Leaked global state**: Koin, `Dispatchers.Main`, kotlinx-test's `ExceptionCollector`, uncancelled view models or scopes, a shared home directory | 10 | 4 | `scripts/check-test-koin.py` (gate + pre-commit): a test that starts the global Koin stops it, and none stops another class's. `OwnedViewModels` (§11). A per-run `user.home` (§7.2) | ✅ Koin check shipped; 11 sites fixed. Next: a teardown census that fails the *leaker* (`proposals/testing-leaked-scope-guard.md`) |
| **C** | **Wall-clock bound**: `elapsed < N`, a 1 000 ms `waitUntil`, Mocha's 2 s, `usleep` used as an ordering | 7 | 0 | `scripts/check-test-wallclock.py` (gate + pre-commit): a ratchet. No test file may gain a real-time bound, `Duration.parse` budget or sleep; the way out is a written `wall-clock:` reason. For a Compose test over a real proxy: an `IdlingResource` on the producer (`RepositoryIdling`, §4) | ✅ shipped; 200 lines grandfathered (§3) |
| **D** | **Test double diverges from production**: dispatcher, lock, replay, who else calls, a fixture that froze what varies | 5 | 1 | Production-shaped defaults in the shared fixtures: `TestProxy` defaults to a real `TestDatabaseLock`; hand-stepped fixtures use `startBackgroundLoops = false`. Next: no defaulted `coroutineScope` on `TestProxy` (`tech-debt.md` "Wandering NPE") | partly built |
| **E** | **Inert seam**: a harness parameter defaulting to null, so the test passes or hangs for the wrong reason | 1 | 0 | Rule: a seam the test depends on has no default. `RecentFilesFailureIntegrationTest` delegates to the platform chain instead of narrowing it | rule only |
| **F** | **Platform assumption**: wildcard bind vs loopback, `/tmp` owned by root, the JS regex `u` flag, JS `Float`, no tz database, a `JAVA_TOOL_OPTIONS` banner, `chmod` under root | 10 | 0 | Bind `127.0.0.1`; one JS exclusion list read by both gate and CI; the Linux-container method in `tech-debt.md` "Every JVM test on Linux, run ahead of CI" | method, no gate (no Linux lane, by decision) |
| **G** | **Third-party library race**: paging-testing's `asSnapshot` | 2 | 1 | `fromTheObservedGeneration()` (`:core-paging`), used by Drive's `boundedSnapshot`: a snapshot starts from the listing's observed generation, so the observer's confirmation can never land mid-scroll | ✅ shipped |
| **H** | **Real product defect** that read as a flake | 15 | 1 | None: these are bugs. They are why a red is a lead, not noise | n/a |
| **I** | **Build step**, not a test (`*:packageDebug`) | 1 | 1 | Outside the test harness | — |
| **R** | **Random test input**: a random swap that matched the checksum 1 time in 16 | 1 | 0 | Fixed vectors. A seeded-`Random` rule is cheap to check and not yet written | rule only |
| **U** | **Undiagnosed** | 0 | 13 | Instrument, don't hypothesise: `elapsed / budget` on every wait (`awaitState`, `TestSystem.advanceUntil`), `PagingSettleProbe` at the next hang site | — |
| | **Total** | **63** | **22** | | |

Read the table this way:

- **H is the largest fixed bucket**, as `tech-debt.md` already concluded: most members that were
  diagnosed turned out not to be load. So the harness does not make a red go away. It makes a red
  *about the code*.
- **A, B, C and G are the classes a harness can close**, and together they are 30 of the 61 fixed.
  Each fix used to be one test at a time. Each now has a mechanism that stops the next instance.
- **Nothing here retries, shuffles or loosens.** The two verdicts this doc rules out by method are
  "flaky, retry it" and "slow machine, raise the timeout". `tech-debt.md`'s method section says
  why: a 24 ms wait that dies at 8 s is a dropped signal.

## 2. The four recent cases, and what each one generalises to

| Case | Category | Fixed by | Generalised as |
|---|---|---|---|
| `FilesListingEfficiencyTest.openAndScroll_isOnePassPlusPageWindows`: `IndexOutOfBoundsException: Index: 20, Size: 20` in `SnapshotLoader.awaitLoad` (Linux CI) | G | A `pagingSource` listing always emits a second generation when its observer is confirmed. `SnapshotLoader` holds an index across that, so the snapshot now starts from the observed generation | Every `boundedSnapshot` site. `ObservedGenerationSnapshotTest` forces it: plain `asSnapshot` throws every time. ⚠ The same race was the test's "5 or 7 windows" read-count spread. With one generation the count is 1189 / 1219 on every run |
| `ImportRowLayoutTest` → `TimelineEmptyStateRenderTest` (Linux CI, class order) | B | `ImportRowLayoutTest` stops Koin after each test | `check-test-koin.py`. It found one more leaker (`RecoveryPhraseScreenTest`) and ten `stopKoin()`-before-`startKoin` absorbers that would have hidden the next one |
| `ModelsLedgerTest`, read once, 1 in 9 on Linux | A | Await the settled state | `awaitState`, and `PairingVmFlowSystemTest` now uses it rather than a private copy |
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

- **B, four rows.** Photos `anAbsentThumbnailIsNotRemembered_…`, `MultiProxySyncIntegrationTest`,
  the wandering `ClosedScopeException` after `stopKoin`, and the other `*LocalRuntimeTest`s on iOS.
  ⇒ The leaked-scope census from `proposals/testing-leaked-scope-guard.md`, failing the class that
  *leaked* rather than the next one to run, and `Assembly.close()` in every iOS local-runtime test.
- **C, none.** ✅ The last two, `FilesScreenIntegrationTest.testTwoUploadsShowCorrectDetailImage`
  and `….testFileUploadConflictShowsDialog`, waited on a real-time `waitUntil` for state made on the
  proxy's threads and the Swing EDT, which `waitForIdle()` cannot see. ⚠⚠ "Await the upload, then
  `waitForIdle()`" was not enough: the listing re-emits after the call returns, and paging-compose
  presents on `Dispatchers.Main`. `RepositoryIdling` (`drive-shared-ui` `integration/utils`) is an
  `IdlingResource` that is busy while a repository call is in flight or a live listing owes an
  emission, and that runs an EDT barrier on every poll. So `waitForIdle()` itself is the
  happens-before. ⚠ Register it through `composeDesktopVmTest`: the plain v2 `runComposeUiTest`
  never polls a resource. Forced pair in `tech-debt.md` (the flaky-gate entry's closed members).
- **D, one row.** `RetiredBlockDisposalTest` / `MultiRealmPushDrainTest` `Entry not found`.
  ⇒ Run every fixture over a lock-checked store.
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
- **U, thirteen rows** (`ContactHandshakeSystemTest` `COMPLETED`/`REDEEMED` is the most frequent, at
  ~1 in 5 on 2026-11-01). Each needs a forced pair or an in-memory probe. ⚠ `println` perturbs the
  timing (`tech-debt.md`). ⇒ For the `:system-test-utils` members, the stepped `TestSystem`
  (`testing.md` §12) is the forcing tool: a suspected interleaving becomes a sequence of
  `sync(…)` calls, run in both orders.

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
| `S3BlobStoreRealS3ContractTest` / `S3BlobStoreContractTest` (`:sync-relay-server`) | 18.7 s + 12.2 s → 7.5 s + 12.4 s | An S3 server (S3Mock or the Netty fake) started per **test**, not per class | ✅ **Done**: one lazily started server per test JVM, and a fresh bucket per store. Isolation is pinned by the contract's `aFreshStore_sharesNothingWithAnEarlierOne`, run by every backend, and the single start by `everyStoreInThisJvm_sharesOne…`. Same-module `--rerun`: 6.5 s → 3.9 s and 5.0 s → 3.9 s, with 5 more tests between them. ⚠ Under gate load the fake did not get faster: its time is its two 1 500-PUT tests (deliberate; one pins the batching ratio), not its server start. Its next cut is the contract's `deleteAll_spansMoreThanOneBatch`, which the fake runs beside a strictly stronger ratio test |
| `ViewerDwellMintTest` (`:photos-shared-ui`) | 9.5 s → 6.7–7.2 s alone; 17.1 s in the gate | `mainClock.advanceTimeBy` steps of 2–5 s around a 1 s dwell, ~1 160 frames in all | ✅ **Done**: every step is derived from `DWELL_MS` (now `internal`): `DWELL_MS + 500` past it, `DWELL_MS / 2` for a glance, a late subscriber at `DWELL_MS + 500`. Five policy breaks (no settle check, no dwell, no `take(1)`, a captured licence, a dwell timed from subscription) each still fail the case written for it. What is left is the first case's warm-up |
| `PhotoEditorScreenTest`, `CollectionsScreensTest`, `FeedScrubberTest` (`:photos-shared-ui`); `ContactsScreensTest` (`:contacts-shared-ui`) | 10–23 s each | Compose UI tests, several frames per assertion | Measure the frames before cutting. The same `advanceTimeBy` sizing rule applies. ⚠ `AboutSheetTest` and `ContactsScreensTest` spend ~6 s on their **first** case and under 1 s on the rest: per-class warm-up, not the case |
| `DuplicatesBoundedReadTest` (`:photos-shared-logic`); `SyncedFolderControllerTest.theSkipAsksTheRealmInBoundedReplies_andStillSkipsWhatTheRealmHolds` (`:drive-shared-ui`) | 8.1 s → 4.5 s; 3.7 s → 3.5 s | A large N, on purpose: reading the realm whole must overflow the reply budget, and Duplicates' groups must straddle hash pages | ✅ **Done**: N is derived from `RealmContentReads.REPLY_BYTE_BUDGET` over a 190 B hash-only row (1 380 files) and from `HASH_PAGE_SIZE` (groups at the page edges, three pages). A precondition asserts that the narrowest whole-realm read really overflows the budget. ⚠ Derive N from a size the test **measures**, never from a row-size constant alone: rows measure ~268 B (path + hash) and ~200 B (hash only), lighter than `HASH_ROW_BYTES` and the ~205 B in the docs, so an N taken from those lands under the budget, where an unbounded read passes |
| `WorkoutActivityRollupMaintainerTest.backfillOverALargeHistoryStaysCorrect` (`:health-service-proxy`) | 7.7 s | 10 000 saves, but no boundary to cross: the maintainer has no page or batch cap, the store is in-memory, and the case never calls the backfill its name promises | Left as it is: a smaller N has no boundary to be derived from. Whether a volume case earns 7.7 s is a separate decision |
| `AddsBeforeRemovesTest`, `FreedContentRepairTest`, `EdgesOnPushTest`, `TombstoneCascadeOnTheWireTest` (`:sync-service-proxy`) | 15.3 s → 0.8 s, 12.4 s → 0.25 s, 9.9 s → 0.10 s, 2.1 s → 0.09 s | **Not N**: `MockSyncRelay`'s simulated latency (`delay(500)` a push, tombstone or read) ran on a real clock, because each rig built `TestProxy` on its default `Dispatchers.Default` scope, so the drains it ran were off `runTest`'s scheduler | ✅ **Done**: each rig passes `coroutineScope = backgroundScope`, so the latency is virtual. A sweep of every `MockSyncRelay` user with the latency at 0 found no test that needs it. ⚠ A rig over `MockSyncRelay` belongs on the test's scope: on the default scope every relay call costs 500 ms of real time. ⚠ `aCascadeIsRecordedBeforeItsTombstoneCanBePushed` keeps its real dispatcher on purpose: it races a concurrent drain, and costs 0.02 s |
| `PairingHandshakeIntegrationTest` (`:control-shared`) | 8.8–9.2 s → 1.7–1.8 s | **Not N**: the same `MockSyncRelay` latency, plus `HostPairingMonitor`'s 2 s poll cadence, on a real clock that **cannot** be made virtual: `ProxyGateway.call` blocks internally, so the harness deadlocks under `runTest` | ✅ **Done**: the test binds `MockSyncRelay(…, simulateLatency = false)` and passes `HostPairingMonitor(…, pollIntervalMs = 50)`. Both defaults are unchanged. ⚠ Turning the latency off is the fallback for a harness that must run on real time; everywhere else, put the rig on the test's scope |
| `SqliteObjectStoreCommitFailureTest` (`:core-common`) | 10.9 s → 2.1 s (alone) | **Not N**: ten cases held the write lock from a second connection in a `Thread.sleep(1_000)` or `(2_000)` and joined it, though the store gives up in ~80 ms (20 ms `busy_timeout`, two 10 ms backoffs) | ✅ **Done**: the hold ends on an event — the failed `flush()`/`close()`, or the store's first traced retry — not a guessed time. The retried-and-lands case now retries **by construction**. One case keeps a timed hold (600 ms), because the wait on the lock is its subject. ⚠ Breaking the close teardown's `NonCancellable` is caught by **no** case, before or after this change: `a close cancelled while flushing still releases the lock` does not pin what it is named for |
| `HomeDeviceSystemTest` (`:hub`) | 12.9 s → 6.2 s alone (14.9 s in the gate) | **Not N**: 9.6 s of `delay` proving that something did **not** happen (a re-delivered command, a stale command, sub-quantum drift, a re-announce) — "wait several sync cycles, then assert nothing changed" | ✅ **Done**: each negative claim now follows a positive one, from `DeviceBridgeHost` probes beside `executedCommandCount`: `handledCommand(id)` (the gate's decision, for which `issuedAt`) and `handledState(deviceId)` / `hasPendingFlush()`. The rename check reads the hub's replica the moment `publishInventory` returns. Four product breaks (journal, stale window, quantum, clobber) fail the same case before and after. ⚠ A wait proves nothing about absence: it passes just as well when the command never arrived. ⚠ Not on the stepped `TestSystem` (`testing.md` §12): `HubRuntime` runs its own sync loop, outside it. `HomeDeviceControlSystemTest` (5.2 s alone) has no such waits and is left |
| `ThumbnailLadderParityTest` (`:file-service-proxy`) | 10.6 s → 5.1 s alone | Pixels, not N: the per-rung path decodes the original once per rung, and each upload runs twice | ✅ **Done**: each fixture is sized from the top rung's edge (`GetStoredThumbnail.XXLARGE_PX`) to the boundary it is about. Production branches only on "larger than this rung" and on each rung's cap. The 15 MP landscape is now 2 560 × 1 536 and the noise frame 2 112². Same declines, step-down and pixel margins (worst 1.62 of 4). ⚠ The stripes stay at 4 200: twice the rung is where a subsampled read would pick factor 2 and turn them black. `LadderFixtures` writes pixels in bulk, byte-identical, but the encode is most of a fixture's cost |

**One structural cost:** 287 `commonTest` classes run on two JVM targets, the module's JVM/desktop
target and `testAndroidHostTest`. The second copy costs **68 s**, 32 s of it in `:core-common`.
It is not pure duplication, because the host run reaches `androidMain` actuals. But most of
those classes touch no `expect` declaration. Running only the classes that do, or a tag, would
cut most of it. That is a decision, not a fix, because it narrows Android coverage.

⚠ Not counted: two Robolectric classes (`:control-shared-proxy`, `:inference-service-proxy`) went
red in the container because it could not download `android-all-instrumented`. That is an
environment limit, and they did not run.
