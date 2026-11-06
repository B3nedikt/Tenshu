package org.tenshu.sync.relay.mailbox

/**
 * Relay-side state for the **app↔proxy EPC rendezvous mailbox**
 * (`docs/proposals/ios-proxy-architecture.md` §5, Phase 3a): a store-and-forward
 * channel that lets an iOS app and its proxy exchange the EPC handshake + sealed
 * session frames without either holding the other's network address.
 *
 * Shape: a **session** (keyed by the HKDF `deriveEpcRendezvousSessionId`) owns two
 * directional FIFOs of opaque sealed frames — [Direction.CLIENT_TO_PROXY] and
 * [Direction.PROXY_TO_CLIENT]. The relay assigns each appended frame a
 * monotonically increasing per-(session, direction) sequence and serves them in
 * order from a caller-supplied cursor.
 *
 * Admission (the §5 gate): a session can only be [provision]ed by a **realm
 * device** under its device-key JWT, binding it to an `ownerId` and counting
 * against a per-owner cap — so an app (not a realm device) cannot create
 * unbounded sessions. Frame [append]/[fetch] are **anonymous**, gated only by
 * knowledge of the high-entropy session id (the `/sync/invite` + `/sync/grant`
 * capability posture) and requiring the session to already exist.
 *
 * Zero-trust posture (House Rule #1): every frame is opaque `EpcSession`
 * ciphertext the relay has no key for; the session id is an HKDF of a pairing
 * secret the relay never sees; the relay learns only routing metadata (the id,
 * sequence numbers, the owner-binding already visible via A6) and serves bytes
 * verbatim. Replay/reorder/tamper rejection is end-to-end in the EPC session.
 *
 * Storage SPI (`docs/proposals/sync-relay-persistence.md` pattern). Two backends:
 *   - [RendezvousStore] — in-memory; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteRendezvousStore` — durable.
 */
interface RendezvousRegistry : OwnerScopedStore {

    /** The two halves of the duplex channel. */
    enum class Direction(val wire: String) {
        CLIENT_TO_PROXY("c2p"),
        PROXY_TO_CLIENT("p2c");

        companion object {
            fun fromWire(value: String?): Direction? = entries.firstOrNull { it.wire == value }
        }
    }

    /** Outcome of `POST /sync/epc/{sessionId}` (provision a session). */
    enum class ProvisionResult {
        /** Provisioned (or refreshed by the same owner) — 204. */
        PROVISIONED,

        /** The owner is at the open-session cap — 429. */
        CAP_EXCEEDED,

        /** The session id is already owned by a different owner — 409. */
        OWNER_MISMATCH,
    }

    /** Outcome of `POST /sync/epc/{sessionId}/{direction}` (append a frame). */
    enum class AppendResult {
        /** Appended — 204. */
        APPENDED,

        /** No live session at this id (not provisioned, or expired) — 404. */
        NO_SESSION,

        /** This direction is at its frame cap — 429. */
        CAP_EXCEEDED,
    }

    /** One stored frame: the relay-assigned [seq] (≥ 1) and the opaque sealed [payload]. */
    data class Frame(val seq: Long, val payload: ByteArray)

    /** Outcome of `GET /sync/epc/{sessionId}/{direction}?since=…`. */
    sealed interface Fetch {
        /** Frames with `seq > since`, in order (possibly empty) — 200. */
        data class Frames(val frames: List<Frame>) : Fetch

        /** No live session at this id — 404. */
        data object NoSession : Fetch
    }

    /** Outcome of `DELETE /sync/epc/{sessionId}` (tear down a session). */
    enum class TeardownResult { TORN_DOWN, NOT_FOUND, OWNER_MISMATCH }

    /**
     * Provision a rendezvous session [sessionId] for realm device owner [ownerId],
     * idempotent for the same owner (refreshes [expiresAtMs]). [maxSessionsPerOwner]
     * caps an owner's live sessions — checked only when creating a new id.
     */
    fun provision(
        sessionId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxSessionsPerOwner: Int,
    ): ProvisionResult

    /**
     * Append a sealed [payload] to [direction] of [sessionId]. Requires the
     * session to exist (else [AppendResult.NO_SESSION]); [maxFramesPerDirection]
     * bounds the FIFO so a leaked id can't grow storage unboundedly.
     */
    fun append(
        sessionId: String,
        direction: Direction,
        payload: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        maxFramesPerDirection: Int,
    ): AppendResult

    /** Fetch frames of [direction] with `seq > sinceSeq`, in order. */
    fun fetch(
        sessionId: String,
        direction: Direction,
        sinceSeq: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Fetch

    /** Tear down [sessionId] and its frames; owner-scoped (the provisioning [ownerId]). */
    fun teardown(
        sessionId: String,
        ownerId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): TeardownResult

    /** Drop sessions (and their frames) past their TTL. Returns the count dropped. */
    fun gcSweep(nowMs: Long = System.currentTimeMillis()): Int

    /** Number of live sessions (test/diagnostics hook). */
    fun sessionCount(): Int
}
