package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.sync.relay.PairingResponseInbox
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry

/**
 * Durable [PairingOfferRegistry] backed by [RelayDatabase] (SQLite) —
 * `sync-pairing-inverted-direction.md` §3.4, the `sync-relay-persistence.md`
 * pattern. The rendezvous is minutes-scale, but persisting it means a relay
 * restart mid-handshake doesn't strand an offerer polling for a response that
 * evaporated — and the consumed marker (single-use, T-P3) survives restarts,
 * which the in-memory backend cannot promise. Every column is routing
 * metadata or sealed ciphertext (House Rule #1).
 *
 * Read-then-write sequences run inside [RelayDatabase.withConnection]'s
 * single-writer lock, so post-once is race-free (the [SqliteInviteStore]
 * precedent).
 */
class SqlitePairingOfferStore(private val db: RelayDatabase) : PairingOfferRegistry {

    override fun post(
        pairingSecretHash: String,
        response: PairingOfferRegistry.OfferResponse,
        expiresAtMs: Long,
        nowMs: Long,
    ): PairingOfferRegistry.PostResult = db.withConnection { conn ->
        val consumed = conn.prepareStatement(
            "SELECT 1 FROM pairing_offer_response WHERE secret_hash = ?"
        ).use { ps ->
            ps.setString(1, pairingSecretHash)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (consumed) return@withConnection PairingOfferRegistry.PostResult.CONSUMED
        conn.prepareStatement(
            """
            INSERT INTO pairing_offer_response(
                secret_hash, owner_id, status, public_key_a, payload, expires_at,
                reg_device_id, reg_public_key, reg_admitted_at, reg_admission_chain
            )
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            val reg = response.registration
            ps.setString(1, pairingSecretHash)
            ps.setString(2, response.ownerId)
            ps.setString(3, response.status)
            ps.setBytes(4, response.publicKeyA)
            ps.setBytes(5, response.payload)
            ps.setLong(6, expiresAtMs)
            // The registration's owner is the row's `owner_id` (OfferResponse requires it).
            ps.setString(7, reg?.deviceId)
            ps.setBytes(8, reg?.publicKey)
            ps.setString(9, reg?.admittedAt?.encode())
            ps.setBytes(10, reg?.admissionCertChain)
            ps.executeUpdate()
        }
        PairingOfferRegistry.PostResult.STORED
    }

    override fun postAnnounce(
        pairingSecretHash: String,
        response: PairingOfferRegistry.OfferResponse,
        expiresAtMs: Long,
        nowMs: Long,
    ): PairingOfferRegistry.AnnounceResult = db.withConnection { conn ->
        // A terminal response settles the hash — announcing after the decision is meaningless, and
        // must not displace the blob the offerer is polling for.
        val settled = conn.prepareStatement(
            "SELECT 1 FROM pairing_offer_response WHERE secret_hash = ?"
        ).use { ps ->
            ps.setString(1, pairingSecretHash)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (settled) return@withConnection PairingOfferRegistry.AnnounceResult.CONSUMED
        val announced = conn.prepareStatement(
            "SELECT 1 FROM pairing_offer_announce WHERE secret_hash = ?"
        ).use { ps ->
            ps.setString(1, pairingSecretHash)
            ps.executeQuery().use { rs -> rs.next() }
        }
        // ⚠⚠ One announce per hash. The offerer PINS the announced key and later refuses a terminal
        // response that does not match it; letting a second announce overwrite the first would
        // re-point that pin and turn the lock into its opposite.
        if (announced) return@withConnection PairingOfferRegistry.AnnounceResult.ALREADY_ANNOUNCED
        conn.prepareStatement(
            "INSERT INTO pairing_offer_announce(secret_hash, owner_id, status, public_key_a, expires_at) VALUES(?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, pairingSecretHash)
            ps.setString(2, response.ownerId)
            ps.setString(3, response.status)
            ps.setBytes(4, response.publicKeyA)
            ps.setLong(5, expiresAtMs)
            ps.executeUpdate()
        }
        PairingOfferRegistry.AnnounceResult.STORED
    }

    private fun fetchAnnounce(
        conn: java.sql.Connection,
        pairingSecretHash: String,
        nowMs: Long,
    ): PairingOfferRegistry.Fetch = conn.prepareStatement(
        "SELECT owner_id, status, public_key_a FROM pairing_offer_announce WHERE secret_hash = ? AND expires_at > ?"
    ).use { ps ->
        ps.setString(1, pairingSecretHash)
        ps.setLong(2, nowMs)
        ps.executeQuery().use { rs ->
            if (!rs.next()) PairingOfferRegistry.Fetch.NotFound
            else PairingOfferRegistry.Fetch.Found(
                PairingOfferRegistry.OfferResponse(
                    ownerId = rs.getString(1),
                    status = rs.getString(2),
                    publicKeyA = rs.getBytes(3),
                    payload = null,
                )
            )
        }
    }

    override fun fetch(pairingSecretHash: String, nowMs: Long): PairingOfferRegistry.Fetch =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT owner_id, status, public_key_a, payload, reg_device_id, reg_public_key, reg_admitted_at, reg_admission_chain
                FROM pairing_offer_response WHERE secret_hash = ? AND expires_at > ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, pairingSecretHash)
                ps.setLong(2, nowMs)
                ps.executeQuery().use { rs ->
                    // Terminal first; the announce is served only until the decision replaces it.
                    if (!rs.next()) return@withConnection fetchAnnounce(conn, pairingSecretHash, nowMs)
                    val ownerId = rs.getString(1)
                    val regDeviceId: String? = rs.getString(5)
                    val regPublicKey: ByteArray? = rs.getBytes(6)
                    PairingOfferRegistry.Fetch.Found(
                        PairingOfferRegistry.OfferResponse(
                            ownerId = ownerId,
                            status = rs.getString(2),
                            publicKeyA = rs.getBytes(3),
                            payload = rs.getBytes(4),
                            registration = if (regDeviceId != null && regPublicKey != null) {
                                PairingResponseInbox.PendingRegistration(
                                    ownerId = ownerId,
                                    deviceId = regDeviceId,
                                    publicKey = regPublicKey,
                                    admittedAt = decodeHlcStamp(rs.getString(7)),
                                    admissionCertChain = rs.getBytes(8),
                                )
                            } else {
                                null
                            },
                        )
                    )
                }
            }
        }

    override fun pendingAdmittedAt(ownerId: String, deviceId: String, nowMs: Long): org.tenshu.core.common.util.HlcStamp? =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT reg_admitted_at FROM pairing_offer_response " +
                    "WHERE owner_id = ? AND reg_device_id = ? AND expires_at > ? AND reg_admitted_at IS NOT NULL"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, deviceId)
                ps.setLong(3, nowMs)
                ps.executeQuery().use { rs ->
                    val stamps = mutableListOf<org.tenshu.core.common.util.HlcStamp>()
                    while (rs.next()) decodeHlcStamp(rs.getString(1))?.let(stamps::add)
                    stamps
                }
            }.maxOrNull()
        }

    override fun gcSweep(nowMs: Long, retentionMs: Long): Int = db.withConnection { conn ->
        val terminal = conn.prepareStatement(
            "DELETE FROM pairing_offer_response WHERE expires_at + ? <= ?"
        ).use { ps ->
            ps.setLong(1, retentionMs)
            ps.setLong(2, nowMs)
            ps.executeUpdate()
        }
        // ⚠ Announces are swept too, or a handshake abandoned after the scan leaks a row forever.
        val announces = conn.prepareStatement(
            "DELETE FROM pairing_offer_announce WHERE expires_at + ? <= ?"
        ).use { ps ->
            ps.setLong(1, retentionMs)
            ps.setLong(2, nowMs)
            ps.executeUpdate()
        }
        terminal + announces
    }

    /**
     * Account closure (F12) — also drops a queued offerer registration, which lives on its terminal
     * row (`reg_*`), so a fetch after erasure cannot re-register a device into the erased realm.
     * ⚠ A terminal row is also the single-use marker for its secret hash; dropping
     * it early is no weaker than [gcSweep], which drops it `retentionMs` after a minutes-long TTL — long
     * before any closure grace has run out.
     */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(
        ownerId,
        "pairing_offer_response" to "owner_id",
        "pairing_offer_announce" to "owner_id",
    )
}
