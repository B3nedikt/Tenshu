package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.GrantRegistry

/**
 * Durable [GrantRegistry] backed by [RelayDatabase] (SQLite) —
 * `shared-realm-membership-grant.md` §4, the `sync-relay-persistence.md`
 * pattern. The grant mailbox is ephemeral (≤ 30-day TTL, single-pickup) but
 * persisting it is harmless and means a relay restart mid-handshake doesn't
 * strand a grantee waiting on a grant that evaporated. Every column is routing
 * metadata or sealed ciphertext (House Rule #1).
 *
 * Read-then-write sequences run inside [RelayDatabase.withConnection]'s
 * single-writer lock, so single-pickup-delete is race-free without explicit
 * transactions (the [SqliteInviteStore] precedent).
 */
class SqliteGrantStore(private val db: RelayDatabase) : GrantRegistry {

    override fun put(
        address: String,
        ownerId: String,
        sealedGrant: ByteArray,
        expiresAtMs: Long,
        nowMs: Long,
        maxOpenPerOwner: Int,
    ): GrantRegistry.PutResult = db.withConnection { conn ->
        val exists = conn.prepareStatement("SELECT 1 FROM grant_mailbox WHERE address = ?").use { ps ->
            ps.setString(1, address)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (!exists) {
            val openCount = conn.prepareStatement(
                "SELECT COUNT(*) FROM grant_mailbox WHERE owner_id = ? AND expires_at > ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, nowMs)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            if (openCount >= maxOpenPerOwner) return@withConnection GrantRegistry.PutResult.CAP_EXCEEDED
        }
        // Last-write-wins on a repeated address (a re-grant supersedes an unread blob).
        conn.prepareStatement(
            """
            INSERT INTO grant_mailbox(address, owner_id, sealed_grant, created_at, expires_at)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(address) DO UPDATE SET
                owner_id = excluded.owner_id,
                sealed_grant = excluded.sealed_grant,
                created_at = excluded.created_at,
                expires_at = excluded.expires_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, address)
            ps.setString(2, ownerId)
            ps.setBytes(3, sealedGrant)
            ps.setLong(4, nowMs)
            ps.setLong(5, expiresAtMs)
            ps.executeUpdate()
        }
        GrantRegistry.PutResult.STORED
    }

    override fun pickup(address: String, nowMs: Long): GrantRegistry.Pickup = db.withConnection { conn ->
        val row = conn.prepareStatement(
            "SELECT sealed_grant, expires_at FROM grant_mailbox WHERE address = ?"
        ).use { ps ->
            ps.setString(1, address)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getBytes(1) to rs.getLong(2) else null
            }
        } ?: return@withConnection GrantRegistry.Pickup.Empty
        // Single-pickup: delete on read regardless of expiry.
        conn.prepareStatement("DELETE FROM grant_mailbox WHERE address = ?").use { ps ->
            ps.setString(1, address)
            ps.executeUpdate()
        }
        val (sealedGrant, expiresAtMs) = row
        if (nowMs >= expiresAtMs) GrantRegistry.Pickup.Empty
        else GrantRegistry.Pickup.Found(sealedGrant)
    }

    override fun gcSweep(nowMs: Long): Int = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM grant_mailbox WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
    }

    override fun recordCount(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM grant_mailbox").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** Account closure (F12): unpicked grants this owner dropped. */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "grant_mailbox" to "owner_id")
}
