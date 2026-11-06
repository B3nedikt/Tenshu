package org.tenshu.sync.relay.storage

import kotlinx.serialization.Serializable
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower
import org.tenshu.sync.common.manifestRefOf
import java.sql.Connection

/**
 * **Operator reclamation of ciphertext no content describes** (`content-plane-cr2-quorumless.md`
 * §12.1), built on the deletion machinery the automatic sweep uses (§10.2, §10.7).
 *
 * Duplicate emits of one content leave chunk sets in `content_blob` that no `content_ref` row names:
 * every emit mints a fresh CEK, and only the last describe wins. They count against the owner's quota
 * and nothing frees them. G4 guarantees the automatic sweep never will, so this job does it once.
 *
 * ## ⚠⚠ Why "undescribed" is only safe AFTER the declaration backfill
 * For some duplicated contents the **live** chunk set is the undescribed one. Only a client can
 * re-describe it (the relay cannot read manifests), which the declaration backfill does. Run [plan]
 * after it has completed on a device, and read [Plan.edgeOnlyWithManifest] first.
 *
 * ## Three phases, each an operator call
 * - [plan] writes nothing. It reports the candidates and the danger signals.
 * - [enqueue] moves exactly the planned candidates into the durable `content_free_queue`. It refuses
 *   if the candidate SET moved since the plan ([Plan.candidateDigest]), or if a danger signal is present.
 * - [drain] deletes in batches, each batch **holding the store lock across the byte delete**:
 *   1. re-check that each queued ref is still undescribed and not a protected manifest;
 *   2. drop its `content_blob` index row;
 *   3. delete its bytes;
 *   4. delete its queue row.
 *
 *   A crash anywhere leaves queue rows, and the next [drain] finishes them. Every step is idempotent.
 *
 * ## ⚠⚠ The PUT and GET races are closed by the lock, not by timestamps
 * `SqliteContentBlobStore` takes a ref out of the queue under the same lock, **first**: before a PUT's
 * byte write, before a GET's read. So a PUT or GET either lands before a batch (the ref is gone from the
 * queue, and nothing of it is deleted) or after it (the bytes are already gone, or rewritten from
 * scratch). The `put_seq`/`freed_seq` comparison the proposal sketched is this, expressed as queue
 * membership.
 *
 * ## ⚠⚠ What it CANNOT tell from garbage (read before running)
 * Undescribed is not the same as orphaned (`ContentInventory`'s table). Two live populations are
 * undescribed and invisible to every check here:
 * - **Relation-slice snapshots** (`SliceSnapshotWriter` never declares; their ref hashes a realm id,
 *   relation and slice key the relay does not know). Deleting one costs a joiner a log replay and the
 *   next maintenance pass a re-seal: churn, not loss. Accepted for this one-off.
 * - **Legacy entity-keyed emits** (a row whose `resolveContentId` is null, e.g. a workout saved before
 *   `Workout.ContentId`): their manifest rides the ordered log, and their chunks sit here undescribed.
 *   Deleting those **loses data**, so the runbook requires ruling them out on a device first.
 *
 * ## What is protected
 * - Any ref some `content_ref` row names (described content), re-checked at drain time.
 * - Any blob PUT or read after it was queued: the store takes it out of the queue, under this lock,
 *   before its own byte write or read.
 * - The derived **manifest** of every content id the owner has an edge or description for
 *   ([manifestRefOf]). A manifest is the one blob of a content the relay can name undescribed.
 * - Anything touched (PUT or GET) within `minIdleMs`: a content still uploading has its chunks put
 *   before its describe.
 * - Other owners: every statement is keyed by `owner_id` (Mode-B copies share refs across owners).
 */
class ContentReclaimJob(
    private val db: RelayDatabase,
    private val blobs: BlobStore,
) {

    @Serializable
    data class Plan(
        val ownerId: String,
        val ownerBlobs: Int,
        val ownerBytes: Long,
        val describedBlobs: Int,
        val describedBytes: Long,
        val candidateBlobs: Int,
        val candidateBytes: Long,
        /** Undescribed blobs spared because they are the manifest of a known content. */
        val protectedManifests: Int,
        /** Undescribed blobs spared because they were touched within the idle window. */
        val recentlyTouched: Int,
        /**
         * ⚠⚠ Content ids with an edge but no description **whose manifest blob exists**. Their chunks
         * are undescribed and possibly live, and the relay cannot tell them from garbage. [enqueue]
         * refuses while this is non-empty. Capped at [MAX_LISTED].
         */
        val edgeOnlyWithManifest: List<String>,
        val edgeOnlyWithManifestCount: Int,
        /** Already queued, from an earlier enqueue not yet drained. */
        val queued: Int,
        /**
         * sha256 of the sorted candidate refs. [enqueue] must quote it: the operator reviewed this exact
         * set, and an equal COUNT can hide a different set.
         */
        val candidateDigest: String,
    )

    @Serializable
    data class EnqueueResult(val enqueued: Int, val bytes: Long)

    @Serializable
    data class DrainResult(
        val deletedBlobs: Int,
        val freedBytes: Long,
        /** Queued refs that became described or protected since the enqueue, dropped without deleting. */
        val spared: Int,
        val remaining: Int,
        /**
         * Non-zero when the drain stopped because content with an edge and a manifest but no description
         * appeared (a declaration in flight). Nothing was deleted in that batch; call again later.
         */
        val pausedOnEdgeOnly: Int = 0,
        /** Blobs whose delete the backend accepted but which are still present; they stay queued. */
        val failedDeletes: Int = 0,
    )

    class Refused(message: String) : IllegalStateException(message)

    fun plan(ownerId: String, minIdleMs: Long, nowMs: Long): Plan {
        require(ownerId.isNotBlank()) { "plan requires an ownerId" }
        requireIdleWindow(minIdleMs)
        return db.withConnection { conn -> planIn(conn, ownerId, minIdleMs, nowMs) }
    }

    /**
     * Queue the plan's candidates. [expectCandidates] is the count the operator read from [plan]: if the
     * store moved since, the operator has not seen what would be deleted, so nothing is queued.
     */
    fun enqueue(ownerId: String, minIdleMs: Long, nowMs: Long, expectDigest: String): EnqueueResult {
        requireIdleWindow(minIdleMs)
        return db.withConnection { conn ->
            val plan = planIn(conn, ownerId, minIdleMs, nowMs)
            if (plan.edgeOnlyWithManifestCount > 0) {
                throw Refused(
                    "${plan.edgeOnlyWithManifestCount} content(s) have an edge and a manifest but no description; " +
                        "their chunks cannot be told from garbage. Let the declaration backfill describe them first.",
                )
            }
            val candidates = candidatesIn(conn, ownerId, minIdleMs, nowMs)
            if (digestOf(ownerId, candidates.map { it.ref }) != expectDigest) {
                throw Refused("the candidate set moved since the plan (${plan.candidateBlobs} now); plan again")
            }
            conn.prepareStatement(
                "INSERT OR IGNORE INTO content_free_queue(owner_id, ref, enqueued_at, byte_len) VALUES(?, ?, ?, ?)",
            ).use { ps ->
                for (c in candidates) {
                    ps.setString(1, ownerId)
                    ps.setString(2, c.ref)
                    ps.setLong(3, nowMs)
                    ps.setLong(4, c.bytes)
                    ps.executeUpdate()
                }
            }
            EnqueueResult(enqueued = candidates.size, bytes = candidates.sumOf { it.bytes })
        }
    }

    /** ⚠ At least an hour: a shorter window would make an upload in progress a candidate. */
    private fun requireIdleWindow(minIdleMs: Long) =
        require(minIdleMs >= MIN_IDLE_MS) { "minIdleMs must be at least $MIN_IDLE_MS, was $minIdleMs" }

    /** Delete up to [maxBatches] batches of [batchSize] queued refs. Call again to continue. */
    fun drain(ownerId: String, batchSize: Int = DEFAULT_BATCH, maxBatches: Int = Int.MAX_VALUE): DrainResult {
        require(ownerId.isNotBlank()) { "drain requires an ownerId" }
        var deleted = 0
        var freed = 0L
        var spared = 0
        var batches = 0
        var pausedOn = 0
        var failed = 0
        while (batches < maxBatches) {
            val progressed = db.withConnection { conn ->
                val queuedLen = conn.prepareStatement(
                    "SELECT ref, byte_len FROM content_free_queue WHERE owner_id = ? ORDER BY ref LIMIT ?",
                ).use { ps ->
                    ps.setString(1, ownerId)
                    ps.setInt(2, batchSize)
                    ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
                }
                val queued = queuedLen.keys.toList()
                if (queued.isEmpty()) return@withConnection false
                // ⚠⚠ A declaration writes its edge and its refs in two lock sections.
                // Between them the content is edge-only with a stored manifest, and its chunks look exactly
                // like garbage. So the enqueue's refusal is re-asked on EVERY batch, and the drain stops
                // without deleting anything while one exists; the next drain call retries.
                val inFlight = edgeOnlyWithManifestIn(conn, ownerId)
                if (inFlight.isNotEmpty()) {
                    pausedOn = inFlight.size
                    return@withConnection false
                }
                val protected = protectedManifestsIn(conn, ownerId)
                val toDelete = mutableListOf<String>()
                for (ref in queued) {
                    // ⚠ Re-checked here, not trusted from the enqueue: a backfill may have described it
                    // in between, and a describe is exactly the evidence that it is live. (A use after
                    // the enqueue needs no check here: every PUT and GET takes its ref out of the queue.)
                    if (isDescribed(conn, ownerId, ref) || ref in protected) spared++ else toDelete += ref
                }
                conn.prepareStatement("DELETE FROM content_blob WHERE owner_id = ? AND ref = ?").use { ps ->
                    for (ref in toDelete) {
                        ps.setString(1, ownerId)
                        ps.setString(2, ref)
                        ps.executeUpdate()
                    }
                }
                // ⚠⚠ Bytes deleted while the lock is still held: a PUT of the same ref waits for it, so
                // it can never land between this delete and the index delete above.
                blobs.deleteAll(toDelete.map { "$ownerId/$it" })
                // ⚠⚠ Verified, not assumed: `FileBlobStore.delete` ignores a failed
                // `File.delete()`. A blob still present keeps its queue row, which is then the ONLY record of
                // it, since its index row is gone. The next drain retries it, and it is reported meanwhile.
                val stuck = toDelete.filter { blobs.exists("$ownerId/$it") }.toSet()
                val settled = queued.filter { it !in stuck }
                conn.prepareStatement("DELETE FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
                    for (ref in settled) {
                        ps.setString(1, ownerId)
                        ps.setString(2, ref)
                        ps.executeUpdate()
                    }
                }
                val gone = toDelete.filter { it !in stuck }
                deleted += gone.size
                freed += gone.sumOf { queuedLen.getValue(it) }
                failed = stuck.size
                // A batch in which nothing left the queue would be picked again unchanged: stop the call.
                settled.isNotEmpty()
            }
            if (!progressed) break
            batches++
        }
        return DrainResult(deleted, freed, spared, remaining = queued(ownerId), pausedOnEdgeOnly = pausedOn, failedDeletes = failed)
    }

    fun queued(ownerId: String): Int = db.withConnection { conn -> queuedIn(conn, ownerId) }

    // ---- internals, all called with the lock held ----

    private data class Candidate(val ref: String, val bytes: Long)

    private fun planIn(conn: Connection, ownerId: String, minIdleMs: Long, nowMs: Long): Plan {
        val threshold = nowMs - minIdleMs
        val protected = protectedManifestsIn(conn, ownerId)
        var ownerBlobs = 0
        var ownerBytes = 0L
        var describedBlobs = 0
        var describedBytes = 0L
        var candidates = 0
        var candidateBytes = 0L
        var protectedCount = 0
        var recent = 0
        conn.prepareStatement(
            """
            SELECT b.ref, b.byte_len, b.last_referenced_at,
                   EXISTS (SELECT 1 FROM content_ref r WHERE r.owner_id = b.owner_id AND r.ref = b.ref)
            FROM content_blob b WHERE b.owner_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val ref = rs.getString(1)
                    val len = rs.getLong(2)
                    ownerBlobs++
                    ownerBytes += len
                    when {
                        rs.getInt(4) == 1 -> { describedBlobs++; describedBytes += len }
                        ref in protected -> protectedCount++
                        rs.getLong(3) >= threshold -> recent++
                        else -> { candidates++; candidateBytes += len }
                    }
                }
            }
        }
        val edgeOnly = edgeOnlyWithManifestIn(conn, ownerId)
        val digest = digestOf(ownerId, candidatesIn(conn, ownerId, minIdleMs, nowMs).map { it.ref })
        return Plan(
            ownerId = ownerId,
            ownerBlobs = ownerBlobs,
            ownerBytes = ownerBytes,
            describedBlobs = describedBlobs,
            describedBytes = describedBytes,
            candidateBlobs = candidates,
            candidateBytes = candidateBytes,
            protectedManifests = protectedCount,
            recentlyTouched = recent,
            edgeOnlyWithManifest = edgeOnly.take(MAX_LISTED),
            edgeOnlyWithManifestCount = edgeOnly.size,
            queued = queuedIn(conn, ownerId),
            candidateDigest = digest,
        )
    }

    /**
     * ⚠ Bound to the OWNER as well as the set: two owners can hold identical refs
     * (Mode-B copies), and a digest copied from one owner's plan must not authorise another's enqueue.
     */
    private fun digestOf(ownerId: String, refs: List<String>): String =
        sha256((listOf("owner:$ownerId") + refs.sorted()).joinToString("\n").encodeToByteArray()).toHexLower()

    private fun candidatesIn(conn: Connection, ownerId: String, minIdleMs: Long, nowMs: Long): List<Candidate> {
        val protected = protectedManifestsIn(conn, ownerId)
        return conn.prepareStatement(
            """
            SELECT b.ref, b.byte_len FROM content_blob b
            WHERE b.owner_id = ? AND b.last_referenced_at < ?
              AND NOT EXISTS (SELECT 1 FROM content_ref r WHERE r.owner_id = b.owner_id AND r.ref = b.ref)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.setLong(2, nowMs - minIdleMs)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val ref = rs.getString(1)
                        if (ref !in protected) add(Candidate(ref, rs.getLong(2)))
                    }
                }
            }
        }
    }

    /**
     * The derived manifest ref of every content id the owner has an edge or a description for.
     *
     * ⚠⚠ Live edges only (`content_id IS NOT NULL`, `content-plane-cr2-b3-wire-spec.md` §2 rule 3): a
     * none register names no content, and reading it here would hand `manifestRefOf` a null.
     */
    private fun protectedManifestsIn(conn: Connection, ownerId: String): Set<String> =
        conn.prepareStatement(
            """
            SELECT content_id FROM content_edge WHERE owner_id = ? AND content_id IS NOT NULL
            UNION SELECT content_id FROM content_ref WHERE owner_id = ?
            """.trimIndent(),
        ).use { ps ->
            repeat(2) { ps.setString(it + 1, ownerId) }
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(manifestRefOf(rs.getString(1))) } }
        }

    private fun edgeOnlyWithManifestIn(conn: Connection, ownerId: String): List<String> {
        val edgeOnly = conn.prepareStatement(
            """
            SELECT DISTINCT e.content_id FROM content_edge e
            WHERE e.owner_id = ? AND e.content_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM content_ref r WHERE r.owner_id = e.owner_id AND r.content_id = e.content_id)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        // ⚠ Most edge-only rows are harmless — a thumbnail class that was never emitted has an edge and
        // no bytes at all. Only one whose MANIFEST is stored has chunks the relay cannot name.
        return edgeOnly.filter { contentId -> blobExists(conn, ownerId, manifestRefOf(contentId)) }.sorted()
    }

    private fun blobExists(conn: Connection, ownerId: String, ref: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM content_blob WHERE owner_id = ? AND ref = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, ref)
            ps.executeQuery().use { it.next() }
        }

    private fun isDescribed(conn: Connection, ownerId: String, ref: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM content_ref WHERE owner_id = ? AND ref = ? LIMIT 1").use { ps ->
            ps.setString(1, ownerId)
            ps.setString(2, ref)
            ps.executeQuery().use { it.next() }
        }

    private fun queuedIn(conn: Connection, ownerId: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM content_free_queue WHERE owner_id = ?").use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    companion object {
        /** Refs deleted per held lock. Small, because PUTs wait for the whole batch (an S3 batch delete). */
        const val DEFAULT_BATCH = 200

        const val MAX_LISTED = 50

        const val MIN_IDLE_MS = 3_600_000L
    }
}
