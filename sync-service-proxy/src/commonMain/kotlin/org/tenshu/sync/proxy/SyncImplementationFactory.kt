package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.AwaitPairing
import org.tenshu.sync.CancelPairing
import org.tenshu.sync.CancelScheduled
import org.tenshu.sync.ClearBiometricSetup
import org.tenshu.sync.RelaxToHeadless
import org.tenshu.sync.CloseContentStream
import org.tenshu.sync.OpenContentStream
import org.tenshu.sync.ReadContentRange
import org.tenshu.sync.AcceptSharedRealmGrant
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.CreateSharedRealm
import org.tenshu.sync.DeclinePendingGrant
import org.tenshu.sync.GrantSharedRealmMembership
import org.tenshu.sync.PollPendingGrants
import org.tenshu.sync.RemoveSharedRealmMember
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.DrainSync
import org.tenshu.sync.SyncNow
import org.tenshu.sync.Schedule
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.GetRecoveryPhrase
import org.tenshu.sync.GetSyncStatus
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.GetUnlockStatus
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.OfferHubPairing
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.RespondToHubPairing
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.ReadmitDevice
import org.tenshu.sync.ReregisterDevice
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.SetUpBiometricUnlock
import org.tenshu.sync.SetUpLocalUnlock
import org.tenshu.sync.LockRealm
import org.tenshu.sync.SetUpUnlock
import org.tenshu.sync.StartPairing
import org.tenshu.sync.StopPairing
import org.tenshu.sync.UnlockBiometric
import org.tenshu.sync.UnlockLocal
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.proxy.impl.ApprovePairingRequestImplementation
import org.tenshu.sync.proxy.impl.AwaitPairingImplementation
import org.tenshu.sync.proxy.impl.CancelPairingImplementation
import org.tenshu.sync.proxy.impl.CancelScheduledImplementation
import org.tenshu.sync.proxy.impl.ClearBiometricSetupImplementation
import org.tenshu.sync.proxy.impl.RelaxToHeadlessImplementation
import org.tenshu.sync.proxy.impl.CloseContentStreamImplementation
import org.tenshu.sync.proxy.impl.OpenContentStreamImplementation
import org.tenshu.sync.proxy.impl.ReadContentRangeImplementation
import org.tenshu.sync.proxy.impl.AcceptSharedRealmGrantImplementation
import org.tenshu.sync.proxy.impl.CreateRealmImplementation
import org.tenshu.sync.proxy.impl.CreateSharedRealmImplementation
import org.tenshu.sync.proxy.impl.DeclinePendingGrantImplementation
import org.tenshu.sync.proxy.impl.GrantSharedRealmMembershipImplementation
import org.tenshu.sync.proxy.impl.PollPendingGrantsImplementation
import org.tenshu.sync.proxy.impl.RemoveSharedRealmMemberImplementation
import org.tenshu.sync.proxy.impl.ListScheduledImplementation
import org.tenshu.sync.proxy.impl.CompactSchemaImplementation
import org.tenshu.sync.proxy.impl.DrainSyncImplementation
import org.tenshu.sync.proxy.impl.SyncNowImplementation
import org.tenshu.sync.proxy.impl.ScheduleImplementation
import org.tenshu.sync.proxy.impl.GetChangesImplementation
import org.tenshu.sync.proxy.impl.GetPairingStatusImplementation
import org.tenshu.sync.proxy.impl.GetPendingPairingRequestsImplementation
import org.tenshu.sync.proxy.impl.GetRecoveryPhraseImplementation
import org.tenshu.sync.proxy.impl.GetSyncStatusImplementation
import org.tenshu.sync.proxy.impl.GetSyncLogBeforeImplementation
import org.tenshu.sync.proxy.impl.GetSyncLogImplementation
import org.tenshu.sync.proxy.impl.GetTombstonesImplementation
import org.tenshu.sync.proxy.impl.GetUnlockStatusImplementation
import org.tenshu.sync.proxy.impl.InitiatePairingImplementation
import org.tenshu.sync.proxy.impl.OfferHubPairingImplementation
import org.tenshu.sync.proxy.impl.OfferPairingImplementation
import org.tenshu.sync.proxy.impl.GetOfferStatusImplementation
import org.tenshu.sync.proxy.impl.CancelPairingOfferImplementation
import org.tenshu.sync.proxy.impl.RespondToHubPairingImplementation
import org.tenshu.sync.proxy.impl.RespondToPairingOfferImplementation
import org.tenshu.sync.proxy.impl.ApprovePairingOfferImplementation
import org.tenshu.sync.proxy.impl.DenyPairingOfferImplementation
import org.tenshu.sync.proxy.impl.PushBlockImplementation
import org.tenshu.sync.proxy.impl.PushTombstoneImplementation
import org.tenshu.sync.proxy.impl.RecoveryClaimImplementation
import org.tenshu.sync.proxy.impl.ReadmitDeviceImplementation
import org.tenshu.sync.proxy.impl.ReregisterDeviceImplementation
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import org.tenshu.sync.proxy.impl.RevokeDeviceImplementation
import org.tenshu.sync.proxy.impl.SetUpBiometricUnlockImplementation
import org.tenshu.sync.proxy.impl.SetUpLocalUnlockImplementation
import org.tenshu.sync.proxy.impl.LockRealmImplementation
import org.tenshu.sync.proxy.impl.SetUpUnlockImplementation
import org.tenshu.sync.proxy.impl.StartPairingImplementation
import org.tenshu.sync.proxy.impl.StopPairingImplementation
import org.tenshu.sync.proxy.impl.UnlockBiometricImplementation
import org.tenshu.sync.proxy.impl.UnlockLocalImplementation
import org.tenshu.sync.proxy.impl.WriteSliceSnapshotsImplementation

fun syncProxyImplementationFactory(
    id: String,
    relay: SyncRelay,
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    tombstoneEmitter: TombstoneEmitter? = null,
    /**
     * SR-3b.3: the device's [RealmRegistry], needed by `CreateSharedRealm` to
     * register the realm it bootstraps. `null` (the default) ⇒ `CreateSharedRealm`
     * resolves to `null` (unavailable) — keeps existing callers that don't wire
     * shared realms compiling unchanged. Production / system-test wirings pass
     * `SyncProxyComponents.realmRegistry`.
     */
    realmRegistry: RealmRegistry? = null,
    /**
     * GR-3: builds a per-realm-device-key [SyncRelay] ([perRealmDeviceRelay]) for a
     * granted realm, so a cross-user member's shared-realm JWT is signed with its
     * fresh per-realm key (D2 unlinkability) rather than the personal A4. Needed by
     * `AcceptSharedRealmGrant` / `GrantSharedRealmMembership` / `RemoveSharedRealmMember`;
     * `null` (the default) ⇒ those ops resolve to `null`. Production / system-test DI
     * supply it.
     */
    grantedRelayFactory: ((RealmContext) -> SyncRelay?)? = null,
    /** GR-3 wall clock (ms) for capability/binding/joined timestamps; injected so the factory stays commonMain. */
    nowMs: () -> Long = { 0L },
    /**
     * E4-5 (`core-schema-evolution.md` §8.3): the compaction maintenance sweep,
     * usually `SchemaCompactionMaintenance::runOnce`. `null` (the default) ⇒
     * `CompactSchema` resolves to unavailable, which is the honest state for a
     * wiring with no `ObjectStore` handle — the sweep needs to enumerate rows by
     * type, and `Database` does not expose the store.
     */
    compactSchema: (suspend () -> Unit)? = null,
    /**
     * Local-first reframe (ADR 0019): gates `CreateRealm`'s eager relay genesis
     * (`POST /sync/realm`). Default [SyncGate.AlwaysAllow] keeps test/legacy
     * wirings registering at creation; production passes a storage-backed gate
     * (`StorageSyncGate`) so an unsubscribed user's realm stays local-only and
     * `SyncScheduler` performs the deferred genesis once a subscription binds.
     */
    registrationGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Layer-3 OS-woken scheduler (bgproc Phase 6/7). `Schedule` / `CancelScheduled`
     * mirror the persisted registry onto it so app-closed wakeups track the store.
     * `null` (the default, and desktop/test wirings) ⇒ registry-only via
     * [ScheduleDrainLoop] — no behaviour change. Android production passes a
     * `WorkManagerScheduler`.
     */
    platformScheduler: PlatformScheduler? = null,
    /**
     * bgproc Phase 1 (`bgproc-scheduling-roles.md` §6): the session's
     * [SyncSchedulerHolder], so `DrainSync` can delegate to the live scheduler's
     * `drainNow()`. `null` (the default, test/desktop wirings that don't schedule
     * a `DrainSync` item) ⇒ `DrainSync` no-ops. Android production passes the
     * Koin single populated post-init.
     */
    syncSchedulerHolder: SyncSchedulerHolder? = null,
    /**
     * Task composite (core-task-composite.md §5, TS-3): the live-task registry
     * the TaskService ops (`RegisterTask`/`RenewTaskLease`/`TransitionTask`)
     * mutate. `null` (the default, and every wiring that predates tasks) ⇒
     * the ops resolve to `null` (unavailable). Production wirings pass the
     * per-proxy singleton; its `activeCount` feeds the platform liveness sum.
     */
    taskRegistry: TaskRegistry? = null,
    /**
     * Deref-at-call-time accessor for the lane API ([SyncBlockEmitter] —
     * `SyncObserver`), which exists only after `initSyncService`. Same timing
     * pattern as `realmRegistryHolder.realmRegistry` at the call sites.
     */
    laneEmitter: (() -> org.tenshu.sync.common.SyncBlockEmitter?)? = null,
    /**
     * The pause between `AwaitPairing`'s completion attempts. `null` (every production wiring) keeps
     * its real cadence; the system-test harness passes a stepped one so a test decides when the
     * joiner looks again.
     */
    pairingWaitPacer: (suspend () -> Unit)? = null,
): OperationImplementation? {
    return when (id) {
        PushBlock.ID -> PushBlockImplementation(relay, ContentPropertyTagger(syncManager) { realmRegistry })
        GetChanges.ID -> GetChangesImplementation(relay)
        // D2b streaming (drive-streaming-preview.md §3.5): the impls delegate to the
        // OnDemandResolution component at execute-time, so they need no factory collaborators.
        OpenContentStream.ID -> OpenContentStreamImplementation()
        ReadContentRange.ID -> ReadContentRangeImplementation()
        CloseContentStream.ID -> CloseContentStreamImplementation()
        // ⭐ sync-envelope-authentication.md §3.1: tagged at drain under the key of the realm it names.
        PushTombstone.ID -> PushTombstoneImplementation(relay) { ownerId ->
            (realmRegistry?.runtimeFor(ownerId)?.context
                ?: syncManager.currentRealmContext()?.takeIf { it.ownerId == ownerId })
                ?.derivedKeys?.sharedIdentityKey
        }
        GetTombstones.ID -> GetTombstonesImplementation(relay)
        GetSyncLog.ID -> GetSyncLogImplementation(relay)
        GetSyncLogBefore.ID -> GetSyncLogBeforeImplementation(relay)
        StartPairing.ID -> StartPairingImplementation(syncManager)
        StopPairing.ID -> StopPairingImplementation(syncManager)
        GetSyncStatus.ID -> GetSyncStatusImplementation(syncManager)
        GetRecoveryPhrase.ID -> GetRecoveryPhraseImplementation(syncManager)
        GetPendingPairingRequests.ID -> GetPendingPairingRequestsImplementation(
            relay,
            syncManager,
            cryptoManager,
        )
        ApprovePairingRequest.ID -> ApprovePairingRequestImplementation(
            relay,
            syncManager,
            cryptoManager,
            freedContentResolver = { syncSchedulerHolder?.scheduler?.freedContentResolverOrNull() },
        )
        InitiatePairing.ID -> InitiatePairingImplementation(relay, syncManager, cryptoManager)
        GetPairingStatus.ID -> GetPairingStatusImplementation(relay, syncManager, cryptoManager)
        AwaitPairing.ID -> if (pairingWaitPacer != null) {
            AwaitPairingImplementation(relay, syncManager, cryptoManager, pairingWaitPacer)
        } else {
            AwaitPairingImplementation(relay, syncManager, cryptoManager)
        }
        OfferPairing.ID -> OfferPairingImplementation(syncManager, cryptoManager)
        OfferHubPairing.ID -> OfferHubPairingImplementation(relay, syncManager, cryptoManager)
        GetOfferStatus.ID -> GetOfferStatusImplementation(relay, syncManager, cryptoManager)
        CancelPairingOffer.ID -> CancelPairingOfferImplementation(syncManager)
        RespondToPairingOffer.ID -> RespondToPairingOfferImplementation(syncManager, cryptoManager, relay)
        RespondToHubPairing.ID -> RespondToHubPairingImplementation(relay, syncManager, cryptoManager)
        ApprovePairingOffer.ID -> ApprovePairingOfferImplementation(
            relay,
            syncManager,
            cryptoManager,
            freedContentResolver = { syncSchedulerHolder?.scheduler?.freedContentResolverOrNull() },
        )
        DenyPairingOffer.ID -> DenyPairingOfferImplementation(relay, syncManager, cryptoManager)
        CreateRealm.ID -> CreateRealmImplementation(syncManager, cryptoManager, relay, registrationGate)
        ReregisterDevice.ID -> ReregisterDeviceImplementation(syncManager, cryptoManager, relay)

        ReadmitDevice.ID -> ReadmitDeviceImplementation(syncManager, cryptoManager, relay)
        CreateSharedRealm.ID -> realmRegistry?.let {
            CreateSharedRealmImplementation(syncManager, cryptoManager, relay, it)
        }
        GrantSharedRealmMembership.ID -> if (realmRegistry != null && grantedRelayFactory != null) {
            GrantSharedRealmMembershipImplementation(syncManager, cryptoManager, relay, realmRegistry, grantedRelayFactory, nowMs)
        } else null
        AcceptSharedRealmGrant.ID -> if (realmRegistry != null && grantedRelayFactory != null) {
            AcceptSharedRealmGrantImplementation(syncManager, cryptoManager, relay, realmRegistry, grantedRelayFactory, nowMs)
        } else null
        RemoveSharedRealmMember.ID -> if (realmRegistry != null && grantedRelayFactory != null) {
            RemoveSharedRealmMemberImplementation(syncManager, cryptoManager, relay, realmRegistry, grantedRelayFactory, nowMs)
        } else null
        PollPendingGrants.ID -> if (realmRegistry != null && grantedRelayFactory != null) {
            PollPendingGrantsImplementation(syncManager, cryptoManager, relay, realmRegistry, grantedRelayFactory, nowMs)
        } else null
        DeclinePendingGrant.ID -> if (realmRegistry != null && grantedRelayFactory != null) {
            DeclinePendingGrantImplementation(syncManager, cryptoManager, relay, realmRegistry, grantedRelayFactory, nowMs)
        } else null
        // Realm-data reset (`control-data-reset.md` R1). Not a sync operation by
        // subject — but emitting one tombstone per deleted row IS its substance
        // (§3: a bare `deleteData` loop converges nowhere), and this factory is the
        // one place already holding the [TombstoneEmitter]. Registered here rather
        // than in `:core-common`'s generic factory, which cannot see the emitter
        // (`:sync-service-common` depends on `:core-common`, not the reverse).
        org.tenshu.core.realmdata.DeleteRealmData.ID ->
            org.tenshu.sync.proxy.realmdata.DeleteRealmDataImplementation(tombstoneEmitter)
        RecoveryClaim.ID -> RecoveryClaimImplementation(syncManager, cryptoManager, relay)
        RevokeDevice.ID -> RevokeDeviceImplementation(
            tombstoneEmitter,
            syncManager,
            cryptoManager,
            relay,
            nowMs
        )
        org.tenshu.sync.ForgetThisDevice.ID -> ForgetThisDeviceImplementation(
            syncManager = syncManager,
            // ⚠ The real RevokeDevice implementation, constructed identically — forgetting yourself
            // must leave the realm in exactly the state a peer-initiated removal would.
            revokeDevice = { onRelayStripFailed ->
                RevokeDeviceImplementation(
                    tombstoneEmitter,
                    syncManager,
                    cryptoManager,
                    relay,
                    nowMs,
                    onRelayStripFailed,
                )
            },
            nowMs = nowMs,
            realmRegistry = realmRegistry,
            // Resolved per poll: the holder is filled after `initSyncService`, later than this
            // factory runs. No holder (web) ⇒ never refused ⇒ the bounded wait, as before.
            relayRefusesThisDevice = { syncSchedulerHolder?.scheduler?.relayRefusesThisDevice == true },
        )
        GetUnlockStatus.ID -> GetUnlockStatusImplementation(syncManager)
        SetUpLocalUnlock.ID -> SetUpLocalUnlockImplementation(syncManager)
        SetUpUnlock.ID -> SetUpUnlockImplementation(syncManager)
        UnlockLocal.ID -> UnlockLocalImplementation(syncManager)
        LockRealm.ID -> LockRealmImplementation(syncManager)
        SetUpBiometricUnlock.ID -> SetUpBiometricUnlockImplementation(syncManager)
        UnlockBiometric.ID -> UnlockBiometricImplementation(syncManager)
        ClearBiometricSetup.ID -> ClearBiometricSetupImplementation(syncManager)
        RelaxToHeadless.ID -> RelaxToHeadlessImplementation(syncManager)
        CancelPairing.ID -> CancelPairingImplementation(syncManager)
        Schedule.ID -> ScheduleImplementation(platformScheduler)
        ListScheduled.ID -> ListScheduledImplementation()
        CancelScheduled.ID -> CancelScheduledImplementation(platformScheduler)
        DrainSync.ID -> DrainSyncImplementation { syncSchedulerHolder?.scheduler?.drainNow() }
        // E4-5 (§8.3): unavailable (null) without a wired sweep, exactly like the
        // task-registry ops below — the op id exists so `ScheduleService` can accept
        // it, and a DI that supplies `compactSchema` lights it up.
        org.tenshu.sync.CompactSchema.ID -> compactSchema?.let { CompactSchemaImplementation(it) }
        SyncNow.ID -> SyncNowImplementation { syncSchedulerHolder?.scheduler?.forceDrainOnce() }
        // C8 (`sync-device-catch-up.md` §5.7): the joiner-snapshot pass, dispatched by the
        // schedule registry on the idle+charging path. Same thunk shape as `DrainSync` — the
        // scheduler does not exist yet when this graph is built, and a cold-boot race is a
        // no-op the next period covers.
        org.tenshu.sync.WriteSliceSnapshots.ID -> WriteSliceSnapshotsImplementation {
            syncSchedulerHolder?.scheduler?.writeSliceSnapshots()
        }
        // CR-4d (`content-plane-refcount.md` §9.7): the one pass that describes content uploaded
        // before the declaration seam existed. Same thunk shape, same cold-boot tolerance.
        org.tenshu.sync.BackfillContentDeclarations.ID ->
            org.tenshu.sync.proxy.impl.BackfillContentDeclarationsImplementation {
                syncSchedulerHolder?.scheduler?.backfillContentDeclarations()
            }
        // Task composite (TS-3): unavailable (null) without a registry.
        org.tenshu.sync.RegisterTask.ID -> taskRegistry?.let {
            org.tenshu.sync.proxy.impl.RegisterTaskImplementation(it, laneEmitter ?: { null })
        }
        org.tenshu.sync.RenewTaskLease.ID -> taskRegistry?.let {
            org.tenshu.sync.proxy.impl.RenewTaskLeaseImplementation(it)
        }
        org.tenshu.sync.TransitionTask.ID -> taskRegistry?.let {
            org.tenshu.sync.proxy.impl.TransitionTaskImplementation(it, laneEmitter ?: { null })
        }
        else -> null
    }
}
