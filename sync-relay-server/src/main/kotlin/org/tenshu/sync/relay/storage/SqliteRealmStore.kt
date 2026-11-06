package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.RealmRegistry
import org.tenshu.sync.relay.mailbox.RealmStore

/**
 * Durable [RealmRegistry] backed by [RelayDatabase] (SQLite). Stores only the realm's
 * master **public** key (House Rule #1). Reuses [RealmStore.RealmRecord] as the record
 * type (it stays nested on the in-memory backend; both backends share it).
 */
class SqliteRealmStore(private val db: RelayDatabase) : RealmRegistry {

    override fun createIfAbsent(record: RealmStore.RealmRecord): Boolean =
        db.withConnection { conn ->
            // `INSERT OR IGNORE` is the atomic create-if-absent: a duplicate ownerId
            // affects 0 rows. Single-writer lock makes the check-and-insert race-free —
            // exactly one winner for two clients racing the same ownerId (returns 409).
            conn.prepareStatement(
                "INSERT OR IGNORE INTO realm(owner_id, master_public_key, created_at) VALUES(?, ?, ?)"
            ).use { ps ->
                ps.setString(1, record.ownerId)
                ps.setBytes(2, record.masterPublicKey)
                ps.setLong(3, record.createdAt)
                ps.executeUpdate() == 1
            }
        }

    override fun get(ownerId: String): RealmStore.RealmRecord? =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT master_public_key, created_at FROM realm WHERE owner_id = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        RealmStore.RealmRecord(
                            ownerId = ownerId,
                            masterPublicKey = rs.getBytes(1),
                            createdAt = rs.getLong(2),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    override fun isErased(ownerId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("SELECT 1 FROM erased_realm WHERE owner_id = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().next()
        }
    }

    /** Account closure (F12): the realm row itself and recording the erasure marker. */
    override fun eraseOwner(ownerId: String): Int {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO erased_realm(owner_id, erased_at) VALUES(?, ?)"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
        return db.deleteOwnerRows(ownerId, "realm" to "owner_id")
    }
}
