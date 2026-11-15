# Background Processing — Three-Layer Model, Platform Schedulers, Tray UX

**Status:** ⚠⚠ **This header is stale and understates what shipped — §10.6 is the truth.**
Phase 4a-bis (`SyncWakeSignal`) is on master, and Phases 5 (`ScheduleDrainLoop`), 6
(`PlatformScheduler` + `DrainSync`) and 7 (`WorkManagerScheduler` + `TenshuOperationWorker`) are
built and wired in production DI. The rest of this header describes the 2026-05 state.

Phase 4a *fast short-poll* landed in lean form (2026-05-13). Carved
out from the full Phase 4a: no `BackgroundJobRegistry` extraction (Phase 1),
no per-type `SyncableTypeSubscriberCounter` (Phase 2 — replaced by a single
"any stream observed" boolean derived from `StreamRegistry.activeStreamCount`),
no HLC-tip gating (Phase 3), no immediate-pull-on-edge or wake-on-save
(§4.3 #1 and #2 — the wake-channel machinery was tried and reverted because
applying it to `SyncObserver.onDataSaved` produced an infinite block-echo
between paired devices; the edge-trigger half was dropped along with it for
simplicity). `SyncScheduler` now switches between [pollingIntervalMs] and
[hotPollingIntervalMs] (default 1500 ms) when streams open/close, with `minOf`
guarding aggressive test overrides. Worst-case latency to the first hot pull
after opening a stream is one cold tick (the in-flight `delay` doesn't
preempt). Phase 4a-bis (§4.3 #1+#2 reintroduced via a sync-vs-user save
discriminator — see §8 Phase 4a-bis), Phase 4b (long-poll), Phase 3 (HLC-tip
gating), Phase 5–8 (Layer 3), and the deferred immediate-pull-on-edge
optimisation are all separate tracks. (The §7.1 file-content storage decision
is **resolved** — the two-plane content channel, [ADR 0017](../adr/0017-file-content-two-plane-channel.md);
see the §7.1 update note.)

**Phase 4a-bis** (wake-on-save via a sync-vs-user save discriminator) **landed**
on the Track-B branch `feat/background-processing` (commit `7c740326`,
2026-05-15) as a self-contained `SyncWakeSignal` + scheduler glue; it reaches
master when Track B merges. It is independent of Phase L2 below (different
module: 4a-bis = `:sync-service-proxy`; L2 = `:core-common` transport).

**Phase L2** (wire-level `TaskHandle` / cross-process cancellation, §8 "Phase
L2") **landed for the cross-process path** (this PR). The `ProxyGateway`
in-flight registry, the desktop Unix-socket cancel frame + per-id correlation
+ non-blocking server, the `TaskHandle` / `CancellableTaskInteractor` DSL, and
the discipline guard are in; `:core-common:desktopTest` green incl. a new
`PhaseL2CancellationTest` (registry, socket round-trip, pending-cancel race,
discipline boundary, static check). Two scoped decisions are recorded in §8
"Phase L2": the task id is **client-assigned** (the spec's proxy-assigned +
"task started" ack was a sketch — a connection-scoped correlation key is all
correctness needs, and client-assignment avoids the binder double-return and a
back-channel result route).

**In-process carve-out — CLOSED** (follow-up PR `feat/inproc-bg-cancel`). A
`CancellableLocalTaskExecutor` (`LocalTaskExecutor` implements it) runs a
`BackgroundOperation` via `coroutineScope { withEvaluationScope(this,
runBackgroundInRequestScope = true) }`, the in-process mirror of
`ProxyGateway`'s cross-process `requestScope` path — so the JS SDK
(`LocalTaskExecutor`, no `ProxyGateway`) now runs background ops *awaited and
cancellable* too, not the legacy detached `sessionScope.launch` +
`Success(null)`. `InProcessBackgroundCancelTest` proves await-not-detached and
cancel-unwinds; `:tenshu-sdk-js:jsTest` + full `:core-common:desktopTest`
green.

**§7.5 pairing migration — LANDED** (follow-up PR `feat/pairing-l2`, on top
of the closed carve-out so it is one shared migration, no platform split).
`PairingCompletion` extracted from `GetPairingStatusImplementation` (one copy
of the completion crypto); new `AwaitPairing` `BackgroundOperation` loops it +
`catch(CancellationException){clearHandshake}`; `PairingWizardViewModel` drops
the 60×5s poll loop + `CancelPairing` lever for `handle.await()` /
`handle.cancel()`. Threat-model A8 amended (deterministic handshake teardown).
See §7.5 for the full shape.

**Android binder cancel frame — LANDED** (PR `feat/android-binder-l2`).
`:core-aidl` `IProxyInterface` gains `callCancellable(byte[], String)` +
`oneway cancel(String)`; `ProxyService` routes them to
`proxyGateway.call(..., taskId)` / `proxyGateway.cancel(...)` (the oneway
cancel runs on a separate binder-pool thread, so it is never serialised
behind the in-flight `callCancellable` — binder's threadpool gives this for
free, unlike the desktop socket which needed a non-blocking server
restructure); `BinderTaskTransport` overrides the two `TaskTransport`
methods instead of the no-op defaults. **Not locally verifiable** (no Android
SDK in the build env, per the repo's "run Android from Android Studio" note)
— correctness-by-construction + CI/Studio verification; pure Android-only
change (no commonMain), so desktop/JS are unaffected. This closes the last
Phase L2 transport; `BackgroundOperation`/`TaskHandle` cross-process cancel
now works on all three transports (desktop socket, Android binder, JS
in-process).

**Phase L2-LIVE — Android LANDED** (§8 "Phase L2-LIVE"). The
per-platform Layer-2 *process-liveness* wrapper §2.2 names and the Phase L2
runtime does not deliver. `ProxyGateway` now exposes
`inFlightCount: StateFlow<Int>` (the one **commonMain** addition — the §8/§5.2
"pure `:control-android`, no commonMain" wording was design-intent optimism:
the Phase-L2 in-flight registry is `private` with no observable hook, so a
minimal read-only count flow is the seam; it mirrors the existing
`StreamRegistry.activeStreamCount` pattern, updated under the same
`registryMutex`, so it is additive and non-regressive). `ForegroundTaskLiveness`
in **`:control`** (the Android Control app module — there is no separate
`:control-android` module; the spec name maps here) holds only the registry →
liveness edge logic (Android-import-free, JVM-unit-tested) and drives a
`TaskLivenessHandle`; `ProxyService` implements it as a `dataSync` foreground
service: empty→non-empty → `startForegroundService` + `ServiceCompat
.startForeground` (ongoing "Keeping N background tasks running" notification);
non-empty→empty → `stopForeground(REMOVE)` + `stopSelf` (bound lifetime
unaffected). Manifest gains `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`/
`POST_NOTIFICATIONS` + `foregroundServiceType="dataSync"`. **Locally verified**
(unlike the Phase L2 binder PR): `:control` main/unit/androidTest source sets
all compile against the local SDK, `ForegroundTaskLivenessTest` 5/5 green, full
`:core-common:desktopTest` green incl. a new `inFlightCount` assertion in
`PhaseL2CancellationTest`; the instrumented test compiles but needs a
device/emulator to run. Not WorkManager (§5.2) — orthogonal to Layer 3 (no
`ScheduleItem`/`HeadlessSafe`/platform scheduler). This is the actual unblock
for `drive-folder-upload.md` Phase 3a on Android.

**Phase 5 (Layer 3) — LANDED 2026-05-18 (slices 1+2).** The design
was reshaped with the maintainer *before* implementation, then built:
`HeadlessSafe` trait (slice 1) + the `ScheduleSpec`/`ConstraintSpec`/
`RetryPolicy`/`ScheduleItem` composites, `ScheduleService` (3 ops) and the
in-process `ScheduleDrainLoop` (slice 2), with threat-model invariant **I9**.
Tests green (`HeadlessSafeTest` 3/3, `ScheduleServiceTest` 3/3). Per-platform
`PlatformScheduler` **seam + Android WorkManager LANDED 2026-07-07** (§8 Phase 6
Slice A + Phase 7 Slice B): the Koin-per-platform `PlatformScheduler` interface,
`Schedule`/`CancelScheduled` → `register`/`cancel` wiring, `NoOpPlatformScheduler`
(desktop/iOS), and `WorkManagerScheduler` + `TenshuOperationWorker` (Android,
device-unverified). **Slice C — largely as-built via a different track (2026-07-08):**
the desktop *daemon* (launchd/`LaunchAgentManager` at login) and the persistent menu-bar
*tray* already shipped under [`desktop-macos-integration.md`](desktop-macos-integration.md) —
the Compose `Tray` sketch of §5.3/§8 was **superseded on macOS by the native Swift
`NSStatusItem` agent**, and the resident daemon hosts the in-process `ScheduleDrainLoop`
(Layer 3) + keeps in-flight Layer-2 tasks alive. The remaining §5.3-specific piece —
rendering the **four sync states** (Synced / Syncing / Offline / **Divergence detected**)
in the tray — **landed 2026-07-08**: `getSyncState` (`ControlBridge`) now emits an
authoritative `SyncStateCode` derived (pure `syncStateCode(...)`, unit-tested) from the
unlock flags + the Control→Drive sync-health `SyncHealthHolder` figures (`online`/`isSyncing`/`pendingBlockCount`)
+ `divergenceWarning`; the tray maps it to a localised label + attention tint. This closes
the CLAUDE.md-flagged "UI rendering of `SyncManager.divergenceWarning`" downstream piece on
desktop. The tray **Pause/Resume-sync** control + the §5.3 **`PAUSED`** state **landed
2026-07-08**: a device-local `SyncPauseState` (Koin single, persisted via
`DesktopControlSettings`) composed *ahead* of `ConstraintSyncGate` in the desktop `syncGate`
so pausing skips the whole in-process drain/pull cycle (⚠ no longer true — `tech-debt.md` *"The desktop tray's Pause holds file content only"*); `setSyncPaused` bridge method + a tray
button. **Slice C is now complete on macOS** — remaining Slice-C scope is only Linux/Windows
daemons (macOS-only so far). Layer 3
is **not** a single
`ScheduledOperation` trait carrying
schedule/constraint params. It is a param-less **`HeadlessSafe`** eligibility
marker (fail-closed static enforcement of the §6.4 no-user-presence
invariant) **plus** a persisted **`ScheduleItem`** composite + **`ScheduleService`**
carrying the per-invocation schedule/constraints/retry/dedup (absorbs §6.3
`BackgroundJobRegistry`). Rationale + shape in §3.2; §3.3/§6.3/§6.4/§8 Phase 5
updated to match. Folder-upload Phase 3 is correspondingly split (3a =
Layer-2 *survive-app-close*: the `TaskHandle` runtime is landed (Phase L2) **and** the Android per-platform Layer-2 process-liveness wrapper
from §2.2/§5.2 is now landed (Phase L2-LIVE — `ForegroundTaskLiveness`
+ `ProxyService` foreground service); desktop's is free once the §5.3 daemon
ships. So 3a is **unblocked on Android**; 3b = Layer-3 deferred/metered, gated
on Phase 5) — see `drive-folder-upload.md`.

**Scope:** `:core` (`trait/BackgroundOperation.kt`, new
`trait/HeadlessSafe.kt` + `ScheduleItem`/`ScheduleService` schemas,
`BackgroundScopeProvider.kt`),
`:sync-service-proxy` (`SyncScheduler.kt` repurposed under a new
`BackgroundJobRegistry`), `:sync-service-common` (Stream-subscriber → syncable-type
bridge), `:control-shared` + `:control-desktop` + `:control` (the Android Control app
module; per-platform scheduler bindings, daemon mode, tray icon, foreground
service), `:tenshu-sdk-js` and apex (Service
Worker scaffolding, deferred). Threat-model amendment under
`docs/sync/threat-model.md`.

This proposal names the runtime gap left by the current `BackgroundOperation`
marker trait. Today the trait says "operations may outlive the individual request"
with no scheduling primitive behind it, no platform integration, and no signal
about user presence or wake conditions. `SyncScheduler` polls every 30 seconds
on whatever `CoroutineScope` is handed to it, regardless of whether anything is
being observed and regardless of whether peers have advanced their HLC tip.
There is no story for work that runs while the proxy process is dead.

The fix is a three-layer split: subscriber-driven freshness inside the live
proxy (Layer 1), long-running in-process tasks that outlive a single IPC call
(Layer 2), and OS-scheduled out-of-process work (Layer 3). Each layer has a
different lifetime, a different cost model, a different cancellation story,
and — most importantly — a different threat-model assumption about user
presence. Conflating them under one trait makes all three honest about exactly
none of those distinctions.

---

## 1. What's there today

### 1.1 `BackgroundOperation` is a marker

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/trait/BackgroundOperation.kt
interface BackgroundOperation : Trait {
    companion object {
        const val ID = "tenshu/core/operation/background"
        val Schema: TraitSchema = trait(ID)
    }
}
```

That's the entire surface. No constraints, no schedule, no platform binding —
just a flag the proxy can read off the operation schema. Nothing in the runtime
inspects it for anything other than dispatch routing.

### 1.2 `BackgroundScopeProvider` is the in-process scope only

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/BackgroundScopeProvider.kt
interface BackgroundScopeProvider : TComponent {
    val serviceScope: CoroutineScope
    companion object { const val ID = "core/background-scope-provider" }
}
```

`serviceScope` is a long-lived coroutine scope owned by the proxy process. It
survives request lifetimes; it does not survive the proxy process. When Control
is killed (Android low-memory, Desktop close-app, Web tab-close), this scope
goes with it. Anything that wants to keep running needs to be re-scheduled by
something the OS keeps alive.

### 1.3 `SyncScheduler` polls on a fixed tick regardless of observation

```kotlin
// sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncScheduler.kt
class SyncScheduler(
    // ...
    private val pollingIntervalMs: Long = 30000,
    private val onPeerTip: (SignedHlcTip) -> Unit = {}
) {
    fun start() {
        if (syncJob != null) return
        syncJob = coroutineScope.launch {
            while (isActive) {
                if (syncManager.isEnabled()) {
                    drainPendingBlocks()
                    drainPendingTombstones()
                    pullChanges(since)
                    pullTombstones(since)
                }
                delay(pollingIntervalMs)
            }
        }
    }
}
```

Three problems compound here. First, the loop runs whether or not anything is
being observed — opening Drive on a fresh device with no UI surfaces still
ticks every 30s. Second, the pull is unconditional: even when peers haven't
advanced their HLC tip, we round-trip to the relay and ask. Third, the cadence
is hard-coded — there's no way for an active screen to ask for tighter
freshness, and no way to back off when nothing's open.

The `onPeerTip` hook is plumbed in (per `sync-backend-relay-plan.md` §5.1) but
production wiring is `{}` — peer tips are accepted, the divergence tracker
isn't connected, and the tip information isn't yet used to gate whether a pull
should run at all.

### 1.4 No platform scheduler integration exists

There is no Android `WorkManager`, no iOS `BGTaskScheduler`, no Web Background
Sync registration, no Desktop daemon, no `launchd` plist or `systemd` unit
template, no Task Scheduler XML. The proxy is launched on demand (Android: by
Drive over AIDL; Desktop: by `:control-desktop` `main`) and dies when the host
goes away. There is no path for "the OS wakes the proxy at 3am to drain a
pending upload."

### 1.5 `Stream` lifecycle is request-scoped but not type-aggregated

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/collection/list/Stream.kt
interface Stream<T : Data> : Operation {
    fun stream(): RequestObject
    object Params { const val STREAM_ID = "$ID/streamId" }
}

interface EndStream : Operation { /* ... */ }
```

Every subscribe creates a `streamId`; every unsubscribe ends it. The proxy
already knows which streams are alive — that's how `StreamElementDispatcher`
fans events back out — but there's no aggregated "how many streams currently
observe data of type X" signal that `SyncScheduler` (or anything else) can
read. The information is derivable; today nothing derives it.

---

## 2. Three layers, three contracts

| Layer                          | Lifetime                                        | Process               | User presence                     | Cost per "tick"                                         | Driving signal                              |
|--------------------------------|-------------------------------------------------|-----------------------|-----------------------------------|---------------------------------------------------------|---------------------------------------------|
| 1. Subscriber-driven freshness | While ≥1 Stream is observed                     | Proxy alive           | Implicit (UI is open)             | One long-poll round trip, gated on HLC peer-tip advance | Stream subscriber count per syncable type   |
| 2. Long-running in-process     | Multiple IPC calls; user may close the UI       | Proxy kept alive *for the task* (Android foreground service / desktop resident daemon) | May be absent — the app can be closed | Bound to the work itself | Operation is enqueued, returns a TaskHandle |
| 3. Platform-scheduled          | Outlives proxy process                          | Whatever the OS wakes | **Cannot assume present**         | One full proxy boot per wake                            | OS scheduler fires per declared constraints |

Two things matter most about this table. First, the user-presence column is the
**threat-model column** — Layer 3 cannot trigger any UI-required flow
(recovery, pairing approval, device selection), and the proposal calls that
out as a hard rule rather than an emergent property. Second, the
"driving signal" column is the **trait column** — Layer 1 doesn't need a trait
because it's a runtime concern about Stream lifetime, Layer 2 needs a trait
that says "outlives the request" (`BackgroundOperation`), and Layer 3 needs
only a static *eligibility* marker (`HeadlessSafe`, §3.2) — its schedule and
constraint metadata is per-invocation runtime data owned by `ScheduleService`,
not trait params. One trait can't honestly carry all three.

### 2.1 Layer 1 — Subscriber-driven freshness

When a `Stream<T>` for a syncable type `T` gains its first subscriber, the
sync loop for `T` becomes "hot": it long-polls `/sync/changes` and drains
incoming blocks promptly. When the last subscriber disappears, the loop
returns to "cold": it stops polling for that type, falling back to the
generic any-block periodic drain (or to nothing, if no other type is hot
either).

The pull side is **not scoped per-type** — see §4.5 — because per-type
scoping at the relay leaks shape data and violates the zero-trust principle
that asset A1 hangs on. Scoping is at the **emit** side, which is already free
in the existing reactive layer.

### 2.2 Layer 2 — Long-running in-process tasks

Operations that outlive a single request but stay inside the live proxy
process. Examples already in flight:

- The pairing handshake (currently polled by `PairingWizardViewModel` via
  repeated `GetPairingStatus` calls — this is the right *behaviour*, the
  wrong *abstraction*).
- File upload progress — once `UploadFile` becomes streamable rather than a
  one-shot, the multi-chunk progress reporting is a Layer 2 task.
- Recovery claim — multi-step state machine over key derivation + relay
  fetch + local materialisation.

This layer is what `BackgroundOperation` *actually* describes today; the
docstring's "may outlive the individual request" is correct at face value,
just incomplete on what runtime support that implies. The wire-level
`TaskHandle` work-item already on
[`docs/tech-debt.md`](../tech-debt.md) §"Wire-Level Task Cancellation" is the
runtime piece that makes this concrete: a Layer 2 operation returns a
`TaskHandle` instead of a synchronous `TResult`, and the handle is what gets
queried for progress and cancelled across IPC.

**Layer 2's contract includes process liveness.** "Stays inside the *live*
proxy process" raises the question the original framing skipped: *what keeps
the proxy alive once the foreground app goes away mid-task?* On a user-started
upload the user closes Drive and walks off — the §3.1 assumption "the
foreground app is the reason the proxy is alive" is then **false**, yet the
work must continue. So Layer 2's contract is: long-running in-process **and
the platform proactively keeps that process alive for the duration of the
task** —

- **Android:** Control runs a **foreground service** (persistent
  notification) for as long as a Layer-2 task is in flight. This is *not*
  WorkManager. WorkManager is a Layer-3 *deferred/periodic trigger*; a
  foreground service is *process liveness for work happening now*. They are
  orthogonal mechanisms and compose only in the deferred-resilient case
  (WorkManager decides *when/whether to (re)start*; the foreground service
  keeps the run *alive while it runs*). The common case — "start now, survive
  the app closing" — needs **only** the foreground service and **no**
  WorkManager.
- **Desktop:** the resident daemon (§5.3) plays the same role — it stays up
  for an in-flight Layer-2 task, not only to host the Layer-3 scheduler.

What Layer 2 still does *not* do: survive proxy **death** (kill the daemon /
swipe-kill Control → the task is gone; durability across process death is
Layer 3), and it still must not require *interactive* user input mid-run
(that's the §6.4 line, unchanged).

### 2.3 Layer 3 — Platform-scheduled background work

Out-of-process scheduling. The OS holds the schedule, fires when constraints
are met, and is responsible for waking enough of Tenshu to run the operation.
Examples on the milestones list:

- Periodic sync drain when no app is open (so a phone left in a drawer still
  picks up new blocks while on charger + Wi-Fi).
- Deferred file upload — user picks a 4 GB video on a metered connection;
  the upload is registered with the platform scheduler, runs when the device
  hits unmetered Wi-Fi.
- Location pings for the eventual maps/location feature.
- Maps tile prefetch.

This layer is the genuinely missing one. The trait that drives it is new (§3.2),
the constraint vocabulary is new (§3.3), and per-platform glue is per-platform
(§5).

---

## 3. Trait shape changes

### 3.1 `BackgroundOperation` — current state stays for Layer 2

Two options were considered: rename to `LongRunningOperation` for clarity, or
keep the existing name and document Layer 2 semantics. The proposal lands on
**keep the name, document the semantics**, for a single reason: the trait is
already referenced across ~40 files (per the Grep that opened this work), and
a rename touches every Service binding. The cost is paid for clarity that
Layer 3 introduces a new trait anyway. If a rename ever does happen, it should
be pulled forward into a separate refactor that does nothing else.

The trait gains documentation but no new fields:

```kotlin
/**
 * Trait that marks an operation as long-running within the proxy process.
 *
 * A [BackgroundOperation] outlives the IPC request that started it but does
 * NOT outlive the proxy process. The runtime contract is:
 *
 * - The operation returns a TaskHandle (per docs/tech-debt.md
 *   "Wire-Level Task Cancellation"), not an immediate TResult.
 * - Cancellation cooperatively unwinds the in-process scope; if the proxy
 *   dies, the operation is gone (durability across proxy death is Layer 3).
 * - It MAY outlive the foreground app: while the task is in flight the
 *   platform keeps the proxy alive *for the task* — an Android foreground
 *   service, the desktop resident daemon (§2.2). It does NOT assume a UI is
 *   open. It DOES still assume no *interactive* user input is needed
 *   mid-run; an op that needs a human in the loop (recovery, pairing
 *   approval) is not a valid Layer-2 background op.
 * - Deferred/periodic execution the OS owns (run later on Wi-Fi, run every
 *   N hours, survive proxy death / reboot) is a different concern: carry
 *   [HeadlessSafe] and schedule via `ScheduleService` (§3.2). That is NOT
 *   the same as the Layer-2 process-liveness mechanism above —
 *   WorkManager ≠ foreground service.
 */
interface BackgroundOperation : Trait { /* unchanged */ }
```

### 3.2 Layer 3 — `HeadlessSafe` marker trait + `ScheduleService`

> **Design revision (2026-05-18, decided with the maintainer before any Phase
> 5 implementation).** Layer 3 is *not* modelled as one `ScheduledOperation`
> trait carrying `ScheduleSpec`/`ConstraintSpec`/`dedupKey`/`RetryPolicy` as
> trait params. That conflated two genuinely different things — *eligibility*
> ("may this op ever run with no user/UI present?", a static, security-
> relevant property of the op *type*) and *a scheduled invocation* ("run
> *this* call with *these* constraints later", per-instance runtime data with
> a lifecycle). A `dedupKey` being a trait param is the tell: it is
> per-invocation runtime data, not op-type schema metadata. The shape below
> separates the two.

**(a) `HeadlessSafe` — a param-less marker trait** (same shape as the existing
`BackgroundOperation` / `TerminalOperation` markers) — **LANDED** (Phase 5
slice 1). Implemented exactly as below plus the `Operation.isHeadlessSafe()`
overload; no `operation(...)` DSL guard couples it to `BackgroundOperation`
(they are orthogonal — see the next paragraph; the §6.4 enforcement is in
`ScheduleService`, not the schema DSL):

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/trait/HeadlessSafe.kt
interface HeadlessSafe : Trait {
    companion object {
        const val ID = "tenshu/core/operation/headlessSafe"
        val Schema: TraitSchema = trait(ID)        // no params
    }
}
fun OperationSchema.isHeadlessSafe(): Boolean = hasTrait(HeadlessSafe.ID)
```

It carries **no** schedule/constraint data. It is the operation author's
static declaration: *this op is safe to dispatch in the §6.4 world — no user
present to answer a prompt, no foreground UI, possibly a cold proxy boot
triggered by the OS scheduler.* No SAS confirmation, no device picker, no
recovery-phrase entry, no "tap approve."

Why a trait and not a runtime check in `ScheduleService`: §6.4 / the §2
user-presence column make "Layer 3 must never trigger recovery / pairing /
UI-required flows" a **hard rule**. A runtime denylist *fails open* — a new
UI-requiring op added later is schedulable until someone remembers to denylist
it. `HeadlessSafe` is an **allowlist by construction**: an op is unschedulable
*unless* its author opted in by adding the trait, so it *fails closed*, and
the complete Layer-3 attack surface is one grep (`isHeadlessSafe`).
`RecoveryClaim` / `ApprovePairingRequest` / `AwaitPairing` simply never carry
it → unschedulable by the type/registration system, not by convention.

`HeadlessSafe` is **orthogonal** to `BackgroundOperation`: the latter is *how
an op runs when invoked* (returns a `TaskHandle`, cancellable in-proxy — Layer
2, landed); `HeadlessSafe` is *whether it may be invoked with no user/UI*. A
deferred upload carries **both**, but they answer different questions and
neither carries schedule data, so the §3.1 "op carries both `BackgroundOperation`
and `ScheduledOperation`" murk is gone.

**(b) The scheduled invocation is data + a service.** **LANDED** (Phase 5
slice 2 — see §8 Phase 5 for placement/scoping decisions). A persisted
`ScheduleItem` composite (op reference + `ScheduleSpec` + `ConstraintSpec` +
`RetryPolicy` + `dedupKey` + lifecycle state: PENDING/RUNNING/DONE/FAILED) is
the unit; "serialized args" is deferred (the op reference is param-less for
now — §8 Phase 5). A `ScheduleService` (Service & Operation pattern, like
`FileSystem`/`SyncService`, implemented in the `:sync-service` family) owns it:

```
ScheduleService:
    schedule(opRef, ScheduleSpec, ConstraintSpec, RetryPolicy, dedupKey) -> ScheduleItem
    listScheduled() -> [ScheduleItem]          // the tray's data source
    cancelScheduled(dedupKey)
```

`ScheduleService.schedule(...)` rejects an `opRef` whose schema is not
`isHeadlessSafe()` (defense in depth on top of the registration-time check —
opRefs can be wire-deserialised / dynamic). The persisted `ScheduleItem` set
*is* the `BackgroundJobRegistry` of §6.3 (which this absorbs): the in-process
loop drains it when the proxy is alive; the per-platform `PlatformScheduler`
(§5) drains it when an OS wake fires. One implementation, two drivers.

### 3.3 Constraint vocabulary

These are the fields of the `ScheduleItem` composite / the arguments to
`ScheduleService.schedule(...)` (§3.2(b)) — per-invocation runtime data, **not**
trait params.

The vocabulary is the union of what the four target platforms can express,
trimmed to constraints that genuinely map across all of them. Per-platform
extensions live next to the platform-specific scheduler binding; cross-platform
operations stick to the core set.

```
ScheduleSpec:
    kind: ONE_SHOT | PERIODIC
    minPeriodMs: Long?      // PERIODIC only; floor on tick interval
    flexPeriodMs: Long?     // PERIODIC only; window the OS can shift within
    initialDelayMs: Long?   // ONE_SHOT and PERIODIC; minimum wait before first run

ConstraintSpec:
    network:    ANY | UNMETERED | NONE
    power:      ANY | CHARGING
    idle:       ANY | DEVICE_IDLE
    storage:    ANY | NOT_LOW
    battery:    ANY | NOT_LOW
    maxDataMb:  Long?    // monthly cellular-data ceiling; null = no cap

RetryPolicy:
    maxRetries: Int
    backoff:    LINEAR | EXPONENTIAL
    initialBackoffMs: Long
```

`dedupKey` is a string that the platform scheduler uses to merge enqueueings.
"Sync drain" registers with `dedupKey = "sync-drain"`; calling it ten times in
ten seconds results in one scheduled run. `"upload:<file-id>"` keys each upload
to its file so cancelling one upload doesn't tear down the rest.

The §5.6 mapping table shows how each constraint translates to the four target
platforms and which platforms cannot honour which.

`maxDataMb` is the consumer for `ControlPreferences.MaxMonthlyDataMb` (see
`docs/proposals/core-preference-service.md` §5.1 / §10.1): the realm-wide
"monthly data limit" preference resolves *into* this field at `schedule(...)`
time, the same way `ControlPreferences.WifiOnly` resolves into
`network = UNMETERED`. **Both halves now exist in code:** `ConstraintSpec`
carries an optional `maxDataMb` (`TInteger`) field, and the
preference→`ConstraintSpec` resolver (`backgroundSyncConstraints()` in
`:control-shared`) is landed + tested (preference-service Phase 5). The
**call-site mechanism** is also landed (test-only): `BackgroundSyncScheduler`
(`:sync-service-proxy`, the registration counterpart to `ScheduleDrainLoop`)
reconciles a single dedup-keyed periodic `ScheduleItem` with a
`Flow<ConstraintSpec>` — a toggle reschedules in place. What remains for
*enforcement* is below — the per-platform scheduler binding (Phase 6), a real
head-less sync-drain op (needs the Phase 1 `SyncScheduler` extraction, since its
drain methods are `internal`, not operations), and wiring `BackgroundSyncScheduler`
into production init (deferred to avoid double-draining against the legacy
`SyncScheduler` poll). **The `SyncScheduler`-vs-`ScheduleService` roles, the
per-platform enforcement model, and the cheap first step (Path A: gate the
in-process `SyncScheduler` on the constraint) are decided in
[`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md).** Unlike `network`,
no platform scheduler exposes a native
"max bytes" constraint, so it is enforced **above** the scheduler rather than by
it:

- **Android:** the `WorkManagerScheduler`/`TenshuOperationWorker` (§5.2) keeps a
  per-realm rolling monthly byte counter; a scheduled run that would exceed
  `maxDataMb` returns `Result.retry()` (deferred) instead of draining, until the
  month rolls over or the device is back on unmetered network. WorkManager has
  no `maxBytes` analogue, so this is app-level bookkeeping.
- **Desktop / iOS / web:** no metered-data accounting in v1 — `maxDataMb` is
  recorded but ignored. Desktop is assumed unmetered; the other targets revisit
  when their schedulers land.

`maxDataMb = null` (the absence of a cap) is the default and imposes no
bookkeeping.

---

## 4. Layer 1 — Subscriber-driven sync

### 4.1 Stream subscriber lifecycle as a sync trigger

`StreamInteractor` already tracks the live `streamId`s. The proposal adds an
aggregator on the proxy side — `SyncableTypeSubscriberCounter` — that
maintains a `Map<TypeId, Int>` from syncable composite types to the count of
streams currently observing data of that type. Increment on `Stream` start,
decrement on `EndStream`, with the type derived from the stream's underlying
`TList<T>` element type.

The new component is a `TComponent` like `BackgroundScopeProvider`, registered
once per proxy. Consumers (`SyncScheduler` and friends) subscribe to a Flow of
"hot types changed" and adjust their loop accordingly.

This is the only piece in §4 that genuinely requires new code in `:core` /
`:core-common`. Everything else either already exists or is a tweak inside
`:sync-service-proxy`.

### 4.2 HLC-tip gating

`SyncScheduler.onPeerTip` already accepts signed peer tips returned by
`GetChanges`. Today they're discarded by the production wiring. The proposal
adds a `HlcDivergenceTracker` (already gestured at in `sync-backend-relay-plan.md`
§5.1) whose responsibility is to:

1. Verify the signature on each peer tip (the relay can lie about the value;
   the device that signed it cannot).
2. Track the latest peer tip per known device.
3. Expose `hasPendingChanges(): Boolean` — `true` iff any peer's signed tip
   exceeds our local HLC.

`SyncScheduler` polls `hasPendingChanges()` before issuing a `pullChanges`. If
no peer has advanced, the pull is skipped. This collapses the cost of "tick
ran but nothing was new" from one round trip to zero.

### 4.3 Hot-mode cadence — immediate triggers, fast short-poll, then long-poll

When the hot-set is non-empty, three things change relative to today's loop:

1. **Immediate pull on first subscribe.** The empty → non-empty transition
   fires a pull right away, not at the next periodic tick. New `JobTrigger`
   `STREAM_ACTIVE` (§6.3) is an edge trigger, not a level one — the registry
   dispatches `SyncDrainJob` synchronously on the transition. Without this,
   "user opens folder view → sees stale state for up to one tick interval"
   regardless of how fast the steady-state cadence is.

2. **Immediate drain on local save.** `SyncObserver.onDataSaved` already
   enqueues into `PENDING_BLOCKS`; today the bytes sit there until the next
   `SyncScheduler` tick. The fix is a one-line `SyncObserver` change to kick
   `drainPendingBlocks()` directly on enqueue (debounced if necessary; see
   §6.3 dedup). Symmetric to (1) — without it, *outbound* latency is bounded
   by the periodic tick even when pull latency is sub-second. Independent of
   any pull-side change; can ship in Phase 1.

3. **Steady-state cadence while hot.** Two viable shapes, ship in order:

   - **Phase 4a — fast short-poll.** While the hot-set is non-empty, drop
     the periodic interval to 1–3 s. No relay change, no held connections,
     no statefulness shift — purely a `SyncDrainJob` knob keyed off the
     hot-set. Combined with HLC-tip gating (§4.2), most fast ticks
     short-circuit at "no peer advance" and cost nothing beyond a small
     signed-tip exchange.

   - **Phase 4b — long-poll (cost mitigation).** Once
     [`sync-relay-api.md`](sync-relay-api.md) Phase 5 lands, `pullChanges`
     can become "issue a request that hangs server-side until either a peer
     tip advances or a 60s server-side timeout elapses." `SyncDrainJob`
     keeps one long-poll in flight while hot; cold-mode falls back to the
     periodic HLC-gated tick (or stops entirely). User-facing latency at
     this point is unchanged from Phase 4a — long-poll's value is reducing
     mobile radio wakeups and idle relay JWT-verify cost, not feature
     latency.

The relay-side cost of long-poll is one held HTTP connection per active
client, which the relay design was already prepared to support (it's the
Phase 5 design's whole point). Phase 4a does not need any of that.

### 4.4 Block-size budget — sender-side concern, flagged here

Layer 1's cost model assumes blocks are small enough that draining them in the
foreground is cheap. If a single block can be megabytes — file content, large
folder snapshots, big notification payloads — then the cost of "Stream
subscribed, drain hot" degrades from "one round trip + a few KB" to "one round
trip + tens of MB + decrypt time." That's a Stream subscription cost the user
does not expect.

The mitigation is sender-side: cap block size at the SyncBlock-emit boundary
and split large operations into multiple blocks. A separate decision (out of
scope here, see §7.1) is whether file content lives in the block log at all —
the current `UploadFile` schema takes `byteArray` directly, so the bytes are
in the operation flow, but where they end up on the wire is something this
proposal does not try to settle.

#### Confirmed on Android (2026-05-19): the predicted OOM + a restart-storm amplifier

The §5d Android smoke (large background folder upload) reproduced exactly
this hazard, fatally. Code trace:

- **Disease (this §4.4 / §7.1, now concrete).** `UploadFileImplementation
  .uploadFile` holds each file's whole content as a heap `ByteArray`,
  copies it (`toTByteArray`), **and** `SyncObserver.emitPropertyBlock(value
  = bytes)` enqueues the *entire file content* as a property block into the
  DB-backed `pendingBlocks` array. `SyncScheduler` drains it with
  `database.getArrayData(pendingBlocksRef).toList().forEach { … }` — i.e.
  it **materialises the whole pending backlog into heap on every tick**.
  When relay push lags bulk-upload throughput (near-certain for a folder of
  photos; guaranteed if the relay is slow/unreachable), `pendingBlocks`
  grows one full-file block per file and each tick re-materialises the
  entire backlog → Control Java heap (Android ~192 MB) is exhausted in
  proportion to bytes uploaded → the OS OOM-kills the Control process.
- **Amplifier.** `ProxyService` has no `onStartCommand` → default
  `START_STICKY`, and it is a foreground service + the bound proxy → after
  the OOM-kill Android **recreates it**. `ProxyService.onCreate` (main
  thread) lazily resolves the Koin proxy graph → `SyncManagerImpl`'s
  constructor legacy auto-load → `deriveKeys` → **Argon2id m=64 MiB on the
  main thread** while the upload immediately resumes pressuring the fresh
  process → re-OOM → kill → restart: a self-sustaining storm. The Argon2
  parameters are frozen by threat-model **T-RC-02** (a protocol-version
  constant) and are *not* the bug — the bug is *when/where/how often* that
  64 MiB memory-hard KDF runs.

**Mitigation taken (amplifier only — branch `fix/control-oom-folder-upload`,
2026-05-19):** the `SyncManagerImpl` constructor *legacy auto-load* is
deferred onto a background dispatcher (off the main thread / off
`ProxyService.onCreate`'s critical path). Explicit `initialize()`
(CreateRealm / RecoveryClaim) stays synchronous; readers of
`derivedKeys`/`currentOwnerId`/`currentMasterKeyPair` already tolerate the
brief not-yet-initialised window (`?: return`, `isEnabled() == derivedKeys
!= null`, `SyncScheduler` ticks). This removes the ANR + the
`onCreate`-blocking 64 MiB spike and breaks the *storm* once the disease no
longer OOM-kills Control. **Option A (persist derived keys / master keypair
to skip re-derivation) was rejected**: it is an asset-A1 at-rest change
deviating from T-K-04 ("held in memory only … re-derived on next launch")
and would need a threat-model amendment for no benefit once the disease is
fixed. The amplifier fix is a stabiliser, **not** the cure.

**The cure is still the §4.4 / §7.1 work** (cap block size at the emit
boundary and/or route file bytes via a separate ciphertext blob channel so
the in-foreground `pendingBlocks` backlog is bounded, and so the scheduler
drain does not `.toList()` the whole backlog into heap). That remains the
sized, deferred piece; the Android smoke just proved it is a hard v1
blocker for folder upload, not a theoretical Layer-1 cost-model concern.

### 4.5 What does NOT change

To keep the threat model honest, the proposal explicitly does **not** change:

- **Block opacity at the relay.** The relay sees ciphertext SyncBlocks. There
  is no per-type channel, no per-collection notification, no shape leakage.
- **Pull granularity.** When the proxy decides to pull, it pulls every block
  available since its last cursor — there is no "only fetch File-typed blocks."
- **Block schema.** `SyncBlock` carries the same fields it does today.
- **Reactive emit fanout.** Once blocks are applied to the local DB, the
  existing notification → Stream emit machinery handles per-type fanout for
  free.

The change is entirely in **when and how often** the pull runs, not what comes
back from it.

### 4.6 Bulk-drain liveness — the metered-deferral gap (confirmed on-device 2026-07-11)

§4.4 covered the *heap* hazard of a large upload. This covers the *liveness*
hazard, which is distinct and — with the content-blob channel now landed (§7.1
partly cured; the drained blocks are small metadata/property/thumbnail blocks,
not megabyte content) — is the failure that actually bites a folder upload today.

**Observed.** A 95-file folder uploaded on an Android phone produced a backlog
of ~770 small `SyncBlock`s. On-device tracing (`SyncScheduler` drain + relay
push, temporary instrumentation) showed every push succeeding (`HTTP 200`) —
push, auth, quota all healthy — yet the folder never reached the paired desktop,
and the desktop tray showed *"Sync issue detected."* Two compounding causes:

1. **The drain is Layer 1, so it has no liveness contract.** The block drain is
   the fixed-tick `SyncScheduler` loop (§1.3) running on `sessionScope`. It is
   **not** a Layer-2 task: it acquires no `TaskHandle`, registers nothing in the
   proxy's in-flight count, and therefore does **not** hold the Android
   foreground service (which `ForegroundTaskLiveness` drives *only* off
   `proxyGateway.inFlightCount`). A ~770-block drain takes ~77 s of sequential
   pushes; nothing keeps the process alive for it. When the upload's in-flight
   *request* work finishes and Drive backgrounds/unbinds, `ProxyService.onDestroy`
   → `proxyGateway.shutdownAndJoin()` → `sessionScope.cancel()` cancels the drain
   mid-pass. Pushed blocks are dequeued; the rest stay queued.

2. **The only durable fallback is throttled and metered-gated.** The wired
   Layer-3 `BackgroundSyncScheduler` (`DrainSync`) is a WorkManager *periodic*
   job at **1 h** with an **`UNMETERED`** constraint. The test phone was on 5G
   with the realm's **Wi-Fi-only** policy on, so the fallback **never fired** —
   confirmed: toggling Wi-Fi-only off drained the backlog immediately. So an
   orphaned backlog waits up to an hour, on Wi-Fi only — effectively forever on a
   metered-primary device.

**The Task API already formalises the fix — this is a layering bug, not a missing
mechanism.** "Belt and suspenders" maps exactly onto the existing contracts:

- **Belt = Layer 2.** §2.2 names *"File upload progress"* and *"start now,
  survive the app closing — needs **only** the foreground service"* as the
  canonical Layer-2 case. The runtime landed (Phase L2 / L2-LIVE): a
  `BackgroundOperation` returns a `TaskHandle`, and while it is in flight the FGS
  stays up via the *existing* `inFlightCount → ForegroundTaskLiveness` path. So
  the bespoke idea "hold the FGS while `pendingBlocks` is non-empty" is just
  *"the bulk drain is a Layer-2 task."*
- **Suspenders = Layer 3.** `DrainSync` survives process **death** (swipe-kill /
  OOM). It should also gain an **expedited one-time** kick on backlog-appears
  (WorkManager `setExpedited`), not only the hourly periodic, so an app-closed
  backlog drains promptly instead of on the next hour.

**Decision D-DRAIN-1 — what is the Task's lifetime?** Two shapes:

- **(a) Task = the user action.** `UploadFile` / a folder-upload op stays in
  flight until *its* emitted blocks have drained. Truest to the Task model; gives
  natural per-upload progress ("syncing 320/770") and cancel. Cost: the op must
  hold a handle across the async drain of blocks it emitted — a non-trivial
  coupling between the emit boundary and drain completion.
- **(b) Task = the queue state.** A single standing "drain task" is in flight
  whenever `pendingBlocks` (or any per-realm outbound queue) is non-empty,
  independent of what enqueued the blocks. Simpler; covers *every* backlog source
  (tombstones, shared-realm drains, offline-keeper), not just uploads. Cost:
  coarser progress (a total count, not per-upload).

**Recommendation: (b) as the liveness primitive, with (a) layered on for
progress.** (b) is the minimal correct fix — a `pendingBlockCount > 0` signal
folded into the same liveness source the FGS already consumes — and it closes the
gap for all producers. (a) is an additive refinement once streaming `UploadFile`
lands (§7.1) and wants real progress UI. This also composes cleanly with §6.3
(`SyncScheduler` should register through the `BackgroundJobRegistry`): the drain
becomes a registered job whose *in-flight-ness* is exactly "backlog non-empty."

**Metered interaction is the load-bearing subtlety.** The belt must respect the
same Wi-Fi-only policy as the suspenders, or a metered device holds a foreground
service open forever trying to push under a policy that forbids the push. So the
liveness signal is **"backlog non-empty AND the active `SyncGate` currently
permits push"** — on metered with Wi-Fi-only on, the correct state is *deferred*
(no FGS, no busy-loop), surfaced to the user as a benign "waiting for Wi-Fi" (see
§5.3), **not** an error and **not** a pinned foreground service.

---

## 5. Layer 3 — Platform schedulers

### 5.1 The shared abstraction

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/scheduler/PlatformScheduler.kt
interface PlatformScheduler {
    fun register(operationId: String, schedule: ScheduleSpec, constraints: ConstraintSpec, dedupKey: String, retryPolicy: RetryPolicy)
    fun cancel(dedupKey: String)
    fun cancelAll()
}
```

One implementation per target, **bound through Koin** via an `expect fun
schedulerModule(): Module` seam (mirroring `cryptoBackendsModule()`), *not* an
`expect class` — see §8 Phase 6 for the rationale (per-platform impls take
different injected deps). The proxy's `ScheduleService` registry
(§3.2(b)/§6.3) calls `register` for each persisted `ScheduleItem` (whose op is
`HeadlessSafe` by construction). When the OS wakes the platform-specific
worker, the worker's job is to bind back to the proxy and dispatch the
operation by ID through the existing operation-execution surface.

**This section covers two distinct per-platform mechanisms.** `PlatformScheduler`
above is the Layer-3 one (the OS holds a schedule, fires on constraints). But
Layer 2's contract also has a per-platform piece — the *process-liveness*
mechanism from §2.2 that keeps the proxy alive *while an in-flight task runs*.
It is built from the same platform components, so it is documented per platform
here too. Keep the two cleanly separated when reading §5.2/§5.3: the foreground
service / always-on daemon is **Layer 2 process liveness for work happening
now**; WorkManager / the in-daemon schedule loop is **Layer 3 deferred or
periodic triggering**. They are orthogonal and compose only in the
deferred-resilient case (§2.2).

### 5.2 Android — foreground service (Layer 2) + WorkManager (Layer 3)

**Layer 2 process liveness — the foreground service.** Control already runs as
a bound service that `drive` binds to over AIDL. The gap §2.2 names: when the
user starts an upload and then swipes Drive away, nothing keeps Control's
process from being reclaimed mid-task. The mechanism is a **foreground
service** — Control promotes itself to the foreground (a persistent
notification: "Tenshu — uploading 3 files…") for as long as ≥1 Layer-2 task is
in flight, and demotes back to a plain bound service when the in-flight count
returns to zero. Concretely this is a small `ForegroundTaskLiveness` component
in `:control-android` driven by the same in-flight registry the wire-level
`TaskHandle` work already maintains in `ProxyGateway` (`(clientId, taskId) →
Job`, landed in Phase L2): registry non-empty → `startForeground(...)`;
registry empties → `stopForeground(...)`. No `ScheduleItem`, no WorkManager, no
`HeadlessSafe` — a Layer-2 task is user-initiated and runs *now*; the only
thing missing today is the OS permission to keep running, which the foreground
notification buys.

This is **not** WorkManager and must not be conflated with it. WorkManager
(below) decides *when or whether to (re)start* deferred/periodic work; the
foreground service keeps a *currently running* task's process alive. They
compose only in the deferred-resilient case (a Layer-3 `ScheduleItem` whose
op, once triggered, itself runs long enough to want foreground liveness); the
common "start now, survive the app closing" upload path needs **only** the
foreground service.

**Layer 3 scheduling — WorkManager + bind to Control.**

The `actual` is a `WorkManagerScheduler` that translates `ScheduleSpec` +
`ConstraintSpec` into `WorkRequest` (one-shot) or `PeriodicWorkRequest`
constructions. The `Worker` class is `TenshuOperationWorker`, which:

1. Binds to Control via the existing AIDL surface (`:core-aidl` /
   `:control-shared`'s `PlatformProxyModule`).
2. Dispatches the operation by ID through Control's `ExecutionContext`.
3. Returns `Result.success()` / `Result.retry()` based on the
   `TResult`.

Two pieces of nuance:

- The work runs **inside Control**, not inside the worker process. WorkManager
  wakes the worker, the worker wakes Control by binding, and Control runs the
  op. This keeps the key-availability story trivial: keys are wherever they
  always are. The worker process doesn't need any of Tenshu's crypto state.

- Control's process must allow itself to be woken from a Worker. The current
  AIDL bind-on-demand path already supports that — `drive` does it today —
  but the PR landing this should add a regression test for "Worker binds to
  Control, runs an op, returns success."

`ConstraintSpec.network = UNMETERED` maps to
`Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)`.
`power = CHARGING` maps to `setRequiresCharging(true)`. Full table in §5.5.

### 5.3 Desktop — Control as daemon + tray icon

Two pieces, both new and load-bearing.

The desktop daemon serves **two distinct roles** — the same component, two
contracts. (1) **Layer-2 process liveness:** it stays up so a user-started
in-flight task (an upload) survives the Compose window closing — the desktop
analogue of Android's foreground service. (2) **Layer-3 scheduler host:** it
is the always-on process the in-daemon schedule loop runs inside. Unlike
Android — where these are two separate OS mechanisms (foreground service vs
WorkManager) — desktop collapses them into one always-resident process, so
both roles are satisfied by the same daemon. Before the daemon ships, a
Layer-2 task whose owning window closes simply dies (the §2.2 "does not
survive proxy death" line); the daemon is what closes that gap on desktop.

**Daemon mode.** `:control-desktop` today is a Compose Desktop app — start it
manually, it runs while the window is open. For either role above to mean
anything on desktop, Control needs to start at user login and stay running.
That means:

- macOS: a `LaunchAgent` plist installed at first run, registering Control
  with `launchd` to start at login and restart on crash.
- Linux: a `systemd` user-unit, generated by Control at first run, enabled
  via `systemctl --user enable`.
- Windows: a Task Scheduler entry (`Logon` trigger + `Run whether user is
  logged on or not`).

The first-run installer for these is out of band — it lives in Control's
installer, not in a runtime component. Control itself can detect "I am not
registered as a daemon" and prompt; that prompt is part of the onboarding
flow.

`PlatformScheduler` on desktop translates `ScheduleSpec` into a coroutine
loop on `serviceScope` — the daemon is alive, so the scheduling is
in-process and trivially expressible. The OS-level scheduling is
"Control is always running"; the per-operation scheduling is internal. The
Layer-2 liveness role needs *no* additional desktop mechanism — once the
daemon is resident, an in-flight `TaskHandle` job simply keeps running on
`serviceScope` after the window closes; there is no foreground-notification
equivalent to build, because desktop has no per-task process-reclaim pressure
to defend against.

**Tray icon.** Compose Desktop has `androidx.compose.ui.window.Tray`. Control
gets a tray icon with four states:

```
Tenshu
├─ ● Idle
├─ ◐ Syncing
├─ ⚠ Divergence detected
└─ ⏸ Paused
```

The tray menu shows: recent activity (last N Drive operations), pause/resume
sync, "Open Drive", "Open Control" (the existing UI), and a settings link.
The icon is the natural surface for `SyncManager.divergenceWarning` —
[AGENTS.md](../../AGENTS.md) calls out that UI rendering of the warning is
the downstream piece for the relay-plan §3.1 work, and a persistent ambient
indicator is a better surface than a transient toast.

The icon is also the user's discovery point for the daemon. "Why is Control
running when Drive is closed?" → click → "Tenshu is keeping your devices in
sync. [Pause sync] [Quit Tenshu]." That makes the daemon's existence
legible rather than spooky.

#### 5.3.1 Status semantics — a policy-deferred state is not an error

The 2026-07-11 incident (§4.6) surfaced a UX bug as much as a liveness bug: the
tray showed **"Sync issue detected"** for what was actually a *benign,
user-configured deferral* (Wi-Fi-only, on a metered network). Two things were
wrong — the `DIVERGENCE` state fired on a false-positive zero HLC tip (fixed
separately by the empty-tip guard in `HlcDivergenceTracker`), and, more
structurally, **the tray had no vocabulary for "waiting by policy" so it fell
through to a scary warning.**

How the incumbents handle this — the consistent pattern across OneDrive, Dropbox,
and Google Drive for desktop is **three visually-distinct semantic tiers**:

1. **Healthy** — "Up to date" (green check / solid icon).
2. **Working *or* paused-by-policy** — "Syncing 320 of 770", "Paused",
   "Waiting for Wi-Fi", "Paused — on a metered network". Neutral/informational
   (grey or blue), **never** red. Always carries a *reason* and an *escape
   hatch* ("Resume", "Sync anyway").
3. **Genuine error needing action** — auth expired, quota full, unresolvable
   conflict. Red/attention, actionable.

OneDrive is the closest analogue: it has a first-class **Paused** state (manual,
and auto on metered/battery-saver) rendered grey with "Your files aren't
syncing" + a Resume button — categorically separate from the red "sync problem"
badge. The load-bearing principle: **reserve the attention colour for things the
user must fix; a deferral is tier 2, with a reason and a way out.**

**Two surfaces, two messages — the deferral is self-observed on the phone, only
inferred on the desktop, so the copy differs by surface.** This is the crux: the
device that is *withholding* the push knows exactly why and can act on it; the
device *waiting* for the data cannot. Splitting the messaging along that line is
what makes each surface honest.

- **Phone (the deferring device) — actionable, and nearly free to build.** The
  existing `SyncHealthStatus` (Control→Drive signal, `drive-connectivity-service.md`)
  is single-row, proxy-authoritative, and **already carries
  `pendingBlockCount`** plus a `connectivity` enum (`online` / `offline` /
  `needs_attention`). Add a **`deferred`** connectivity value (or a reason field),
  derived when the active `SyncGate` withholds push on a metered network with a
  non-empty backlog. Drive's existing sync-health banner then renders *"Waiting
  for Wi-Fi — N items"* — **N is `pendingBlockCount`, already present** — plus a
  *"Sync now over cellular"* action (a one-shot `SyncGate` override) and a deep
  link to the Wi-Fi-only setting. Self-observed, no cross-device dependency: a
  one-value extension of a shipped mechanism.
- **Desktop tray (the observer) — informational.** The desktop cannot see the
  phone's `SyncGate`; it only notices the phone's data is behind. So its message
  is tier-2 *"Waiting for phone…"* / *"{device} hasn't synced recently"* with **no
  action** (the desktop can't toggle the phone's Wi-Fi). The tray's own
  `SyncStateCode` (`DISABLED / LOCKED / PAUSED / DIVERGENCE / OFFLINE / SYNCING /
  SYNCED`) gains a `DEFERRED` value too, but that applies only to the *desktop's
  own* deferral (rare — desktop is usually wired). `DIVERGENCE` stays red but rare
  (empty-tip guard ⇒ only genuine signature-mismatch/regression) and should be
  reworded from *"Sync issue detected"* to say what to do
  (*"Sync verification failed — {device} may need re-pairing"*).

**Making the desktop *specific* ("Phone is waiting for Wi-Fi") needs a published
signal — and there's a deadlock to dodge.** For the tray to name the reason
rather than a generic "behind," the phone must publish its deferral as a synced
`DeviceSyncStatus` (device → {syncing, deferred(reason, count), paused, idle}).
**Load-bearing subtlety:** that record rides the *same* gated push, so a
metered-deferred phone cannot announce *"I'm deferred on metered"* unless the
`DeviceSyncStatus` heartbeat is **exempt from the Wi-Fi-only gate** (a few bytes,
always allowed) or is flushed **once** on entering the deferred state, before the
gate closes. Metadata-only, no relay/wire-shape change, but its own slice —
deferred. Absent it, the desktop's honest fallback is inference from peer-tip
staleness: *"Waiting for phone"* as tier 2, **never** the red warning. The
principle holds regardless: show *this* device's state accurately (actionably on
the phone), and stay calm — not alarmed — about peers.

### 5.4 Web — Service Worker + Background Sync API

Web is the most architecturally different target and has the most TODO. The
shape:

- The apex registers a Service Worker (one per origin per
  [`docs/web/architecture.md`](../web/architecture.md) §13).
- Layer 3 ops are registered with the SW via the Background Sync API
  (`registration.sync.register('sync-drain')`). Periodic Sync is Chrome-only
  and used opportunistically — fall back to one-shot Background Sync
  (registered after every relevant user action) on browsers without it.
- The SW, on `sync` event, runs the operation. The catch: the SW does not
  share memory with the apex tab, so any in-memory proxy state is gone.
  Keys and persisted state must come from `IndexedDB`.

This means a meaningful chunk of `:tenshu-sdk-js`'s in-process runtime
(`JsRuntime`, `ExecutionContext`, `SchemaRegistry`) needs to compile into a
SW-targetable bundle, separate from the apex bundle. That's a non-trivial
KMP→JS slice. The proposal flags it but does not commit to a Web-side
implementation in the same phase as Android — see §8 phasing.

### 5.5 iOS — BGTaskScheduler, blocked on iOS architecture

iOS is included for completeness and to set scope expectations. The iOS app
architecture is not yet designed
([`docs/protocol/ios-ipc.md`](../protocol/ios-ipc.md) is the IPC spec, not
an app design); BGTaskScheduler runs in the host app process, which means
Control's iOS equivalent must be in-process with the consuming app rather
than a separate IPC daemon. The interface (`PlatformScheduler`) is the same;
the implementation lands when iOS does.

### 5.6 Constraint mapping table

| Constraint            | Android (WorkManager)      | Desktop (in-daemon)             | Web (Background Sync)                   | iOS (BGTaskScheduler)                           |
|-----------------------|----------------------------|---------------------------------|-----------------------------------------|-------------------------------------------------|
| `network = UNMETERED` | `NetworkType.UNMETERED`    | Detected via OS API; loop guard | `requiresExplicitConfirmation` indirect | `requiresNetworkConnectivity` (no metered hint) |
| `network = ANY`       | `NetworkType.CONNECTED`    | always                          | always                                  | `requiresNetworkConnectivity = true`            |
| `network = NONE`      | `NetworkType.NOT_REQUIRED` | always                          | n/a (SW needs net)                      | `requiresNetworkConnectivity = false`           |
| `power = CHARGING`    | `setRequiresCharging`      | OS power API; loop guard        | not expressible                         | not directly expressible (use BGProcessingTask) |
| `power = ANY`         | default                    | default                         | default                                 | default                                         |
| `idle = DEVICE_IDLE`  | `setRequiresDeviceIdle`    | OS idle API; loop guard         | not expressible                         | indirect via BGProcessingTask                   |
| `storage = NOT_LOW`   | `setRequiresStorageNotLow` | OS API; loop guard              | not expressible                         | not expressible                                 |
| `battery = NOT_LOW`   | `setRequiresBatteryNotLow` | OS API; loop guard              | not expressible                         | not directly expressible                        |
| `kind = PERIODIC`     | `PeriodicWorkRequest`      | timer in `serviceScope`         | Periodic Sync (Chrome only)             | BGAppRefreshTask (rate-limited)                 |
| `kind = ONE_SHOT`     | `OneTimeWorkRequest`       | `delay` then dispatch           | one-shot Sync                           | BGTaskScheduler one-shot                        |

Constraints not honourable on a given platform are best-effort: the operation
runs when the platform's scheduler permits, and the scheduler logs that the
constraint was relaxed.

---

## 6. Cross-cutting concerns

### 6.1 Key availability without user presence

Scheduled ops on Android, Desktop, and iOS run with the same key access as
foreground ops, because the work executes inside the same process where keys
already live. Web is the exception: the Service Worker has its own context,
and unwrapped device keys must be loadable from `IndexedDB` without the apex
tab. This is feasible — keys are already at-rest encrypted with an OS-derived
key — but it does mean the SW slice needs the same crypto-init path as the
apex.

What scheduled ops cannot do, on any platform: trigger a recovery flow, a
pairing approval, or any operation that requires the user to type a phrase
or confirm on another device. The threat-model amendment in §6.4 makes that
a hard rule; the runtime check is per-op (the op declares whether it's safe
to run without user presence; ops that require presence are rejected from the
scheduled-context dispatch).

### 6.2 Cancellation across process boundaries

Already on the tech-debt list under "Wire-Level Task Cancellation"; Layer 3
makes it pressing. Concrete cases:

- **Device revocation.** `RevokeDevice` lands → the revoked device's
  scheduled queue (e.g., a queued upload) must be torn down, not just the
  in-process job. The platform scheduler's `cancelAll()` is the lever; the
  revocation flow calls it before the device's keys become unusable.
- **Per-op cancellation from foreground.** User cancels a queued upload
  from the tray menu; `PlatformScheduler.cancel(dedupKey)` removes the
  pending work even if the proxy isn't currently running it.

Cancellation semantics for Layer 3 are weaker than Layer 2's: cancelling a
scheduled op is "the scheduler will not start it" rather than "running work
is interrupted." If the op is already mid-execution, the in-process Layer 2
TaskHandle path is what's being cancelled, and Layer 3 cancellation
degenerates into the existing in-process cancel.

### 6.2.1 Cancellable reads — a read its caller abandons must stop in Control (2026-10-01)

**The defect** (measured on a device, `photos-app.md` §37): the Photos feed's paging
source starts a first-page read, then a second when Control confirms it is observing; the second
cancels the first on the client, and Control **kept executing the cancelled read to the end,
answering 1.9–8.2 s later**, beside every other read. Every screen that cancels reads pays it — a
superseded paging generation, a screen closed mid-load, a retry.

**Where the cancel is lost — two places, and fixing either alone fixes nothing:**

1. **The client never tells Control.** A plain IO call is `TaskTransport.call()`. On Android that is
   `withContext(Dispatchers.IO) { proxy.call(request) }` around a **blocking Binder transaction**:
   no coroutine cancellation reaches a thread inside a transaction, and `withContext` waits for its
   block, so the caller cannot even unwind until Control answers — and nothing is ever sent. On
   desktop a correlated call (§2.2 of `desktop-ipc.md`) frees its waiter at once, but likewise
   sends nothing. The only cross-process cancel that existed was Layer 2's `cancel(taskId)` (§8
   Phase L2), which only a task-id-carrying call can use — and a task id is not free (below).
2. **Control could not stop the walk even if told.** `ProxyGateway` evaluates each request in a
   `requestScope`, and cancelling its job unwinds *suspension points* only. A read is almost none:
   `evaluateCallstack` is a plain function, and `GetAll`'s `toList()`, a filter walk and the
   ordered-window fusion are plain loops over a row `Sequence`. A cancelled job let the walk run to
   the last row, and the reply was still serialised (and on Android, tokenised into shared memory).

`CancellableReadTest` reproduces both on the JVM against the real gateway — the desktop half over the
real `UnixSocketServer`/`UnixSocketTaskTransport`, the Android half over `BinderShapedTransport`, a
thread blocked in the call exactly like a Binder transaction. With the transport half removed the
Binder caller cannot unwind (3 s timeout) and the socket proxy walks 85 more rows in 500 ms; with the
cooperative half removed the cancel arrives and the proxy *still* walks 82 more rows in 500 ms. The
second is the trap: a suspending stand-in for the read would have passed against it.

**The decision — a cancellable read is a distinct, opt-in call kind** (option (a) of the brief,
plus (c) inside it):

- ⚠⚠ **Opt-in, never the default.** A plain IO call is not stopped by its caller's cancellation on
  any transport, and in-process an `AsyncReference` runs on `sessionScope` — so "a sent call
  completes" is the platform's actual contract, and code relies on it: a save launched from a screen
  that closes a moment later still lands. Nothing in the callstack says "read" (Mia's MQ-D1 makes
  the same point: read-only is enforced, never inherent), so the proxy cannot tell which calls are
  safe to stop. The caller can: `cancellableReads { … }` (`:core`) marks every IO call in its block,
  and `DefaultInteractorProvider` routes them to `CancellableReadTaskExecutor.executeAsyncCancellableRead`.
  A `withTask` element wins over it. `socket_aPlainCallIsNotStoppedByItsCaller` and
  `CancellableReadsRoutingTest.aPlainIoCallStaysPlain` pin the default.
- ⚠⚠ **Not a task.** Routing reads through `callCancellable(taskId)` would have needed no new wire,
  and is wrong: a task id makes `ProxyGateway.call` detach the request from the client's session,
  count it towards `inFlightCount` (Android's foreground-service promotion — every listing scroll
  would promote Control) and name a sync lane after it. The gateway therefore keeps a **second
  registry** — `(clientId, readOwner, readId) → job + RequestCancellation`, its own lock, no
  observable count — and a read is otherwise byte-for-byte a plain call.
- **Cooperative checkpoints** (option (c)). `RequestCancellation.checkpoint()` runs per row at the
  `ExecutionContext` row seams (`getArrayData`, `getArrayDataWindow`, `resolveList` — every walk,
  fused or not, reads through them) **and inside the store's materialisation**: ⚠⚠ `DatabaseImpl`
  reads and deserialises a stored array *eagerly, under the database lock*, before it returns a
  `Sequence`, so a check on that sequence alone fires only after every row has been read. The checkpoint is therefore passed down (`Database`/`ReferenceResolver`
  `getArrayData(…, checkpoint)`) and called before each member is fetched. A throw there is safe:
  every `DatabaseLock` releases in `finally`, the read writes nothing, the half-built list is a local,
  and the store keeps only whole rows it read through. `CancellableStoreReadTest` pins it on
  `SqliteObjectStore` + `ReentrantLockDatabaseLock` (red without it: 80 more rows read in 500 ms
  after the cancel), including the lock being free and the next read whole. Also per step in `evaluateCallstack` (which also makes it per row of
  a filter walk), and before the reply is serialised. ⚠ It is tripped **only** by an explicit client
  cancel (or, in-process, the caller's own job) — *not* by the request job's cancellation, because a
  session teardown cancels that job too, and turning a teardown into a mid-walk abort would stop a
  write's chain half-way. ⚠⚠ It is **disarmed** when the request ends: a per-request
  `ExecutionContext` outlives its request (a stream subscribed through it re-evaluates on it at every
  later store change), and an armed checkpoint there would throw into a listener.
- **Per transport.** Android: AIDL appends `ipcRevision()`, `callCancellableRead(request, readId)`
  and `oneway cancelRead(readId)`; the client awaits the blocking transaction as a `Deferred`
  (`awaitBlockingCallOrCancelRemotely`), so the caller's cancellation is seen at once, sent as the
  oneway cancel, and the caller unwinds immediately. Desktop: wire revision 2 adds
  `TYPE_CANCEL_READ` (`desktop-ipc.md` §2.3); every correlated call is findable by its call id, and
  the client sends the cancel only for a cancellable read. In-process (`LocalTaskExecutor`,
  `SessionAwareLocalTaskExecutor`, iOS route-1, JS): the read runs in a scope bound to the caller,
  with a checkpoint that observes the caller's job. EPC: unchanged — the default
  `callCancellableRead` is `call`, so the read is simply not stopped.
- ⚠ **A cancel reaches exactly one read.** On desktop the key includes the socket (call ids are a
  counter per client process, so two processes of one app both send `"1"`). On Android it **cannot** include the pid — a `oneway` transaction reports
  the caller's uid but not its pid (`getCallingPid()` reads 0), so a pid-keyed read could never be
  found by its own cancel — so the key is (attested package, random UUID). An id with nothing in
  flight is parked (bounded at 256), because the cancel can overtake its read on another binder
  thread or another launch off the socket. `gateway_aCancelReachesOnlyTheReadItNames` pins it.
- **Mixed versions** (apps and Control update separately). ⚠ *1.0.1 re-cut (2026-11): the
  revision-0 Binder path and the desktop rev-1/pre-hello paths are deleted — revision 1 is the Binder
  floor (`BinderIpc.REVISION_FLOOR`, also what a failed query reads as), 2 the desktop floor
  (`StreamFramer.WIRE_REVISION_FLOOR`, refused below). What follows is the history.* An older Control has no `ipcRevision()`;
  its stub returns `false` from `onTransact` and writes nothing, and the generated proxy reads that
  empty reply as `0`, so the client stays on `call()`. A §2.2 desktop Control answers the hello
  with revision 1, so no cancel frame is ever sent to it (it would read type 9 as a callstack). An
  older client never calls the new methods or sends type 9; Control serves it as before. The revision
  is cached per binder / per socket, so a reconnect to a different build is asked again. No House
  Rule 9 bump: no stored row, schema or relay envelope changed — this is the app↔Control IPC
  contract, versioned by its own revision on each transport.
- **Adopted** in `:core-paging` — `TenshuKeysetPagingSource.load` and its count, and
  `TenshuPagingSource.load` — which covers every paged listing in every app. Other abandoners
  (Collections' bounded reads, view-model reads cancelled by `viewModelScope`) adopt it per call
  site; what is left is in `tech-debt.md` *"A cancelled read still runs in Control unless it is
  wrapped"*.

### 6.3 `BackgroundJobRegistry` — `SyncScheduler` should compose

> **Per the §3.2 revision:** this registry is the in-proxy half of
> `ScheduleService` — it holds the persisted `ScheduleItem`s and is drained by
> both the in-process loop and the per-platform `PlatformScheduler`. Only ops
> whose schema `isHeadlessSafe()` may be registered (enforced at registration
> *and* at `ScheduleService.schedule(...)`). `SyncDrainJob` carries
> `HeadlessSafe`; recovery/pairing ops do not. The `BackgroundJob` shape below
> is the conceptual contract; `ScheduleService` + `ScheduleItem` is its
> concrete, persisted, queryable form.

Today `SyncScheduler` is a hand-coded class with a hard-coded loop. The
proposal extracts it into one of N registered jobs under a unified registry:

```kotlin
interface BackgroundJob {
    val id: String
    val triggers: Set<JobTrigger>          // STREAM_ACTIVE / PEER_ADVANCED / PERIODIC / ONE_SHOT
    suspend fun run(context: ExecutionContext): JobResult
}
```

`SyncScheduler` becomes a `SyncDrainJob` registered with triggers
`{STREAM_ACTIVE, PEER_ADVANCED, PERIODIC}`. New jobs (deferred-upload-drain,
location-ping, tile-prefetch) register similarly. The **same registry** is
consumed by both the in-process loop (when the proxy is alive) and the
platform-scheduler dispatcher (when an OS wake fires). A single scheduled-op
implementation runs whether it was triggered foreground or background — the
context just changes.

This unification matters because today `SyncScheduler` is the only thing
doing background work, and any new background work would either fold into it
(making it a god-class) or duplicate its scaffolding (tick loop, drain logic,
peer-tip handling) somewhere else. The registry breaks that choice.

### 6.4 Threat-model amendments

When this lands, [`docs/sync/threat-model.md`](../sync/threat-model.md)
gains:

1. **Asset A1 (block opacity)** — explicit reaffirmation that subscriber-
   driven Layer 1 does not introduce per-type channels and the relay's view
   of sync traffic is unchanged.
2. **New invariant: scheduled context cannot assume user presence.**
   Enforced **fail-closed** by the `HeadlessSafe` marker (§3.2): an op is
   unschedulable *unless* its author opted in by carrying `HeadlessSafe`, so
   ops requiring user-interactive flows (recovery claim, pairing approval,
   key-derivation phrase entry) are unschedulable by construction — they
   simply never carry it. Checked at registration *and* at
   `ScheduleService.schedule(...)` (the latter covers wire-deserialised /
   dynamic op references). A runtime denylist would fail open; this fails
   closed and the full Layer-3 surface is auditable as the `HeadlessSafe` set.
3. **Revocation propagates through scheduled queues.** When a device is
   revoked, its scheduled-op queue is drained (`PlatformScheduler.cancelAll`)
   before its keys are removed. A revoked device cannot push from a
   scheduled op because its keys are gone; the queue drain is for cleanup,
   not for security.
4. **Web SW has the same key-at-rest constraint as the apex.** No new asset;
   reaffirms that the SW context loads keys with the same OS-level
   protection.

---

## 7. Open questions

### 7.1 File content in the block log vs. a separate blob channel

Today's `UploadFile` schema:

```kotlin
val Schema = operation(
    id = ID,
    receiver = FileSystem.Schema.toType(),
    params = {
        string(id = Params.PARENT_PATH, type = FilePath.Schema)
        string(id = Params.FILE_NAME, type = FilePath.Schema)
        byteArray(id = Params.DATA)
    },
    traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
)
```

`Params.DATA` is a `ByteArray` parameter; the file's bytes flow through the
operation. Whether they land in the resulting `SyncBlock` body or get routed
to a separate (still ciphertext) blob channel is not visible from the schema
alone, and this proposal does not try to settle it.

But Layer 1's cost model assumes blocks are small. If file content sits in
the block log, then "Stream subscribed → drain hot" ends up streaming
gigabytes through the foreground sync path — which breaks the entire premise
of Layer 1 being cheap. So Layer 1's ability to scale to the file service
gates on a blob-channel decision.

**Recommendation:** scope this proposal to Layers 2 + 3 of the file service
initially (uploads run as Layer 3 deferred ops with `network = UNMETERED`).
Layer 1 ships first for non-file syncable types (Folder metadata, Notification,
Device, Realm membership) where blocks are bounded-small. A separate proposal
designs the blob channel and brings file content into Layer 1 once that lands.

> **Update (2026-05-19): this is no longer theoretical.** The §5d Android
> smoke proved it a *hard folder-upload blocker* — uploading large photos
> OOM-kills the Control process (the full file is the `SyncBlock` payload;
> `DatabaseImpl.getArrayData` eagerly materialises the whole `pendingBlocks`
> backlog by design, so there is no contained fix). The blob-channel
> decision named here is the **two-plane** design shipped in
> [ADR 0017](../adr/0017-file-content-two-plane-channel.md) (protocol
> semantics in [`sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel)):
> a small per-file *manifest* on the ordered metadata plane (always
> synced) + chunk bodies in an on-demand, ciphertext-opaque *content
> plane* (fetched by reference only when a file is opened), bounded
> O(CHUNK) memory end-to-end. The amplifier fix (deferring
> `SyncManagerImpl`'s legacy auto-load off the main thread) removed the *restart-storm* but not this
> disease.

### 7.2 Stream → syncable-type mapping

The §4.1 `SyncableTypeSubscriberCounter` requires deriving the syncable type
from a `Stream`. For a `Stream<T>` over `TList<T>`, the type is the element
type — but only if `T` is itself a syncable composite, which not all `T` are.
Streams over derived/computed views don't directly map to a sync type.

The mapping table is constructible at schema-registration time: for each
syncable `CompositeSchema`, walk the schema graph for `Stream<*>` operations
that ultimately resolve to it. This needs `Type` infrastructure that landed
with operation-schema typing ([ADR 0009](../adr/0009-operation-schema-typing.md)) and depends on the same machinery the
relation rework uses; there's no new infrastructure required, just a
new walker.

### 7.3 Foreground/background overlap and dedup

If both Layer 1 (because a Stream is hot) and Layer 3 (because a periodic
schedule fires) want to run `SyncDrainJob` at the same time, the
`BackgroundJobRegistry` deduplicates: one drain runs, the other coalesces
into "wait for the running drain, then no-op if it covered our work." The
`dedupKey` mechanism from §3.3 generalises to this — the registry treats
in-process and OS-woken triggers symmetrically.

### 7.4 Web SW slice size

Compiling the proxy logic into a Service Worker bundle is a separate KMP→JS
slice from the apex SDK. The SW needs `JsRuntime`, `ExecutionContext`,
`SchemaRegistry`, all the syncable schemas, the operation implementations
that are scheduled, the crypto, and an IndexedDB-backed store. That's most
of the proxy's compile graph. Bundle splitting is the engineering question;
whether it's feasible without an unacceptable initial-fetch cost is the
product question.

### 7.5 Pairing handshake — Layer 2 migration — **LANDED**

The `PairingWizardViewModel` used to poll `GetPairingStatus` 60×/5s and use a
separate `CancelPairing` op as the back-out lever — correct behaviour, wrong
abstraction. **Landed shape** (follow-up PR after the in-process carve-out
close; `feat/pairing-l2`):

- The completion path is **one** routine, `PairingCompletion.tryComplete`
  (factored out of `GetPairingStatusImplementation` — decrypt master key →
  `initializeWithKeys` → ack relay → self-push `Device` → seed epoch →
  `clearHandshake`). `GetPairingStatus` calls it once (poll-once, unchanged —
  kept for JS / back-compat callers); the security-critical crypto is **not**
  duplicated.
- A new `AwaitPairing` op carries `BackgroundOperation` (no `IoOperation`, so
  it resolves to a `CancellableTaskInteractor`). Its impl loops
  `PairingCompletion` in-proxy until the master key arrives or a ~5-min budget
  elapses, and its `catch(CancellationException) { clearHandshake }` makes
  cancellation tear down the in-proxy handshake — subsuming the explicit
  `CancelPairing` lever once the wait is running.
- `SyncRepository.awaitPairing` returns a `TaskHandle<TBoolean>`;
  `PairingWizardViewModel` drops the poll loop for `handle.await()` and the
  pre-`InitiatePairing`-stash back-out still falls back to the idempotent
  `CancelPairing`. The interim "`Stream`-returning `BackgroundOperation`"
  shape was skipped — with `TaskHandle` the non-streaming op + the unchanged
  quick `InitiatePairing` (which still surfaces the SAS) is the simpler shape.
- Uniform across desktop/Android (cross-process) and JS (in-process) because
  the in-process carve-out was closed first. Threat-model amended (asset A8 —
  deterministic handshake teardown + bounded lifetime; no protocol/relay
  change). Tests: `AwaitPairingImplementationTest` (completes; cancel burns
  the handshake) + the unchanged `GetPairingStatusImplementationTest`
  (extraction is behaviour-preserving) + `:control-shared` /
  `:tenshu-sdk-js` / `:system-test-utils` pairing suites green.

---

## 8. Phased plan

Each phase ships value standalone. Phases 1–4 land Layer 1 without any
platform integration; Phase L2 lands the Layer-2 cross-process task *runtime*
(`TaskHandle`), independent of both; Phase L2-LIVE lands the per-platform
Layer-2 *process-liveness* wrapper (§2.2) on top of L2; Phases 5–8 land
Layer 3 platform-by-platform. **L2 runtime ≠ L2 liveness:** `TaskHandle`
(L2, landed) makes a long-running task cancellable across IPC, but does not
by itself stop the OS reclaiming the proxy when the foreground app closes —
that is L2-LIVE. `drive-folder-upload.md` Phase 3a needs *both* L2 and
L2-LIVE; only the former is landed.

### Phase 1 — `BackgroundJobRegistry` extraction

Refactor `SyncScheduler` into a `SyncDrainJob` registered with a new
`BackgroundJobRegistry` in `:sync-service-proxy`. No behaviour change; the
loop runs the registered job on the same 30s tick.

Tests: existing `SyncScheduler` tests migrate verbatim to drive the registry.

### Phase 2 — `SyncableTypeSubscriberCounter`

New `TComponent` in `:core-common`, populated by `StreamInteractor` and
`StreamElementDispatcher`. Exposes `Flow<Set<TypeId>>` — the set of currently-
hot syncable types. No consumer yet; the component is observable but
nobody acts on it.

Tests: subscribe/unsubscribe streams over various types, assert the hot-set.

### Phase 3 — HLC-tip gating

Wire `SyncManager.hlcDivergenceTracker` into production. `SyncDrainJob.run`
checks `hasPendingChanges()` before issuing a pull and skips when peers
haven't advanced. Tick frequency for the periodic trigger drops from 30s to
5min for the cold case (or higher).

Tests: `HlcDivergenceTracker` unit tests already exist or are stubbed in the
relay-plan work; this phase wires the tracker to the scheduler and adds an
integration test for "no peer advance → no pull."

### Phase 4a — Fast short-poll while hot

`SyncDrainJob` consumes `SyncableTypeSubscriberCounter` and switches the
periodic interval to 1–3 s while the hot-set is non-empty; reverts to the
HLC-gated 5-minute cold tick when empty. Wires the §4.3 immediate triggers:
`STREAM_ACTIVE` edge → instant pull; `SyncObserver.onDataSaved` → instant
`drainPendingBlocks()` (the latter independent and shippable in Phase 1 if
sequencing allows). No relay change.

Tests: integration test that subscribes a Stream, asserts an inbound block
applies in <2 s; that a local save flushes to the relay within one short-poll
window; that unsubscribing reverts cadence to cold.

**Landed 2026-05-13 in carved-out form.** `StreamRegistry.activeStreamCount`
(StateFlow<Int>) feeds `SyncScheduler`'s tick computation: `interval = if
(count > 0) minOf(hotPollingIntervalMs, pollingIntervalMs) else
pollingIntervalMs`. Default hot = 1500 ms. The §4.3 immediate triggers were
attempted via a wake-channel and reverted — `SyncObserver.onDataSaved` fires
on *every* saveData call, including saves originating from
`SyncProcessor.processSyncBlock`, so each end of a paired pair echoes the
other's writes at network speed, faster than HLC dedup can settle. The
edge-trigger half of the immediate triggers was dropped for simplicity (its
machinery had only marginal user-facing value over the cadence change alone,
worst-case one cold-tick wait for the first hot pull after opening a stream).

The "sync-vs-user save discriminator that doesn't exist today" gap is what
**Phase 4a-bis** (§8) closes — a process-local `SyncIngestMarker` shared
between `SyncProcessor` and `SyncObserver` flips on for the synchronous
duration of `processSyncBlock` / `processDeleteBlock`, so the observer can
short-circuit both block generation *and* the wake. With that discriminator
in place, both halves of §4.3 (#1 idle→hot edge wake, #2 wake-on-user-save)
become safe to reintroduce on top of the cadence change. Phase 4a-bis is
optional — the cadence change alone covers the common case — but unlocks
sub-tick outbound latency for user-perceptible saves, which the cadence
change cannot.

### Phase 4a-bis — `SyncIngestMarker` reintroduces wake-on-save (optional, designed)

The §4.3 #1 + #2 immediate triggers, dropped from Phase 4a because
`SyncObserver.onDataSaved` couldn't distinguish a user-originated save from
an ingest-driven one. **Status:** designed and prototyped on
`feat/sync-ingest-marker` (May 2026), not yet landed — a paired-device
`:system-test-utils` flake under parallel `TestSystem` instances surfaced
during validation and the rollout was deferred until the wake-channel +
nested-`launch` interaction with `Dispatchers.Default` is understood. The
minimal fix is a process-local boolean shared between `SyncProcessor` and
`SyncObserver`:

```kotlin
// :sync-service-proxy
class SyncIngestMarker {
    var isIngesting: Boolean = false
        private set
    fun <R> withIngest(block: () -> R): R {
        val prev = isIngesting; isIngesting = true
        try { return block() } finally { isIngesting = prev }
    }
}
```

`SyncProcessor.processSyncBlock` / `processDeleteBlock` wrap their bodies in
`ingestMarker.withIngest { … }`. `SyncObserver.onDataSaved` /
`onTombstoneEmitted` check `ingestMarker.isIngesting` and short-circuit when
set — both the block generation *and* the wake-channel fire are skipped.
With observer dispatch synchronous on a single thread
(`ObserverRegistry.snapshot-then-iterate`, no `withContext`, no thread hop —
see [ADR 0008](../adr/0008-execution-context-decomposition.md) Phase 4), a plain
mutable boolean is sufficient. No `ThreadLocal`, no
`expect`/`actual`, no coroutine-context element.

With the marker in place, `SyncWakeSignal` (one-shot conflated `Channel<Unit>`)
is added back:

- `SyncObserver` fires `wakeSignal.wake()` after the marker-gated
  `addToArray(pendingBlocksRef, block)` and after the
  `addToArray(pendingTombstonesRef, …)` enqueue — only on the user-save
  paths.
- `SyncScheduler` collects `activeStreamCount.map { it > 0 }.distinctUntilChanged()
  .filter { hot -> hot }` and fires `wakeSignal.wake()` on the idle→hot
  edge.
- The scheduler's `delay(interval)` becomes `wakeSignal.awaitOrTimeout(interval)`
  — suspending until either a wake or the cold/hot interval elapses. A
  leading `yield()` keeps tight test loops fair when wakes are buffered.

**What this buys.** Outbound latency for user saves drops from "next periodic
tick" to "ObserverRegistry dispatch + one channel send" — sub-millisecond in
the local case. The idle→hot edge fires a pull within one yield rather than
waiting one cold tick. Both gains compound at the boundaries the user
actually perceives (typing in a folder name, dropping a file in, opening
Drive for the first time after a peer save).

**What this does not buy.** The relay-trip cost is unchanged — a pull still
costs one round-trip whether triggered by the tick or by a wake. The win is
*latency*, not *traffic*. HLC-tip gating (Phase 3) is the orthogonal lever
for trip-count reduction.

**Tests.** `SyncProcessorIngestMarkerTest` — assert no block is generated
for saves performed inside `withIngest { … }`, and that nested ingest
scopes restore the previous value on exit. Pair `SyncObserver` +
`SyncProcessor` against a single in-memory `Database` and assert no echo
block lands in `pendingBlocks` after `processSyncBlock(peerBlock)`. Add a
two-device system test (`:control-shared`) that saves on device A and
asserts device B observes the resulting block within one hot tick + one
wake — the existing folder-sync system test fixture covers the pairing.

**Why "optional".** The cadence change alone (Phase 4a) already collapses
worst-case latency to one short-poll interval. Phase 4a-bis trades a small
amount of new code (marker + wake-channel + scheduler glue, ~80 lines) for
sub-tick responsiveness, which is most visible on local-LAN or
loopback-relay setups where the round-trip itself is faster than the
hot-poll interval. On a real-network deployment, the marginal gain over
Phase 4a is smaller — the relay round-trip dominates.

**Forward link to the long-term Database improvement.** The marker is a
tactical fix: it threads "this save was caused by ingest" through a
side-channel boolean instead of through the `Database` / `ObserverRegistry`
surface. The principled long-term shape pushes write-origin metadata into
those surfaces directly — `Database.saveData(data, origin = WriteOrigin.USER
| WriteOrigin.SYNC_INGEST)`, observers receive the origin in
`onDataSaved(data, origin)`, and `setArrayListener` callbacks carry the same
tag. That subsumes the marker (no shared mutable state across modules) and
also lets `setArrayListener`'s level-triggered full-array diff
([`DatabaseImpl.kt:142-193`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/db/DatabaseImpl.kt))
become event-driven — listeners get the added/removed entries directly from
the `addToArray` / `removeFromArray` call site rather than re-reading and
diffing the full array on every store. That sits naturally next to the
deferred `TenshuProtocolObjectDeserializer` cleanup
([ADR 0008](../adr/0008-execution-context-decomposition.md) explicit non-goal —
see also tech-debt §"Database Serializer: Only-Grow Logic Doesn't Recurse Into
Nested Composites") and the "Sync Decoupling: Channel-Based SyncBlock Generation"
tech-debt entry — all three are facets of the same theme: the `Database` /
serializer / observer surface is currently coarse-grained ("rewrite, diff,
fan out"), and a richer event model would let sync, stream listeners, and
the relation maintainers all use it. The marker stays as a checked-in
short-term fix; a follow-up proposal under
`core-database-events.md` (TBD — not written, so deliberately not a link) would cover the
larger surface change, and Phase 4a-bis would migrate the marker call
sites to it once that lands.

### Phase 4b — Long-poll wiring

Gates on [`sync-relay-api.md`](sync-relay-api.md) Phase 5. Once the relay
supports long-poll on `/sync/changes`, `SyncDrainJob` switches the hot-mode
loop from fast short-poll to long-poll. Cold-mode unchanged. User-facing
latency unchanged from Phase 4a; the win is mobile radio + idle relay cost.

Tests: integration test that a long-poll is issued while hot; that a
mid-poll peer advance returns promptly without waiting for the timeout.

### Phase L2 — Wire-level `TaskHandle` (Layer 2 runtime)

The Layer-2 runtime behind `BackgroundOperation` (§2.2, §3.1). Independent
of the Layer-1 phases above and the Layer-3 phases below; a prerequisite for
the §7.5 pairing migration, [`drive-folder-upload.md`](drive-folder-upload.md)
Phase 3, and any Phase 5 `HeadlessSafe` op that also carries
`BackgroundOperation` (§3.2). Subsumes the `tech-debt.md` "Wire-Level Task
Cancellation" item.

**The cooperative half already exists and is not what this phase builds.**
`ProxyGateway.call` already runs every request in a per-request
`SupervisorJob`/`CoroutineScope` (`requestJob`/`requestScope`), and
`AsyncTaskExecutor.executeAsync` cooperates with that context — cancelling
`requestJob` already unwinds every suspending IO point for free (structured
concurrency; the gateway is already `CancellationException`-correct). What is
missing is purely the **wire trigger** to cancel a *specific* in-flight
`requestJob` from the client, plus the client-held handle:

1. **In-flight registry.** `ProxyGateway` assigns each `call` a
   connection-scoped task id, registers `id → requestJob` on start, and
   deregisters in the existing `finally` (which keeps its
   `requestJob.cancel()` — this only adds the map).
2. **Wire frame.** For ops tagged `BackgroundOperation` the transport sends
   the id back as a "task started" ack before the final result, and gains a
   separate `cancel(taskId)` RPC over the same channel. Per transport:
   Android Binder (`:core-aidl`), desktop socket (`UnixSocketServer`), JS
   in-process (`JsRuntime`, pass-through). `cancel` looks the id up and calls
   `requestJob.cancel()`; an unknown or already-completed id is a no-op.
3. **DSL surface.** A `CancellableTaskInteractor` variant of the sealed
   `Interactor` hierarchy (per [ADR 0012](../adr/0012-interactor-pattern.md)
   §"When to revisit"): `execute()` returns the suspending result *and* a `TaskHandle`
   carrying `cancel()`. Existing `TaskInteractor` callers are untouched — the
   variant is opt-in by trait.
4. **Discipline audit (load-bearing).** A wire-cancel only reaches work that
   stays *inside* the request coroutine. Work detached onto
   `serviceScope`/`sessionScope` (`ExecutionContext.kt`'s `sessionScope.launch`;
   the class where `runTest` tests leaked their job via the proxy `serviceScope`) escapes `requestJob` and survives the cancel.
   Long-running `BackgroundOperation` impls must keep their work in the
   request coroutine; this lands with a test/lint guard, not a review
   convention.

Threat-model: cancelling mid-IO must leave consistent local state — covered
by §6.2; no new amendment (the §6.4 scheduled-context amendment is Layer 3,
orthogonal).

Tests: registry add-on-start / remove-on-complete; a long-running stub
`BackgroundOperation` cancelled via `TaskHandle` asserts the *proxy*
coroutine stopped (not merely that the client suspending call returned); a
deliberately-detached `serviceScope.launch` op asserts it is **not**
cancelled — a guard documenting the discipline boundary; per-transport
cancel-frame round-trip (Binder, socket, in-process).

#### Implementation notes — what landed (cross-process), and the decisions

The cross-process path landed in one PR. The shapes that differ from the
sketch above, and the load-bearing thing the "cooperative half already
exists" framing understated:

- **The framework detaches every `BackgroundOperation`; the cooperative half
  did *not* already exist for them.** `ExecutionContext`'s
  `AsyncOperationImplementation` dispatch ran `if (schema.isBackground())
  sessionScope.launch { … }` and returned `Success(null)` immediately — so a
  background op's work was *never* inside `requestJob`, and cancelling
  `requestJob` would have controlled nothing. The cooperative half existed
  only for the `IoOperation` / `AsyncReference` path. Phase L2 therefore adds
  a per-request `ExecutionContext.runBackgroundInRequestScope` flag:
  `ProxyGateway.call` sets it **iff the request carried a client-minted task
  id**, and the dispatch becomes `if (schema.isBackground() &&
  !runBackgroundInRequestScope) { legacy detach } else { AsyncReference on
  evaluationScope }`. A cancellable request thus runs the background op
  *awaited, inside `requestScope`*, so `requestJob.cancel()` unwinds it and
  the real `TResult` flows back through the `TaskHandle`. Without a task id
  the legacy fire-and-forget is byte-for-byte unchanged — `StartPairing`
  (the only `BackgroundOperation` today, dispatched via `asTaskInteractor`,
  polled by the pairing wizard) is untouched until the §7.5 migration.
- **The task id is client-assigned**, carried in the request envelope, not
  proxy-assigned with a "task started" ack. Correctness needs only a stable
  connection-scoped correlation key both sides agree on; client-assignment
  lets `execute()` hand back a `TaskHandle` synchronously, keeps the normal
  request/response wire shape, and avoids the binder double-return and a
  back-channel result route. The registry keys `(clientId, taskId)`; a
  bounded `pendingCancels` set closes the race where a `cancel` frame is
  read off the wire before the call frame's async dispatch has registered.
- **Transport surface.** `TaskTransport` gains `callCancellable(request,
  taskId)` (default → `call`, id ignored) and `cancel(taskId)` (default
  no-op) so a non-cancel-aware transport degrades to the pre-L2 behaviour.
  `RemoteBinaryTaskExecutor` implements `CancellableRemoteTaskExecutor`.
  Desktop socket: new `TYPE_CANCELLABLE_REQUEST` / `TYPE_CANCELLABLE_RESPONSE`
  / `TYPE_CANCEL` frames; the client correlates cancellable responses by
  id (not the global `callLock` + single queue, which a long call would
  otherwise hold — blocking even the cancel); the server **must not block
  its read loop** on `proxyGateway.call` (it dispatches each frame on a
  per-connection scope, with a shared write lock so a stream callback can't
  interleave with a now-concurrent response).
- **In-process carve-out — CLOSED** (follow-up PR `feat/inproc-bg-cancel`,
  decided with the maintainer before the §7.5 pairing migration). Phase L2's
  first PR landed only the *cross-process* path; the in-process gap (the JS
  SDK / `LocalTaskExecutor` with no `ProxyGateway` still detached background
  work onto `sessionScope`, so an in-process `TaskHandle.cancel()` controlled
  nothing) is now closed. `CancellableLocalTaskExecutor` (implemented by
  `LocalTaskExecutor`; routed by `DefaultInteractorProvider` after the remote
  branch) runs the task under `coroutineScope { executionContext
  .withEvaluationScope(this, runBackgroundInRequestScope = true) }` — the
  in-process mirror of `ProxyGateway`'s `requestScope` path, so cancelling the
  caller (what `TaskHandle.cancel()` does in-process) cooperatively unwinds
  the op. Low-risk because there were *zero* existing async-`BackgroundOperation`
  consumers (the only `BackgroundOperation`, `StartPairing`, is a *sync*
  `SyncOperationImplementation` and never hit the async-background branch).
  `InProcessBackgroundCancelTest` proves await-not-detached + cancel-unwinds;
  `:tenshu-sdk-js:jsTest` + full `:core-common:desktopTest` +
  `:sync-service-proxy:jvmTest` green. Net: `BackgroundOperation` /
  `TaskHandle` is now uniform across desktop / Android (cross-process) and JS
  (in-process), so the §7.5 pairing migration is one shared migration, not a
  platform split.
- **Android binder — LANDED** (`feat/android-binder-l2`): `:core-aidl`
  `IProxyInterface` gains `callCancellable(byte[], String)` + `oneway
  cancel(String)`; `ProxyService` routes to `proxyGateway.call(..., taskId)` /
  `proxyGateway.cancel(...)`; `BinderTaskTransport` overrides the two
  `TaskTransport` methods. The oneway `cancel` lands on a separate binder-pool
  thread so it is never serialised behind the blocked `callCancellable` —
  binder's threadpool gives for free what the desktop socket needed a
  non-blocking-server restructure to achieve. Pure Android change (no
  commonMain); not locally compilable (no Android SDK in the build env) —
  correctness-by-construction + CI/Studio. With this, cross-process cancel
  works on all three transports.
- The discipline guard is a `PhaseL2CancellationTest` source
  static check (no feature-service `*-proxy` op impl may
  `sessionScope/serviceScope.launch`) plus the behavioural boundary test.

### Phase L2-LIVE — Layer-2 process liveness (per-platform)

The piece §2.2 names and Phase L2 (runtime) does **not** deliver: keeping the
proxy process alive while a user-started Layer-2 task is in flight, after the
foreground app goes away. Gated on Phase L2 (needs the in-flight registry it
introduced); independent of Layer 3 (no `ScheduleItem`, no `HeadlessSafe`, no
platform scheduler). This is the actual unblock for
[`drive-folder-upload.md`](drive-folder-upload.md) Phase 3a — without it, an
upload dies the moment the OS reclaims a backgrounded proxy.

- **Android (the substantive piece) — LANDED (this PR).** A
  `ForegroundTaskLiveness` in `:control` (the Android Control app module;
  there is no separate `:control-android` Gradle module — the original
  wording predated checking the module list, the component lives in
  `:control`), driven by the `ProxyGateway` in-flight registry from
  Phase L2: transition empty→non-empty → `startForeground(...)` with an
  ongoing Tenshu notification ("Keeping N background tasks running");
  non-empty→empty → `stopForeground(REMOVE)` + `stopSelf()`. Not WorkManager
  (§5.2); a foreground service is process liveness for work happening *now*.
  Manifest gains `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`/
  `POST_NOTIFICATIONS` + `android:foregroundServiceType="dataSync"` on the
  `ProxyService` element, and a `NotificationChannelCompat` low-importance
  channel created in `onCreate`.
- **Desktop.** Delivered *for free* by the resident daemon (Phase 6, §5.3) —
  an always-on process has no per-task reclaim pressure, so an in-flight
  `TaskHandle` job simply keeps running on `serviceScope` after the window
  closes. There is no separate desktop work item here; the §5.3 daemon's
  Layer-2-liveness role is satisfied as a side effect of its Layer-3-host
  role. Before Phase 6 ships, a desktop Layer-2 task whose window closes
  dies (the §2.2 "does not survive proxy death" line) — acceptable, because
  desktop UI close is explicit and rare relative to mobile process reclaim.
- **JS / iOS.** No equivalent in this phase. JS background continuity is the
  Service Worker story (Phase 8, Layer 3); iOS is blocked on app
  architecture (§5.5).

Tests: an Android instrumented test that an in-flight task holds liveness up
and that completion tears it down (registry empties → idle), and a JVM unit
test on the registry→liveness mapping (empty/non-empty edges, idempotent).

#### Implementation notes — what landed, and the one seam decision

- **The one commonMain change: `ProxyGateway.inFlightCount: StateFlow<Int>`.**
  §8/§5.2 said "pure `:control-android`, no commonMain" — that was design
  optimism. The Phase-L2 in-flight registry (`inFlight: Map<TaskKey, Job>`)
  is `private` with no observable hook, so `ForegroundTaskLiveness` cannot
  observe transitions without a seam. The minimal one is a read-only
  `StateFlow<Int>` mirroring the already-accepted `StreamRegistry
  .activeStreamCount` pattern: `_inFlightCount.value = inFlight.size` is set
  under the same `registryMutex` on every register/deregister, so the flow is
  always consistent with the map and the change is additive + non-regressive
  (full `:core-common:desktopTest` green; `PhaseL2CancellationTest` gained a
  `inFlightCount` 0→1→0 assertion). No protocol/relay/threat-model surface is
  touched — see "threat-model" below.
- **Edge logic is Android-import-free and JVM-unit-tested.**
  `ForegroundTaskLiveness` collects `inFlightCount` (a `StateFlow`, already
  conflated + distinct — no `distinctUntilChanged`) and drives a
  `TaskLivenessHandle` (`onActive`/`onCountChanged`/`onIdle`) tracking an
  `active` flag for edge idempotency. `ProxyService` implements
  `TaskLivenessHandle` via `androidx.core` `ServiceCompat`/`NotificationCompat`
  (already on the classpath via `core-ktx`), wiring the collector on a
  `Dispatchers.Main.immediate` service scope in `onCreate`, cancelled in
  `onDestroy`. `startForegroundService` is called before `startForeground`
  so an otherwise bound-only service is permitted to go foreground.
- **Locally verified** (unlike the Phase L2 Android binder PR, which shipped
  not-locally-compiled): a worktree-local `local.properties` pointed Gradle
  at the installed SDK; `:control` main + `testDebugUnitTest` +
  `compileDebugAndroidTestKotlin` all build, `ForegroundTaskLivenessTest`
  5/5 green. The instrumented test compiles but needs a device/emulator.
- **Threat-model: no amendment.** Phase L2-LIVE adds only a local
  foreground-service notification + process-liveness. It touches no asset
  (A1–A4/A9), no relay data shape, no sync protocol; the §6.4 scheduled-
  context amendment is Layer 3 (`HeadlessSafe`), orthogonal. House rule #2
  considered and found not to apply.

#### Known gap — L2-LIVE is necessary but **not sufficient** (found 2026-05-19)

Phase L2-LIVE keeps the *Control process* resident while
`inFlightCount > 0`, but a code trace (this session) shows the proxy's own
teardown path cancels the very work the foreground service exists to
protect, and the folder-upload orchestration loop is not in the kept-alive
process at all. Three distinct defects; L2-LIVE alone delivers neither
`drive-folder-upload.md` Phase 3a's survive-app-close nor the §2.2 Layer-2
contract ("the task outlives the foreground app"):

1. **Session-scope teardown cancels in-flight Layer-2 tasks on unbind.**
   In `ProxyGateway.call`, the Phase-L2 `requestJob` is created as a child
   of the per-client session scope:
   `SupervisorJob(sessionContext.sessionScope.coroutineContext[Job])`
   (`ProxyGateway.kt` ~L77). `ProxyService.onUnbind` →
   `ProxyGateway.unregisterAll()` → `ClientRegistry.clear()` →
   `sessionScope.cancel("Registry cleared")` (`ClientRegistry.kt` ~L81–87)
   therefore cancels **every in-flight cancellable task**, collaterally,
   via job lineage — not through the `inFlight` map. The FGS keeps the
   process up; Control's own `onUnbind` handler then kills the task.
   *Aggravating sub-bug:* `clear()` cancels **all** sessions, so one
   client unbinding can cancel a *different* client's in-flight Layer-2
   work.
2. **The trigger is routine interaction, not just OOM.** Drive binds
   app-scoped (`applicationContext` + `BIND_AUTO_CREATE`,
   `BinderTaskTransport.kt`), but `client.destroy()→unbind()` is wired
   only to `MainActivity.onDestroy()` (`MainActivity.kt` ~L99–102; `init`
   in a `LaunchedEffect` ~L75). Home/recents background = `onStop`, no
   unbind → upload continues (the Phase-3a win is real *for this path*).
   But **Back-press / `finish()` / configuration change (rotation)** all
   call `onDestroy` → unbind → defect (1) → the in-flight file is
   cancelled *with Drive's process fully alive*, and the loop's next
   per-file call throws `error("Proxy not connected")`
   (`BinderTaskTransport.kt` ~L81/L92) until something rebinds. Rotating
   the device mid-folder-upload is enough.
3. **The orchestration loop lives in Drive, which has no liveness
   anchor.** `FolderUploadController` runs enumerate-+-per-file on a
   process-lifetime scope **inside the Drive process** (its own KDoc
   states this). Even with (1)+(2) fixed, a true swipe-away/OOM kills
   Drive → the loop stops → remaining files are never submitted.
   L2-LIVE keeps *Control* alive; nothing keeps *Drive's loop* alive.

**Fix options** (decision taken 2026-05-19 — **A**, see below; recorded per House Rule
"communicating across sessions"):

- **A. Detach Layer-2 `requestJob`s from `sessionScope`.** Parent
  taskId-carrying requests to a proxy/service-level scope, not the
  per-client `sessionScope`. Client-driven `cancel(taskId)` still works —
  it cancels the job via the `inFlight` map directly, independent of
  lineage. Tradeoff: the detached task still references an
  `ExecutionContext`/`Database`/`ClientConnection` whose session may be
  torn down; either keep the session alive while it has in-flight tasks
  or give the task its own context. Cleanest, transport-agnostic, makes
  the Layer-2 contract actually true. Touches `ProxyGateway` +
  `ClientRegistry`.
- **B. `clear()`/`unregisterAll()` skips sessions with in-flight Layer-2
  tasks.** Defer that session's teardown until its `inFlight` keys drain.
  Smaller; keeps context ownership intact; adds a "zombie session"
  window + bookkeeping.
- **C. `ProxyService` doesn't call `unregisterAll()` from `onUnbind`
  while `inFlightCount > 0`.** Narrowest, Android-only; leaves other
  transports' unbind paths and the all-clients coarseness of
  `unregisterAll` unaddressed.
- **D. (orthogonal, for defect 3) Move as much of the folder walk as
  possible into a Control-side `BackgroundOperation`.** Constrained:
  per `drive-folder-upload.md` §2.1 SAF enumeration is client-side *by
  necessity*, so D is at best partial (per-file submission proxy-driven;
  enumeration may have to stay in Drive, which then needs its own
  foreground liveness). Needs its own design pass — do **not** fold into
  the A/B/C fix.

**Decision — 2026-05-19, with the maintainer: A.** Detach Layer-2
`requestJob`s from the per-client `sessionScope` (parent them to a
proxy/service-level scope); client-driven `cancel(taskId)` keeps working
via the `inFlight` map. Chosen because it makes "task outlives the
binding" — Layer-2's entire point — actually hold, is transport-agnostic,
and reuses the existing `inFlight` cancel path. B/C/D are not pursued for
defects 1 & 2. Defect 3 is a separate, deeper problem gated by SAF
(`drive-folder-upload.md` §2.1) — tracked independently, **not** folded
into A; its concrete shape is the Drive-side FGS noted under
"Notification ownership" below (the client's own foreground service that
carries the rich notification also anchors the Drive-resident loop).
Until A lands, `drive-folder-upload.md` Phase 3a survive-app-close is
**not delivered** despite L2-LIVE having landed.

**Option A — as implemented (2026-05-19).** Landed for defects 1 & 2;
defect 3 remains separate as decided above. Two coordinated changes in
`:core-common`, both `commonMain` (transport-agnostic, every platform):

- **`ProxyGateway.call` re-parents only taskId-carrying `requestJob`s.**
  For `taskId != null` the `requestJob` is `SupervisorJob(parent =
  clientRegistry.detachScope.coroutineContext[Job])` and `requestScope`
  inherits the session's *non-Job* context
  (`sessionScope.coroutineContext.minusKey(Job)` — dispatcher etc.) but
  **not** its Job. So a per-client `sessionScope.cancel()` (one client's
  unbind) no longer cascades into it. `taskId == null` is byte-for-byte
  unchanged (session-parented — cancel-on-client-death is correct there;
  the client is synchronously awaiting). `ProxyGateway.cancel(clientId,
  taskId)` is unaffected: it cancels via the `inFlight` map directly,
  independent of job lineage — verified by the regression test.
- **Context-ownership sub-problem — resolved by deferring session
  teardown, not by detaching the context (the smaller change).** Merely
  re-parenting the Job is insufficient: a detached task still references
  the session's `ExecutionContext`, and `ClientRegistry.clear()` /
  `unregisterClient()` also call `context.destroy()` (clears
  `observerRegistry` / `streamRegistry`, destroys `clientConnection`) —
  which would silently break observer-driven fanout (e.g. `SyncObserver`
  on a `saveData` from the in-flight op) under the running task. Chosen
  fix: `ProxyGateway` installs an `inFlightProbe` on `ClientRegistry`;
  `unregisterClient`/`clear` consult it and, if that client still has a
  detached task, **defer** that session's `sessionScope.cancel()` +
  `context.destroy()` into a `deferredTeardown` map instead of running
  them. `ProxyGateway.deregisterInFlight` calls
  `ClientRegistry.drainDeferredTeardown(clientId)` when a client's *last*
  detached task drains, performing the deferred teardown then. Net: the
  context lives exactly as long as the work that needs it. Giving the
  task its own context (the other option in the proposal) was rejected as
  a far larger `ExecutionContext`-ownership change for no extra
  correctness here.
- **No-leak on real shutdown.** Detached jobs parent to
  `ClientRegistry.detachScope` = the injected `serviceScope` (production:
  cancelled by the service/DI lifecycle on true shutdown) or, when none
  was injected, a registry-owned fallback `SupervisorJob`. A routine
  `clear()` (unbind) deliberately does **not** cancel either — detached
  tasks must outlive the binding. A real shutdown is the `serviceScope`
  being cancelled by its owner; for the no-serviceScope case a new
  `ClientRegistry.shutdown()` / `ProxyGateway.shutdown()` cancels the
  fallback scope and tears every session (active + deferred) down. The
  regression test asserts a detached task survives `unregisterAll()` but
  is cancelled when the service scope is cancelled.
- **Thread-safety.** The probe runs *synchronously* on the unbind/binder
  thread and cannot take the suspend `registryMutex`; it reads a
  copy-on-write `AtomicReference<Set<TaskKey>>` snapshot maintained under
  the mutex on every register/deregister — the established
  `ObserverRegistry` / `StreamRegistry.activeStreamCount` pattern.
- **Files:** `core-common/.../proxy/ProxyGateway.kt`,
  `core-common/.../proxy/ClientRegistry.kt`; regression coverage in
  `core-common/src/desktopTest/.../transport/PhaseL2CancellationTest.kt`
  (3 new cases — survives-unbind/explicit-cancel-still-works,
  non-taskId-still-cancelled, no-leak-on-service-shutdown). Full
  `:core-common:desktopTest` green (244/244). **Caveat:** Android paths
  (`ProxyService.onUnbind`) are not locally compilable in this env (no
  Android SDK); the fix is entirely in transport-agnostic `commonMain`
  and the desktop tests exercise the exact `unregisterAll()` → `clear()`
  path Android's `onUnbind` invokes. One pre-existing, unchanged narrow
  race remains (a `clear()` landing between `getExecutionContext` and
  `registerInFlight` — same shape as the documented `pendingCancels`
  ordering window; the detached *job* still survives, only the
  same-tick-destroyed context is at risk; out of scope for this minimal
  fix).

**Notification ownership — decided 2026-05-19 (this session), isolation
constraint.** An earlier idea — plumb `(filename, i/of-n)` into
`ProxyGateway.inFlight` so Control's notification reads "Uploading
cat.jpg — 3 of 12" — is **rejected**: it makes Control render
client-supplied presentation strings in Control's own system
notification (a proxy↔client isolation/trust inversion), and is
incoherent once Layer-2 tasks are heterogeneous across clients (Drive
folder upload *and* Control's own location-history sampling for the
future timeline app, `milestones.md`). The model instead splits by
ownership:

- **Control's FGS notification = system liveness, Control-authoritative
  only.** Renders solely what Control derives itself — in-flight count,
  calling-package label (PackageManager-resolved), optionally the schema
  op-id from the parsed callstack. Never a client-supplied string. Stays
  coherent multi-client (group by Control's own keys, e.g. "Drive —
  Upload (3) · Control — Location history (1)"). Make it `IMPORTANCE_MIN`
  / silent / minimal — present (OS-mandated) but unobtrusive.
- **Client's notification = rich task UX, owned by the client, posted
  from the client's process** ("Uploading cat.jpg — 3 of 12", tappable
  into the client's screen, client brand). No isolation breach — the
  client controls its own notification; Control never sees it.

Two notifications here is the **correct ownership split**, not a
compromise — one is OS-mandated system liveness (generic), the other is
task UX (rich, owned); different audiences, the standard Android pattern
(download/playback FGS vs. app progress UI). **Convergence with defect
3:** a client only needs its rich notification when *backgrounded*, and a
client-side FGS for a backgrounded in-flight upload is *exactly* the
defect-3 liveness anchor for the client-resident loop — so the client
notification is not extra scope, it is the defect-3 fix. End state: each
process owns its own liveness *and* its own notification; scales to
multi-client with no special-casing. (Control rendering *its own* tasks
richly is fine — that is not crossing a client boundary.)

### Phase 5 — `HeadlessSafe` marker + `ScheduleService` introduced

Per the §3.2 revision (not the old single-`ScheduledOperation`-trait shape):

- **`:core/trait/HeadlessSafe.kt` — LANDED** (Phase 5 slice 1, this PR).
  Param-less marker mirroring `BackgroundOperation` exactly + `Operation
  .isHeadlessSafe()` / `OperationSchema.isHeadlessSafe()`. **No `operation(...)`
  DSL guard ties it to `BackgroundOperation`** — §3.2 establishes the two are
  *orthogonal*; a "HeadlessSafe ⟹ BackgroundOperation" registration error
  would contradict that. The §6.4 "checked at registration *and* at
  `schedule(...)`" enforcement therefore lives entirely in
  `ScheduleService`/registry land (the remaining slice), **not** in the core
  schema DSL. `:core:jvmTest` `HeadlessSafeTest` 3/3 green (carries it →
  schedulable; lacks it → fails closed; orthogonality both ways + both
  together).
- **`ScheduleSpec` / `ConstraintSpec` / `RetryPolicy` / `ScheduleItem`
  composites + `ScheduleService` (3 ops) + in-process drain loop — LANDED**
  (Phase 5 slice 2, this PR). Decisions recorded below.
- No platform scheduler — the in-process `ScheduleDrainLoop` drains the
  registry and fires `kind = PERIODIC` items honouring `minPeriodMs`;
  `PlatformScheduler` is Phase 6.

**Slice-2 decisions (resolved during implementation):**

- **Placement: the `:sync-service` family, not `:core` or a new module
  quartet.** The spec Scope line said schemas in `:core`, registry/loop in
  `:sync-service-proxy`. `:core` hosts traits + base schema but no
  Service&Operation machinery (DTO factories, proxy impls, DI). `ScheduleSpec`
  /`ConstraintSpec`/`RetryPolicy`/`ScheduleItem` + `ScheduleService` +
  `Schedule`/`ListScheduled`/`CancelScheduled` go in
  `:sync-service`/`-common`/`-proxy` — alongside `SyncBlock`/`SyncService`,
  reusing the existing `syncServiceCommonModule` + `syncProxyImplementation
  Factory` + DI already in `ProxyModule`/`PlatformProxyModule` (no new module,
  no new DI surface, no `settings.gradle` change — matches the minimal-change
  rule and §6.3 "the ScheduleItem set *is* the BackgroundJobRegistry", which
  is sync-adjacent background infra). The `HeadlessSafe` *trait* stays in
  `:core` (slice 1 — traits are core).
- **`ScheduleItem` carries a param-less `opId`, not "serialized args" (yet).**
  §3.2(b)'s parenthetical "serialized args" is deferred: the canonical Phase 5
  example ("sync drain", periodic) and all three acceptance tests are
  param-less, and arg-passing for a deferred upload needs `PlatformScheduler`
  (Phase 6/7) anyway. Modelling arbitrary op args as a composite has no clean
  precedent; revisit when a consumer needs it.
- **Enum vocab as `string` fields + companion `const val`, not a `TConstant`
  hierarchy per value.** Still schema-first (the `string(...)` DSL) and
  wire-serialisable; avoids ~8 sealed `TConstant` types + DTOs the in-process
  loop wouldn't use (constraints are best-effort §5.6 until `PlatformScheduler`).
- **A *new* `ScheduleDrainLoop`, not a `SyncScheduler` repurpose.** The
  `SyncScheduler → BackgroundJobRegistry` extraction is Phase 1 (separate, and
  touches a class a parallel track has diverged). Cadence gating is
  tick-accumulator based (no wall clock) so it is deterministic under
  `kotlinx-coroutines-test`. Started from `initSyncService` (one common init
  point; `SyncProxyComponents` gains an additive `scheduleDrainLoop` field).
- **Threat-model amended (house rule #2):** new invariant **I9. No headless
  escalation** in [`threat-model.md`](../sync/threat-model.md) §3 — the §6.4(2)
  fail-closed enforcement, landed *with* this slice because this is what first
  makes scheduling possible.

Tests (security-correct inversion of the old "fails without a schedule") —
`:sync-service-proxy:jvmTest` `ScheduleServiceTest` 3/3 green:
- A non-`HeadlessSafe` op is rejected at `ScheduleService.schedule(...)` and
  nothing is persisted; the drain loop re-checks before dispatch (defence in
  depth for wire-deserialised / older-build items).
- In-process periodic dispatch fires a registered `ScheduleItem`.
- `dedupKey` coalesces repeat enqueueings to one pending `ScheduleItem`.

**Amended by media pipeline P4 (2026-10-02, `media-processing-pipeline-sweep.md` F3.1/F8):**

- `ScheduleDrainLoop.tick()` **starts** due items and no longer awaits them: each runs in its own
  coroutine under a supervisor, with **per-key single flight** (an in-flight key is skipped and its
  accumulator does not grow, so the period still counts from the end of a run). One long item no
  longer stops every other item ticking in-process — which matters most on desktop and iOS, where
  this loop is the only scheduler.
- A third run outcome: an op may throw **`ScheduledRunDeferred(retryAfterMs)`** (`:sync-service`).
  In-process the item fires again after `retryAfterMs` instead of a full period, its row untouched;
  on Android `TenshuOperationWorker` returns `Result.retry()`. Returning resets the period and any
  other throw keeps the pre-P4 behaviour, both as before.
  - ⚠⚠ **Only the in-process loop honours `retryAfterMs`.** `Result.retry()` carries no delay:
    WorkManager computes the wait from the request's backoff and the chain's attempt count
    (`WorkSpec.calculateNextRunTime`, work-runtime 2.11.2) — linear is *backoff × attempts*,
    exponential *backoff × 2^(attempts−1)*, both capped at 5 h. Consecutive deferrals under a
    15-min linear policy wait 15, 30, 45 … min, not 15 each. A fixed interval would need the worker
    to re-enqueue itself, which this deliberately does not do.
  - ⚠ **Deferrals are counted apart from failures.** WorkManager's `runAttemptCount` is one counter
    for every start of a chain, and it is the only state carried between attempts, so the worker's
    `runAttemptCount < 3` failure budget was being spent by deferrals. `ScheduledRetryAccounting`
    (commonMain, unit-tested) subtracts a per-work deferral count kept in a device-local
    `SharedPreferences` file; the count is clamped to the attempt count and dropped at attempt 0,
    so a stale or lost value costs at most the old behaviour. The cold-boot "proxy not ready"
    retry is counted as a deferral too. A run the system interrupted still counts as a failure.
    ⚠ For **periodic** work a final `Result.failure()` is not terminal — WorkManager resets the
    period exactly as for a success — so the cost of a spent budget there is one period's wait.
- `RetryPolicy` is now **consumed** on Android, narrowly: an `initialBackoffMs` at or above
  WorkManager's 10 s floor becomes `setBackoffCriteria` (`ScheduledWorkPlan.backoff`). Below the
  floor — including `RetryPolicy.create`'s 1 s default, which every pre-P4 registrant passes — it is
  read as unset, so those items keep WorkManager's 30 s exponential default.
- ⚠⚠ **The tick reads the rows under the loop's lock.** With runs off the loop's own coroutine, a
  snapshot taken before the lock could still show a one-shot `PENDING` after its run had written
  `DONE` and freed the key, and fire it a second time.
- ⚠⚠ **A completion writes only the row its run started from — the generation guard.** Every row
  for a key has the same `DataId` (`ScheduleItemDataIdProviderImpl` keys on `dedupKey` alone), so a
  completion used to overwrite whatever was there: a `Schedule` for the key during its run was
  lost, and a `CancelScheduled` was undone. `ScheduleRunState` holds a process-local generation per
  key in an atomic (compare-and-set; **not** a coroutine `Mutex` — both writers are non-suspend,
  HR 3). `ScheduleImplementation` and `CancelScheduledImplementation` bump it inside their
  `Database.transaction`, **before** they touch the row. The tick pairs each row with its
  generation; the completion compares and writes in one transaction and writes nothing if the
  generation moved. A completion also never *adds* a row — it only updates one that is there.
  - The tick does **not** open a transaction to read: on a durable store `Database.transaction`
    enqueues a commit barrier, which a 1 Hz read must not do. It reads the generations, the rows,
    and the generations again, and leaves a key that moved across the read for the next tick.
  - Nothing is persisted, so no stored shape moved (no HR 9 bump). A restart starts every key at
    zero, which is sound: a dead process has no completion. ⚠ Limit: iOS route 1, where several
    processes share one store and cannot see each other's bumps.
  - `ScheduleRunState.Default` is the process's instance — a default argument, not a DI binding.
- ⚠⚠ **A schedule row is written in place, never remove-then-add** (`writeScheduleItem`).
  `Database.transaction` is a lock with **no rollback**; `Schedule` and the loop's completion both
  removed the row and then added its replacement, so an add that threw left the key with no row —
  silently unscheduled. A `saveData` over the shared `DataId` leaves the key with a row whether or
  not it succeeds. Only a key with no row yet is appended. ⚠ It is **not** all-or-nothing: a
  `ScheduleItem` is several store writes (`DbEntryMapper.serialize` stores the three nested specs as
  entries of their own, then the row's entry), so a failure part-way can leave newer specs under
  the previous entry. The row still exists and still runs.
- ⚠⚠ **A run that stood down leaves no timing behind.** Every outcome — a deferral too, which
  writes no row — asks whether the run's row is still the key's row. If not, the key's in-memory
  timing is forgotten whole: the period, the retry delay, "has fired once", and the old item's
  kind. A row that is there starts from its own `initialDelayMs` as a registration that has never
  run. ⚠ So a periodic key re-registered **during its run** fires once more on the next tick rather
  than a period later; re-registering an *idle* key keeps its timing, as before.
- ⚠⚠ **Once `stop()` has returned, no run begins.** The invariant: a run is a child of the
  supervisor `stop()` cancels, and a coroutine cancelled before its body starts never runs its
  body. `stop()` cannot take the loop's lock, and a flag or count a mid-pass tick reads on its
  behalf can be read on the wrong side of the stop (tried, and found wanting, in review). So a tick
  reads nothing; it only launches into a parent, and `Job` decides attach-versus-cancel atomically.
  A key is freed by its run's completion handler, since a run that never begins has no `finally`.
  `start()` after `stop()` brings a fresh supervisor.
  - ⚠⚠ **A run already dispatching is cancelled, not prevented, and the op is not what is
    cancelled.** `executeAsync` starts a suspending op on the session's scope; the run only awaits
    it. `stop()` ends the wait and nothing is recorded, but the op carries on until it ends or its
    session does. An op that never suspends runs to its end. `stopAndAwait()` waits for the runs,
    not for an abandoned op. Not new with P4.
- ⚠ **A completion write that throws no longer escapes the run's coroutine.** Before P4 the loop's
  own `try` caught it; a run in its own coroutine under a bare `SupervisorJob` has no handler, so
  the throw would have reached the thread's uncaught handler (on Android, Control dies). It is
  caught and printed; the row is as it was, so the item runs again.

### Phase 6 — `PlatformScheduler` interface + Desktop daemon + tray

**Seam + registration LANDED 2026-07-07 (Slice A).** As-built deviations from the
sketch below: the interface lives in **`:sync-service-proxy`** commonMain (not
`:core`) because `register` takes a `ScheduleItem` — signature is
`register(item: ScheduleItem)` / `cancel(dedupKey)` / `cancelAll()`, not the
5-arg form. It is bound **per-platform directly in each `PlatformProxyModule`**
(mirroring `NetworkStateProvider`), *not* via a dedicated `expect fun
schedulerModule()` — same Koin-per-platform principle, less ceremony, and it only
needs the one proxy graph (unlike `cryptoBackendsModule`, which is `includes()`-ed
everywhere). Desktop + iOS bind **`NoOpPlatformScheduler`** (the resident process's
in-process `ScheduleDrainLoop` already drains the registry — "one store, two
drivers"); Android binds `WorkManagerScheduler` (Phase 7). `Schedule` /
`CancelScheduled` call `register` / `cancel` after persisting; a rejected
non-`HeadlessSafe` op is never registered (fail-closed *before* the seam). Tested:
`ScheduleServiceTest` (register/cancel wiring with a recording fake).
**Slice C — mostly delivered via [`desktop-macos-integration.md`](desktop-macos-integration.md)
+ the sync-state landing below (2026-07-08).** The desktop **daemon** (launchd via
`LaunchAgentManager`, start-at-login) and a persistent menu-bar **tray** already shipped on
macOS — so the `NoOp` desktop `PlatformScheduler` binding is fine: the resident daemon runs
the in-process `ScheduleDrainLoop`, and desktop scheduling no longer needs Control's *window*
open, only the daemon. Note the tray is the **native Swift `NSStatusItem` agent**, not the
Compose `androidx.compose.ui.window.Tray` this section originally sketched (superseded on
macOS). **The §5.3 sync-state model landed 2026-07-08:** `getSyncState` emits a
`SyncStateCode` (`DISABLED`/`LOCKED`/`PAUSED`/`DIVERGENCE`/`NEEDS_UPDATE`/`OFFLINE`/`SYNCING`/`SYNCED`;
`NEEDS_UPDATE` = the schema pull fence holds, added for Control surfacing its own fence — see `core-schema-skew-surface.md` §8.8) via a
pure, unit-tested `syncStateCode(...)` fed by the `SyncHealthHolder` (registered as a Koin
`single`, injected into `initSyncService`) + `divergenceWarning` + the pause switch; the Swift
tray renders it as a localised label with an attention tint for divergence/offline (ADR 0016 —
the code is authoritative, the strings are the tray's). **The tray Pause/Resume-sync control
also landed 2026-07-08:** a device-local `SyncPauseState` (`:control-shared-logic`; Koin single
registered by `control-desktop` `Main.kt`, persisted via `DesktopControlSettings` — *not* the
preference-service, since pausing is per-device) composed **ahead of** `ConstraintSyncGate` in
the desktop `syncGate` (`!paused && constraintAllowsSync(...)`), so pausing skips the whole
in-process drain/pull cycle (⚠ false since `sync-metered-plane-split.md` — see `tech-debt.md`); a `setSyncPaused` bridge method + a tray button toggle it, and the
same single is read by `getSyncState` so the state label follows. Tests: `SyncStateCodeTest`
(precedence incl. `PAUSED`); `:control-desktop:buildTrayAgent` (swiftc) + `compileKotlinJvm`
green. **Slice C is complete on macOS.** Still open (out of Slice C's macOS scope):
**Linux/Windows** daemons + trays; the pause gate is desktop-in-process only (Android
app-closed pause via WorkManager is Path B / Phase 7 territory).

---

*Original sketch (superseded in part by the as-built note above):*
`PlatformScheduler` is a plain **`interface` in `:core` commonMain**, bound
per platform through **Koin**, *not* an `expect class`. The seam mirrors
`cryptoBackendsModule()` (`:core-common`): an `expect fun schedulerModule():
Module` whose `actual` on each target returns `module { single<PlatformScheduler>
{ … } }`, `includes(...)`-ed by the proxy/Control module graph. Rationale: the
per-platform impls take different injected dependencies (desktop = launchd/
systemd paths; Android = `androidContext()` + WorkManager; both = `ScheduleService`),
which an `expect class`/`actual class` single shared constructor can't express —
Koin resolves each platform's graph independently. This is the house convention
for platform-specific DI wiring (see `CryptoBackendsModule.kt`, incl. its
"function not `val`" note so `:system-test-utils` per-device clients keep
isolated Koin scopes); testing binds a `FakePlatformScheduler` with no `actual`
stub. Desktop `actual` in `:control-desktop`. Desktop daemon mode: launchd plist
on macOS, systemd unit on Linux, Task Scheduler on Windows; first-run prompts to
install.
Tray icon with the four states from §5.3, fed by `SyncManager` state and
`divergenceWarning` once that surface lands. The resident daemon serves the
two roles of §5.3: it hosts the Layer-3 schedule loop **and** (its Phase
L2-LIVE role) keeps in-flight Layer-2 tasks alive after the window closes —
one process, both contracts.

Tests: integration test for "Control survives Drive close, sync drain still
runs"; tray-state golden snapshot for each state.

### Phase 7 — Android WorkManager

**LANDED 2026-07-07 (Slice B) — device-unverified (no Android SDK in the build
env; mirrors the existing `SyncWorker`).** `WorkManagerScheduler` (androidMain,
`:sync-service-proxy`) maps each `ScheduleItem` → a `ScheduledWorkPlan` (the pure,
`commonTest`-covered `§5.6` vocabulary mapping) → a `PeriodicWorkRequest` /
`OneTimeWorkRequest` keyed uniquely on `dedupKey` (`UPDATE`/`REPLACE` so
re-register replaces). The enqueued **`TenshuOperationWorker`** (framework-
instantiated `CoroutineWorker`, `KoinComponent`) reads the op id from `inputData`
and dispatches it via a **`ScheduledOpDispatcherHolder`** (populated post-init,
mirrors `SyncSchedulerHolder`) — running the op through the *same*
`executeAsync(opId)` path `ScheduleDrainLoop` uses, **inside Control** (keys stay
put). Bound in Android `PlatformProxyModule` as
`platformScheduler = WorkManagerScheduler(androidContext())`. `maxDataMb` stays a
`ConstraintSyncGate` (drain-time) concern, not a WorkManager constraint.
**Remaining:** the instrumented test (schedule → advance WorkManager test
scheduler → assert op ran) + on-device pass.

---

*Original sketch:*
Android `actual fun schedulerModule()` binds `single<PlatformScheduler> {
AndroidWorkManagerScheduler(androidContext(), get()) }` — the scheduler enqueues
work via `WorkManager`. Note the split: the *scheduler* is Koin-injected, but the
*worker* it enqueues (`TenshuOperationWorker`) is **framework-instantiated** —
WorkManager constructs it, so it resolves its deps via `KoinComponent`/
`GlobalContext`, not a Koin constructor (it is **not** a `single<>`).
`TenshuOperationWorker` binds to Control via existing AIDL, dispatches by op ID,
returns `Result.success`/`retry`. Constraint mapping per §5.6.

Tests: instrumented test that schedules a `HeadlessSafe` op via
`ScheduleService`, advances WorkManager's test scheduler, asserts the op ran
inside Control.

### Phase 8 — Web Service Worker (deferred)

`actual` for Web. Requires a SW-targeted KMP→JS bundle slice and an
IndexedDB-backed store; not blocking on Phases 1–7. Likely sequenced
after the `:tenshu-sdk-js` apex SDK reaches feature parity (per
[`docs/web/roadmap.md`](../web/roadmap.md) Phase 7).

iOS lands when the iOS app architecture lands; out of phase plan here.

### Phase L2-DRAIN — bulk drain as a Layer-2 task (§4.6)

> ⚠⚠ **Steps 1 and 2 are superseded on Android** by
> [`control-background-work-off-fgs.md`](control-background-work-off-fgs.md) (2026-10-24): Play
> allows the `dataSync` foreground service for user-started work only, so the `SYNC_DRAIN` source no
> longer promotes it (step 1), and the per-edge expedited `DrainSync` (step 2, `ExpeditedDrainScheduler`)
> was replaced by Control's own `SyncPushWorker` — measured firing 746 jobs for a 300-photo backup.

Closes the metered-deferral / orphaned-backlog gap confirmed on-device
2026-07-11. Depends on Phase L2-LIVE (landed) + §6.3 `BackgroundJobRegistry`.
Steps 3–5 (deferred-status surfacing + the `MeteredExempt` trait) are specced
standalone in [`sync-metered-deferred-status.md`](sync-metered-deferred-status.md).

1. **Liveness signal (shape (b)). — SHIPPED 2026-07-12.** A new
   `BackgroundLiveness` source (`:core-common`, a `StateFlow<Int>` next to
   `ProxyGateway.inFlightCount`) is raised by `SyncScheduler` while it has a
   **permitted, pushable** backlog (`pendingBlockCount > 0` AND `storageGate`
   open AND not entitlement-backing-off) and lowered once the backlog drains or
   the metered `syncGate` closes. `ProxyService.onCreate` `combine`s it with
   `proxyGateway.inFlightCount` (additive sum) into the single `StateFlow<Int>`
   `ForegroundTaskLiveness` already consumes — so the bulk drain holds the FGS
   exactly while it has permitted work, with no bespoke "hold FGS while queue
   non-empty" code. The scheduler raises the signal *before* the (long,
   suspending) drain so the FGS is up before Drive can unbind, and the `finally`
   recomputes it from the post-drain queue. The metered subtlety is honoured: a
   closed `syncGate` lowers the signal to 0 (deferred, no FGS), so a Wi-Fi-only
   device on metered never pins a foreground service. Desktop passes `null`
   (the daemon is already always-resident). Wiring: `SyncScheduler` +
   `initSyncService` `backgroundLiveness` param; Android DI registers the Koin
   `single` and injects it into both. Tests: `SyncSchedulerGateTest` +5
   (permitted-backlog-holds / drained-releases / metered-defers / storage-gated /
   stop-releases). Android smoke gate PASSED. **Remaining:** the desktop-daemon
   equivalent (§5.3) once a Layer-2 task can outlive its window there.
2. **Expedited Layer-3 backstop. — SHIPPED 2026-07-12.** On backlog-appears
   (the `BackgroundLiveness` rising edge — idle → permitted, pushable backlog),
   `ExpeditedDrainScheduler` (`:sync-service-proxy` commonMain, platform-agnostic)
   enqueues an **expedited** one-time `DrainSync` via the
   `WorkManagerScheduler.enqueueExpeditedOneShot` seam — `setExpedited(RUN_AS_
   NON_EXPEDITED_WORK_REQUEST)` + a unique `sync-drain-expedited` key with
   `ExistingWorkPolicy.KEEP` (flapping liveness can't pile up jobs) — in addition
   to the hourly periodic, so an app-closed backlog drains promptly even after a
   swipe-kill/OOM tears down the step-1 foreground service. The `UNMETERED`
   constraint stays honest: the expedited job carries the same constraint as the
   periodic drain, and because `BackgroundLiveness` only rises when the metered
   `SyncGate` permits, a Wi-Fi-only-on-metered device never triggers a (pointless)
   expedited kick — its deferred backlog rides the periodic unmetered job. Also
   added `TenshuOperationWorker.getForegroundInfo()` (required for expedited work
   on API 24–30, minSdk 24). Tests: `ExpeditedDrainSchedulerTest` +4 (rising-edge
   only / no re-enqueue while active / fall-then-rise / null-constraint skip);
   Android smoke gate PASSED. The WorkManager enqueue itself is framework code
   (on-device verified only).
3. **Phone banner — the actionable surface (§5.3.1).** Extend the existing
   `SyncHealthStatus` (Control→Drive) with a `deferred` connectivity value
   derived from "`SyncGate` withholds push on metered AND `pendingBlockCount > 0`";
   Drive renders *"Waiting for Wi-Fi — N items"* (N = the field already carried) +
   *"Sync now over cellular"* one-shot override. This is the smallest, highest-value
   slice and is independent of steps 1–2 — it directly kills the confusing warning.
4. **Desktop tray (§5.3.1).** Add a `DEFERRED` `SyncStateCode` for the desktop's
   *own* deferral; reword `DIVERGENCE` away from "Sync issue detected"; render a
   peer being behind as tier-2 "Waiting for {device}", never red.
5. **(Deferred) `DeviceSyncStatus`** synced record so the desktop can name a
   *peer's* deferral reason — needs a Wi-Fi-only-**exempt** heartbeat (or a
   flush-on-enter) to avoid the metered-announce deadlock (§5.3.1). Metadata-only.
6. **(Deferred) progress refinement (shape (a))** — per-upload "syncing 320/770",
   sequenced after streaming `UploadFile` (§7.1).

The heap half of §4.4 (don't `.toList()` the whole backlog; cap block size at the
emit boundary) remains the separate §7.1 piece; this phase is liveness + UX, not
the memory bound.

---

## 9. Non-goals

- **Renaming `BackgroundOperation`.** §3.1 covers the call.
- **Settling the file-content-in-block-log question.** §7.1 covers the
  scope split.
- **iOS app architecture.** §5.5 notes the dependency.
- **A foreground notification system.** Tray icon + tray menu are the
  ambient surface; system-notification-tray integration is out of scope and
  belongs to a separate UX proposal.
- **Battery-impact telemetry.** Worth doing eventually; not load-bearing
  for v1.

---

## 10. The bulk-task sync lane — 1 task == 1 liveness op == 1 checkpoint block == N content blobs

**Status: TS-1…TS-5 IMPLEMENTED 2026-07-24; TS-6 deferred.** ⚠ This said "SPECCED … not started"
until 2026-08-11, contradicting §10.6 four screens below, where every TS-1…TS-5 bullet carries an
"IMPLEMENTED" label with as-built detail and named tests. §10.6 is canonical. This is the spec section that
[`bounded-store-cache.md`](bounded-store-cache.md) §"The task-scoped sync lane"
(maintainer direction, 2026-07-16) named as owed here. The client-facing half —
the request-DSL methods that compose multiple `IoOperation` calls into one
`BackgroundTask` — is specced in [`core-task-composite.md`](core-task-composite.md)
§5–§6 (added the same day); this section owns the wire + emit-boundary half and
the integrated phasing. First consumers: the Fit Health-Connect import, then
Drive folder upload + Synced Folders.

### 10.1 The problem, grounded (code recon 2026-07-24)

- `SyncObserver.processSavedData` generates **one `SyncBlock` per entity save**
  (plus one per Syncable property); `SyncBlock` is strictly **single-payload**
  (`DataBlockPayload` = one entity or one property value). The
  `SyncScheduler` drain then pushes **one block per `POST /sync/push`** — no
  batching exists at any layer.
- A 4.5k-workout Fit import therefore emits ~4,500 small metadata blocks
  (post-option-B the `Workout.Streams` blob is already content-plane — the
  blocks are small, the *count* is the flood). The client-side batched op
  (`RecordImportedHealth`, BATCH_LIMIT 500) collapses IPC round-trips only:
  proxy-side it is still one `saveData` → one block per record.
- Drive folder upload is 2 metadata blocks per file (File row incl. inline
  thumbnails + `CreatedFileModification`), × N files, each its own relay POST.
- Every receiver ingests the same flood: N pulls' worth of blocks, N
  decrypt-`resolveConflictAndSave` cycles.
- **Neither bulk path is checkpointed.** Fit's recovery is
  re-run-and-let-content-addressing-dedup; folder upload's is cooperative
  re-enumeration (conflicts surface per file). The only durable bulk cursor in
  the tree is `SyncedFolderController`'s per-(source,bucket) cursor.

**Corrected premise (vs the bounded-store-cache sketch):** there is **no
array-append re-emit flood on the wire for these consumers.** Relation
membership is not a synced primitive — `ParentFilesRelationMaintainer` and the
health relation maintainers write the parent arrays via raw
`database.addToArray` (never `saveData`), so folder/workout membership is
derived locally on every device and is never re-emitted. The
checkpoint-coalescing benefit for arrays applies only to *genuinely synced*
array-valued properties (e.g. preference override lists, rosters), not to the
two migration targets. Similarly, the historical notification flood is already
coalesced (`FileAddedNotificationCoalescer`). The lane's wins are: relay
mailbox volume, receiver ingest cost, per-push overhead, and **durable
task-scoped checkpoint/resume** — not the array flood.

### 10.2 Wire design — `DeltaBatchPayload`

A third payload variant, sibling of `DataBlockPayload` and
`FileContentManifestPayload` (which is the existing precedent for "one block
referencing N things"). Naming (settled with the maintainer at TS-1): the
wire type names **what it carries** — a batch of `SyncDelta`s — like its
siblings, not why it was emitted; "checkpoint" is the *lane/task event* that
seals a batch (`SyncLane.checkpoint()`, TS-2) and stays event-vocabulary
throughout this section. A future non-task batcher may reuse the container
without checkpoint semantics.

- `DeltaBatchPayload` = a `TList` of per-entity deltas, each carrying its own
  **inner HLC**, optional `propertyId`, and `value: Data` — i.e. a container of
  exactly the shapes `DataBlockPayload` carries today. Batching, not semantic
  fusion: merge stays entity-granular.
- The **outer** `SyncBlock.hlc` is the checkpoint marker; the receiver advances
  its clock from the inner HLCs (max) and runs each delta through the existing
  `resolveConflictAndSave` cycle (per-entity `SyncMetadata`, LWW/`Merge`
  unchanged). A concurrent organic edit to one contained entity on another
  device resolves exactly as today.
- Content-plane bytes never ride the container: bulk ingest is
  **content-plane-first** (chunks + manifests via `ContentBlobStore`, the
  ADR 0017 split), the container carries the small rows/references. The
  ordering invariant (ADR 0015 entity-before-bytes) is restated for the lane:
  a staged delta's HLC predates its manifest emit; content PUTs may complete
  before the checkpoint that references them (receivers fetch lazily by
  reference, so early blobs are invisible until the checkpoint lands).
- ⚠ **Format impact (HR #9):** a new payload variant inside the ciphertext is
  a wire-shape change old peers cannot parse → `TENSHU_STORAGE_FORMAT_VERSION`
  bump and lockstep install, in the same PR that first emits one.
  ⚠⚠ **Corrected 2026-08-11: this bullet used to end "relay 426 gate +
  coordinated redeploy", and every "relay redeploy owed" note in §10.6 below
  descends from it. That is no longer true and was retired by schema-evolution
  E2e (2026-07-26), which took payload versions off the relay entirely.** The
  426 gate reads `RELAY_PROTOCOL_VERSION` — the relay's *own* wire contract —
  and `sync-relay-server`'s production sources do not reference
  `TENSHU_STORAGE_FORMAT_VERSION` at all (it survives only in that module's
  tests, one of which pins the rule: *"a `TENSHU_STORAGE_FORMAT_VERSION` bump
  owes NO relay redeploy"*, `RealmRoutingTest.kt`). The relay stores ciphertext
  (HR #1), so a payload reshape cannot affect it. These notes were written
  2026-07-23/24, days before E2e landed; they were true then and nobody swept
  them. See AGENTS.md House Rule #9 and
  [`core-schema-evolution.md`](core-schema-evolution.md) §7.5/§7.8.
- ⚠ **`targetEntityIdHash` question (open, D2 below):** the container has N
  target entities but the block has one plaintext routing hash;
  `/sync/fetch-entity-blocks` fetches by that hash. v1 recommendation: lane
  blocks set it null and lane-eligible types are excluded from per-entity
  block fetch (receivers persist rows on ingest; the on-demand path serves
  *content*, which stays manifest-addressed). If per-entity fetchability is
  ever needed, carrying N hashes leaks batch cardinality — no worse than
  today's N separate blocks, but it needs the threat-model paragraph then.

### 10.3 Sender design — the lane at the emit boundary

A `SyncLane` keyed by taskId, sitting at the `SyncObserver` emit channel
(the seam `awaitEmitIdle` already proves):

- While a lane is open, saves belonging to it **stage** into the lane buffer
  instead of `generateSyncBlock`; staging coalesces **last-wins by
  `dataIdHash`** (n edits to one entity within a checkpoint window emit its
  final state once — the wire twin of the store mailbox's dedupe-by-hash).
- Lane membership travels with the save, not with a new `WriteOrigin` value:
  the taskId is carried on the op execution (see `core-task-composite.md`
  §5's `withTask(task) { }` mechanics, Q-DSL-1), and `ExecutionContext.saveData`
  threads it to the observer. `WriteOrigin.SYNC_INGEST` short-circuiting is
  unchanged; organic (non-lane) saves keep the per-save reactive path —
  this **adds** a bulk lane, it does not replace the reactive one.
- **Checkpoint triggers:** every 500 staged deltas or 256 KiB encoded
  (constants, not knobs — aligned with the client BATCH_LIMIT and the §4.4
  block-size budget), and always on lane close. "One block per checkpoint,
  usually one" — a multi-hour task keeps cross-device visibility and
  resumability via intermediate checkpoints.
- **Crash safety (the load-bearing part):** rows saved locally but never
  emitted are stranded forever (the locked-save gap class). The staging
  buffer is therefore a **durable journal** (the `DataIdMigrator`
  intent-journal pattern): staged delta refs are persisted below `saveData`;
  `initSyncService` replays lingering lane journals as a final checkpoint
  (idempotent — receivers dedup by inner HLC). A lane whose task lease
  expires (client died mid-loop) is auto-checkpointed and closed the same
  way.
- Tombstones: explicit `SyncObserver.emit` tombstones inside a lane stay on
  the existing per-tombstone path in v1 (bulk delete is deferred, D10).

### 10.4 The equation, structurally

With `core-task-composite.md` §5–§6:

> **1 `BackgroundTask` == 1 liveness op == 1 lane == 1 checkpoint block per
> checkpoint == N content-plane blobs.**

`register(spec)` mints the task and opens the lane; constituent `IoOperation`s join via
`withTask(task) { }`; the proxy's live-task registry feeds the same
`StateFlow<Int>` liveness sum `ForegroundTaskLiveness`/`BackgroundLiveness`
already consume (§L2-DRAIN step 1) — task in flight ⇒ FGS/daemon liveness
held, with no bespoke per-feature bridge; terminal state seals the final
checkpoint and closes the lane. Client-side, `NotificationLiveness` observes
the task's progress stream instead of per-feature controller state, collapsing
the `FolderUploadLiveness` / Fit-import FGS bridges into one pattern.

### 10.5 Decisions — **APPROVED by the maintainer 2026-07-24** (recommendations hold as written)

| #   | Decision | Recommendation |
|-----|----------|----------------|
| D1 | Container-in-ciphertext vs relay batch endpoint | Container (10.2). A batch endpoint fixes only HTTP round-trips; may still be added later, orthogonally. |
| D2 | `targetEntityIdHash` for lane blocks | Null + exclude lane types from per-entity block fetch (10.2). Verify no lane-eligible type needs `/sync/fetch-entity-blocks`. |
| D3 | Lane join mechanism | taskId via op execution context (10.3), **not** a new `WriteOrigin` value. |
| D4 | Inner-HLC assignment | At save time; last-wins survivor keeps its tick (10.3). |
| D5 | Cadence/size constants | 500 deltas / 256 KiB / close (10.3); constants, not knobs. |
| D6 | Staged-delta durability | Durable journal + replay-on-init + lease auto-checkpoint (10.3). |
| D7 | Receiver transactionality | Per-delta transactions in v1 (failure semantics identical to today); container-atomic + listener-fanout batching deferred to TS-6. |
| D8 | Entity-before-bytes under staging | Restated invariant per 10.2; land the ADR 0015 paragraph with TS-2. |
| D9 | Lane lease owner | The task registry (task lease = lane lease); no separate lane lifecycle. |
| D10 | Bulk tombstones / receiver fanout batching / durable (Layer-3) tasks | All deferred out of v1 (TS-6 / Layer 3). |

### 10.6 Phased plan (integrated with the Task Composite)

Phases prefixed TS- to avoid colliding with §8's numbering. Each is
independently shippable; TS-1/TS-2 are pure `:sync-service(-proxy)` +
`:core-common`; the composite enters at TS-3.

- **TS-0 — Spec.** This section + `core-task-composite.md` §5–§6.
  **DONE 2026-07-24; review COMPLETE same day** — the DSL surface settled
  with the maintainer (`withTask` join scope, `TaskSpec<R>` composite,
  `register` verb, Q-DSL-1, the §4 re-scope) and **D1–D10 approved as
  recommended (§10.5)**. TS-1 is unblocked.
- **TS-1 — Wire primitive. IMPLEMENTED 2026-07-24.** `DeltaBatchPayload` +
  `SyncDelta` (`:sync-service`; DTOs + wire registration in
  `:sync-service-common`); `SyncProcessor.applyDeltaBatch` loops each
  delta through the existing per-entity cycle with its inner HLC
  (`resolveConflictAndSave`/`attachPropertyAndSave` refactored to take
  `(hlc, label)` instead of the block). As-built notes: (a) property deltas
  carry `targetEntityIdHash` *inside* the delta (the container block's wire
  hash is null per D2 — a hash-less property delta is dropped with a log);
  (b) receiver safety = parse-to-null-and-drop on pre-TS-1 peers (no crash,
  but silent row loss ⇒ the bump is still mandatory); (c) fmt **v10→v11** —
  ⚠ no relay redeploy is owed (retired by E2e; see the Format-impact bullet in
  §10.4). The client-side lockstep install is what the bump buys;
  (d) threat-model **T-R-11** (A6 cadence exposure strictly improves — this
  is the "block batching" mitigation T-R-08/09/10 named). Tests:
  `DeltaBatchPayloadIngestTest` (5: multi-delta apply, both
  container-vs-organic conflict directions, inner-HLC clock advance,
  malformed-delta drop) + `DeltaBatchSyncSystemTest` (paired
  `TestSystem`: hand-built container over the real drain/relay/ingest;
  stale delta loses on the wire). Test-only producer, as specced.
- **TS-2 — Sender lane. IMPLEMENTED 2026-07-24.** The lane lives **inside
  `SyncObserver`, confined to the ADR 0015 emit-consumer coroutine** — stage
  rides `EmitEvent.Saved(data, laneId)`; open/checkpoint/close/replay are
  marshalled as events (no locking; `awaitEmitIdle` covers lanes). As-built
  notes: (a) **D3** = `ExecutionContext.saveData(data, origin, syncLaneId)` +
  a `LaneAwareDataObserver` sub-interface — `ObserverRegistry` dispatches the
  three-arg overload only to implementors, so the 15+ existing observers are
  untouched; (b) **D6** = durable journal `PENDING_LANE_DELTAS` (an array of
  `SyncDelta`s, journal-written *before* lane memory; shared across lanes —
  replay seals all lingering deltas cross-lane in cap-sized chunks; hooks:
  `initSyncService` + the locked→enabled edge via `reemitPendingLockedWork`);
  ⚠ `SyncDelta` gained a `deltaId` field + `DataIdProvider` (journal-array
  removal is by element DataId) — a pre-producer reshape inside the v11
  window, no further bump (no serialized delta exists anywhere yet);
  (c) caps 500 deltas / 256 KiB / raw-journal backstop 2 000; (d) **v1
  limits**: lanes are personal-realm-only (shared-realm, `MeteredExempt`,
  and DataId-less values fall back to the per-save path), and saves landing
  while locked lose their lane tag (replayed per-save on unlock);
  (e) ADR 0015 amended (D8 — the delta's HLC is assigned at stage time, so
  the entity-before-bytes ordering is restated, not weakened). Tests:
  `SyncLaneTest` (7 — incl. the headline collapse **1 200 saves →
  ceil(1200/500) = 3 blocks**, last-wins coalescing, crash-replay via a
  fresh observer over the same store, organic-save interleaving, and a
  sealed-block round trip through the TS-1 `SyncProcessor` ingest).
- **TS-3 — Task composite v1 + bindings. IMPLEMENTED 2026-07-24.**
  `TaskSpec<R>` (`:core`, phantom R; fieldless polymorphic base schema) +
  `GenericTaskSpec` escape hatch + `BackgroundTask` row composite +
  `TaskService` with THREE ops — `RegisterTask`, `RenewTaskLease` (pure heartbeat — **no progress
  payload**, progress is domain-shaped and lives in typed `TaskSpec`
  subtypes / domain surfaces; superseded the first-cut
  `AdvanceTask(completed)` same day, see `core-task-composite.md` §5), and
  one `TransitionTask(complete|fail|cancel)` (three terminal ops would be
  three Ros for one switch); typed client surface =
  `client.taskService.register(spec) → BackgroundTaskHandle<R>` +
  `withTask(task) { }`. As-built key mechanics: (a) **the withTask carrier is
  the Phase-L2 cancellable envelope** — `TaskInteractor.execute()` (suspend)
  reads the `WithTaskElement` from the caller's context and routes the
  constituent call via `callCancellable(request, taskId)`; zero new transport
  surface, and task-cancel can cancel an in-flight constituent call;
  (b) `ProxyGateway` threads the envelope taskId to the request's
  `ExecutionContext.requestSyncLaneId`, and `saveData` falls back to it — so
  **op implementations need zero changes** to join a lane; (c) ⚠ the lane
  **explicit-open gate**: `SyncObserver` now stages ONLY into lanes
  `RegisterTask` opened (lane API rides `SyncBlockEmitter` defaults) — the
  gate is load-bearing because EVERY cancellable call's taskId reaches the
  observer (an `AwaitPairing` id would otherwise stage into a never-closing
  lane); (d) `TaskRegistry` (`:sync-service-proxy`): rows + D9 lease (5 min,
  renewed by register/advance/transition — NOT by constituent calls in v1;
  self-contained sweep ticker started by `initSyncService`, expiry → FAILED +
  lane close = auto-checkpoint) + `activeCount: StateFlow<Int>` summed into
  Android's `ProxyService` liveness combine (third source next to
  `inFlightCount` + `BackgroundLiveness`); (e) production wiring in the 3
  `PlatformProxyModule`s (Koin single + factory `taskRegistry`/`laneEmitter`
  deref-at-call-time args + `initSyncService` param); **JS runtime not wired
  in v1** (ops resolve unavailable there). Task rows are in-memory Layer 2
  (recovery = re-register + idempotency; durable tasks = TS-6). Tests:
  `TaskServiceOpsTest` (register→stage→complete seals + releases liveness;
  `requestSyncLaneId` fallback; lease renew + expiry auto-checkpoint;
  terminal idempotence) + the `SyncLaneTest` gate test; Android smoke gate
  per HR #8.
- **TS-4 — Fit import migration. IMPLEMENTED 2026-07-24 — the first
  production producer.** `FitProjectionRepository` wraps BOTH import paths
  (`runImport` bulk + `importFiles` streaming) in one `withImportTask`:
  register a `GenericTaskSpec("fit/health-import")` → `withTask { … }` (every
  `recordImportedHealth`/`recordWorkout` write joins the lane; a 4.5k import
  rides the relay as ~⌈N/500⌉ `DeltaBatchPayload` blocks) → count-throttled
  heartbeat (`renewTaskLease` every 100 records) → `complete`/`fail`/
  `cancel`-on-`NonCancellable`. **Graceful degradation is load-bearing**: if
  TaskService is unavailable (older Control, or a runtime without the sync
  client modules) the import runs bare — byte-identical per-save sync — so
  mixed-version pairings and every pre-existing test keep working. As-built
  finds: (a) **the in-process cancellable path now threads the taskId too**
  (`CancellableLocalTaskExecutor.executeAsyncCancellable(task, taskId)` +
  `LocalTaskExecutor` → `requestSyncLaneId`) — closes `withTask` for the JS
  SDK, iOS route-1, AND the `TestSystem` harness in one seam; (b) ⚠ a TS-3
  latent gap surfaced by the first real client invocation: op-returned/param
  composites needed **client-side `type(...)` registrations** and the op Ros
  must request **generic `Data` request objects** (the `StartPairingRo`
  pattern — a typed RO needs a per-composite requestObjectFactory data rows
  don't have); (c) the system-test harness (`SystemTestPlatformModule`)
  gained the same `taskRegistry`/`laneEmitter` wiring as production; (d) Fit
  registers `syncServiceCommonModule` + `syncServiceClientModule` in
  `FitClientModule`. Test: `TaskBatchedImportSystemTest` — 30 task-wrapped
  `recordWorkout`s over the real stack → complete → batch over the relay →
  joiner converges on all 30. Durable import cursor stays optional/deferred.
  ⚠ **No relay redeploy is owed here** — this bullet read "the v11 relay
  redeploy (426 gate) is NOW DUE … deployed relays must move to v11 before
  on-device use" until 2026-08-11. E2e retired it (see §10.4); on-device use
  needs only the lockstep client install.
- **TS-5 — Folder upload + Synced Folders migration. IMPLEMENTED
  2026-07-24.** The client-side walk stays (SAF/grant boundary). As-built:
  (a) `FilesRepository.withBulkUploadTask(kind, block)` — interface
  **default = run bare** (fakes + task-less runtimes keep per-save sync),
  `TenshuFilesRepository` overrides with register(`GenericTaskSpec`) →
  `withTask` → complete/fail/cancel-on-`NonCancellable`;
  `uploadFolder` wraps its own walk loop internally
  (`"drive/folder-upload"`, per-file lease heartbeat via the active-task
  handle) and `SyncedFolderController` wraps each source drain
  (`"drive/synced-folders"`) — the FGS/notification bridges are untouched
  (progress stayed domain-side per TS-3b); (b) ⚠ **the Bg-op variants are
  the folder-upload data path** (`UploadFileBg` via
  `CancellableTaskInteractor`, whose `execute()` is non-suspend) — so
  `withTask` now covers Background ops too: `getInteractor` (suspend)
  captures the element and the TASK's id replaces the per-call minted L2
  id (`buildCancellableTaskInteractor(withTaskId)`), making the envelope
  double as the lane carrier for Bg calls; consequence: constituent Bg
  calls under one task must be **serial** (the in-flight registry is
  keyed `(clientId, taskId)`) — which every bulk loop is; the in-process
  Bg branch threads the id too; (c) ⚠ lease expiry mid-task (one file
  uploading > 5 min with no heartbeat) degrades gracefully — the lane
  closes, later saves fall back per-save; batching lost, nothing lost.
  Tests: `PhaseL2CancellationTest` +1 (a Bg op inside `withTask` rides
  the task's id) + `TaskBatchedUploadSystemTest` — createFolder + 12
  uploads with REAL bytes under one task → joiner converges via the
  batch blocks AND the OnDemand byte fetch round-trips byte-identical
  (**§10.5 D8 proven in anger**: content plane independent of the batch).
  On-device bulk-upload pass = maintainer (no relay redeploy gates it — §10.4).
- **TS-6 — Deferred.** Receiver listener-fanout batching, container-atomic
  ingest, bulk tombstone lane, Layer-3 durable tasks, relay batch endpoint if
  ever wanted.

Threat-model deltas (the container payload note under D2, plus the A-series
touch if `targetEntityIdHash` semantics change) land with the phase that
ships them, per House Rule #2.
