# Tenshu Platform: Testing Conventions

All tiers run on the JVM (Android targets use JVM-hosted tooling, not Instrumented tests). The
goal is a fast developer loop with no emulator or device in the critical path.

## 1. UI Tests (e.g., `HomeScreenTest`)

Compose UI tests driving a single "Content" composable (or a thin Screen wrapper) via
`runComposeUiTest`, with fake repositories / ViewModels wired through Koin.

* **Scope**: one screen or composable at a time. No real proxy or transport layer.
* **Dependency Injection**: `startKoin { modules(module { ... }) }` in `@Before`, `stopKoin()` in
  `@After`. Register fakes for every repository and `viewModel { ... }` for every ViewModel the
  screen resolves.
* **ViewModel Store**: provide `LocalViewModelStoreOwner` manually so Koin's `koinViewModel()` can
  scope the ViewModel for the test. Use a plain `object : ViewModelStoreOwner` with a fresh
  `ViewModelStore`.
* **Theme**: wrap the composable under test in `TenshuTheme { ... }` so tokens and typography
  match production. Do NOT use `PreviewBox` here — it is a preview-only harness.
* **Assertions**: prefer semantic finders (`hasText`, `onNodeWithText`, `useUnmergedTree = true`)
  over implementation-detail matchers. Use `waitUntil { ... }` for reactive/paged data so the
  test does not flake on emission timing.
* **Location**: `*-shared/src/desktopTest/kotlin/.../<feature>/<Screen>Test.kt`.

## 2. App Integration Tests (e.g., `HomeScreenIntegrationTest`)

End-to-end tests that spin up a real `TenshuClient` against a real in-process proxy. They verify
that client → proxy → client round-trips (notifications, reactive streams, operation results)
work across the full stack without a device.

* **Setup**: `startAppIntegrationKoin(clientId = "...")` bootstraps the client, proxy, database,
  and services exactly as the app does at runtime.
* **Scope**: cross-service flows — e.g., "creating a folder emits a `FolderAddedNotification` on
  the notification stream." NOT for asserting UI state; use a UI test for that.
* **Coroutines**: wrap the test body in `runTest { ... }` and use `async` to subscribe to streams
  *before* triggering the action, then `.first()` to wait for the expected emission.
* **Teardown**: `stopKoin()` in `@After`. Integration tests MUST leave no shared Koin state
  behind.
* **Location**: `*-shared/src/desktopTest/kotlin/.../integration/<Flow>IntegrationTest.kt` with
  shared helpers under `integration/utils/`.

## 3. Operation Tests (e.g., `CreateFolderOperationTest`)

Unit-level tests for a single `OperationImplementation` running against a `TestProxy` (real
`ExecutionContext`, in-memory database, no transport).

`TestProxy` (in `:core-test-utils`) is the test-side simulator for the proxy process — it owns
its own database, `SchemaRegistry`, and `ProxyGateway` and exposes an `executionContext` for
direct operation invocation. App-integration tests construct the equivalent of a "TestClient"
inline today via `startAppIntegrationKoin(...)` plus `InProcessTaskTransport`; extracting that
into a first-class `TestClient` is sized in [`docs/tech-debt.md`](tech-debt.md) §"`TestClient`
Counterpart Not Yet Introduced".

* **Setup**: `createFileProxy()` (or the analogous factory in the relevant `*-test-utils` module)
  builds a `TestProxy` with the service's schema registry pre-registered.
* **Invocation**: call `testProxy.executionContext.execute<T>(operationId, caller, params)` with
  the exact DTO parameters the operation expects. Wrap raw values with
  `toTString(testProxy.tContext)` / equivalents so `TContext` is threaded correctly.
* **Assertions**: check both the `TResult.Success` / `TResult.Failure` variant *and* the database
  side-effect (e.g., read back via `executionContext.getArrayData(ref)`). Never assert only on
  the return value — the operation must also leave the database in the expected state.
* **Error Cases**: use typed errors (`is AlreadyExistsError`). Do not match error strings.
* **Location**: `*-service-proxy/src/commonTest/kotlin/.../<Operation>Test.kt`.

## 4. Synthetic Test Data & Registries

Every module that defines a schema contributes a `create[Module]TestRegistry()` helper in its
`*-test-utils` module. These helpers compose: `createCoreTestRegistry()` at the root, then each
feature's common + client registrations layered on top.

* **Convention**: a feature-service `*-test-utils` module MUST expose both a
  `create[Feature]ServiceCommonTestRegistry()` (schema + DTO factories only — used by proxy tests)
  and a `create[Feature]TestRegistry()` (adds RO factories — used by client tests).
* **Composition**: higher-level registries `.apply { registerConfig(lowerModule) }` rather than
  rebuilding. The central `createCoreTestRegistry()` in `:core-test-utils` is the root.
* **Synthetic Data Factories**: when a test needs a realistic `Entity<File>` or paged list, prefer
  adding a factory function to `*-test-utils` over constructing composites inline. This keeps
  tests readable and data shapes consistent across the suite.
* **No Production Code In Test Utils**: `*-test-utils` modules MUST NOT ship production schema
  registrations; they compose the production module configuration and add test-only factories.

## 5. General Rules

* **JVM First**: every test MUST run on the JVM (`commonTest` or `desktopTest`). Android-only
  (`androidTest`) tests are prohibited unless the code under test is genuinely Android-specific
  (e.g., a platform delegate implementation).
* **No Real Network, No Real Filesystem**: integration tests use in-process transport; operation
  tests use in-memory database. If a test needs disk or network, it is a different kind of test
  and belongs in a separate suite outside CI's default path.
* **Deterministic**: no `Thread.sleep`, no wall-clock timeouts. Use `runTest` + virtual time, or
  Compose's `waitUntil { ... }` with a semantic predicate. ⚠ Stated absolutely here, and **39 test
  files break it** — 23 of them for a good reason (a real proxy or socket, where virtual time is
  the wrong answer). §10 carries the discriminator, and what to do in the cases the flat rule
  cannot cover.
* **Isolated**: every test MUST `stopKoin()` in `@After` and create fresh mocks in `@Before`.
  Shared mutable state between tests is a bug.
* **Typed Errors Over Strings**: assert with `is ErrorType` sealed-class checks, never with error
  message string matching.

## 6. The Android smoke gate

Kotlin unit tests and `compileDebugKotlin` cannot see DI wiring, manifest entries or startup
order, so a PR can be entirely green and still install an app that dies on launch.
`scripts/android-smoke.sh` closes that gap: it boots the emulator if none is attached, builds
and installs a set of first-party apps, launches each one, and fails on any crash or ANR.
House Rule #8 makes it mandatory for any PR touching `androidMain`, `:control`, or
`:drive-android`. The build env has a working SDK + emulator (`~/Library/Android/sdk`, AVD
`Medium_Phone`); a worktree needs no `local.properties`, the script exports `ANDROID_HOME`
itself.

### Which apps the gate builds, and why not all of them

The built set is Control + Drive + Maps + Home + Calendar + Timeline + Mia + Photos. ⚠⚠ **Add a
new first-party app the day it exists.** Four apps have already taught this lesson, each a
different half of it:

* **Calendar** was absent, which is why CA-1…CA-4 shipped with Calendar never wired into
  Control at all — unit-green code that could not have worked on a device, found four phases
  later.
* **Timeline** was added 2026-08-10 because it was *launched but not built*, so every PR that
  changed it was gated against whatever APK happened to already be on the device.
* **Mia** was added 2026-08-11 with MQ-1, the slice that gave it a client stack: a Binder
  binding, a manifest `<queries>` entry and a `ClientCallbackService` — none of which a unit
  test can exercise, and all of which fail *silently*.
* **Photos** was added in the commit that created it (`photos-app.md` PH-1) — the Mia
  precedent applied on day one rather than in hindsight. ⚠ It also ships alongside a
  `FileService` version bump, which makes it the app most likely to be caught out of lockstep,
  and a Photos whose client is mis-wired shows an **empty grid** rather than crashing: exactly
  the silent failure this gate exists to catch.

⚠⚠ **But at least one first-party app must always stay un-built** (Fit and Contacts today).
An app the gate reinstalls is current by construction and can never be caught out of step, so
the un-built ones are the only apps whose lockstep and announce assertions carry any
information. Building an app gates *changes to it*; not building one detects *skew*. Add to
the built set when an app's own changes need gating, never sooner.

⚠ The canaries are whichever un-built apps are actually *installed on that device* — a clean
emulator often has only Fit, so the margin is thinner than the module list suggests.

⚠⚠ **A canary from before a re-cut fails every run until it is refreshed — and that failure
is the canary working, not your PR breaking.** After a wipe-everything re-cut (the release
reset that restarted every version at 1, the pre-Play re-cut of the announce envelope in `core-schema-evolution.md`
Appendix A.4), a canary installed earlier reports a pre-cut storage format (`v22 != source v1`),
announces no session, and its announce, in the *old* envelope, comes back from the new Control
as `tenshu/app/service/announceSession -> tenshu/core/error/internal`. A pre-cut client cannot
parse the new reply, and the new proxy cannot parse the old request, so it surfaces as an
internal error rather than the skew banner. Measured on 2026-10-28 for the announce-envelope re-cut.
- **Shared `Medium_Phone` emulator:** Contacts and Fit, installed 2026-10-22, produced exactly these three FAILs. The failing announce came from Contacts' pid.
- **Private AVD (`wsc_private`, own adb server on port 5041):** Control from the branch, plus Fit and Contacts built from the same branch. Both canaries reported `storageFormatVersion=1`, logged `admitted=true`, and produced no `IPC Client -> Failure`.
- **Name the caller before blaming the change.** `adb logcat -d | grep "IPC Client -> Failure"`
  gives the pid; `grep "Process <pid> created for"` gives the package.
- **Refresh shared canaries from `master`, never from a branch.** Otherwise they stop being
  canaries for everyone else's PR.
- ⚠ **Do not "fix" it by adding the canary to the built set.** That leaves the gate with no
  canaries at all.

### What it asserts beyond "did not crash"

**Schema lockstep**: every installed app reports its `TENSHU_STORAGE_FORMAT_VERSION` as a
startup beacon, and the gate checks them against the current source. A stale Control serving a
newer client's schema then fails loudly, and named here, instead of surfacing as a cryptic
`IPC Client -> Failure … error/internal` on a peer-broken read (House Rule #9). ⚠ Re-run the
gate on a real device after installing only *some* apps — that is exactly how lockstep gets
broken.

The gate is smoke, not functional. Truly device- or network-dependent flows (real uploads,
pairing, metered-network deferral) still need a maintainer's own device.

## 7a. The quick gate — `scripts/run-gate.sh --quick`

The full gate runs every test task in the repository (~145 of them, ~2,200 classes) plus the JS
tests and both compile lanes. That is the right check before a merge of shared code and before a
public sync, and too much for every round of a review loop.

`--quick` runs the same lines with the same guarantees (results cleared, every requested task must
have run, failures named) for the modules the change touches **and every module that depends on
them**. The change is the working tree against the merge-base with `origin/master`
(`TENSHU_GATE_BASE=<ref>` to change it); the dependency graph comes from the build itself
(`-Ptenshu.projectGraph`, root `build.gradle.kts`), through every configuration, tests included.
`scripts/lib/affected_modules.py` does the mapping.

| The change | What runs |
|---|---|
| Files in one or more modules | their tests, JS tests and compile lanes, and those of every dependent |
| A build file, `gradle/`, `config/`, the gate's own scripts, or a file in no module | the **full** gate, automatically |
| Only docs, `scripts/`, `store/`, `.github/` or `web/` | nothing (`web/` names the app's `pnpm test` to run instead) |

Measured on a one-line `contact-service-common` change: 43 test tasks and 627 classes, against 145
and 2,222 for the full gate.

⚠⚠ **It follows the code graph only.** A test that reads another module's files at runtime is not
followed, so a change to shared code (`:core`, `:core-common`, `:core-client`, `:core-proxy`,
`:core-test-utils`, `:ui-core`) prints a reminder that it owes the full gate before it merges. The
quick gate never writes the module-loss ledger (`build/gate-modules.txt`): it runs a subset by
design, and writing that subset would blind the next full run's comparison.

## 7. Keeping a gate run — `scripts/gate-run.sh`

`./gradlew desktopTest jvmTest test` tells you whether *this* run was green and then destroys
the evidence. `docs/tech-debt.md` § "The full gate is flaky under parallel load" is what that
costs: ~20 rotating victims, two of them recorded with their failure message lost to an
overwrite, and a set of central claims drawn from 3 and 16 runs that later turned out to be
wrong at that sample size.

```bash
scripts/gate-run.sh                        # one gate, harvested into .tenshu-gate/
scripts/gate-run.sh --repeat 10 --label pre-fix
scripts/gate-run.sh -- :control-shared:desktopTest --rerun    # a module only
scripts/gate-report.py                     # rates, victims, co-occurrence
scripts/gate-report.py --test SomeTest     # every occurrence, message + cause chain
scripts/gate-report.py --compare pre-fix post-fix
```

It is a wrapper, not a new gate: same Gradle invocation, plus the four things that make a run
still be worth something tomorrow. Each closes a trap `tech-debt.md` records having been hit:

1. **Stale XML is deleted before the run.** Scanning `build/test-results/**` otherwise reads
   *history* — a deliberately-failed run from an hour earlier is still on disk.
2. **`--rerun` is the default, and the executed-task count is recorded.** A task that is
   `UP-TO-DATE` writes no XML, so a class can be reported green having never run; a run that
   executed nothing is flagged, loudly, rather than passing for a fast green one.
3. **Gradle's own exit status is read** (`PIPESTATUS`, cross-checked against `BUILD FAILED` in
   the log). `./gradlew … ; echo "EXIT=$?"` reports the *echo's* status.
4. **Failures are stored with their full cause chain**, which is the half `showCauses` exists
   to print — an `UncaughtExceptionsBeforeTest` names the actual culprit only there.

⚠ The history is machine-local and gitignored: a flake rate is a property of the box that
produced it, and load is the variable half these entries turn on.

⚠⚠ **A rate is corroboration, never the evidence that a race is fixed.** `--compare` prints
the Fisher exact p next to the two rates precisely so a weak claim looks weak — at a ~12 %
base rate, 16 clean runs after a fix give p ≈ 0.26. What settles a fix in this repo is the
**forced-interleaving pair**: a forced deschedule that fails the test deterministically without
the fix and passes with it (`Zigbee2MqttBridgeTest`, `UnixSocketReconnectTest` and
`FilesPagingIntegrationTest` were all closed that way).

### 7.1 One gate at a time on a machine — `scripts/lib/gate-lock.sh`

`run-gate.sh`, `gate-run.sh`, `android-smoke.sh` and `ios-smoke.sh` (when it builds) each take one machine-wide
lock (`~/.gradle/tenshu-gate.lock`) before doing any work, so a second run **queues** behind the
first and says so. One session per worktree means one gate per session, and three at once is
more RAM than the box has: it swaps, the count and timing assertions fail for reasons no diff
explains, and the harness kills whichever run it reaches first. Queued gates are both faster in
total and truer.

- ⚠⚠ The lock is taken by re-executing the script under `lockf` (`flock -o` on Linux), never by
  holding a descriptor in the script: the Gradle daemon a run leaves behind inherits open
  descriptors and would hold the lock for its 30-minute idle timeout.
- The kernel owns it, so a killed run releases it; there is no stale lock to clear.
- `TENSHU_GATE_NO_LOCK=1` runs alongside the holder and prints that it did.
- ⚠ A worktree that predates the file does not queue, and neither does a bare
  `./gradlew :module:jvmTest` — a targeted run is the cheap case this deliberately leaves alone.

### 7.2 A test JVM never runs in your home

The lock above does not cover targeted runs, so isolation is per JVM instead: every desktop/JVM
`Test` task (root `build.gradle.kts`) starts its JVM with `user.home` at a fresh
`<module>/build/tmp/<task>/isolated-home/run-*/home`, so `~/.tenshu/<appId>`, window state and the
dev socket directory are private to that run (one per task execution). Runs older than an hour
are swept, and at most 3 earlier runs are kept beside the current one.

- ⚠⚠ The real home rides along as `tenshu.test.realHome`, and `DesktopAppPaths.root()` **throws**
  if it would resolve under `<realHome>/.tenshu` or `<realHome>/Library` — a test that reaches the
  real data roots fails where it does it, not as a flake in someone else's run.
  `TestHomeIsolationTest` (`:core-common`) goes red if the build block stops applying.
- ⚠ Android host tests (`*UnitTest`, `testAndroidHostTest`) are exempt: they cannot reach
  `desktopMain`, and Robolectric caches under `~/.m2` via `user.home`. Skiko's natives keep the real
  `~/.skiko` (`skiko.data.path`) — a cache, not app state.
- ⚠ A test that needs a home-relative path must read `user.home`, never hard-code `~`.

## 8. Cross-platform pairing — what is covered, and the shape of what is missing

Scoped 2026-08-26, after a live iPhone-host ↔ Android-joiner pairing failed with the joiner's
decrypt failing its MAC check while **both screens showed the same SAS** (`tech-debt.md` → *"The
joiner's decrypt fails its MAC check"*).

### What already exists

| layer | test | runs where |
|---|---|---|
| primitives | `LibsodiumCryptoBackendsTest` — X25519 shared secret, Ed25519, Argon2id, and the iOS backend decrypting a **JVM-produced** AEAD blob, all against JVM vectors | `iosSimulatorArm64Test`, now via `./gradlew iosTestCheck` |
| flow | `PairingVmFlowSystemTest` — two parties, real proxies, in-process relay, real `PairingWizardViewModel`s, asserting each side's user-visible states | `:system-test-utils` **desktopTest — both parties JVM** |

The primitives are green and interoperate. The flow test is real but **monoplatform**, so nothing
covers "an iOS proxy and a JVM proxy complete a handshake together".

### ⚠⚠ The blocker is a policy line, not missing test code

`build.gradle.kts` disables iOS tests for **every `-proxy` and `-test-utils` module**, on the
reasoning that *"proxy-side code does NOT run in the iOS app under proxy-mediated EPC"*. That was
true when it was written and **is no longer**: under route 1 the whole proxy runs on the iOS device.
The exclusion covers `PairingCompletion`, `PairingAdmission`, `PairingKeyBlob` **and
`:system-test-utils` itself** — so the pairing flow is untestable on K/N by construction.

### Three options, in the order worth doing them

**C — handshake conformance vectors (cheap, do first).** The vector-test idea one layer up: fix
`(hostPriv, joinerPriv)`, assert the derived SAS, the packed `PairingKeyBlob` bytes, and a
pack→encrypt→decrypt→unpack round trip. Put it in `commonTest` so **every** platform runs the same
vectors. Covers exactly the layer this bug lives in — `derivePairingSas`, `PairingKeyBlob`, and the
shared-secret→blob binding — costs one file, needs no policy change, and rides the `iosTestCheck`
task that now exists. ⚠ It would NOT have caught the live bug (see D), but it is what stops the
*next* pack/derive divergence.

**A — run the two-party flow test on K/N (the real system test).** Lift the `-proxy`/`-test-utils`
iOS-test exclusion for the sync modules + `:system-test-utils`, and compile
`PairingVmFlowSystemTest` for `iosSimulatorArm64` with **both** parties on K/N. Catches the
divergence class that has actually bitten this project — dispatchers, `Main.immediate`
(`viewModelScope` on K/N), coroutine scoping, K/N null handling — none of which the vectors can see.
⚠ Cost is real: linking test binaries is the heaviest K/N step and the exclusion exists partly for
`Java heap space` OOMs on `linkDebugTest…`. Scope the lift to the modules the pairing flow needs
rather than all ~19.

**B — a true two-process interop harness (JVM host ↔ K/N joiner).** The only shape that proves
cross-platform *interop* at the flow level, and it cannot be a unit test: two runtimes plus a relay
means a script, not a test task. ⚠ Highest fidelity, highest flake surface. Not worth it while A is
undone.

### D — and the guard the live bug actually needs is same-platform

⚠⚠ **No cross-platform test would have caught it**, because nothing about it is platform-specific.
The relay's pairing-response mailbox is `Map<pairingSecret, blob>` (`SyncMailbox`), removed **only
on ack**, and the joiner acks **only after a successful install**. The joiner's handshake is *also*
keyed by `pairingSecret` alone, and `InitiatePairing` mints a **fresh ephemeral keypair on every
call**. Nothing binds the stored blob to the keypair it was encrypted for, and the SAS the UI shows
is a **snapshot from the first initiate** while the decrypt uses whatever keypair the handshake
holds **now**.

So a second `InitiatePairing` under one secret — a double-tapped Continue, a back-and-retry — or a
stale un-acked blob from a failed attempt reproduces the exact observed signature: **matching SAS,
unopenable blob, and a failure that repeats identically on every retry**. That is a JVM-only unit
test in `:sync-service-proxy`, and it is the first thing to write.

## 9. The web lanes

⚠⚠ Until 2026-08-28 this document did not mention the web at all — no `web/`, no `apex`, no
`pnpm`. That absence was not neutral: it is where the inconsistency lived, and the 0 B family
(§9.3) shipped for two months behind suites that were green the whole time.

Web has no single gate the way Android has `scripts/android-smoke.sh`. It has **four lanes**,
and they are not interchangeable. `scripts/run-web-gate.sh` runs them and refuses to report
success unless test cases actually executed — the same discipline, and the same reason, as
`scripts/run-gate.sh` (a re-run of `:tenshu-sdk-js:jsTest` reports BUILD SUCCESSFUL in 12 s
having executed **zero** tests).

### 9.1 The ladder, and how it maps to §1–§3

| native (§) | web lane | runs in | covers |
|---|---|---|---|
| UI tests (§1) | `pnpm -F {apex,drive} test` | vitest + jsdom | components, rendering, interaction |
| App integration (§2) | `:tenshu-sdk-js:jsTest` | **headless Chrome, real Kotlin runtime** | the in-process runtime end-to-end |
| Operation tests (§3) | *boundary-contract tests* (§9.3) | same lane | what an SDK op **emits** |
| — | `pnpm -F {apex,drive} build` | tsc + vite | TS contract drift, which no test sees |

⭐ The second lane is the valuable one and the least like anything native has: it drives the
**real** runtime — IndexedDB, crypto, schema, sync wiring — in a real browser. A local realm
("Just try it locally") needs no relay and no second device, which makes far more reproducible
than it looks.

### 9.2 What a green web gate does NOT mean

⚠⚠ **No lane covers browser policy.** Background-tab throttling (§1.2b of
[`drive-web-parity.md`](proposals/drive-web-parity.md)) and storage eviction (§1.2d) are Chrome
behaviours; jsdom has neither and headless Chrome will not freeze a tab for you.
⚠⚠ **No lane covers cross-device sync**, because no lane has a relay or a second device. W1's
content plane passed every gate and is *still* unverified on a device.
⚠ **No lane covers build provenance.** A preview built without `VITE_TENSHU_RELAY_URL` points the
SDK at the production relay, which CORS-refuses a localhost origin — code perfectly correct,
app completely broken. Check what `configure()` targets in the built bundle after any local build.

### 9.3 ⚠⚠ Boundary-contract tests — the assertion that was missing

The whole 0 B family — no size, no date, folders reading "0 items", `details()` returning `null`
— shipped from Phase C to 2026-08-28 with every suite green, because **nothing asserted the shape
of a listing row**. Both sides compiled: the Kotlin schema walker emitted whatever
`composite.fields` happened to hold, and `web/sdk/src/files.ts` separately declared what the UI
hoped for. Nothing compared them.

The rule: **every SDK op that returns data owes a test asserting the keys its published TS type
declares** — not the mapper's internals, so a rewrite may change how a row is built but not what
it carries. `JsRuntimeTest.list_rowCarriesTheFullPublishedContract` is the worked example.

⚠ You cannot assert a contract nobody wrote down, which is the practical argument for typed
mappers over the walker ([`drive-web-parity.md`](proposals/drive-web-parity.md) §1.2f): a typed
return is a declaration a test can point at. `FileSystem` has one now; Contacts, Timeline,
SharedFiles and Membership do not.

### 9.4 ⚠ Prove a guard by deleting the fix

House Rule #7 says tests over screenshots; web adds a corollary, learned twice in one day.

A wiring test written for W1 **could not fail** — deleting the line it existed to protect left
the suite green 60/60, because the observable was private to another module. It says so in its
own comment now. The contract test in §9.3 was checked the same way and does fail: injecting
"size is always 0" turns it red naming the expected and actual bytes.

So: after writing a guard, **break the thing it guards and watch it go red**. A test that cannot
fail is worse than no test, because it reads as coverage.

### 9.4.1 ⚠ A red lane with every test green is a timer that outlived jsdom

Vitest fails a run on an unhandled error even when every test passed. In the web lanes, the cause
so far has been a timer still pending when the file ends: Vitest tears jsdom down, Node's own
globals come back, and the timer's callback touches a DOM it no longer owns. The case on 2026-10-28
was Radix FocusScope's unmount `setTimeout(0)` (shared `Dialog`/`Menu`), which threw "parameter 1 is
not of type 'Event'". It is rare because Vitest only reports a throw that lands between teardown and
the worker's `stopped` message. So looping the file is a poor reproduction: 140 unfixed runs
(file and full suite) never reported it, even though a trace showed the throw happening.

So `@tenshu/app-kit/testing/setup` waits one real tick after `cleanup()`, and
`app-kit/src/testing/setup.test.tsx` guards that wait deterministically. If a lane goes red this
way, look for a timer the component or library starts and never clears, and wait for it in the
setup. **Don't suppress unhandled errors**: that hides the real-leak case too.

### 9.5 What is missing, in the order worth doing it

1. **Boundary-contract tests for the remaining SDK surfaces** — cheap, and the 0 B family is the
   evidence for what their absence costs.
2. **A JS-reader vs native-mapper conformance test** — catches the *next* divergence rather than
   this one. Same schema, two readers, and only one of them is exercised by anything today.
3. **A web smoke gate** — the `android-smoke.sh` analogue: headless Chrome, local realm,
   onboard → upload → list → open, assert no uncaught exception. Most of the driver already
   exists as scratch tooling from the 2026-08-28 session; nothing about it needs a relay.
4. **A device pass** for anything crossing an origin or a device — the only instrument for W1,
   W8, W10 and W13.

⚠⚠ And the caveat that outranks all four: **CI has been dark since 2026-06-30** (see the
tech-debt entry). Every gate above runs only when a human chooses to run it, so the strongest
suite here still gates nothing automatically.

## 10. Virtual time is the default; real time is a decision you justify

> ⚠ Every known flake, sorted by cause with the harness change for each, is in
> [`testing-flakes.md`](testing-flakes.md). A new real-time bound or sleep in a test fails
> `scripts/check-test-wallclock.py` unless it carries a `wall-clock:` reason.

§5 states this flatly — *"no `Thread.sleep`, no wall-clock timeouts"* — and 39 test files break
it. Most of those are not sloppiness; a flat rule cannot express the case where the real scheduler
**is** the behaviour under test. This section is the discriminator.

**The rule.** Prefer `runTest` + `advanceUntilIdle()`. Reach for real time — `runBlocking`, real
dispatchers, a poll with a timeout — only when the thing under test is the real scheduler: a real
in-process proxy, a Unix socket, a live relay, a genuine concurrency race.

⚠⚠ **The discriminator is not `runTest` vs `runBlocking`. It is whether every dispatcher on the
path is injectable.** Both mistakes shipped here in the same week:

* `TenshuPagingPresenterTest` bridged to `Dispatchers.Default` on the stated grounds that *"the
  Pager fetcher runs its loads on a real dispatcher that the `runTest` virtual clock does not
  control"*. False: the presenter takes both its scope and its `mainContext` as parameters and
  `Pager.flow` loads on its collector's context, so one `advanceUntilIdle()` settles the whole
  path. The bridge is what made a load-state read a race, and it lost on a gate.
* `ModelDownloaderTest` went the other way — moving the class onto the test scheduler measured
  **worse** (0/8 → 5/8 red), because Ktor's `MockEngine` delivers the response body on its own
  dispatchers whatever the caller passes. The download never became single-threaded; it just
  stopped yielding to the collector.

**Check whether the component actually forces you onto real threads before bridging to them. A
KDoc asserting that it does is not evidence** — that one stood unchallenged long enough to cost a
gate failure and then a workaround.

### 10.1 ⚠ A long timeout is a ceiling, not a cost — measure before sweeping

Counting `withTimeout(60_000)` literals overstates the problem badly. Measured 2026-09-10:

| | |
|---|---|
| test files with a `withTimeout(≥1000)` or `Thread.sleep` | **39** |
| slowest single case across `:drive-shared-ui`, `:core-common`, `:core-paging` | **3.2 s** |
| `FilesPagingRoundTripTest` — `withTimeout(60_000)` around a 5 s inner poll | **0.187 s** |
| module totals | 55.8 s / 429 cases · 65.2 s / 830 cases · 3.7 s / 43 cases |

**The reason to remove a real-time wait is flakiness, not speed.** A 5 s poll that is ample on an
idle box is what loses at gate scale — which is exactly what `pageToAll`'s own KDoc records
happening: *"green in isolation, red in the suite"*.

### 10.2 How the 39 split, and what each half needs

* **6** are `runTest` that bridged virtual time away. ⚠⚠ **Worked 2026-09-10, and "6 straight
  conversions" was wrong — only ONE was.** The `runTest`-vs-`runBlocking` split is a cheap proxy
  for the real discriminator and it over-promises; each file still needs reading:
  * ✅ `CachedInRefreshContractTest` — a straight conversion, and it had inherited its false
    premise verbatim from `TenshuPagingPresenterTest`. ⭐ Now *stronger*: removing `refresh()`
    fails it with `expected:<[1, 2, 3, 4]> but was:<[1, 2, 3]>` instead of a 5 s timeout carrying
    no diff.
  * ✅ `PlaceDetailOfflineOfferTest` (a named flake in `tech-debt.md`) — **needed a production
    change first**: `PlaceDetailViewModel` hardcoded three `withContext(Dispatchers.Default)` hops
    in `init`, so nothing was injectable. Making that context a constructor parameter is the
    remedy, and only then is the test convertible.
  * ⏸ `UploadOverwriteRecorderWiringTest`, `InProcessProxyReactiveStreamTest`,
    `FitPipelineIntegrationTest`, `TimelinePipelineIntegrationTest` — **real by construction**, all
    four over a real `TestProxy`/`InProcessProxy`. `InProcessProxyReactiveStreamTest`'s own KDoc
    says the proxy's live delivery *is* the subject. These are the §10 exception working as
    intended; leave them.
* **23** are `runBlocking` by construction — a real proxy or socket, where virtual time would be
  the *wrong* answer. The fix there is a **happens-before instead of a poll**.
* **4** are both.

⚠ So the useful sizing is not "39 files to sweep" but "read 39 files, expect to convert a handful".
The census finds candidates; only the code says which are.

### 10.3 The pattern underneath all of it

Every one is the same defect in a different hat: **a test asserting on state produced
concurrently, at a moment it has no right to name.** Three remedies, in order of preference:

1. **Delete the concurrency** — put the path on the test scheduler and `advanceUntilIdle()`.
   Afterwards nothing is left to run, so reading `.value` is a fact rather than a sample.
2. **Take a happens-before from the code** — block the producer where it must be suspended, then
   read synchronously. ⚠ **One gate per producer, never one shared release**: a single gate stops
   holding after the first sample and the rest races, which measured `expected:<3072> but
   was:<3584>` — the first artifact's total plus one chunk of the next.
3. **Await the predicate** — `first { … }` rather than `.value`. Weakest of the three: it still
   depends on the value being deliverable, but it beats sampling (`TenshuPagingPresenterTest` tried this first, then
   replaced it with remedy 1).

⚠ **In a Compose test, remedy 2 is an `IdlingResource` on the producer.** `waitForIdle()` sees only
the composition's own work: not the proxy's threads, and not the Swing EDT, where `viewModelScope`
and paging-compose's presenter run. `RepositoryIdling` (Drive's `integration/utils`) makes it wait
for both, so an assertion follows an action with no `waitUntil` (`testing-flakes.md` §4). ⚠⚠ Only a
`DesktopComposeUiTest` polls one; on the plain v2 `runComposeUiTest`, `registerIdlingResource`
returns `false` and nothing is polled.

⚠⚠ **Weakening an assertion is not on that list.** A conflating `StateFlow` can drop everything
after any given point, so no threshold is guaranteed observable — *only not sampling can fix a
sampling race* (in the inference download-progress test, "> the largest single artifact" replaced "== the total" and still
failed).

---

## 11. Owning a test's view models — `:viewmodel-test-utils`

`OwnedViewModels` owns the lifetime of every view model and hand-built `CoroutineScope` a test
constructs, so each is **cancelled and joined** before the test ends. Use it in any test that
builds a `ViewModel`.

```kotlin
vmTest { vms ->                                  // runTest + own + join + guard
    val vm = vms.own(MyViewModel(FakeRepo()))
    …
}
```

| entry point | for | lives in |
|---|---|---|
| `vmTest` | a `runTest` body — the default | `commonMain` |
| `vmBlocking` | a `runBlocking` integration test over a real proxy | `blockingMain` |
| `closeAndResetMain()` | a class that installs its own `Main` — joins, resets, then asserts | `blockingMain` |
| `composeVmTest` | a `runComposeUiTest` body | **the UI module**, not here — one line over `withOwnedViewModels` |

`own(…)` takes a view model, `ownScope(…)` a hand-built scope, `ownStore(…)` the `ViewModelStore`
a `koinViewModel()` composition resolves into.

### 11.1 Why this exists — the failure never names its own cause

A view model launches its `init` work on `viewModelScope`, which is `Main.immediate`-backed. A test
that constructs one and returns leaves that scope alive; `Dispatchers.resetMain()` then swaps `Main`
out from under its parked collectors. Whatever resumes one next lands on **whatever test runs
next**, as one of:

* `IllegalStateException: Dispatchers.Main is used concurrently with setting it` — thrown by the
  *next* class's `setMain`/`resetMain` (kotlinx-coroutines-test's `NonConcurrentlyModifiable`).
  Two classes fail at once: the reader's stored exception at the following `set`, the writer's at
  its own.
* `UncaughtExceptionsBeforeTest` — an exception escaping a `StandaloneCoroutine` on the dead scope,
  replayed into the next `runTest` by the JVM-global `ExceptionCollector`.
* `IllegalStateException: Dispatchers.Main was accessed when the platform dispatcher was absent`
  — on a `Dispatchers.Default` worker, when a `withContext` hop unwinds after the reset. The one
  maps saw: the thirteenth victim in the rotating-flake population, filed for months against a test
  that was only ever the reporter.

⚠⚠ All three name a **reporter, never the leaker** — which is why a sweep covers a whole module
rather than the tests that were blamed. In maps the leak was in a different class than the one
reporting it.

### 11.2 ⚠⚠ What the guard catches, and what it does not

`assertNothingActive()` is narrower than three copies of this fixture used to claim. Each row is a
forced pair, measured 2026-09-11 on `:photos-shared-ui` (112 tests) and now pinned by
`OwnedViewModelsGuardTest`:

| forced pair | result | |
|---|---|---|
| `closeAll()` made a no-op | 24 / 24 owning tests **red**, deterministically | ✅ |
| `cancelAndJoin` → bare `cancel` | 112 / 112 green | ❌ |
| a view model built without `own(…)` | 112 / 112 green | ❌ |

So it catches **a close that did not happen** — a teardown that stopped being called, or a
`closeAll()` that threw partway — and neither of the other two. A cancelled `Job` reads
`isActive == false` at once while its collectors are still unwinding, and the guard only ever sees
what it was handed.

⚠ The join is pinned instead by `aJoinWaitsForTheCollectorToFinishUnwinding`, whose body takes real
time to unwind: it is the only test that goes red on the bare-`cancel` swap, and the only pair
`cancelAndJoin` has ever had. The `ownStore(…)` case is the one exception to the third row — a
store is enumerated at close time, so whatever a composition put in it is found.

### 11.4 ⚠⚠ On Kotlin/Native the leak never surfaces as an error

The three reporters in §11.1 are the **JVM's**. On desktop `Main` is a draining Swing EDT, so a model
left collecting after `resetMain()` keeps working and nothing fails at all. On K/N the real `Main` is
the GCD main queue, which a test binary never drains: a leaked model's next resume parks there for
the rest of the binary. That is how a Contacts iOS test binary wrote its results and then never
exited (`docs/tech-debt.md`, *"Contacts iOS test binary never exits"*). So the `tenshu-sdk-ios-*`
tests follow §11 too, by the same three shapes:

* `vmTest(dispatcher)` where the class installs a test dispatcher as `Main` — most surface tests;
* `OwnedViewModels` + `closeAll()` **before** `resetMain()` in a `runBlocking` test over a live runtime;
* `withOwnedViewModels` where no `Main` is installed — ⚠ only for a model that launches nothing at
  construction, since joining one that did waits on the queue nobody drains.

⚠ An iOS test reaches most models through a **surface factory** (`DriveMutationSurface.rename(…)`),
not a constructor. `scripts/check-owned-view-models.py` reads the factory names from `iosMain`, but
cannot see a model inside a holder (`MoveBound.viewModel`, `DriveReadSurface.Bound.selection`) —
own each one the holder exposes.

### 11.3 Why the module exists, and why Compose is not in it

The class lived as three hand-copied files (maps, drive, photos) until the same
wrong claim in its KDoc had to be corrected in all three on one day, which is what bought
the extraction.

* ⚠ **Compose stays out.** `composeVmTest` needs `runComposeUiTest`, and that would put Compose on
  `:maps-shared-logic`, which uses the fixture *because* it is Compose-free. The owner, the join
  and the guard are shared; the UI builder each module tests through is not.
* ⚠ **`runBlocking` cannot live in `commonMain`**, whatever targets a module declares —
  kotlinx-coroutines declares it in its own concurrent (jvm + native) source set, and a common
  source set sees only the library's common API. Hence `blockingMain`. `:core-test-utils` carries
  the same intermediate under the name `nonJsMain`.
* It is the one `-test-utils` module with tests of its own. Its whole value is one assertion, three
  copies of it disagreed about what that assertion catches, and every consumer inherits whatever it
  says.

---

## 12. Stepped system tests — the test decides which device syncs, and when

A `:system-test-utils` `TestSystem` runs real devices against an in-process relay. It has two modes.

| | **Polled** (default) | **Stepped** (`TestSystem.builder().stepped()`) |
|---|---|---|
| sync loops | every device's `SyncScheduler` and `ScheduleDrainLoop` run at `pollingIntervalMs` | never started (`initSyncService(startBackgroundLoops = false)`) |
| a device syncs | whenever its loop ticks | only when the test calls `sync` / `syncUntil` / `syncUntilQuiet` |
| a two-device race | whichever loop wins | an order the test writes |
| a wait is bounded by | a wall-clock timeout (`advanceUntil`) | a **count of rounds** |

```kotlin
val system = TestSystem.builder().stepped().build()
val phone = system.client("phone", TestClientRole.RealmHost)
val tablet = system.client("tablet", TestClientRole.JoiningDevice)
system.pair(host = phone, joiner = tablet)             // the shared handshake; works in both modes

phoneRepo.save(…)
system.sync(phone, tablet)                             // phone pushes, THEN tablet pulls
system.syncUntil("the tablet holds the row") { rowOn(tablet) != null }
system.syncUntilQuiet()                                // "and it stays that way"
```

* **`sync(a, b, …)`**: one tick on each device, in that order. Each tick first waits for the
  device's queued saves to become blocks (`SyncBlockEmitter.awaitEmitIdle`), because a save hands
  its block to an emit channel and returns. Without the barrier a tick can run before the block
  exists and push nothing.
* **`syncUntil(what) { … }`**: rounds over every device until the predicate holds. It fails after
  `DEFAULT_MAX_ROUNDS` (25) with `what`. A slow machine cannot reach that bound, so a red means the
  state was never reached by syncing.
* **`syncUntilQuiet()`**: rounds until **two in a row** move nothing. "Nothing moved" means the
  relay accepted no block or tombstone (`SyncMailbox.lastArrivalSeq`) and no tick left more to pull
  (`SyncScheduler.drainNowReportingBacklog`). One still round is not enough: a device that pulled in
  it may have queued a reaction, and that reaction only pushes on its next tick. This replaces every
  `delay(SETTLE)` that asked a state to "hold for ~15 ticks".
* Every step takes the scheduler's own `tickMutex` (`drainNow`), never `tickOnce` directly. Cycle
  hooks rely on that mutex for single-flight (`tech-debt.md` § "Residual Suite-Ordering Flake:
  ContactHandshakeSystemTest", route 1).

**Forcing an interleaving.** The production personal push gate (`storageGate`) gives a device a
*pull-only* tick. "A's write reaches the relay, B pulls it, and only then does B's own write go up"
is: shut both gates, write on both, open A's, `sync(A)`, `sync(B)` (it pulls, pushes nothing), open
B's, `sync(B)`. Each ordering under test is its own test case (`CalendarMirrorStopSystemTest`,
`ConcurrentMoveVsDeleteSystemTest`). The polled versions needed a `drainNow()` boundary after
closing each gate, because a tick in flight had already sampled it open. With no loop, no tick
is ever in flight between steps.

⚠⚠ **A shut gate does not pull until `realmRelayRegistered` has latched.** `pullAllowed` is
`pushAllowed || realmRelayRegistered`, and a joiner latches that flag in
`RealmRegistrar.ensureRegistered` only on a push-allowed tick *after* its first pull. A polled
joiner gets that second open tick from its cadence. A stepped joiner can stop at exactly one pull,
and then its gated ticks do nothing at all: the ordering degrades without failing. Before shutting a
gate, `syncUntil { a.syncManager.realmRelayRegistered && b.syncManager.realmRelayRegistered }`.
This state is **harness-only**. In production every platform passes `StorageSyncGate`, which
is open whenever `joinedFundedRealm` is set. Pairing sets that flag (`PairingKeyInstaller`, both
directions), iOS route-1 sets both flags (`FirstPartyLocalRuntime.adopt`), and nothing ever clears
it. So a paired joiner's gate never shuts, and its second tick latches the flag. A system test that
passes `storageGate = SyncGate { … }` replaces that gate, and with it `joinedFundedRealm`. Pinned on
the gate side by `StorageSyncGateTest`.

**What stepped mode does not stop.** Only the sync loops stop. Observer fan-out, derived views,
view-model timers and the relay itself still run on their own coroutines. A state computed *after*
ingest is still awaited with `advanceUntil`, after the sync it depends on, for example a Photos
feed row derived from a synced `File` (`PhotoDeleteSyncSystemTest.converge`). `ScheduleDrainLoop`
is also off, so a test that needs a schedule item to run stays polled or drives that item itself.

**The sync clock.** A stepped system also owns `system.syncClock` (`TestSyncClock`), the `nowMs`
every device's sync runtime reads: the scheduler's three `RejectionBackoff` windows, relay
reachability, health stamps. It starts at real time and moves only on `syncClock.advance(…)`. So a
backoff is a sequence too: tick the refused device inside its window and assert nothing was retried,
`advance` past the window, tick once more at the probing boundary
(`RelayAmnesiaRepairSystemTest.anA1Holder_…`; with the `advance` removed it goes red). Advance by
`RejectionBackoff.DEFAULT_MAX_DELAY_MS` when the test does not care which window it is in.
⚠⚠ Only the sync runtime reads it. JWTs, HLCs, entitlement proofs and the ops' own `nowMs` stay on
real time, because the relay checks a token's times against its own clock and `RevokeDevice` signs
with the op clock. So an op-level real-time wait is not shortened by it. When one shows up in a
stepped test, the cause is usually the product's: `ForgetThisDevice` used to spend its whole 8 s
retirement timeout on a removed joiner, which a real device did too, until the wait learned to stop
on the scheduler's `401` state.

**Pollers outside the sync loop: `StepPacer`.** A view model's or op's own poll loop is not the
scheduler's, so stepping the system does not reach it. Where such a poll paces a test, give it a
`pacePoll: suspend () -> Unit` parameter that defaults to its production `delay(…)`, and install a
`StepPacer`'s `pace` in the test. `step()` returns once the poller is back at its pause (its last
poll has finished) and lets exactly one more poll run. `awaitPause()` / `release()` split that, for
a poller that tries once before its first pause and must be seen to have done so before the other
device acts (`AwaitPairing`). `PairingVmFlowSystemTest` steps three: `OfferPairingMonitor`,
`HostPairingMonitor` and `AwaitPairing` (via `TestSystem.client(pairingWaitPacer = …)`). Each
case is then "the other device acts, its own state shows the act reached the relay, one poll here
shows the outcome". Without the step after the approval, the offer case goes red at its 20 s
`awaitState` budget.

**When to stay polled** (§10's exception): when the real cadence *is* the subject and no seam
reaches it. No `:system-test-utils` class is polled for that reason today. Each class's KDoc says
which mode it uses and why.

Measured on a 4-core Linux container, 2026-11-13, `./gradlew :system-test-utils:test --rerun`
(JUnit XML class times). The module went from **177 s to 92 s**:

| Class | Before | After |
|---|---:|---:|
| `CalendarDeleteSyncSystemTest` | 21.9 s | 2.6 s |
| `CalendarMirrorStopSystemTest` | 19.3 s | 0.9 s |
| `ConcurrentMoveVsDeleteSystemTest` | 14.4 s | 1.1 s |
| `PhotoDeleteSyncSystemTest` | 11.0 s | 1.4 s |
| `SyncedThumbnailSystemTest` | 10.6 s | 6.0 s (the rest is rendering a 2600 px PNG's thumbnails) |
| `RelayAmnesiaRepairSystemTest` | 14.3 s | 1.7 s, with the sync clock and `ForgetThisDevice`'s refusal short-circuit |
| `PairingVmFlowSystemTest` | 11.7 s | 0.7 s (the three polls stepped with `StepPacer`) |
