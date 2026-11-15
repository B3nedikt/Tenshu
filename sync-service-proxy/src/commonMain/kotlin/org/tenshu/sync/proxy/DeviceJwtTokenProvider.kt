package org.tenshu.sync.proxy

import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager

/**
 * The personal realm's relay JWT, signed by this device's A4 key — one token per call.
 *
 * ⚠ The id in the token and the key that signs it come from **one**
 * [SyncManager.currentDeviceIdentity] snapshot, never two reads: "Forget this device" replaces
 * the pair in a running process (threat model A4, *lifecycle*), and a token naming one identity
 * signed by the other is refused by the relay as a 401. Null before a realm exists.
 */
fun deviceJwtTokenProvider(
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    nowEpochSec: () -> Long,
    /** The realm the token is for: the personal one by default, a shared realm's own owner id otherwise. */
    ownerIdProvider: () -> String? = { syncManager.currentOwnerId },
): suspend () -> String? = provider@{
    val ownerId = ownerIdProvider() ?: return@provider null
    val identity = syncManager.currentDeviceIdentity()
    jwtTokenProvider(
        ownerIdProvider = { ownerId },
        deviceIdProvider = { identity.deviceId },
        signer = { message -> cryptoManager.signEd25519(privateKey = identity.signingKeyPair.privateKey, message = message) },
        nowEpochSec = nowEpochSec,
    )()
}
