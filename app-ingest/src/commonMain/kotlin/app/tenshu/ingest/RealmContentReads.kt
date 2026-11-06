package app.tenshu.ingest

import kotlinx.coroutines.CancellationException
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortBy
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.Size
import org.tenshu.core.file.contentHash
import org.tenshu.core.file.files
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.getOrNull
import org.tenshu.core.oneOf
import org.tenshu.file.client.fileService

/**
 * What a Duplicates screen needs: the media duplicate groups and how many files were compared.
 *
 * @property groups [ContentIndex.mediaDuplicateGroups]'s order and shape — largest group first,
 *   oldest copy first within a group.
 * @property filesScanned every non-trashed file that carries a content hash — the number the
 *   screen's *"We compared N files"* states.
 */
data class DuplicateScan(
    val groups: List<List<IndexedContent>>,
    val filesScanned: Int,
)

/**
 * The two questions the realm's content hashes answer, each asked in **byte-bounded replies**
 * (`photos-app.md` §40).
 *
 * ## ⚠⚠ Why not one projection of the realm, which is what this replaced
 *
 * Both callers used to read every non-trashed file in the realm, nine properties each, in **one**
 * reply (`RealmContentIndex`'s stream for Duplicates, `ContentIndex.load`'s `getAll` for the backup
 * drain). A reply is one Binder transaction, and strings and entity rows do not go through the
 * SharedMemory byte tokenizer, so its ~1 MB ceiling was the realm's ceiling: 995 KB on a phone
 * holding 1,401 photographs, ~5.8 MB at 10,000 files, and past it there is **no answer at all**.
 * §39 moved Collections off it; these were the last two readers, and both are gone.
 *
 * Each question is now asked as narrowly as it can be, and every reply is capped in rows of a
 * known narrow shape (row sizes measured in §40). ⚠⚠ A row count is a byte bound only for a row of
 * known size, and a **path has no length limit** — nothing refuses a long one at write time. So the
 * two reads whose rows carry a path are sized in bytes as well: [chunkWithinReply] where the paths
 * are known before the read, and a page that shrinks where they are not ([readSortedByPath]).
 *
 * - [heldContentHashes] — *"which of these hashes does the realm hold?"* — one fused
 *   `contentHash oneOf …` walk per [HASH_CHUNK] hashes, each reply at most [PRESENCE_ROW_LIMIT]
 *   hash-only rows (~205 B). The backup drain asks it per batch of candidates.
 * - [duplicateScan] — *"which contents appear more than once?"* — a sorted, overlapping page walk
 *   over `(path, contentHash)` rows ([HASH_PAGE_SIZE] a page, ~275 B + the path each), then the
 *   duplicated copies' full rows by `path oneOf …`, [PATH_CHUNK] at a time (~700 B each).
 * - [everyContentHash] — the hash walk alone: the presence lookup's fallback on a Control that
 *   cannot answer a hash predicate (`TenshuDuplicateContentGate`).
 *
 * ## ⚠⚠ Client-side, and it has to stay that way
 *
 * Unchanged from `ContentIndex`: the hashes are compared on a device that already holds the
 * plaintext. Control answers a `oneOf` over the device's **own** realm through the chain it already
 * evaluates; nothing reaches the relay, and there is no cross-user lookup (House Rule #1; threat
 * model A1).
 *
 * ## ⚠ Every function returns `null` for *"could not look"*
 *
 * Never an empty answer. A failed presence read that came back as "none held" would re-upload
 * content the realm has (a quota cost, silently); a failed scan that came back empty would tell the
 * user they have no duplicates.
 */
object RealmContentReads {

    /**
     * Hashes per `oneOf` predicate.
     *
     * ⚠ `oneOf` compares each row against its candidates linearly, so a walk costs rows × hashes;
     * this keeps a large batch from becoming one walk of millions of comparisons, and bounds the
     * *request* (~70 bytes a hash).
     */
    const val HASH_CHUNK = 200

    /**
     * Rows per presence reply ([heldContentHashes]).
     *
     * ⚠⚠ The **byte** bound, not a page size: a row carries the content hash alone — ~205 bytes on
     * the wire, most of it the entity's type ids — so a reply is at most ~205 KB. A chunk whose
     * matches fill it is asked again for the hashes the reply did not name, so a hash held by
     * thousands of files cannot make a reply bigger, only add a walk.
     */
    const val PRESENCE_ROW_LIMIT = 1_000

    /**
     * The most new rows per page of the hash walk ([duplicateScan], [everyContentHash]).
     *
     * A `(path, contentHash)` row is [HASH_ROW_BYTES] on the wire plus its path (~310 for a
     * `/Backup/<device>/Camera/PXL_….jpg` path), and a page carries [PAGE_OVERLAP] rows of overlap
     * — so a page of ordinary paths is ~165 KB, under [REPLY_BYTE_BUDGET].
     * ⚠⚠ That is a measurement of short paths, not a bound: [readSortedByPath] asks for fewer rows
     * where the paths are long, and this is only the ceiling it starts from.
     * ⚠ Each page is one O(realm) walk in Control, so fewer, bigger pages are faster; revisit only
     * with a byte measurement in hand.
     */
    const val HASH_PAGE_SIZE = 500

    /**
     * The most paths per `oneOf` when the duplicated copies' full rows are read back — `oneOf` scans
     * its candidates per row. ⚠ The byte bound is [chunkWithinReply]'s, not this count.
     */
    const val PATH_CHUNK = 200

    /**
     * What one reply may weigh: a quarter of Binder's ~1 MB transaction buffer, the budget §39 sized
     * its pages to. The buffer is shared by every transaction the process has in flight, so a reply
     * that fits alone is not thereby safe.
     */
    const val REPLY_BYTE_BUDGET = 256 * 1024

    /** A `(path, contentHash)` row on the wire, without its path (§40.4). */
    internal const val HASH_ROW_BYTES = 275

    /** A nine-property row ([fullRowProjection]) on the wire, without its path (§40.4). */
    internal const val FULL_ROW_BYTES = 700

    /**
     * Rows each page re-reads behind the cursor, so a page can tell where the previous one ended
     * even if files sorting before it were deleted in between. See [readSortedByPath].
     */
    internal const val PAGE_OVERLAP = 32

    /** Whole-walk restarts a page walk allows when the realm moves faster than it reads. */
    internal const val MAX_RESTARTS = 3

    /**
     * The subset of [hashes] that some non-trashed file in the realm carries, or `null` when the
     * realm could not be read.
     *
     * ⚠ Blank hashes are never asked about and never held: *"I could not identify it"* must not
     * read as *"I have it"*.
     *
     * ⚠⚠ Needs a Control whose predicates read an absent stored property as "no match" (§40):
     * before that, `contentHash oneOf …` raised on the first folder the walk met — every real realm
     * — and this returns `null`. [everyContentHash] is the fallback that works on any Control.
     */
    suspend fun heldContentHashes(
        client: TenshuClient,
        hashes: Collection<String>,
        rowLimit: Int = PRESENCE_ROW_LIMIT,
    ): Set<String>? {
        val held = mutableSetOf<String>()
        for (chunk in hashes.filter { it.isNotEmpty() }.distinct().chunked(HASH_CHUNK)) {
            var remaining = chunk
            while (remaining.isNotEmpty()) {
                val rows = readRows(client) {
                    root.files.traverse()
                        .filter<File> { contentHash oneOf remaining }
                        .filter<File> { notTrashed() }
                        // ⚠ The sort is here for the WINDOW: `take` bounds the reply, and a
                        // window fuses into the walk only behind a sort (fusion §9).
                        .sortBy(File.ContentHash)
                        .take(rowLimit)
                        .select { relations(File.ContentHash) }
                } ?: return null
                val found = rows.mapNotNullTo(mutableSetOf()) { it.contentHashOrNull() }
                held += found
                // A short reply named every match. A full one may have stopped part way through a
                // hash's copies, so ask again for the ones it did not name — every full reply names
                // at least one, so this ends.
                if (rows.size < rowLimit) break
                remaining = remaining.filterNot { it in found }
            }
        }
        return held
    }

    /**
     * Every content hash some non-trashed file carries, read in [HASH_PAGE_SIZE] pages, or `null`
     * when the realm could not be read.
     *
     * ⚠ O(realm) in replies and walks — the presence lookup's fallback, not its first resort.
     */
    suspend fun everyContentHash(client: TenshuClient, pageSize: Int = HASH_PAGE_SIZE): Set<String>? =
        hashRows(client, pageSize)?.mapNotNullTo(mutableSetOf()) { it.contentHashOrNull() }

    /**
     * The realm's media duplicate groups, or `null` when it could not be read.
     *
     * Two reads, both byte-bounded per reply:
     *
     * 1. every non-trashed file's `(path, contentHash)`, in [HASH_PAGE_SIZE] pages sorted by path —
     *    grouped here, so a group whose copies straddle a page boundary is still one group;
     * 2. the full rows of the copies of every hash held more than once, by `path oneOf …`, in
     *    chunks that fit a reply ([chunkWithinReply]). ⚠ By PATH, not by hash: a path is a field every row has, so
     *    this works on a Control that predates the absent-property rule [heldContentHashes] needs.
     *
     * ⚠ The two reads are not one snapshot. A copy deleted between them leaves its group a member
     * short (and a group of one is dropped); a copy added between them is missed until the next
     * scan. Both are what reading a moving realm a moment earlier or later would show.
     */
    suspend fun duplicateScan(
        client: TenshuClient,
        hashPageSize: Int = HASH_PAGE_SIZE,
        pathChunk: Int = PATH_CHUNK,
    ): DuplicateScan? {
        val pathsByHash = mutableMapOf<String, MutableList<String>>()
        for (row in hashRows(client, hashPageSize) ?: return null) {
            val hash = row.contentHashOrNull() ?: continue
            val path = row.pathOrNull() ?: continue
            pathsByHash.getOrPut(hash) { mutableListOf() } += path
        }
        val duplicatedPaths = pathsByHash.values.filter { it.size > 1 }.flatten()
        val entries = mutableListOf<IndexedContent>()
        for (chunk in chunkWithinReply(duplicatedPaths, pathChunk)) {
            val rows = readRows(client) {
                root.files.traverse()
                    .filter<File> { path oneOf chunk }
                    .filter<File> { notTrashed() }
                    .sortBy { path }
                    .take(chunk.size)
                    .select { fullRowProjection() }
            } ?: return null
            rows.mapNotNullTo(entries) { ContentIndex.indexed(it) }
        }
        return DuplicateScan(
            groups = ContentIndex.of(entries).mediaDuplicateGroups(),
            filesScanned = pathsByHash.values.sumOf { it.size },
        )
    }

    /**
     * [paths] split so that each chunk's rows fit [byteBudget] — and at most [maxPaths] a chunk.
     *
     * ⚠⚠ By BYTES, because the paths are in hand before the read: a chunk's reply is one
     * [FULL_ROW_BYTES] row per path plus the path itself, and its request carries the same paths, so
     * both are bounded here whatever their lengths. ⚠ A single path over the whole budget still goes
     * out, alone — dropping it would lose a copy from its group without a word.
     */
    internal fun chunkWithinReply(
        paths: List<String>,
        maxPaths: Int,
        byteBudget: Int = REPLY_BYTE_BUDGET,
    ): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0
        for (path in paths) {
            val cost = FULL_ROW_BYTES + path.encodeToByteArray().size
            if (current.isNotEmpty() && (current.size >= maxPaths || used + cost > byteBudget)) {
                chunks += current
                current = mutableListOf()
                used = 0
            }
            current += path
            used += cost
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /** Every non-trashed file's `(path, contentHash)`, paged — [readSortedByPath] over no narrowing. */
    private suspend fun hashRows(client: TenshuClient, pageSize: Int): List<Entity<File>>? =
        readSortedByPath(client, pageSize, narrow = { this }) {
            fields(File.Field.PATH)
            relations(File.ContentHash)
        }

    /**
     * Every non-trashed file [narrow] keeps, sorted by path and read at most [pageSize] new rows at
     * a time, or `null` when a page of a single row failed or the realm moved faster than the walk
     * could follow.
     *
     * ## ⚠⚠ A page is sized in bytes, and nothing here knows the bytes in advance
     *
     * Every row carries its path, and a path has no length limit — 500 rows of 2 KB paths are past
     * Binder's ~1 MB, where the page gets no answer. The client cannot count bytes it has not read
     * and the chain has no byte-capped window (that would be a new op parameter), so:
     *
     * - **a page that got no answer is asked again at half the rows**, down to one new row, and
     *   past that one row to a transaction (below); that ceiling stays lowered for the rest of the
     *   walk. This is the part that holds whatever the paths are — only a single row too big for a
     *   transaction is *could not look*. ⚠ "Too big" cannot be told from any other failed read, so
     *   a dead transport costs ~log2([pageSize]) failed calls before the same `null`;
     * - **each page sizes the next** from the bytes it carried ([HASH_ROW_BYTES] + path), to
     *   [byteBudget]. Rows are sorted by path, so neighbours share a prefix and the last page
     *   predicts the next well; this keeps replies near the budget rather than near the limit.
     *
     * The overlap is never wider than the page, so the smallest page is two rows.
     *
     * ## ⚠⚠ One row to a transaction, when two will not fit
     *
     * Rows that each fit a transaction but not in pairs (paths past ~half a megabyte) cannot be
     * read with an overlap at all. The walk then reads the row at the cursor **alone**, and checks
     * its place with a second one-row read: the row just before it must still be the last one held.
     *
     * ⚠ That is weaker than the overlap, and cannot be made equal to it with the ops there are:
     * the overlap's guarantee comes from the anchor and the new rows being ONE read, and here they
     * are two. A delete before the cursor ahead of the first read **and** an insert before the
     * cursor between the two cancel out unseen, and one row is then missed for this walk. Every
     * other movement fails the check and restarts the walk. The alternative for such a realm is no
     * answer at all, ever.
     *
     * ## ⚠⚠ Why offsets, and how they are kept honest
     *
     * A keyset cursor needs a "greater than" over the key, and the chain has none for strings
     * (`inRange` is numeric only, and an older Control would answer a string range with `false`
     * — an empty page that would end the read early, silently). So each page is
     * `sortBy(path).drop(offset).take(n)`, and an offset is only right while nothing sorting before
     * it changes. Each page therefore starts [overlap] rows **before** the previous page's end and
     * must begin with a row that sorts at or before the last path already held:
     *
     * - inserts before the cursor push rows later — the page still starts at or before the cursor,
     *   and only rows sorting after the last held path are taken;
     * - deletes before the cursor pull rows earlier — up to the overlap's width the page still
     *   starts at or before the cursor; past it the check fails and the page is re-read reaching
     *   further back (up to half a page), and past that the walk restarts ([MAX_RESTARTS]).
     *
     * Paths are unique, so "sorts after the last held path" is exact; the comparison is
     * `String.compareTo`, the one `SortBy` uses for a string key.
     */
    internal suspend fun readSortedByPath(
        client: TenshuClient,
        pageSize: Int,
        narrow: TList<File>.() -> TList<File>,
        overlap: Int = PAGE_OVERLAP,
        byteBudget: Int = REPLY_BYTE_BUDGET,
        /** Runs after every page — the seam a test moves the realm through mid-walk. */
        afterPage: suspend () -> Unit = {},
        projection: ListBuilder<Selector<File>>.() -> Unit,
    ): List<Entity<File>>? {
        // The most new rows a page may ask for: halved when a page gets no answer, never raised.
        var ceiling = pageSize
        // Set when even one new row beside one row of overlap got no answer; never cleared.
        var oneRowAtATime = false
        repeat(MAX_RESTARTS + 1) {
            val rows = mutableListOf<Entity<File>>()
            var lastPath: String? = null
            var offset = 0
            var size = ceiling
            var back = minOf(overlap, size)
            while (true) {
                if (oneRowAtATime) {
                    val held = lastPath
                    val row = readRows(client) {
                        root.files.traverse()
                            .narrow()
                            .filter<File> { notTrashed() }
                            .sortBy { path }
                            .drop(offset)
                            .take(1)
                            .select(projection)
                    } ?: return null
                    if (held != null) {
                        val anchor = readRows(client) {
                            root.files.traverse()
                                .narrow()
                                .filter<File> { notTrashed() }
                                .sortBy { path }
                                .drop(offset - 1)
                                .take(1)
                                .select(projection)
                        } ?: return null
                        // The realm moved under the cursor: nothing here can say by how much.
                        if (anchor.singleOrNull()?.pathOrNull() != held) break
                    }
                    afterPage()
                    val next = row.singleOrNull() ?: return rows
                    val path = next.pathOrNull() ?: break
                    if (held != null && path <= held) break
                    rows += next
                    lastPath = path
                    offset++
                    continue
                }
                val reach = minOf(overlap, size)
                // ⚠ The overlap is part of the reply, so it is capped: a reply is never more than a
                // page and a half of rows, whatever the realm did between two pages.
                val maxBack = maxOf(reach, size / 2)
                val start = if (lastPath == null) 0 else (offset - back).coerceAtLeast(0)
                val want = (offset - start) + size
                val page = readRows(client) {
                    root.files.traverse()
                        .narrow()
                        .filter<File> { notTrashed() }
                        .sortBy { path }
                        .drop(start)
                        .take(want)
                        .select(projection)
                }
                if (page == null) {
                    if (size == 1) {
                        // A lone row got no answer: nothing smaller to ask for.
                        if (want == 1) return null
                        oneRowAtATime = true
                        continue
                    }
                    ceiling = size / 2
                    size = ceiling
                    back = minOf(overlap, size)
                    continue
                }
                afterPage()
                val held = lastPath
                // ⚠ An EMPTY page past offset 0 proves nothing either: the rows after the cursor may
                // now sit before `start`.
                val first = page.firstOrNull()?.pathOrNull()
                if (held != null && start > 0 && (first == null || first > held)) {
                    // More rows before the cursor vanished than this page looked back over.
                    if (back >= maxBack) break
                    back = (back * 4).coerceAtMost(maxBack)
                    continue
                }
                rows += if (held == null) page else page.filter { (it.pathOrNull() ?: "") > held }
                page.lastOrNull()?.pathOrNull()?.let { if (held == null || it > held) lastPath = it }
                if (page.size < want) return rows
                offset = start + page.size
                val rowBytes =
                    page.sumOf { HASH_ROW_BYTES + (it.pathOrNull()?.encodeToByteArray()?.size ?: 0) } / page.size
                // The overlap comes out of the same budget; where the budget is too small for the
                // usual overlap, half of it is overlap.
                val fit = byteBudget / rowBytes
                size = (if (fit >= 2 * overlap) fit - overlap else fit / 2).coerceIn(1, ceiling)
                back = minOf(overlap, size)
            }
        }
        return null
    }

    /** One chain, read to a list; `null` for a failed read, a dead transport or a throwing client. */
    private suspend fun readRows(
        client: TenshuClient,
        chain: FileSystem.() -> TList<*>,
    ): List<Entity<File>>? = try {
        client.fileService.defaultFileSystem.chain()
            .getAll()
            .getOrNull()
            ?.toList()
            ?.map {
                @Suppress("UNCHECKED_CAST")
                it as? Entity<File> ?: return null
            }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // `getService` throws on an unregistered schema, and a dead transport throws rather
        // than folding.
        null
    }

    /** The nine properties [ContentIndex.indexed] maps — the Duplicates screen's tile. */
    private fun ListBuilder<Selector<File>>.fullRowProjection() {
        fields(File.Field.PATH)
        relations(
            File.ContentHash,
            File.ContentId,
            File.CreatedAt,
            File.Category,
            File.Size,
            File.CaptureLatE7,
            File.CaptureLngE7,
            File.PixelWidth,
            File.PixelHeight,
        )
    }

    private fun Entity<File>.contentHashOrNull(): String? =
        get(File.ContentHash)?.get()?.takeIf { it.isNotEmpty() }

    private fun Entity<File>.pathOrNull(): String? = runCatching { path.get() }.getOrNull()
}
