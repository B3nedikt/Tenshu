package org.tenshu.core.place

/**
 * Evidence, beyond the labels and the geometry, that two places are (or are not) one venue —
 * consulted **only** for the ambiguous bucket [PlaceClustering.mayMerge] cannot decide
 * (`timeline-recognition-and-place-naming.md` §10.12).
 *
 * ⚠⚠ **Three-valued on purpose.** `null` is "I have no evidence", and it is a different answer from
 * `false` ("I looked, and these visits belong to two different venues"). Collapsing the two would make
 * the absence of a moment window — an old pair, a place-only wiring, a fresh device — read as proof of
 * distinctness and silently stop merging the fragments this whole track exists to repair.
 *
 * ⚠ It is asked only about pairs that are already spatially plausible *and* already ambiguous, which
 * is what keeps an implementation free to do a store read: the ambiguous pair is rare, and every
 * cheaper signal has been tried first.
 */
fun interface PlaceCorroboration {

    /**
     * `true` = one venue, `false` = two venues, `null` = no evidence either way.
     *
     * Implementations must be **deterministic from stored rows** — the reconciliation pass re-enters
     * itself, so a verdict that varied between runs would make it flip-flop.
     */
    fun sameVenue(a: PlaceClustering.Candidate, b: PlaceClustering.Candidate): Boolean?

    companion object {
        /** The honest default for a wiring with no timeline: knows nothing, decides nothing. */
        val Unknown: PlaceCorroboration = PlaceCorroboration { _, _ -> null }
    }
}
