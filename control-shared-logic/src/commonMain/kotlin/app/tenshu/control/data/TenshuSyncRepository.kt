package app.tenshu.control.data

import org.tenshu.core.collection.list.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskHandle
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.file.client.fileSyncDefaultPolicyOrNull
import org.tenshu.preference.client.preferenceService
import org.tenshu.core.getOrNull
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getDevices
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.fencedServices
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.clearBiometricSetup
import org.tenshu.sync.denyPairingOffer
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.cancelPairingOffer
import org.tenshu.sync.getOfferStatus
import org.tenshu.sync.offerPairing
import org.tenshu.sync.respondToHubPairing
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.sync.getUnlockStatus
import org.tenshu.sync.lockRealm
import org.tenshu.sync.setUpBiometricUnlock
import org.tenshu.sync.setUpLocalUnlock
import org.tenshu.sync.relaxToHeadless
import org.tenshu.sync.setUpUnlock
import org.tenshu.sync.unlockBiometric
import org.tenshu.sync.unlockLocal
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.awaitPairing
import org.tenshu.sync.cancelPairing
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.client.syncService
import org.tenshu.sync.createRealm
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.forgetThisDevice
import org.tenshu.sync.getRecoveryPhrase
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.recoveryClaim
import org.tenshu.sync.readmitDevice
import org.tenshu.sync.reregisterDevice
import org.tenshu.sync.revokeDevice
import org.tenshu.sync.startPairing
import org.tenshu.sync.stopPairing

class TenshuSyncRepository(
    private val client: TenshuClient
) : SyncRepository {

    override val devices: Flow<List<Device>> =
        client.identityService.devices.flow().map { result: TResult<TList<Device>> ->
            (result as? TResult.Success)?.value?.toList() ?: emptyList()
        }

    // ⚠ No `select { fields(...) }` projection: the row has exactly the two fields we read, so a
    // projection would only add an `Entity<…>` wrapper to unpick for nothing. One row per realm
    // device — a handful — makes the unprojected read the cheaper *and* simpler one.
    override suspend fun deviceLastSeen(): Map<String, Long> =
        client.identityService.deviceLastSeen
            .getAll()
            .getOrNull()
            ?.toList()
            ?.associate { it.deviceId.get() to it.lastSeenMs.get() }
            .orEmpty()

    // The relation's live flow, like [devices]; unprojected for the same reason as [deviceLastSeen].
    // A failed read maps to "no models known", never to an error — the roster renders names alone.
    override val deviceHardwareNames: Flow<Map<String, String>> =
        client.identityService.deviceHardware.flow().map { result: TResult<TList<DeviceHardware>> ->
            (result as? TResult.Success)?.value?.toList()
                ?.associate { it.deviceId.get() to it.hardwareName.get() }
                .orEmpty()
        }

    override suspend fun revokeDevice(deviceId: String): TResult<Data?> =
        client.syncService.revokeDevice(deviceId)

    override suspend fun readmitDevice(deviceId: String): TResult<Data?> =
        client.syncService.readmitDevice(deviceId)

    override suspend fun forgetThisDevice(): TResult<TString> =
        client.syncService.forgetThisDevice()

    override suspend fun getRecoveryPhrase(): TResult<TString> {
        return client.syncService.getRecoveryPhrase()
    }

    override suspend fun getSyncStatus(): TResult<SyncStatus> =
        client.syncService.getSyncStatus()

    override suspend fun getPendingPairingRequests(): TResult<TList<PairingRequest>> =
        client.syncService.getPendingPairingRequests()

    /** The proxy's live health row — a relation read, no operation (`SyncServiceExtensions`). */
    override fun syncHealth(): kotlinx.coroutines.flow.Flow<org.tenshu.sync.SyncHealthStatus?> =
        client.syncService.syncHealth()

    /** The proxy's schema pull-fence row — a relation read beside [syncHealth]. */
    override fun schemaFence(): Flow<List<String>?> =
        client.syncService.schemaFence().map { status ->
            status?.let { if (it.fenced.get()) it.fencedServices() else emptyList() }
        }

    override suspend fun startPairing(): TResult<SyncStatus> =
        client.syncService.startPairing()

    override suspend fun stopPairing(): TResult<SyncStatus> =
        client.syncService.stopPairing()

    override suspend fun approvePairingRequest(deviceId: String): TResult<Data?> =
        client.syncService.approvePairingRequest(deviceId)

    override suspend fun getDeviceId(): String =
        client.syncService.getSyncStatus().getOrNull()?.deviceId?.get() ?: ""

    override suspend fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): TResult<PairingInitiation> {
        return client.syncService.initiatePairing(
            ownerId = ownerId,
            pairingSecret = pairingSecret,
            deviceName = deviceName,
            otherDevicePublicKey = otherDevicePublicKey,
        )
    }

    override suspend fun pollPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TResult<TBoolean> {
        return client.syncService.getPairingStatus(
            pairingSecret = pairingSecret,
            otherDevicePublicKey = otherDevicePublicKey
        )
    }

    override suspend fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): TaskHandle<TBoolean> =
        client.syncService.awaitPairing(
            pairingSecret = pairingSecret,
            otherDevicePublicKey = otherDevicePublicKey
        )

    override suspend fun createRealm(
        recoveryPhrase: String,
        deviceName: String
    ): TResult<Data?> = client.syncService.createRealm(recoveryPhrase, deviceName)

    override suspend fun reregisterDevice(): TResult<Data?> =
        client.syncService.reregisterDevice()

    override suspend fun recoveryClaim(
        recoveryPhrase: String,
        deviceName: String
    ): TResult<Data?> = client.syncService.recoveryClaim(recoveryPhrase, deviceName)

    override suspend fun getUnlockStatus(): TResult<UnlockStatus> =
        client.syncService.getUnlockStatus()

    override suspend fun setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
        client.syncService.setUpLocalUnlock(passphrase)

    override suspend fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): TResult<TBoolean> =
        client.syncService.setUpUnlock(passphrase, biometricWrapKey)

    override suspend fun relaxToHeadless(): TResult<TBoolean> =
        client.syncService.relaxToHeadless()

    override suspend fun unlockLocal(passphrase: String): TResult<TBoolean> =
        client.syncService.unlockLocal(passphrase)

    override suspend fun lock(): TResult<TBoolean> =
        client.syncService.lockRealm()

    override suspend fun setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
        client.syncService.setUpBiometricUnlock(wrapKey)

    override suspend fun unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
        client.syncService.unlockBiometric(wrapKey)

    override suspend fun clearBiometricSetup(): TResult<TBoolean> =
        client.syncService.clearBiometricSetup()

    override suspend fun cancelPairing(pairingSecret: String): TResult<TBoolean> =
        client.syncService.cancelPairing(pairingSecret)

    override suspend fun respondToPairingOffer(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
        client.syncService.respondToPairingOffer(scannedUri)

    override suspend fun respondToHubPairing(scannedUri: String): TResult<PairingOfferApprovalPrompt> =
        client.syncService.respondToHubPairing(scannedUri)

    override suspend fun approvePairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        client.syncService.approvePairingOffer(pairingSecretHash)

    override suspend fun denyPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        client.syncService.denyPairingOffer(pairingSecretHash)

    override suspend fun offerPairing(deviceName: String): TResult<OfferPairingSession> =
        client.syncService.offerPairing(deviceName)

    override suspend fun getOfferStatus(pairingSecretHash: String): TResult<OfferStatus> =
        client.syncService.getOfferStatus(pairingSecretHash)

    override suspend fun cancelPairingOffer(pairingSecretHash: String): TResult<TBoolean> =
        client.syncService.cancelPairingOffer(pairingSecretHash)

    override suspend fun deviceSyncPolicy(deviceId: String): SyncPolicy? =
        client.preferenceService.fileSyncDefaultPolicyOrNull(deviceId)
}
