package org.tenshu.sync.relay.storage

import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.relay.mailbox.HlcTipRegistry

/**
 * Durable [HlcTipRegistry] backed by [RelayDatabase] (SQLite). Each `SignedHlcTip` is
 * stored as its serialized wire envelope ([RelayBinaryCodec]) keyed by
 * `(ownerId, deviceId)`; `record` overwrites (the relay always serves whatever the device
 * last claimed — regression detection is client-side). Signed metadata only, no plaintext
 * (House Rule #1).
 */
class SqliteHlcTipStore(
    private val db: RelayDatabase,
    private val codec: RelayBinaryCodec,
) : HlcTipRegistry {

    override fun record(ownerId: String, tip: SignedHlcTip) {
        val bytes = codec.serialize(tip)
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO hlc_tip(owner_id, device_id, tip) VALUES(?, ?, ?)
                ON CONFLICT(owner_id, device_id) DO UPDATE SET tip = excluded.tip
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, tip.deviceId.get())
                ps.setBytes(3, bytes)
                ps.executeUpdate()
            }
        }
    }

    override fun getPeerTips(ownerId: String, excludingDeviceId: String): List<SignedHlcTip> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT tip FROM hlc_tip WHERE owner_id = ? AND device_id <> ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, excludingDeviceId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(codec.parse<SignedHlcTip>(rs.getBytes(1))) }
                }
            }
        }

    override fun allTips(ownerId: String): List<SignedHlcTip> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT tip FROM hlc_tip WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(codec.parse<SignedHlcTip>(rs.getBytes(1))) }
                }
            }
        }

    override fun forgetDevice(ownerId: String, deviceId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM hlc_tip WHERE owner_id = ? AND device_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.executeUpdate()
            }
        }

    /** Account closure (F12). */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "hlc_tip" to "owner_id")
}
