package org.tenshu.contact.proxy

import org.tenshu.contact.common.INVITE_RESPONSE_INFO
import org.tenshu.contact.common.InviteResponsePayload
import org.tenshu.contact.common.decodeInviteResponsePayload
import org.tenshu.core.common.util.sha256
import org.tenshu.core.security.SyncCryptoManager

/**
 * The §4-step-4 response check, shared by [InviteResponsePickup] (sync-cycle
 * arrival) and `CompleteContactHandshakeImplementation` (on-demand): unseal
 * with the inviter's A13 private key (`inviteId` bound in the AAD), decode,
 * and compare `SHA-256(payload.inviteSecret)` against the invite's stored
 * `secretHash`.
 *
 * [BadSecret] and [Garbage] both **burn** the invite (`COMPROMISED`) — a
 * response that fails the end-to-end check is evidence the link channel
 * leaked (T-CI-01/05); the difference is only whether there is a readable
 * responder key to seal the auto-reject to.
 */
internal sealed interface ResponseVerification {
    data class Ok(val payload: InviteResponsePayload) : ResponseVerification
    data class BadSecret(val payload: InviteResponsePayload) : ResponseVerification
    data object Garbage : ResponseVerification
}

internal fun verifyInviteResponse(
    cryptoManager: SyncCryptoManager,
    identityPrivateKey: ByteArray,
    inviteIdHex: String,
    secretHash: String,
    sealedResponse: ByteArray,
): ResponseVerification {
    val payload = try {
        decodeInviteResponsePayload(
            cryptoManager.unsealFromIdentity(
                sealed = sealedResponse,
                myEncPrivateKey = identityPrivateKey,
                info = INVITE_RESPONSE_INFO,
                aad = inviteIdHex.hexToByteArray(),
            )
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        return ResponseVerification.Garbage
    }
    return if (sha256(payload.inviteSecret).toHexString() == secretHash) {
        ResponseVerification.Ok(payload)
    } else {
        ResponseVerification.BadSecret(payload)
    }
}
