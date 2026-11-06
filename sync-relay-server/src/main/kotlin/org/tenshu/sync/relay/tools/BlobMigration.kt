package org.tenshu.sync.relay.tools

import org.tenshu.sync.relay.storage.BlobStore
import org.tenshu.sync.relay.storage.RelayDatabase

/**
 * Copies every large-blob key from one [BlobStore] to another — the volume→bucket move that
 * `S3BlobStore` makes possible (`hosting-provider-economics.md` D10 S4).
 *
 * ⚠⚠ **This runs as a bulk pass against a LIVE relay, then a short delta pass with the relay
 * stopped.** That ordering is the whole design, and it is not interchangeable with "switch the env
 * vars and copy in the background":
 *
 * During the bulk pass the relay is still on [org.tenshu.sync.relay.storage.FileBlobStore]. Nothing
 * reads the destination, so a partly-filled bucket is unobservable and no invariant can be violated
 * by it. The relay then stops, this runs again to catch what was written during the bulk pass, the
 * env flips, and it starts. There is never a moment where the live store is missing bytes the SQL
 * index believes in.
 *
 * ⚠⚠ **Why not a read-through composite store instead** (zero downtime, and it was considered):
 * a composite has to fall through on more than `get`, and only `get` is obvious.
 *  - **`exists`** — [org.tenshu.sync.relay.storage.SqliteShareStore]'s `isCollectible` reads
 *    `!blobs.exists(chunkKey(shareId, 0))` as *"this share never received chunk 0"*. A live share
 *    whose bytes are still only on the volume answers `false`, so the GC deletes its record. The
 *    orphan branch needs `createdAt` older than a full retention window, so it fires **later**,
 *    against the oldest links, long after the migration looks finished — and the record does not
 *    come back when the bytes do.
 *  - **`delete`** — `gcSweep` reclaims a blob from the primary; the next `get` falls through and
 *    finds it alive on the secondary. Reclamation silently stops working.
 *  - **`put`** — `putChunk` is `if (!blobs.exists(key)) blobs.put(...)`, so with a correct
 *    fallthrough a re-upload of a volume-resident chunk skips the write. Benign, but it means the
 *    composite changed behaviour in a method nobody was considering.
 *
 * None of that is unimplementable — but its correctness rests on a reviewer holding four
 * non-obvious cases in mind, and `BlobStoreContractTest` **cannot** catch a mistake: it runs
 * against one store, so a composite passes it trivially. Stopping the relay rests on "the relay is
 * off". For a one-time move of a local-first system, that is the better trade.
 */
class BlobMigration(
    private val source: BlobStore,
    private val destination: BlobStore,
    /**
     * Read each object back from [destination] and compare it to the bytes written.
     *
     * ⚠⚠ **Default on, and it earns the extra read.** A store that does not decode `aws-chunked`
     * framing does not *fail* the PUT — it stores the framing, and a 2 MiB blob lands as 2,098,678
     * bytes (see `S3BlobStore.clientFor`). That is silent corruption discovered on read, months
     * later, which is exactly the failure a migration must not carry into the destination. Copying
     * without verifying turns a detectable bug into a data-loss event.
     */
    private val verify: Boolean = true,
) {

    /** What a run did. [failed] being non-empty means **do not flip the env vars.** */
    data class Report(
        val copied: Int = 0,
        val skipped: Int = 0,
        val missingAtSource: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList(),
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /**
     * Copy every key in [keys] that the destination does not already hold.
     *
     * Idempotent and resumable by construction: an interrupted run is re-run, and everything
     * already copied is skipped. That is what makes the two-pass shape work — the delta pass is
     * simply this function again over the same key set.
     */
    fun run(keys: Sequence<String>, onProgress: (Report) -> Unit = {}): Report {
        var report = Report()
        for (key in keys) {
            report = try {
                when {
                    destination.exists(key) -> report.copy(skipped = report.skipped + 1)
                    else -> {
                        val bytes = source.get(key)
                        if (bytes == null) {
                            // The index names a blob the source does not have. Not fatal on its own
                            // — a crash between the index write and the file write, or a GC race —
                            // but it is reported rather than silently counted as success, because
                            // "the destination is missing it too" is the state we are here to end.
                            report.copy(missingAtSource = report.missingAtSource + key)
                        } else {
                            destination.put(key, bytes)
                            if (verify) verifyCopied(key, bytes)
                            report.copy(copied = report.copied + 1)
                        }
                    }
                }
            } catch (e: Exception) {
                report.copy(failed = report.failed + (key to (e.message ?: e::class.simpleName.orEmpty())))
            }
            onProgress(report)
        }
        return report
    }

    private fun verifyCopied(key: String, written: ByteArray) {
        val readBack = destination.get(key)
            ?: error("verify: destination has no object at '$key' immediately after writing it")
        if (!readBack.contentEquals(written)) {
            error(
                "verify: '$key' read back as ${readBack.size} bytes, wrote ${written.size} — " +
                    "the destination altered the payload (aws-chunked framing is the usual cause)"
            )
        }
    }

    companion object {

        /**
         * Every key the large-blob plane holds, read from the **SQL index** rather than by listing
         * either store.
         *
         * ⚠ [BlobStore] has no `list` on purpose — "GC works off the SQL index, so the store is
         * never *listed* by owner" — so the index is not merely convenient here, it is the only
         * enumeration source. A consequence worth stating: a blob on the volume with no index row
         * is invisible to this tool, and is already unreachable to the relay for the same reason.
         */
        fun enumerateKeys(db: RelayDatabase): Sequence<String> = sequence {
            yieldAll(contentKeys(db))
            yieldAll(shareChunkKeys(db))
        }

        private fun contentKeys(db: RelayDatabase): List<String> = db.withConnection { conn ->
            conn.prepareStatement("SELECT owner_id, ref FROM content_blob").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add("${rs.getString(1)}/${rs.getString(2)}") }
                }
            }
        }

        /**
         * `share/<shareId>/<idx>` for every index a record claims. Uses `chunk_count` rather than
         * decoding `chunk_hashes`, since the count is all enumeration needs.
         */
        private fun shareChunkKeys(db: RelayDatabase): List<String> = db.withConnection { conn ->
            conn.prepareStatement("SELECT share_id, chunk_count FROM share_record").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val shareId = rs.getString(1)
                            repeat(rs.getInt(2)) { index -> add("share/$shareId/$index") }
                        }
                    }
                }
            }
        }
    }
}
