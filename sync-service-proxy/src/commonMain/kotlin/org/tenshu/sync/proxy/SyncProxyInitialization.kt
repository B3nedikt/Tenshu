package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.time.clock
import org.tenshu.sync.common.DescribeOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.common.util.getPlatformDeviceName
import org.tenshu.core.TString
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.File
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.HlcWatermarkStore
import org.tenshu.sync.common.DataIdMigrator
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SyncBlockEmitter
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitter

/**
 * What [initSyncService] hands back. Most callers hold only [scheduler]; production wiring also
 * takes [tombstoneEmitter] so cross-module operations can publish tombstones.
 */
class SyncProxyComponents(
    val scheduler: SyncScheduler,
    val syncObserver: SyncObserver,
    val tombstoneRegistry: TombstoneRegistry,
    /** §9.14: shared by every session's observer and the one scheduler, like [tombstoneRegistry]. */
    val tombstoneCascades: TombstoneCascades,
    /** The content-emit ownership table, shared by every session's observer — see [EmitKeyLocks]. */
    val emitKeyLocks: EmitKeyLocks,
    val divergenceTracker: HlcDivergenceTracker,
    /** The in-process `ScheduleService` registry drain (`core-background-processing.md` §3.2(b)). */
    val scheduleDrainLoop: ScheduleDrainLoop,
    /** The device's realm set (SR-M.1b): shared realms register here; today only the personal one. */
    val realmRegistry: RealmRegistry,
    /**
     * Sync-health figures (`drive-connectivity-service.md`), written by the scheduler each tick.
     * Exposed for tests; production reads them through `initSyncHealth`'s derived relation.
     */
    val syncHealthHolder: SyncHealthHolder,
    /**
     * This runtime's pull-activity signal (`ios-sync-activity-indicator.md` §3.1). Exposed for
     * tests; production reads its row through `SyncService.catchUp(hostAppId)`.
     */
    val catchUp: SyncCatchUp,
) {
    val tombstoneEmitter: TombstoneEmitter get() = syncObserver
    val syncBlockEmitter: SyncBlockEmitter get() = syncObserver

    /**
     * Two-plane file-content emitter (`docs/adr/0017-file-content-two-plane-channel.md` P1) — the same [SyncObserver].
     * Production pushes it into `SyncBlockEmitterHolder.fileContentEmitter` so uploads take the
     * bounded-memory chunk path instead of a whole-file `emitPropertyBlock`.
     */
    val fileContentEmitter: FileContentSyncEmitter get() = syncObserver

    /**
     * The shared runtime seen through another session's observer (`drive-upload-progress.md` P3).
     * An observer only sees saves made through its own `ExecutionContext`, so every session wires
     * its own — see [SyncRuntimeHolder].
     */
    internal fun copyForSession(syncObserver: SyncObserver): SyncProxyComponents =
        SyncProxyComponents(
            scheduler = scheduler,
            syncObserver = syncObserver,
            tombstoneRegistry = tombstoneRegistry,
            tombstoneCascades = tombstoneCascades,
            emitKeyLocks = emitKeyLocks,
            divergenceTracker = divergenceTracker,
            scheduleDrainLoop = scheduleDrainLoop,
            realmRegistry = realmRegistry,
            syncHealthHolder = syncHealthHolder,
            catchUp = catchUp,
        )
}

/**
 * Self-heal this device's own [Device] row when the local store has lost it (tech-debt
 * *"`Device` rows have no regeneration path"*).
 *
 * Membership has two halves written together once, at pairing. The authoritative half (A4 keypair,
 * derived realm keys, relay registration) survives a store wipe; the descriptive half — this synced
 * row, which drives the roster, revocation targeting and `admittedAtEpoch` — does not, and nothing
 * re-creates it. HR #9 moves every replica in lockstep, so a core-version wipe empties every copy at
 * once and no peer can re-sync it. ⚠ `RevokeDeviceImplementation` bails without the local row, so
 * an empty roster means **nothing can be revoked**.
 *
 * Reconstructed from local material: `deviceId`, `name`, `publicKey`, `isAuthorized`. Not
 * recoverable, and not guessed: `admittedByDeviceId` is written empty (right for a genesis device,
 * lossy for an A33-delegated one, whose revoke subtree shrinks until it re-pairs), and
 * `admittedAtEpoch` is the local view of [SyncManager.currentRealmEpoch], itself reset by the wipe.
 *
 * Guards — all three, so this can never manufacture membership: the device is in a realm
 * ([SyncManager.currentRealmContext] non-null; ⚠ not merely "unlocked"), the row is absent (a
 * peer's row always wins), and no tombstone names this device (a revoked device must not resurrect
 * itself). Idempotent; returns `true` only when a row was written.
 */
fun ExecutionContext.reconcileOwnDeviceRow(
    syncManager: SyncManager,
    tombstoneRegistry: TombstoneRegistry,
    /** EI-1: the same resolver the registry was keyed with — see the probe below. */
    wireIds: WireIdResolver = WireIdResolver(syncManager),
): Boolean {
    // ⚠ Realm membership, not `getUnlockStatus().isLocked`: a brand-new device is "unlocked" too,
    // and gating on that let an unpaired device self-assert `isAuthorized = true`; its phantom row
    // then won LWW over the admitter's real one and erased `admittedByDeviceId`
    // (`DelegatedAdmissionSystemTest`). `currentRealmContext()` needs the owner id AND derived keys.
    val realm = syncManager.currentRealmContext() ?: return false
    val deviceId = realm.deviceId
    if (deviceId.isEmpty()) return false

    val ownDeviceId = DataId.StringDataId.create(
        context = context,
        type = Device.ID,
        keyValue = deviceId
    )
    if (database.getData(ownDeviceId) != null) return false
    // EI-1: the registry is keyed by the WIRE name, so this lookup must be too — a local-hash
    // probe here would always miss and the device would never see its own revocation.
    if (tombstoneRegistry.tombstoneHlcFor(wireIds.of(ownDeviceId)) != null) return false

    // A wiring that reports unlocked but cannot produce the key must not brick startup.
    val signingKey = runCatching { syncManager.getDeviceSigningKeyPair() }.getOrNull() ?: return false

    saveData(
        Device.create(
            context = context,
            deviceId = deviceId,
            // The user's label if this process was given one; the platform default otherwise.
            name = syncManager.currentDeviceName?.takeIf { it.isNotBlank() } ?: getPlatformDeviceName(),
            publicKey = signingKey.publicKey,
            isAuthorized = true,
            admittedAtEpoch = syncManager.currentRealmEpoch,
            admittedByDeviceId = "",
            // F5b: carry our own A33 key, if we hold one — see PairingKeyInstaller.
            admissionPublicKey = syncManager.getAdmissionKeyPair()?.publicKey ?: ByteArray(0),
        )
    )
    return true
}

fun ExecutionContext.initSyncService(
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    mergerRegistry: SemanticMergerRegistry = SemanticMergerRegistry(),
    /**
     * Scope for the per-session work. Defaults to the receiver's [ExecutionContext.sessionScope], so
     * the `sessionScope.cancel()` that `ClientRegistry` issues on unregister / shutdown cancels it
     * too — a detached scope left loops ticking after teardown. JS passes its `serviceScope`.
     */
    coroutineScope: CoroutineScope? = null,
    pollingIntervalMs: Long = 30000,
    syncProcessorHolder: SyncProcessorHolder? = null,
    /**
     * Content plane + chunk source for the two-plane file-content emit (`docs/adr/0017-file-content-two-plane-channel.md`
     * P1). Both null in test/legacy wirings ⇒ file-content emit no-ops and only the single-block
     * path runs.
     */
    contentBlobStore: ContentBlobStore? = null,
    fileDatabase: FileDatabase? = null,
    /**
     * The content-declaration backfill's sources and the tombstone-seq seam. Null builds no backfill.
     *
     * ⚠⚠ Nothing on a device frees relay content, so this builds no sweep
     * (`content-plane-cr2-quorumless.md` §10.9). See [ReclamationSources].
     */
    reclamationSources: ReclamationSources? = null,
    /** Where the backfill's per-pass line goes. `null` is silent. */
    onReclamationReport: ((String) -> Unit)? = null,
    /**
     * When a resolve finds content the relay has freed, put it back from this device's own
     * plaintext if it has any (`drive-upload-stop-and-reclamation.md` §4.18). `false` — the
     * default — leaves a dangling reference as it is: the file does not surface, and the resolve
     * retries for ever.
     *
     * ⚠ Additive by construction (it uploads or does nothing).
     */
    repairDanglingContent: Boolean = false,
    /**
     * Collects plaintext an *ingested* delete stranded on this device (`docs/tech-debt.md`, "A delete
     * frees the deleting device's disk and no one else's"). `null` — the default — runs nothing.
     *
     * ⚠ Composed by the caller because the sweep lives in `:file-service-proxy`, for the same reason
     * [reclamationSources] does: this module must not reach into that one.
     *
     * ⚠⚠ The one consumer of the scheduler's `onTickSettled` hook
     * (`content-plane-cr2-quorumless.md` §10.9) — the hook exists for it.
     */
    orphanedBytesSweep: (suspend () -> Unit)? = null,
    /**
     * Backing store for the G3 orphan sweep (`core-local-store-orphan-gc.md`). Null, or a store
     * that cannot enumerate (iOS and JS today) ⇒ no sweep, which is the safe direction.
     */
    objectStore: ObjectStore? = null,
    /**
     * The personal relay, used only for the C1 targeted entitlement fetch (`sync-device-catch-up.md`
     * §5.3). Null ⇒ recovery takes the slow path; ordinary traffic goes through the operation layer.
     */
    personalRelay: SyncRelay? = null,
    /**
     * Path A policy gate (`bgproc-scheduling-roles.md`), forwarded to [SyncScheduler]. Production
     * passes a constraint-backed gate.
     */
    syncGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Local-first storage gate (ADR 0019): gates the **personal** realm's drain/pull only.
     * Production passes a `StorageCapacityHolder`-backed gate so nothing reaches the relay before a
     * storage subscription is bound.
     */
    storageGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Deferred personal-realm genesis (ADR 0019, Phase 2): the scheduler runs `POST /sync/realm`
     * the first time [storageGate] opens. Null ⇒ no deferred genesis; test/legacy wirings register
     * eagerly in `CreateRealm`.
     */
    realmRegistrar: RealmRegistrar? = null,
    /**
     * SR-3b.2: a per-shared-realm [SyncRelay] (JWT scoped to that realm's `ownerId`, same A4 key).
     * Null ⇒ shared realms queue locally but never push or pull.
     */
    sharedRealmRelayFactory: ((String) -> SyncRelay?)? = null,
    /**
     * A per-shared-realm [ContentBlobStore], so a shared realm's file chunks go
     * to its own content plane. Null ⇒ shared-realm file content cannot emit.
     */
    sharedRealmContentBlobStoreFactory: ((String) -> ContentBlobStore?)? = null,
    /**
     * GR-3 / D9-b: a per-realm-device-key [SyncRelay] for a granted realm, used by
     * [MemberSelfBindObserver] so a member's second device can self-bind. Null ⇒ that observer is
     * not registered (single-device accept only).
     */
    grantedRelayFactory: ((RealmContext) -> SyncRelay?)? = null,
    /**
     * Sync-health figures holder (`drive-connectivity-service.md`). Null ⇒ created internally;
     * production passes a Koin `single` so out-of-band readers (the desktop tray's `getSyncState`)
     * see the same figures the scheduler writes.
     */
    syncHealthHolder: SyncHealthHolder? = null,
    /**
     * Fired when the relay starts / stops refusing this device's file content for space (`507`).
     * Control wires this to `StorageCapacityHolder.relayRejected` so a manual upload is refused
     * up-front with a typed error rather than accepted and then silently held. Default = no-op,
     * which preserves prior behaviour for every test/legacy wiring.
     */
    onStorageRejectionChanged: (Boolean) -> Unit = {},
    /**
     * Reports the content journal's outstanding bytes each tick. Control wires this to
     * `StorageCapacityHolder.queuedContentBytes` so an upload is measured against the queue in
     * front of it (ST-7). Default = no-op.
     */
    onQueuedContentBytes: (Long) -> Unit = {},
    /**
     * App id of the binary hosting this runtime (`core-schema-skew-surface.md` SK-2): Control on
     * Android and desktop, the app itself under iOS route 1. Published as the single
     * [org.tenshu.sync.RuntimeIdentity] row so a client knows which app to tell the user to update.
     * ⚠ Null publishes no row, and the client then names no app rather than guessing.
     */
    hostAppId: String? = null,
    /** The host binary's version string. Display only — never compared. */
    hostAppVersion: String = org.tenshu.sync.RuntimeIdentity.UNKNOWN_VERSION,
    /**
     * Whether this runtime writes the catch-up heartbeat (`ios-sync-activity-indicator.md` §12):
     * only where several runtimes share one store and a sibling reads it — iOS. ⚠ `false` on
     * Android, desktop and web, where one runtime owns the store and nothing reads the relation;
     * its own `SyncCatchUpStatus` row is written either way.
     */
    catchUpHeartbeat: Boolean = false,
    /**
     * Process-liveness source (`core-background-processing.md` §4.6): the scheduler raises it while
     * a pushable backlog exists, so Android's foreground service survives a long drain. Null on
     * desktop and in tests, whose daemon is always resident.
     */
    backgroundLiveness: BackgroundLiveness? = null,
    /** Wall clock (ms) for the GR-3 self-bind `boundAt` and the `DivergenceWarning` stamp. */
    nowMs: () -> Long = { 0L },
    /**
     * The device's durable HLC watermark (`sync-hlc-tip-device-identity.md` TC-2). Null keeps the
     * pre-TC-2 behaviour: the published tip is this process's clock and a restart republishes `(0,0)`.
     */
    hlcWatermarkStore: HlcWatermarkStore? = null,
    /**
     * The per-proxy live-task registry (`core-task-composite.md` TS-3). When set, its D9 lease
     * sweep starts here: an expired task fails and its sync lane closes, so staged deltas are
     * checkpointed instead of stranded.
     */
    taskRegistry: TaskRegistry? = null,
    /**
     * Handle to the ONE sync runtime per process (`drive-upload-progress.md` P3). When already
     * populated, this call builds only this session's observers and reuses the runtime — see
     * [SyncRuntimeHolder]. Null (tests, legacy wirings) builds a complete runtime on every call.
     */
    runtimeHolder: SyncRuntimeHolder? = null,
    /**
     * Scope for the process-scoped loops (scheduler, schedule drain, status derivations), as
     * opposed to [coroutineScope]'s per-session work: the runtime must outlive the session that
     * built it, or one client disconnecting stops sync for every app. Defaults to the session scope.
     */
    processScope: CoroutineScope? = null,
    /**
     * Start the [SyncScheduler] and [ScheduleDrainLoop] loops; production never passes `false`.
     * A test that steps the scheduler by hand wants the components built and the loops NOT
     * running, and "start, then `stopAndAwait`" cannot express that: the loop ticks immediately on
     * `start()`, so whether that first tick runs before the cancel lands is a scheduling race
     * (`SyncHealthReportingTest` lost it).
     */
    startBackgroundLoops: Boolean = true,
    /**
     * ⭐ The OS alert for a change lost to freed content (`content-plane-cr2-b4-spec.md` §2.4), where the platform binds a
     * `NotificationPresenter`. The synced notice is sent regardless; `null` ⇒ no OS alert.
     */
    onChangeLost: ((fileName: String, path: String) -> Unit)? = null,
    /**
     * The OS scheduler whose items the in-process [ScheduleDrainLoop] leaves alone
     * ([PlatformScheduler.ownsDispatch], `control-background-work-off-fgs.md` §5.6). Android passes
     * its `WorkManagerScheduler`; everyone else keeps the loop as the only driver.
     */
    platformScheduler: PlatformScheduler,
): SyncProxyComponents {
    // ⚠⚠ The whole check → build → publish runs under the holder's lock. See [electing].
    return runtimeHolder.electing {
        // A runtime already exists in this process: build only the per-session observers (see
        // [SyncRuntimeHolder]) and hand the shared runtime back. Everything below this block is process
        // work that must not run twice.
        // ⚠ Re-elect if the held runtime is dead. It lives on the scope of the session that built it,
        // which can go away while other sessions carry on; handing out a scheduler whose loops are
        // cancelled would stop sync process-wide. Checked here, so the next client rebuilds it
        // (`drive-upload-progress.md` §6 tracks giving the runtime a real process-lifetime owner).
        runtimeHolder?.runtime?.takeIf { it.isAlive() }?.let { existing ->
            val sessionObserver = registerSessionObservers(
                syncManager = syncManager,
                // ⚠ A fresh resolver on the re-attach path, but a realm-AWARE one — which is the
                // property that matters. `K_entity` is a pure function of the realm's volume key, so
                // two instances agree by construction; what must never differ is whether they can see
                // the realm registry at all.
                wireIds = WireIdResolver(syncManager) { existing.components.realmRegistry },
                cryptoManager = cryptoManager,
                tombstoneRegistry = existing.components.tombstoneRegistry,
                // ⚠⚠ The SHARED one: this session's cascades must reach the scheduler it shares (§9.14).
                tombstoneCascades = existing.components.tombstoneCascades,
                // ⚠⚠ The SHARED one too, or this session's overwrites never contend with another's emits.
                emitKeyLocks = existing.components.emitKeyLocks,
                realmRegistry = existing.components.realmRegistry,
                wakeSignal = existing.wakeSignal,
                contentBlobStore = contentBlobStore,
                fileDatabase = fileDatabase,
                syncGate = syncGate,
                grantedRelayFactory = grantedRelayFactory,
                nowMs = nowMs,
                backgroundScope = coroutineScope ?: sessionScope,
            )
            // One line per session init: the multiplicity is otherwise invisible, and getting it wrong
            // reads as a flickering status banner.
            println("SyncProxy -> reusing this process's sync runtime for a new client session")
            // ⚠ The view carries the SHARED scheduler and THIS session's observer; callers wire the
            // observer into their own emitter holders.
            return existing.components.copyForSession(syncObserver = sessionObserver)
        }

        // Realm schema epoch max-merge (`core-schema-evolution.md` §7.1). ⚠ Registered HERE, the one
        // seam every wiring funnels through, so the "merger registered in tests only" gotcha cannot
        // recur for the epoch; without it the row degrades to last-writer-wins. Idempotent by typeId.
        mergerRegistry.register(RealmSchemaEpochMerger())

        // Session-owned by default: per-session work dies with the session.
        val backgroundScope = coroutineScope ?: sessionScope
        // ⚠ The process-scoped loops must not run on the session scope when a runtime holder is in
        // play, or the first client to initialise takes the scheduler down with it when it disconnects.
        // Single-session wirings (tests, JS) fall back to the session scope.
        val runtimeScope = processScope ?: backgroundScope

        // EI-1 (`sync-entity-identity.md`): filled in below, once `realmRegistry` exists.
        //
        // ⚠⚠ Deferred rather than passed directly, and the ordering is the reason: the registry is
        // built here but `RealmRegistry` is not constructed until later in this function, and a wire id
        // is per realm. Capturing the holder keeps one resolver — and so one `K_entity` cache — instead
        // of deriving a key per call.
        // EI-1: the ONE resolver everything in this wiring names entities with — the registry, the
        // observer and the processor. ⚠⚠ Two independently-built resolvers gave one entity two names:
        // the one with `realmRegistry` resolved a shared-realm entity under the shared key, the one
        // without resolved it under the personal key, and only the emit side had the registry.
        // EI-1: ONE resolver for this whole wiring — registry, observer and processor. Its realm
        // registry is supplied lazily because `SyncProcessor` is constructed before `RealmRegistry`
        // exists; capturing null there would make it realm-blind.
        var realmRegistryRef: RealmRegistry? = null
        val wireIds = WireIdResolver(syncManager) { realmRegistryRef }
        val tombstoneRegistry = TombstoneRegistry(
            executionContext = this,
            // ⚠⚠ The reverse index resolves hashes that ARRIVED from the relay, so it must be keyed by
            // the same name the wire uses. Without this the registry falls back to the local
            // hash and every inbound tombstone misses — silently. `WireIdWiringTest` pins it.
            wireIdFor = wireIds::ofOrNull,
            // Local writes stamp `LocalWriteMetadata` (a tombstone older than a local write must not
            // delete it) and local deletes stamp a short-circuit tombstone (a re-pulled own-create must
            // not resurrect the row). Both are local events, so both tick.
            localEventHlc = { syncManager.hlcManager.tick() }
        )

        // Realm epoch = count of `Device`-targeted tombstones seen locally (`docs/adr/0007-sync-device-management.md`
        // §"Realm-state data", the derived cache).
        syncManager.setRealmEpochProvider {
            tombstoneRegistry.tombstoneCountForType(Device.ID).toLong()
        }

        // HLC-piggyback divergence tracker (`sync-backend-relay-plan.md` §5.1). A public key still
        // missing because the peer's `Device` row has not synced yet is transient, not a warning.
        val executionContext = this
        val divergenceTracker = HlcDivergenceTracker(
            cryptoManager = cryptoManager,
            publicKeyLookup = { deviceId ->
                val deviceDataId = DataId.StringDataId.create(
                    context = executionContext.context,
                    type = Device.ID,
                    keyValue = deviceId
                )
                (executionContext.database.getData(deviceDataId) as? Device)?.publicKey?.get()
            },
            nowMs = nowMs,
        )
        @Suppress("UNCHECKED_CAST")
        syncManager.setDivergenceWarningSource(divergenceTracker.warning as StateFlow<Any?>)

        // Two-plane file-content read path (`docs/adr/0017-file-content-two-plane-channel.md` P2): exists only when both a
        // content store and a file database are wired; otherwise a received manifest is a no-op.
        val manifestApplier =
            if (contentBlobStore != null && fileDatabase != null) {
                FileContentManifestApplier(
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    fileDatabase = fileDatabase,
                    contentBlobStore = contentBlobStore
                )
            } else {
                null
            }

        // Content repair (`drive-upload-stop-and-reclamation.md` §4.18). Late-bound because the repair
        // emits through the `SyncObserver`, which is built below — the processor needs the *call*, not
        // the object, at construction time.
        val contentRepairHolder = ContentRepairHolder()

        val syncProcessor = SyncProcessor(
            executionContext = this,
            syncManager = syncManager,
            wireIdResolver = wireIds,
            cryptoManager = cryptoManager,
            mergerRegistry = mergerRegistry,
            tombstoneRegistry = tombstoneRegistry,
            manifestApplier = manifestApplier,
            contentRepair = if (repairDanglingContent) {
                ContentRepair { entityId, propertyId, contentId ->
                    contentRepairHolder.repair?.repair(entityId, propertyId, contentId) ?: false
                }
            } else {
                null
            },
        )
        syncProcessorHolder?.processor = syncProcessor

        // One wake signal: the observer fires it on user-originated enqueues, the scheduler awaits it
        // instead of a plain delay (`core-background-processing.md` §8 Phase 4a-bis).
        val wakeSignal = SyncWakeSignal()

        // SR-M.1b: the device's realm set — the personal realm today, plus any shared realm a
        // migration registers. Each shared realm gets its own inbound `SyncProcessor`, keyed to its
        // `derivedKeys` and a per-realm `HLCManager`, so pulls decrypt and clock in isolation.
        @Suppress("UNUSED_VALUE")
        val realmRegistry = RealmRegistry(
            syncManager = syncManager,
            processorFactory = { realm ->
                // A shared file's chunks live in the realm's own content store under the realm's keys
                // (`drive-shared-realm-file-model.md` §6 Phase 3b.2), so this applier uses the realm's
                // store and keys, not the personal ones. An in-memory test factory must memoise per
                // ownerId to share the store with the emit side.
                val realmStore = sharedRealmContentBlobStoreFactory?.invoke(realm.ownerId)
                val realmManifestApplier =
                    if (realmStore != null && fileDatabase != null) {
                        FileContentManifestApplier(
                            syncManager = syncManager,
                            cryptoManager = cryptoManager,
                            fileDatabase = fileDatabase,
                            contentBlobStore = realmStore,
                            realmKeys = realm.derivedKeys,
                            realmHistoricalKeys = realm.historicalDerivedKeys,
                        )
                    } else {
                        null
                    }
                SyncProcessor(
                    executionContext = this,
                    syncManager = syncManager,
                    wireIdResolver = wireIds,
                    cryptoManager = cryptoManager,
                    mergerRegistry = mergerRegistry,
                    tombstoneRegistry = tombstoneRegistry,
                    manifestApplier = realmManifestApplier,
                    realmKeys = realm.derivedKeys,
                    realmRetiredKeys = realm.retiredKeys,
                    realmHlcManager = HLCManager(realm.deviceId, executionContext.context),
                )
            },
            relayFactory = sharedRealmRelayFactory,
            contentBlobStoreFactory = sharedRealmContentBlobStoreFactory,
        )
        // The resolver the tombstone index closes over, once the realm registry exists.
        realmRegistryRef = realmRegistry

        // Persisted, so a tombstone queued before a restart still names its subtree.
        val tombstoneCascades = TombstoneCascades(DatabaseTombstoneCascadeStore(this))
        val syncObserver = registerSessionObservers(
            syncManager = syncManager,
            wireIds = wireIds,
            cryptoManager = cryptoManager,
            tombstoneRegistry = tombstoneRegistry,
            tombstoneCascades = tombstoneCascades,
            // ⚠⚠ Created at the call, and read back off the observer below, rather than held in a local:
            // one more local in this function made D8 crash dexing it (`Index -1 out of bounds`) and broke
            // every Android build of master. This function is at a size D8 does not tolerate growing.
            emitKeyLocks = EmitKeyLocks(),
            realmRegistry = realmRegistry,
            wakeSignal = wakeSignal,
            contentBlobStore = contentBlobStore,
            fileDatabase = fileDatabase,
            syncGate = syncGate,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
            backgroundScope = backgroundScope,
        )

        // Now that the emitter exists, give the late-bound repair its body
        // (`drive-upload-stop-and-reclamation.md` §4.18). ⚠ Needs a
        // `fileDatabase` — without one this device has no plaintext to put back and the repair could
        // only ever answer "not me", so leaving the holder empty is the same answer more cheaply.
        if (repairDanglingContent && fileDatabase != null) {
            contentRepairHolder.bindLocalBytes(this, fileDatabase, syncObserver)
        }

        // Content-addressing §3.3.4: finish a DataId migration a prior session began — a crash between
        // the marker write and its clear leaves a `RekeyMigrationMarker` in the journal. Every observer
        // it needs is wired by now (the file-service maintainers from `initFileService`, the sync
        // observers above). Defensive: a bad marker stays for the next attempt instead of bricking startup.
        runCatching { DataIdMigrator(this, syncObserver).resumeIncompleteMigrations() }
            .onFailure { println("SyncProxy -> rekey-migration resume failed (will retry next start): $it") }

        // Own-`Device`-row self-heal. Here rather than at the top: the sync observers are registered
        // now, so the repaired row emits a SyncBlock and peers learn it too. No-op while the realm is
        // locked; the `onEnabled` edge below repairs on unlock.
        runCatching {
            if (reconcileOwnDeviceRow(syncManager, tombstoneRegistry)) {
                println("SyncProxy -> own Device row was missing; self-healed (see tech-debt: Device rows have no regeneration path)")
            }
        }.onFailure { println("SyncProxy -> own-Device-row self-heal failed (will retry next start): $it") }

        // E4 (§8.3): publish which schema versions THIS device runs, so the roster can take the
        // readable floor by MIN. After the observers for the same reason as above — a local-only row
        // reads as a silent device and pins the floor for the whole realm. Write-if-changed.
        runCatching {
            val deviceId = syncManager.currentRealmContext()?.deviceId.orEmpty()
            if (DeviceSchemaVersionPublisher(this).publishIfChanged(deviceId)) {
                println("SyncProxy -> published this device's schema versions (E4 roster floor)")
            }
        }.onFailure { println("SyncProxy -> device schema-version publish failed (will retry next start): $it") }

        // This device's hardware model, beside the user-chosen `Device.name`. After the own-row
        // self-heal: the publisher refuses without that row. Write-if-changed.
        runCatching {
            val deviceId = syncManager.currentRealmContext()?.deviceId.orEmpty()
            DeviceHardwarePublisher(this).publishIfChanged(deviceId)
        }.onFailure { println("SyncProxy -> device hardware publish failed (will retry next start): $it") }

        // Task-scoped sync lane (`core-background-processing.md` §10.3 D6): seal deltas a prior session
        // journaled but never checkpointed. No-op on an empty journal; if the realm is still locked, the
        // locked→enabled edge replays instead (`SyncObserver.reemitPendingLockedWork`).
        runtimeScope.launch {
            runCatchingCancellable { syncObserver.replaySyncLaneJournal() }
                .onFailure { println("SyncProxy -> lane-journal replay failed (will retry on unlock): $it") }
        }

        // Task composite (TS-3): D9 lease sweep — the task lease IS the lane lease.
        taskRegistry?.startLeaseSweep(runtimeScope) { taskId ->
            syncObserver.closeSyncLane(taskId)
        }

        // Sync-health signal (`drive-connectivity-service.md`): the scheduler reports into this holder
        // each tick; `initSyncHealth` derives the single-row relation Drive's banner observes over IPC.
        // Local-only, never synced.
        val healthHolder = syncHealthHolder ?: SyncHealthHolder()
        // Seed the lock state before the first derived emission, so a sealed cold start publishes
        // `locked` at once instead of the previous process's row — see [SyncHealthHolder.seedLocked].
        healthHolder.seedLocked(syncManager.getUnlockStatus().isLocked)
        initSyncHealth(runtimeScope, healthHolder)

        // Deferred-content figures behind Drive's "Waiting for Wi-Fi. N files" banner
        // (`sync-metered-plane-split.md` §8). Its own row: `SyncHealthStatus` is a positional composite
        // and cannot grow fields for a version-skewed Drive.
        initContentTransferStatus(runtimeScope, healthHolder)

        // The "3 of 12" of an upload pass in flight (`drive-upload-progress.md` P2); its own row for
        // the same positional reason.
        initContentUploadProgress(runtimeScope, healthHolder)

        // Pull activity (`ios-sync-activity-indicator.md` §3.1): ONE row per writer, keyed by the host
        // app id, so several runtimes over one store never clear each other's. Seeded `false` here,
        // which also replaces a `true` this app's previous process left behind when it was killed.
        val catchUp = initSyncCatchUp(runtimeScope, hostAppId, heartbeat = catchUpHeartbeat)

        // Who hosts this runtime (SK-2). Written once; its own row for the same positional reason.
        initRuntimeIdentity(hostAppId, hostAppVersion)

        // The schema-driven content walk behind the backfill's enumeration (`content-plane-refcount.md`
        // §9.6). Nothing on a device frees content (`content-plane-cr2-quorumless.md` §10.9).
        //
        // ⚠ The tree walk stays as the fallback, for a store that cannot enumerate (JS).
        val schemaContentWalk = reclamationSources?.declarableContent?.let { treeWalk ->
            val walkRegistry = schemaProvider as? org.tenshu.core.common.proxy.SchemaRegistry
            if (objectStore != null && walkRegistry != null) {
                SchemaDrivenContentWalk(
                    executionContext = this,
                    objectStore = objectStore,
                    mapper = org.tenshu.core.common.proxy.db.DbEntryMapper(
                        context,
                        walkRegistry,
                        walkRegistry,
                        traitResolver,
                    ),
                    fallback = treeWalk,
                )
            } else {
                null
            }
        }

        // The content-declaration backfill (`content-plane-refcount.md` §9.7): describes content
        // uploaded before the declaration seam existed. ⚠ Inert by absence — no `declarableContent`
        // source, no pass.
        //
        // ⚠⚠ Not on a per-tick hook: this one fetches and decrypts one manifest per live content
        // object, so it belongs behind idle+charging and a completion marker.
        //
        // ⚠⚠ The enumeration is SCHEMA-DRIVEN where the store can enumerate, and falls back to the
        // injected Drive-tree walk where it cannot (the JS `IndexedDbObjectStore` implements neither
        // `rowsOfTypes` nor `scanRowsOfType`); see `SchemaDrivenContentWalk` for why only the schema
        // keeps §9.6's *declared ⇒ walkable* invariant for every type.
        val declarationBackfill = reclamationSources?.declarableContent?.let { treeWalk ->
            contentBlobStore?.let { store ->
                val schemaWalk = schemaContentWalk
                ContentDeclarationBackfill(
                    declarableContent = schemaWalk?.let { walk -> suspend { walk.enumerate() } } ?: treeWalk,
                    chunkRefsOf = { contentId -> syncProcessor.contentChunkRefs(contentId) },
                    // ⚠ The relay's own answer for the manifest ref: its 404 is the only "never existed".
                    manifestPresent = { contentId -> store.hasChunk(manifestContentRef(contentId)) },
                    // Null-safe by the holder: a platform that wires no repair hands nothing over.
                    repairAbsent = { holder, propertyId, contentId ->
                        contentRepairHolder.ensure?.repair(holder, propertyId, contentId) ?: false
                    },
                    manifestRefOf = { contentId -> manifestContentRef(contentId) },
                    // ⭐ Describe only. Edges travel with each holder's own push.
                    // ⭐ A describe refused as FREED is final, never a retry
                    // (`content-plane-cr2-b4-spec.md` §2.2 step 4).
                    describe = { contents -> store.describe(contents) != DescribeOutcome.Failed },
                    // ⚠⚠ The marker may only latch once this device has finished catching up.
                    // `0` is "walked to the bottom"; `null` (never started) and `> 0` (descending) both
                    // mean the corpus is still arriving, and a pass that latches then strands whatever
                    // has not yet landed — a whole realm can be left undeclared.
                    isCaughtUp = { syncManager.backfillCursor == 0L },
                    // ⚠⚠ The pass declares outside the emit keys, so it revalidates
                    // against this runtime's emits — the SAME observer's keys the emit paths hold.
                    emitGuard = syncObserver.backfillEmitGuard,
                    // ⚠ A realm this build created, recovered or joined was described
                    // as it uploaded, so the pass never runs on it.
                    readComplete = { syncManager.contentDescribedFromGenesis || reclamationSources.readBackfillComplete() },
                    writeComplete = reclamationSources.writeBackfillComplete,
                    report = onReclamationReport ?: {},
                )
            }
        }

        val scheduler = SyncScheduler(
            executionContext = this,
            syncManager = syncManager,
            syncProcessor = syncProcessor,
            cryptoManager = cryptoManager,
            coroutineScope = runtimeScope,
            pollingIntervalMs = pollingIntervalMs,
            // ⚠⚠ The orphaned-bytes sweep is this hook's only consumer
            // (`content-plane-cr2-quorumless.md` §10.9). It runs on the tick because that is when
            // ingested deletes have just landed.
            onTickSettled = orphanedBytesSweep,
            declarationBackfill = declarationBackfill,
            activeStreamCount = streamRegistry.activeStreamCount,
            onPeerTip = divergenceTracker::observe,
            wakeSignal = wakeSignal,
            syncGate = syncGate,
            storageGate = storageGate,
            realmRegistry = realmRegistry,
            realmRegistrar = realmRegistrar,
            // G3: the sweeper refuses on a store that cannot enumerate, or when the registry cannot
            // prove which blocks are still queued.
            retiredBlockSweeper = RetiredBlockSweeper(
                executionContext = this,
                realmRegistry = realmRegistry,
                objectStore = objectStore,
            ),
            personalRelay = personalRelay,
            syncHealthHolder = healthHolder,
            catchUp = catchUp,
            backgroundLiveness = backgroundLiveness,
            // On the locked→enabled edge, replay saves and file-content emits held while the realm had
            // no keys — otherwise a file saved during the lock window is stranded local forever.
            onEnabled = {
                syncObserver.reemitPendingLockedWork()
                // Same repair as at init, for a cold start into the lock screen. Idempotent.
                runCatching {
                    if (reconcileOwnDeviceRow(syncManager, tombstoneRegistry)) {
                        println("SyncProxy -> own Device row was missing; self-healed on unlock")
                    }
                }.onFailure { println("SyncProxy -> own-Device-row self-heal failed on unlock: $it") }
                // A cold start into the lock screen had no realm to publish under at init.
                runCatching {
                    val deviceId = syncManager.currentRealmContext()?.deviceId.orEmpty()
                    DeviceHardwarePublisher(executionContext).publishIfChanged(deviceId)
                }.onFailure { println("SyncProxy -> device hardware publish failed on unlock: $it") }
                // ⚠⚠ And rekey markers, for the same reason. `resumeIncompleteMigrations` runs once at
                // init, so a cold start into the lock screen attempts it with no realm: the old row is
                // already gone locally and its tombstone cannot be named, so the retry is (correctly)
                // kept — and without this nothing would ever retry it, leaving every peer holding the
                // old path. Idempotent, like the resume at init.
                // ⚠ This unlock edge is precisely why that retry lives in the emit-only phase-2 journal:
                // an arbitrary amount of local work happens between the lock and here, including a new
                // file at the very path being retired.
                runCatching {
                    // ⚠⚠ The OWED journal only. Phase 1 deletes unconditionally, and its safety rests
                    // on running before anything could recreate the retired path — true at proxy init,
                    // false here, after arbitrary user activity during the lock.
                    val resumed = DataIdMigrator(executionContext, syncObserver).resumeOwedTombstones()
                    if (resumed > 0) println("SyncProxy -> resumed $resumed rekey migration(s) on unlock")
                }.onFailure { println("SyncProxy -> rekey-migration resume failed on unlock: $it") }
            },
            drainDeferredContent = { force, onProgress ->
                syncObserver.drainDeferredContent(force, onProgress)
            },
            contentEmittedTotal = { syncObserver.deferredContentEmitted },
            contentChunksLandedTotal = { syncObserver.contentChunksLandedTotal },
            contentEmitsLandedTotal = { syncObserver.contentEmitsLandedTotal },
            contentPlaneWaiting = syncObserver::contentPlaneWaiting,
            contentPlaneWaitRemaining = syncObserver::contentPlaneWaitRemainingMs,
            retryContentPlane = syncObserver::retryContentPlane,
            // Read *after* each drain to classify a `507` (over quota) the same way the block drain's
            // 402/401 are classified — see `SyncObserver.contentStorageRejected`.
            // ⚠⚠ The FRESH refusal, not the sticky latch. Classifying a tick on the sticky flag reports
            // a rejection on ticks that attempted nothing — which re-arms the backoff for ever and
            // wedges the device. See `SyncObserver.consumeStorageRefusal` for the three ways.
            contentStorageRejected = { syncObserver.consumeStorageRefusal() },
            onStorageRejectionChanged = onStorageRejectionChanged,
            onQueuedContentBytes = onQueuedContentBytes,
            pendingContentEmitCount = syncObserver::deferredContentFigures,
            nowMs = nowMs,
            hlcWatermarkStore = hlcWatermarkStore,
            // C8 (`sync-device-catch-up.md` §5.7): the joiner-snapshot plane. Null ⇒ a joiner replays
            // the log, exactly as before.
            contentBlobStore = contentBlobStore,
            freedContentResolver = freedContentResolverFor(this, syncProcessor, syncObserver, onChangeLost),
        )
        if (startBackgroundLoops) scheduler.start()

        // C8 write half: a periodic idle+charging maintenance item that seals slices for OTHER devices,
        // so it must never compete with work a user is waiting for. Registration is idempotent; the
        // `ScheduleDrainLoop` below dispatches it in-process.
        runtimeScope.launch { registerSliceSnapshotSchedule() }
        // ⚠ Registered unconditionally, like the slice-snapshot pass: the op itself is a no-op
        // without a backfill wired, and a schedule item for an absent pass costs one row.
        runtimeScope.launch { registerContentBackfillSchedule() }

        // `core-background-processing.md` §3.2(b): the in-process `ScheduleService` registry drain — its own loop,
        // not a `SyncScheduler` repurpose.
        val scheduleDrainLoop = ScheduleDrainLoop(
            executionContext = this,
            coroutineScope = runtimeScope,
            platformScheduler = platformScheduler,
        )
        if (startBackgroundLoops) scheduleDrainLoop.start()

        val components = SyncProxyComponents(
            scheduler = scheduler,
            syncObserver = syncObserver,
            tombstoneRegistry = tombstoneRegistry,
            tombstoneCascades = tombstoneCascades,
            emitKeyLocks = syncObserver.emitKeyLocks,
            divergenceTracker = divergenceTracker,
            scheduleDrainLoop = scheduleDrainLoop,
            realmRegistry = realmRegistry,
            syncHealthHolder = healthHolder,
            catchUp = catchUp,
        )
        // Publish the runtime for the NEXT session (`drive-upload-progress.md` P3) — after everything
        // is built and started, so nobody can observe a half-constructed runtime.
        runtimeHolder?.let {
            it.runtime = SyncRuntime(components = components, wakeSignal = wakeSignal, scope = runtimeScope)
            println("SyncProxy -> sync runtime elected by this session (one scheduler per process)")
        }
        return components
    }
}

/**
 * Build and register the observers that are genuinely per client session, returning the
 * [SyncObserver] (this session's tombstone / block / file-content emitter).
 *
 * ⚠ These cannot be process-scoped: `ExecutionContext.observerRegistry` is per context, so an
 * observer only sees saves made through its own context, and one process-wide observer would
 * silently miss every other app's writes. That asymmetry is why [initSyncService] splits as it
 * does — see [SyncRuntimeHolder]. Everything passed in is shared runtime state, so a later
 * session's observers enqueue into the queues the one scheduler drains.
 */
private fun ExecutionContext.registerSessionObservers(
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    tombstoneRegistry: TombstoneRegistry,
    tombstoneCascades: TombstoneCascades,
    emitKeyLocks: EmitKeyLocks,
    /** EI-1: the ONE resolver this wiring names entities with — see `initSyncService`. */
    wireIds: WireIdResolver,
    realmRegistry: RealmRegistry,
    wakeSignal: SyncWakeSignal,
    contentBlobStore: ContentBlobStore?,
    fileDatabase: FileDatabase?,
    syncGate: SyncGate,
    grantedRelayFactory: ((RealmContext) -> SyncRelay?)?,
    nowMs: () -> Long,
    backgroundScope: CoroutineScope,
): SyncObserver {
    val syncObserver = SyncObserver(
        executionContext = this,
        syncManager = syncManager,
        wireIdResolver = wireIds,
        cryptoManager = cryptoManager,
        tombstoneRegistry = tombstoneRegistry,
        tombstoneCascades = tombstoneCascades,
        emitKeyLocks = emitKeyLocks,
        contentBlobStore = contentBlobStore,
        fileDatabase = fileDatabase,
        wakeSignal = wakeSignal,
        // The emit consumer shares the session-owned scope.
        coroutineScope = backgroundScope,
        realmRegistry = realmRegistry,
        // The metered policy is enforced here, on the content plane (`sync-metered-plane-split.md` §3).
        contentGate = syncGate,
    )
    addDataObserver(syncObserver)

    // SR-3b.2 auto-join: a `SharedRealmMembership` landing locally (own or synced in) registers its
    // realm. Must observe SYNC_INGEST saves — that is how a second device joins. Skips realms
    // already registered.
    addDataObserver(SharedRealmJoinObserver(this, syncManager, cryptoManager, realmRegistry))

    // Delegated-admission migration (`delegated-device-admission-authority.md` §8): a pre-P3 joiner
    // announces a self-minted A33 key, an A1-holder answers with a depth-1 cert grant, and deep
    // chains flatten the same way. Synced-row choreography only; no private key ever on a row.
    addDataObserver(AdmissionMigrationObserver(this, syncManager, cryptoManager))

    // GR-3 / D9-b: a member's second device mints its own per-realm device key and admits itself
    // once the member-authority key and membership have synced in; the personal-key join observer
    // above cannot admit a cross-user member.
    if (grantedRelayFactory != null) {
        addDataObserver(
            MemberSelfBindObserver(
                executionContext = this,
                syncManager = syncManager,
                cryptoManager = cryptoManager,
                realmRegistry = realmRegistry,
                grantedRelayFactory = grantedRelayFactory,
                nowMs = nowMs,
                coroutineScope = backgroundScope,
            ),
        )
    }
    return syncObserver
}

/**
 * ⭐ The production [FreedContentResolver] (`content-plane-cr2-b4-spec.md` §2.2) — the row from
 * the block (decrypted by the processor), the re-upload through the observer, and the P3 notice
 * (§2.4) sent as a synced `ChangeLostNotification` through `SendNotification`, plus
 * [onChangeLost]'s OS alert where bound.
 *
 * ⚠ Out of `initSyncService` on purpose: that function is at the size D8 will not dex (`tech-debt.md`).
 */
internal fun freedContentResolverFor(
    executionContext: ExecutionContext,
    syncProcessor: SyncProcessor,
    syncObserver: SyncObserver,
    onChangeLost: ((fileName: String, path: String) -> Unit)?,
): () -> FreedContentResolver? {
    val resolver = FreedContentResolver(
        executionContext = executionContext,
        rowOf = syncProcessor::rowOfOwnBlock,
        reupload = syncObserver::reuploadForRevive,
        notifyChangeLost = { holder, row ->
            val path = FreedContentResolver.pathOf(holder, row)
            val fileName = path.substringAfterLast('/').ifEmpty { path }
            sendChangeLostNotification(executionContext, fileName, path)
            onChangeLost?.invoke(fileName, path)
        },
    )
    return { resolver }
}

/**
 * Sends the synced P3 notice through `:notification-service`'s `SendNotification` (§2.4), or saves it
 * directly in a wiring that registers no notification service (the op is exactly that save).
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun sendChangeLostNotification(executionContext: ExecutionContext, fileName: String, path: String) {
    val notification = org.tenshu.core.file.notifications.ChangeLostNotification.create(
        context = executionContext.context,
        uuid = kotlin.uuid.Uuid.random().toString(),
        timestamp = executionContext.context.clock().nowEpochMillis(),
        fileName = fileName,
        path = path,
    )
    val sent = runCatching {
        executionContext.execute<Data?>(
            operationId = org.tenshu.core.notifications.SendNotification.ID,
            caller = null,
            params = mapOf(org.tenshu.core.notifications.SendNotification.Params.NOTIFICATION to notification),
        )
    }.isSuccess
    if (!sent) executionContext.saveData(notification)
}

