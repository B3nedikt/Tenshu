package org.tenshu.timeline.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.timeline.Trip
import org.tenshu.core.timeline.TripMember
import org.tenshu.sync.common.SemanticMerger
import org.tenshu.sync.common.resolveListField

/**
 * Converges two devices' versions of the same [Trip] (I5, `docs/proposals/timeline-services.md`
 * §5.2). A `Trip` is content-addressed by `tripId`, so when two devices each detect-and-accept the
 * *same* trip (or a receiving device refines an incoming one), their states land on one key and this
 * merger fires under `Trip`'s `ConflictStrategy.Merge`. It **unions the membership** (member moment
 * ids) so a trip gets *more complete* as data converges — Phone B, holding a Lisbon photo Phone A
 * lacked, adds it to the same trip without a second "is this a trip?" prompt.
 *
 * The same seam and shape as `FolderMerger`: read both collection fields with `resolveListField`
 * (which resolves a lazy or materialised list), union by a stable key, and keep the newer state's
 * scalar metadata. Pure — no IO beyond `resolveList` — so it is safe inside `SemanticMerger.merge`
 * (the home-dependent detection that *populates* membership runs at detect-time / with home passed
 * in, never here; §10 purity, resolved in I5a).
 *
 * ⚠ The merge only fires when the incoming HLC is strictly newer than the local row
 * (`SyncProcessor.resolveConflictAndSave`), so incoming's `title`/`dateRange`/`year` are the latest —
 * membership is the only field unioned rather than overwritten.
 */
class TripMerger : SemanticMerger {
    override val typeId: String = Trip.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingMembers = memberIds(executionContext, existing)
        val incomingMembers = memberIds(executionContext, incoming)

        // Union, preserving order: existing members first, then incoming members not already present.
        val merged = LinkedHashSet<String>().apply {
            addAll(existingMembers)
            addAll(incomingMembers)
        }.toList()

        return Entity.create<Trip>(
            context = executionContext.context,
            composite = Trip.create(
                context = executionContext.context,
                tripId = stringField(incoming, Trip.Fields.TRIP_ID),
                title = stringField(incoming, Trip.Fields.TITLE),
                dateRange = stringField(incoming, Trip.Fields.DATE_RANGE),
                year = stringField(incoming, Trip.Fields.YEAR),
                membership = merged,
            ),
        )
    }

    private fun memberIds(executionContext: ExecutionContext, data: Data): List<String> =
        executionContext.resolveListField(data, Trip.Fields.MEMBERSHIP)
            .filterIsInstance<TripMember>()
            .map { it.momentId.get() }

    private fun stringField(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }
}
