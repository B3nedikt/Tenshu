package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.sync.proxy.security.deriveJoinPairingSas

internal class InitiatePairingImplementation(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val ownerId = (params?.get(InitiatePairing.Params.OWNER_ID) as TString).get()
        val pairingSecret = (params[InitiatePairing.Params.PAIRING_SECRET] as TString).get()
        val requestedDeviceName = (params[InitiatePairing.Params.DEVICE_NAME] as TString).get()
        val otherDevicePublicKey =
            (params[InitiatePairing.Params.OTHER_DEVICE_PUBLIC_KEY] as TByteArray).get()

        // 1. Our ephemeral key pair — REUSED while a handshake for this secret is live.
        //
        // ⚠⚠ Minting a fresh one on a second initiate strands the pairing, and does it in the most
        // confusing way available. The host may already have approved and posted the master blob
        // encrypted to the FIRST key; the relay's mailbox is `Map<pairingSecret, blob>` and drops
        // that entry **only on a successful ack**, so it stays. Meanwhile the SAS on the joiner's
        // screen is a snapshot of the first initiate. Re-minting therefore produces: both screens
        // showing the same code, the user approving in good faith, and a decrypt that fails its
        // MAC check — identically on every retry, because the stale blob never clears.
        //
        // ⚠ This is the joiner's half of a rule the host has had all along:
        // `SyncManagerImpl.generatePairingContext` is idempotent while a context is live, for
        // exactly the symmetric reason ("a non-null context reliably means a handshake the joiner
        // may be mid-way through; reuse it"). A genuinely new session starts after the handshake is
        // cleared — cancel, completion, or `stopPairing`.
        //
        // ⚠ No key-lifetime cost: the key still lives in memory only, still dies with the
        // handshake (asset A8), and reuse is confined to one session under one secret.
        val myKeyPair = syncManager.getHandshake(pairingSecret)?.keyPair
            ?: cryptoManager.generateKeyPair()

        // 2. Compute the SAS per sync-pairing-flow-improvements.md §3.2.
        // The host derives the same value from its private key + this
        // device's public key (via ApprovePairingRequest); the user
        // verifies the two screens match before approving. Done at
        // initiation time (rather than during status polling) because
        // (a) all inputs are available now, (b) the UI displays the SAS
        // immediately on §4a.4 without an extra round-trip.
        // ⚠⚠ SAS v2 (threat model T-P-14): the transcript covers exactly what step 4 posts — this
        // device's signing key, id and name — so a relay that rewrites any of them before the
        // host's first poll changes the host's code and not this one. These three values are the
        // ones posted below; compute and post from the same vals.
        val signingPublicKey = syncManager.getDeviceSigningKeyPair().publicKey
        val myDeviceId = syncManager.currentDeviceId
        // ⚠ Fit the name to the relay's sealed-name cap FIRST (a long, emoji-heavy platform name
        // would otherwise be refused with an opaque 400): the SAS, the stored name and the sealed
        // copy must all be this one string, or the host's code would not match.
        val deviceName = PairingRendezvous.fitJoinDeviceName(
            pairingSecret = pairingSecret,
            deviceId = myDeviceId,
            publicKey = myKeyPair.publicKey,
            signingPublicKey = signingPublicKey,
            deviceName = requestedDeviceName,
        )
        val sharedSecret = cryptoManager.calculateSharedSecret(
            myKeyPair.privateKey, otherDevicePublicKey,
        )
        val sas = deriveJoinPairingSas(
            sharedSecret = sharedSecret,
            hostPublicKey = otherDevicePublicKey,
            joinerPublicKey = myKeyPair.publicKey,
            joinerSigningPublicKey = signingPublicKey,
            joinerDeviceId = myDeviceId,
            joinerDeviceName = deviceName,
        )

        // 3. Store the keypair for status polling, and remember the device name so we can
        // populate the Device entity we'll push after the handshake completes
        // (docs/adr/0007-sync-device-management.md step 5).
        // Stash the host realm's ownerId alongside the keypair so `GetPairingStatus`
        // (which reads it back on success) can install it as `currentOwnerId` via
        // `initializeWithKeys(keys, ownerId)` — without that plumbing, this device
        // would issue JWTs whose `iss` claim doesn't match the relay's
        // `KnownDevicesStore` entry, and every subsequent push would 401.
        syncManager.startHandshake(pairingSecret, myKeyPair, hostOwnerId = ownerId)
        syncManager.setCurrentDeviceName(deviceName)

        // 4. Post the pairing request to the relay. Per docs/adr/0007-sync-device-management.md,
        // Device B's public-key registration on the relay happens later via Device A's
        // ApprovePairingRequest (master delegation in POST /sync/pairing/response); the
        // local Device entity is self-pushed by GetPairingStatus once the master key
        // arrives. The legacy `PairDevice` op (which used to POST the now-deleted
        // `/identity/pair-device` endpoint) was a transitional bridge that's been retired.
        //
        // Two pubkeys travel with the request: `publicKey` is the X25519 ECDH key for
        // SAS derivation (host's `GetPendingPairingRequestsImplementation` pairs it with
        // its own private half); `signingPublicKey` is the persistent Ed25519 device
        // signing key (asset A4) the host signs the master delegation over and the relay
        // registers in `KnownDevicesStore`. Conflating the two registers the wrong key
        // and breaks JWT verification on every later push from this device.
        //
        // ⚠⚠ F7: the relay gets the secret's domain-separated HASH and the name SEALED under a key
        // derived from the secret — never either in clear. The host holds the secret (it minted
        // the QR) and unseals; the relay, holding only the hash, cannot. The SAS above is still
        // over the clear name, so the host's code matches only if it unsealed this exact name.
        val relayResult = relay.postPairingRequest(
            ownerId = ownerId,
            pairingSecretHash = PairingRendezvous.joinSecretHash(pairingSecret),
            deviceId = myDeviceId,
            sealedDeviceName = PairingRendezvous.sealJoinDeviceName(
                crypto = cryptoManager,
                pairingSecret = pairingSecret,
                deviceId = myDeviceId,
                publicKey = myKeyPair.publicKey,
                signingPublicKey = signingPublicKey,
                deviceName = deviceName,
            ),
            publicKey = myKeyPair.publicKey,
            signingPublicKey = signingPublicKey,
        )

        if (relayResult is TResult.Failure) {
            syncManager.clearHandshake(pairingSecret)
            throw TenshuException(relayResult.error)
        }

        return PairingInitiation.create(
            context = executionContext.context,
            publicKey = myKeyPair.publicKey,
            sas = sas,
        ) as T
    }
}
