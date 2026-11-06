package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.ContentRefRegistry

/**
 * SQLite [ContentRefRegistry] — one row per `(owner, content, ref)`, the sibling of
 * [SqliteContentEdgeStore].
 */
class SqliteContentRefStore(
    private val db: RelayDatabase,
) : ContentRefRegistry {

    override fun declareRefs(ownerId: String, contentId: String, refs: Collection<String>) {
        if (ownerId.isBlank() || contentId.isBlank()) return
        val wanted = refs.filterTo(mutableSetOf()) { it.isNotBlank() }
        // ⚠⚠ Delete-then-insert inside ONE transaction (§7b.3 #6). Between the two statements the
        // content has no refs at all; a reader that saw that window would call every chunk an orphan.
        // The transaction is what makes the window unobservable, and the rollback is what makes a
        // failure leave the PREVIOUS set standing rather than none.
        // ⚠ Through `inTransaction`, so it NESTS (CR-2 B4r): inside a push's transaction (a revive) or
        // a whole describe request's, it joins the outer one instead of committing it early.
        db.inTransaction { conn ->
            conn.prepareStatement(
                "DELETE FROM content_ref WHERE owner_id = ? AND content_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeUpdate()
            }
            if (wanted.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO content_ref(owner_id, content_id, ref) VALUES(?, ?, ?)",
                ).use { ps ->
                    for (ref in wanted) {
                        ps.setString(1, ownerId)
                        ps.setString(2, contentId)
                        ps.setString(3, ref)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    override fun refsOf(ownerId: String, contentId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT ref FROM content_ref WHERE owner_id = ? AND content_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override fun declaredContentIds(ownerId: String): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT DISTINCT content_id FROM content_ref WHERE owner_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override fun describedOwnerIds(): Set<String> =
        db.withConnection { conn ->
            conn.prepareStatement("SELECT DISTINCT owner_id FROM content_ref").use { ps ->
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override fun refsByContent(ownerId: String, contentIds: Collection<String>): Map<String, Set<String>> {
        if (ownerId.isBlank() || contentIds.isEmpty()) return emptyMap()
        val grouped = mutableMapOf<String, MutableSet<String>>()
        // ⚠ Chunked for the 999-parameter ceiling, as `referencedRefs` is. Both columns are selected
        // because the grouping is the point — a union would answer a different question.
        contentIds.distinct().chunked(500).forEach { batch ->
            db.withConnection { conn ->
                val placeholders = batch.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT content_id, ref FROM content_ref " +
                        "WHERE owner_id = ? AND content_id IN ($placeholders)",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    batch.forEachIndexed { i, id -> ps.setString(i + 2, id) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            grouped.getOrPut(rs.getString(1)) { mutableSetOf() }.add(rs.getString(2))
                        }
                    }
                }
            }
        }
        return grouped
    }

    override fun referencedRefs(ownerId: String, contentIds: Collection<String>): Set<String> {
        if (contentIds.isEmpty()) return emptySet()
        // ⚠ Chunked rather than one giant `IN (...)`: SQLite's default parameter ceiling is 999,
        // and an owner's live content list is unbounded.
        return contentIds.distinct().chunked(500).flatMapTo(mutableSetOf()) { batch ->
            db.withConnection { conn ->
                val placeholders = batch.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT DISTINCT ref FROM content_ref WHERE owner_id = ? AND content_id IN ($placeholders)",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    batch.forEachIndexed { i, id -> ps.setString(i + 2, id) }
                    ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            }
        }
    }

    override fun forgetContent(ownerId: String, contentId: String): Int {
        if (ownerId.isBlank() || contentId.isBlank()) return 0
        return db.withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM content_ref WHERE owner_id = ? AND content_id = ?",
            ).use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, contentId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Account closure (F12): every description, as plain statements so it joins `AccountErasure`'s
     * transaction rather than committing on its own.
     */
    override fun eraseOwner(ownerId: String): Int = db.deleteOwnerRows(ownerId, "content_ref" to "owner_id")
}
