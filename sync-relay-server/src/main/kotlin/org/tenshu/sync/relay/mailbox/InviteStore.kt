package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [InviteRegistry] — the default + test backend
 * (`contact-invitation-handshake.md` §6). All mutations go through
 * `ConcurrentHashMap.compute`, so the single-slot `OPEN → RESPONDED`
 * transition and the cap-checked insert are atomic per key without a global
 * lock. Durable sibling: `org.tenshu.sync.relay.storage.SqliteInviteStore`.
 */
class InviteStore(
    /** §6 retention: a posted response keeps the record alive this long past `respondedAt`. */
    private val responseRetentionMs: Long = RESPONSE_RETENTION_MS,
) : InviteRegistry {

    /** One invite mailbox. Immutable — state changes replace the record. */
    data class InviteRecord(
        val inviteId: String,
        val ownerId: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val state: State,
        val sealedResponse: ByteArray? = null,
        val sealedAck: ByteArray? = null,
        val respondedAtMs: Long? = null,
    ) {
        enum class State { OPEN, RESPONDED, ACKED, REVOKED }
    }

    private val records = ConcurrentHashMap<String, InviteRecord>()

    override fun register(
        inviteId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long,
        maxOpenPerOwner: Int,
    ): InviteRegistry.RegisterResult {
        // Cap check + insert are not one atomic step across keys; a burst of
        // concurrent registers can land an owner slightly over the cap. The
        // cap is administrative anti-griefing (T-CI-05), not an invariant —
        // the relaxed check matches the SQLite backend's read-then-insert
        // under its single-writer lock.
        val openCount = records.values.count {
            it.ownerId == ownerId && it.state == InviteRecord.State.OPEN && nowMs < it.expiresAtMs
        }
        if (openCount >= maxOpenPerOwner) return InviteRegistry.RegisterResult.CAP_EXCEEDED
        val prior = records.putIfAbsent(
            inviteId,
            InviteRecord(
                inviteId = inviteId,
                ownerId = ownerId,
                createdAtMs = nowMs,
                expiresAtMs = expiresAtMs,
                state = InviteRecord.State.OPEN,
            )
        )
        return if (prior == null) {
            InviteRegistry.RegisterResult.REGISTERED
        } else {
            InviteRegistry.RegisterResult.DUPLICATE
        }
    }

    override fun revoke(inviteId: String, ownerId: String): Boolean {
        var revoked = false
        records.compute(inviteId) { _, current ->
            current ?: return@compute null
            if (current.ownerId != ownerId) return@compute current
            revoked = true
            current.copy(
                state = InviteRecord.State.REVOKED,
                sealedResponse = null,
                sealedAck = null,
            )
        }
        return revoked
    }

    override fun postResponse(
        inviteId: String,
        sealedResponse: ByteArray,
        nowMs: Long,
    ): InviteRegistry.ResponseResult {
        var result = InviteRegistry.ResponseResult.UNKNOWN
        records.compute(inviteId) { _, current ->
            current ?: return@compute null
            when {
                current.state == InviteRecord.State.REVOKED || nowMs >= current.expiresAtMs -> {
                    result = InviteRegistry.ResponseResult.GONE
                    current
                }

                current.state != InviteRecord.State.OPEN -> {
                    result = InviteRegistry.ResponseResult.ALREADY_RESPONDED
                    current
                }

                else -> {
                    result = InviteRegistry.ResponseResult.POSTED
                    current.copy(
                        state = InviteRecord.State.RESPONDED,
                        sealedResponse = sealedResponse,
                        respondedAtMs = nowMs,
                    )
                }
            }
        }
        return result
    }

    override fun pendingResponses(ownerId: String): List<InviteRegistry.PendingResponse> =
        records.values
            .filter { it.ownerId == ownerId && it.state == InviteRecord.State.RESPONDED }
            .mapNotNull { record ->
                record.sealedResponse?.let { InviteRegistry.PendingResponse(record.inviteId, it) }
            }

    override fun postAck(
        inviteId: String,
        ownerId: String,
        sealedAck: ByteArray,
    ): InviteRegistry.AckResult {
        var result = InviteRegistry.AckResult.UNKNOWN
        records.compute(inviteId) { _, current ->
            current ?: return@compute null
            when {
                // Existence-leak-proof: wrong owner reads the same as absent.
                current.ownerId != ownerId -> current

                current.state != InviteRecord.State.RESPONDED -> {
                    result = InviteRegistry.AckResult.CONFLICT
                    current
                }

                else -> {
                    result = InviteRegistry.AckResult.POSTED
                    current.copy(state = InviteRecord.State.ACKED, sealedAck = sealedAck)
                }
            }
        }
        return result
    }

    override fun getAck(inviteId: String, nowMs: Long): InviteRegistry.AckFetch {
        val record = records[inviteId] ?: return InviteRegistry.AckFetch.NotFound
        record.sealedAck?.let { return InviteRegistry.AckFetch.Found(it) }
        return when {
            record.state == InviteRecord.State.REVOKED -> InviteRegistry.AckFetch.Gone
            nowMs >= record.expiresAtMs -> InviteRegistry.AckFetch.Gone
            else -> InviteRegistry.AckFetch.NotFound
        }
    }

    override fun done(inviteId: String): Boolean = records.remove(inviteId) != null

    override fun gcSweep(nowMs: Long): Int {
        var dropped = 0
        for (record in records.values) {
            if (isCollectible(record, nowMs) && records.remove(record.inviteId, record)) {
                dropped++
            }
        }
        return dropped
    }

    private fun isCollectible(record: InviteRecord, nowMs: Long): Boolean {
        if (nowMs < record.expiresAtMs) return false
        // §6: a posted response/ack extends the record past expiry so an
        // offline party can still complete.
        val respondedAt = record.respondedAtMs ?: return true
        return nowMs >= respondedAt + responseRetentionMs
    }

    override fun recordCount(): Int = records.size

    /** Account closure (F12): every invite mailbox this owner opened. */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        return records.values.filter { it.ownerId == ownerId }.count { records.remove(it.inviteId, it) }
    }

    /** Read a record (test/diagnostics hook). */
    fun peek(inviteId: String): InviteRecord? = records[inviteId]

    companion object {
        /** §6: posted response/ack survives `max(expiresAt, respondedAt + 72 h)`. */
        const val RESPONSE_RETENTION_MS: Long = 72L * 60L * 60L * 1_000L
    }
}
