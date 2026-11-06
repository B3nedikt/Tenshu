package org.tenshu.sync.relay.mailbox

import org.tenshu.core.common.util.HlcStamp

/**
 * The relay's edge registers: which content each **holder**'s property names, as of which HLC
 * (`content-plane-cr2-b3-wire-spec.md` §2, CR-2 B3c).
 *
 * One register per `(owner, holder, property)` holding `contentId` (`null` = **none**: the latest
 * write cleared it) and the [HlcStamp] of that write, plus one **holder watermark** per
 * `(owner, holder)`: the highest HLC at which the holder was removed (tombstone, cascade or reap).
 *
 * ⚠⚠ **Ordered by HLC, never by arrival** (rules 1–2). Two devices' pushes and a third's reap reach
 * the relay in any order; the register must land where the log's own ordering puts it, or the edge
 * set stops being a function of the log (§10.13's invariant). Every comparison is [HlcStamp]'s, which
 * is the shared `compareHlc` — the one peers use to decide whether an entity is alive.
 *
 * ⚠⚠ **A none register is kept, never deleted.** Deleting it would lose the stamp a later, *stale*
 * write has to be compared against, and that stale write would then resurrect the edge.
 *
 * ⚠⚠ **Rule 3: every count or enumeration of edges skips none registers.** [referencedContentIds]
 * and [holdersOf] do; any new query over `content_edge` must too, or a none register reads as an edge.
 *
 * ⚠ HR #1 untouched: the relay is *told* these edges by the push that carries the row's block. It
 * derives nothing and reads no payload.
 */
interface ContentEdgeRegistry : OwnerScopedStore {

    /** One edge of a pushed row: [propertyId] names [contentId], or none when it is `null`. */
    data class EdgeWrite(val propertyId: String, val contentId: String?)

    /** A register's state. [contentId] `null` is **none** — present, stamped, and not an edge. */
    data class Register(val contentId: String?, val stamp: HlcStamp)

    /**
     * Run [block] as ONE transaction over this store **and the mailbox**: the push route appends the
     * block and applies its edges inside it, so neither commits without the other (wire spec §3.1).
     *
     * ⚠ The SQLite store shares its [org.tenshu.sync.relay.storage.RelayDatabase] with
     * `SqliteSyncMailbox` in production, which is what makes the mailbox write part of the same
     * transaction. The in-memory twin serialises under one lock and cannot roll back — it is the
     * dev/test store and is not durable anyway.
     */
    fun <T> atomically(block: () -> T): T

    /**
     * Rule 1 for every edge of one pushed row: holder [holderHash], stamp [stamp] (the block's HLC).
     *
     * Per edge: a no-op if the holder's watermark is at or above [stamp]; otherwise create the
     * register, or overwrite it iff [stamp] is strictly greater than its stamp.
     *
     * ⚠ CR-2 B5r: also keeps the clock — a content this write left with no live edge gets
     * `since = now`; every content a written edge names loses its row.
     *
     * @return how many registers this write changed, for diagnostics and tests.
     */
    fun writeEdges(ownerId: String, holderHash: String, stamp: HlcStamp, edges: List<EdgeWrite>): Int

    /**
     * Rule 2 for each of [holderHashes] at [at] — a tombstone (its target and its cascade) or a reap.
     *
     * Raises each holder's watermark to `max(watermark, at)`, and turns every register of the holder
     * whose stamp is ≤ [at] into none at stamp [at]. A register stamped above [at] is a recreate that
     * outlived the removal, and is kept.
     *
     * ⚠ Idempotent: every reaping device sends the same `(holder, T)`.
     *
     * ⚠ CR-2 B5r: a content this left with no live edge gets `since = now` on the clock.
     *
     * @return how many registers went from an edge to none.
     */
    fun removeHolders(ownerId: String, holderHashes: Collection<String>, at: HlcStamp): Int

    /** The register at `(owner, holder, property)`, none included — `null` when there is none at all. */
    fun register(ownerId: String, holderHash: String, propertyId: String): Register?

    /** The holder's removal watermark, or `null` if it was never removed. */
    fun watermark(ownerId: String, holderHash: String): HlcStamp?

    /**
     * Every contentId [ownerId] holds a **live** edge to (rule 3: none registers excluded). The input
     * to enumeration, the reclaim re-check and the inventory.
     */
    fun referencedContentIds(ownerId: String): Set<String>

    /** Holders with a live edge naming [contentId] (rule 3) — diagnostics, and "is this still live". */
    fun holdersOf(ownerId: String, contentId: String): Set<String>

    // ---- CR-2 B5r: the G2 clock (`content-plane-cr2-b5-spec.md` §1.1) ----------------------------
    //
    // ⚠⚠ [writeEdges] and [removeHolders] keep it themselves, inside their own transaction (lock): a
    // content whose live-edge count they take from one to zero gets `since = now`, and a content a
    // live edge names again loses its row. Kept in every sweep mode.

    /** One row of the clock: [contentId] has had no live edge since [sinceMs]. */
    data class Unreferenced(val ownerId: String, val contentId: String, val sinceMs: Long)

    /** When [contentId]'s last live edge went, or `null` if it has one (or never had one). */
    fun unreferencedSince(ownerId: String, contentId: String): Long?

    /** Every clock row of [ownerId], `contentId -> since`. The quota's held set (D5). */
    fun unreferencedContents(ownerId: String): Map<String, Long>

    /** Clock rows of every owner with `since <= cutoffMs`, oldest first, at most [limit]. The sweep's step 1. */
    fun expiredUnreferenced(cutoffMs: Long, limit: Int = Int.MAX_VALUE): List<Unreferenced>

    /** Every clock row, for the status route's totals. */
    fun unreferencedCount(): Int

    /** Drop [contentId]'s clock row (a free took it out of UNREFERENCED). @return whether there was one. */
    fun clearUnreferenced(ownerId: String, contentId: String): Boolean

    /**
     * Start [contentId]'s clock at [nowMs] **iff it has no live edge** and no clock row yet (an earlier
     * `since` is kept). Used by a revive and by the arming seed (D4). @return whether a row was written.
     */
    fun startClockIfUnreferenced(ownerId: String, contentId: String, nowMs: Long): Boolean
}
