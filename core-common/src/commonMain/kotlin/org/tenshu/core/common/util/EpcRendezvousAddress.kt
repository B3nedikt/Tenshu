package org.tenshu.core.common.util

/**
 * Derives the **EPC rendezvous session id** that keys the relay's `/sync/epc`
 * mailbox (`docs/proposals/ios-proxy-architecture.md` §5, Phase 3).
 *
 * ```
 * sessionId = HKDF-SHA-256(ikm = pairingSecret,
 *                          salt = empty, info = "tenshu/epc/rendezvous/v1", length = 16)
 * ```
 *
 * Unlike [deriveGrantAddress] (which binds the two A13 keys), this depends on the
 * out-of-band **pairing secret only** — the seed the proxy shows as QR/link and
 * the app scans. That seed is the *one* thing both ends hold *before* the EPC
 * handshake runs (the X25519 ephemeral exchange happens **over** this mailbox, so
 * its keys can't seed the address). Both ends derive the same id without a
 * directory or either knowing the other's network location.
 *
 * Knowledge of the id is the capability to use the mailbox (the `/sync/invite` +
 * `/sync/grant` anonymous-pickup posture). It does **not** authenticate the
 * channel — the handshake SAS does (a relay that swaps handshake frames yields a
 * divergent SAS). The pairing secret never reaches the relay in any form.
 *
 * Returns the raw 16-byte id; callers hex-encode it for the relay route
 * (`/sync/epc/{sessionId}`).
 */
fun deriveEpcRendezvousSessionId(pairingSecret: ByteArray): ByteArray =
    hkdfSha256(
        ikm = pairingSecret,
        salt = ByteArray(0),
        info = EPC_RENDEZVOUS_INFO,
        length = EPC_RENDEZVOUS_SESSION_ID_LENGTH,
    )

/** Raw byte length of an EPC rendezvous session id — matches the 16-byte grant address. */
const val EPC_RENDEZVOUS_SESSION_ID_LENGTH = 16

private val EPC_RENDEZVOUS_INFO: ByteArray = "tenshu/epc/rendezvous/v1".encodeToByteArray()
