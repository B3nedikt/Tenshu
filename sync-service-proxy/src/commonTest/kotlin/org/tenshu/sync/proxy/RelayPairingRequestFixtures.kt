package org.tenshu.sync.proxy

import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.security.PairingRendezvous

/**
 * A join-flow request **as the relay serves it** after F7: the session secret's hash and the name
 * sealed under it, exactly what `InitiatePairingImplementation` posts. Test fakes of
 * [SyncRelay.getPairingRequests] return these, so the host path under test unseals for real.
 */
internal fun sealedRelayRequest(
    crypto: SyncCryptoManager,
    deviceId: String,
    secret: String,
    deviceName: String = "Tablet",
    publicKey: ByteArray = ByteArray(32) { 0x01 },
    // B1: a delegation is only ever over a 32-byte Ed25519 key, so the fixture's is one.
    signingPublicKey: ByteArray = FIXTURE_JOINER_SIGNING_KEY,
): SyncRelay.RelayPairingRequest = SyncRelay.RelayPairingRequest(
    deviceId = deviceId,
    sealedDeviceName = PairingRendezvous.sealJoinDeviceName(crypto, secret, deviceId, publicKey, signingPublicKey, deviceName),
    publicKey = publicKey,
    signingPublicKey = signingPublicKey,
    pairingSecretHash = PairingRendezvous.joinSecretHash(secret),
)

/** The fixture joiner's A4 key (B1: a delegation is only ever over a 32-byte Ed25519 key). */
internal val FIXTURE_JOINER_SIGNING_KEY: ByteArray = ByteArray(32) { 0x02 }

/** Workstream B: the fixture joiner's id is its key's — the only id an admitter will sign over. */
internal val FIXTURE_JOINER_ID: String = org.tenshu.sync.common.deviceIdForPublicKey(FIXTURE_JOINER_SIGNING_KEY)

/** A distinct 32-byte key per [label], and its derived id — for fixtures with more than one joiner. */
internal fun fixtureDeviceKey(label: String): ByteArray = org.tenshu.core.common.util.sha256(label.encodeToByteArray())
internal fun fixtureDeviceId(label: String): String = org.tenshu.sync.common.deviceIdForPublicKey(fixtureDeviceKey(label))
