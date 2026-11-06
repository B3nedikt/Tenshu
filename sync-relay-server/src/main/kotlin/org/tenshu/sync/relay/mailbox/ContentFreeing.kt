package org.tenshu.sync.relay.mailbox

/**
 * The **FREED** content state and its way out (CR-2 B4r, `content-plane-cr2-b4-spec.md` §1;
 * `content-plane-cr2-quorumless.md` §10.2 G2/G3/REVIVE, §10.12 Q2/Q3).
 *
 * A content is FREED once [free] has run on it: its refs moved into the deletion queue, its
 * description and handles gone, and a `content_freed` row left behind. From then on:
 * - a push naming it on a **non-regenerable** edge is refused (409 `content_freed`) unless the push
 *   carries a revive for it (G3);
 * - a push naming it on a **regenerable** edge just clears the row ([unfree]): a rung is re-minted;
 * - a describe of it is refused (409 `content_freed`); a describe is never the way out (§10.12 Q3).
 *
 * ⭐ **"PUT since the free" is queue membership**, not a sequence column: [free] queues the refs, and
 * the blob store's PUT and GET take a ref out of the queue under the DB lock before touching bytes. So a
 * ref that is present and not queued was PUT (or read) after the free, or was never part of the freed
 * content ([unrevivableRefs]).
 *
 * ⚠⚠ Every check here and every write is keyed by owner: a revive cannot reach another owner's bytes
 * (threat model, B4r amendment).
 *
 * ⚠ The route-level checks run inside [atomically], which is the same transaction (SQLite) or the same
 * lock (in-memory) a push's block append and edge writes use, so a push and a [free] serialise.
 */
interface ContentFreeing : OwnerScopedStore {

    enum class FreeResult {
        /** The content is now FREED (or already was, and stays so). */
        FREED,

        /** It still has a live edge; nothing changed. */
        NOT_FREED,
    }

    /**
     * Run [block] as ONE transaction that also spans the push's mailbox and edge writes (SQLite: the one
     * `RelayDatabase`) or under the edge store's lock (in-memory). Nests.
     */
    fun <T> atomically(block: () -> T): T

    /**
     * The logical free (spec §1.2), in one transaction: precondition zero live edges; queue the content's
     * refs (with their `byte_len`) and drop its `content_ref` rows; drop its handles; insert the FREED row,
     * ⚠ **even when there were no refs** (PENDING content whose last edge went before its upload landed),
     * so the late describe is refused.
     *
     * No production caller in B4: B5's sweep is the first. Tests call it.
     */
    fun free(ownerId: String, contentId: String, nowMs: Long = System.currentTimeMillis()): FreeResult

    /** Which of [contentIds] are FREED for [ownerId]. */
    fun freedAmong(ownerId: String, contentIds: Collection<String>): Set<String>

    /** Clear [contentId]'s FREED row, if any. @return whether there was one. */
    fun unfree(ownerId: String, contentId: String): Boolean

    /**
     * The refs of [refs] a revive may **not** rely on: absent from the owner's blob index, or still in the
     * deletion queue (not PUT since the free). Empty means the revive verifies.
     *
     * ⚠ The in-memory twin has no queue, so there "present" is the whole check (spec §1.5).
     */
    fun unrevivableRefs(ownerId: String, refs: Collection<String>): Set<String>

    /**
     * Apply one verified revive entry: replace [contentId]'s ref set with [refs] (a describe, inside the
     * caller's transaction) and clear its FREED row if present. For an id that was not FREED this is
     * exactly a describe, which is what makes a client's retry after a lost 200 idempotent.
     */
    fun revive(ownerId: String, contentId: String, refs: Collection<String>)

    // ---- CR-2 B5r (`content-plane-cr2-b5-spec.md` §1) --------------------------------------------

    /**
     * D4, the arming seed: every **described** content (ref rows) with **zero** live edges and no clock
     * row gets `since = nowMs`, so it enters T rather than never seeing a one-to-zero transition. Runs
     * **once** per relay (a durable marker); later calls return `null` and change nothing.
     *
     * ⚠ Undescribed content stays outside the cycle (G4).
     *
     * @return how many clock rows were written, or `null` when the seed had already run.
     */
    fun seedClockOnce(nowMs: Long): Int?

    /** When [seedClockOnce] ran, or `null` if it has not. */
    fun seededAt(): Long?

    /**
     * How many contents [seedClockOnce] would put on the clock now (described, zero live edges, no clock
     * row). Read-only: `dry_run` reports it, because arming frees that backlog T later.
     */
    fun pendingSeedCount(): Int

    /**
     * D5: the bytes of [ownerId]'s blobs that belong **only** to content that is UNREFERENCED (a clock
     * row) or queued for deletion. ⚠ A ref some other, live content also describes stays charged.
     *
     * ⚠ The in-memory twin has no queue, so there it is the UNREFERENCED half alone.
     */
    fun heldBytes(ownerId: String): Long
}

/**
 * The in-memory [ContentFreeing] — the twin used when the relay runs without `databaseUrl`, and by tests.
 *
 * ⚠ Serialised by [edges]' lock ([ContentEdgeRegistry.atomically]), the same one a push holds. Cannot roll
 * back, like the rest of the in-memory set. There is no free queue: [free] forgets the refs and leaves the
 * bytes (nothing in-memory drains), so "present and not queued" reduces to "present".
 */
class ContentFreedStore(
    private val edges: ContentEdgeRegistry,
    private val refs: ContentRefRegistry,
    private val blobs: ContentBlobRegistry,
    /** B5r: the clock a revive restarts the G2 clock with. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentFreeing {

    private data class Key(val ownerId: String, val contentId: String)

    private val freed = HashMap<Key, Long>()
    private var seededAtMs: Long? = null

    override fun <T> atomically(block: () -> T): T = edges.atomically(block)

    override fun free(ownerId: String, contentId: String, nowMs: Long): ContentFreeing.FreeResult = atomically {
        require(ownerId.isNotBlank() && contentId.isNotBlank()) { "free requires an owner and a content id" }
        if (edges.holdersOf(ownerId, contentId).isNotEmpty()) return@atomically ContentFreeing.FreeResult.NOT_FREED
        refs.forgetContent(ownerId, contentId)
        freed.putIfAbsent(Key(ownerId, contentId), nowMs)
        // B5r: it leaves UNREFERENCED for FREED.
        edges.clearUnreferenced(ownerId, contentId)
        ContentFreeing.FreeResult.FREED
    }

    override fun freedAmong(ownerId: String, contentIds: Collection<String>): Set<String> = atomically {
        contentIds.filterTo(linkedSetOf()) { Key(ownerId, it) in freed }
    }

    override fun unfree(ownerId: String, contentId: String): Boolean = atomically {
        freed.remove(Key(ownerId, contentId)) != null
    }

    override fun unrevivableRefs(ownerId: String, refs: Collection<String>): Set<String> {
        if (refs.isEmpty()) return emptySet()
        val present = blobs.blobIndex(ownerId).keys
        return refs.filterNotTo(linkedSetOf()) { it in present }
    }

    override fun revive(ownerId: String, contentId: String, refs: Collection<String>) = atomically {
        this.refs.declareRefs(ownerId, contentId, refs)
        freed.remove(Key(ownerId, contentId))
        // B5r: see `SqliteContentFreedStore.revive` — the push's own edge clears this a moment later.
        edges.startClockIfUnreferenced(ownerId, contentId, clock())
        Unit
    }

    override fun seedClockOnce(nowMs: Long): Int? = atomically {
        if (seededAtMs != null) return@atomically null
        var seeded = 0
        for (ownerId in refs.describedOwnerIds()) {
            for (contentId in refs.declaredContentIds(ownerId)) {
                if (edges.startClockIfUnreferenced(ownerId, contentId, nowMs)) seeded++
            }
        }
        seededAtMs = nowMs
        seeded
    }

    /** Account closure (F12): the FREED rows. The ref and edge twins erase their own. */
    override fun eraseOwner(ownerId: String): Int = atomically {
        requireErasableOwner(ownerId)
        freed.keys.filter { it.ownerId == ownerId }.onEach { freed.remove(it) }.size
    }

    override fun seededAt(): Long? = atomically { seededAtMs }

    override fun pendingSeedCount(): Int = atomically {
        refs.describedOwnerIds().sumOf { ownerId ->
            refs.declaredContentIds(ownerId).count { contentId ->
                edges.holdersOf(ownerId, contentId).isEmpty() && edges.unreferencedSince(ownerId, contentId) == null
            }
        }
    }

    override fun heldBytes(ownerId: String): Long = atomically {
        val unreferenced = edges.unreferencedContents(ownerId).keys
        if (unreferenced.isEmpty()) return@atomically 0L
        val byContent = refs.refsByContent(ownerId, refs.declaredContentIds(ownerId))
        val live = byContent.filterKeys { it !in unreferenced }.values.flatten().toSet()
        val held = byContent.filterKeys { it in unreferenced }.values.flatten().filterTo(mutableSetOf()) { it !in live }
        val index = blobs.blobIndex(ownerId)
        held.sumOf { index[it]?.byteLen ?: 0L }
    }
}
