package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.RendezvousRegistry

/**
 * Durable [RendezvousRegistry] backed by [RelayDatabase] (SQLite) —
 * `ios-proxy-architecture.md` §5 Phase 3a, the `sync-relay-persistence.md`
 * pattern. The rendezvous mailbox is short-lived (minutes-scale TTL) but
 * persisting it means a relay restart mid-pairing doesn't strand the two ends.
 * Every column is routing metadata or opaque sealed ciphertext (House Rule #1).
 *
 * Read-then-write sequences (sequence assignment, cap checks) run inside
 * [RelayDatabase.withConnection]'s single-writer lock, so they are race-free
 * without explicit transactions (the [SqliteGrantStore] precedent).
 */
class SqliteRendezvousStore(private val db: RelayDatabase) : RendezvousRegistry {

    override fun provision(
        sessionId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long,
        maxSessionsPerOwner: Int,
    ): RendezvousRegistry.ProvisionResult = db.withConnection { conn ->
        val existingOwner = conn.prepareStatement(
            "SELECT owner_id FROM epc_session WHERE session_id = ? AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (existingOwner != null) {
            if (existingOwner != ownerId) return@withConnection RendezvousRegistry.ProvisionResult.OWNER_MISMATCH
            conn.prepareStatement("UPDATE epc_session SET expires_at = ? WHERE session_id = ?").use { ps ->
                ps.setLong(1, expiresAtMs)
                ps.setString(2, sessionId)
                ps.executeUpdate()
            }
            return@withConnection RendezvousRegistry.ProvisionResult.PROVISIONED
        }
        val openCount = conn.prepareStatement(
            "SELECT COUNT(*) FROM epc_session WHERE owner_id = ? AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (openCount >= maxSessionsPerOwner) return@withConnection RendezvousRegistry.ProvisionResult.CAP_EXCEEDED
        // Replaces any expired row at the same id (and its now-stale frames cascade below).
        conn.prepareStatement("DELETE FROM epc_session WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM epc_frame WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO epc_session(session_id, owner_id, expires_at) VALUES(?, ?, ?)"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, ownerId)
            ps.setLong(3, expiresAtMs)
            ps.executeUpdate()
        }
        RendezvousRegistry.ProvisionResult.PROVISIONED
    }

    override fun append(
        sessionId: String,
        direction: RendezvousRegistry.Direction,
        payload: ByteArray,
        nowMs: Long,
        maxFramesPerDirection: Int,
    ): RendezvousRegistry.AppendResult = db.withConnection { conn ->
        val live = conn.prepareStatement(
            "SELECT 1 FROM epc_session WHERE session_id = ? AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (!live) return@withConnection RendezvousRegistry.AppendResult.NO_SESSION
        val maxSeq = conn.prepareStatement(
            "SELECT COALESCE(MAX(seq), 0) FROM epc_frame WHERE session_id = ? AND direction = ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, direction.wire)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        if (maxSeq >= maxFramesPerDirection) return@withConnection RendezvousRegistry.AppendResult.CAP_EXCEEDED
        conn.prepareStatement(
            "INSERT INTO epc_frame(session_id, direction, seq, payload) VALUES(?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, direction.wire)
            ps.setLong(3, maxSeq + 1)
            ps.setBytes(4, payload)
            ps.executeUpdate()
        }
        RendezvousRegistry.AppendResult.APPENDED
    }

    override fun fetch(
        sessionId: String,
        direction: RendezvousRegistry.Direction,
        sinceSeq: Long,
        nowMs: Long,
    ): RendezvousRegistry.Fetch = db.withConnection { conn ->
        val live = conn.prepareStatement(
            "SELECT 1 FROM epc_session WHERE session_id = ? AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (!live) return@withConnection RendezvousRegistry.Fetch.NoSession
        val frames = conn.prepareStatement(
            "SELECT seq, payload FROM epc_frame WHERE session_id = ? AND direction = ? AND seq > ? ORDER BY seq ASC"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, direction.wire)
            ps.setLong(3, sinceSeq)
            ps.executeQuery().use { rs ->
                val out = ArrayList<RendezvousRegistry.Frame>()
                while (rs.next()) out.add(RendezvousRegistry.Frame(rs.getLong(1), rs.getBytes(2)))
                out
            }
        }
        RendezvousRegistry.Fetch.Frames(frames)
    }

    override fun teardown(
        sessionId: String,
        ownerId: String,
        nowMs: Long,
    ): RendezvousRegistry.TeardownResult = db.withConnection { conn ->
        val owner = conn.prepareStatement("SELECT owner_id FROM epc_session WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@withConnection RendezvousRegistry.TeardownResult.NOT_FOUND
        if (owner != ownerId) return@withConnection RendezvousRegistry.TeardownResult.OWNER_MISMATCH
        conn.prepareStatement("DELETE FROM epc_frame WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM epc_session WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
        RendezvousRegistry.TeardownResult.TORN_DOWN
    }

    override fun gcSweep(nowMs: Long): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "DELETE FROM epc_frame WHERE session_id IN (SELECT session_id FROM epc_session WHERE expires_at <= ?)"
        ).use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM epc_session WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
    }

    override fun sessionCount(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM epc_session").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /**
     * Account closure (F12): the owner's sessions and their frames. ⚠ Frames first — `epc_frame` has
     * no owner column and is reached only through its session, so deleting the sessions first would
     * strand the frames with nothing left to name them.
     */
    override fun eraseOwner(ownerId: String): Int {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        return db.withConnection { conn ->
            val frames = conn.prepareStatement(
                "DELETE FROM epc_frame WHERE session_id IN (SELECT session_id FROM epc_session WHERE owner_id = ?)"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeUpdate()
            }
            frames + db.deleteOwnerRows(ownerId, "epc_session" to "owner_id")
        }
    }
}
