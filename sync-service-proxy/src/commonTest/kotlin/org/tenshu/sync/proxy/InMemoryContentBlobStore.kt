package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.core.toTByteArray
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ReapRemoval

/**
 * Test fake for the content plane (`docs/adr/0017-file-content-two-plane-channel.md` P1 write
 * path + P2 read path). Records every chunk ciphertext `PUT` so a test
 * can assert that file bodies went to the content plane and NOT into
 * the ordered `pendingBlocks` drain, and serves them back via
 * [getChunk] so the P2 read path round-trips entirely in-process (no
 * relay server — that's P3).
 *
 * [maxObservedChunkBytes] is the proof of the cure: it never grows past
 * one `CHUNK`, regardless of total file size or how many files were
 * uploaded into the same store — i.e. the emit path is `O(CHUNK)`, not
 * `O(Σ file sizes)` the way the old whole-file `emitPropertyBlock` was.
 *
 * [fetchedRefs] records every ref a [getChunk] was *asked* for (in
 * order, with duplicates) so the selective-fetch test can assert that
 * opening file A pulled only A's chunk refs and never touched B's.
 * [maxObservedGetBytes] mirrors [maxObservedChunkBytes] for the read
 * side — the reassembly path must also stay `O(CHUNK)`.
 */
class InMemoryContentBlobStore(private val context: TContext) : ContentBlobStore {

    val chunks = LinkedHashMap<String, ByteArray>()

    /**
     * Every [ContentDescription] sent, in order. ⚠ A list, not a map: a re-emit describing the same
     * content again is a case a test may be about. ⭐ No edges are recorded: since CR-2 B3 they ride
     * the holder's push (`MockSyncRelay.pushedEdgesOf`), never this store.
     */
    val describedContents: MutableList<ContentDescription> = mutableListOf()

    /** When true, [describeContent] answers `false` — a refused request, or a dead link. */
    var failDeclarations = false

    /** Every `/sync/content/reap` batch the relay accepted, in order (CR-2 B3d). */
    val reapedRemovals = mutableListOf<List<ReapRemoval>>()

    /** When true, [reapRemovals] answers `false` — the relay refused, or the link is down. */
    var failReaps = false
    var putCount = 0
        private set
    var maxObservedChunkBytes = 0
        private set

    val fetchedRefs = mutableListOf<String>()
    var getCount = 0
        private set
    var maxObservedGetBytes = 0
        private set

    /**
     * Refs the test has explicitly removed to simulate a relay that
     * dropped / never stored a blob (missing-chunk negative). A
     * [getChunk] for a dropped ref fails cleanly.
     */
    private val dropped = mutableSetOf<String>()

    /**
     * When true every [putChunk] fails, standing in for a relay that is refusing writes — or, for
     * the journal-first upload tests, for a process that dies mid-upload: the emit never completes,
     * so nothing clears its intent.
     */
    var failPuts = false

    /**
     * When true every [putChunk] fails the way the relay refuses an **over-quota** owner — a `507`
     * carrying [STORAGE_REJECTION_MARKER] — rather than the generic transport failure [failPuts]
     * stands in for.
     *
     * ⚠⚠ The distinction is the whole point: a generic failure is *transient* and the journal
     * should retry it next tick, a `507` is *doomed* and retrying it is what produced the measured
     * ~30 rejected `PUT`s/second storm. A fake that could only fail one way could not tell the two
     * apart, so the classification had nothing to test it against.
     */
    var failPutsOverQuota = false

    /**
     * Refs whose [putChunk] fails while every other ref succeeds — the *manifest* PUT alone, say.
     *
     * ⚠ [failPuts] cannot express that: it fails the chunks too, so the emit aborts before it ever
     * reaches the manifest, and a test using it to reason about manifest ordering proves nothing.
     */
    val failPutsForRefs = mutableSetOf<String>()

    /** Every ref [putChunk] was asked to store, including attempts that failed. */
    val attemptedRefs = mutableListOf<String>()

    /**
     * Let the first N `PUT`s land, then fail every one after — a link that drops partway through a
     * multi-chunk upload, which is the only shape that produces a *partial* set on the relay.
     *
     * ⚠ [failPuts] cannot express it: it fails the first chunk, so nothing ever lands and there is
     * no partial state for a resume to find.
     */
    var failPutsAfterCount: Int? = null

    /** Fail every `PUT` with exactly this reason — a relay status as `RestContentBlobStore` words it. */
    var failPutsWith: String? = null

    override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> {
        attemptedRefs += ref
        failPutsAfterCount?.let { allowed ->
            if (putCount >= allowed) {
                return TResult.Failure.create(
                    context = context,
                    error = org.tenshu.core.GenericError.create(context, "link dropped at $ref"),
                )
            }
        }
        failPutsWith?.let { reason ->
            return TResult.Failure.create(context = context, error = GenericError.create(context, reason))
        }
        if (ref in failPutsForRefs) {
            return TResult.Failure.create(
                context = context,
                error = org.tenshu.core.GenericError.create(context, "content store refused $ref"),
            )
        }
        if (failPutsOverQuota) {
            return TResult.Failure.create(
                context = context,
                error = GenericError.create(
                    context,
                    "$STORAGE_REJECTION_MARKER: content PUT rejected (507 Insufficient Storage)",
                ),
            )
        }
        if (failPuts) {
            return TResult.Failure.create(
                context = context,
                error = org.tenshu.core.GenericError.create(context, "content store unavailable"),
            )
        }
        putCount++
        if (ciphertext.size > maxObservedChunkBytes) {
            maxObservedChunkBytes = ciphertext.size
        }
        chunks[ref] = ciphertext
        return TResult.Success.create(context, null)
    }

    /** When set, [hasChunk] answers `null` for every ref — an unreachable relay. */
    var presenceUnknown = false
    val presenceChecks = mutableListOf<String>()

    override suspend fun hasChunk(ref: String): Boolean? {
        presenceChecks += ref
        if (presenceUnknown) return null
        return ref !in dropped && ref in chunks
    }

    /** Runs on every [getChunk], before it answers — lets a test land an overlapping emit mid-resolve. */
    var onGet: (suspend (ref: String) -> Unit)? = null

    override suspend fun getChunk(ref: String): TResult<TByteArray> {
        onGet?.invoke(ref)
        getCount++
        fetchedRefs += ref
        val bytes = if (ref in dropped) null else chunks[ref]
        if (bytes == null) {
            return TResult.Failure.create(
                context,
                GenericError.create(context, "content blob not found: $ref")
            )
        }
        if (bytes.size > maxObservedGetBytes) maxObservedGetBytes = bytes.size
        return TResult.Success.create(context, bytes.toTByteArray(context))
    }

    /** Simulate the relay dropping / never having stored [ref]. */
    fun dropBlob(ref: String) {
        dropped += ref
    }

    /** Replace the stored ciphertext for [ref] (tamper negative). */
    fun tamperBlob(ref: String, replacement: ByteArray) {
        chunks[ref] = replacement
    }

    override suspend fun describeContent(contents: List<ContentDescription>): Boolean {
        if (failDeclarations) return false
        describedContents += contents
        return true
    }

    /** CR-2 B4: ids the relay holds FREED; a describe naming one is refused (`409 content_freed`). */
    val freedContentIds = mutableSetOf<String>()

    /** B4's typed describe: freed ids are refused, every other description lands (§1.4, §2.1). */
    override suspend fun describe(contents: List<ContentDescription>): org.tenshu.sync.common.DescribeOutcome {
        if (failDeclarations) return org.tenshu.sync.common.DescribeOutcome.Failed
        val freed = contents.map { it.contentId }.filter { it in freedContentIds }.distinct()
        describedContents += contents.filter { it.contentId !in freed }
        return if (freed.isEmpty()) org.tenshu.sync.common.DescribeOutcome.Described
        else org.tenshu.sync.common.DescribeOutcome.Refused(freed)
    }

    override suspend fun reapRemovals(removals: List<ReapRemoval>): Boolean {
        if (failReaps) return false
        reapedRemovals += removals
        return true
    }
}
