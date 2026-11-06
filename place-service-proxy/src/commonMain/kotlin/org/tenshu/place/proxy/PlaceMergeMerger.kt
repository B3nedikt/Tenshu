package org.tenshu.place.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.time.UnixInstant
import org.tenshu.sync.common.SemanticMerger

/**
 * Converges two devices' merge pointers for one place
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §9.4).
 *
 * Two rules, and the split between them is the whole design:
 *
 *  - **both targets set and different** → the **smaller id** wins. That is the same deterministic rule
 *    that picks a target in the first place, so two devices that folded the same pair independently
 *    land on the same answer regardless of which pulled first. Side-independent by construction — a
 *    merger only fires when the incoming block is strictly newer, so "keep mine"/"take theirs" would
 *    resolve by arrival order; `min` does not.
 *  - **one side blank** → take the **incoming** (newer) state. ⚠⚠ This is what makes **unmerge
 *    converge** (§9.4). An unmerge writes a blank target, and the first version of this merger
 *    preferred non-blank unconditionally — so a peer's stale merge row would silently *undo the user's
 *    undo*, forever. Blank is not "no opinion", it is "the user said these are different places", and
 *    the newer opinion is the one that counts. A stale peer cannot resurrect the merge either: an
 *    older block never reaches the merger at all (`SyncProcessor` drops it whole).
 */
class PlaceMergeMerger : SemanticMerger {
    override val typeId: String = PlaceMerge.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingTarget = string(existing, PlaceMerge.Fields.MERGED_INTO)
        val incomingTarget = string(incoming, PlaceMerge.Fields.MERGED_INTO)
        val target = when {
            // Both set and differing → the deterministic root, so independent devices agree.
            existingTarget.isNotBlank() && incomingTarget.isNotBlank() ->
                minOf(existingTarget, incomingTarget)
            // One side blank → the newer state, which is the incoming one by definition of when a
            // merger runs. See the ⚠⚠ in the class KDoc: this is unmerge's convergence.
            else -> incomingTarget
        }
        // Keep the earliest recorded time while the pair stays merged — "since when" is honestly the
        // first device to notice. ⚠ An unmerge resets it to the incoming stamp instead: the old
        // "merged since" is meaningless once the merge is gone, and keeping it would date a *future*
        // re-merge to before the unmerge.
        val existingAt = timestamp(existing)
        val incomingAt = timestamp(incoming)
        val mergedAt = when {
            target.isBlank() -> incomingAt
            existingAt.toEpochMilliseconds() <= incomingAt.toEpochMilliseconds() -> existingAt
            else -> incomingAt
        }

        return Entity.create<PlaceMerge>(
            context = executionContext.context,
            composite = PlaceMerge.create(
                context = executionContext.context,
                placeId = string(incoming, PlaceMerge.Fields.PLACE_ID),
                mergedInto = target,
                mergedAt = mergedAt,
            ),
        )
    }

    private fun string(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }

    private fun timestamp(data: Data): UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(PlaceMerge.Fields.MERGED_AT)
        is Composite -> data.getField(PlaceMerge.Fields.MERGED_AT)
        else -> error("PlaceMerge has no ${PlaceMerge.Fields.MERGED_AT}")
    }
}
