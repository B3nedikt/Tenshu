package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ShareRegistry
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.mailbox.TakedownOutcome
import org.tenshu.sync.relay.mailbox.TakedownReason
import org.tenshu.sync.relay.mailbox.TakedownRecord
import org.tenshu.sync.relay.mailbox.TakedownResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.sql.Connection
import java.sql.ResultSet

/**
 * Durable [ShareRegistry] — Phase 3b (the last in-memory relay store). Mode-A share
 * **descriptors** live in SQLite (`share_record`, reshaped by migration 3 so it holds no clear
 * file name, MIME type or size — only the opaque `sealed_meta`, F3); the large opaque **chunk ciphertext**
 * lives in a [BlobStore] (key `share/<shareId>/<idx>`). The relay never decrypts and
 * `K_share` is never stored (House Rule #1 / A11).
 *
 * Atomicity: `open`'s cap/expiry/revoke check + the `opensSoFar` bump run in one held
 * write lock, so concurrent opens can't race past the cap (parity with the in-memory
 * compute-lock). GC mirrors the in-memory `isCollectible` rules (revoked / expired /
 * orphan past the retention window), deleting both the row and the chunk files.
 */
class SqliteShareStore(
    private val db: RelayDatabase,
    private val blobs: BlobStore,
    private val revokedRetentionWindowMs: Long = RelayConfig.DEFAULT_RETENTION_WINDOW_MS,
) : ShareRegistry {

    private fun chunkKey(shareId: String, chunkIndex: Int): String = "share/$shareId/$chunkIndex"

    override fun create(record: ShareStore.ShareRecord): ShareStore.ShareRecord? =
        db.withConnection { conn ->
            // ⚠ An id with a takedown on record is retired for good, also after the GC dropped its row:
            // the sharer picks the id, so otherwise the reported URL could be re-published (with the
            // same K_share it would even open again). `null` → the route's 409.
            val takenDown = conn.prepareStatement("SELECT 1 FROM share_takedown WHERE share_id = ? LIMIT 1").use { ps ->
                ps.setString(1, record.shareId)
                ps.executeQuery().use { it.next() }
            }
            if (takenDown) return@withConnection null
            val existing = selectRecord(conn, record.shareId)
            if (existing != null) {
                // Same owner re-PUT → idempotent; different owner → refuse (global namespace).
                return@withConnection if (existing.ownerId == record.ownerId) existing else null
            }
            conn.prepareStatement(
                """
                INSERT INTO share_record(
                    share_id, owner_id, chunk_count, chunk_hashes, expires_at, max_opens,
                    created_at, opens_so_far, revoked_at, sealed_meta
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.shareId)
                ps.setString(2, record.ownerId)
                ps.setInt(3, record.chunkCount)
                ps.setBytes(4, encodeHashes(record.chunkHashes))
                setNullableLong(ps, 5, record.expiresAtMillis)
                setNullableLong(ps, 6, record.maxOpens?.toLong())
                ps.setLong(7, record.createdAtMs)
                ps.setInt(8, record.opensSoFar)
                setNullableLong(ps, 9, record.revokedAtMs)
                ps.setBytes(10, record.sealedMeta)
                ps.executeUpdate()
            }
            record
        }

    override fun open(shareId: String, nowMs: Long): ShareStore.ShareRecord? =
        db.withConnection { conn ->
            val record = selectRecord(conn, shareId) ?: return@withConnection null
            if (record.revokedAtMs != null) return@withConnection null
            record.expiresAtMillis?.let { if (nowMs >= it) return@withConnection null }
            val capExhausted = record.maxOpens?.let { record.opensSoFar >= it } == true
            if (capExhausted) return@withConnection null

            conn.prepareStatement("UPDATE share_record SET opens_so_far = opens_so_far + 1 WHERE share_id = ?")
                .use { ps -> ps.setString(1, shareId); ps.executeUpdate() }
            // Return the descriptor with the bumped count, matching the in-memory backend.
            record.copy(opensSoFar = record.opensSoFar + 1)
        }

    override fun peek(shareId: String): ShareStore.ShareRecord? =
        db.withConnection { conn -> selectRecord(conn, shareId) }

    override fun revoke(shareId: String, ownerId: String, nowMs: Long): Boolean =
        db.withConnection { conn ->
            val record = selectRecord(conn, shareId) ?: return@withConnection false
            if (record.ownerId != ownerId) return@withConnection false
            conn.prepareStatement("UPDATE share_record SET revoked_at = ? WHERE share_id = ?").use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, shareId)
                ps.executeUpdate()
            }
            true
        }

    override fun putChunk(shareId: String, chunkIndex: Int, ciphertext: ByteArray) {
        // First-write-wins (chunks immutable) — `exists` avoids reading the blob to check.
        val key = chunkKey(shareId, chunkIndex)
        if (!blobs.exists(key)) blobs.put(key, ciphertext)
        // ⚠ A revoke or takedown can commit while this PUT is in flight (the route checked first, then
        // read up to 8 MiB). Re-check AFTER the write: a takedown commits its revoke before it deletes,
        // so either its delete sees these bytes or this check sees its revoke. Never served either way;
        // this is what makes "deleted at once" true.
        val revoked = db.withConnection { conn -> selectRecord(conn, shareId)?.revokedAtMs != null }
        if (revoked) blobs.delete(key)
    }

    override fun getChunk(shareId: String, chunkIndex: Int, nowMs: Long): ByteArray? {
        val live = db.withConnection { conn ->
            val r = selectRecord(conn, shareId) ?: return@withConnection false
            if (r.revokedAtMs != null) return@withConnection false
            r.expiresAtMillis?.let { if (nowMs >= it) return@withConnection false }
            true
        }
        if (!live) return null
        return blobs.get(chunkKey(shareId, chunkIndex))
    }

    override fun gcSweep(nowMs: Long): Int {
        if (revokedRetentionWindowMs == Long.MAX_VALUE) return 0
        val collected = db.withConnection { conn ->
            val candidates = mutableListOf<Pair<String, Int>>() // (shareId, chunkCount)
            conn.prepareStatement(
                "SELECT share_id, chunk_count, expires_at, created_at, revoked_at FROM share_record"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val shareId = rs.getString("share_id")
                        val chunkCount = rs.getInt("chunk_count")
                        if (isCollectible(shareId, rs.getLongOrNull("revoked_at"), rs.getLongOrNull("expires_at"), rs.getLong("created_at"), nowMs)) {
                            candidates += shareId to chunkCount
                        }
                    }
                }
            }
            for ((shareId, _) in candidates) {
                conn.prepareStatement("DELETE FROM share_record WHERE share_id = ?")
                    .use { ps -> ps.setString(1, shareId); ps.executeUpdate() }
            }
            candidates
        }
        for ((shareId, chunkCount) in collected) {
            blobs.deleteAll((0 until chunkCount).map { i -> chunkKey(shareId, i) })
        }
        return collected.size
    }

    override fun recordCount(): Int =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM share_record").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    override fun declaredChunkTotal(): Long =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COALESCE(SUM(chunk_count), 0) FROM share_record").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    /**
     * Account closure, phase 1 (F12): revoke every share of [ownerId], so from the commit on no link
     * opens and no chunk is served. The rows stay for now — they are the only record of which
     * `share/<id>/<n>` keys to delete, so they are the ledger [eraseOwnerBytes] works from.
     *
     * @return 0: nothing is dropped yet; [eraseOwnerBytes] reports what goes.
     */
    override fun eraseOwner(ownerId: String): Int {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE share_record SET revoked_at = COALESCE(revoked_at, ?) WHERE owner_id = ?",
            ).use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, ownerId)
                ps.executeUpdate()
            }
        }
        return 0
    }

    /**
     * Account closure, phase 2 (F12): delete each share's chunk bytes, then its row. Per share, bytes
     * first, so a throw leaves the row (and with it the keys) for the next run.
     *
     * `bytesFreed` sums the stored chunk ciphertext lengths, read from the [BlobStore] before each
     * delete — since F3 the row holds no declared size, and the ciphertext is what the relay held anyway.
     */
    override fun eraseOwnerBytes(ownerId: String): org.tenshu.sync.relay.mailbox.ContentBlobRegistry.ReclaimResult {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        val shares = db.withConnection { conn ->
            conn.prepareStatement("SELECT share_id, chunk_count FROM share_record WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
                }
            }
        }
        var chunks = 0
        var bytes = 0L
        for ((shareId, chunkCount) in shares) {
            val keys = (0 until chunkCount).map { i -> chunkKey(shareId, i) }
            val shareBytes = keys.sumOf { blobs.sizeOf(it) ?: 0L }
            blobs.deleteAll(keys)
            db.withConnection { conn ->
                conn.prepareStatement("DELETE FROM share_record WHERE share_id = ? AND owner_id = ?").use { ps ->
                    ps.setString(1, shareId)
                    ps.setString(2, ownerId)
                    ps.executeUpdate()
                }
            }
            chunks += chunkCount
            bytes += shareBytes
        }
        return org.tenshu.sync.relay.mailbox.ContentBlobRegistry.ReclaimResult(blobsDropped = chunks, bytesFreed = bytes)
    }

    /**
     * Operator takedown. The revoke and the audit row commit as ONE transaction, so a takedown is
     * never on record without the link being dead, nor dead without the record. The chunk bytes go
     * after the commit (a [BlobStore] cannot join the transaction): a throw there leaves the link dead
     * and the keys for a repeat call — or the share GC, one retention window on — to delete.
     *
     * ⚠ `share_takedown` is append-only (triggers refuse UPDATE/DELETE), and account closure keeps it
     * (`AccountErasure.KEPT_TABLES`): it is the operator's record of having acted on a notice.
     */
    override fun takeDown(
        shareId: String,
        reason: TakedownReason,
        operatorNote: String?,
        nowMs: Long,
    ): TakedownResult {
        val (row, chunkCount) = db.inTransaction { conn ->
            val record = selectRecord(conn, shareId)
            val priorOwner: String? = conn.prepareStatement(
                "SELECT owner_id FROM share_takedown WHERE share_id = ? AND outcome = ? ORDER BY id LIMIT 1",
            ).use { ps ->
                ps.setString(1, shareId)
                ps.setString(2, TakedownOutcome.TAKEN_DOWN.code)
                ps.executeQuery().use { rs -> if (rs.next()) (rs.getString(1) ?: "") else null }
            }
            if (record != null) {
                conn.prepareStatement("UPDATE share_record SET revoked_at = COALESCE(revoked_at, ?) WHERE share_id = ?")
                    .use { ps -> ps.setLong(1, nowMs); ps.setString(2, shareId); ps.executeUpdate() }
            }
            val outcome = when {
                priorOwner != null -> TakedownOutcome.ALREADY_TAKEN_DOWN
                record == null -> TakedownOutcome.NOT_FOUND
                else -> TakedownOutcome.TAKEN_DOWN
            }
            val ownerId = record?.ownerId ?: priorOwner?.takeIf { it.isNotEmpty() }
            val id = conn.prepareStatement(
                """
                INSERT INTO share_takedown(share_id, owner_id, taken_down_at, reason_code, operator_note, outcome)
                VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, shareId)
                if (ownerId == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, ownerId)
                ps.setLong(3, nowMs)
                ps.setString(4, reason.code)
                if (operatorNote == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, operatorNote)
                ps.setString(6, outcome.code)
                ps.executeUpdate()
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
                }
            }
            TakedownRecord(id, shareId, ownerId, nowMs, reason, operatorNote, outcome) to record?.chunkCount
        }
        if (chunkCount == null) return TakedownResult(row, bytesDeleted = 0L, bytesDeleteCompleted = true)
        return try {
            val keys = (0 until chunkCount).map { i -> chunkKey(shareId, i) }
            val bytes = keys.sumOf { blobs.sizeOf(it) ?: 0L }
            blobs.deleteAll(keys)
            TakedownResult(row, bytesDeleted = bytes, bytesDeleteCompleted = true)
        } catch (e: Exception) {
            // No id in the line (LogStatementPiiGuardTest); the operator has it in the answer.
            System.err.println("share takedown: chunk delete failed, link is dead, repeat the call: ${e::class.simpleName}: ${e.message}")
            TakedownResult(row, bytesDeleted = 0L, bytesDeleteCompleted = false)
        }
    }

    override fun takedowns(shareId: String): List<TakedownRecord> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM share_takedown WHERE share_id = ? ORDER BY id").use { ps ->
                ps.setString(1, shareId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                TakedownRecord(
                                    id = rs.getLong("id"),
                                    shareId = rs.getString("share_id"),
                                    ownerId = rs.getString("owner_id"),
                                    takenDownAtMs = rs.getLong("taken_down_at"),
                                    reason = TakedownReason.stored(rs.getString("reason_code")),
                                    operatorNote = rs.getString("operator_note"),
                                    outcome = TakedownOutcome.fromCode(rs.getString("outcome")),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** Mirrors `ShareStore.isCollectible`: revoked → expired → orphan (no chunk 0), each + window. */
    private fun isCollectible(
        shareId: String,
        revokedAt: Long?,
        expiresAt: Long?,
        createdAt: Long,
        nowMs: Long,
    ): Boolean {
        if (revokedAt != null) return nowMs - revokedAt > revokedRetentionWindowMs
        if (expiresAt != null) return nowMs - expiresAt > revokedRetentionWindowMs
        // Orphan: never got chunk 0, and older than one window.
        return !blobs.exists(chunkKey(shareId, 0)) && nowMs - createdAt > revokedRetentionWindowMs
    }

    private fun selectRecord(conn: Connection, shareId: String): ShareStore.ShareRecord? =
        conn.prepareStatement("SELECT * FROM share_record WHERE share_id = ?").use { ps ->
            ps.setString(1, shareId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    private fun ResultSet.toRecord() = ShareStore.ShareRecord(
        shareId = getString("share_id"),
        ownerId = getString("owner_id"),
        sealedMeta = getBytes("sealed_meta") ?: ByteArray(0),
        chunkCount = getInt("chunk_count"),
        chunkHashes = decodeHashes(getBytes("chunk_hashes")),
        expiresAtMillis = getLongOrNull("expires_at"),
        maxOpens = getLongOrNull("max_opens")?.toInt(),
        createdAtMs = getLong("created_at"),
        opensSoFar = getInt("opens_so_far"),
        revokedAtMs = getLongOrNull("revoked_at"),
    )

    private fun ResultSet.getLongOrNull(column: String): Long? {
        val v = getLong(column)
        return if (wasNull()) null else v
    }

    private fun setNullableLong(ps: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setLong(index, value)
    }

    // chunk_hashes: List<ByteArray> ↔ length-framed BLOB (count, then per-hash len+bytes).
    private fun encodeHashes(hashes: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(hashes.size)
            for (h in hashes) { d.writeInt(h.size); d.write(h) }
        }
        return out.toByteArray()
    }

    private fun decodeHashes(bytes: ByteArray): List<ByteArray> =
        DataInputStream(bytes.inputStream()).use { d ->
            List(d.readInt()) { ByteArray(d.readInt()).also { d.readFully(it) } }
        }
}
