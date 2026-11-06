package org.tenshu.sync.relay.storage

import org.tenshu.identity.Device
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.relay.EnvelopeOnlySyncBlock
import org.tenshu.sync.relay.MailboxStore
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.SyncMailbox
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable [MailboxStore] backed by [RelayDatabase] (SQLite) — Phase 2b-ii. The ordered
 * sync-block log and tombstone log persist; the **ephemeral** pairing handshake state and
 * the retention-override admin seam stay in-memory (consistent with the in-memory backend).
 *
 * Cursor: a single shared `relay_seq` counter assigns each block/tombstone its arrival seq,
 * matching the in-memory `AtomicLong` — but it **continues across restart** (it must: the
 * blocks it numbered also persist, so resetting would collide seqs / strand client cursors).
 *
 * Body-drop GC: instead of swapping in an [EnvelopeOnlySyncBlock] in place, the SQL backend
 * flips a `body_dropped` flag and re-wraps the parsed block with the same wrapper on read —
 * identical observable behaviour (`getChanges` returns an empty-payload envelope marker).
 *
 * The relay never decrypts — envelopes are stored as serialized ciphertext (House Rule #1).
 */
class SqliteSyncMailbox(
    private val db: RelayDatabase,
    private val codec: RelayBinaryCodec,
) : MailboxStore {

    // Ephemeral / admin-seam state — in-memory, like the in-memory backend.
    private val pairingRequests = org.tenshu.sync.relay.PairingRequestInbox()
    private val pairingResponses = org.tenshu.sync.relay.PairingResponseInbox()
    private val retentionWindowOverridesMs = ConcurrentHashMap<String, Long>()

    private fun nextSeq(conn: java.sql.Connection): Long {
        conn.createStatement().use { it.executeUpdate("UPDATE relay_seq SET value = value + 1") }
        return conn.prepareStatement("SELECT value FROM relay_seq WHERE id = 0").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    // --- Sync-block log ---

    override fun pushBlock(ownerId: String, block: SyncBlock) {
        val envelope = codec.serialize(block)
        db.withConnection { conn ->
            val seq = nextSeq(conn)
            conn.prepareStatement(
                """
                INSERT INTO sync_block(seq, owner_id, block_id, target_entity_id_hash, hlc_physical, envelope, body_dropped)
                VALUES(?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, seq)
                ps.setString(2, ownerId)
                ps.setString(3, block.blockId.get())
                ps.setString(4, block.targetEntityIdHash?.get())
                ps.setLong(5, block.hlc.physicalTime.get())
                ps.setBytes(6, envelope)
                ps.executeUpdate()
            }
        }
    }

    override fun getChanges(ownerId: String, sinceTimestamp: Long, limit: Int): SyncMailbox.ChangesResult =
        db.withConnection { conn ->
            var maxSeq = sinceTimestamp
            // ⚠ The LIMIT is load-bearing, not an optimisation: without it this query
            // materialises the owner's entire history into one heap-resident list, which is
            // exactly how a from-zero cursor OOM'd the relay (see SYNC_CHANGES_PAGE_LIMIT).
            // `ORDER BY seq` makes the truncation a prefix, so `maxSeq` below is the page end
            // and a caller advancing to it has missed nothing.
            val blocks = conn.prepareStatement(
                "SELECT seq, envelope, body_dropped FROM sync_block WHERE owner_id = ? AND seq > ? ORDER BY seq LIMIT ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, sinceTimestamp)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            maxSeq = maxOf(maxSeq, rs.getLong(1))
                            add(readBlock(rs.getBytes(2), rs.getInt(3) != 0))
                        }
                    }
                }
            }
            SyncMailbox.ChangesResult(blocks, maxSeq)
        }

    override fun fetchEntityBlocks(ownerId: String, entityIdHash: String): List<SyncBlock> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT envelope, body_dropped FROM sync_block WHERE owner_id = ? AND target_entity_id_hash = ? ORDER BY seq"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, entityIdHash)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readBlock(rs.getBytes(1), rs.getInt(2) != 0)) }
                }
            }
        }

    /** Parse a stored envelope; re-wrap as envelope-only if its body was GC'd. */
    private fun readBlock(envelope: ByteArray, bodyDropped: Boolean): SyncBlock {
        val block = codec.parse<SyncBlock>(envelope)
        return if (bodyDropped) EnvelopeOnlySyncBlock(block) else block
    }

    // --- Tombstone log ---

    override fun pushTombstone(ownerId: String, block: DeleteBlock): Long {
        val envelope = codec.serialize(block)
        return db.withConnection { conn ->
            val seq = nextSeq(conn)
            conn.prepareStatement(
                """
                INSERT INTO delete_block(seq, owner_id, block_id, target_entity_id_hash, target_entity_type, hlc_physical, envelope)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, seq)
                ps.setString(2, ownerId)
                ps.setString(3, block.blockId.get())
                ps.setString(4, block.targetEntityIdHash.get())
                ps.setString(5, block.targetEntityType.get())
                ps.setLong(6, block.hlc.physicalTime.get())
                ps.setBytes(7, envelope)
                ps.executeUpdate()
            }
            seq
        }
    }

    override fun getTombstones(ownerId: String, sinceTimestamp: Long, limit: Int): SyncMailbox.TombstonesResult =
        db.withConnection { conn ->
            var maxSeq = sinceTimestamp
            val tombstones = conn.prepareStatement(
                "SELECT seq, envelope FROM delete_block WHERE owner_id = ? AND seq > ? ORDER BY seq LIMIT ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, sinceTimestamp)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            maxSeq = maxOf(maxSeq, rs.getLong(1))
                            add(codec.parse<DeleteBlock>(rs.getBytes(2)))
                        }
                    }
                }
            }
            SyncMailbox.TombstonesResult(tombstones, maxSeq)
        }

    /**
     * The merged prefix, as one `UNION ALL … ORDER BY seq` — the SQL statement of the same
     * property the in-memory backend gets by merging two lists: ONE page cut across both
     * kinds, so a single cursor covers both.
     *
     * ⚠ Fetches `limit + 1` rows and keeps `limit`. The extra row IS the `hasMore` answer,
     * exactly and in one round trip — no second COUNT, and none of the guesswork of inferring
     * truncation from `rows.size == limit`, which cannot tell a full page from a full *final*
     * page.
     */
    override fun getSyncLog(
        ownerId: String,
        sinceTimestamp: Long,
        limit: Int
    ): SyncMailbox.SyncLogResult =
        db.withConnection { conn ->
            // kind 0 = sync block, 1 = tombstone. `body_dropped` is meaningless for a
            // tombstone row, so the branch selects a literal 0 to keep the column counts
            // union-compatible.
            val rows = conn.prepareStatement(
                """
                SELECT seq, envelope, body_dropped, 0 AS kind
                  FROM sync_block WHERE owner_id = ? AND seq > ?
                UNION ALL
                SELECT seq, envelope, 0 AS body_dropped, 1 AS kind
                  FROM delete_block WHERE owner_id = ? AND seq > ?
                ORDER BY seq
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, sinceTimestamp)
                ps.setString(3, ownerId)
                ps.setLong(4, sinceTimestamp)
                ps.setInt(5, limit + 1)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LogRow(
                                    seq = rs.getLong(1),
                                    envelope = rs.getBytes(2),
                                    bodyDropped = rs.getInt(3) != 0,
                                    isTombstone = rs.getInt(4) == 1
                                )
                            )
                        }
                    }
                }
            }

            val hasMore = rows.size > limit
            val page = if (hasMore) rows.take(limit) else rows
            val nextCursor = page.lastOrNull()?.seq ?: sinceTimestamp

            SyncMailbox.SyncLogResult(
                blocks = page.filter { !it.isTombstone }.map { readBlock(it.envelope, it.bodyDropped) },
                tombstones = page.filter { it.isTombstone }.map { codec.parse<DeleteBlock>(it.envelope) },
                nextCursor = nextCursor,
                hasMore = hasMore
            )
        }

    /**
     * The backward page — same `UNION ALL` as [getSyncLog], `ORDER BY seq DESC`, and the same
     * `limit + 1` trick for an exact `hasMore` in one round trip.
     */
    override fun getSyncLogBefore(
        ownerId: String,
        beforeSeq: Long,
        limit: Int
    ): SyncMailbox.SyncLogResult =
        db.withConnection { conn ->
            val rows = conn.prepareStatement(
                """
                SELECT seq, envelope, body_dropped, 0 AS kind
                  FROM sync_block WHERE owner_id = ? AND seq < ?
                UNION ALL
                SELECT seq, envelope, 0 AS body_dropped, 1 AS kind
                  FROM delete_block WHERE owner_id = ? AND seq < ?
                ORDER BY seq DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setLong(2, beforeSeq)
                ps.setString(3, ownerId)
                ps.setLong(4, beforeSeq)
                ps.setInt(5, limit + 1)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LogRow(
                                    seq = rs.getLong(1),
                                    envelope = rs.getBytes(2),
                                    bodyDropped = rs.getInt(3) != 0,
                                    isTombstone = rs.getInt(4) == 1
                                )
                            )
                        }
                    }
                }
            }

            val hasMore = rows.size > limit
            // Descending from the query; keep the page's own ascending order for the caller.
            val page = (if (hasMore) rows.take(limit) else rows).sortedBy { it.seq }
            val floor = page.firstOrNull()?.seq ?: beforeSeq

            SyncMailbox.SyncLogResult(
                blocks = page.filter { !it.isTombstone }.map { readBlock(it.envelope, it.bodyDropped) },
                tombstones = page.filter { it.isTombstone }.map { codec.parse<DeleteBlock>(it.envelope) },
                nextCursor = floor,
                hasMore = hasMore
            )
        }

    /** One row of the merged `getSyncLog` query, before it is split back out by kind. */
    private data class LogRow(
        val seq: Long,
        val envelope: ByteArray,
        val bodyDropped: Boolean,
        val isTombstone: Boolean,
    )

    override fun tombstoneIndex(ownerId: String): Map<String, DeleteBlock> =
        db.withConnection { conn ->
            // Highest-HLC tombstone per target (recreate semantics). Keep the winning
            // envelope bytes per hash, then parse only the winners.
            val winners = HashMap<String, Pair<Long, ByteArray>>()
            conn.prepareStatement(
                "SELECT target_entity_id_hash, hlc_physical, envelope FROM delete_block WHERE owner_id = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val hash = rs.getString(1)
                        val hlc = rs.getLong(2)
                        val existing = winners[hash]
                        if (existing == null || hlc > existing.first) winners[hash] = hlc to rs.getBytes(3)
                    }
                }
            }
            winners.mapValues { (_, v) -> codec.parse<DeleteBlock>(v.second) }
        }

    override fun tombstoneOwnerIds(): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT DISTINCT owner_id FROM delete_block").use { ps ->
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override fun deviceTombstoneCount(ownerId: String): Long =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM delete_block WHERE owner_id = ? AND target_entity_type = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, Device.ID)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    // --- GC body-drop bookkeeping ---

    override fun setRetentionWindowMs(ownerId: String, retentionMs: Long) {
        retentionWindowOverridesMs[ownerId] = retentionMs
    }

    override fun retentionWindowMs(ownerId: String): Long =
        retentionWindowOverridesMs[ownerId] ?: RelayConfig.DEFAULT_RETENTION_WINDOW_MS

    override fun droppedBlockIds(ownerId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT block_id FROM sync_block WHERE owner_id = ? AND body_dropped = 1"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override fun dropBodiesFor(ownerId: String, targetEntityIdHash: String, tombstoneHlcPhysical: Long): Int =
        db.withConnection { conn ->
            // body_dropped = 1 is both the freed-bytes marker and the idempotence guard.
            conn.prepareStatement(
                """
                UPDATE sync_block SET body_dropped = 1
                WHERE owner_id = ? AND target_entity_id_hash = ? AND hlc_physical < ? AND body_dropped = 0
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, targetEntityIdHash)
                ps.setLong(3, tombstoneHlcPhysical)
                ps.executeUpdate()
            }
        }

    // --- Ephemeral pairing handshake state (in-memory) ---

    override fun addPairingRequest(ownerId: String, request: SyncMailbox.PairingRequestData) {
        pairingRequests.add(ownerId, request)
    }

    override fun getPairingRequests(ownerId: String): List<SyncMailbox.PairingRequestData> =
        pairingRequests.get(ownerId)

    override fun removePairingRequest(ownerId: String, pairingSecretHash: String) {
        pairingRequests.remove(ownerId, pairingSecretHash)
    }

    // F7: keyed by the joiner's pairingSecretHash; TTL + consumed marker in PairingResponseInbox.
    override fun addPairingResponse(
        pairingSecretHash: String,
        encryptedKey: ByteArray,
        registration: org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration?,
    ) = pairingResponses.post(pairingSecretHash, encryptedKey, registration)

    override fun getPairingResponse(pairingSecretHash: String): org.tenshu.sync.relay.PairingResponseInbox.Delivery? =
        pairingResponses.peek(pairingSecretHash)

    override fun pendingPairingAdmittedAt(ownerId: String, deviceId: String) =
        pairingResponses.pendingAdmittedAt(ownerId, deviceId)

    override fun ackPairingResponse(pairingSecretHash: String) {
        pairingResponses.ack(pairingSecretHash)
    }

    override fun isPairingResponseConsumed(pairingSecretHash: String): Boolean =
        pairingResponses.isConsumed(pairingSecretHash)

    /**
     * Account closure (F12): the ordered log (blocks + tombstones) and the in-memory per-owner state.
     * Includes the owner's queued pairing responses (attributed by their registration) — see [MailboxStore].
     */
    override fun eraseOwner(ownerId: String): Int {
        val rows = db.deleteOwnerRows(ownerId, "sync_block" to "owner_id", "delete_block" to "owner_id")
        retentionWindowOverridesMs.remove(ownerId)
        return rows + pairingRequests.eraseOwner(ownerId) + pairingResponses.eraseOwner(ownerId)
    }
}
