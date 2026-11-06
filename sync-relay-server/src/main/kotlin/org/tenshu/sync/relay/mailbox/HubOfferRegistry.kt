package org.tenshu.sync.relay.mailbox

/**
 * Relay-side state for the **headless hub pairing rendezvous** (hub proposal §11.3, H2):
 * the outbound sibling of [PairingOfferRegistry]. A hub — headless, unpaired,
 * credential-less — POSTs its **signed ephemeral offer** keyed by
 * `pairingSecretHash = SHA-256(printedSecret)` (hex); the phone that scanned the hub's
 * printed label GETs (and thereby **consumes**) it. The return leg then rides the
 * existing offer-response mailbox unchanged, under the session-scoped hash
 * `SHA-256(secret || ephemeralPub)`.
 *
 * Semantics differ from [PairingOfferRegistry] deliberately, because the printed secret
 * is **long-lived** while an offer QR's secret is per-session:
 *  - a re-post under the same hash **replaces** the previous offer (re-arming after a
 *    timeout/denial, or a hub restart, must not brick the label for 24 h);
 *  - a fetch **consumes** the offer (single-fetch, §11.4 — a consumed hash closes the
 *    arming window until the hub re-arms with a fresh ephemeral key).
 *
 * Zero-trust posture (House Rule #1): the stored record is public material only — an
 * ephemeral X25519 public key, a device id, the device name **sealed** under a key derived
 * from the printed secret (F7 — the relay holds only the secret's hash, so it cannot open it),
 * and an Ed25519 signature the relay cannot even verify (the verify key lives on the printed label, phone-side). Both POST
 * and GET are unauthenticated by design: the hub has no relay credentials yet, the phone
 * has no relation to the hub yet; the secret hash is the rendezvous capability and the
 * signature (verified phone-side against the printed key) is the integrity anchor.
 *
 * ⚠ **Accepted residual (threat model T-P-17):** the printed secret is long-lived, so its hash
 * is a stable identifier — the relay can link every re-arm of one hub (and the IPs it arms
 * from) across its lifetime. Inherent to a printed label; sealing the name removes the label
 * text, not the linkability.
 *
 * In-memory only for the H2 prototype (a ≤7-minute arming rendezvous; a relay restart
 * mid-window just means re-arming the hub). A durable SQLite sibling can follow the
 * `SqlitePairingOfferStore` pattern if hub pairing ever needs to survive restarts.
 */
interface HubOfferRegistry {

    /** The hub's signed ephemeral offer — public material + signature only. */
    class HubOffer(
        val ephemeralPublicKey: ByteArray,
        val deviceId: String,
        val sealedDeviceName: ByteArray,
        val signature: ByteArray,
    )

    /** Outcome of `POST /sync/pairing/hub-offer/fetch`. */
    sealed interface Fetch {
        /** The live offer — 200. Consumed by this fetch. */
        data class Found(val offer: HubOffer) : Fetch

        /** Nothing live at this hash (never posted, expired, or already consumed) — 404. */
        data object NotFound : Fetch
    }

    /**
     * Store (or replace — re-arming) the offer for [pairingSecretHash]. [expiresAtMs]
     * bounds serving (the hub's arming window + grace).
     */
    fun post(
        pairingSecretHash: String,
        offer: HubOffer,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    )

    /** Phone fetch. [Fetch.Found] only while live — and the entry is consumed by it. */
    fun consume(pairingSecretHash: String, nowMs: Long = System.currentTimeMillis()): Fetch

    /** Drop expired entries. Returns the count dropped. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int
}
