package org.tenshu.core.place

/**
 * The **pure** half of place reconciliation (`timeline-recognition-and-place-naming.md` §10.3 /
 * §9.5 F6): given what a device knows about its places, decide which of them are one venue and which
 * row survives. No IO, no schema types — so it is unit-testable without a proxy, and the proxy-side
 * `PlaceReconciler` is left holding only the store access.
 *
 * ⚠⚠ **The survivor is deterministic — the lexicographically smallest `placeId` in the cluster.**
 * Two devices reconciling the same pair must write the *same* pointer, or the merge plane can only
 * pick the newer of two contradictory ones and the answer depends on who pulled first. This is
 * `MomentReconciler`'s trick (elect off a deterministic invariant, never off arrival order) applied
 * one level down.
 */
object PlaceClustering {

    /** What clustering needs to know about one stored place. */
    data class Candidate(
        val placeId: String,
        val latE7: Long,
        val lngE7: Long,
        val accuracyM: Int = 0,
        val label: String = "",
        val labelSource: String = LabelSource.GEOCODED,
        /** Network tags this place is fingerprinted by (§9.3) — corroboration, when available. */
        val networkTags: Set<String> = emptySet(),
    ) {
        val hasUserLabel: Boolean get() = LabelSource.isUser(labelSource) && label.isNotBlank()
    }

    /** One decided fold: [placeId] is really [mergedInto]. */
    data class Merge(val placeId: String, val mergedInto: String)

    /** What the labels alone can settle about a pair — see [mayMerge]. */
    enum class Verdict {
        /** Nothing contests the fold: labels blank, equal, or a machine guess against a user's name. */
        ALLOW,

        /** The user named both, differently. The user is authoritative; no evidence overrides this. */
        VETO,

        /** Both named, both machine-guessed, and the guesses disagree — §10.12's ambiguous middle. */
        NEEDS_CORROBORATION,
    }

    /**
     * The merges implied by [anchor] and the places around it, given the [existingMerges] already
     * recorded (`placeId → mergedInto`). Returns only merges that are **not already recorded with
     * that same target**, so a re-run over unchanged input yields nothing — the idempotence the
     * observer depends on to avoid writing itself into a loop (§10.3).
     *
     * An already-merged neighbour is not skipped but **resolved to its root** — see the ⚠ inside.
     *
     * [corroboration] arbitrates only [Verdict.NEEDS_CORROBORATION] pairs, and only after the free
     * signals have been tried — see [permits].
     */
    fun mergesFor(
        anchor: Candidate,
        others: Collection<Candidate>,
        existingMerges: Map<String, String>,
        corroboration: PlaceCorroboration = PlaceCorroboration.Unknown,
    ): List<Merge> {
        if (anchor.placeId in existingMerges) return emptyList()

        val near = others.filter { other ->
            other.placeId != anchor.placeId &&
                // ⚠ Geometry first, deliberately: [corroboration] may read the store, and asking it
                // about every distant place would put that read on the hot path. Only a pair that is
                // already spatially plausible can reach it.
                isOneVenue(anchor, other) &&
                permits(anchor, other, corroboration)
        }
        if (near.isEmpty()) return emptyList()

        // ⚠ Resolve each neighbour to the row it has *already* been folded into, rather than skipping
        // it. Skipping strands the far end of a chain: with cells A–B–C where C touches B but not A,
        // once B has folded into A, anchoring on C would see only a settled B, cluster alone, and C
        // would stay a duplicate forever. Resolving means C clusters with A and the whole run
        // collapses onto one row.
        val ids = (listOf(anchor.placeId) + near.map { rootOf(it.placeId, existingMerges) }).distinct()
        if (ids.size < 2) return emptyList()

        val root = ids.min()
        return ids
            .filter { it != root && existingMerges[it] != root }
            .map { Merge(placeId = it, mergedInto = root) }
    }

    /**
     * The two ways one venue ends up as two rows, and both must be caught:
     *  - the centroids are within the accuracy-scaled radius ([PlaceProximity]) — the general case;
     *  - the ids sit in **touching grid cells** ([PlaceGrid]) — the artefact of id minting itself,
     *    which a metre radius provably cannot span in the latitude axis (see [PlaceGrid]'s ⚠⚠).
     */
    private fun isOneVenue(a: Candidate, b: Candidate): Boolean =
        PlaceProximity.isSamePlace(a.latE7, a.lngE7, a.accuracyM, b.latE7, b.lngE7, b.accuracyM) ||
            PlaceGrid.areAdjacent(a.placeId, b.placeId)

    /** Follow [merges] to the surviving row, with the same cycle-safety as `PlaceMerge.resolveRoot`. */
    private fun rootOf(placeId: String, merges: Map<String, String>): String {
        var current = placeId
        val seen = mutableSetOf(current)
        while (true) {
            val next = merges[current]?.takeIf { it.isNotBlank() && it != current } ?: return current
            if (!seen.add(next)) return seen.min()
            current = next
        }
    }

    /**
     * The corroboration gate (§9.5, three-valued since §10.12). Auto-merging places is riskier than
     * auto-merging moments — a café and the shop next door are metres apart and both real, and a wrong
     * fold here is visible and sticky. The one thing that outright vetoes a merge is **the user having
     * named both, differently**.
     *
     * ⚠ **Deliberately narrower than the §9.5 draft**, which also required "at least one side
     * unnamed, or the same user label, or a shared network tag". A *geocoded* label must not veto:
     * geocoded names are machine guesses, and the reported bug is precisely the geocoder naming two
     * cells of one venue differently (a street name beside the venue's). Letting a guess block
     * the fold would preserve exactly the fragmentation this pass exists to repair.
     *
     * What §10.12 adds is the middle: **both named, both guessed, and the guesses disagree** — the one
     * bucket that was folding on adjacency alone, and which holds both the town-hall pause on a walk to an ice cream shop
     * (merging was right) and the café-and-the-bookshop-next-door (merging would be wrong). That, and
     * only that, is handed to a [PlaceCorroboration].
     *
     * ⚠⚠ **A machine guess never contests a user's name.** One side user-authored and the other
     * geocoded differently stays [Verdict.ALLOW] — routing it through corroboration would let a pair
     * of everyday places whose visits happen never to abut (home, captured in two cells on different
     * days) stop merging, which is the re-fragmentation §12.2 forbids.
     */
    fun mayMerge(a: Candidate, b: Candidate): Verdict = when {
        a.hasUserLabel && b.hasUserLabel && a.label != b.label -> Verdict.VETO
        a.hasUserLabel || b.hasUserLabel -> Verdict.ALLOW
        a.label.isBlank() || b.label.isBlank() -> Verdict.ALLOW
        a.label == b.label -> Verdict.ALLOW
        else -> Verdict.NEEDS_CORROBORATION
    }

    /**
     * Resolve [mayMerge]'s verdict into the yes/no the fold needs.
     *
     * The ladder for the ambiguous bucket is **cheapest and strongest first** (§12.3 D3): a shared
     * network tag means the two rows saw the same access point, which is tens of metres of evidence
     * already in hand since F4 synced fingerprints; only when that is silent is [corroboration] asked,
     * and only it may need to read anything.
     *
     * ⚠⚠ **No evidence means merge** (§12.7 D1) — the `?: true`. Absence of a moment window is not
     * proof of distinctness, and treating it as one would quietly revert the pass to the fragmented
     * behaviour this track repaired. Corroboration may only ever *decide* the ambiguous middle; it can
     * never widen it.
     */
    private fun permits(a: Candidate, b: Candidate, corroboration: PlaceCorroboration): Boolean =
        when (mayMerge(a, b)) {
            Verdict.ALLOW -> true
            Verdict.VETO -> false
            Verdict.NEEDS_CORROBORATION ->
                sharesNetworkTag(a, b) || (corroboration.sameVenue(a, b) ?: true)
        }

    /** Both rows fingerprinted by the same access point ⇒ the same few tens of metres. */
    private fun sharesNetworkTag(a: Candidate, b: Candidate): Boolean =
        a.networkTags.isNotEmpty() && a.networkTags.any { it in b.networkTags }

    /**
     * The user-authored name a cluster should end up carrying, or `null` when the [root] already has
     * one (or nobody does). Merging must not lose a name just because the row the user renamed is not
     * the one that deterministically survives — §9.4's "the name is not at the mercy of the pointer".
     * Ties break on the lowest id, so every device picks the same name.
     */
    fun userLabelToCarry(root: Candidate, merged: Collection<Candidate>): String? {
        if (root.hasUserLabel) return null
        return merged.filter { it.hasUserLabel }.minByOrNull { it.placeId }?.label
    }
}
