package org.tenshu.sync.proxy.impl

import org.tenshu.sync.common.ED25519_PUBLIC_KEY_BYTES
import org.tenshu.sync.common.deviceIdMatchesKey
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.NotFoundError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.sync.ReadmitDevice
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistrar

/**
 * Restore a peer device's relay registration from this device's roster — see `ReadmitDevice`
 * (sync-service) for the contract and the gap it closes.
 *
 * Trust surface is unchanged: [RealmRegistrar.admitDevice] mints the same master-signed
 * delegation over `devicePublicKey || deviceId || epoch(8 BE)` that admission has always used,
 * against the same `POST /sync/admit-device` endpoint. The A1-holder running this could have
 * admitted the device by pairing anyway; this only spares the user a QR/SAS round-trip.
 */
internal class ReadmitDeviceImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val ctx = executionContext.context
        val deviceId: String = params.read<TString>(ReadmitDevice.Params.DEVICE_ID).get()
        val registrar = RealmRegistrar(syncManager, cryptoManager, relay)

        // A1 only — the delegation is master-signed. A joiner cannot rescue a peer any more than
        // it can rescue itself, and saying so is better than a relay `401` the user must decode.
        val masterKeyPair = registrar.masterIdentity()
            ?: throw TenshuException(
                GenericError.create(
                    ctx,
                    "Only a device holding the master identity can readmit another device",
                )
            )

        // ⚠ Self is `ReregisterDevice`'s job: it re-asserts genesis first, which is the step that
        // repairs a relay that lost the whole realm rather than just one device. Routing self
        // through here would silently skip it.
        if (deviceId == syncManager.currentDeviceId) {
            throw TenshuException(
                GenericError.create(
                    ctx,
                    "Use ReregisterDevice to repair this device's own registration",
                )
            )
        }

        // ⚠⚠ The row is REQUIRED here, unlike in `RevokeDevice` where it is merely descriptive:
        // `publicKey` is the thing being admitted, and only the row carries it. This also means a
        // **revoked** device cannot be readmitted — revocation deletes the row — which is the
        // property that keeps this from being a one-tap undo of a deliberate removal.
        val device = executionContext.database.getData(
            DataId.StringDataId.create(ctx, Device.ID, deviceId),
        ) as? Device
            ?: throw TenshuException(
                NotFoundError.create(
                    ctx,
                    "No Device row for $deviceId; it was removed from this realm, or has not " +
                        "reached this device yet",
                )
            )

        val devicePublicKey = device.publicKey.get()
        if (devicePublicKey.isEmpty()) {
            throw TenshuException(
                NotFoundError.create(ctx, "Device $deviceId has no public key to readmit"),
            )
        }
        // ⚠⚠ B1: A1 is about to sign a delegation over a key ANY realm device can write into a
        // `Device` row. Only an exactly-32-byte Ed25519 key is a key; anything else is refused
        // here (and `pairingDelegationMessage` would refuse it too).
        if (devicePublicKey.size != ED25519_PUBLIC_KEY_BYTES) {
            throw TenshuException(
                NotFoundError.create(ctx, "Device $deviceId's public key is not an Ed25519 key; refusing to readmit"),
            )
        }
        // ⚠⚠ Workstream B: and the row's id must be that key's id. A row naming one device's id
        // beside another's key is not a device the relay will register (it refuses the pair), and
        // A1 must not vouch for it either.
        if (!deviceIdMatchesKey(deviceId, devicePublicKey)) {
            throw TenshuException(
                NotFoundError.create(ctx, "Device $deviceId's id is not derived from its public key; refusing to readmit"),
            )
        }

        val result = registrar.admitDevice(masterKeyPair, deviceId, devicePublicKey)
        if (result is TResult.Failure<*>) throw TenshuException(result.error)
        return null as T
    }
}
