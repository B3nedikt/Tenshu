package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.MembershipRegistry

/**
 * Durable [MembershipRegistry] backed by [RelayDatabase] (SQLite) —
 * `shared-realm-membership-grant.md` §4, the `sync-relay-persistence.md`
 * pattern. Durability is **load-bearing** here (unlike the ephemeral grant
 * mailbox): the revocation list and the admitted-device associations must
 * survive a restart, or a revoked member's devices would be silently
 * re-admissible. Every column is a public key, an opaque id, or a master-signed
 * token (House Rule #1).
 */
class SqliteMembershipStore(private val db: RelayDatabase) : MembershipRegistry {

    override fun recordAdmission(
        realmId: String,
        memberAuthorityPublicKey: ByteArray,
        deviceId: String,
    ) = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO member_admission(realm_id, member_authority_pub, device_id)
            VALUES(?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, realmId)
            ps.setBytes(2, memberAuthorityPublicKey)
            ps.setString(3, deviceId)
            ps.executeUpdate()
        }
        Unit
    }

    override fun admittedDevices(realmId: String, memberAuthorityPublicKey: ByteArray): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT device_id FROM member_admission WHERE realm_id = ? AND member_authority_pub = ?"
            ).use { ps ->
                ps.setString(1, realmId)
                ps.setBytes(2, memberAuthorityPublicKey)
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override fun isMemberDevice(realmId: String, deviceId: String): Boolean =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM member_admission WHERE realm_id = ? AND device_id = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, realmId)
                ps.setString(2, deviceId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    override fun clearAdmissions(realmId: String, memberAuthorityPublicKey: ByteArray) =
        db.withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM member_admission WHERE realm_id = ? AND member_authority_pub = ?"
            ).use { ps ->
                ps.setString(1, realmId)
                ps.setBytes(2, memberAuthorityPublicKey)
                ps.executeUpdate()
            }
            Unit
        }

    override fun appendRevocation(record: MembershipRegistry.RevocationRecord) = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO member_revocation(realm_id, member_authority_pub, epoch, revoked_at, signature)
            VALUES(?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, record.realmId)
            ps.setBytes(2, record.memberAuthorityPublicKey)
            ps.setLong(3, record.epoch)
            ps.setLong(4, record.revokedAt)
            ps.setBytes(5, record.signature)
            ps.executeUpdate()
        }
        Unit
    }

    override fun revocations(realmId: String): List<MembershipRegistry.RevocationRecord> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT member_authority_pub, epoch, revoked_at, signature
                FROM member_revocation WHERE realm_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, realmId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                MembershipRegistry.RevocationRecord(
                                    realmId = realmId,
                                    memberAuthorityPublicKey = rs.getBytes(1),
                                    epoch = rs.getLong(2),
                                    revokedAt = rs.getLong(3),
                                    signature = rs.getBytes(4),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun recordCount(): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM member_revocation").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /**
     * Account closure (F12): the admitted-device associations and member revocations of the realm [ownerId] (`realm_id`).
     */
    override fun eraseOwner(ownerId: String): Int {
        val admissions = db.deleteOwnerRows(ownerId, "member_admission" to "realm_id")
        val revocations = db.deleteOwnerRows(ownerId, "member_revocation" to "realm_id")
        return admissions + revocations
    }
}
