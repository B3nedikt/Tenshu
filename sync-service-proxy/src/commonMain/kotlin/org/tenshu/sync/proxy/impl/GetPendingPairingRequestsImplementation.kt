package org.tenshu.sync.proxy.impl

import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.messageOrNull
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.GetPendingPairingRequests
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.isRelayUnauthorized
import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.sync.proxy.security.deriveJoinPairingSas

internal class GetPendingPairingRequestsImplementation(
    private val syncRelay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : org.tenshu.core.Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, org.tenshu.core.Data>?
    ): T {
        // No realm-bound owner means there's no pairing session to poll for — return an
        // empty result instead of polling the relay under a placeholder owner (which 401s
        // and would surface as a spurious "can't reach the relay" on the host).
        val ownerId = syncManager.currentOwnerId
            ?: return emptyList<PairingRequest>().toTList(executionContext.context) as T
        val activeContext = syncManager.activePairingContext

        // Do NOT swallow a failed poll. A relay error here — most importantly a
        // 401 because this device's registration was lost when the (in-memory)
        // relay restarted under a long-lived autostarted daemon — must NOT look
        // identical to "no pending requests". Propagate it as a typed throw
        // (error-channel Shape A); the catch-all lifts it to a TResult.Failure
        // the host UI can surface, and re-registration can later key off. A
        // genuine empty result is a Success with an empty list and stays silent.
        val requests = when (val result = syncRelay.getPairingRequests(ownerId)) {
            is SyncRelay.PairingRequestsFetch.Found -> result.requests
            is SyncRelay.PairingRequestsFetch.Failed -> {
                val error = result.error
                // Tag a `401` with the op's public error constant so the host UI can tell "the relay
                // refuses this device" from "the relay is unreachable" — two states that need opposite
                // things from the user, and which the pairing banner used to conflate into
                // "Can't reach the relay — retrying…". The relay marker itself stays internal to this
                // module; the client contract is `ERROR_DEVICE_UNRECOGNIZED`.
                if (TenshuException(error).isRelayUnauthorized()) {
                    throw TenshuException(
                        GenericError.create(
                            executionContext.context,
                            "${GetPendingPairingRequests.ERROR_DEVICE_UNRECOGNIZED}: the relay does not " +
                                "recognise this device — it must be paired again. " +
                                "(${error.messageOrNull().orEmpty()})",
                        ),
                    )
                }
                throw TenshuException(error)
            }
        }

        // No session — or the session this poll was asked under ended while the relay was
        // answering (the context is read above, before the round-trip). Either way there is no
        // live context whose key a SAS could be derived from or whose pins approve would read.
        if (activeContext == null || syncManager.activePairingContext !== activeContext) {
            return emptyList<PairingRequest>().toTList(executionContext.context) as T
        }

        // F7: the relay holds only the secret's hash, so that is what this session matches on.
        val sessionHash = PairingRendezvous.joinSecretHash(activeContext.secret)
        val filtered = requests.filter { it.pairingSecretHash == sessionHash }

        // Enrich each filtered request with the §6.3 SAS so the host's
        // approval prompt can render it alongside the device-name. The
        // SAS is host-local: derived from this device's ephemeral
        // private key (held in `activePairingContext`) and the
        // requester's public key (carried on the request). Per the
        // pairing-flow-improvements §3.2 ordering, host's pubkey is
        // PublicKeyA, requester's is PublicKeyB.
        // A pairing request is **untrusted input**: `POST /sync/pairing/request` is necessarily
        // unauthenticated (an unpaired joiner has no credentials yet), the relay is untrusted by
        // construction (House Rule #1), and neither validates the X25519 key. A bad key makes the
        // SAS underivable — `calculateSharedSecret` `require`s 32 bytes, and the agreement itself
        // rejects a small-order point — and an exception here used to escape the *whole* op, so one
        // junk request (hostile, or just a broken client that scanned the same QR) turned the
        // host's entire poll into a failure and hid the legitimate joiner it was trying to pair
        // with. `MalformedPairingRequestSystemTest` pins both shapes.
        //
        // Such a request is **dropped, not shown with a null SAS**: the short authentication
        // string is the pairing ceremony's MITM defence, so surfacing a device whose SAS the user
        // *cannot* compare would invite approving an unverifiable peer. Dropping keeps every other
        // request visible and leaves nothing unverifiable on screen. Logged, because a legitimate
        // client hitting this would otherwise vanish without trace.
        //
        // ⚠⚠ Every request that leaves this op is **pinned first, and served from its pin**
        // (threat model T-P-14). `ApprovePairingRequest` carries only a `deviceId` and admits the
        // pinned keys, so what is returned here must be exactly what approve will use: one row per
        // `deviceId`, always the first one this session derived a SAS for, with the name it had
        // then. A request claiming a pinned `deviceId` with different keys is withheld — showing
        // it would put a second code on screen for an id approve can only resolve one way.
        val served = mutableSetOf<String>()
        val enriched = filtered.mapNotNull { request ->
            val deviceId = request.deviceId
            val publicKey = request.publicKey
            val signingPublicKey = request.signingPublicKey
            val pin = activeContext.shownRequest(deviceId) ?: run {
                // F7: the name crossed the relay sealed under a key derived from this session's
                // secret, bound to the claimed `deviceId` AND both keys it arrived with. A blob that
                // does not open — sealed under another secret, for another device, reposted by the
                // relay under keys of its own, or tampered with — is the same unverifiable
                // request as a key that yields no SAS, and is dropped for the same reason.
                val deviceName = runCatching {
                    PairingRendezvous.openJoinDeviceName(
                        crypto = cryptoManager,
                        pairingSecret = activeContext.secret,
                        deviceId = deviceId,
                        publicKey = publicKey,
                        signingPublicKey = signingPublicKey,
                        sealedDeviceName = request.sealedDeviceName,
                    )
                }.getOrElse { cause ->
                    println(
                        "GetPendingPairingRequests -> dropping pairing request from device " +
                            "$deviceId: its sealed name does not open under this session (${cause.message})"
                    )
                    return@mapNotNull null
                }
                val sas = runCatching {
                    val sharedSecret = cryptoManager.calculateSharedSecret(
                        privateKey = activeContext.privateKey,
                        otherPublicKey = publicKey,
                    )
                    // SAS v2 over the whole tuple being pinned — the ECDH key AND the signing
                    // key, deviceId and name — so a relay that rewrote any of them since the
                    // joiner posted shows a code the joiner's screen does not (T-P-14). No v1
                    // fallback, ever: lockstep (see `deriveJoinPairingSas`).
                    deriveJoinPairingSas(
                        sharedSecret = sharedSecret,
                        hostPublicKey = activeContext.publicKey,
                        joinerPublicKey = publicKey,
                        joinerSigningPublicKey = signingPublicKey,
                        joinerDeviceId = deviceId,
                        joinerDeviceName = deviceName,
                    )
                }.getOrElse { cause ->
                    println(
                        "GetPendingPairingRequests -> dropping pairing request from device " +
                            "$deviceId: its public key yields no SAS (${cause.message})"
                    )
                    return@mapNotNull null
                }
                activeContext.pinShownRequest(
                    SyncManager.PairingContext.ShownRequest(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        publicKey = publicKey,
                        signingPublicKey = signingPublicKey,
                        sas = sas,
                    )
                )
            }
            if (!pin.hasKeys(publicKey, signingPublicKey)) {
                println(
                    "GetPendingPairingRequests -> withholding pairing request from device " +
                        "$deviceId: this session already showed that device under different keys"
                )
                return@mapNotNull null
            }
            if (!served.add(deviceId)) return@mapNotNull null
            PairingRequest.create(
                context = executionContext.context,
                deviceId = pin.deviceId,
                deviceName = pin.deviceName,
                publicKey = pin.publicKey,
                signingPublicKey = pin.signingPublicKey,
                pairingSecretHash = request.pairingSecretHash,
                sas = pin.sas,
            )
        }

        return enriched.toTList(executionContext.context) as T
    }
}
