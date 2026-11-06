package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.sync.proxy.isRealmAlreadyExistsFailure
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistrar

/**
 * First-device realm bootstrap (docs/adr/0007-sync-device-management.md, sync-relay-api.md §3.2).
 * Initialises the local sync state and pushes the first `Device { isAuthorized = true }`
 * entity into the synced DB (§2.3 first-device step 4) — both **unconditionally**, so the
 * local realm is usable offline / before any subscription. The first device is authorised
 * by virtue of being the realm's master-key holder — same trust model as recovery.
 *
 * The relay genesis (`POST /sync/realm`) is delegated to [RealmRegistrar] and gated by
 * [registrationGate] (local-first reframe, ADR 0019). With the default
 * [SyncGate.AlwaysAllow] (test/legacy wirings, or a user already subscribed) the realm
 * registers eagerly here. In production the gate is storage-backed
 * (`StorageSyncGate`): a realm created **without** a storage subscription stays
 * local-only — no relay request at setup — and `SyncScheduler` performs the deferred
 * genesis (via its own [RealmRegistrar]) the first time relay traffic unlocks.
 */
internal class CreateRealmImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    relay: SyncRelay,
    private val registrationGate: SyncGate = SyncGate.AlwaysAllow,
) : AsyncOperationImplementation {

    private val realmRegistrar = RealmRegistrar(syncManager, cryptoManager, relay)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val recoveryPhrase: TString = params.read(CreateRealm.Params.RECOVERY_PHRASE)
        val deviceName: TString = params.read(CreateRealm.Params.DEVICE_NAME)

        // Initialize sync manager state if not already (idempotent — initialize re-derives
        // the same keys for the same phrase).
        if (!syncManager.isEnabled()) {
            syncManager.initialize(recoveryPhrase.get())
        }
        syncManager.setCurrentDeviceName(deviceName.get())

        val deviceKeyPair = syncManager.getDeviceSigningKeyPair()
        val deviceId = syncManager.currentDeviceId

        // Relay genesis (`POST /sync/realm`) — gated by [registrationGate]. With the
        // default AlwaysAllow (test/legacy, or already-subscribed) this registers
        // eagerly; in production a storage-backed gate defers it to local-only until a
        // subscription binds, at which point `SyncScheduler` runs the genesis. The
        // [RealmRegistrar] signs from `currentMasterKeyPair` (populated by `initialize`
        // above), so no recovery phrase reaches the registrar.
        val result = if (registrationGate.shouldSync()) {
            realmRegistrar.ensureRegistered()
        } else {
            null
        }

        // Push the first device's own Device entity into the synced DB
        // (docs/adr/0007-sync-device-management.md step 4). `isAuthorized = true` because the
        // device holds the master identity.
        //
        // We push UNCONDITIONALLY — even when the relay genesis was skipped (local-only,
        // no subscription) or failed (offline, LAN unreachable, etc.). The local realm
        // is consistent with the deterministic master-key derivation; the deferred
        // genesis will succeed later (or 409 because a previous attempt already
        // registered, which is semantically the same: the realm exists with our master
        // key). Without this unconditional push, a first-launch leaves the user with a
        // Sync dashboard that shows zero devices — which masks the local state being
        // fine and confuses debugging.
        val ownDevice = org.tenshu.identity.Device.create(
            context = executionContext.context,
            deviceId = deviceId,
            name = deviceName.get(),
            publicKey = deviceKeyPair.publicKey,
            isAuthorized = true,
            // Genesis: no Device tombstones yet, so currentRealmEpoch == 0L.
            admittedAtEpoch = syncManager.currentRealmEpoch,
            // F5b: carry our own A33 key, if we hold one — see PairingKeyInstaller.
            admissionPublicKey = syncManager.getAdmissionKeyPair()?.publicKey ?: ByteArray(0),
        )
        // Saving is sufficient: `initIdentityService` registers a DataObserver that
        // maintains `IdentityService.Relations.DEVICES` automatically on every Device
        // save (covers both local op writes and sync-merge incoming entities). Don't
        // also call `addToArray` here — both paths writing would produce duplicate
        // entries in the relation.
        executionContext.saveData(ownDevice)

        // Phase 4 of `docs/proposals/core-error-channel.md`: impls return T;
        // re-throw an *attempted* relay genesis failure as a TenshuException. A skipped
        // genesis (local-first, gate closed) is `null` and never throws.
        // ⚠⚠ A `409` is NOT a failure — the comment above already says so ("semantically the same:
        // the realm exists with our master key"), and now the code agrees. Something else can
        // always have registered first: the scheduler calls `ensureRegistered` as soon as pushes
        // are allowed, holding a DIFFERENT registrar instance with its own latch. Reporting that as
        // failed made genesis look broken — `BootstrapSystemTest`, 2026-08-18.
        if (result is TResult.Failure<*> && !result.isRealmAlreadyExistsFailure()) {
            throw TenshuException(result.error)
        }
        return null as T
    }
}
