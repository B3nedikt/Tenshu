package org.tenshu.sync.relay.mailbox

/**
 * CR-6e — the relay-side inventory (`content-plane-refcount.md` §11.9/§11.11, track P2).
 *
 * A stock-take of one owner's content plane, split by declaration status. Its headline is
 * `blobs − refs(described)`: how many stored bytes **no declaration reaches**. It is the number
 * §9.9b asked for before CR-6 is built, and it is deliberately the only thing this file does — it
 * computes and reports, and **frees nothing**.
 *
 * ⚠ **Per owner is per realm.** `ownerId` is derived from the realm's master public key (the realm
 * bootstrap rejects any other), so one owner is exactly one realm and these totals are realm-wide.
 *
 * ## ⚠⚠ An undescribed blob is not an orphan
 *
 * `undescribed ⇒ orphan` is sound only if nothing legitimate writes without declaring, and §9.9a's
 * writer inventory says otherwise. At least four populations sit inside [undescribedBlobCount] with
 * no defect implied:
 *
 * | population | why it is undescribed | does it grow? |
 * |---|---|---|
 * | every byte uploaded **before CR-4** | the declaration route did not exist | no — fixed backlog |
 * | **slice snapshots** (`SliceSnapshotWriter`, client-side) | ⚠⚠ declares **never** (§9.9a) | ⭐ no — `sha256("snapshot:$realmId:$relationId:$sliceKey")` is deterministic, so a re-seal **overwrites the same ref** |
 * | the legacy `else` emit branch (**Fit**, any null `resolveContentId`) | not on the content-addressed path | with Fit use |
 * | **aborted emits** | the emit never reached its declaration | ⚠ yes — this is the one P1 attacks |
 * | **in-flight uploads** | chunks are `PUT` before the declaration | transient; see [recentlyTouchedBlobCount] |
 *
 * ⇒ **The number is a prior for a decision, not a work list.** Reclaiming on it would delete live
 * joiner snapshots — not data loss (a joiner replays the log) but a delete/rewrite churn loop, since
 * the next maintenance pass rewrites what was freed.
 *
 * ⚠⚠ **Read [describedContentCount] first.** A 0 there means the inventory is measuring the
 * *absence of the declaration mechanism*, not stranded bytes, and the undescribed figure is then
 * 100 % by construction and says nothing. That is production's state as of 2026-09-11.
 *
 * ## Where each term comes from
 *
 * | term | source | table |
 * |---|---|---|
 * | blobs | [ContentBlobRegistry.blobIndex] | `content_blob` |
 * | described | [ContentRefRegistry.declaredContentIds] | `content_ref` |
 * | refs(described) | [ContentRefRegistry.referencedRefs] | `content_ref` |
 * | candidates | [candidateContentIds] | both |
 *
 * Every term is relay state the relay was **told**. Nothing is decrypted and no manifest is read,
 * so House Rule #1 is untouched — the same reasoning [ContentRefRegistry] carries.
 */
data class ContentInventoryReport(
    /** Blobs held for the owner, of every kind. */
    val blobCount: Int,
    /** Their summed `byte_len`. ⚠ A lower bound by [zeroLengthBlobCount] rows — see that field. */
    val blobBytes: Long,
    /**
     * Blobs recording 0 bytes — a zero-length body was stored, which no legitimate blob is (see
     * [ContentBlobRegistry.blobIndex]). Named rather than summed, so a defect cannot hide inside
     * a byte total.
     */
    val zeroLengthBlobCount: Int,
    /** Content objects the relay knows the ref set of. ⚠⚠ Read this first; see the class KDoc. */
    val describedContentCount: Int,
    /** Described content no live edge names — CR-5's candidate set, freeable only after a client veto. */
    val candidateContentCount: Int,
    /** ⭐ **The headline.** Blobs no described content names. ⚠ Not orphans — see the class KDoc. */
    val undescribedBlobCount: Int,
    /** Their summed `byte_len`, a lower bound by [zeroLengthUndescribedBlobCount] rows. */
    val undescribedBlobBytes: Long,
    /** How many of [undescribedBlobCount] record 0 bytes. */
    val zeroLengthUndescribedBlobCount: Int,
    /**
     * Undescribed blobs touched within the reporting window — §11.5's `W`, the population that is
     * undescribed only because its emit has not finished yet.
     *
     * ⚠⚠ **Reported, never excluded, and it over-counts.** `last_referenced_at` is refreshed by
     * `get` as well as `put`, so a blob someone just *downloaded* is indistinguishable from one
     * mid-upload. Subtracting this from the headline would therefore hide live old bytes behind a
     * read; reporting it lets a reader discount the transient half without the instrument lying.
     */
    val recentlyTouchedBlobCount: Int,
    /** Their summed `byte_len`. */
    val recentlyTouchedBlobBytes: Long,
) {
    companion object {
        val EMPTY = ContentInventoryReport(0, 0L, 0, 0, 0, 0, 0L, 0, 0, 0L)

        /**
         * Default reporting window for [ContentInventoryReport.recentlyTouchedBlobCount].
         *
         * ⚠ A **reporting** split, not a safety bound — nothing frees on this, so it does not need
         * to be conservative, and §11.5 Q3's *"measure `W` rather than pick it"* applies to CR-6f's
         * quarantine, not here. One hour comfortably covers an emit over a slow link.
         */
        const val DEFAULT_RECENT_WINDOW_MS: Long = 60 * 60 * 1000L
    }
}

/**
 * Compute [ContentInventoryReport] for [ownerId] from relay state alone.
 *
 * ⚠ Read-only by construction: every call here is a query, and there is no write path to reach from
 * this function. Keep it that way — CR-6f's quarantine is a separate, gated act (§11.5), and the
 * value of this one is that it can be run on production with no decision attached.
 *
 * ⚠ Allocating: it materialises the owner's blob index and the keep set. That is fine for a
 * diagnostic on a relay holding tens of thousands of refs, and is why no hot path calls it. If an
 * owner ever outgrows it, the whole computation is one SQL statement against two tables in the same
 * database — replace the body, keep the shape.
 */
fun computeContentInventory(
    ownerId: String,
    blobs: ContentBlobRegistry,
    refs: ContentRefRegistry,
    edges: ContentEdgeRegistry,
    nowMs: Long = System.currentTimeMillis(),
    recentWindowMs: Long = ContentInventoryReport.DEFAULT_RECENT_WINDOW_MS,
): ContentInventoryReport {
    val index = blobs.blobIndex(ownerId)
    val described = refs.declaredContentIds(ownerId)
    val candidates = candidateContentIds(ownerId, refs, edges).count()

    if (index.isEmpty()) {
        // Still report the content-side counts: a realm can have declarations whose bytes another
        // device holds, and reporting 0/0/0 there would read as "nothing declared" rather than
        // "no bytes here".
        return ContentInventoryReport.EMPTY.copy(
            describedContentCount = described.size,
            candidateContentCount = candidates,
        )
    }

    // The keep set: every ref reachable from content the relay has been told the shape of.
    // ⚠ Described, NOT referenced — a content object whose holder is gone still describes its
    // bytes, and those bytes are CR-5's business, not this inventory's.
    val keep = refs.referencedRefs(ownerId, described)
    val recentThreshold = nowMs - recentWindowMs

    var undescribedCount = 0
    var undescribedBytes = 0L
    var zeroLengthUndescribed = 0
    var zeroLengthAll = 0
    var totalBytes = 0L
    var recentCount = 0
    var recentBytes = 0L
    for ((ref, entry) in index) {
        totalBytes += entry.byteLen
        if (entry.byteLen == 0L) zeroLengthAll++
        if (ref in keep) continue
        undescribedCount++
        undescribedBytes += entry.byteLen
        if (entry.byteLen == 0L) zeroLengthUndescribed++
        if (entry.lastReferencedAtMs >= recentThreshold) {
            recentCount++
            recentBytes += entry.byteLen
        }
    }

    return ContentInventoryReport(
        blobCount = index.size,
        blobBytes = totalBytes,
        zeroLengthBlobCount = zeroLengthAll,
        describedContentCount = described.size,
        candidateContentCount = candidates,
        undescribedBlobCount = undescribedCount,
        undescribedBlobBytes = undescribedBytes,
        zeroLengthUndescribedBlobCount = zeroLengthUndescribed,
        recentlyTouchedBlobCount = recentCount,
        recentlyTouchedBlobBytes = recentBytes,
    )
}
