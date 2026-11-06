package org.tenshu.core.file.sync

import org.tenshu.core.entity.DataId

/**
 * What the backfill (`content-plane-refcount.md` §9.7, CR-4d) found to declare.
 *
 * ⚠⚠ **[walkComplete] is not [LiveContentIds.Result.Incomplete], and the difference is the point.**
 * The veto's walk must be *unable* to be mistaken for complete, because a short one deletes live
 * content — so it carries no partial set at all. A short **backfill** declares fewer edges, so
 * fewer things become candidates: a leak, never a loss. It is therefore right to declare what was
 * found and wrong to record the pass as done, which is exactly the pair of facts this type carries.
 *
 * ⚠ Recording "done" over a partial pass is the failure this shape exists to prevent: the backfill
 * runs once by design, so a latched marker over a degraded run strands whatever it missed for ever.
 */
object DeclarableContent {

    /**
     * One declarable edge, with the provenance an edge's key needs.
     *
     * ⚠⚠ The holder is a [DataId], never a pre-computed hash. Its relay-visible name is keyed
     * (`sync-entity-identity.md` EI-1), only the sync layer holds the key, and an edge must carry the
     * exact name the holder's tombstone will later undeclare. A walk that named holders itself
     * declared under the local `getDataIdHash()`, which no tombstone matches since EI-1, so every
     * backfilled edge stood for ever (`content-plane-refcount.md` §9.14).
     */
    data class Entry(
        val holder: DataId,
        val propertyId: String,
        val contentId: String,
    )

    data class Result(
        val entries: List<Entry>,
        /** True only when every folder was readable. See the class KDoc. */
        val walkComplete: Boolean,
        /**
         * Rows the walk read, whether or not they held content. **Null when the walk does not count.**
         * ⚠ Null and 0 are different answers: 0 is a counted walk over a realm with no declaring rows,
         * and it has to be printable as such, or it reads like a walk that never counted.
         */
        val rowsRead: Int? = null,
        val skipped: Skipped = Skipped(),
    )

    /**
     * Rows the walk read and could **not** ask about, which is a different fact from a row that holds
     * no content.
     *
     * ⚠⚠ None of these is in [Result.entries], so none is declared, described, or counted as "without
     * refs". Until they were counted, a walk over a mostly unreadable store and a walk over a small
     * realm produced the same line: on 2026-10-01 a desktop declared 492 edges where about 8,000 were
     * expected, recorded itself complete, and nothing could say which of the two it was.
     *
     * ⚠ They do **not** clear [Result.walkComplete]. One permanently undecodable row would then re-run
     * the whole pass on every slot for ever, which is how counting "without refs" against completeness
     * once made the marker unlatchable. They are reported, so the number is seen before that is decided.
     */
    data class Skipped(
        /** The stored row would not decode, for example a nested row it references is missing. */
        val undecodable: Int = 0,
        /** The row decoded and no `DataId` could be derived for it, so the port could not be asked. */
        val unkeyed: Int = 0,
        /**
         * **Properties**, not rows, for which `resolveContentId` threw. A null or blank answer is "no
         * content", not this. ⚠ Kept out of [rows]: a row has several content properties, so one row
         * can fail here more than once while its other properties still declare.
         */
        val unresolvedProperties: Int = 0,
    ) {
        /** Rows that declared nothing because they could not be read at all. */
        val rows: Int get() = undecodable + unkeyed

        val any: Boolean get() = rows > 0 || unresolvedProperties > 0
    }
}
