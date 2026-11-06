package org.tenshu.sync.proxy

/**
 * ⭐ **Adds before removes** (CR-2 B4, `content-plane-cr2-b4-spec.md` §2.3; decided in
 * `content-plane-cr2-quorumless.md` §10.14): a removal of content C is not pushed while any queued,
 * unacknowledged block has an edge naming C. That makes a device's own move — a block for the new
 * path and a removal of the old — never pass through a zero-edge state, across retries, refusals, a
 * lane boundary or a restart, which is what lets T shrink from 24 h to minutes.
 *
 * This is the block half's decision, kept pure so it can be pinned without a relay:
 * - a block whose **cleared** ids intersect the **adds** of a later queued block for a **different**
 *   holder waits for that block (it is pushed after it, and only once it was accepted);
 * - blocks for the **same** holder never reorder: one waits for every earlier one the rule deferred;
 * - a wait on a block that was **attempted and not accepted** this tick holds to a later tick — the
 *   deliberate case of an add behind a closed upload gate: holding is visible as pending sync,
 *   pushing would free content the add still needs.
 *
 * ⚠ A pure cycle of deferrals (two holders swapping content) would wait for ever, so the earliest
 * block of a cycle that reaches no attempted block is released ([breakCycle]). That reopens the
 * zero-edge window only for that swap, and only until its other half lands.
 */
internal class AddsBeforeRemoves(entries: List<Entry>) {

    /** One queued block, as its sidecar describes it. */
    data class Entry(
        val blockId: String,
        /** The block's wire target, or null for a batch (no holder, no edges). */
        val holder: String?,
        /** The content ids its edges name. */
        val adds: Set<String>,
        /** The content ids its save cleared. */
        val cleared: Set<String>,
    )

    private val order = entries.map { it.blockId }
    private val byId = entries.associateBy { it.blockId }
    private val position = order.withIndex().associate { (i, id) -> id to i }
    private val remaining = order.toMutableSet()
    private val attempted = mutableSetOf<String>()

    // ⚠ Indexed, because a bulk upload queues thousands of blocks and a pass asks every one of them:
    // a scan per question would be quadratic per pass.
    private val byHolder: Map<String, List<String>> = entries.filter { it.holder != null }
        .groupBy({ it.holder!! }, { it.blockId })
    private val addersOf: Map<String, List<String>> = entries
        .flatMap { entry -> entry.adds.map { it to entry.blockId } }
        .groupBy({ it.first }, { it.second })

    fun isRemaining(blockId: String): Boolean = blockId in remaining

    /** Blocks not yet accepted or dropped, in queue order. */
    fun remaining(): List<String> = order.filter { it in remaining }

    /** The remaining, unattempted blocks that wait on nothing, in queue order. */
    fun pushable(): List<String> = order.filter { it in remaining && it !in attempted && waitsOn(it).isEmpty() }

    /** [blockId] was pushed and refused or failed: it stays, and whatever waits on it holds. */
    fun attempted(blockId: String) {
        attempted += blockId
    }

    /** [blockId] left the queue (accepted, or dropped by a resolution). */
    fun done(blockId: String) {
        remaining -= blockId
    }

    /**
     * The earliest remaining, unattempted block whose waits form a pure cycle — none of them, followed
     * transitively, reaches an attempted block. Null when there is none.
     */
    fun breakCycle(): String? = order.firstOrNull { id ->
        id in remaining && id !in attempted && waitsOn(id).isNotEmpty() && !reachesAttempted(id, mutableSetOf())
    }

    /** Whether a removal of any of [contents] must wait: a remaining block adds one of them. */
    fun addsAnyOf(contents: Set<String>): Boolean =
        contents.any { c -> addersOf[c].orEmpty().any { it in remaining } }

    private fun reachesAttempted(id: String, seen: MutableSet<String>): Boolean {
        if (!seen.add(id)) return false
        return waitsOn(id).any { it in attempted || reachesAttempted(it, seen) }
    }

    private fun waitsOn(id: String): List<String> {
        val self = byId.getValue(id)
        val at = position.getValue(id)
        // An earlier block of the same holder the rule is still deferring: keep their order.
        val sameHolder = self.holder?.let { byHolder[it] }.orEmpty().filter { other ->
            other != id && other in remaining && other !in attempted && position.getValue(other) < at
        }
        // A later block of another holder that adds what this one clears.
        val adders = self.cleared.flatMap { addersOf[it].orEmpty() }.filter { other ->
            other != id && other in remaining && position.getValue(other) > at &&
                (self.holder == null || byId.getValue(other).holder != self.holder)
        }
        return (sameHolder + adders).distinct()
    }
}
