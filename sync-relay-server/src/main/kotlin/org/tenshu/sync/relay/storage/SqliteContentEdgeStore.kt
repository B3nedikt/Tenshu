package org.tenshu.sync.relay.storage

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.edgeWriteApplies
import java.sql.Connection

/**
 * SQLite [ContentEdgeRegistry] — `content_edge` (one register per `(owner, holder, property)`) and
 * `holder_watermark`, both from migration 22.
 *
 * ⚠⚠ Stamps are compared **decoded**, through [HlcStamp]'s `compareHlc`, never as strings and never
 * in SQL: the encoding is `physical:counter:device`, whose lexical order is not the HLC order. So
 * each rule reads the rows it may change, decides in Kotlin, and writes — inside one transaction.
 *
 * ⚠ A stored stamp that does not decode cannot arise from this class (it only writes [HlcStamp.encode]);
 * if one ever did, it is treated as the lowest possible stamp, so the next real write replaces it.
 */
class SqliteContentEdgeStore(
    private val db: RelayDatabase,
    /** The relay's clock for B5r's `content_unreferenced.since`; a test passes its own. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentEdgeRegistry {

    override fun <T> atomically(block: () -> T): T = db.inTransaction { block() }

    override fun writeEdges(
        ownerId: String,
        holderHash: String,
        stamp: HlcStamp,
        edges: List<ContentEdgeRegistry.EdgeWrite>,
    ): Int {
        if (ownerId.isBlank() || holderHash.isBlank() || edges.isEmpty()) return 0
        return db.inTransaction { conn ->
            val watermark = watermarkIn(conn, ownerId, holderHash)
            var changed = 0
            val lost = mutableSetOf<String>()
            for (edge in edges) {
                if (edge.propertyId.isBlank()) continue
                val existing = registerIn(conn, ownerId, holderHash, edge.propertyId)
                if (!edgeWriteApplies(existing?.stamp, watermark, stamp)) continue
                val named = edge.contentId?.takeIf { it.isNotBlank() }
                upsertRegister(conn, ownerId, holderHash, edge.propertyId, named, stamp)
                existing?.contentId?.takeIf { it != named }?.let { lost += it }
                // B5r: a live edge names it, so it is not unreferenced (a no-op for the usual live content).
                if (named != null) deleteClockRow(conn, ownerId, named)
                changed++
            }
            stampOneToZero(conn, ownerId, lost)
            changed
        }
    }

    override fun removeHolders(ownerId: String, holderHashes: Collection<String>, at: HlcStamp): Int {
        val holders = holderHashes.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (ownerId.isBlank() || holders.isEmpty()) return 0
        return db.inTransaction { conn ->
            var cleared = 0
            val lost = mutableSetOf<String>()
            for (holderHash in holders) {
                val previous = watermarkIn(conn, ownerId, holderHash)
                if (previous == null || at > previous) {
                    conn.prepareStatement(
                        """
                        INSERT INTO holder_watermark(owner_id, holder_hash, stamp) VALUES(?, ?, ?)
                        ON CONFLICT(owner_id, holder_hash) DO UPDATE SET stamp = excluded.stamp
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, ownerId)
                        ps.setString(2, holderHash)
                        ps.setString(3, at.encode())
                        ps.executeUpdate()
                    }
                }
                for ((propertyId, register) in registersOf(conn, ownerId, holderHash)) {
                    // ⚠ A register stamped ABOVE the removal is a recreate that outlived it: kept.
                    if (register.stamp > at) continue
                    if (register.contentId != null) {
                        cleared++
                        lost += register.contentId
                    }
                    upsertRegister(conn, ownerId, holderHash, propertyId, contentId = null, stamp = at)
                }
            }
            stampOneToZero(conn, ownerId, lost)
            cleared
        }
    }

    override fun register(ownerId: String, holderHash: String, propertyId: String): ContentEdgeRegistry.Register? =
        db.withConnection { conn -> registerIn(conn, ownerId, holderHash, propertyId) }

    override fun watermark(ownerId: String, holderHash: String): HlcStamp? =
        db.withConnection { conn -> watermarkIn(conn, ownerId, holderHash) }

    // ⚠⚠ Rule 3: `content_id IS NOT NULL` on every count and enumeration — a none register is a
    // stamp, not an edge.
    override fun referencedContentIds(ownerId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT DISTINCT content_id FROM content_edge WHERE owner_id = ? AND content_id IS NOT NULL",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override fun holdersOf(ownerId: String, contentId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT holder_hash FROM content_edge WHERE owner_id = ? AND content_id = ? AND content_id IS NOT NULL",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override fun unreferencedSince(ownerId: String, contentId: String): Long? =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT since FROM content_unreferenced WHERE owner_id = ? AND content_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }

    override fun unreferencedContents(ownerId: String): Map<String, Long> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT content_id, since FROM content_unreferenced WHERE owner_id = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
            }
        }

    override fun expiredUnreferenced(cutoffMs: Long, limit: Int): List<ContentEdgeRegistry.Unreferenced> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT owner_id, content_id, since FROM content_unreferenced
                WHERE since <= ? ORDER BY since, owner_id, content_id LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, cutoffMs)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(ContentEdgeRegistry.Unreferenced(rs.getString(1), rs.getString(2), rs.getLong(3)))
                    }
                }
            }
        }

    override fun unreferencedCount(): Int =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM content_unreferenced").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    override fun clearUnreferenced(ownerId: String, contentId: String): Boolean =
        db.withConnection { conn -> deleteClockRow(conn, ownerId, contentId) > 0 }

    override fun startClockIfUnreferenced(ownerId: String, contentId: String, nowMs: Long): Boolean {
        if (ownerId.isBlank() || contentId.isBlank()) return false
        return db.inTransaction { conn ->
            if (hasLiveEdge(conn, ownerId, contentId)) return@inTransaction false
            insertClockRow(conn, ownerId, contentId, nowMs) > 0
        }
    }

    /**
     * B5r §1.1: each of [contentIds] lost a live edge in this transaction. "Had a live holder before, has
     * none now" — the before is given (the register named it), so only the after is read.
     */
    private fun stampOneToZero(conn: Connection, ownerId: String, contentIds: Collection<String>) {
        if (contentIds.isEmpty()) return
        val now = clock()
        for (contentId in contentIds) {
            if (!hasLiveEdge(conn, ownerId, contentId)) insertClockRow(conn, ownerId, contentId, now)
        }
    }

    // ⚠ Rule 3 by construction: `content_id = ?` never matches a none register's NULL.
    private fun hasLiveEdge(conn: Connection, ownerId: String, contentId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM content_edge WHERE owner_id = ? AND content_id = ? LIMIT 1").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, contentId)
            ps.executeQuery().use { it.next() }
        }

    /** ⚠ OR IGNORE: an existing row keeps its earlier `since`, so re-stamping never extends T. */
    private fun insertClockRow(conn: Connection, ownerId: String, contentId: String, since: Long): Int =
        conn.prepareStatement(
            "INSERT OR IGNORE INTO content_unreferenced(owner_id, content_id, since) VALUES(?, ?, ?)",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, contentId)
            ps.setLong(3, since)
            ps.executeUpdate()
        }

    private fun deleteClockRow(conn: Connection, ownerId: String, contentId: String): Int =
        conn.prepareStatement("DELETE FROM content_unreferenced WHERE owner_id = ? AND content_id = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, contentId)
            ps.executeUpdate()
        }

    private fun registerIn(conn: Connection, ownerId: String, holderHash: String, propertyId: String): ContentEdgeRegistry.Register? =
        conn.prepareStatement(
            "SELECT content_id, stamp FROM content_edge WHERE owner_id = ? AND holder_hash = ? AND property_id = ?",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, holderHash)
            ps.setString(3, propertyId)
            ps.executeQuery().use { rs ->
                if (rs.next()) ContentEdgeRegistry.Register(rs.getString(1), decodeStored(rs.getString(2))) else null
            }
        }

    private fun registersOf(conn: Connection, ownerId: String, holderHash: String): List<Pair<String, ContentEdgeRegistry.Register>> =
        conn.prepareStatement(
            "SELECT property_id, content_id, stamp FROM content_edge WHERE owner_id = ? AND holder_hash = ?",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, holderHash)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getString(1) to ContentEdgeRegistry.Register(rs.getString(2), decodeStored(rs.getString(3))))
                    }
                }
            }
        }

    private fun watermarkIn(conn: Connection, ownerId: String, holderHash: String): HlcStamp? =
        conn.prepareStatement(
            "SELECT stamp FROM holder_watermark WHERE owner_id = ? AND holder_hash = ?",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, holderHash)
            ps.executeQuery().use { rs -> if (rs.next()) decodeStored(rs.getString(1)) else null }
        }

    private fun upsertRegister(
        conn: Connection,
        ownerId: String,
        holderHash: String,
        propertyId: String,
        contentId: String?,
        stamp: HlcStamp,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO content_edge(owner_id, holder_hash, property_id, content_id, stamp)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(owner_id, holder_hash, property_id)
                DO UPDATE SET content_id = excluded.content_id, stamp = excluded.stamp
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, holderHash)
            ps.setString(3, propertyId)
            ps.setString(4, contentId)
            ps.setString(5, stamp.encode())
            ps.executeUpdate()
        }
    }

    /** Account closure (F12): edge registers, holder watermarks and the G2 clock rows. */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(
        ownerId,
        "content_edge" to "owner_id",
        "holder_watermark" to "owner_id",
        "content_unreferenced" to "owner_id",
    )

    private fun decodeStored(raw: String?): HlcStamp = decodeHlcStamp(raw) ?: LOWEST_STAMP

    private companion object {
        /** Below every real stamp: physical time is epoch millis, so never negative. */
        val LOWEST_STAMP = HlcStamp(Long.MIN_VALUE, Long.MIN_VALUE, "")
    }
}
