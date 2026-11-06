package org.tenshu.sync.relay.auth

import org.tenshu.core.common.security.AccountClosureClaims
import org.tenshu.core.common.security.decodeAccountClosureToken
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.sync.relay.RelayConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Verifies a commerce-signed **account-closure record** (`retention-quota-model.md` Face A / D4).
 *
 * A deliberate sibling of [RelayEntitlementVerifier] rather than a method on it: that class's
 * contract is that the `X-Entitlement` header has exactly one interpretation, and folding a
 * *delete-authorising* credential into the same object would blur which credential authorises
 * what. They share the issuer key and nothing else.
 *
 * ⚠ Returns `null` for **every** failure — bad signature, wrong algorithm, expired, malformed,
 * missing owner. A caller cannot accidentally treat a rejected record as a weak yes, and the
 * relay reveals nothing about *why* a record failed.
 */
class RelayAccountClosureVerifier(
    private val ed25519: Ed25519Backend,
    private val issuerPublicKey: ByteArray,
    private val expectedKeyId: String?,
    private val nowSec: () -> Long,
) {

    /** The verified claims, or `null` if the record is not currently valid for any reason. */
    fun verify(token: String): AccountClosureClaims? = runCatching {
        decodeAccountClosureToken(
            token = token,
            currentTimeSec = nowSec(),
            verifier = { keyId, message, signature ->
                // ⚠ Key-id pinning happens here, before the signature check is trusted: an
                // unexpected `kid` is a hard no, not a fall-through to the default key.
                keyId == expectedKeyId && ed25519.verify(issuerPublicKey, message, signature)
            },
        )
    }.getOrNull()

    companion object {
        /**
         * Built only when an issuer key is configured — with no key there is nothing to verify a
         * closure record against, so the relay must refuse rather than improvise.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromConfig(
            config: RelayConfig,
            ed25519: Ed25519Backend,
            nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
        ): RelayAccountClosureVerifier? {
            val b64 = config.entitlementIssuerPublicKeyB64 ?: return null
            return RelayAccountClosureVerifier(
                ed25519 = ed25519,
                issuerPublicKey = Base64.Default.decode(b64.trim()),
                expectedKeyId = config.entitlementIssuerKeyId,
                nowSec = nowSec,
            )
        }
    }
}
