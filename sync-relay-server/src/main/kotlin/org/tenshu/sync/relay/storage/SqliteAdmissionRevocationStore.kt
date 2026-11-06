package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.OwnerScopedStore

/**
 * Durable [AdmissionRevocationRegistry] backed by [RelayDatabase] (SQLite) —
 * `delegated-device-admission-authority.md` §5, the `sync-relay-persistence.md`
 * pattern. Durability is **load-bearing** (the `member_revocation` posture): a
 * revocation list lost on restart would silently restore a revoked device's
 * power to admit. Every column is an opaque id, an epoch, or a master-signed
 * token (House Rule #1).
 */
class SqliteAdmissionRevocationStore(private val db: RelayDatabase) : AdmissionRevocationRegistry, OwnerScopedStore {

    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "admission_revocation" to "owner_id")

    override fun appendRevocation(record: AdmissionRevocationRegistry.RevocationRecord) =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admission_revocation(
                    owner_id, device_id, epoch, revoked_at, signature, device_a33_public_key
                )
                VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.ownerId)
                ps.setString(2, record.deviceId)
                ps.setLong(3, record.epoch)
                ps.setLong(4, record.revokedAt)
                ps.setBytes(5, record.signature)
                ps.setBytes(6, record.deviceA33PublicKey)
                ps.executeUpdate()
            }
            Unit
        }

    override fun revocations(ownerId: String): List<AdmissionRevocationRegistry.RevocationRecord> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT device_id, epoch, revoked_at, signature, device_a33_public_key
                FROM admission_revocation WHERE owner_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AdmissionRevocationRegistry.RevocationRecord(
                                    ownerId = ownerId,
                                    deviceId = rs.getString(1),
                                    epoch = rs.getLong(2),
                                    revokedAt = rs.getLong(3),
                                    signature = rs.getBytes(4),
                                    deviceA33PublicKey = rs.getBytes(5),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun recordCount(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM admission_revocation").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }
}
