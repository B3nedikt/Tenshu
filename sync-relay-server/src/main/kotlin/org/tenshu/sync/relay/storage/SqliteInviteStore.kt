package org.tenshu.sync.relay.storage

import java.sql.Connection
import java.sql.ResultSet
import org.tenshu.sync.relay.mailbox.InviteRegistry
import org.tenshu.sync.relay.mailbox.InviteStore

/**
 * Durable [InviteRegistry] backed by [RelayDatabase] (SQLite) —
 * `contact-invitation-handshake.md` §6, the `sync-relay-persistence.md`
 * pattern. The mailbox is ephemeral by nature (≤ 7-day TTL) but persisting it
 * is harmless: every stored column is routing metadata or sealed ciphertext
 * (House Rule #1), and durability means a relay restart mid-handshake doesn't
 * strand an invitee waiting on an ack that evaporated.
 *
 * All read-then-write sequences run inside [RelayDatabase.withConnection]'s
 * single-writer lock, so the single-slot state machine is race-free without
 * explicit transactions.
 */
class SqliteInviteStore(
    private val db: RelayDatabase,
    private val responseRetentionMs: Long = InviteStore.RESPONSE_RETENTION_MS,
) : InviteRegistry {

    override fun register(
        inviteId: String,
        ownerId: String,
        expiresAtMs: Long,
        nowMs: Long,
        maxOpenPerOwner: Int,
    ): InviteRegistry.RegisterResult = db.withConnection { conn ->
        val existing = stateOf(conn, inviteId)
        if (existing != null) return@withConnection InviteRegistry.RegisterResult.DUPLICATE
        val openCount = conn.prepareStatement(
            "SELECT COUNT(*) FROM invite WHERE owner_id = ? AND state = 'OPEN' AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (openCount >= maxOpenPerOwner) {
            return@withConnection InviteRegistry.RegisterResult.CAP_EXCEEDED
        }
        conn.prepareStatement(
            "INSERT INTO invite(invite_id, owner_id, created_at, expires_at, state) VALUES(?, ?, ?, ?, 'OPEN')"
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, ownerId)
            ps.setLong(3, nowMs)
            ps.setLong(4, expiresAtMs)
            ps.executeUpdate()
        }
        InviteRegistry.RegisterResult.REGISTERED
    }

    override fun revoke(inviteId: String, ownerId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement(
            """
            UPDATE invite SET state = 'REVOKED', sealed_response = NULL, sealed_ack = NULL
            WHERE invite_id = ? AND owner_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.setString(2, ownerId)
            ps.executeUpdate() > 0
        }
    }

    override fun postResponse(
        inviteId: String,
        sealedResponse: ByteArray,
        nowMs: Long,
    ): InviteRegistry.ResponseResult = db.withConnection { conn ->
        val row = readRow(conn, inviteId)
            ?: return@withConnection InviteRegistry.ResponseResult.UNKNOWN
        when {
            row.state == "REVOKED" || nowMs >= row.expiresAtMs ->
                InviteRegistry.ResponseResult.GONE

            row.state != "OPEN" -> InviteRegistry.ResponseResult.ALREADY_RESPONDED

            else -> {
                conn.prepareStatement(
                    """
                    UPDATE invite SET state = 'RESPONDED', sealed_response = ?, responded_at = ?
                    WHERE invite_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setBytes(1, sealedResponse)
                    ps.setLong(2, nowMs)
                    ps.setString(3, inviteId)
                    ps.executeUpdate()
                }
                InviteRegistry.ResponseResult.POSTED
            }
        }
    }

    override fun pendingResponses(ownerId: String): List<InviteRegistry.PendingResponse> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT invite_id, sealed_response FROM invite WHERE owner_id = ? AND state = 'RESPONDED'"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val blob = rs.getBytes(2) ?: continue
                            add(InviteRegistry.PendingResponse(rs.getString(1), blob))
                        }
                    }
                }
            }
        }

    override fun postAck(
        inviteId: String,
        ownerId: String,
        sealedAck: ByteArray,
    ): InviteRegistry.AckResult = db.withConnection { conn ->
        val row = readRow(conn, inviteId)
        if (row == null || row.ownerId != ownerId) {
            // Existence-leak-proof: wrong owner reads the same as absent.
            return@withConnection InviteRegistry.AckResult.UNKNOWN
        }
        if (row.state != "RESPONDED") return@withConnection InviteRegistry.AckResult.CONFLICT
        conn.prepareStatement(
            "UPDATE invite SET state = 'ACKED', sealed_ack = ? WHERE invite_id = ?"
        ).use { ps ->
            ps.setBytes(1, sealedAck)
            ps.setString(2, inviteId)
            ps.executeUpdate()
        }
        InviteRegistry.AckResult.POSTED
    }

    override fun getAck(inviteId: String, nowMs: Long): InviteRegistry.AckFetch =
        db.withConnection { conn ->
            val row = readRow(conn, inviteId)
                ?: return@withConnection InviteRegistry.AckFetch.NotFound
            row.sealedAck?.let { return@withConnection InviteRegistry.AckFetch.Found(it) }
            when {
                row.state == "REVOKED" -> InviteRegistry.AckFetch.Gone
                nowMs >= row.expiresAtMs -> InviteRegistry.AckFetch.Gone
                else -> InviteRegistry.AckFetch.NotFound
            }
        }

    override fun done(inviteId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM invite WHERE invite_id = ?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeUpdate() > 0
        }
    }

    override fun gcSweep(nowMs: Long): Int = db.withConnection { conn ->
        // §6 retention: past expiry AND (never responded, or past the
        // post-response grace window). Shares the rule with the in-memory
        // backend via the same predicate shape, expressed in SQL.
        conn.prepareStatement(
            """
            DELETE FROM invite WHERE expires_at <= ?
              AND (responded_at IS NULL OR responded_at + ? <= ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, nowMs)
            ps.setLong(2, responseRetentionMs)
            ps.setLong(3, nowMs)
            ps.executeUpdate()
        }
    }

    override fun recordCount(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM invite").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** Account closure (F12): every invite mailbox this owner opened, with its sealed blobs. */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "invite" to "owner_id")

    private data class Row(
        val ownerId: String,
        val expiresAtMs: Long,
        val state: String,
        val sealedAck: ByteArray?,
    )

    private fun stateOf(conn: Connection, inviteId: String): String? =
        conn.prepareStatement("SELECT state FROM invite WHERE invite_id = ?").use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun readRow(conn: Connection, inviteId: String): Row? =
        conn.prepareStatement(
            "SELECT owner_id, expires_at, state, sealed_ack FROM invite WHERE invite_id = ?"
        ).use { ps ->
            ps.setString(1, inviteId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }

    private fun ResultSet.toRow() = Row(
        ownerId = getString(1),
        expiresAtMs = getLong(2),
        state = getString(3),
        sealedAck = getBytes(4),
    )
}
