package org.tenshu.sync.proxy.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.getOrNull
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.security.PairingRendezvous

/**
 * The requester-side (Device B) pairing-completion path, factored out of
 * `GetPairingStatusImplementation` so the poll-once op and the long-running
 * `AwaitPairing` [org.tenshu.core.trait.BackgroundOperation] (Phase L2 §7.5,
 * `docs/proposals/core-background-processing.md`) share **one** copy of this
 * security-critical logic — decrypt master key → `initializeWithKeys` → ack
 * relay → self-push `Device` → seed epoch → `clearHandshake`. The only
 * difference between the two callers is poll-once vs. loop; the crypto/device
 * logic must never be duplicated.
 */
internal class PairingCompletion(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager
) {

    /**
     * One attempt to complete the handshake under [pairingSecret]. Peeks the
     * relay for the encrypted master key; if present, runs the full completion
     * and returns `true`. If the key has not arrived yet, returns `false`
     * (the caller decides poll-once vs. loop). Throws if there is no pending
     * handshake for the secret, or if the relay poll itself fails (see below).
     */
    suspend fun tryComplete(
        executionContext: ExecutionContext,
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): Boolean {
        val handshake = syncManager.getHandshake(pairingSecret)
            ?: error("No pending handshake found for secret")
        val myKeyPair = handshake.keyPair

        // F7: the relay's mailbox is keyed by the secret's hash; the secret never leaves here.
        val rendezvous = PairingRendezvous.joinSecretHash(pairingSecret)
        val response = relay.getPairingResponse(rendezvous)
        // Distinguish "the approval hasn't arrived yet" (relay returns `Success(null)`,
        // i.e. HTTP 404) from "the poll itself failed" (network / CORS / 5xx →
        // `TResult.Failure`). The prior code folded BOTH into `return false`, so any
        // transport error was indistinguishable from a still-pending handshake and the
        // requester polled on silently — on the web that renders the SAS until the
        // ~5-minute budget elapses with no surfaced cause. Propagate a genuine failure as
        // a typed throw (error-channel Shape A); the caller's poll loop decides whether to
        // tolerate a transient blip or surface it. A real 404 still returns `false` and
        // keeps polling, because the approval may still be on its way. Thrown before any
        // local state changes, so the handshake stays intact for a later retry.
        if (response is TResult.Failure<*>) throw TenshuException(response.error)
        val encryptedKey = response.getOrNull() as? TByteArray ?: return false

        // Decrypt + install the received key blob (shared with the offer flow via
        // installReceivedPairingPayload): initializeWithKeys under the host's ownerId,
        // supersede any prior realm, open the storage gate, seed the epoch, and self-push
        // this device's authorised Device row.
        installReceivedPairingPayload(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            myEphemeralPrivateKey = myKeyPair.privateKey,
            otherDevicePublicKey = otherDevicePublicKey,
            ownerId = handshake.hostOwnerId,
            encryptedBlob = encryptedKey.get(),
            deviceName = syncManager.currentDeviceName ?: "Unknown Device",
        )

        // Ack the relay so the pairing-secret state is freed (peek-only GET + explicit ack,
        // sync-relay-api.md §3.3). AFTER install so a crash between fetch and install leaves
        // the entry re-fetchable within the TTL.
        relay.ackPairingResponse(rendezvous)

        syncManager.clearHandshake(pairingSecret)
        return true
    }
}
