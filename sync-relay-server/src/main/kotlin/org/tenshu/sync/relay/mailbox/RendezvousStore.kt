package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [RendezvousRegistry] — the default + test backend
 * (`ios-proxy-architecture.md` §5, Phase 3a). Each session holds two directional
 * frame logs guarded by the session's own lock, so append (sequence assignment)
 * and fetch are race-free. Durable sibling:
 * `org.tenshu.sync.relay.storage.SqliteRendezvousStore`.
 */
class RendezvousStore : RendezvousRegistry {

    private class Session(
        val ownerId: String,
        @Volatile var expiresAtMs: Long,
    ) {
        val lock = Any()
        // Per-direction append-ordered logs; index + 1 is the frame's sequence.
        val c2p = ArrayList<RendezvousRegistry.Frame>()
        val p2c = ArrayList<RendezvousRegistry.Frame>()

        fun log(direction: RendezvousRegistry.Direction) =
            if (direction == RendezvousRegistry.Direction.CLIENT_TO_PROXY) c2p else p2c
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun provision(
        sessionId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long,
        maxSessionsPerOwner: Int,
    ): RendezvousRegistry.ProvisionResult {
        val existing = sessions[sessionId]
        if (existing != null && nowMs < existing.expiresAtMs) {
            if (existing.ownerId != ownerId) return RendezvousRegistry.ProvisionResult.OWNER_MISMATCH
            existing.expiresAtMs = expiresAtMs // idempotent refresh by the same owner
            return RendezvousRegistry.ProvisionResult.PROVISIONED
        }
        // New (or replacing an expired) id: enforce the per-owner cap on live sessions.
        val openCount = sessions.values.count { it.ownerId == ownerId && nowMs < it.expiresAtMs }
        if (openCount >= maxSessionsPerOwner) return RendezvousRegistry.ProvisionResult.CAP_EXCEEDED
        sessions[sessionId] = Session(ownerId, expiresAtMs)
        return RendezvousRegistry.ProvisionResult.PROVISIONED
    }

    override fun append(
        sessionId: String,
        direction: RendezvousRegistry.Direction,
        payload: ByteArray,
        nowMs: Long,
        maxFramesPerDirection: Int,
    ): RendezvousRegistry.AppendResult {
        val session = liveSession(sessionId, nowMs) ?: return RendezvousRegistry.AppendResult.NO_SESSION
        synchronized(session.lock) {
            // Re-check liveness under the lock (a concurrent GC could have dropped it).
            if (nowMs >= session.expiresAtMs) return RendezvousRegistry.AppendResult.NO_SESSION
            val log = session.log(direction)
            if (log.size >= maxFramesPerDirection) return RendezvousRegistry.AppendResult.CAP_EXCEEDED
            log.add(RendezvousRegistry.Frame(seq = log.size + 1L, payload = payload))
            return RendezvousRegistry.AppendResult.APPENDED
        }
    }

    override fun fetch(
        sessionId: String,
        direction: RendezvousRegistry.Direction,
        sinceSeq: Long,
        nowMs: Long,
    ): RendezvousRegistry.Fetch {
        val session = liveSession(sessionId, nowMs) ?: return RendezvousRegistry.Fetch.NoSession
        synchronized(session.lock) {
            if (nowMs >= session.expiresAtMs) return RendezvousRegistry.Fetch.NoSession
            val frames = session.log(direction).filter { it.seq > sinceSeq }
            return RendezvousRegistry.Fetch.Frames(frames)
        }
    }

    override fun teardown(
        sessionId: String,
        ownerId: String,
        nowMs: Long,
    ): RendezvousRegistry.TeardownResult {
        val session = sessions[sessionId] ?: return RendezvousRegistry.TeardownResult.NOT_FOUND
        if (session.ownerId != ownerId) return RendezvousRegistry.TeardownResult.OWNER_MISMATCH
        sessions.remove(sessionId, session)
        return RendezvousRegistry.TeardownResult.TORN_DOWN
    }

    override fun gcSweep(nowMs: Long): Int {
        var dropped = 0
        for ((id, session) in sessions) {
            if (nowMs >= session.expiresAtMs && sessions.remove(id, session)) dropped++
        }
        return dropped
    }

    override fun sessionCount(): Int = sessions.size

    /** Account closure (F12): the owner's sessions, frames included. Counts session + frame rows. */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        var dropped = 0
        for ((id, session) in sessions) {
            if (session.ownerId == ownerId && sessions.remove(id, session)) {
                dropped += 1 + synchronized(session.lock) { session.c2p.size + session.p2c.size }
            }
        }
        return dropped
    }

    private fun liveSession(sessionId: String, nowMs: Long): Session? =
        sessions[sessionId]?.takeIf { nowMs < it.expiresAtMs }
}
