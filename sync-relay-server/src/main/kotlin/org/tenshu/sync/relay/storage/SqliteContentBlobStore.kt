package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry

/**
 * Durable [ContentBlobRegistry]. Splits storage by size: the multi-MB opaque
 * **ciphertext** chunk bodies go in a [BlobStore], while SQLite holds only
 * the `(ownerId, ref) → last_referenced_at` index that drives the retention/orphan GC. The
 * relay never decrypts (House Rule #1).
 *
 * Liveness/GC semantics match the in-memory backend: every put/get refreshes
 * `last_referenced_at`; a blob is collectible once untouched for
 * [contentRetentionWindowMs] (`Long.MAX_VALUE` disables GC).
 */
class SqliteContentBlobStore(
    private val db: RelayDatabase,
    private val blobs: BlobStore,
    private val contentRetentionWindowMs: Long = RelayConfig.CONTENT_RETENTION_WINDOW_MS,
) : ContentBlobRegistry {

    private fun blobKey(ownerId: String, ref: String): String = "$ownerId/$ref"

    override fun put(ownerId: String, ref: String, ciphertext: ByteArray, nowMs: Long) {
        // Bytes first (idempotent overwrite — content-addressed), then the index. A crash
        // between leaves at worst an orphan file (never an index row without bytes). ⚠ Nothing
        // reclaims it: `BlobStore` has no enumeration call by design (GC works off the SQL
        // index), so this is a permanent, bounded leak rather than a deferred cleanup.
        // ⚠⚠ Both under the lock, as in [putWithinQuota]: `ContentReclaimJob.drain` deletes bytes
        // while HOLDING it, so a PUT whose bytes landed outside it could be deleted by a drain that
        // then lets this index row commit — a row with no bytes behind it.
        db.withConnection { conn ->
            dequeue(conn, ownerId, ref)
            blobs.put(blobKey(ownerId, ref), ciphertext)
            upsertIndex(conn, ownerId, ref, ciphertext.size.toLong(), nowMs)
        }
    }

    override fun putWithinQuota(
        ownerId: String,
        ref: String,
        ciphertext: ByteArray,
        quotaBytes: Long,
        nowMs: Long,
    ): ContentBlobRegistry.PutResult {
        return db.withConnection { conn ->
            // Whole check-and-store runs under the single write lock, so no concurrent PUT/GC can
            // change the owner's total between the SUM and the upsert — the durable backend is
            // race-free where the in-memory one tolerates a bounded overshoot.
            val storedLength = storedLength(conn, ownerId, ref)
            // A new ref, or a re-PUT that grows one, is checked on its growth; a re-PUT at no growth
            // is a retry or an in-place re-seal and always lands (see the registry's KDoc).
            if (storedLength == null || ciphertext.size > storedLength) {
                val used = sumBytes(conn, ownerId) - (storedLength ?: 0L)
                if (used + ciphertext.size > quotaBytes) {
                    // Reject BEFORE writing anything — an over-quota PUT must leave nothing
                    // retrievable. (get() serves bytes straight from the BlobStore, so writing them
                    // speculatively would expose an unindexed, uncounted, un-GC'd orphan.)
                    return@withConnection ContentBlobRegistry.PutResult.OVER_QUOTA
                }
            }
            // Within quota (or an idempotent re-PUT): bytes first then index, as in put() — a crash
            // between leaves at worst an orphan file, which nothing reclaims (see put()).
            dequeue(conn, ownerId, ref)
            blobs.put(blobKey(ownerId, ref), ciphertext)
            upsertIndex(conn, ownerId, ref, ciphertext.size.toLong(), nowMs)
            ContentBlobRegistry.PutResult.STORED
        }
    }

    override fun bytesUsed(ownerId: String): Long =
        db.withConnection { conn -> sumBytes(conn, ownerId) }

    override fun storedLength(ownerId: String, ref: String): Long? =
        db.withConnection { conn -> storedLength(conn, ownerId, ref) }

    private fun storedLength(conn: java.sql.Connection, ownerId: String, ref: String): Long? =
        conn.prepareStatement("SELECT byte_len FROM content_blob WHERE owner_id = ? AND ref = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, ref)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    override fun totalBytes(): Long =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COALESCE(SUM(byte_len), 0) FROM content_blob").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    override fun blobIndex(ownerId: String): Map<String, ContentBlobRegistry.BlobEntry> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT ref, byte_len, last_referenced_at FROM content_blob WHERE owner_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(rs.getString(1), ContentBlobRegistry.BlobEntry(rs.getLong(2), rs.getLong(3)))
                        }
                    }
                }
            }
        }

    override fun lastReferencedAt(ownerId: String, refs: Collection<String>): Map<String, Long> {
        if (ownerId.isBlank() || refs.isEmpty()) return emptyMap()
        // ⚠ Chunked rather than one giant `IN (...)`: SQLite's default parameter ceiling is 999, and
        // a page of content objects can name more refs than that — the same bound
        // `SqliteContentRefStore.referencedRefs` works under.
        return refs.distinct().chunked(500).flatMap { batch ->
            db.withConnection { conn ->
                val placeholders = batch.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT ref, last_referenced_at FROM content_blob " +
                        "WHERE owner_id = ? AND ref IN ($placeholders)",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    batch.forEachIndexed { i, ref -> ps.setString(i + 2, ref) }
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) }
                    }
                }
            }
        }.toMap()
    }

    private fun sumBytes(conn: java.sql.Connection, ownerId: String): Long =
        conn.prepareStatement("SELECT COALESCE(SUM(byte_len), 0) FROM content_blob WHERE owner_id = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    /**
     * ⚠⚠ Take [ref] out of the deletion queue (`ContentReclaimJob`). Called under the store lock the
     * drain holds, and **before anything that can fail**: a PUT before its byte write, a GET before its
     * read. A process that dies after this call leaves a ref that is simply not queued any more,
     * the safe direction. One that dies after a byte write but before this call would
     * leave the fresh bytes queued for deletion.
     */
    private fun dequeue(conn: java.sql.Connection, ownerId: String, ref: String) {
        conn.prepareStatement("DELETE FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, ref)
            ps.executeUpdate()
        }
    }

    private fun upsertIndex(conn: java.sql.Connection, ownerId: String, ref: String, byteLen: Long, nowMs: Long) {
        // byte_len is content-addressed (ref = sha256 of ciphertext) so a re-PUT carries the same
        // length; refreshing it on conflict is a harmless idempotent write.
        conn.prepareStatement(
            """
            INSERT INTO content_blob(owner_id, ref, last_referenced_at, byte_len) VALUES(?, ?, ?, ?)
            ON CONFLICT(owner_id, ref) DO UPDATE SET
                last_referenced_at = excluded.last_referenced_at,
                byte_len = excluded.byte_len
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, ref)
            ps.setLong(3, nowMs)
            ps.setLong(4, byteLen)
            ps.executeUpdate()
        }
    }

    override fun get(ownerId: String, ref: String, nowMs: Long): ByteArray? {
        // ⚠⚠ A read proves use. So BEFORE reading, and in ONE lock section, it takes the ref out of the
        // deletion queue AND refreshes its timestamp. Two sections, or a refresh only
        // after the read, would leave a gap in which an `enqueue` from a plan taken earlier still sees the
        // old timestamp, re-queues the ref, and a drain deletes bytes this call has just served. Holding
        // the lock across the byte read itself would serialise every download. After this section, any
        // later plan sees the ref as recently touched, and any earlier plan's digest no longer matches.
        val restoreLen = db.withConnection { conn ->
            // ⚠ A queued ref whose index row a drain already dropped (its byte delete failed) has the queue
            // row as its ONLY record, carrying its byte count. Read it before the dequeue,
            // so a read that finds the bytes can restore the index rather than leave them in neither table.
            val queuedLen = conn.prepareStatement(
                "SELECT byte_len FROM content_free_queue WHERE owner_id = ? AND ref = ?"
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, ref)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
            dequeue(conn, ownerId, ref)
            val refreshed = conn.prepareStatement(
                "UPDATE content_blob SET last_referenced_at = ? WHERE owner_id = ? AND ref = ?"
            ).use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, ownerId)
                ps.setString(3, ref)
                ps.executeUpdate()
            }
            if (refreshed == 0) queuedLen else null
        }
        val bytes = blobs.get(blobKey(ownerId, ref)) ?: return null
        // The bytes survived a failed delete, and nothing else names them now: put the index back.
        if (restoreLen != null) db.withConnection { conn -> upsertIndex(conn, ownerId, ref, restoreLen, nowMs) }
        return bytes
    }

    override fun gcSweep(nowMs: Long): Int {
        if (contentRetentionWindowMs == Long.MAX_VALUE) return 0
        val threshold = nowMs - contentRetentionWindowMs
        // SELECT + DELETE under one held lock → atomic: put/get can't refresh between them
        // (they need the same lock), so a blob touched after the SELECT predicate is safe.
        val dropped = db.withConnection { conn ->
            val refs = conn.prepareStatement(
                "SELECT owner_id, ref FROM content_blob WHERE last_referenced_at < ?"
            ).use { ps ->
                ps.setLong(1, threshold)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            }
            conn.prepareStatement("DELETE FROM content_blob WHERE last_referenced_at < ?").use { ps ->
                ps.setLong(1, threshold)
                ps.executeUpdate()
            }
            refs
        }
        blobs.deleteAll(dropped.map { (ownerId, ref) -> blobKey(ownerId, ref) })
        return dropped.size
    }

    /**
     * Content-keyspace reclamation (Face A / D4) — see the SPI KDoc for the safety notes. Account
     * closure does not call this directly: `AccountErasure` runs [eraseOwner] inside its row
     * transaction and [eraseOwnerBytes] after it.
     *
     * It is exactly those two phases (F12), so it inherits their crash safety. ⚠⚠ Deleting the index
     * rows and *then* the bytes with no ledger in between would leave, on a throw, bodies nothing
     * would ever name again (`BlobStore` cannot be listed by owner).
     */
    override fun reclaimOwner(ownerId: String): ContentBlobRegistry.ReclaimResult {
        require(ownerId.isNotBlank()) { "reclaimOwner requires a non-blank ownerId." }
        db.inTransaction { eraseOwner(ownerId) }
        return eraseOwnerBytes(ownerId)
    }

    /**
     * Account closure, phase 1 (F12): move every `content_blob` index row of [ownerId] into
     * `content_free_queue` — the durable deletion ledger `ContentReclaimJob` already drains — and drop
     * the index rows. From the commit on, nothing serves or counts these blobs.
     *
     * ⚠⚠ The ledger is what makes closure crash-safe: deleting the index and *then* the bytes would
     * leave, on a throw between them, bytes nothing names, for ever. Here a throw in
     * [eraseOwnerBytes] leaves queue rows, and the next closure post finishes them.
     * `byte_len` is copied (as the reclaim job does) so the drain can still say what it freed.
     */
    override fun eraseOwner(ownerId: String): Int {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO content_free_queue(owner_id, ref, enqueued_at, byte_len)
                SELECT owner_id, ref, ?, byte_len FROM content_blob WHERE owner_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, ownerId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM content_blob WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Account closure, phase 2 (F12): delete the bytes of every queued ref of [ownerId], then its queue
     * row, in batches.
     *
     * ⚠⚠ Each batch holds the store lock across its byte delete, exactly as `ContentReclaimJob.drain`
     * does: [put] and [get] take a ref out of the queue under the same lock before touching bytes, so a
     * PUT (a re-created realm uploading again) either lands before the batch — its ref leaves the
     * queue and is spared — or after it, onto a clean slate. A batch is one S3 `DeleteObjects` call.
     *
     * ⚠ Also drains refs a sweep free or an operator enqueue had already queued for this owner: they were
     * committed to deletion anyway, and the owner is going.
     */
    override fun eraseOwnerBytes(ownerId: String): ContentBlobRegistry.ReclaimResult {
        org.tenshu.sync.relay.mailbox.requireErasableOwner(ownerId)
        var dropped = 0
        var freed = 0L
        while (true) {
            val progressed = db.withConnection { conn ->
                val batch = conn.prepareStatement(
                    "SELECT ref, byte_len FROM content_free_queue WHERE owner_id = ? ORDER BY ref LIMIT ?",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    ps.setInt(2, ERASE_BATCH)
                    ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
                }
                if (batch.isEmpty()) return@withConnection false
                // Bytes first: a throw here leaves the queue rows, which are the only record of the bytes.
                blobs.deleteAll(batch.keys.map { ref -> blobKey(ownerId, ref) })
                blobs.deleteByPrefix("$ownerId/")
                conn.prepareStatement("DELETE FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
                    for (ref in batch.keys) {
                        ps.setString(1, ownerId)
                        ps.setString(2, ref)
                        ps.executeUpdate()
                    }
                }
                dropped += batch.size
                freed += batch.values.sum()
                true
            }
            if (!progressed) break
        }
        return ContentBlobRegistry.ReclaimResult(blobsDropped = dropped, bytesFreed = freed)
    }

    override fun dropRefs(
        ownerId: String,
        refs: Collection<String>,
    ): ContentBlobRegistry.ReclaimResult {
        require(ownerId.isNotBlank()) { "dropRefs requires a non-blank ownerId." }
        if (refs.isEmpty()) return ContentBlobRegistry.ReclaimResult.NOTHING
        // Distinct so a caller repeating a ref cannot double-count what it freed.
        val wanted = refs.toSet()
        val (dropped, bytes) = db.withConnection { conn ->
            var freed = 0L
            val gone = mutableListOf<String>()
            // ⚠ Sized per ref rather than by one SUM over the owner: the owner may be uploading
            // concurrently, so a before/after difference would attribute someone else's writes to
            // this reclamation. Measured inside the same held connection as the delete.
            conn.prepareStatement(
                "SELECT byte_len FROM content_blob WHERE owner_id = ? AND ref = ?"
            ).use { ps ->
                for (ref in wanted) {
                    ps.setString(1, ownerId)
                    ps.setString(2, ref)
                    ps.executeQuery().use { rs -> if (rs.next()) { gone += ref; freed += rs.getLong(1) } }
                }
            }
            conn.prepareStatement("DELETE FROM content_blob WHERE owner_id = ? AND ref = ?").use { ps ->
                for (ref in wanted) {
                    ps.setString(1, ownerId)
                    ps.setString(2, ref)
                    ps.executeUpdate()
                }
            }
            gone to freed
        }
        blobs.deleteAll(dropped.map { ref -> blobKey(ownerId, ref) })
        return ContentBlobRegistry.ReclaimResult(blobsDropped = dropped.size, bytesFreed = bytes)
    }

    override fun blobCount(ownerId: String): Int =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM content_blob WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    private companion object {
        /**
         * Refs per held lock in [eraseOwnerBytes]. S3's `DeleteObjects` ceiling, so one batch is one
         * request; a PUT waits at most that long.
         */
        const val ERASE_BATCH = 1_000
    }
}
