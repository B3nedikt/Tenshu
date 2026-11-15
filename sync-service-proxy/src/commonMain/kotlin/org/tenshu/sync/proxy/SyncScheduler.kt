package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.pulledHistoryComplete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.getOrNull
import org.tenshu.core.messageOrNull
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.FirstPullMarker
import org.tenshu.sync.common.HlcWatermark
import org.tenshu.sync.common.HlcWatermarkStore
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentRefusedException
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.hlcTipCanonicalMessage
import kotlin.math.max

/**
 * Periodically pulls [SyncBlock]s from the backend and triggers processing.
 * Also drains the local pending blocks queue to the relay.
 */
class SyncScheduler(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val syncProcessor: SyncProcessor,
    private val cryptoManager: SyncCryptoManager,
    private val coroutineScope: CoroutineScope,
    private val pollingIntervalMs: Long = 30000,
    /**
     * Faster cadence while at least one stream is observed. Default 1500ms
     * (the §4.3 1–3s recommendation in core-background-processing.md). The
     * scheduler reverts to [pollingIntervalMs] when the hot-set empties.
     */
    private val hotPollingIntervalMs: Long = 1500,
    /**
     * Observable stream-subscriber count. Non-zero ⇒ at least one client has
     * a Stream open; the scheduler switches to [hotPollingIntervalMs] for the
     * next tick. Default = perpetually cold.
     *
     * The idle→hot edge (false→true transition of `count > 0`) fires
     * [wakeSignal] so the first hot pull happens within one `yield()` rather
     * than waiting out the in-flight cold interval — see [start]. A
     * paired-device echo is prevented at the source: `SyncObserver` only
     * enqueues (and wakes) for `WriteOrigin != SYNC_INGEST` saves.
     * `core-background-processing.md` §8 Phase 4a-bis.
     */
    private val activeStreamCount: StateFlow<Int> = MutableStateFlow(0),
    /**
     * Sink for peer HLC tips returned by `POST /sync/changes` (per
     * `sync-backend-relay-plan.md` §5.1). Production wiring connects this to
     * `HlcDivergenceTracker`; tests typically pass a no-op or a recording lambda.
     */
    private val onPeerTip: (SignedHlcTip) -> Unit = {},
    /**
     * Work to run once the tick's pull has settled — the orphaned-bytes sweep
     * (`content-plane-cr2-quorumless.md` §10.9). `null` (the default) runs nothing.
     *
     * ⚠⚠ A throw here must not take the sync tick with it — sync is what the user is waiting on,
     * and this is housekeeping.
     */
    private val onTickSettled: (suspend () -> Unit)? = null,
    /**
     * The device's durable HLC watermark (`sync-hlc-tip-device-identity.md` TC-2). `null` ⇒ the
     * tip is this *process's* clock, which is what every wiring without a platform store gets.
     */
    private val hlcWatermarkStore: HlcWatermarkStore? = null,
    /**
     * Shortcuts the poll interval on a user-originated outbound enqueue
     * ([SyncObserver]) or the idle→hot stream edge. Production wiring
     * ([initSyncService]) shares one instance between this scheduler and the
     * `SyncObserver`; the default is a private instance (only the idle→hot
     * edge fires it — harmless for direct-construction tests).
     */
    private val wakeSignal: SyncWakeSignal = SyncWakeSignal(),
    /**
     * Path A policy gate (`bgproc-scheduling-roles.md`) — the user's `WifiOnly` policy.
     *
     * ⚠ **Read-only here** (`sync-metered-plane-split.md`). Skipping the whole drain/pull cycle
     * would withhold the kilobyte metadata plane *and the inbound pull* while file bytes go out
     * from `SyncObserver.emitFileContent` — the exact inverse of the policy's purpose.
     * Enforcement lives on the content plane (the observer's `contentGate`, the same instance);
     * the scheduler consults this only to *report* the deferral in [reportSyncHealth]. Do not add
     * a `shouldSync()` branch around the tick.
     */
    private val syncGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * The user's device-local **pause** switch ([SyncGate] KDoc). Unlike [syncGate] this one *is*
     * enforced here: while closed, [tickOnce] runs its local maintenance, reports health and returns
     * — no push, no pull, no shared realm, no slice bootstrap. The content plane parks uploads on the
     * same gate (`initSyncService` composes it into the observer's `contentGate`), so nothing leaves
     * the device until it opens. ⚠ A pending [forceDrainOnce] waits for the gate too: "Sync now" is
     * not a way past the user's own pause.
     */
    private val pauseGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Local-first storage gate (ADR 0019, local-first reframe): consulted each
     * tick around the **personal** realm's drain/pull only. `false` (no bound
     * storage subscription) keeps the personal realm local-only — its blocks
     * stay queued and nothing reaches the relay — so the app never contacts the
     * relay for a user who hasn't subscribed.
     *
     * Deliberately scoped to the personal realm: shared realms are funded by
     * their owner, so an invited free member still syncs them (their drain/pull
     * runs outside this gate). Default [SyncGate.AlwaysAllow] for test
     * wirings; production wiring (`:control-shared`)
     * supplies a `StorageCapacityHolder`-backed gate.
     */
    private val storageGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Multi-realm push drain (`sync-multi-realm-runtime.md`).
     * When set, each shared realm's per-realm outbound queue is drained to its
     * own [org.tenshu.sync.common.SyncRelay] after the personal drain — so a
     * shared realm's blocks reach *its* relay stream (different ownerId, same
     * device key), never the personal one. `null` (the default, and any wiring
     * without shared realms) leaves the single-realm drain untouched.
     */
    private val realmRegistry: RealmRegistry? = null,
    /**
     * Lazy personal-realm relay genesis (ADR 0019). When set, the first
     * time the [storageGate] opens (a subscription binds) the scheduler runs the
     * deferred `POST /sync/realm` before the first push, so a realm created
     * local-only registers itself once relay traffic unlocks. Idempotent and
     * self-retrying — see [RealmRegistrar]. `null` (the default, and test/legacy
     * wirings) ⇒ no deferred genesis; those wirings register eagerly in
     * `CreateRealm`.
     *
     * Distinct from [realmRegistry] (the shared-realm registry): this is the
     * *personal* realm's one-time genesis.
     */
    private val realmRegistrar: RealmRegistrar? = null,
    /**
     * G3 orphan sweep (`core-local-store-orphan-gc.md`). Null in test/legacy wirings ⇒ no sweep.
     * Runs a bounded slice at the **end** of a tick so it can never delay real sync work, and
     * off the launch path by construction — the scheduler only ticks after init.
     */
    private val retiredBlockSweeper: RetiredBlockSweeper? = null,
    /**
     * The personal relay, for the C1 targeted entitlement fetch (`sync-device-catch-up.md` §5.3).
     * `null` in wirings without one ⇒ [EntitlementRecovery] simply never attempts, which is the safe
     * direction: the device recovers the slow way (bulk replay).
     */
    private val personalRelay: SyncRelay? = null,
    /**
     * Sink for the locally-derived sync-health signal Drive renders as the
     * status banner (`drive-connectivity-service.md`). When set, each
     * [tickOnce] reports `isSyncing`, relay reachability (from the pull outcome),
     * the pending-block backlog, and the last-successful-pull wall-clock. `null`
     * (the default, and test/legacy wirings) ⇒ no health reporting.
     */
    private val syncHealthHolder: SyncHealthHolder? = null,
    /**
     * Wall-clock source (epoch millis) used only to stamp the health signal's
     * `lastSyncedAtMillis`. Defaults to `{ 0L }` (unused when no
     * [syncHealthHolder] is wired); production forwards the same `nowMs` the rest
     * of the sync init uses.
     */
    private val nowMs: () -> Long = { 0L },
    /**
     * Bounds the personal push loop's retry when the relay rejects writes with `402`
     * (entitlement enforcement, `enforcement-followup-tracks.md` Track A). On a `402` the
     * scheduler skips the push drain for a geometrically-growing window instead of hammering the
     * relay ~6×/sec (the observed storm), and reports the "needs attention on this device" health
     * state. Defaults to a [RejectionBackoff] over [nowMs]; test/legacy wirings that leave
     * `nowMs` at `{ 0L }` never back off (their `MockSyncRelay` never 402s anyway).
     */
    private val entitlementBackoff: RejectionBackoff = RejectionBackoff(nowMs),
    /**
     * Bounds the sync loop's retry when the relay rejects this device's identity with `401`, and
     * carries the "this device is not recognised" health state while it does.
     *
     * The same shape as [entitlementBackoff] for the same reason — a doomed request retried at tick
     * cadence forever (measured: ~8k rejected requests/hour/device against the live relay after a
     * data wipe). The difference is that a `401` is often *repairable* by the device itself, so the
     * probing boundary also runs [RealmRegistrar.reregisterThisDevice]; see [recoverRegistration].
     */
    private val registrationBackoff: RejectionBackoff = RejectionBackoff(nowMs),
    /**
     * Bounds the **content** drain's retry when the relay refuses a chunk with `507` (the owner is
     * at their storage quota), and carries the "storage full" health state while it does.
     *
     * The third instance of the same shape, for the third occurrence of the same storm — see
     * [RejectionBackoff] for the `402` and `401` cases and [STORAGE_REJECTION_MARKER] for this
     * one's measurements. Kept separate from [entitlementBackoff] because the two clear on
     * different events: a `402` clears when *this device* gets a credential, a `507` only when the
     * **owner** frees bytes or raises their tier — plausibly from another device entirely, which is
     * why the probing boundary matters here and a permanent latch would be wrong.
     */
    private val storageBackoff: RejectionBackoff = RejectionBackoff(nowMs),
    /**
     * Whether the last content drain ended because the relay refused a chunk for space. Production
     * wiring points this at `SyncObserver.contentStorageRejected`; default = never rejected.
     */
    private val contentStorageRejected: () -> Boolean = { false },
    /**
     * Fired when the storage backoff blocks or clears, so the *upload* path can refuse a new file
     * the moment the user picks it instead of accepting and journalling one that cannot land.
     * Production wiring points this at `StorageCapacityHolder.relayRejected`; a plain lambda rather
     * than the holder itself because `:sync-service-proxy` has no edge to `:storage-service-proxy`
     * and this is not worth creating one. Default = nobody listening.
     */
    private val onStorageRejectionChanged: (Boolean) -> Unit = {},
    /**
     * Reports the content journal's outstanding bytes each tick, so the *upload* pre-flight can
     * measure a new file against the queue in front of it rather than against raw headroom
     * (ST-7). Production wiring points this at `StorageCapacityHolder.queuedContentBytes`.
     *
     * ⚠ Per tick rather than live: `CheckCapacity` is a **sync** operation and `ensureWithinQuota`
     * a non-suspend extension, so neither can await the journal read. Staleness under-counts, which
     * is the permissive direction and matches this gate's optimistic-on-unknown posture.
     */
    private val onQueuedContentBytes: (Long) -> Unit = {},
    /**
     * Auxiliary process-liveness source (`core-background-processing.md` §4.6). Raised while this scheduler has a **permitted, pushable** backlog and lowered
     * once it drains or the gate closes — the platform (Android FGS) merges it with the
     * in-flight RPC count to keep the process alive across a long bulk drain, so a large
     * folder upload isn't stranded when the foreground app unbinds mid-pass. `null` (the
     * default, and desktop/test wirings where the daemon is already always-resident) ⇒ no
     * liveness signalling.
     */
    private val backgroundLiveness: BackgroundLiveness? = null,
    /**
     * Run once on each locked→enabled edge (the realm just unlocked / finished autoloading its
     * keys), before the tick's drain/pull. Production wiring points this at
     * [SyncObserver.reemitPendingLockedWork] so Syncable saves / file-content emits that were held
     * while the realm was locked (`!isEnabled()`) are replayed the moment keys become available —
     * otherwise a file saved during the startup/lock window is stranded local forever. Serialised
     * under [tickMutex] (it runs inside [tickOnce]). Default no-op for test/legacy wirings.
     */
    private val onEnabled: suspend () -> Unit = {},
    /**
     * Push file content the metered **content** gate deferred, returning how many emits are still
     * waiting (`sync-metered-plane-split.md` §4). Production wiring points this at
     * `SyncObserver.drainDeferredContent`; the `Boolean` carries the user's one-shot *"upload over
     * cellular"* override, which replays past the gate that parked the work.
     *
     * A function rather than a `SyncObserver` reference for the same reason [onEnabled] is one:
     * the scheduler must not depend on the observer, and test/legacy wirings supply neither.
     * Default = nothing deferred, ever.
     */
    private val drainDeferredContent: suspend (force: Boolean, onProgress: (Int, Int) -> Unit) -> Int =
        { _, _ -> 0 },
    /**
     * Deferred content emits actually uploaded so far (`SyncObserver.deferredContentEmitted`): read
     * around each content drain for [tickAccepted]. Default: none, ever.
     */
    private val contentEmittedTotal: () -> Long = { 0L },
    /**
     * Content chunks accepted on any path, the inline upload on a client's call included
     * (`SyncObserver.contentChunksLandedTotal`): progress for [drainNowStalled] that the drain itself
     * does not see (`control-background-work-off-fgs.md` §5.7). Default: none, ever.
     */
    private val contentChunksLandedTotal: () -> Long = { 0L },
    /**
     * File-content emits that completed, on any path (`SyncObserver.contentEmitsLandedTotal`): what
     * ends the push job's park ([contentProgressTotal]) and what makes a run's progress more than
     * re-sent chunks ([drainNowStalled]). Default: none, ever.
     */
    private val contentEmitsLandedTotal: () -> Long = { 0L },
    /**
     * Whether the content plane is waiting out refused writes (`SyncObserver.contentPlaneWaiting`,
     * §5.7). While it waits the drain tries no entry, so the content backlog is not pushable.
     * Default: never.
     */
    private val contentPlaneWaiting: () -> Boolean = { false },
    /** How long it still waits, in ms (`SyncObserver.contentPlaneWaitRemainingMs`). Default: 0. */
    private val contentPlaneWaitRemaining: () -> Long = { 0L },
    /** Half-open that wait (`SyncObserver.retryContentPlane`) — see [retryContentPlaneNow]. */
    private val retryContentPlane: () -> Unit = {},
    /**
     * How many file-content emits the metered gate is currently holding — the figure the
     * *"Waiting for Wi-Fi. N files"* banner reports, and the one that keeps the drain-liveness
     * signal raised while a deferred upload is outstanding. Production wiring points this at
     * `SyncObserver.pendingContentEmitCount`; default = nothing deferred.
     */
    private val pendingContentEmitCount: suspend () -> DeferredContentFigures =
        { DeferredContentFigures() },
    /**
     * The personal realm's content plane, for the C8 joiner snapshot (`sync-device-catch-up.md`
     * §5.7) — the slice writer PUTs there and the reader GETs from it. `null` (test/legacy
     * wirings without a store) ⇒ neither half ever runs, and a joiner replays the log.
     */
    private val contentBlobStore: ContentBlobStore? = null,
    /**
     * The one-time content-declaration backfill (`content-plane-refcount.md` §9.7). ⚠ Null on a platform with no
     * backfill wired — inert by absence.
     */
    private val declarationBackfill: ContentDeclarationBackfill? = null,
    /**
     * ⭐ Resolves a push the relay refused as naming freed content — repair and revive, or
     * retire the row with a notice. A provider because it closes over the `SyncObserver` (the re-upload),
     * which is built after the scheduler (`content-plane-cr2-b4-spec.md` §2.2). `null` (test and
     * legacy wirings) ⇒ a refused block simply stays queued, exactly as any other failed push.
     */
    private val freedContentResolver: () -> FreedContentResolver? = { null },
    /**
     * This runtime's pull-activity signal (`ios-sync-activity-indicator.md` §3.1): raised before a
     * page of rows is applied and where the evidence says a pull is expected, settled at every
     * tick end. `null` (test and legacy wirings) ⇒ nothing is published.
     */
    private val catchUp: SyncCatchUp? = null,
) {

    /** The resolver the pairing flusher shares ([flushPendingBlocks]); see [freedContentResolver]. */
    internal fun freedContentResolverOrNull(): FreedContentResolver? = freedContentResolver()

    /** The queues the personal drains push from, which a resolution may drop blocks of. */
    private val personalBlockQueues get() = listOf(pendingBlocksRef)

    private var syncJob: Job? = null

    // Tracks the realm's enabled state across ticks so [onEnabled] fires exactly once per
    // locked→enabled transition (and re-arms on a re-lock). See [tickOnce].
    private var enabledLatch: Boolean = false

    // Reconnect detection for the eager re-drive of stranded writes (proposal §6a): the relay
    // reachability observed at the end of the previous tick. An offline→online transition resets
    // [entitlementBackoff] and wakes the loop so a device that was 402-backed-off (or merely idle
    // between polls) re-pushes promptly on reconnect rather than waiting out the backoff window.
    private var previousOnline: Boolean = true

    // Whether the merged `/sync/log` lane (L3) is available. Starts optimistic and latches
    // off permanently the first time it is found not to be — the answer cannot change under a
    // running relay, and re-probing every tick would burn a wasted request per tick forever
    // against an older deployment.
    //
    // ⚠ Two independent things can make it unavailable, and BOTH must degrade to the two-lane
    // path rather than to "offline":
    //   - the relay has no `/sync/log` route (bare 404), and
    //   - this proxy has no `GetSyncLog` implementation wired.
    // The second is not hypothetical: iOS route-1 and the JS runtime assemble their own
    // operation graphs, and several test harnesses register ops by hand. An unimplemented op
    // raises a bare `IllegalStateException` from `ExecutionContext.execute`, which — caught as
    // a generic failure — would report a perfectly healthy device as unreachable while the
    // fallback lane would have worked. Hence [mergedLaneWired] probes rather than catches:
    // matching on that exception's message would be the fragile alternative.
    private var mergedLaneSupported: Boolean = true

    /**
     * Whether this proxy has an implementation for [GetSyncLog] at all — asked, not inferred
     * from a thrown error. One map lookup against the same factory `execute` would consult.
     */
    private fun mergedLaneWired(): Boolean =
        executionContext.implementationFactory(GetSyncLog.ID) != null

    // Per-tick relay reachability, set by [pullChanges]: `true` on a successful
    // GetChanges call, `false` on a failed one, `null` when no relay call was
    // attempted this tick (pre-pairing / never-funded). Read in [tickOnce]'s
    // health bookkeeping. Only ever touched under [tickMutex], so no races.
    private var tickOnline: Boolean? = null

    /**
     * Whether this tick's relay call was **authorized**, as distinct from merely answered.
     *
     * ⚠⚠ [tickOnline] cannot carry this: [noteRelayFailure] sets it `true` on a `401` on purpose,
     * so the banner does not blame the user's network for a relay that is plainly up. The move
     * fence needs the opposite reading — during a lost-registration episode every write is refused
     * — so the two travel separately. See [RelayReachability.isConfirmedReachable].
     */
    private var tickAuthorized: Boolean? = null

    /**
     * Whether any metadata **push** was refused this tick, for any reason at all.
     *
     * ⚠⚠ The general form of one defect that recurs as special cases — a `401`, a `402`, a `500`:
     * `noteRelayAccepted()` is only ever called from the PULL paths, so a successful *read* would
     * certify that *writes* are accepted. Both outcomes happen in the same tick: the push fails and
     * the pull that follows it succeeds.
     *
     * ⇒ this is deliberately NOT a list of refusal codes. Any failed push closes the fence, so the
     * next unexpected status the relay invents is covered too.
     *
     * ⚠ Personal-relay pushes only (blocks, tombstones). [drainSharedRealms] pushes
     * to another owner's stream, and folding its refusals in would let one lapsed shared realm
     * defer every personal move.
     *
     * ⚠ An empty queue pushes nothing, so `false` here means "no push was refused", never "a push
     * succeeded". With nothing to send there is no write evidence at all, which is why only an
     * acknowledged round-trip actually closes this.
     */
    private var tickWriteRefused = false

    /**
     * Writes the relay accepted this tick — blocks, tombstones and content uploads. The push job's
     * progress signal (`SyncPushRun`): unlike the backlog's size it is not masked by new work arriving
     * as fast as old work leaves.
     */
    private var tickAccepted = 0

    /**
     * File-content emits completed since process start, on ANY path — the drain's and a client's
     * inline upload.
     *
     * ⚠⚠ What un-parks Android's push job (`SyncPushScheduler`, `control-background-work-off-fgs.md`
     * §5.7). A stall parks the push for 15 minutes; without this, only a push run ends that early,
     * and a backlog the in-process loop drains after the plane heals leaves the push parked — a
     * backup started after it waits for the retry.
     * ⚠⚠ Completed content only: with only the content plane failing, metadata blocks keep
     * landing, and counting them would un-park the push into another failing run every 2–3 minutes. A park a metadata outage caused ends by time, or when content lands.
     */
    fun contentProgressTotal(): Long = contentEmitsLandedTotal()

    /** How long the content plane still waits, in ms (0 = not waiting) — `SyncPushScheduler`'s seam. */
    fun contentPlaneWaitRemainingMs(): Long = contentPlaneWaitRemaining()

    /**
     * ⚠⚠ Let content try again now: half-opens the plane's wait ([ContentEmitBackoff.halfOpen]) and
     * wakes the loop. For what says the world may have changed — a
     * user-started upload, the app coming to the front, *Sync now*, the network coming back.
     */
    fun retryContentPlaneNow() {
        retryContentPlane()
        wakeSignal.wake()
    }

    /**
     * Whether any pull path reported a backlog **this tick** — the verdict [catchUp] is settled
     * with (`ios-sync-activity-indicator.md` §3.1). Reset at the top of [tickOnce]; each path ORs
     * in its own report: the forward pull, the backward walk, and every shared realm.
     *
     * ⚠ One rule, symmetric with [applyPage]: the flag stays up while ANY path has more to bring
     * in. Without the shared-realm half a long shared catch-up would clear and re-raise on every
     * tick. Only ever touched under [tickMutex].
     */
    private var tickBacklog = false

    // Single-flight guard: two [tickOnce] runs must never overlap (concurrent pulls
    // double-process blocks + race `lastSyncTimestamp`; concurrent pushes double-send).
    // The polling loop and the on-demand [drainNow] both acquire it, so an
    // OS-scheduled background tick (the Android `DrainSync` op via WorkManager)
    // serialises with the live loop instead of a second parallel drain.
    private val tickMutex = Mutex()

    /**
     * One `401` backoff per **shared** realm, keyed by its ownerId.
     *
     * ⚠ Deliberately not the personal [registrationBackoff]: each shared realm carries its own
     * device registration and JWT, so they fail — and recover — independently. One refused realm
     * must not silence the others, and a refused personal realm must not silence a shared one.
     *
     * Bounded by the number of realms this device is a member of, so it needs no eviction.
     */
    private val sharedRealmBackoff = mutableMapOf<String, RejectionBackoff>()

    private val pendingBlocksRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = SyncBlock.ID,
            relationId = SyncService.Collections.PENDING_BLOCKS
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID
    )

    private val pendingTombstonesRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID
    )

    /**
     * Drain-failure state per outbound queue. A stuck queue (relay down, JWT
     * rejected, offline) logs on **transitions** — once when the queue first
     * stalls, once when it clears — instead of N identical lines for N pending
     * blocks every 1.5s for the whole outage. The captured cause surfaces *why*,
     * which the push try/catch otherwise swallows. Interim sink: the first thing
     * the logging facade (`docs/proposals/core-logging-facade.md`) should adopt.
     */
    private val blockDrainLog = DrainFailureLog("pending-blocks push")
    private val tombstoneDrainLog = DrainFailureLog("pending-tombstones push")
    private val reapDrainLog = DrainFailureLog("pending-reap-removals push")

    /** Names each entry/exit of the metered-deferred state — see [DeferredStateLog]. */
    private val deferredLog = DeferredStateLog()

    /**
     * Turns the raw per-tick relay outcomes into the reported online/offline verdict. A latched
     * `false` surviving indefinitely once attempts stop would — because `Offline` outranks
     * `Deferred` — mask the "Waiting for Wi-Fi" banner entirely. See [RelayReachability].
     */
    private val relayReachability = RelayReachability(nowMs)

    /**
     * Inbound twin of the two drain logs: names *why* a pull failed, once per outage rather
     * than once per tick. Without it a device that cannot pull says only "offline", and
     * telling an unreachable relay from one that is answering `500` needs the relay's logs.
     */
    private val pullFailureLog = PullFailureLog()

    /**
     * Realm schema pull fence (`core-schema-evolution.md` §7.3). Consulted once
     * per tick in [drainAndPull], between the push drain and the pull; it also publishes
     * the single-row `SchemaFenceStatus` signal. All state is lazy, so a scheduler that
     * never ticks costs nothing.
     */
    private val schemaPullFence = SchemaPullFence(
        executionContext = executionContext,
        // Read per tick, not captured: a shared realm registered mid-session is fenced
        // from the next tick on.
        sharedRealmIds = { realmRegistry?.sharedRealms()?.map { it.ownerId } ?: emptyList() },
        // Control's tray, OS notification and desktop log read the verdict from here.
        onVerdictChanged = { syncHealthHolder?.onSchemaFence(it) },
    )

    /**
     * C1 targeted entitlement fetch (`sync-device-catch-up.md` §5.3). Fired only at an entitlement
     * backoff *probing boundary* — see [drainAndPull] — so the existing 2 s → 2 min window is its
     * rate limit and it needs no timer of its own.
     */
    private val entitlementRecovery = EntitlementRecovery(
        executionContext = executionContext,
        syncManager = syncManager,
        syncProcessor = syncProcessor,
        relay = personalRelay,
        nowMs = nowMs,
    )

    /**
     * Outbound-queue repair for a device whose schema moved while blocks were queued
     * (`core-schema-evolution.md` §8.3). One-shot, runs before the first drain of the
     * process, and inert without registered migration chains — see
     * [PendingBlockRestamper]. Declared after the queue refs it borrows.
     */
    private val pendingBlockRestamper = PendingBlockRestamper(
        executionContext = executionContext,
        syncManager = syncManager,
        cryptoManager = cryptoManager,
        queues = listOf(pendingBlocksRef),
    )

    /**
     * C8 joiner snapshots (`sync-device-catch-up.md` §5.7). Two halves on opposite schedules:
     * the [SliceSnapshotReader] runs once per process at the top of a tick (a joiner needs its
     * first screen *now*), while [SliceSnapshotWriter] runs only from the idle+charging
     * `WriteSliceSnapshots` op — it is work done entirely for other devices.
     *
     * Both are inert without a [contentBlobStore], and both degrade to an ordinary relation
     * read on any failure, so a scheduler that never reaches either loses nothing.
     */
    private val sliceSnapshotReader = SliceSnapshotReader(
        executionContext = executionContext,
        syncManager = syncManager,
        cryptoManager = cryptoManager,
        contentBlobStore = contentBlobStore,
        // ⚠⚠ The processor's own registry, so the reader sees every tombstone the drain recorded.
        isDeleted = { id -> syncProcessor.deletionPoints.deletionPointOf(id) != null },
    )

    private val sliceSnapshotWriter = SliceSnapshotWriter(
        executionContext = executionContext,
        syncManager = syncManager,
        cryptoManager = cryptoManager,
        contentBlobStore = contentBlobStore,
    )

    /**
     * One slice-snapshot pass, for the `WriteSliceSnapshots` op the schedule registry dispatches.
     *
     * ⚠ Not called from [tickOnce], deliberately: sealing every slice is expensive and buys this
     * device nothing, so it belongs behind the idle+charging constraints rather than on the path
     * a user is waiting for. The writer's own gate refuses to publish from a device that has not
     * finished catching up, so an early dispatch is harmless as well as unhelpful.
     */
    suspend fun writeSliceSnapshots() {
        // The writer logs its own outcome — including every reason it sealed nothing, which is
        // the only way a once-a-day pass is observable on a device at all.
        //
        // ⚠⚠ And it must not throw past here. `ScheduleDrainLoop` catches every `Throwable` from a
        // dispatched op and records only a boolean, so an op that throws is **completely silent** —
        // no line, no state change a reader can distinguish from a clean skip. The gate can throw:
        // the realm's key accessors throw while locked, where the null-returning
        // `currentRealmContext()` suggests they cannot, and every named skip line is upstream of
        // that throw.
        //
        // Swallowing is right rather than lazy here: the pass is never correctness-bearing, so a
        // failure costs a joiner time, not data — but a failure nobody can see costs the next
        // person a device pass.
        try {
            sliceSnapshotWriter.writeAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("SliceSnapshotWriter: pass failed — $e")
        }
    }

    /**
     * One content-declaration backfill pass, for the `BackfillContentDeclarations` op the schedule
     * registry dispatches (`content-plane-refcount.md` §9.7).
     *
     * ⚠⚠ Not on [tickOnce], and the reason is cost rather than politeness: this fetches and
     * decrypts one manifest per live content object. It is fine once, behind idle+charging, and
     * ruinous per tick — which is also why the pass carries its own completion marker rather than
     * relying on the schedule to run it only occasionally.
     *
     * ⚠⚠ It must not throw past here, for the reason [writeSliceSnapshots] documents at length:
     * `ScheduleDrainLoop` catches every `Throwable` and records only a boolean, so an op that
     * throws is completely silent. Swallowing is right here too — the pass only ever *describes*
     * content, so a failure costs a delayed reclamation, never data — but a failure nobody can see
     * costs the next person a device pass.
     */
    suspend fun backfillContentDeclarations() {
        if (declarationBackfill == null) return
        // ⚠⚠ One pass per process. The scheduled op and a manual trigger both land here, and can
        // overlap: two passes fetching the same thousands of manifests side by side. The declares
        // are idempotent, so it is only waste — but the second pass also doubles the time the
        // first needs the network. A caller that finds one running is told so, not queued.
        if (!maintenancePass.tryLock()) {
            println("ContentDeclarationBackfill -> skipped: a pass is already running in this process")
            return
        }
        // ⚠⚠ No liveness key and no foreground service (`control-background-work-off-fgs.md` §5.6).
        // On Android the pass runs only under its own WorkManager job (the in-process schedule loop
        // leaves an idle+charging item to WorkManager), dispatched as the job's child, so the job
        // holds the process and the network for exactly as long as the pass runs and a stop unwinds
        // it through here. The lock is released in `finally`, so the job's continuation can start
        // the next pass.
        try {
            runMaintenancePass()
        } catch (e: CancellationException) {
            println("ContentDeclarationBackfill -> pass stopped before it finished (its job or session ended); the next run starts it again")
            throw e
        } finally {
            maintenancePass.unlock()
        }
    }

    private val maintenancePass = Mutex()

    private suspend fun runMaintenancePass() {
        declarationBackfill?.let { backfill ->
            try {
                backfill.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("ContentDeclarationBackfill: pass failed — $e")
            }
        }
    }

    /**
     * Service-supplied piggyback checks run at the end of every sync cycle
     * (after drains + pulls). First consumer: the contact service's
     * "pending invites?" response pickup (`contact-invitation-handshake.md`
     * §12) — registered from app DI so `:sync-service-proxy` stays free of a
     * `:contact-service` dependency. Hooks must be cheap when idle (each
     * gates itself on its own local state) and may not break the cycle: a
     * throwing hook is caught and logged per-tick.
     */
    private val syncCycleHooks = mutableListOf<suspend () -> Unit>()

    fun addSyncCycleHook(hook: suspend () -> Unit) {
        syncCycleHooks.add(hook)
    }

    /**
     * Local **maintenance** that runs before the enabled guard, i.e. even on a device whose realm
     * is sealed.
     *
     * ⚠⚠ **Why this exists as a second list rather than reusing [addSyncCycleHook].** `tickOnce`
     * returns at `if (!syncManager.isEnabled())` — before the sync-cycle hooks — so a process whose
     * realm never unlocks never runs any of them. That is correct for the hooks already there
     * (`InviteResponsePickup` needs realm keys), and silently wrong for garbage collection, which
     * needs nothing but the local store.
     *
     * Drive-on-iOS's route-1 runtime keeps its realm sealed by design (`DriveLocalRuntime`: *"no
     * genesis, no unlock"*): an orphan sweeper registered as a cycle hook is constructed on every
     * launch and **never once called**, so that store keeps its orphaned rows — rows which, because
     * `UploadFileImplementation` tests existence by path, also block their own re-upload. Control
     * sweeping its own store to completion meanwhile makes it look fine.
     *
     * ⚠ A maintenance hook therefore may not assume an unlocked realm, keys, or a reachable relay.
     * It must be self-refusing, bounded and local — exactly what the two sweeps already are.
     * ⚠ Isolated per hook like the cycle hooks: cleanup must never break a tick.
     */
    private val maintenanceHooks = mutableListOf<suspend () -> Unit>()

    fun addMaintenanceHook(hook: suspend () -> Unit) {
        maintenanceHooks.add(hook)
    }

    /**
     * Starts the polling job.
     */
    fun start() {
        if (syncJob != null) return
        // Reopens the latch [stop] set, so a restarted scheduler can raise again.
        catchUp?.resume()
        syncJob = coroutineScope.launch {
            // Idle→hot edge wake. Structured child of syncJob — cancelled with
            // it in stop(). `map { it > 0 }.distinctUntilChanged()` collapses
            // the count to a hot/cold boolean; `filter { it }` keeps only the
            // false→true (idle→hot) transition, so opening the first stream
            // fires an immediate pull instead of waiting out the cold tick.
            launch {
                activeStreamCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .filter { hot -> hot }
                    .collect { wakeSignal.wake() }
            }
            while (isActive) {
                try {
                    tickMutex.withLock { tickOnce() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                }
                // Hot cadence kicks in when at least one stream is observed
                // (`StreamRegistry.activeStreamCount > 0`). Reverts to
                // [pollingIntervalMs] when the hot-set empties. `minOf` protects
                // tests / aggressive production overrides that set [pollingIntervalMs]
                // below [hotPollingIntervalMs] — the hot path must never slow polling
                // down. Layer 1 of `core-background-processing.md` §4.3.
                val interval = if (activeStreamCount.value > 0) {
                    minOf(hotPollingIntervalMs, pollingIntervalMs)
                } else {
                    pollingIntervalMs
                }
                // Leading yield keeps tight test loops fair when a wake is
                // already buffered. awaitOrTimeout returns on either a wake (a
                // user-originated save enqueuing a block, or the idle→hot edge)
                // or `interval` elapsing — collapsing user-save outbound
                // latency from "next tick" to "observer dispatch + one channel
                // send". core-background-processing.md §8 Phase 4a-bis.
                yield()
                wakeSignal.awaitOrTimeout(interval)
            }
        }
    }

    /**
     * Run one full drain/pull cycle **on demand**, serialized with the polling loop
     * via [tickMutex] so it can never overlap an in-flight tick. The entry point for
     * an OS-scheduled background sync (the Android `DrainSync` op):
     * WorkManager wakes Control, this drains once (push + pull + shared realms, gated
     * exactly like the loop — so Wi-Fi-only etc. are honoured), and the worker awaits
     * it for its `Result`. Suspends until the drain (or any in-flight tick ahead of
     * it) completes.
     */
    suspend fun drainNow() = tickMutex.withLock { tickOnce() }

    /**
     * The relay is refusing this device's identity: its sync traffic met a `401` that no repair has
     * cleared yet. The same bit the health row publishes as `deviceUnrecognized`.
     *
     * Read without [tickMutex], on purpose: its reader is `ForgetThisDevice`'s retirement wait,
     * which polls while ticks run, and must stop waiting for a push this relay will not accept
     * (`ForgetThisDeviceImplementation`).
     */
    val relayRefusesThisDevice: Boolean get() = registrationBackoff.isBlocked

    /**
     * [drainNow], answering whether that tick left **more to pull** — a full page on the forward
     * lane, an unfinished backward walk, or a full page from any shared realm ([tickBacklog]).
     *
     * For a caller that steps ticks itself and has to know when it has caught up rather than guess:
     * the system-test harness's stepped mode (`TestSystem.syncUntilQuiet`), which runs with the
     * polling loop off, so the wake a backlog normally raises is never acted on. Read inside the same
     * [tickMutex] hold as the tick, so the answer is this tick's.
     */
    suspend fun drainNowReportingBacklog(): Boolean = tickMutex.withLock {
        tickOnce()
        tickBacklog
    }

    /**
     * [drainNow], answering whether the cycle **failed to reach the relay or had a write refused**
     * — the stall signal for Android's `SyncPushWorker` (`control-background-work-off-fgs.md` §5.3).
     *
     * ⚠ Read inside the same [tickMutex] hold as the tick, so the answer is this cycle's, not the
     * polling loop's next one. `tickOnline == null` (nothing to send, no relay call) is not a failure.
     */
    suspend fun drainNowStalled(): SyncPushDrain = tickMutex.withLock {
        tickOnce()
        // ⚠⚠ Chunks that landed since the last call count as sent, whichever path sent them. An
        // automatic backup uploads inside the client's call, and the drain answers that entry
        // "someone already emitting": counting the drain alone reads a ten-minute video as two
        // minutes of nothing sent, and the job parks mid-upload (§5.7).
        val chunks = contentChunksLandedTotal()
        val chunksMoved = chunks > chunksSeenByPush
        chunksSeenByPush = chunks
        val emits = contentEmitsLandedTotal()
        val emitsMoved = emits > emitsSeenByPush
        emitsSeenByPush = emits
        SyncPushDrain(
            stalled = tickOnline == false || tickWriteRefused,
            sent = tickAccepted > 0 || chunksMoved,
            // ⚠ Chunks alone are not a completion: a file that changes under every attempt re-sends
            // its chunks each time and never finishes, which keeps a run's `sent` true and would
            // chain MORE runs for ever. `SyncPushRun` ends such a run STALLED.
            completed = tickAccepted > 0 || emitsMoved,
        )
    }

    /** [contentChunksLandedTotal] as [drainNowStalled] last read it. Under [tickMutex]. */
    private var chunksSeenByPush = 0L

    /** [contentEmitsLandedTotal] as [drainNowStalled] last read it. Under [tickMutex]. */
    private var emitsSeenByPush = 0L

    /**
     * Eager re-drive hook (`enforcement-followup-tracks.md` §6a): an external signal that the entitlement write
     * block may have cleared — a per-device commerce token became available, the account was
     * (re)bound, or enforcement was relaxed. Drops the `402` backoff window and wakes the loop so
     * stranded writes re-push on the next tick instead of waiting one out (or a process restart).
     *
     * Complements the two in-loop triggers (a successful push, and reconnect detection in
     * [reportSyncHealth]); wire this from the credential/session layer when that signal is
     * observable. Safe to call at any time — [RejectionBackoff.reset] and the wake are both cheap
     * and idempotent.
     */
    fun notifyEntitlementCredentialChanged() {
        entitlementBackoff.reset()
        wakeSignal.wake()
    }

    /**
     * The host became foreground again — wake the loop so the next tick runs now instead of
     * after the remainder of [pollingIntervalMs].
     *
     * ⚠ Deliberately weaker than its two neighbours, and that is the point: it bypasses **no**
     * gate and resets **no** backoff. [forceDrainOnce] is the user's explicit "sync over
     * cellular" override and [notifyEntitlementCredentialChanged] clears the 402 backoff;
     * calling either of those on every foreground would silently defeat a Wi-Fi-only policy
     * or hammer a relay that is refusing this device. Coming back to a window is not consent
     * to either — it only means "a tick is worth running now".
     *
     * The wake is CONFLATED ([SyncWakeSignal]), so foreground/background flapping collapses to
     * a single tick rather than queueing one per transition.
     *
     * Motivated by web, where the app window *is* the proxy: a hidden tab's timers are
     * throttled and then frozen, so the loop stops entirely (`drive-web-parity.md` §1.2b
     * measured 6- and 12-minute gaps and then nothing). Nothing here can make a frozen tab
     * run — it removes the wait *after* it resumes. Native hosts with a foreground/background
     * edge (iOS, a desktop window un-hiding) can use it for the same reason.
     */
    fun notifyForegrounded() {
        // The app in front is a user looking at the banner: a waiting plane tries again now (§5.7).
        retryContentPlane()
        wakeSignal.wake()
    }

    /**
     * One-shot "Sync now over cellular" override (`sync-metered-deferred-status.md` §2.3):
     * the user tapped through the *deferred* banner to drain the backlog now despite the
     * `WifiOnly` policy. Sets a single-use flag consumed by the next [tickOnce] and wakes
     * the loop. One-shot, not a persistent toggle — the user opted into *this* drain, not
     * "always cellular" (that lives in the Wi-Fi-only setting). The `storageGate` /
     * entitlement gates inside [drainAndPull] still apply, so this bypasses the metered
     * [syncGate] only.
     */
    fun forceDrainOnce() {
        // Logged unconditionally (a user tap is rare, so this cannot flood): without it the
        // whole override is invisible end-to-end — the op returns before the drain runs and
        // the Drive-side caller discards the result, so a tap that never reaches a live
        // scheduler is indistinguishable from one that drained successfully.
        println("SyncScheduler: forceDrainOnce() requested — one drain will run past the metered gate")
        forceDrainRequested.value = true
        wakeSignal.wake()
    }

    /** Consumed atomically by [tickOnce] — one drain runs past a closed [syncGate]. */
    private val forceDrainRequested = MutableStateFlow(false)

    /** Guards [repairDanglingQueueRefsOnce]; the queues cannot re-acquire dangling refs mid-process. */
    private var danglingQueuesRepaired = false

    /**
     * Drop dangling references from this device's outbound queues, once per process.
     *
     * ⚠ Personal queues only, matching [PendingBlockRestamper]'s scope — a shared realm's queue is
     * reached through [RealmRegistry] and belongs with that work. ⚠ Repairing is safe by
     * construction: [Database.removeDanglingArrayMembers] only drops a reference whose object is
     * *provably absent*, so it can never discard an unsent write.
     */
    private fun repairDanglingQueueRefsOnce() {
        if (danglingQueuesRepaired) return
        danglingQueuesRepaired = true
        val dropped = executionContext.database.removeDanglingArrayMembers(pendingBlocksRef)
        if (dropped > 0) {
            println(
                "SyncScheduler: outbound queue repair — dropped $dropped dangling block " +
                    "reference(s). Their rows were already gone; the blocks they named cannot be " +
                    "recovered, and every reader of these queues stops paying for them."
            )
        }
    }

    /**
     * Run a pre-drain one-shot so that its failure cannot cost this tick its drain.
     *
     * ⚠⚠ The repair passes at the top of [tickOnce] are **maintenance**; the drain is the point of
     * the tick. A throw from either, propagated straight out of [tickOnce], would skip
     * `updateDrainLiveness`, `reportSyncHealth` **and `drainAndPull`** — push and pull both. The
     * loop catches and re-ticks, so a *deterministic* failure (a dangling queue member,
     * `PendingBlockRestamper`) would leave sync dead on that device forever, and silently:
     * `reportSyncHealth()` is in the skipped region, so the UI keeps showing the last value from
     * before it broke. Seen on a real iPhone — `tech-debt.md` §Sync → "PendingBlockRestamper
     * throws on a dangling queue member".
     *
     * ⚠ `CancellationException` is rethrown: it is how the tick is stopped, never a failure to
     * absorb. (This is why it is a `try`/`catch` and not `runCatching`, which would swallow it.)
     *
     * ⚠ Loud on purpose, and stdout-only — routing a maintenance failure into
     * `reportSyncHealth` means deciding what the user should be told about a repair pass they
     * never asked for, which is its own change.
     */
    private suspend fun runOneShot(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println(
                "WARN SyncScheduler: $name FAILED — continuing to the drain. The tick's repair " +
                    "work is skipped this pass; push and pull are not."
            )
            e.printStackTrace()
        }
    }

    /**
     * One drain/pull cycle, gated by `isEnabled()` (realm/unlock) **and**
     * [syncGate] (Path A user policy). The personal-realm relay traffic within is
     * additionally gated, but **push and pull are gated separately**:
     *
     * - **Push** (genesis + outbound drain) requires [storageGate] open — a bound
     *   local subscription or an owner-funded join. Local-first: an unsubscribed
     *   device uploads nothing.
     * - **Pull** additionally opens whenever the realm is already registered on the
     *   relay ([SyncManager.realmRelayRegistered]). This closes the cross-device
     *   bootstrap deadlock (storage-entitlement-sync.md): a device whose
     *   subscription lives on *another* of the owner's devices — the genesis device
     *   when the user subscribed on a paired phone, or a device paired before the
     *   `joinedFundedRealm` latch existed — must be able to pull the synced
     *   `Entitlement` (and future revocation tombstones) that flips its *own*
     *   `hasStorage`, even though its local figure is still false. Pulling is
     *   read-only and consumes no quota; a never-funded realm is never registered,
     *   so a truly-unsubscribed first device still makes no relay request at all.
     * - **Pull is additionally fenced** by [SchemaPullFence] (`core-schema-evolution.md`
     *   §7.3): a device behind the realm's schema epoch stops pulling — and stops
     *   advancing its cursor — while continuing to push. It is the one gate here that
     *   closes the *read* side and leaves the *write* side open; the other two do the
     *   reverse.
     *
     * Shared realms are unaffected. `internal` so tests can step it
     * deterministically without the polling loop.
     */
    internal suspend fun tickOnce() {
        // ⚠⚠ FIRST, ahead of every personal push — a reset placed after a push erases that push's
        // refusal the moment it was recorded. See [tickWriteRefused].
        tickWriteRefused = false
        tickAccepted = 0
        tickBacklog = false
        // 0. Local maintenance — BEFORE the enabled guard, deliberately. See [addMaintenanceHook]:
        // a sealed-realm process (Drive-on-iOS's route-1 read path) never gets past that guard, so
        // anything registered after it simply never runs there. GC needs no keys, so it runs here.
        maintenanceHooks.toList().forEach { hook ->
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("SyncScheduler: maintenance hook failed; continuing — $t")
            }
        }

        if (!syncManager.isEnabled()) {
            // Re-arm so [onEnabled] fires again if the realm is later re-locked and unlocked.
            enabledLatch = false
            // Report health before returning: returning early would leave the row frozen at its
            // last pre-lock value, so Drive would render stale connectivity and — with `hasStorage`
            // collapsed to false by the null owner — mis-read a *sealed* realm as an *unsubscribed* one ("Local only — set up
            // storage"). `getUnlockStatus().isLocked` distinguishes the two `!isEnabled()` cases:
            // a sealed realm (wrapper present, keys absent) vs a device that never onboarded.
            reportSyncHealth()
            // A realm that locked mid-catch-up pulls nothing; the flag must not outlive it.
            catchUp?.settle(false)
            return
        }
        // The user paused sync on this device: no push, no pull, no shared realm. Ahead of the latch on
        // purpose — [onEnabled]'s replay is work that wants to push, so it waits for the first
        // unpaused tick. Health is still reported (with no relay outcome: this tick made no call),
        // so the row does not freeze mid-sync.
        if (!pauseGate.shouldSync()) {
            tickOnline = null
            tickAuthorized = null
            reportSyncHealth()
            catchUp?.settle(false)
            return
        }
        // Locked→enabled edge: replay any work held while the realm was locked (saves/content
        // emits the `!isEnabled()` guards deferred) before this tick drains, so a file saved
        // during the startup/lock window syncs instead of being stranded local. Once per edge.
        if (!enabledLatch) {
            enabledLatch = true
            onEnabled()
        }

        // Drop references to blocks whose rows are gone, BEFORE anything reads the queues.
        //
        // ⚠⚠ Reads tolerate a dangling member (`Database.getArrayData` skips it), so this is not
        // what keeps the tick alive — it is what stops the queue carrying dead weight forever,
        // paid for by every reader of those queues (restamper, drain, sweeper) on every pass.
        // One-shot per process; a no-op returning 0 on a healthy store, which is every store that
        // has not lost a row.
        runOneShot("danglingQueueRepair") { repairDanglingQueueRefsOnce() }

        // Schema-evolution §8.3: repair the outbound queue's version stamps before
        // ANYTHING drains it. Blocks queued before this device's schema moved are
        // sealed at the old vector (the stamp rides inside the ciphertext and a drain
        // cannot see it), and the §7.3 fence makes that the normal case — a fenced
        // device is push-allowed and keeps queuing. Placed here, after the enabled
        // guard, so realm keys are present; one-shot, and inert with no registered
        // migration chains.
        runOneShot("pendingBlockRestamper.restampOnce") { pendingBlockRestamper.restampOnce() }

        // C8 (`sync-device-catch-up.md` §5.7): a device still catching up fetches its seed
        // slices and renders from them, rather than waiting for the backward walk to reach the
        // rows its first screen needs. One-shot; skipped entirely once catch-up is complete;
        // and it touches no cursor — see [SliceSnapshotReader.bootstrapOnce] for why moving
        // `lastSyncTimestamp` here would strand every row outside the snapshot.
        //
        // Placed after the enabled guard so realm keys are present, and before the drains so a
        // joiner's first tick is also the tick that makes it usable.
        //
        // ⚠⚠ Everything from here to the end of the tick sits inside one bracket whose `finally`
        // settles [catchUp] (`ios-sync-activity-indicator.md` §3.1). It opens BEFORE the bootstrap
        // because the bootstrap can raise: a tick that throws, or is cancelled inside the slice
        // fetch, must still settle what it raised. [SyncCatchUp.settle] does not suspend, so it
        // runs under cancellation too; after [stop] it is refused and the stop's clear stands.
        try {
            raiseBeforeBootstrap()
            runOneShot("sliceSnapshotReader.bootstrapOnce") { sliceSnapshotReader.bootstrapOnce() }
            drainAfterBootstrap()
        } finally {
            catchUp?.settle(tickBacklog)
        }
    }

    /**
     * Raise rule 1 (`ios-sync-activity-indicator.md` §3.1): the slice bootstrap is about to GET
     * and apply the rows a joining device's first screen needs.
     *
     * The bootstrap has no cheap "a slice exists" step to raise after — the blob GET *is* the
     * existence check — so the gate is durable evidence that this device is still joining: its
     * first pull never finished, or a persisted backward walk has pages left. The second clause
     * matters after a merged-lane handover, where the forward cursor is promoted to a live tail
     * (and the marker can read `COMPLETED`) while history is still outstanding.
     *
     * ⚠ Also gated on the pull being open at all. A never-funded, local-only device has no first
     * pull to finish and reaches no relay; without this it would raise on every launch.
     */
    private suspend fun raiseBeforeBootstrap() {
        catchUp ?: return
        if (!sliceSnapshotReader.willFetch()) return
        if (!storageGate.shouldSync() && !syncManager.realmRelayRegistered) return
        if (firstPullCompleted() && !backfillUnfinished()) return
        catchUp.raise()
    }

    /**
     * Whether this store has finished a first forward pull, by the marker table.
     *
     * ⚠ No marker with a cursor above zero is a device upgraded from a build without the marker:
     * it pulled before, so it counts as completed. No marker with a zero cursor never pulled.
     */
    private fun firstPullCompleted(): Boolean = when (syncManager.firstPullMarker) {
        FirstPullMarker.COMPLETED -> true
        FirstPullMarker.STARTED -> false
        null -> forwardCursorMoved()
    }

    /**
     * Whether this store has pulled its realm's whole history: [pulledHistoryComplete], the one
     * definition the orphan sweep reads too (B.22.6; the sweep asks this function where it is wired).
     *
     * - ⚠ A marker row this build does not recognise is **not pulled**, as
     *   [ObjectStore.hasPulledItsHistory] answers, never read as "no marker" and then as an upgrade.
     * - ⚠⚠ A backward-walk floor above zero counts as unfinished **only while this
     *   process would walk it** ([mergedLaneAvailable]), [raiseBeforePull]'s rule. A floor left by a
     *   relay that has since lost the merged lane is never advanced again; counted, it would hold the
     *   sweep and the watermark (and so the realm's tombstone GC) off for ever.
     */
    fun storeHistoryPulled(): Boolean = try {
        if (syncManager.firstPullMarkerUnrecognised) {
            false
        } else {
            pulledHistoryComplete(
                firstPullMarker = when (syncManager.firstPullMarker) {
                    FirstPullMarker.COMPLETED -> ObjectStore.FIRST_PULL_COMPLETED
                    FirstPullMarker.STARTED -> ObjectStore.FIRST_PULL_STARTED
                    null -> null
                },
                forwardCursorMoved = forwardCursorMoved(),
                backfillFloor = syncManager.backfillCursor.takeIf { mergedLaneAvailable() },
            )
        }
    } catch (e: Exception) {
        // Unknown is not complete.
        println("SyncScheduler -> the store's pull markers could not be read ($e): the history is not taken as pulled")
        false
    }

    /** Whether this process walks the backward lane at all: [raiseBeforePull]'s condition. A test seam. */
    internal var mergedLaneAvailable: () -> Boolean = { mergedLaneSupported && mergedLaneWired() }

    /** The watermark gate is said once per process, not at every tip. */
    private var loggedWatermarkHeld = false

    private fun forwardCursorMoved(): Boolean =
        syncManager.lastSyncTimestamp > 0L || syncManager.lastTombstoneSyncTimestamp > 0L

    /**
     * Whether a **persisted** backward walk still has pages. `null` (never walked) is not
     * evidence: on a relay without the merged lane no cursor is ever stored, so absence says
     * nothing, and the first-pull marker is the only durable sign there.
     */
    private fun backfillUnfinished(): Boolean {
        val floor = syncManager.backfillCursor
        return floor != null && floor != 0L
    }

    /**
     * Raise rule 2 and the marker's first two rows: called once the pull is allowed, before its
     * request is sent.
     *
     * ⚠ The backward walk counts only while this process would actually walk it. A cursor left
     * over from a merged-lane relay that has since lost the route is never advanced again, and
     * raising on it would light the flag before every pull for ever.
     */
    private suspend fun raiseBeforePull() {
        // ⚠ A marker this build does not know is not an upgraded store. Latching it
        // COMPLETED here would make "unknown" read as pulled; it restarts as STARTED instead, and the
        // pull completes it as it would any first pull.
        if (syncManager.firstPullMarkerUnrecognised) {
            syncManager.firstPullMarker = FirstPullMarker.STARTED
            catchUp?.raise()
            return
        }
        when (syncManager.firstPullMarker) {
            FirstPullMarker.COMPLETED -> Unit
            FirstPullMarker.STARTED -> {
                catchUp?.raise()
                return
            }
            null -> if (forwardCursorMoved()) {
                syncManager.firstPullMarker = FirstPullMarker.COMPLETED
            } else {
                // Written BEFORE the request, so a kill mid-pull is still told from an upgrade.
                syncManager.firstPullMarker = FirstPullMarker.STARTED
                catchUp?.raise()
                return
            }
        }
        if (backfillUnfinished() && mergedLaneSupported && mergedLaneWired()) catchUp?.raise()
    }

    /**
     * Raise rule 3, **the one place a relay page is applied from**: a response carrying rows
     * raises [catchUp] before its first row is applied, on every lane and path.
     *
     * ⚠⚠ A new pull path must apply through here. The rule is "rows are about to be applied",
     * not a list of call sites, and a path that applies on its own is silently invisible.
     *
     * The raise suspends until the status row is written, so even a one-page pull whose apply
     * and settle share a tick is observable as `true` while it applies.
     */
    private suspend inline fun applyPage(rowCount: Int, apply: () -> Unit) {
        if (rowCount > 0) catchUp?.raise()
        apply()
    }

    /** The rest of [tickOnce] after the bootstrap, split out so its bracket reads in one screen. */
    private suspend fun drainAfterBootstrap() {
        // A pending "Sync now over cellular" override: one content drain past the closed
        // metered gate. It does not unblock the tick — the tick is never metered-gated — only
        // the deferred *content* the gate is actually holding (§4).
        val forced = forceDrainRequested.compareAndSet(expect = true, update = false)
        if (forced) {
            println("SyncScheduler: consuming the forceDrainOnce override — uploading deferred content over the metered network")
        }

        // About to push. Raise the drain-liveness signal *before* draining if a backlog
        // exists and push is actually permitted, so the platform FGS keeps this process
        // alive across the (potentially tens-of-seconds) sequential push. Otherwise a
        // background/unbind cancels the session mid-pass and strands the backlog (§4.6).
        // Recomputed in the finally once the pass has (or hasn't) cleared the queue.
        updateDrainLiveness(pushPermitted = pushPermittedNow())
        syncHealthHolder?.markSyncing()
        tickOnline = null
        tickAuthorized = null
        try {
            drainAndPull(forceContent = forced)
        } finally {
            updateDrainLiveness(pushPermitted = pushPermittedNow())
            reportSyncHealth()
        }
    }

    /**
     * One C1 recovery attempt, logged only when it did something. A failure is deliberately silent
     * beyond the outcome: the relay being unreachable is already reported by the pull path, and this
     * runs on a window that repeats.
     */
    private suspend fun recoverEntitlementBeforeProbing() {
        when (val outcome = entitlementRecovery.recoverIfStale()) {
            is EntitlementRecovery.Outcome.Fetched -> println(
                "SyncScheduler: entitlement recovery — fetched ${outcome.applied} block(s), " +
                    "usable=${outcome.usable}"
            )
            // Nothing to say: either the row was already usable (so the 402 is not about the
            // record), or there is no relay/owner to ask, or the call failed and will repeat.
            else -> Unit
        }
    }

    /**
     * Whether a personal-realm push is permitted *right now*, independent of the metered
     * [syncGate] (which [tickOnce] gates on separately, and a "Sync now over cellular"
     * override bypasses). Mirrors the `pushAllowed` gate in [drainAndPull]: a backlog only
     * warrants holding the process alive if it can actually drain.
     */
    private suspend fun pushPermittedNow(): Boolean =
        storageGate.shouldSync() && !entitlementBackoff.isBackingOff()

    /**
     * Raise/lower the auxiliary [backgroundLiveness] signal: active iff push is permitted
     * ([pushPermitted]) AND a personal backlog is waiting. Best-effort — a failure reading
     * the backlog degrades to idle rather than throwing (mirrors [reportSyncHealth]). No-op
     * when no liveness source is wired (desktop/test).
     */
    private suspend fun updateDrainLiveness(pushPermitted: Boolean) {
        val liveness = backgroundLiveness ?: return
        liveness.setActive(pushPermitted && drainableBacklog() > 0)
    }

    /**
     * What a push could send **right now**: the queued blocks plus the content the metered gate
     * currently lets through. ⚠ Content still held by a closed gate contributes nothing —
     * `contentDrainable` is false then, so a metered device under Wi-Fi-only never counts it.
     * Content that is *about to upload* does count: a deferred backlog released by reaching
     * Wi-Fi (or by the user's override) is exactly the long sequential push §4.6 built the
     * liveness signal for. Best-effort: a failed read degrades to 0 rather than throwing.
     */
    private suspend fun drainableBacklog(): Int = try {
        pendingBlockCount() + if (contentDrainable()) pendingContentEmitCount().count else 0
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        0
    }

    /**
     * The backlog a push job should drain: [drainableBacklog] when a personal push is permitted
     * at all, else 0 — the same figure the [BackgroundLiveness.SYNC_DRAIN] source is raised on.
     * Read by Android's `SyncPushWorker` after each [drainNow] to decide whether to go on
     * (`control-background-work-off-fgs.md` §5.3).
     */
    suspend fun pushableBacklog(): Int = if (pushPermittedNow()) drainableBacklog() else 0

    /**
     * Bytes of file content a push could upload **right now** — 0 while the metered gate holds it —
     * what decides whether a push job is small enough to run expedited. `null` when it cannot be read.
     *
     * ⚠ Not every pending byte: with 200 MB held by Wi-Fi-only on cellular, counting the held bytes
     * would make a small metadata edit's push a regular job, which can wait hours in RARE or under Doze.
     */
    suspend fun drainablePendingContentBytes(): Long? = try {
        if (contentDrainable()) pendingContentEmitCount().bytes else 0L
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

    /**
     * Whether deferred content could actually upload right now: either the metered gate is open,
     * or the user's one-shot override is pending. Mirrors the reasoning in [pushPermittedNow] —
     * a backlog only warrants holding the process alive if it can drain.
     */
    private suspend fun contentDrainable(): Boolean =
        (syncGate.shouldSync() || forceDrainRequested.value) && !storageBackoff.isBackingOff() &&
            // ⚠⚠ Nor while the content plane waits: the drain tries no entry then, and counting
            // them would run a push job ~2 minutes for nothing before it parked.
            // `SyncPushScheduler` queues a run for when the wait ends instead.
            (forceDrainRequested.value || !contentPlaneWaiting())

    /** The per-tick drain+pull body, split out so [tickOnce] can bracket it with health reporting. */
    private suspend fun drainAndPull(forceContent: Boolean = false) {
        // Personal-realm PUSH (genesis + outbound drain) is gated on a bound
        // storage subscription / owner-funded join ([storageGate]). Without one the
        // personal realm is local-first on the write side: saves still write to the
        // local DB and queue as pending blocks, but nothing is pushed to the relay.
        // The queued blocks flush on the first tick after the subscription binds.
        // Skip the personal push drain while inside an entitlement `402` backoff window:
        // the relay is rejecting this device's writes and retrying now would just re-storm. Pull
        // still runs below (it is not entitlement-gated), so a synced `Entitlement`/revocation or a
        // reconnect can be observed and clear the block. The window is short at first (2s) and caps
        // at 2 min, so recovery after enforcement relaxes is prompt without a restart.
        // C1 (`sync-device-catch-up.md` §5.3): we are entitlement-blocked and the backoff window
        // has just elapsed, so the drain below is about to make a probing push. If the reason we
        // are blocked is that our `Entitlement` row is missing or expired, that probe is doomed —
        // and the row that would fix it may be sitting near the relay tip, hundreds of thousands of
        // seq ahead of our cursor. Fetch it directly first, so the retry we are about to make can
        // actually succeed instead of re-arming the backoff.
        //
        // ⚠ Placement is load-bearing on both sides. BEFORE the drain, so a successful recovery is
        // spent immediately rather than a window later; and at the *probing boundary*
        // (`isBlocked && !isBackingOff`), so the existing geometric window rate-limits it — an
        // unentitled device costs one extra request per 2 min, not one per tick.
        if (entitlementBackoff.isBlocked && !entitlementBackoff.isBackingOff()) {
            recoverEntitlementBeforeProbing()
        }

        // The same shape for a sustained `401`, and for the same reason: repair at the probing
        // boundary so the window rate-limits the attempt, and BEFORE the drain so a successful
        // repair is spent on this tick rather than the next one.
        if (registrationBackoff.isBlocked && !registrationBackoff.isBackingOff()) {
            recoverRegistration()
        }

        val pushAllowed = storageGate.shouldSync() &&
            !entitlementBackoff.isBackingOff() &&
            !registrationBackoff.isBackingOff()
        if (pushAllowed) {
            // 0. Lazy realm genesis (ADR 0019): a realm created local-only
            // (no subscription) isn't registered on the relay yet. With relay
            // traffic unlocked, ensure `POST /sync/realm` has run before the
            // first push — otherwise authed pushes 401. Idempotent + self-retrying:
            // a no-op once registered, retried next tick if it fails (offline).
            realmRegistrar?.ensureRegistered()

            // 1. Send outbound changes (blocks then tombstones — pre-revoke
            // SyncBlocks should reach the relay before the tombstone that
            // supersedes them so receivers tombstone-short-circuit cleanly).
            drainPendingBlocks()
            // ⚠⚠ Re-checked, not inherited from `pushAllowed` above. That boolean was computed
            // BEFORE the block drain, and a queue-wide `402`/`401` inside it arms the matching
            // backoff mid-pass — so without this the tombstone drain proceeds on a stale answer.
            // It matters because `/sync/push-tombstone` is **ungated at the relay**: the tombstone
            // lands while the blocks that belong with it do not, splitting one user action across
            // the two planes. A file delete writes both — `removeFromArray` on the parent
            // `Folder.files` plus a `FileDeletedNotification` (ordinary blocks) *and* the entity
            // tombstone — so peers would drop the `File` while their folder still lists it.
            // Pinned by `DrainSplitOnRejectionTest`.
            if (!entitlementBackoff.isBackingOff() && !registrationBackoff.isBackingOff()) {
                drainPendingTombstones()
                // After the tombstones, so a folder's own cascade usually lands first; the
                // relay applies both at the same T, so the order is not load-bearing.
                drainReapRemovals()
            }

            // 1c. Push file content the metered gate deferred (`sync-metered-plane-split.md`
            // §4). Runs *after* the metadata drain, which is what keeps the entity-before-bytes
            // invariant intact: the entity block was emitted (and has just been pushed) on the
            // ungated metadata plane, so the manifest block this produces necessarily carries a
            // later HLC. Self-gating and cheap when the journal is empty — the drain itself
            // re-checks the content gate unless [forceContent] carries the user's one-shot
            // "upload over cellular" override.
            // ⚠⚠ Skipped entirely while backing off a `507`: the drain's own per-pass latch stops
            // it after the *first* rejection, but only this keeps the next tick from re-attempting
            // the whole journal a second later. Both halves are needed — the latch bounds one pass,
            // the backoff bounds the sequence of passes.
            if (!storageBackoff.isBackingOff()) {
                val emittedBefore = contentEmittedTotal()
                drainDeferredContent(forceContent, ::publishUploadProgress)
                // ⚠ Uploaded, not resolved: a pass whose every PUT failed resolves every entry too.
                tickAccepted += (contentEmittedTotal() - emittedBefore).toInt().coerceAtLeast(0)
                // The drain reports through a latch rather than a throw (see
                // `SyncObserver.contentStorageRejected`), so classify it here — the same place the
                // block drain classifies its own 402/401 rejections.
                //
                // ⚠⚠ This asks "did the relay refuse us THIS pass", NOT "is the relay full". It is
                // wired to `SyncObserver.consumeStorageRefusal` — a read-and-clear edge — precisely
                // because the underlying latch is a sticky level that only an accepted write
                // clears. Classifying on the level reports a rejection on a pass that attempted
                // nothing (empty journal, or the content gate shut on cellular) and on one that
                // failed for an unrelated reason (relay 500, offline, a file since deleted). Each
                // re-arms the backoff for ever, so the clearing branch below is never reached,
                // `relayRejected` stays set, and `UploadFile` refuses every upload with bytes free.
                //
                // ⚠ Seen on a phone with 2.45 GB free: banner "Storage full", every upload refused,
                // cleared instantly by a force-stop because the latch is in-memory. The device that
                // cannot escape is the one with nothing queued — it can never
                // produce the accepted write the sticky latch demands, because the latch is what
                // refuses the upload that would produce it.
                if (contentStorageRejected()) {
                    onStorageRejectionChanged(true)
                    if (!storageBackoff.isBlocked) {
                        println(
                            "SyncScheduler -> relay refused file content for space (507); backing " +
                                "off. Content uploads resume when the owner frees bytes or raises " +
                                "their tier — metadata sync is unaffected."
                        )
                    }
                    storageBackoff.onRejected()
                } else {
                    // ⚠ Only on a pass that actually ran and rejected nothing — a pass skipped by
                    // the backoff above never reaches here, so a blocked device is not repeatedly
                    // told it is fine.
                    if (storageBackoff.isBlocked) onStorageRejectionChanged(false)
                    storageBackoff.onCleared()
                }
            }
        }

        // Personal-realm PULL opens on push OR an already-registered realm
        // (storage-entitlement-sync.md cross-device bootstrap): the inbound pull is
        // what delivers the owner's `Entitlement` to a device whose own `hasStorage`
        // is still false, so gating it on the *local* figure deadlocks any device
        // that isn't the current subscription holder. `realmRelayRegistered` is the
        // safe opener — it is only ever true for a realm that was funded at least
        // once, so this never lets a never-funded realm reach the relay. ⚠ A joiner
        // does not latch it on its first pull: `RealmRegistrar.ensureRegistered` sets it
        // (via `lastSyncTimestamp > 0`) on the first PUSH-allowed tick after that pull.
        // That is never a gap in production, because a paired joiner's `storageGate`
        // (`StorageSyncGate`) is held open by `joinedFundedRealm`, which pairing sets and
        // nothing clears. Only a test that swaps the gate for a bare lambda can shut a
        // joiner's gate before the latch (`docs/testing.md` §12).
        // Realm schema pull fence (core-schema-evolution.md §7.3): if the realm's
        // epoch records a service version above what this device declares, the inbound
        // blocks are shapes this device's positional decoders would garble mid-parse — so
        // skip the pull entirely (no fetch: cheaper than fetch-and-discard, and a pull that
        // never runs cannot half-apply). The cursor is left where it is *by construction*:
        // it only ever advances inside the branch below, so a fenced device never looks
        // "caught up" and the skipped blocks are still waiting once it updates.
        //
        // Evaluated unconditionally — the typed SchemaFenceStatus signal must be accurate
        // even for a device whose pull isn't otherwise allowed (local-first, never funded) —
        // and deliberately AFTER the push drain above: pull-fenced, push-allowed (§7.3), so
        // this device's pending old-format writes still reach peers, which upcast them
        // (§8.2). See [SchemaPullFence] for what it deliberately does not fence on
        // (unknown services, core-version skew).
        val schemaFenced = schemaPullFence.evaluate()
        // ⚠⚠ The `401` backoff gates the PULL too — unlike the entitlement backoff, which
        // deliberately keeps pulling (that is how the `Entitlement` row that clears it arrives). A
        // relay that refuses this device's identity serves it *nothing*, so a pull is as doomed as a
        // push, and the pull is where the storm lives: of ~40k rejected requests measured in five
        // hours, `POST /sync/log` (a pull) was 19,646. Gating only the push would cut the request
        // count roughly in half and read as fixed.
        //
        // This cannot deadlock the recovery: the probing boundary above clears the window for the
        // rest of the tick (`allowImmediateRetry`), so exactly one pull per window still goes out —
        // which is also what lets a device whose 401 had another cause (skew) recover on its own.
        val pullAllowed = (pushAllowed || syncManager.realmRelayRegistered) &&
            !schemaFenced &&
            !registrationBackoff.isBackingOff()
        if (pullAllowed) {
            // 2. Pull inbound changes — **one cursor per lane**
            // (`sync-lane-cursor-split.md` L1). Each lane asks from, and advances on, its
            // own evidence: the highest `relay_seq` that lane actually served.
            //
            // *Never pass a seq the relay did not serve* holds **by construction**, because no
            // lane's cursor is ever moved by the other lane's result.
            //
            // ⚠ Never share one cursor between the lanes: they share the relay's counter but not
            // its density. Measured on-device, 500 blocks spanned ~56 000 seq while 500 tombstones
            // spanned exactly 500, so a `min` of the two pins *both* lanes to +500/tick and
            // re-delivers the same ~500 blocks every tick — ~85 wasted ticks to cross one block
            // backlog.
            //
            // ⚠ Ordering: the lanes may drift arbitrarily far apart, so a tombstone can
            // be applied long after a block that follows it in `relay_seq`. That is safe
            // only because ingest is order-independent on *both* sides — see L1a in
            // `SyncProcessor.processDeleteBlock`.
            //
            // L3 supersedes all of the above where the relay supports it: ONE ordered log,
            // ONE cursor, and the density asymmetry cannot express itself at all. The
            // two-lane path is the fallback for a relay without `/sync/log`, and is what the
            // merged path degrades to — never an error.
            raiseBeforePull()
            val forward =
                if (mergedLaneSupported && mergedLaneWired()) pullMergedLog()
                else pullSeparateLanes()
            val backlogRemains = forward.backlog
            // The first pull is over only when every forward lane ANSWERED and none has more.
            // Written last: by here the page is applied and its cursors persisted, so a kill
            // before this line leaves `STARTED` and the next launch still shows the signal.
            if (forward.caughtUp && syncManager.firstPullMarker != FirstPullMarker.COMPLETED) {
                syncManager.firstPullMarker = FirstPullMarker.COMPLETED
            }

            // C2 (`sync-device-catch-up.md` §5.4): the forward pass above walks UP from where
            // this device left off; this one walks DOWN from the relay's tip, so recent state
            // arrives first instead of after hours of crawling through July. They stop when they
            // meet, so every seq is read by exactly one of them — the forward path is unchanged.
            val backfillRemains = backfillOnce()
            // Catching up on a backlog: keep ticking instead of waiting out the poll
            // interval, or a large history would drain one page per interval (a 49k-block
            // backlog at the cold cadence is hours). The loop still re-evaluates every gate
            // each pass, so this only shortens the wait.
            if (backlogRemains || backfillRemains) {
                tickBacklog = true
                wakeSignal.wake()
            }

            // ⚠⚠ Isolated from the tick on purpose. This is housekeeping and sync is what the
            // user is waiting on, so a sweep that throws costs a log line — never the tick, and
            // never the pull that has already been applied above.
            // ⚠ The outer tick loop already catches, so this is not what keeps sync alive — it keeps
            // the *rest of this tick* alive. The hook sits inside the pull block, and shared-realm
            // sync runs after it; an escaping throw would skip that and print a stack trace for a
            // housekeeping failure. Narrower than it looks, and deliberately so.
            onTickSettled?.let { settled ->
                try {
                    settled()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("SyncScheduler -> post-tick work failed, sync unaffected: $e")
                }
            }
        }

        // Shared realms sync regardless of the personal storage gate: their
        // storage is funded by the realm owner, so an invited free member still
        // pushes/pulls them: drain then pull, isolated per-realm.

        // 1b. Drain each shared realm's own outbound queue to its own relay.
        // Isolated from the personal drain above: a shared realm's blocks are queued under a per-realm relation and pushed to a
        // per-realm relay (its own ownerId stream).
        drainSharedRealms()

        // 2b. Pull each shared realm's own inbound stream through its own
        // processor + cursor. Schema-fenced **per realm** against that
        // realm's own epoch (§7.3): a realm whose owner writes at a version this device
        // lacks is skipped, while every other realm — and the personal one — keeps
        // pulling. Isolated from the personal pull above: a shared realm's `getChanges`
        // is keyed to *its* ownerId/relay and decrypted under *its* keys, advancing a
        // per-realm cursor — the personal `lastSyncTimestamp` is never touched by
        // shared traffic. The verdicts were computed by the `evaluate()` above.
        pullSharedRealms()

        // 3. Service piggyback checks (see [addSyncCycleHook]). Self-gating
        // and isolated: one failing hook neither breaks the cycle nor the
        // other hooks.
        syncCycleHooks.toList().forEach { hook ->
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("SyncScheduler: sync-cycle hook failed; continuing — $t")
            }
        }

        // 4. G3 orphan sweep (`core-local-store-orphan-gc.md`) — LAST, so a store still carrying
        // retired-block garbage never delays a push or a pull. One bounded page per tick; self-refusing
        // and self-terminating. Isolated like the hooks above: cleanup must never break a cycle.
        retiredBlockSweeper?.let { sweeper ->
            try {
                sweeper.sweepOnce()?.let { pass ->
                    if (pass.deleted > 0 || pass.done) {
                        println(
                            "SyncScheduler: retired-block sweep — scanned=${pass.scanned} " +
                                "deleted=${pass.deleted} done=${pass.done}"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("SyncScheduler: retired-block sweep failed; continuing — $t")
            }
        }
    }

    /**
     * Classify a failed relay call for the reachability + registration signals.
     *
     * ⚠ A `401` is the relay **answering**, so it proves reachability while telling us the identity
     * was refused. Reporting it as `tickOnline = false` is a double miss: the health row claims
     * "Offline" — pointing the user at their network — and the one state that *is* actionable never
     * surfaces (on the live relay: five hours of 401s on every request, reported as nothing at all).
     */
    private fun noteRelayFailure(cause: Throwable) {
        if (cause.isRelayUnauthorized()) {
            registrationBackoff.onRejected()
            tickOnline = true
            // ⚠⚠ Reachable but NOT authorized — the whole reason these two are separate fields.
            tickAuthorized = false
        } else {
            tickOnline = false
            tickAuthorized = false
        }
    }

    /**
     * A relay call round-tripped **and** was authorised: the relay is reachable and it holds a
     * registration for this device, so any prior `401` block is stale — clear it so the next tick
     * runs at full cadence with no residual window.
     */
    private fun noteRelayAccepted() {
        tickOnline = true
        // The only place this becomes true: the relay round-tripped AND accepted the identity.
        tickAuthorized = true
        registrationBackoff.onCleared()
    }

    /**
     * Repair this device's relay registration after a sustained `401`, then let the next drain
     * retry (`RealmRegistrar.reregisterThisDevice`).
     *
     * **Why this exists.** `RealmRegistrar.ensureRegistered` latches once-ever, so a device whose
     * registration the relay has lost — a restore from a backup predating its admission, an
     * operator data wipe, a move to another relay host — never re-bootstraps on its own. The only
     * other automatic recovery is `HostPairingMonitor`'s, which fires solely while the host pairing
     * poll is running (`_isPairing == true`). Without this, the consequence is ~8k rejected
     * requests/hour/device, forever, with no path back.
     *
     * **Gated on A1** ([RealmRegistrar.masterIdentity]): re-asserting genesis / self-admitting needs
     * the master identity. A pairing **joiner** holds none, so for it a `401` is genuinely terminal
     * — it must be re-paired or re-admitted by another device — and the honest thing is to back off
     * and say so in the health signal rather than retry a repair it cannot perform.
     *
     * ⚠ Deliberately not conditioned on *why* the relay said `401`: it refuses to tell us, on
     * purpose (see [UNAUTHORIZED_REJECTION_MARKER]). The repair is safe under that ambiguity instead —
     * idempotent, granting no new authority, and failing back into the same backoff window when the
     * cause was something else (skew, a wrong key).
     *
     * Called at the *probing boundary* (`isBlocked && !isBackingOff`), so the geometric window rate
     * limits it: one extra request per window, not one per tick.
     */
    private suspend fun recoverRegistration() {
        val registrar = realmRegistrar ?: return
        val masterKeyPair = registrar.masterIdentity() ?: return
        val result = try {
            registrar.reregisterThisDevice(masterKeyPair)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("SyncScheduler -> re-registration after 401 failed; will retry — $t")
            return
        }
        if (result !is TResult.Failure<*>) {
            println("SyncScheduler -> re-registered this device on the relay after a 401; resuming sync.")
            // Let the very next call go out, but keep the rejection count: the relay accepting the
            // repair is not proof it will accept our traffic, and a full reset would re-storm every
            // 2s against a relay that keeps saying 401. `noteRelayAccepted` clears the block for
            // real, once a call actually succeeds.
            registrationBackoff.allowImmediateRetry()
            wakeSignal.wake()
        }
    }

    /**
     * Report this tick's health to the [syncHealthHolder]: relay reachability
     * (from [tickOnline]), the current backlog, and — only on a successful pull —
     * the wall-clock last-sync stamp. Best-effort: a failure reading the backlog
     * must not mask the tick's real outcome, so it degrades to 0 rather than throw.
     */
    private suspend fun reportSyncHealth() {
        // Eager re-drive on reconnect (proposal §6a): the relay was unreachable last tick and is
        // reachable now → the situation may differ (a token could be fetchable, enforcement could
        // have changed), so clear any 402 backoff and wake the loop to re-push immediately. Done
        // regardless of whether a health holder is wired. `tickOnline == null` (no relay call this
        // tick) leaves `previousOnline` untouched.
        if (previousOnline == false && tickOnline == true) {
            entitlementBackoff.reset()
            // The network came back: a content plane waiting out the outage tries again (§5.7).
            retryContentPlane()
            wakeSignal.wake()
        }
        tickOnline?.let { previousOnline = it }

        // Feed the raw outcome to the reachability verdict AFTER the re-drive above, which must
        // key on the true transition rather than the smoothed (deliberately laggy) reading.
        relayReachability.record(tickOnline, authorized = tickAuthorized)

        val holder = syncHealthHolder ?: return
        val pending = try {
            pendingBlockCount()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            0
        }
        // Deferred = the user's WifiOnly policy is withholding **file content** on a metered
        // network while uploads wait. A benign "Waiting for Wi-Fi" state, not an error
        // (`sync-metered-deferred-status.md` §2.2).
        //
        // ⚠ Keyed on the deferred-**content** journal, never the `PENDING_BLOCKS` backlog
        // (`sync-metered-plane-split.md` §0): that queue is realm-wide — every app's Syncable
        // writes, not Drive files — so it is non-zero on any multi-app device and would make the
        // banner claim files were waiting when they had all uploaded. It is also not metered-gated
        // at all, so it cannot be "deferred" by this policy in the first place.
        val gateOpen = syncGate.shouldSync()
        val deferredContent = try {
            pendingContentEmitCount()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DeferredContentFigures()
        }
        val deferred = !gateOpen && deferredContent.count > 0
        deferredLog.onTick(deferred = deferred, gateOpen = gateOpen, pending = deferredContent.count)
        // ⚠ Published from the figure this tick already read for the banner — the journal walk is
        // not repeated, and the two can never disagree about how much is waiting.
        onQueuedContentBytes(deferredContent.bytes)
        holder.onTickComplete(
            // The verdict, not the raw outcome: a failure must be recent AND sustained to be
            // reported as offline, or a single stale `false` masks the deferred banner forever
            // (RelayReachability's KDoc records the on-device case).
            online = relayReachability.isOnline(),
            // The strict twin of the line above, for the move fence. Same source, opposite bias:
            // `isOnline` is optimistic so the banner never flickers, this one is pessimistic so a
            // fence never waves through a write during the smoothing window.
            // ⚠⚠ `entitlementBlocked` closes it too, for the same reason the `401` split exists.
            // After a `402` on a metadata push the scheduler still runs the deliberately-ungated
            // PULL, and that accepted read calls noteRelayAccepted() — so a read succeeding would
            // otherwise certify that writes are accepted, while every write is being refused. Reads
            // are not evidence about writes; a 401 and a 402 are the same lesson twice.
            relayConfirmedReachable = relayReachability.isConfirmedReachable() &&
                !entitlementBackoff.isBlocked &&
                // ⚠⚠ The general guard. `isConfirmedReachable()` is fed by the pull paths alone, so
                // without this a tick whose push failed and whose pull succeeded certifies a move.
                !tickWriteRefused &&
                // ⚠⚠ …and a push that was never ATTEMPTED records no refusal. With the storage gate
                // shut (startup before storage figures load, a lapsed subscription on a registered
                // realm) every personal push is skipped while the pull still succeeds, so the flag
                // above stays false and the fence would open on a device that cannot publish the
                // move. Unregistered local-only realms are unaffected: the fence is
                // inert there via `relaySyncConfigured`, not via this figure.
                pushPermittedNow(),
            // ⚠⚠ Whether a relay is configured AT ALL, which is a different question from whether
            // it is reachable — and collapsing the two breaks a shipped mode. A never-funded
            // local-only realm is never registered, so by this class's own rule it "makes no relay
            // request at all": nothing ever records an attempt, `relayConfirmedReachable` stays
            // null for ever, and a fence that reads null as offline defers EVERY content-bearing
            // move permanently, with replay re-deferring it each pass. There is also nothing to
            // fence against there — the hazard D3 closes needs a peer that can delete the old
            // holder, and an unregistered realm has no peers.
            relaySyncConfigured = storageGate.shouldSync() || syncManager.realmRelayRegistered,
            pendingBlockCount = pending,
            lastSyncedAtMillis = if (tickOnline == true) nowMs() else null,
            entitlementBlocked = entitlementBackoff.isBlocked,
            deviceUnrecognized = registrationBackoff.isBlocked,
            storageRejected = storageBackoff.isBlocked,
            deferred = deferred,
            contentRetrying = deferredContent.count > 0 && contentPlaneWaiting(),
            locked = syncManager.getUnlockStatus().isLocked,
            deferredContentCount = deferredContent.count,
            deferredContentBytes = deferredContent.bytes,
        )
    }

    /**
     * Publish a pass's progress, **throttled** (`drive-upload-progress.md` P2). Each publish is a
     * store transaction that wakes every observing client, so a 500-file pass must not be 500 of
     * them; the first and last updates always go through, so the banner appears immediately and
     * never ends stuck mid-count.
     */
    private fun publishUploadProgress(completed: Int, total: Int) {
        val holder = syncHealthHolder ?: return
        val now = nowMs()
        val isEdge = completed == 0 || completed >= total
        if (!isEdge && now - lastProgressPublishMs < PROGRESS_PUBLISH_INTERVAL_MS) return
        lastProgressPublishMs = now
        holder.onUploadProgress(completed = completed, total = total)
    }

    private var lastProgressPublishMs = 0L

    /** Personal-realm outbound backlog size (pending blocks awaiting relay push). */
    private suspend fun pendingBlockCount(): Int =
        executionContext.database.getArrayData(pendingBlocksRef)?.toList()?.size ?: 0

    /**
     * Dequeue a block that is **finished** and delete the row it left behind
     * (`core-local-store-orphan-gc.md` G1).
     *
     * `addToArray` persisted the block when it was queued; `removeFromArray` alone only rewrites
     * the list's refs, so without this the row is immortal — 99.87% of measured `sync/block` rows
     * were exactly this garbage. Both halves run in one transaction so a crash between them cannot
     * leave a row that is neither queued nor collectable.
     *
     * ⚠ Only for a block that is genuinely done: pushed, or quarantined as unpushable. See
     * [deleteRetiredBlock] for the two paths where a removal is *not* terminal.
     */
    private fun retireBlock(queue: ArrayDatabaseReference, block: SyncBlock) {
        val dataId = executionContext.getDataId(block) ?: return
        executionContext.database.transaction {
            executionContext.database.removeFromArray(queue, dataId)
            executionContext.deleteRetiredBlock(block)
        }
    }

    internal suspend fun drainPendingBlocks() {
        val pendingBlocks = executionContext.database.getArrayData(pendingBlocksRef)
            ?: return blockDrainLog.onTick(failedCount = 0, cause = null)

        var failedCount = 0
        var quarantinedCount = 0
        var firstCause: Throwable? = null
        var sawEntitlementRejection = false
        val currentOwner = syncManager.currentOwnerId
        // ⚠⚠ A `for` loop, not `forEach`, because this pass must be able to STOP. Both doomed
        // rejection classes are scoped to something the whole queue shares — a `402` to the owner's
        // entitlement, a `401` to this device's identity — so once the relay refuses one block,
        // every remaining block in this pass is refused for the identical reason.
        //
        // The backoff bounds how often a PASS runs, never the fan-out INSIDE one: without the
        // break, the live relay saw repeating bursts of ~420 rejected `POST /sync/push` lasting
        // 11 s, with the gaps between bursts growing toward the 2 min cap exactly as designed.
        // Breaking here turns a burst into a single request — "one probe per window".
        val pushable = mutableListOf<Pair<SyncBlock, String>>()
        for (data in pendingBlocks.toList()) {
            val block = data as SyncBlock

            // Quarantine an orphaned-owner block before attempting a push. Its `ownerId`
            // belongs to a realm this device no longer participates in — the classic case
            // is a device that had its own realm, then joined another via pairing (a
            // re-key), leaving pre-join blocks behind. The relay enforces owner-binding,
            // so such a block 403s on *every* tick forever; left queued it hot-loops and
            // wedges the cycle (and its persistent failure masks healthy blocks behind
            // it). It can never succeed, so drop it rather than retry. Guarded on a known
            // current owner so a transient null (locked / not-yet-rehydrated) doesn't
            // discard blocks that are merely waiting for the owner to come back.
            if (currentOwner != null && block.ownerId.get() != currentOwner) {
                retireBlock(pendingBlocksRef, block)
                quarantinedCount++
                continue
            }
            // ⚠ Read up front: a resolution may drop a later block, and a dropped block is lazily backed.
            pushable += block to block.blockId.get()
        }

        // ⭐ Adds before removes (`content-plane-cr2-b4-spec.md` §2.3): the order this pass pushes in. Plain queue order unless
        // some block clears content, in which case it waits for the later block that adds it.
        val plan = AddsBeforeRemoves(pushable.map { (block, blockId) -> queuedEntry(block, blockId) })
        val blocksById = pushable.associate { (block, blockId) -> blockId to block }
        var stopped = false
        while (!stopped) {
            val next = plan.pushable().ifEmpty { listOfNotNull(plan.breakCycle()) }
            if (next.isEmpty()) break
            for (blockId in next) {
                if (!plan.isRemaining(blockId)) continue
                val block = blocksById.getValue(blockId)
                // executeAsync returns `T` directly and throws on typed failure
                // (`docs/proposals/core-error-channel.md`); the absence of a throw is the
                // cursor-advance gate. The cause is retained (rather than a bare boolean) so
                // the per-tick summary can report *why* a stuck queue is stuck.
                when (val step = pushResolving(pendingBlocksRef, block, blockId)) {
                    PushStep.Accepted -> {
                        retireBlock(pendingBlocksRef, block)
                        plan.done(blockId)
                        tickAccepted++
                    }
                    is PushStep.Dropped -> step.blockIds.forEach(plan::done)
                    PushStep.Held -> {
                        // Refused as freed and waiting (gate closed, revive incomplete): pending sync.
                        plan.attempted(blockId)
                        tickWriteRefused = true
                    }
                    is PushStep.Failed -> {
                        val cause = step.cause
                        plan.attempted(blockId)
                        failedCount++
                        // A refused metadata write, whatever the cause — see [tickWriteRefused].
                        tickWriteRefused = true
                        if (firstCause == null) firstCause = cause
                        if (cause.isEntitlementRejection()) sawEntitlementRejection = true
                        // A pushed block the relay refuses to authenticate: same doomed-retry shape as the
                        // 402, repaired (where the device can) at the next probing boundary. Never cleared
                        // here — an empty queue proves nothing about our registration, so only a call the
                        // relay actually *accepted* clears it (see [noteRelayAccepted]).
                        if (cause.isRelayUnauthorized()) registrationBackoff.onRejected()
                        // Stop the pass on a doomed rejection (see the loop's note). Everything still queued
                        // stays queued — this abandons the pass, never a block. A transient failure (network)
                        // still tries the rest: those are per-block, not queue-wide.
                        if (cause.isEntitlementRejection() || cause.isRelayUnauthorized()) {
                            stopped = true
                            break
                        }
                    }
                }
            }
        }
        // Entitlement (402) backoff bookkeeping: a rejection this drain grows the retry
        // window (skip pushing for a while); anything else means the block — if any — has cleared,
        // so reset. Reset also fires the eager re-drive the next tick (a cleared block re-pushes).
        if (sawEntitlementRejection) {
            entitlementBackoff.onRejected()
        } else {
            entitlementBackoff.onCleared()
        }
        // Failed pushes stay queued for the next tick. Logging is per-queue and
        // transition-only (see [DrainFailureLog]), so a relay outage with many
        // queued blocks does not flood stdout every hot tick. No per-block
        // success line.
        if (quarantinedCount > 0) {
            println(
                "SyncScheduler: dropped $quarantinedCount orphaned-owner block(s) from the " +
                    "personal queue — they belong to a realm this device left (re-key), so " +
                    "the relay would 403 them forever."
            )
        }
        // The backlog is what a reader wants to know; `failedCount` is 1 whenever a queue-wide
        // rejection stopped the pass, however many blocks are actually waiting.
        blockDrainLog.onTick(
            failedCount = failedCount,
            cause = firstCause,
            queuedCount = pendingBlocks.toList().size,
        )
    }

    /** What one push of a queued block came to, after any freed-content resolution. */
    private sealed interface PushStep {
        data object Accepted : PushStep

        /** A refusal resolved by dropping blocks (this one among them), sidecars included. */
        data class Dropped(val blockIds: Set<String>) : PushStep

        /** Refused as freed, and waiting for a later tick: not a failure, not retired. */
        data object Held : PushStep

        data class Failed(val cause: Throwable) : PushStep
    }

    /**
     * Pushes [block] through the `PushBlock` op and, on a typed `409` (`content-plane-cr2-b4-spec.md`
     * §2.2), resolves it with
     * the [freedContentResolver]: a repaired block is pushed once more at once with its revive, and
     * anything still refused after that waits for the next tick.
     */
    private suspend fun pushResolving(queue: ArrayDatabaseReference, block: SyncBlock, blockId: String): PushStep {
        repeat(2) {
            try {
                executionContext.executeAsync<Data>(
                    operationId = PushBlock.ID,
                    caller = null,
                    params = mapOf(PushBlock.Params.BLOCK to block)
                )
                return PushStep.Accepted
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (refused: ContentRefusedException) {
                val resolver = freedContentResolver() ?: return PushStep.Failed(refused)
                when (val resolution = resolver.resolve(personalBlockQueues, block, blockId, refused.refusal)) {
                    FreedContentResolver.Resolution.Retry -> Unit
                    FreedContentResolver.Resolution.Held -> return PushStep.Held
                    is FreedContentResolver.Resolution.Dropped -> return PushStep.Dropped(resolution.droppedBlockIds)
                }
            } catch (e: Throwable) {
                return PushStep.Failed(e)
            }
        }
        return PushStep.Held
    }

    /** One queued block as [AddsBeforeRemoves] needs it, from its edge sidecar. */
    private fun queuedEntry(block: SyncBlock, blockId: String): AddsBeforeRemoves.Entry {
        val sidecar = PendingBlockEdgeStore(executionContext)
        return AddsBeforeRemoves.Entry(
            blockId = blockId,
            holder = block.targetEntityIdHash?.get(),
            adds = sidecar.edgesOf(blockId).filter { !it.regenerable }.mapNotNull { it.contentId }.toSet(),
            cleared = sidecar.clearedOf(blockId),
        )
    }

    /**
     * ⭐ `content-plane-cr2-b4-spec.md` §2.3: every content id a queued, unacknowledged personal block adds — what a tombstone
     * or reap of a holder that named one of them must wait for. Read once per drain that needs it.
     */
    private fun queuedContentAdds(): Set<String> {
        val sidecar = PendingBlockEdgeStore(executionContext)
        return personalBlockQueues.flatMap { queue ->
            executionContext.database.getArrayData(queue)?.toList().orEmpty().filterIsInstance<SyncBlock>()
        }.flatMap { block -> sidecar.edgesOf(block.blockId.get()).filter { !it.regenerable }.mapNotNull { it.contentId } }
            .toSet()
    }

    /** ⭐ `content-plane-cr2-b3-wire-spec.md` §11: one tagger (one HKDF per realm) for every shared-realm drain. */
    private val sharedPropertyTags = ContentPropertyTagger(syncManager) { realmRegistry }

    /**
     * Push each shared realm's per-realm outbound queue to that realm's own
     * relay. Unlike the personal drain, this calls the realm's
     * [org.tenshu.sync.common.SyncRelay] directly (not the DI `PushBlock` op,
     * which is bound to the personal relay) — so a shared realm's blocks reach
     * its own ownerId stream, isolated from the personal one. Failed pushes stay
     * queued for the next tick.
     */
    internal suspend fun drainSharedRealms() {
        val registry = realmRegistry ?: return
        registry.sharedRuntimesWithRelay().forEach { runtime ->
            val relay = runtime.relay ?: return@forEach
            val ref = sharedPendingBlocksRef(runtime.context.ownerId)
            val pending = executionContext.database.getArrayData(ref) ?: return@forEach
            // ⚠ This drain has NO backoff of its own: a shared realm's entitlement belongs to that
            // realm's owner, so the personal `entitlementBackoff` would be the wrong instance to
            // consult and a per-realm one is a design question (recorded in tech-debt). What it can
            // do cheaply is refuse to fan out: a rejection is realm-wide, so stop this realm's pass
            // at the first one and let the next tick retry, rather than emitting one doomed request
            // per queued block. Other realms in the loop are unaffected.
            for (data in pending.toList()) {
                // ⚠ A realm unregistered mid-pass — "Forget this device" (§7i.5) — pushes nothing
                // more under the identity it was registered with.
                if (registry.runtimeFor(runtime.context.ownerId) !== runtime) break
                val block = data as SyncBlock
                // The edges the emit fixed from this block's payload. ⚠ None yet: a shared realm's
                // `SharedFile` declares no content edges until it has a tombstone path (§10.12 Q6).
                // Read anyway, so the day one does, this path is not the one that forgets.
                // ⭐ Under this realm's keyed property tags (B3 wire spec §11). No key ⇒ stays queued.
                val edges = sharedPropertyTags
                    .wireEdges(block, PendingBlockEdgeStore(executionContext).edgesOf(block.blockId.get()))
                    ?: continue
                val result = relay.pushBlock(block, edges)
                if (result is org.tenshu.core.TResult.Success) {
                    retireBlock(ref, block)
                } else if (result is org.tenshu.core.TResult.Failure<*>) {
                    val doomed = TenshuException(result.error)
                    if (doomed.isEntitlementRejection() || doomed.isRelayUnauthorized()) break
                }
            }
        }
    }

    /**
     * Pull each shared realm's own inbound stream. Mirror of
     * [drainSharedRealms] on the read side: for every registered shared realm with
     * a relay + processor, call that realm's [org.tenshu.sync.common.SyncRelay]
     * `getChanges` directly (not the DI `GetChanges` op, which is bound to the
     * personal relay), feed each returned block to the realm's own [SyncProcessor]
     * (decrypts under that realm's keys + advances that realm's HLC), and advance
     * the realm's own [RealmRuntime.cursor]. A realm without a processor (registry
     * built without a factory) is skipped — it pushes but can't yet pull.
     */
    internal suspend fun pullSharedRealms() {
        val registry = realmRegistry ?: return
        registry.sharedRuntimesWithRelay().forEach { runtime ->
            val relay = runtime.relay ?: return@forEach
            val processor = runtime.processor ?: return@forEach
            // Per-realm schema fence (§7.3): skip the fetch entirely rather than
            // fetch-and-discard, which also leaves `runtime.cursor` where it is — it is
            // only ever assigned below, so a fenced realm never looks caught up and its
            // skipped blocks are still waiting once this device updates. The push drain
            // (`drainSharedRealms`) deliberately ran already: pull-fenced, push-allowed.
            if (schemaPullFence.isFenced(runtime.context.ownerId)) return@forEach
            val tip = buildSharedTip(runtime) ?: return@forEach

            // ⚠⚠ Per-realm backoff, NOT the personal `registrationBackoff`. A shared realm has its
            // own registration and its own device JWT, so the personal realm being refused says
            // nothing about this one — gating on it would stop shared sync for an unrelated reason.
            // Without it, a failure swallowed by `getOrNull()` — a `401` on a shared realm with no
            // backoff, no health signal and no log — retries at full tick cadence forever: the
            // same shape as the measured personal storm (41 req/min for 31 h), on a path nothing
            // watches.
            val backoff = sharedRealmBackoff.getOrPut(runtime.context.ownerId) {
                RejectionBackoff(nowMs)
            }
            if (backoff.isBackingOff()) return@forEach

            val response = when (val result = relay.getChanges(runtime.cursor, tip)) {
                is TResult.Failure<*> -> {
                    if (TenshuException(result.error).isRelayUnauthorized()) {
                        // Log only on the transition, like the drain/pull logs beside it: a real
                        // outage lasts many ticks and one line per tick is spam.
                        if (!backoff.isBlocked) {
                            println(
                                "SyncScheduler -> shared realm ${runtime.context.ownerId} refuses " +
                                    "this device (401); backing off. This realm's registration is " +
                                    "separate from the personal one, which is unaffected."
                            )
                        }
                        backoff.onRejected()
                    }
                    return@forEach
                }

                else -> result.getOrNull() ?: return@forEach
            }
            // ⚠⚠ Unregistered while the fetch was in flight — "Forget this device" (§7i.5): the
            // store it would write into has just been wiped, and the identity that fetched is gone.
            if (registry.runtimeFor(runtime.context.ownerId) !== runtime) return@forEach
            // The realm answered and accepted our identity — clear any window so the next tick runs
            // at full cadence.
            backoff.onCleared()
            val blocks = response.blocks.toList()
            applyPage(blocks.size) {
                blocks.forEach { block ->
                    processor.processSyncBlock(block)
                }
            }
            runtime.cursor = response.nextCursor.get()
            // A shared realm needs no ceiling arithmetic (unlike the personal pull): it has
            // one lane, so the relay's page cap just makes this a prefix and the cursor lands
            // on the last block actually delivered. Only ask for another tick, so a large
            // shared backlog isn't drained one page per poll interval.
            //
            // Asks the shared rule rather than re-deriving it — see [PullOutcome.isFullPage].
            if (PullOutcome.isFullPage(blocks.size)) {
                tickBacklog = true
                wakeSignal.wake()
            }
        }
    }

    /**
     * The signed HLC tip a shared realm's [pullSharedRealms] poll piggybacks —
     * built from *that realm's* ownerId + its own clock (read off the realm's
     * processor), signed with the device's A4 key as registered for that realm.
     * Distinct from [buildSignedTip] (the personal realm): same device key, but a
     * different `ownerId`/`deviceId` pair and a different HLC, so the relay sees
     * the shared realm's stream tip, never the personal one.
     */
    private fun buildSharedTip(runtime: RealmRuntime): SignedHlcTip? {
        val processor = runtime.processor ?: return null
        val ctx = runtime.context
        val hlc = processor.currentRealmHlc()
        val signingKey = ctx.deviceSigningKeyPair.privateKey
        val message = hlcTipCanonicalMessage(ctx.ownerId, ctx.deviceId, hlc)
        val signature = cryptoManager.signEd25519(signingKey, message)
        return SignedHlcTip.create(
            context = executionContext.context,
            ownerId = ctx.ownerId,
            deviceId = ctx.deviceId,
            hlc = hlc,
            signature = signature
        )
    }

    private fun sharedPendingBlocksRef(realmId: String): ArrayDatabaseReference =
        ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = SyncBlock.ID,
                relationId = RealmRegistry.pendingBlocksRelationId(
                    SyncService.Collections.PENDING_BLOCKS,
                    realmId,
                )
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )

    /**
     * Sends the reaps this device owes the relay (`content-plane-cr2-b3-wire-spec.md` §3.3):
     * every [ReapRemovalOutbox] entry, at most [ContentBlobStore.MAX_REAP_BATCH] per request, until
     * the relay answers 2xx.
     *
     * ⚠ A refused or failed batch stops the pass and stays owed; the next tick retries it. Nothing here
     * is urgent, but nothing may be dropped either: a reap happens once, so a lost removal is an edge that stands for ever.
     * ⚠ Inert without a content store (test and legacy wirings): the outbox simply keeps its entries.
     */
    internal suspend fun drainReapRemovals() {
        val store = contentBlobStore ?: return
        val outbox = ReapRemovalOutbox(executionContext)
        // ⭐ B4 spec §2.3: a reap of a holder that named content a queued, unacknowledged block adds waits.
        val removedContents = RemovedContentsStore(executionContext)
        val adds by lazy { queuedContentAdds() }
        val owed = outbox.pending().filter { entry ->
            val removed = removedContents.contentsOf(entry.holder)
            removed.isEmpty() || removed.none(adds::contains)
        }
        for (batch in owed.chunked(ContentBlobStore.MAX_REAP_BATCH)) {
            val accepted = try {
                store.reapRemovals(batch.map { it.toRemoval() })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            if (!accepted) {
                reapDrainLog.onTick(failedCount = 1, cause = null, queuedCount = owed.size)
                return
            }
            outbox.remove(batch)
            batch.forEach { removedContents.consume(it.holder) }
        }
        reapDrainLog.onTick(failedCount = 0, cause = null)
    }

    /**
     * ⚠ Pushes are one-at-a-time (the relay route takes one block), but the
     * **queue maintenance is batched**: a per-block `removeFromArray` rewrites
     * the whole pending array each time, which is O(N²) over a realm-data reset's
     * ~10 k tombstones and hammers the one `DatabaseImpl` lock the UI thread also
     * needs (`control-data-reset.md` §13.5). Ids are collected and dropped in
     * [DRAIN_REMOVE_BATCH]-sized rewrites instead.
     *
     * ⚠ A block pushed but not yet removed (crash, cancel) is simply pushed again
     * on the next drain — `processDeleteBlock` + `TombstoneRegistry.record` are
     * idempotent, which is why deferring the removal is safe. The flush therefore
     * also runs on the way out of a cancellation, so a stopped drain still records
     * the work it really did.
     */
    internal suspend fun drainPendingTombstones() {
        val queued = executionContext.database.getArrayData(pendingTombstonesRef)
            ?: return tombstoneDrainLog.onTick(failedCount = 0, cause = null)

        // ⚠⚠ A block belongs to the realm it was STAMPED for, and this queue is drained with a JWT
        // minted for whichever realm is current. An owner adoption between an emit and its push —
        // `installReceivedPairingPayload` seating a different owner — leaves a block from the
        // superseded realm here, and pushing it answers `403` while its caller was told it was
        // emitted and has already deleted the row. ⇒ skip it, and leave it queued: the emit side's
        // pre-checks make this rare, and only this point can be certain, because it is the one that
        // knows which realm the push will actually authenticate as.
        // ⚠ Left, not dropped — `purgeOutboundQueues` discards them when the device leaves a realm
        // for good, which is the place that knows the difference between "superseded" and "not yet
        // unlocked".
        // ⚠⚠ The PERSONAL realm, because that is what this drain can actually authenticate as.
        // `PushTombstone` holds one injected `SyncRelay` — the personal one — and the relay answers
        // `403 tombstone.ownerId does not match authenticated owner` for anything else. Admitting a
        // shared realm's blocks here does not deliver them; it turns a silent skip into a permanent
        // per-tick failing request and pushes that realm's ownerId and wire name to the wrong relay.
        // ⚠ The block drain solved this with a per-realm path (`drainSharedRealms` calls the realm's
        // own `SyncRelay` rather than the DI op); the tombstone queue has no equivalent and is a
        // single un-partitioned array. ⇒ shared-realm tombstones have **no correct path** — named
        // in `tech-debt.md`.
        val currentOwnerId = syncManager.currentRealmContext()?.ownerId
        val pending = queued.toList()
            .filterIsInstance<DeleteBlock>()
            .filter { it.ownerId.get() == currentOwnerId }

        var failedCount = 0
        var firstCause: Throwable? = null
        val pushed = mutableListOf<DataId>()
        // ⭐ The blocks whose cascades went out with their push (`PushTombstone` reads each from the
        // store and carries it as the `CascadedTombstonePush`'s cascade), forgotten on the same
        // rhythm as the queue removals. No separate undeclare or successor declare follows: the
        // relay removes the subtree in the push's own transaction, and a moved holder names its
        // content with its own push.
        val cascadesPushed = mutableListOf<String>()

        fun flushPushed() {
            if (pushed.isEmpty() && cascadesPushed.isEmpty()) return
            if (pushed.isNotEmpty()) {
                tickAccepted += pushed.size
                executionContext.database.removeAllFromArray(pendingTombstonesRef, pushed.toList())
                pushed.clear()
            }
            // ⚠ After the queue removal, never before: a cascade dropped while its tombstone is still
            // queued would be re-pushed without its subtree.
            if (cascadesPushed.isNotEmpty()) {
                TombstoneCascades(DatabaseTombstoneCascadeStore(executionContext)).forget(cascadesPushed.toList())
                cascadesPushed.clear()
            }
        }

        // ⭐ Adds before removes (B4 spec §2.3): a tombstone whose holders — the target and its cascade —
        // named content a queued, unacknowledged block adds is held to a later tick. Read lazily: the
        // ordinary tombstone names nothing anyone adds, and then no block sidecar is read at all.
        val removedContents = RemovedContentsStore(executionContext)
        val cascadeStore = DatabaseTombstoneCascadeStore(executionContext)
        val adds by lazy { queuedContentAdds() }
        val consumed = mutableListOf<String>()
        var held = 0

        try {
            pending.forEach { block ->
                val holders = listOf(block.targetEntityIdHash.get()) +
                    cascadeStore.load(block.blockId.get())
                val removed = holders.flatMap { removedContents.contentsOf(it) }.toSet()
                if (removed.isNotEmpty() && removed.any(adds::contains)) {
                    held++
                    return@forEach
                }

                val cause: Throwable? = try {
                    // The relay's assigned seq comes back as the result (RH-1a α); nothing reads it.
                    executionContext.executeAsync<Data>(
                        operationId = PushTombstone.ID,
                        caller = null,
                        params = mapOf(PushTombstone.Params.BLOCK to block)
                    )
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }

                if (cause == null) {
                    pushed += executionContext.getDataId(block)!!
                    cascadesPushed += block.blockId.get()
                    consumed += holders
                    if (pushed.size >= DRAIN_REMOVE_BATCH) flushPushed()
                } else {
                    failedCount++
                    if (firstCause == null) firstCause = cause
                    // A tombstone is a metadata write too — and a move's own: it retires the old
                    // holder. See [tickWriteRefused].
                    tickWriteRefused = true
                }
            }
        } finally {
            flushPushed()
            // The removals this pass delivered no longer need their contents remembered (§2.3).
            consumed.forEach(removedContents::consume)
        }
        if (held > 0) {
            println("SyncScheduler: held $held tombstone(s) behind queued blocks that add their content (B4 §2.3)")
        }
        tombstoneDrainLog.onTick(failedCount, firstCause)
    }

    /**
     * One **backfill** page — the newest-first half of catch-up (`sync-device-catch-up.md` C2 /
     * §5.4). Returns whether more remains to walk.
     *
     * ## Three positions, not two
     *
     * - `lastSyncTimestamp` — the **live tail**. On the handover tick it JUMPS to the top of the
     *   first backward page, so new writes arrive within a tick from then on.
     * - [SyncManager.backfillCursor] — the floor of the backward walk, descending.
     * - [SyncManager.backfillTarget] — where the walk stops: the live tail's value *before* the
     *   jump, i.e. the boundary below which this device had already read everything.
     *
     * Together they cover the log exactly once: `[0, target]` was already read, `(target, floor]`
     * is what the walk is filling, and `(floor₁, ∞)` is the live tail's.
     *
     * ⚠⚠ The jump is half the fix. Without it the forward pass crawls up from wherever the device
     * left off, so **new** writes — which land at the tip, above everything the descending walk has
     * already passed — are reachable only by that crawl. Measured on a real iPhone: a 643 523-seq gap at 7.5 seq/s ⇒ ~24 h before
     * an upload made on another device showed up. Recent state arriving first is worthless if new
     * state still arrives last.
     *
     * ⚠⚠ **Applying newest-first is safe because ingest is HLC-guarded — and it is cheaper.**
     * `SyncProcessor.resolveConflictAndSave` writes **nothing** when an incoming block is older than
     * the row it targets (only `Manual` records a marker). Forward replay of an entity with five
     * superseded versions performs five writes; backward replay performs one and drops four. On the
     * measured log — ~5.5 blocks per live entity — that is most of the work avoided, so this is a
     * reordering that also happens to shrink the job.
     *
     * ## ⚠ Termination, and the three states of the cursor
     *
     * `null` ⇒ never walked, so start from the tip (`Long.MAX_VALUE`). `> 0` ⇒ ask below it. `0` ⇒
     * **complete** — reached either by exhausting the log (`hasMore == false`) or by meeting the
     * forward cursor. Both mean nothing is left to walk, so both store `0` and this returns early
     * forever after. Collapsing `null` and `0` would either re-walk the whole log every launch or
     * never walk it at all, which is why the store read is nullable.
     *
     * ⚠ Gated on the merged lane: a relay without `/sync/log` has no `before=` either, so a device
     * that fell back to the two-lane path simply does not backfill — it catches up forward only.
     * ⚠ Personal realm only; shared realms keep their own cursors.
     */
    private suspend fun backfillOnce(): Boolean {
        if (!mergedLaneSupported || !mergedLaneWired()) return false
        val floor = syncManager.backfillCursor
        if (floor == 0L) return false // complete

        // HANDOVER fires on `null` (nothing walked yet) — and ALSO on a walk that is already in
        // progress but carries no target.
        //
        // ⚠⚠ That second case is the migration. A store whose walk began before the target was
        // recorded has a non-null floor and no target, so a `floor == null` test skips it forever:
        // it keeps descending while its forward pass crawls — new uploads invisible for ~24 h.
        //
        // The target's absence IS the marker of that store, so this needs no version flag: such
        // a device promotes on its next tick, its live tail jumps to the top of the next backward
        // page (near the tip), and the walk keeps descending toward the target it now records.
        val handover = floor == null || syncManager.backfillTarget == null
        val before = floor ?: Long.MAX_VALUE
        val outcome = pullSyncLogBefore(before) ?: return false

        // ⚠⚠ BEFORE anything adopts this page. An EMPTY page reports `nextCursor == beforeSeq` by
        // contract ("the cursor stays where you asked"), and at handover `beforeSeq` is
        // `Long.MAX_VALUE`. Jumping the live tail to that would set the forward cursor past every
        // seq the relay will ever mint — the device would go **permanently deaf**, having quietly
        // declared itself caught up with the end of time. `HomeDeviceControlSystemTest` pins this
        // (as a convergence timeout).
        //
        // A page that did not descend also cannot advance the walk, so the same guard retires it.
        if (outcome.cursor >= before) {
            // ⚠⚠ **Completion is AFFIRMED, never inferred.** `backfillCursor = 0` is a one-way
            // latch — [backfillOnce] opens with `if (floor == 0L) return false`, and only an
            // explicit `SyncManager.reopenBackfill()` re-opens it. So the only
            // page allowed to set it is one where the relay actually said there is nothing below:
            // **no rows AND no more below**.
            //
            // Latching on `cursor >= before` alone conflates "the bottom of the log" with "this
            // page did not descend", and the second has causes that are nothing to do with being
            // finished. The device then believes it is fully caught up while history is
            // permanently missing, and — because a short walk is silent — nothing anywhere says so
            // (seen on an iPhone: 98 rows that exist on the Android peer never arrived).
            //
            // ⚠ A page that returns rows without descending, or that reports `hasMore`, simply
            // retries next tick from the same floor. That can repeat — one request per
            // tick — which is the deliberate trade: a loud, bounded retry beats silently losing
            // history, and the WARN below is what makes the condition observable at all.
            val affirmedBottom = outcome.received == 0 && !outcome.hasMore
            if (affirmedBottom) {
                syncManager.backfillCursor = 0L
                println("SyncScheduler: backfill complete — relay reports nothing below $before")
            } else {
                println(
                    "WARN SyncScheduler: backfill page did not descend below $before " +
                        "(received=${outcome.received}, hasMore=${outcome.hasMore}) — retrying " +
                        "next tick. NOT latching complete: this device may still be missing history."
                )
            }
            return false
        }

        if (handover) {
            // ⚠⚠ Capture where the forward pass had reached BEFORE promoting it — that boundary is the walk's stopping point, and it is the only moment it can be
            // observed.
            //
            // ⚠⚠ **An EXISTING target wins.** Handover fires on `floor == null`, and
            // `SyncManager.reopenBackfill()` produces exactly that state deliberately — so a
            // re-opened walk lands here. Re-capturing the target from the forward cursor would be
            // wrong: on any device that has already handed over, that cursor is a live tail near
            // the tip, so the "repair" would reset the stopping point to almost the top of the log,
            // the re-walk would cover nearly nothing and re-latch — leaving the very range it was
            // re-opened to cover unread.
            //
            // The first capture is the real boundary: it is where the forward pass had genuinely
            // read to before it was promoted, and nothing since has changed that fact. Re-deriving
            // it from a cursor that has since jumped forward does not observe the boundary, it
            // destroys it. So only a walk that has never recorded one captures it.
            val target = syncManager.backfillTarget ?: syncManager.lastSyncTimestamp
            syncManager.backfillTarget = target

            // …then promote the forward pass to a LIVE TAIL at the top of this page.
            //
            // Without this a device stays blind to NEW writes: the backward walk descends and never
            // returns, so anything written after it passed the tip is reachable only by the forward
            // crawl (see this function's KDoc for the ~24 h measurement).
            //
            // ⚠ Only ever forward. A device already past this page's floor is caught up; moving its
            // cursor down would re-read history it has, and the `target` check below then completes
            // the walk immediately, which is correct.
            //
            // ⚠⚠ Compared against the LIVE TAIL, not against [target]. A re-opened walk keeps its
            // original (lower) target, so the two diverge: a page landing between the preserved target and the live tail would
            // satisfy `> target` and drag the forward cursor BACKWARDS — re-pulling history the
            // device already has, and undoing the promotion this block exists to perform.
            val liveTail = syncManager.lastSyncTimestamp
            if (outcome.cursor > liveTail) {
                syncManager.lastSyncTimestamp = outcome.cursor
                syncManager.lastTombstoneSyncTimestamp = outcome.cursor
                println(
                    "SyncScheduler: backfill handover — live tail jumps $liveTail → ${outcome.cursor}; " +
                        "history below fills in backwards"
                )
            }
        }

        // ⚠ Against the TARGET, never against `lastSyncTimestamp` — which is by now the live tail and
        // sits above the whole unread range. Comparing to it would end the walk on page one.
        val target = syncManager.backfillTarget ?: 0L
        val met = outcome.cursor <= target
        if (!outcome.hasMore || met) {
            syncManager.backfillCursor = 0L
            println(
                "SyncScheduler: backfill complete (" +
                    (if (met) "met the forward pass at $target" else "reached the start of the log") + ")"
            )
            return false
        }

        // (The descend-or-retire guard ran above, before any adoption — see there for why the
        // ordering is load-bearing rather than stylistic.)
        syncManager.backfillCursor = outcome.cursor
        return true
    }

    /** The backfill RPC. Mirrors [pullSyncLog]; `null` means the merged lane is unavailable. */
    private suspend fun pullSyncLogBefore(beforeSeq: Long): SyncLogOutcome? {
        val tip = buildSignedTip() ?: return null

        val response = try {
            executionContext.executeAsync<SyncLogResponse>(
                operationId = GetSyncLogBefore.ID,
                caller = null,
                params = mapOf(
                    GetSyncLogBefore.Params.BEFORE_SEQ to beforeSeq.toTInteger(
                        executionContext.context
                    ),
                    GetSyncLogBefore.Params.TIP to tip
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TenshuException) {
            if (e.error is RelayRouteUnsupportedError) {
                mergedLaneSupported = false
                return null
            }
            // ⚠ Deliberately NOT reported as a failed tick: backfill is catch-up, and the forward
            // pull in the same tick is already the authority on relay reachability. A transient
            // backfill failure just retries at the next tick from the same floor.
            return null
        } catch (e: Throwable) {
            return null
        }

        val blocks = response.blocks.toList()
        val tombstones = response.tombstones.toList()
        applyPage(blocks.size + tombstones.size) {
            blocks.forEach { syncProcessor.processSyncBlock(it) }
            tombstones.forEach { syncProcessor.processDeleteBlock(it) }
        }

        return SyncLogOutcome(
            cursor = response.nextCursor.get(),
            hasMore = response.hasMore.get(),
            received = blocks.size + tombstones.size,
        )
    }

    /**
     * Pulls one page of the **merged log** and advances the cursor from it
     * (`sync-lane-cursor-split.md` Option C / L3). Returns whether a backlog remains.
     *
     * ## Why one cursor is sound here
     *
     * The relay pages the *union* of both logs and reports the max seq of that merged
     * **prefix**, so nothing below `nextCursor` is left unsent in either kind. One cursor over
     * the two separate lanes has no such guarantee — it is one number reconciling two
     * independent first-500s — which is why the two-lane path splits it (L1).
     *
     * ## ⚠ Both cursors are kept at the merged value, deliberately
     *
     * The two lane cursors still exist and are written in lockstep here. That is not
     * bookkeeping for its own sake: it means a fallback to [pullSeparateLanes] at *any* tick
     * — including mid-backlog, on a relay that is rolled back under us — resumes correctly
     * with no special case, because both lanes are already positioned exactly where the
     * merged lane left off. The alternative (let them go stale) would make the fallback path
     * silently skip everything ingested while merged.
     */
    private suspend fun pullMergedLog(): ForwardPull {
        val since = mergedCursor()
        val outcome = pullSyncLog(since)
        if (outcome == null) {
            // The relay does not implement the route. Latch off for the life of this process
            // and serve this tick from the two-lane path, so a relay older than the client
            // costs one wasted request, not a stalled sync.
            mergedLaneSupported = false
            return pullSeparateLanes()
        }
        val advanced = max(since, outcome.cursor)
        syncManager.lastSyncTimestamp = advanced
        syncManager.lastTombstoneSyncTimestamp = advanced
        return ForwardPull(backlog = outcome.hasMore, answered = outcome.answered)
    }

    /**
     * The cursor the merged lane resumes from.
     *
     * ⚠ **`min`, not `max`.** Only `min` is provably delivered in *both* kinds: if the block
     * lane reached 71 391 while tombstones reached 29 544, everything above 29 544 is
     * delivered for blocks and unknown for tombstones. Taking `max` would silently skip every
     * tombstone in between. The cost of `min` is re-delivering blocks in `(min, max]` once,
     * which is idempotent.
     */
    private fun mergedCursor(): Long =
        minOf(syncManager.lastSyncTimestamp, syncManager.lastTombstoneSyncTimestamp)

    /**
     * The two-lane pull, the fallback for a relay without `/sync/log`.
     * Per-lane cursors (L1): each lane asks from, and advances on, its own evidence — the
     * highest `relay_seq` that lane actually served.
     *
     * *Never pass a seq the relay did not serve* holds here **by construction**, because no
     * lane's cursor is ever moved by the other lane's result; `SyncLaneCursorTest` pins it. (The
     * density argument against one shared cursor is at the call site in [drainAndPull].)
     *
     * ⚠ Ordering: the lanes may drift arbitrarily far apart here, so a tombstone can be
     * applied long after a block that follows it in `relay_seq`. That is safe only because
     * ingest is order-independent on *both* sides — see L1a in
     * `SyncProcessor.processDeleteBlock`.
     */
    private suspend fun pullSeparateLanes(): ForwardPull {
        val blocksSince = syncManager.lastSyncTimestamp
        val tombstonesSince = syncManager.lastTombstoneSyncTimestamp
        val blocks = pullChanges(blocksSince)
        val tombstones = pullTombstones(tombstonesSince)
        syncManager.lastSyncTimestamp = max(blocksSince, blocks.cursor)
        syncManager.lastTombstoneSyncTimestamp = max(tombstonesSince, tombstones.cursor)
        return ForwardPull(
            backlog = blocks.truncated || tombstones.truncated,
            // BOTH lanes: a block page that arrived beside a tombstone page that failed is not a
            // finished pull, and the cursor alone cannot tell the two apart.
            answered = blocks.answered && tombstones.answered,
        )
    }

    /**
     * Returns the relay's arrival-time `nextCursor` for blocks. `sinceTimestamp` here is
     * the same cursor space — NOT HLC physical time. Returns [sinceTimestamp] verbatim on
     * pre-pairing / pre-tip / RPC failure so the cursor never regresses.
     */
    private suspend fun pullChanges(sinceTimestamp: Long): PullOutcome {
        // Build the signed HLC tip the request piggybacks (sync-backend-relay-plan.md
        // §5.1). Skip if we don't yet know our owner — pre-pairing/recovery state.
        val tip = buildSignedTip() ?: return PullOutcome.exhausted(sinceTimestamp)

        // executeAsync returns the typed value directly and throws TenshuException on
        // failure (`docs/proposals/core-error-channel.md`); treat
        // failure as "no progress this tick" so the cursor never regresses.
        val response = try {
            executionContext.executeAsync<ChangesResponse>(
                operationId = GetChanges.ID,
                caller = null,
                params = mapOf(
                    GetChanges.Params.SINCE_TIMESTAMP to sinceTimestamp.toTInteger(
                        executionContext.context
                    ),
                    GetChanges.Params.TIP to tip
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The per-tick relay-reachability probe (drive-connectivity-service.md
            // §6 Q2): a failed GetChanges means the relay was unreachable this tick.
            //
            // ⚠ This catch is why an unservable pull reads as "offline": a relay answering every
            // from-zero pull with a 500 (say, out of memory materialising the whole history —
            // SYNC_CHANGES_PAGE_LIMIT) lands here, flips the device to OFFLINE and returns the
            // cursor unchanged, so the next tick asks the identical impossible question. Without
            // a log the only evidence on the client is the word "offline". Record the cause, collapsed to state transitions like the
            // drain log beside it (a real outage lasts many ticks; one line per tick is spam).
            pullFailureLog.onTick(failed = true, cause = e)
            noteRelayFailure(e)
            return PullOutcome.exhausted(sinceTimestamp)
        }
        // GetChanges round-tripped → relay reachable this tick, and it accepted our identity.
        pullFailureLog.onTick(failed = false, cause = null)
        noteRelayAccepted()

        val blocks = response.blocks.toList()
        applyPage(blocks.size) {
            blocks.forEach { block ->
                syncProcessor.processSyncBlock(block)
            }
        }

        // Hand peer tips to the divergence tracker (no-op by default; production wiring
        // calls into `HlcDivergenceTracker`). Each tip is opaque to the scheduler — the
        // tracker handles signature verification, regression detection, and warning
        // emission.
        response.peerTips.toList().forEach { onPeerTip(it) }

        return PullOutcome(cursor = response.nextCursor.get(), rowCount = blocks.size, answered = true)
    }

    /**
     * The signed tip the personal-realm pull piggybacks.
     *
     * ⚠⚠ Merges through [hlcWatermarkStore] before signing, and that is the entire point of TC-2:
     * `hlcManager.current()` is a *process's* clock, while the relay keeps one tip slot per
     * `(ownerId, deviceId)`. Two runtimes under one deviceId — iOS route-1's per-app runtimes, two
     * web tabs of one origin — otherwise take turns overwriting that slot with their own,
     * unrelated positions, and a peer reads the backward step as the T-T-04 forked-view tell.
     *
     * The merged value is fed back into the local clock, so a lagging runtime *adopts* the
     * device's real position instead of re-deriving its own stale one on every poll. Suspending
     * because the store is durable; all three callers are already `suspend`.
     *
     * ⚠ A store failure must not stop us publishing a tip. Falling back to the in-process clock
     * is degraded but correct-in-isolation;
     * publishing nothing would strand the relay's tombstone-GC quorum on this device instead.
     */
    private suspend fun buildSignedTip(): SignedHlcTip? {
        val ownerId = syncManager.currentOwnerId ?: return null
        // One snapshot: the id and the key that signs it can never come from two identities.
        val identity = syncManager.currentDeviceIdentity()
        val deviceId = identity.deviceId
        val hlc = mergedHlc()
        val signingKey = identity.signingKeyPair.privateKey
        val message = hlcTipCanonicalMessage(ownerId, deviceId, hlc)
        val signature = cryptoManager.signEd25519(signingKey, message)
        return SignedHlcTip.create(
            context = executionContext.context,
            ownerId = ownerId,
            deviceId = deviceId,
            hlc = hlc,
            signature = signature
        )
    }

    /**
     * This device's position: the local clock raised by, and raising, the durable watermark.
     *
     * Both directions matter. Raising the *store* publishes this runtime's progress to its
     * siblings; raising the *clock* from the merged result is what stops this runtime publishing
     * a value below one a sibling already published — the backward step peers read as divergence.
     *
     * ⚠ This also subsumes startup seeding, which is why there is no separate seed step in
     * `initSyncService`. A freshly started process has an all-zero clock, so its first merge here
     * returns the durable watermark and adopts it — so a restart never publishes a `(0,0)` tip,
     * which would silently zero this device's tombstone-GC quorum on the relay. Doing it on the tip path rather than at init also avoids a fire-and-forget
     * `launch` in proxy initialisation (see `docs/tech-debt.md`, "Fire-and-forget launches in
     * proxy init") racing the first poll.
     */
    private suspend fun mergedHlc(): HybridLogicalClock {
        val local = syncManager.hlcManager.current()
        val store = hlcWatermarkStore ?: return local
        // ⚠⚠ `ios-shared-store-sqlite.md` B.22.6: the durable watermark is trusted only once this
        // store has pulled its realm's history. Before that it may be a stamp the store never
        // observed — a store wiped or reset to re-pull, or, on iOS, a `sync-hlc/` an older-build
        // process made again beside the new store — and publishing it would forge the relay's
        // tombstone-GC ack (T-T-08). Until then the tip is this process's own clock, and the
        // watermark is neither read nor raised. A deflated tip only stalls GC (the safe side).
        if (!storeHistoryPulled()) {
            if (!loggedWatermarkHeld) {
                loggedWatermarkHeld = true
                println("SyncScheduler -> the durable HLC watermark is held back until this store has pulled its history: the tip is this process's clock (B.22.6)")
            }
            return local
        }
        val merged = try {
            store.raiseTo(HlcWatermark(local.physicalTime.get(), local.logicalCounter.get()))
        } catch (e: Exception) {
            // See buildSignedTip: degrade to the process clock rather than publish nothing.
            println("SyncScheduler -> hlc watermark unavailable, using the in-process clock: $e")
            return local
        }
        if (merged <= HlcWatermark(local.physicalTime.get(), local.logicalCounter.get())) return local
        return syncManager.hlcManager.seed(merged.physicalTime, merged.logicalCounter)
    }

    /**
     * The merged-lane RPC. Applies the page and returns its outcome, or **`null` when the
     * relay does not implement `/sync/log`** — a state the caller latches, not an error.
     *
     * ⚠ The unsupported case must NOT flip [tickOnline] to false. A relay that is older than
     * this client is *working*; reporting the device offline for it would be a lie, and one
     * that hides itself — the fallback path would keep syncing perfectly while the UI said
     * "Local only". That is why the signal is the typed [RelayRouteUnsupportedError] rather
     * than a failure the generic catch below would swallow.
     */
    private suspend fun pullSyncLog(sinceTimestamp: Long): SyncLogOutcome? {
        val tip = buildSignedTip()
            ?: return SyncLogOutcome(sinceTimestamp, hasMore = false, answered = false)

        val response = try {
            executionContext.executeAsync<SyncLogResponse>(
                operationId = GetSyncLog.ID,
                caller = null,
                params = mapOf(
                    GetSyncLog.Params.SINCE_TIMESTAMP to sinceTimestamp.toTInteger(
                        executionContext.context
                    ),
                    GetSyncLog.Params.TIP to tip
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TenshuException) {
            if (e.error is RelayRouteUnsupportedError) {
                println("Sync -> relay has no /sync/log; falling back to the block+tombstone lanes")
                return null
            }
            pullFailureLog.onTick(failed = true, cause = e)
            noteRelayFailure(e)
            return SyncLogOutcome(sinceTimestamp, hasMore = false, answered = false)
        } catch (e: Throwable) {
            pullFailureLog.onTick(failed = true, cause = e)
            noteRelayFailure(e)
            return SyncLogOutcome(sinceTimestamp, hasMore = false, answered = false)
        }
        pullFailureLog.onTick(failed = false, cause = null)
        noteRelayAccepted()

        // Blocks then tombstones, matching the two-lane path exactly. The page is a prefix of
        // one ordered log, but applying it kind-by-kind is safe because ingest is HLC-guarded
        // in both directions (`SyncLogResponse` KDoc) — and it is what this code already did,
        // so the merged lane changes the cursor, not the application semantics.
        val blocks = response.blocks.toList()
        val tombstones = response.tombstones.toList()
        applyPage(blocks.size + tombstones.size) {
            blocks.forEach { syncProcessor.processSyncBlock(it) }
            tombstones.forEach { syncProcessor.processDeleteBlock(it) }
        }
        response.peerTips.toList().forEach { onPeerTip(it) }

        return SyncLogOutcome(
            cursor = response.nextCursor.get(),
            hasMore = response.hasMore.get()
        )
    }

    /** Sibling of [pullChanges]; returns the relay's arrival-time `nextCursor` for tombstones. */
    private suspend fun pullTombstones(sinceTimestamp: Long): PullOutcome {
        val response = try {
            executionContext.executeAsync<TombstonesResponse>(
                operationId = GetTombstones.ID,
                caller = null,
                params = mapOf(
                    GetTombstones.Params.SINCE_TIMESTAMP to sinceTimestamp.toTInteger(
                        executionContext.context
                    )
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return PullOutcome.exhausted(sinceTimestamp)
        }

        val tombstones = response.tombstones.toList()
        applyPage(tombstones.size) { tombstones.forEach { tombstone ->
            syncProcessor.processDeleteBlock(tombstone)
            // ⚠ The relay re-serves every tombstone above the cursor, and that is CORRECT —
            // not a missing feature. ADR 0006 decision 6: "Tombstones themselves don't get
            // GC'd, only their target bodies. The relay keeps DeleteBlocks indefinitely —
            // they're the chain of provenance for any device that comes online after
            // retention." Collecting them would leave a long-offline device never learning of
            // the delete and **resurrecting** the entity, which is the whole bug tombstones
            // exist to fix (decision 3). Re-serving is free here: processDeleteBlock and
            // TombstoneRegistry.record are both idempotent.
            //
            // ⚠ Tombstones ARE acknowledged: acks ride the HLC-tip piggyback (relay-plan §3.1),
            // `MailboxStore.acknowledgementSet` derives quorum from them, and `gcSweep` — driven
            // from the relay's `Application.kt` — gates body GC on quorum AND the retention
            // window. Collecting the tombstones themselves is forbidden by decision 6.
        } }

        return PullOutcome(cursor = response.nextCursor.get(), rowCount = tombstones.size, answered = true)
    }

    /**
     * Stops the polling job.
     */
    fun stop() {
        // ⚠ FIRST, before the cancel: a tick still running past this line settles in its
        // `finally`, and that verdict must be refused so this clear is the last word.
        catchUp?.stop()
        syncJob?.cancel()
        syncJob = null
        // Defensive: a stopped scheduler must not leave the process pinned alive. The loop
        // may have exited with a backlog still queued (liveness last set to 1); release it.
        backgroundLiveness?.setActive(false)
    }

    /**
     * [stop], but waits for an in-flight tick to actually finish.
     *
     * ⚠⚠ **`cancel()` is a request, not an event.** It returns immediately, and cancellation is
     * cooperative — a loop already inside [tickOnce] runs on to its next suspension point, which for
     * a tick means it can still drain, pull and hit the relay *after* `stop()` returned. So "the
     * scheduler is stopped" and "the scheduler is quiet" are different claims, and only this one
     * makes the second.
     *
     * A test that calls [stop] so a background tick cannot overlap its hand-stepped one is not
     * protected: `SyncHealthReportingTest`, stopping without awaiting, failed 3 in 12 runs on an
     * idle machine with two `tickOnce` stacks on two threads.
     *
     * ⚠ Production ticks cannot overlap each other — every entry point holds [tickMutex]
     * ([drainNow], and the loop at its `while (isActive)`) — and [tickOnce] itself deliberately does
     * NOT take it, which is why calling it directly bypasses that protection. But a caller that
     * stops the scheduler and then mutates the world it was ticking over (a wipe, a reset, a realm
     * teardown) is racing an in-flight tick unless it waits here.
     */
    suspend fun stopAndAwait() {
        val job = syncJob
        stop()
        job?.cancelAndJoin()
    }

    private companion object {
        /** Pushed-tombstone ids dropped per array rewrite — see [drainPendingTombstones]. */
        const val DRAIN_REMOVE_BATCH = 100

        /**
         * Minimum gap between mid-pass progress publishes. Fast enough to read as live, slow enough
         * that a large pass costs tens of store writes rather than hundreds. Pass edges bypass it.
         */
        const val PROGRESS_PUBLISH_INTERVAL_MS = 500L
    }
}

/**
 * Collapses a per-block, per-tick drain-failure log into one line per **state
 * transition**. [onTick] is called once per drain cycle with the number of
 * blocks that failed to push and the first failure's cause:
 *
 * - clear → stuck: log once, with the count and the cause (the *why*).
 * - stuck → clear: log once that the queue drained.
 * - stuck → stuck: stay silent (the outage is already on record).
 *
 * This is deliberately a bare `println` sink — the SyncScheduler spam is the
 * motivating consumer for the logging facade proposed in
 * `docs/proposals/core-logging-facade.md`, which would move these call sites
 * behind `Log.warn(...)` with level-gating + the diagnostic stream.
 */
private class DrainFailureLog(private val queueLabel: String) {
    private var failing = false

    /**
     * @param failedCount how many pushes were *attempted and refused* this pass.
     * @param queuedCount how many blocks are actually waiting. ⚠ A queue-wide rejection stops the
     *   pass at the first refusal, so [failedCount] is 1 for an arbitrarily large backlog; reporting
     *   it as "block(s) queued" would tell the reader a stalled 386-block queue held a single
     *   block. The two numbers are different questions; report both.
     */
    fun onTick(failedCount: Int, cause: Throwable?, queuedCount: Int = failedCount) {
        if (failedCount > 0) {
            if (!failing) {
                failing = true
                println(
                    "SyncScheduler -> $queueLabel stalled: $queuedCount block(s) queued " +
                        "($failedCount refused this pass), will retry. " +
                        "Cause: ${cause?.drainCauseLabel() ?: "unknown"}"
                )
            }
        } else if (failing) {
            failing = false
            println("SyncScheduler -> $queueLabel recovered: queue drained.")
        }
    }
}

/**
 * Names each entry into (and exit from) the metered-deferred state that drives Drive's
 * *"Waiting for Wi-Fi. N items"* banner. Transition-only, for the same reason
 * [DrainFailureLog] is: the verdict is recomputed every tick (1.5s while a stream is hot).
 *
 * Worth its own line because the banner has three independent inputs the user cannot see — the
 * network classification, the `WifiOnly` preference, and how much is waiting — and it conflates
 * them into one sentence.
 */
private class DeferredStateLog {
    private var deferred = false

    fun onTick(deferred: Boolean, gateOpen: Boolean, pending: Int) {
        if (deferred == this.deferred) return
        this.deferred = deferred
        if (deferred) {
            println(
                "SyncScheduler -> metered-deferred: content gate closed (WifiOnly on a metered " +
                    "network) with $pending file(s) waiting to upload. Metadata push + pull keep " +
                    "running; only file content is held."
            )
        } else {
            println(
                "SyncScheduler -> metered-deferred cleared (gateOpen=$gateOpen, waiting=$pending)."
            )
        }
    }
}

/**
 * The inbound sibling of [DrainFailureLog]: collapses a per-tick pull failure into one line
 * per **state transition** (clear → failing, failing → clear), so a multi-hour outage costs
 * two lines rather than one per poll.
 *
 * Reads its detail through [drainCauseLabel], which is what makes the line worth printing: the
 * relay's own text ("Server error: 500 …") rather than a bare typed-error id — the difference
 * between "offline" and knowing *why*.
 *
 * Same bare-`println` sink as [DrainFailureLog], for the same reason.
 */
private class PullFailureLog {
    private var failing = false
    private var failingUnauthorized = false

    fun onTick(failed: Boolean, cause: Throwable?) {
        if (failed) {
            // ⚠ A `401` is NOT an outage, and saying so is actively misleading: [noteRelayFailure]
            // keeps `tickOnline = true` for a relay that answered, and arms the registration backoff
            // instead. A device the relay has stopped recognising, logged as "offline", reads as a
            // network problem and hides an identity one.
            val unauthorized = cause?.isRelayUnauthorized() == true
            // ⚠ Keyed on (failing, class) rather than `failing` alone, so a device that slides from
            // a 401 into a real outage — or back — still says so once. Collapsing on `failing` alone
            // would print the first cause and then stay silent through a different one.
            if (!failing || unauthorized != failingUnauthorized) {
                failing = true
                failingUnauthorized = unauthorized
                val consequence = if (unauthorized) {
                    // ⚠ Says what it OBSERVED, not what it hopes follows. Re-registration is
                    // conditional on holding A1 and on a wired [realmRegistrar]; a joiner attempts
                    // nothing, and an unwired build returns at `recoverRegistration`'s first line.
                    // Promising the attempt unconditionally would be an overstatement too.
                    "the relay answered but does not recognise this device — backing off, NOT an " +
                        "outage. Repair is attempted only where this device holds its master identity"
                } else {
                    "reporting this device offline"
                }
                println(
                    "SyncScheduler -> inbound pull failing; $consequence. " +
                        "Cause: ${cause?.drainCauseLabel() ?: "unknown"}"
                )
            }
        } else if (failing) {
            failing = false
            failingUnauthorized = false
            println("SyncScheduler -> inbound pull recovered; relay reachable again.")
        }
    }
}

/**
 * Concise, non-stacktrace descriptor for a swallowed drain failure.
 *
 * ⚠ A [TenshuException]'s `message` is the typed error's **id**, not its payload — so a relay
 * rejection logged by `message` reads as the bare `TenshuException: tenshu/result/error/generic`,
 * throwing away the very thing `RestSyncRelay` attached for this purpose (`"Server error: 401
 * Unauthorized"`, the network message, the entitlement marker), with no way to tell a 402 from an
 * unreachable relay. Unwrap the typed error and print its detail.
 */
internal fun Throwable.drainCauseLabel(): String {
    val type = this::class.simpleName ?: "Throwable"
    val detail = (this as? TenshuException)?.error?.let { error ->
        // `messageOrNull` reads the message by convention across every TError subtype, so this
        // stays right for a typed rejection that is not a GenericError.
        val id = error.schema.id.get()
        error.messageOrNull()?.let { "$id — $it" } ?: id
    } ?: message
    return if (detail.isNullOrBlank()) type else "$type: $detail"
}

/**
 * One merged-lane page's outcome (`sync-lane-cursor-split.md` L3).
 *
 * Deliberately NOT a [PullOutcome]: that type carries `rowCount` so `truncated` can be
 * *inferred* from it, and the merged lane does not need to infer — the relay states
 * [hasMore] outright. Reusing [PullOutcome] would mean carrying a row count whose only
 * purpose is an inference this lane has replaced, and would invite someone to ask
 * `isFullPage` of a page whose size means nothing here (its cap is shared across two kinds).
 */
internal data class SyncLogOutcome(
    val cursor: Long,
    val hasMore: Boolean,
    /**
     * How many rows (blocks + tombstones) the page actually delivered.
     *
     * ⚠ Carried purely so [SyncScheduler.backfillOnce] can tell "the relay affirms there is
     * nothing below this seq" from "this page did not descend, for some other reason". Those
     * two look identical through [cursor] alone, and treating the second as the first latches a
     * device's backward walk complete while history is still missing.
     */
    val received: Int = 0,
    /**
     * Whether the relay **answered** this page. An answered empty page and a failed request look
     * the same through [cursor] and [hasMore]; the first-pull marker needs to tell them apart.
     */
    val answered: Boolean = true,
)

/**
 * What a tick's forward pull of the personal realm came to, on either path.
 *
 * [caughtUp] is the first-pull marker's condition: every forward lane answered, and none has more.
 */
internal data class ForwardPull(val backlog: Boolean, val answered: Boolean) {
    val caughtUp: Boolean get() = answered && !backlog
}

/**
 * One inbound lane's result for a tick: how far the relay served, and whether it stopped
 * because it ran out of rows or because it hit its page cap.
 *
 * [truncated] is derived rather than transmitted — the relay caps every response at
 * [SYNC_CHANGES_PAGE_LIMIT], a constant both sides compile against, so a full page *is* the
 * "there is more" signal. Carrying it as a wire field instead would mean adding a member to a
 * positional composite: a format bump and a lockstep fleet move (House Rule #9) for one
 * boolean.
 */
internal data class PullOutcome(
    val cursor: Long,
    val rowCount: Int,
    /** Whether the relay answered; see [SyncLogOutcome.answered]. `false` for a failed request. */
    val answered: Boolean = true,
) {

    /** The relay stopped at its page cap, so rows beyond [cursor] exist but were not sent. */
    val truncated: Boolean get() = isFullPage(rowCount)

    companion object {

        /**
         * **The truncation rule, stated once.** A page of exactly [SYNC_CHANGES_PAGE_LIMIT] rows
         * means the relay stopped at its cap, so more rows exist above the cursor.
         *
         * Every lane asks through here — the personal pull via [truncated], the per-realm shared
         * pull directly — because the shared path has no `PullOutcome` (one lane needs no ceiling
         * arithmetic). Two copies of one rule drift apart; a rule with one home cannot.
         *
         * ⚠ Deliberately still *derived*, not transmitted. An explicit `hasMore` on the response
         * (L2 in `sync-lane-cursor-split.md`) would cost a `RELAY_PROTOCOL_VERSION` bump and a
         * coordinated redeploy — permanent protocol surface — to fix only the exactly-full-final-
         * page false positive, which costs one wasted tick roughly once in
         * [SYNC_CHANGES_PAGE_LIMIT] drains. The merged lane (L3) states `hasMore` outright; only
         * this response derives it.
         */
        fun isFullPage(rowCount: Int): Boolean = rowCount >= SYNC_CHANGES_PAGE_LIMIT
        /**
         * A lane that served everything it had — including the "served nothing" cases: a
         * failed RPC, or no owner yet. Never truncated, so it imposes no ceiling.
         */
        fun exhausted(cursor: Long) = PullOutcome(cursor = cursor, rowCount = 0, answered = false)
    }
}
