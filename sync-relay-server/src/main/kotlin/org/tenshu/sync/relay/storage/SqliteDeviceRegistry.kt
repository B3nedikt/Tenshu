package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.auth.DeviceRegistry

/**
 * Durable [DeviceRegistry] backed by [RelayDatabase] (SQLite). Survives relay restart, so a
 * paired device is not answered 401 after one (`ReregisterDevice` only recovers from that).
 * Stores only `(ownerId, deviceId) → Ed25519 public key`; no plaintext (House Rule #1).
 */
class SqliteDeviceRegistry(private val db: RelayDatabase) : DeviceRegistry {

    override fun register(
        ownerId: String,
        deviceId: String,
        publicKey: ByteArray,
        admittedAt: HlcStamp?,
    ) {
        db.withConnection { conn ->
            // Upsert — "register or replace", matching the in-memory backend. ⚠ The stamp columns
            // are overwritten unconditionally, including to NULL: a re-admission supersedes the one
            // before it, and a stamp outliving its admission would let the guard reject a
            // legitimate later revocation.
            conn.prepareStatement(
                """
                INSERT INTO device(
                    owner_id, device_id, public_key,
                    admitted_hlc_physical, admitted_hlc_counter, admitted_hlc_device
                ) VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_id, device_id) DO UPDATE SET
                    public_key = excluded.public_key,
                    admitted_hlc_physical = excluded.admitted_hlc_physical,
                    admitted_hlc_counter = excluded.admitted_hlc_counter,
                    admitted_hlc_device = excluded.admitted_hlc_device
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.setBytes(3, publicKey)
                if (admittedAt == null) {
                    ps.setNull(4, java.sql.Types.INTEGER)
                    ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setNull(6, java.sql.Types.VARCHAR)
                } else {
                    ps.setLong(4, admittedAt.physicalTime)
                    ps.setLong(5, admittedAt.logicalCounter)
                    ps.setString(6, admittedAt.deviceId)
                }
                ps.executeUpdate()
            }
        }
    }

    override fun admittedAt(ownerId: String, deviceId: String): HlcStamp? =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT admitted_hlc_physical, admitted_hlc_counter, admitted_hlc_device
                FROM device WHERE owner_id = ? AND device_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    val physical = rs.getLong(1)
                    if (rs.wasNull()) return@withConnection null
                    val counter = rs.getLong(2)
                    if (rs.wasNull()) return@withConnection null
                    val device = rs.getString(3) ?: return@withConnection null
                    HlcStamp(physical, counter, device)
                }
            }
        }

    override fun getPublicKey(ownerId: String, deviceId: String): ByteArray? =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT public_key FROM device WHERE owner_id = ? AND device_id = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
            }
        }

    override fun deviceIds(ownerId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT device_id FROM device WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override fun recordRemoval(ownerId: String, deviceId: String) {
        db.withConnection { conn ->
            // INSERT OR IGNORE: a re-delivered tombstone must not multiply rows, and the ledger
            // records *that* a removal happened, not how many times it was announced.
            conn.prepareStatement(
                "INSERT OR IGNORE INTO removed_device(owner_id, device_id, removed_at) VALUES(?, ?, ?)"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    override fun wasRemoved(ownerId: String, deviceId: String): Boolean =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM removed_device WHERE owner_id = ? AND device_id = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    override fun revoke(ownerId: String, deviceId: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM device WHERE owner_id = ? AND device_id = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.executeUpdate()
            }
        }
    }

    /** Account closure (F12): erases device registrations and removed_device fences. */
    override fun eraseOwner(ownerId: String): Int {
        val devices = db.deleteOwnerRows(ownerId, "device" to "owner_id")
        val removed = db.deleteOwnerRows(ownerId, "removed_device" to "owner_id")
        return devices + removed
    }
}
