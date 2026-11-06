package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import java.sql.Connection

/**
 * SQLite [ContentFreeing] over `content_freed` (migration 23), reading `content_edge`, `content_ref`,
 * `content_blob` and `content_free_queue` on the same [db].
 *
 * ⚠⚠ Must share its [RelayDatabase] with the mailbox and the edge, ref and blob stores — as `main` wires
 * them — or [atomically] is not the push's transaction and the free cannot serialise against a push.
 *
 * ⚠ [refs] is the describe's store, so a revive writes `content_ref` exactly as a describe does; its
 * `declareRefs` nests inside an outer [RelayDatabase.inTransaction] rather than committing it.
 */
class SqliteContentFreedStore(
    private val db: RelayDatabase,
    private val refs: ContentRefRegistry,
    /** B5r: the clock a revive restarts the G2 clock with. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentFreeing {

    override fun <T> atomically(block: () -> T): T = db.inTransaction { block() }

    override fun free(ownerId: String, contentId: String, nowMs: Long): ContentFreeing.FreeResult {
        require(ownerId.isNotBlank() && contentId.isNotBlank()) { "free requires an owner and a content id" }
        return db.inTransaction { conn ->
            // 1. ⚠⚠ Zero LIVE edges (rule 3: a none register is a stamp, not an edge).
            val live = conn.prepareStatement(
                "SELECT 1 FROM content_edge WHERE owner_id = ? AND content_id = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeQuery().use { it.next() }
            }
            if (live) return@inTransaction ContentFreeing.FreeResult.NOT_FREED

            // 2. Queue every ref of the content, with its byte count from the blob index (0 when the index
            // has no row: the drain then reports 0 freed for it, and deletes whatever bytes exist).
            // ⚠ OR IGNORE: a ref already queued (an operator enqueue, or a second content sharing the
            // chunk) keeps its original enqueue time.
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO content_free_queue(owner_id, ref, enqueued_at, byte_len)
                SELECT r.owner_id, r.ref, ?, COALESCE(b.byte_len, 0)
                FROM content_ref r
                LEFT JOIN content_blob b ON b.owner_id = r.owner_id AND b.ref = r.ref
                WHERE r.owner_id = ? AND r.content_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, ownerId)
                ps.setString(3, contentId)
                ps.executeUpdate()
            }
            // ⚠ This delete is what lets `ContentReclaimJob.drain` take the bytes: it spares described refs
            // and the derived manifest of any content with an edge or a ref row, and after this there is
            // neither. (A third table, `content_reference`, held N1's handles until CR-2 B6 dropped it.)
            deleteFor(conn, "content_ref", ownerId, contentId)
            // B5r: it leaves UNREFERENCED for FREED, so the clock row goes (spec §1.1).
            deleteFor(conn, "content_unreferenced", ownerId, contentId)
            // 4. ⚠ Even when step 2 moved nothing (PENDING content), so the late describe is refused.
            conn.prepareStatement(
                "INSERT OR IGNORE INTO content_freed(owner_id, content_id, freed_at) VALUES(?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.setLong(3, nowMs)
                ps.executeUpdate()
            }
            ContentFreeing.FreeResult.FREED
        }
    }

    override fun freedAmong(ownerId: String, contentIds: Collection<String>): Set<String> {
        if (ownerId.isBlank() || contentIds.isEmpty()) return emptySet()
        val distinct = contentIds.distinct()
        val found = mutableSetOf<String>()
        // ⚠ Chunked for SQLite's 999-parameter ceiling, as every IN-list in the relay is.
        db.withConnection { conn ->
            distinct.chunked(500).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT content_id FROM content_freed WHERE owner_id = ? AND content_id IN ($placeholders)",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    batch.forEachIndexed { i, id -> ps.setString(i + 2, id) }
                    ps.executeQuery().use { rs -> while (rs.next()) found += rs.getString(1) }
                }
            }
        }
        // Request order, which is the order the 409 lists them in.
        return distinct.filterTo(linkedSetOf()) { it in found }
    }

    override fun unfree(ownerId: String, contentId: String): Boolean =
        db.withConnection { conn -> deleteFor(conn, "content_freed", ownerId, contentId) > 0 }

    override fun unrevivableRefs(ownerId: String, refs: Collection<String>): Set<String> {
        if (refs.isEmpty()) return emptySet()
        val distinct = refs.distinct()
        val usable = mutableSetOf<String>()
        db.withConnection { conn ->
            distinct.chunked(500).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                // ⚠⚠ Present AND not queued. A ref still in the queue was not PUT since the free (a PUT
                // dequeues it under this lock), so the drain may delete it after this push commits.
                conn.prepareStatement(
                    """
                    SELECT b.ref FROM content_blob b
                    WHERE b.owner_id = ? AND b.ref IN ($placeholders)
                      AND NOT EXISTS (SELECT 1 FROM content_free_queue q WHERE q.owner_id = b.owner_id AND q.ref = b.ref)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, ownerId)
                    batch.forEachIndexed { i, r -> ps.setString(i + 2, r) }
                    ps.executeQuery().use { rs -> while (rs.next()) usable += rs.getString(1) }
                }
            }
        }
        return distinct.filterNotTo(linkedSetOf()) { it in usable }
    }

    override fun revive(ownerId: String, contentId: String, refs: Collection<String>) {
        db.inTransaction { conn ->
            this.refs.declareRefs(ownerId, contentId, refs)
            deleteFor(conn, "content_freed", ownerId, contentId)
            // ⚠⚠ B5r: a revive makes the content DESCRIBED again, and FREED cleared its clock row. If the
            // content has no live edge now, its clock starts — the push's own edge write, later in this
            // same transaction, clears it again. What this closes is that edge losing LWW (a stale stamp,
            // a holder watermark above it): without it the revived content would be described, edgeless
            // and off the clock, a leak no later one-to-zero transition could ever end (spec §6).
            if (!hasLiveEdge(conn, ownerId, contentId)) {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO content_unreferenced(owner_id, content_id, since) VALUES(?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    ps.setString(2, contentId)
                    ps.setLong(3, clock())
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun seedClockOnce(nowMs: Long): Int? = db.inTransaction { conn ->
        if (seededAtIn(conn) != null) return@inTransaction null
        // D4: described (ref rows), zero LIVE edges (rule 3), no clock row yet (an earlier `since` stays).
        val seeded = conn.prepareStatement(
            """
            INSERT OR IGNORE INTO content_unreferenced(owner_id, content_id, since)
            SELECT DISTINCT r.owner_id, r.content_id, ? FROM content_ref r
            WHERE NOT EXISTS (
                SELECT 1 FROM content_edge e WHERE e.owner_id = r.owner_id AND e.content_id = r.content_id
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO content_sweep_state(id, seeded_at) VALUES(0, ?)").use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
        seeded
    }

    override fun seededAt(): Long? = db.withConnection { conn -> seededAtIn(conn) }

    override fun pendingSeedCount(): Int = db.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM (SELECT DISTINCT r.owner_id, r.content_id FROM content_ref r
            WHERE NOT EXISTS (SELECT 1 FROM content_edge e WHERE e.owner_id = r.owner_id AND e.content_id = r.content_id)
              AND NOT EXISTS (SELECT 1 FROM content_unreferenced u WHERE u.owner_id = r.owner_id AND u.content_id = r.content_id))
            """.trimIndent(),
        ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
    }

    override fun heldBytes(ownerId: String): Long = db.withConnection { conn -> heldBytesIn(conn, ownerId) }

    private fun seededAtIn(conn: Connection): Long? =
        conn.prepareStatement("SELECT seeded_at FROM content_sweep_state WHERE id = 0").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun hasLiveEdge(conn: Connection, ownerId: String, contentId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM content_edge WHERE owner_id = ? AND content_id = ? LIMIT 1").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, contentId)
            ps.executeQuery().use { it.next() }
        }

    companion object {
        /**
         * D5 (spec §1.4): the owner's indexed bytes whose ref is queued for deletion or described by an
         * UNREFERENCED content, **and** described by no content that is not UNREFERENCED. A ref shared
         * with live content stays charged, and so does a ref of content that never had an edge (G4).
         *
         * ⚠ Starts from the held CANDIDATES (the queue and the clock's refs), never from the owner's whole
         * blob index: armed, it runs on every metered PUT.
         */
        private fun heldBytesIn(conn: Connection, ownerId: String): Long =
            conn.prepareStatement(
                """
                SELECT COALESCE(SUM(b.byte_len), 0) FROM content_blob b
                WHERE b.owner_id = ?
                  AND b.ref IN (
                    SELECT q.ref FROM content_free_queue q WHERE q.owner_id = ?
                    UNION
                    SELECT r.ref FROM content_unreferenced u
                    JOIN content_ref r ON r.owner_id = u.owner_id AND r.content_id = u.content_id
                    WHERE u.owner_id = ?
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM content_ref r
                    WHERE r.owner_id = b.owner_id AND r.ref = b.ref
                      AND NOT EXISTS (
                        SELECT 1 FROM content_unreferenced u WHERE u.owner_id = r.owner_id AND u.content_id = r.content_id
                      )
                  )
                """.trimIndent(),
            ).use { ps ->
                repeat(3) { ps.setString(it + 1, ownerId) }
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
    }

    /**
     * Account closure (F12): the FREED set. Only `content_freed` — the refs and clock rows it reads belong
     * to the ref and edge stores, which erase their own.
     */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "content_freed" to "owner_id")

    private fun deleteFor(conn: Connection, table: String, ownerId: String, contentId: String): Int =
        conn.prepareStatement("DELETE FROM $table WHERE owner_id = ? AND content_id = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, contentId)
            ps.executeUpdate()
        }
}
