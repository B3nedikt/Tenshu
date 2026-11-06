# Background-sync scheduling: roles, per-platform enforcement, and Path A

**Status:** Design note / decision. Resolves the open seam flagged in
`core-background-processing.md` (Phase 5 landed `ScheduleService` +
`ScheduleDrainLoop` *deliberately separate* from the legacy `SyncScheduler`;
the relationship between them was left undecided) and in
`core-preference-service.md` Phase 5 (the `WifiOnly` / `MaxMonthlyDataMb`
preferences resolve to a `ConstraintSpec` but nothing *enforces* it yet).

**TL;DR:** `SyncScheduler` and `ScheduleService` are **complementary, not a
migration**. Realtime in-process sync stays on `SyncScheduler`; `ScheduleService`
+ a per-platform `PlatformScheduler` handle *app-closed, OS-woken* jobs.
Constraint enforcement therefore needs a home in **both**. The cheap,
desktop-shippable first step is **Path A**: gate the in-process `SyncScheduler`
on the preference-derived constraint. The OS-scheduled enforcement (Android
WorkManager) is **Path B** = bgproc Phase 6 and is genuinely Android-gated.

---

## 1. What exists today

- **`SyncScheduler`** (`:sync-service-proxy`) — the realtime sync engine. While
  the proxy process is alive it polls (cold `pollingIntervalMs`, hot
  `hotPollingIntervalMs` off `StreamRegistry.activeStreamCount`), wakes on a
  user-originated save (`SyncWakeSignal`), and each tick — gated only on
  `syncManager.isEnabled()` — drains pending blocks/tombstones then pulls
  changes/tombstones.
- **`ScheduleService` + `ScheduleDrainLoop`** (Phase 5) — a persisted
  `ScheduleItem` registry + a dumb in-process periodic loop that dispatches
  head-less ops by id. No hot/cold adaptivity, no wake-on-save, no constraint
  enforcement (best-effort, §5.6).
- **The preference resolver** (`backgroundSyncConstraints()` in `:control-shared`)
  + **`BackgroundSyncScheduler`** (`:sync-service-proxy`, pref Phase 5) — produce a
  `ConstraintSpec` from `WifiOnly`/`MaxMonthlyDataMb` and can register/reschedule
  one periodic `ScheduleItem` from it. **Test-only**; nothing consumes the
  constraint.
- ~~**No network-state abstraction exists anywhere in the tree**~~ — **this
  snapshot is superseded by §4 (Path A LANDED).** `NetworkStatus` +
  `NetworkStateProvider` now exist (`:control-shared-logic` interface;
  `Android`/`Desktop`/`Ios` actuals in `:control-shared-proxy`), and
  `ConstraintSyncGate` consumes them to enforce `WifiOnly` on the in-process
  `SyncScheduler`. A background *keeper* / OS-scheduled enforcement (Path B) can
  reuse this provider as-is — it is **not** a missing prerequisite.

---

## 2. How scheduling actually works, per platform

The decisive fact: `ScheduleService` + `ConstraintSpec` earn their keep only
when the **OS** must wake a **non-running** process **under constraints**.

- **Android — real.** WorkManager is a deferred, constraint-aware scheduler: it
  wakes a killed app only when `NetworkType.UNMETERED`, charging, etc.
  `ConstraintSpec.network → WorkManager.Constraints.requiredNetworkType` is a
  clean native mapping. `maxDataMb` has no native analogue → app-level rolling
  byte counter (§3.3). This is where the whole Phase 5/6 machinery pays off.
- **Desktop — no native equivalent.** No desktop OS scheduler wakes a dead
  process *only on unmetered network*. The realistic shapes are:
  1. a **resident daemon** (`core-background-processing.md` §5.3) that stays
     alive and runs the in-process loop — in which case the in-process loop *is*
     the scheduler and constraints are checked in-process; or
  2. **launchd / systemd timers / Task Scheduler** that wake on a clock but
     cannot express network/charging constraints — you check those in-process
     after waking.
  Either way desktop scheduling collapses to "a process is alive running an
  in-process loop that checks constraints itself." Desktop is also generally
  assumed always-on / unmetered (the proposal already ignores `maxDataMb`
  there).
- **iOS / Web** — out of scope here; BGTaskScheduler / Service Worker periodic
  sync respectively, both constraint-poor, both later.

**Consequence:** on desktop, `ScheduleDrainLoop` (dumb periodic) is strictly
*worse* than `SyncScheduler` (adaptive + wake-on-save). Routing desktop sync
through `ScheduleService` would regress responsiveness, or double-drain if both
run. So desktop's "scheduler" should remain `SyncScheduler`.

---

## 3. Decision: roles

| | `SyncScheduler` | `ScheduleService` + `PlatformScheduler` |
|---|---|---|
| Purpose | Realtime sync **while the proxy is alive** | Deferred / periodic head-less jobs **when the app is closed** |
| Driver | In-process loop (adaptive, wake-on-save) | OS scheduler (Android WorkManager; desktop daemon/timer) |
| Lifetime | Foreground app / resident daemon | OS-woken |
| Constraints | Checked **in-process** (Path A) | Enforced by the OS scheduler (Path B) |

They are **complementary**. We do **not** migrate sync-drain off `SyncScheduler`
onto `ScheduleService`. The eventual unification (bgproc Phase 1,
`SyncScheduler → BackgroundJobRegistry`) makes "drain sync" a **head-less op**
so the *same* logic can be invoked by either driver — `SyncScheduler` in-process
when alive, `PlatformScheduler` when the OS wakes a closed app — over **one**
`ScheduleItem`/job store. Phase 1 is the prerequisite for a *real* head-less
sync-drain op (today `SyncScheduler.drainPendingBlocks`/`pullChanges` are
`internal`, not operations; the only `HeadlessSafe` op is a test fake).

**Therefore constraint enforcement is needed in two places** and the
preference resolver feeds both:

- **Path A — in-process gate in `SyncScheduler`.** Makes `WifiOnly` mean
  something for the always-running case (desktop daemon; Android while Control
  is foregrounded). No `ScheduleService` involved. Small, isolated,
  desktop-testable.
- **Path B — OS-scheduler enforcement.** `ConstraintSpec → WorkManager` for
  app-closed Android. Big, Android-only, = bgproc Phase 6. `BackgroundSyncScheduler`
  is its call-site scaffold.

**Recommendation: do Path A next.** It's the smallest change that makes the
preference do something real and is verifiable here; Path B waits for a device.

---

## 4. Path A — **LANDED**

**Goal:** when the realm's background-sync constraint is *not* satisfied (e.g.
`WifiOnly` is on and the device is on a metered network), `SyncScheduler` skips
the drain/pull cycle for that tick instead of syncing.

**As built:**

- `SyncGate` (`fun interface`, `suspend shouldSync()`, `companion AlwaysAllow`)
  lives in **`:sync-service-common`** — not `-proxy` as first sketched in §4.1 —
  because `:control-shared`'s commonMain (where the gate impl lives) only
  depends on `:sync-service-proxy` from its platform source sets.
- `SyncScheduler` gained a `syncGate` param (default `AlwaysAllow`) and the
  guarded cycle was extracted into `internal suspend tickOnce()`
  (`if (!isEnabled() || !syncGate.shouldSync()) return`), threaded through
  `initSyncService(syncGate = …)`.
- `NetworkStatus` + `NetworkStateProvider` (`:control-shared` commonMain);
  `DesktopNetworkStateProvider` → `UNMETERED`; `AndroidNetworkStateProvider`
  → `ConnectivityManager`. Wired via DI per-platform (not `expect`/`actual` —
  the codebase already does platform variance through Koin).
- `ConstraintSyncGate : SyncGate` (`:control-shared` commonMain) caches the
  latest `ConstraintSpec` from `backgroundSyncConstraints()` and delegates to
  the pure `constraintAllowsSync(constraint, network)`. Wired into both
  `PlatformProxyModule`s (`syncGate = ConstraintSyncGate(client.preferenceService,
  <provider>, scope)`).
- **Tests:** `:sync-service-proxy:jvmTest` `SyncSchedulerGateTest`
  (blocking gate skips the cycle; allowing gate drains) +
  `:control-shared:desktopTest` `ConstraintSyncGateTest`
  (`constraintAllowsSync` truth table). Verified `:control-desktop:compileKotlinJvm`.
  **Desktop is inert** (always `UNMETERED` → never blocks) — real gating is the
  Android provider, **unverified** (no SDK).

### 4.1 Shape (original scope, for reference)

1. **`SyncGate` seam** (`:sync-service-common` or `-proxy`):
   ```kotlin
   fun interface SyncGate { suspend fun shouldSync(): Boolean }
   ```
   Default `SyncGate { true }` (no behavioural change). `SyncScheduler` gains a
   constructor param `syncGate: SyncGate = SyncGate { true }` and the loop
   condition becomes `if (syncManager.isEnabled() && syncGate.shouldSync())`.
   *(`isEnabled()` stays the realm/unlock gate; `SyncGate` is the policy gate —
   keep them separate.)*

2. **`NetworkStateProvider`** `expect`/`actual` (new; first network abstraction
   in the tree):
   ```kotlin
   enum class NetworkStatus { UNMETERED, METERED, NONE }
   interface NetworkStateProvider { fun current(): NetworkStatus }
   ```
   - **desktop `actual`:** returns `UNMETERED` (assumed wired/unmetered) — so
     `WifiOnly` never blocks on desktop, matching the documented assumption.
   - **Android `actual`:** `ConnectivityManager` (`NetworkCapabilities`
     `NET_CAPABILITY_NOT_METERED` / no transport → `NONE`). **Unverifiable in
     this build env (no Android SDK).**

3. **Gate impl bridging the resolver + network state** — lives in
   `:control-shared` (the only module with both `:preference-service` and the
   sync proxy wiring; mirrors `BackgroundSyncConstraints.kt`). Holds the latest
   `ConstraintSpec` (collected from `PreferenceService.backgroundSyncConstraints()`)
   and queries `NetworkStateProvider`:
   ```
   shouldSync() = when (constraint.network) {
       UNMETERED -> networkStatus == UNMETERED
       ANY       -> networkStatus != NONE
       NONE      -> false
   }
   ```
   This keeps `:sync-service-proxy` free of any `:preference-service` dependency
   — the binding happens at the DI wiring layer.

4. **Wiring:** construct the gate in the platform proxy module
   (`control-shared/.../PlatformProxyModule`, desktop + android) and pass it to
   `SyncScheduler` via `initSyncService` (new optional param threaded through
   `SyncProxyComponents`, default no-op so existing tests/JS are unaffected).

### 4.2 Tests (desktop-verifiable)

- `SyncScheduler` unit/integration: with a `SyncGate { false }` (and with a fake
  `NetworkStateProvider` reporting `METERED` + `WifiOnly = true`), assert a tick
  performs **no** drain/pull; with the gate satisfied, assert it does. The
  existing `SyncScheduler` tests already exercise the drain path — add a
  gated-skip case.
- Gate-impl unit test (`:control-shared`): `(WifiOnly, networkStatus)` truth
  table → `shouldSync()`.
- The Android `NetworkStateProvider` `actual` ships **unverified** (no SDK),
  flagged like prior Android-only work.

### 4.3 Explicitly out of Path A

- **`maxDataMb` enforcement** — needs a per-realm rolling byte counter (§3.3);
  desktop ignores it. Follow-on, not in the first cut.
- **Granularity** — Path A gates the *whole realm sync cycle* (push + pull),
  which is the literal meaning of "sync over Wi-Fi only." A future refinement
  could allow small metadata sync on metered networks and gate only large
  file-content (the two-plane blob channel) — note it, don't build it.
- **App-closed behaviour** — that's Path B / Phase 6.

### 4.4 Sizing

Small. One new seam interface + one `expect`/`actual` (desktop trivial) + a
~15-line gate impl + one wiring line + tests. The only Android-bound,
unverifiable piece is the `ConnectivityManager` `actual`.

---

## 5. Sequencing recommendation

1. **Path A** (this note's §4) — ship `WifiOnly` enforcement for the
   in-process/always-on case. Desktop-verifiable now. ✅ **DONE.**
2. **bgproc Phase 1 — LANDED (compile-verified 2026-07-08; see §6).** The
   reconciled shape — per §3.2/§6.3, which **override the stale §8 "Phase 1"
   text** — is: turn sync-drain into a real `HeadlessSafe DrainSync` op that both
   `SyncScheduler` (realtime) and `ScheduleDrainLoop`/`PlatformScheduler`
   (deferred) run; there is **no** separate `BackgroundJobRegistry` (the
   `ScheduleService` / `ScheduleItem` set already *is* it). But it's
   "no-behaviour-change" groundwork whose only consumers — Path B,
   `PlatformScheduler`, and `BackgroundSyncScheduler` prod wiring — are all
   deferred / Android. Building it now is core-sync-engine churn (rewiring
   `SyncProcessor` / `HlcDivergenceTracker` / peer-tip deps through an op impl)
   with no testable benefit, so do it **alongside Path B**, when it can be
   verified on-device. (Not the "tests migrate verbatim" no-op the §8 text
   claims.) **See §6 for the full grounded plan** — and note §6.2's correction:
   the churn is smaller than framed here, because `SyncScheduler.drainNow()` +
   `SyncSchedulerHolder` already give a clean delegation seam, so `DrainSync` is
   a thin op and the real work is retiring the bespoke `SyncWorker`.
3. **bgproc Phase 6 / Path B** — `PlatformScheduler` (Android WorkManager) +
   wire `BackgroundSyncScheduler` into prod init. **LANDED with Phase 1 (§6, step 2
   above — they are one atomic change: wiring `BackgroundSyncScheduler` requires the
   `DrainSync` op to schedule, and retiring `SyncWorker` avoids the double-drain).**
   `maxDataMb` enforcement (Phase 1c, the per-realm rolling byte counter) still
   deferred; `network` (`UNMETERED`) is enforced.

Historical note: the "do not wire `BackgroundSyncScheduler` before step 2" rule held
until 2026-07-08 — steps 2+3 then landed together (there is now a real op to schedule
and `SyncWorker` is retired, so no double-drain).

---

## 6. Implementation Notes for Claude Code — Phase 1 + Path B (Android-gated)

**Status: Phase 1a + 1b LANDED, compile-verified (2026-07-08); on-device pass
pending.** Built exactly as planned below. Phase 1a (the `DrainSync` op) is unit-tested
on desktop (`DrainSyncTest`: `isHeadlessSafe`, real-module dispatch fires the drain,
production factory routes the id). Phase 1b (Android prod wiring + `SyncWorker`
retirement) is **compile-verified** — `:control:compileDebugKotlin` green (the build
env *does* have an Android SDK at `~/Library/Android/sdk`; a worktree just needs
`local.properties` copied in — the earlier "no SDK" notes were about missing that).
On-device verification (app-closed drain fires; `WifiOnly` defers on cellular;
`PostDrainHook` notification; no double-drain) still pending a device/emulator.

**As-built correction to §6.3's framing:** the retirement is a *unification +
efficiency* win, **not a correctness fix**. `SyncWorker` already enforced `WifiOnly`
for app-closed sync — it woke on `NetworkType.CONNECTED` then `drainNow()` → the
Path-A `ConstraintSyncGate` no-op'd on metered. So the pre-1b behaviour was correct,
just wasteful (a metered wake that does nothing). 1b's gain: the `ConstraintSpec →
NetworkType.UNMETERED` WorkManager-native constraint means the OS *doesn't wake at
all* on metered (fewer wakeups), plus one driver/registry and a `maxDataMb`-ready path.

Read §§1–5 first for the *why*; this section is the *how*, grounded in the code as it
stands after bgproc Phase 6/7 + the offline keeper + tray Slice C landed.

### 6.1 What already exists (so the plan is small)

The substrate is almost entirely built — this track is *wiring + one thin op +
one retirement*, not new machinery:

- **`SyncScheduler.drainNow()`** (`:sync-service-proxy`) — `suspend fun drainNow()
  = tickMutex.withLock { tickOnce() }`. A **single-flight** serialized drain of the
  full cycle (drain pending blocks/tombstones, pull changes/tombstones, shared
  realms), already gated on `isEnabled()` **and** `syncGate.shouldSync()` (Path A).
  This is the shared drain body — it does **not** need re-extraction.
- **`SyncSchedulerHolder`** — a mutable post-init holder for the session scheduler
  (populated from `SyncProxyComponents.scheduler`). The Android `SyncWorker` already
  reads it and calls `drainNow()`. This is the seam a `DrainSync` op uses.
- **`ScheduleService` + `ScheduleItem` + `ScheduleDrainLoop`** (Phase 5) — the one
  job registry + the in-process drain loop that dispatches head-less ops by id via
  `executeAsync(opId)`. **Is** the `BackgroundJobRegistry` (§3.2).
- **`WorkManagerScheduler` + `TenshuOperationWorker`** (Phase 7, `:sync-service-proxy`
  androidMain) — maps a `ScheduleItem` → a constraint-mapped `PeriodicWorkRequest`
  keyed on `dedupKey`; the worker wakes closed Control and dispatches the op id via
  `ScheduledOpDispatcherHolder`. Constraint mapping (`§5.6`) is `commonTest`-covered.
- **`BackgroundSyncScheduler`** (`:sync-service-proxy`, pref Phase 5) — reconciles ONE
  dedup-keyed periodic `ScheduleItem` with a `Flow<ConstraintSpec>`; a pref toggle
  reschedules in place. **Test-only; nothing consumes it.**
- **`backgroundSyncConstraints()`** (`:control-shared-logic`) — `WifiOnly` /
  `MaxMonthlyDataMb` → `ConstraintSpec`. Landed + tested.
- **The `HeadlessSafe` op template** — `OfflineKeeper` (`:file-service`, op decl:
  `operation(id, [IoOperation, TerminalOperation, HeadlessSafe])`) +
  `OfflineKeeperImplementation` (`:file-service-proxy`, an
  `AsyncOperationImplementation`, param-less, reaches deps via `executionContext` +
  one injected field) + `registerOfflineKeeperSchedule` (the `executeAsync(Schedule.ID,
  …)` producer) + factory registration. **Copy this shape verbatim for `DrainSync`.**

### 6.2 Correction to §5 step 2 — the churn is smaller than feared

§5 warned Phase 1 is "core-sync-engine churn (rewiring `SyncProcessor` /
`HlcDivergenceTracker` / peer-tip deps through an op impl)." **That over-scopes it.**
`SyncScheduler.drainNow()` already encapsulates the whole drain, and
`SyncSchedulerHolder` already exposes it to a framework-instantiated worker (the
Android `SyncWorker` proves the pattern). So `DrainSync`'s implementation is a
**thin delegate** — `schedulerHolder.scheduler?.drainNow()` — carrying **no** sync
internals. No `SyncProcessor`/`HlcDivergenceTracker` rewiring. The real work is (b)
below: retiring the bespoke `SyncWorker` so there is one constraint-aware driver.

### 6.3 The double-drain problem, concretely

There are **two** Android background drivers today, and both drain:

1. **`SyncWorker`** (`:sync-service-proxy` androidMain) — its own
   `enqueueUniquePeriodicWork("PeriodicSyncWorker", …)`, drains via
   `SyncSchedulerHolder.drainNow()` + runs the `PostDrainHook` (central-notification
   elevation). This is the *bespoke* bgproc-Phase-6 background drain. **No constraint
   awareness** beyond whatever `Constraints` its `enqueue()` sets.
2. **`TenshuOperationWorker`** via `WorkManagerScheduler`/`ScheduleItem` — the
   *generic* constraint-aware Phase-7 path, driven by `ScheduleItem` constraints.

Phase 1 = **converge sync-drain onto driver #2 and retire #1.** A `DrainSync`
`ScheduleItem` registered by `BackgroundSyncScheduler` carries the
`WifiOnly`/`maxDataMb` `ConstraintSpec` → `WorkManagerScheduler` maps it to real
`Constraints.setRequiredNetworkType(...)` etc. → `TenshuOperationWorker` dispatches
`DrainSync.ID` → `drainNow()`. That is the whole point: **`WifiOnly` finally gates
app-closed Android sync** (Path A only covers the in-process/window-open case). If
`SyncWorker` keeps running alongside it, they double-drain — hence the §5 "do not
wire before step 2" rule. The `PostDrainHook` must move to the `DrainSync` path
(dispatch a post-op hook, or fold the notify pass into the op) so central
notifications survive the retirement.

### 6.4 Phased plan

**Phase 1a — the `DrainSync` head-less op (desktop-compilable; on-device N/A).**
- New `object DrainSync { ID = "tenshu/sync/drainSync"; Schema = operation(ID,
  [IoOperation, TerminalOperation, HeadlessSafe]) }` in **`:sync-service`** commonMain
  (next to `OfflineKeeper`'s sibling ops; `isHeadlessSafe()` gates scheduling).
- `DrainSyncImplementation : AsyncOperationImplementation` in **`:sync-service-proxy`**
  commonMain: param-less, `execute { schedulerHolder.scheduler?.drainNow(); null as T }`.
  Inject `SyncSchedulerHolder` (already a Koin single on every platform). No receiver,
  no params, no client RO — exactly like `OfflineKeeperImplementation`.
- Register in the sync-service-proxy operation factory (mirror the offline keeper).
- **Wire-registration gap** (see the [[wire-registration-gap]] memory / `docs/tech-debt.md`):
  a new op dispatched via `executeAsync` must be registered so the proxy `BinaryParser`
  resolves it — add `DrainSync.Schema` wherever `OfflineKeeper.Schema` /
  `Schedule.Schema` are registered (coreCommonModule / the service's `SchemaProvider`).
  Verify with a serialize→parse round-trip test.
- **Tests (desktop-verifiable now):** `DrainSync.Schema.isHeadlessSafe()` is true;
  `DrainSyncImplementation` calls `drainNow()` exactly once (fake `SyncSchedulerHolder`
  with a recording scheduler); no-op when the holder is empty (cold-boot race).

**Phase 1b — wire `BackgroundSyncScheduler` into prod init + retire `SyncWorker`
(the on-device slice).**
- In **Android** `PlatformProxyModule`'s proxy-init hook (where `SyncWorker.enqueue`
  is called today): construct `BackgroundSyncScheduler(executionContext, constraints
  = client.preferenceService.backgroundSyncConstraints(), scope = sessionScope, opId
  = DrainSync.ID, periodMs = WorkManager 15-min floor…1h, dedupKey = "sync-drain")`
  and `.start()`. Populate `ScheduledOpDispatcherHolder.dispatch` (post-init) so
  `TenshuOperationWorker` can run `DrainSync`.
- **Remove `SyncWorker.enqueue(...)`** from that hook (and, once nothing calls it,
  the class) — one driver. Move `PostDrainHook` to the `DrainSync` path.
- **Desktop/iOS unaffected:** they bind `NoOpPlatformScheduler`; the resident daemon's
  in-process `SyncScheduler` + `ScheduleDrainLoop` stay the drivers (a `DrainSync`
  `ScheduleItem` there would be dispatched by `ScheduleDrainLoop` in-process, which is
  redundant with the realtime loop but harmless via `tickMutex` — prefer **not**
  registering the `BackgroundSyncScheduler` item on desktop, keep it Android-only, so
  the always-on daemon isn't double-driving. Gate the `.start()` call on the platform
  binding an OS scheduler, or simply only call it from the Android module).
- **On-device verification (the gate):** (1) with Control **closed**, a `DrainSync`
  `ScheduleItem` fires via WorkManager and drains inside Control (keys stay put); (2)
  toggling `WifiOnly` on a metered network keeps it **deferred** (the WorkManager
  `NetworkType.UNMETERED` constraint), and un-metering releases it; (3) **no
  double-drain** — assert only one periodic unique-work name remains after the
  `SyncWorker` retirement; (4) `PostDrainHook` notifications still fire.

**Phase 1c — `maxDataMb` rolling counter (optional follow-on; §3.3).** WorkManager
has no byte cap → `WorkManagerScheduler`/`TenshuOperationWorker` keep a per-realm
rolling monthly byte counter; a run that would exceed `ConstraintSpec.maxDataMb`
returns `Result.retry()` (deferred) until the month rolls or the device is unmetered.
`network = UNMETERED` already covers the common "no cellular sync" case, so this is
lower priority. Desktop/iOS ignore `maxDataMb` (assumed unmetered), unchanged.

> **UI pulled 2026-07-09.** The user-facing `maxDataMb` control was removed from
> both settings surfaces (Control *Background sync* field + Drive *Sync
> preferences* row) because nothing enforced it — a setting that silently did
> nothing. The `ControlPreferences.MaxMonthlyDataMb` preference and
> `backgroundSyncConstraintSpec` plumbing are **kept** (defaulted, still absent
> from the emitted `ConstraintSpec` when 0). Re-surface the control **together
> with** the enforcement above so it never ships ahead of its effect again.

### 6.5 Decisions to confirm with the maintainer before coding

1. **`DrainSync` delegates to `SyncSchedulerHolder.drainNow()`** (recommended, §6.2)
   vs extracting the drain body into a scheduler-independent `SyncDrainRunner`. The
   holder-delegation reuses `drainNow()` + `tickMutex` (no new single-flight logic)
   and matches the existing `SyncWorker`; extraction buys nothing here.
2. **Retire `SyncWorker` outright** (recommended — one driver) vs keep it and gate one
   off. Retiring removes the double-drain surface entirely; the only migration cost is
   moving `PostDrainHook`.
3. **Android-only `BackgroundSyncScheduler.start()`** (recommended) so the desktop
   daemon's in-process loop isn't shadowed by a redundant `ScheduleItem`. Confirm
   desktop keeps `SyncScheduler` as its sole driver (per §3, it does).
4. **Period floor** — WorkManager's periodic minimum is 15 min; pick 15 min–1 h for
   the app-closed drain (the realtime in-process loop remains the low-latency path
   while Control is open).

### 6.6 No threat-model / relay / wire change

`DrainSync` runs the *existing* drain; it moves no data the relay doesn't already see
and adds no plaintext-at-rest. The only new schema is a param-less op marker (code-side,
not serialised state). House Rule #2 doesn't trigger. (If Phase 1c's byte counter
persists per-realm counters, that's local-only bookkeeping, still no relay/A1 change.)
