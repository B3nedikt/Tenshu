package org.tenshu.timeline.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.sync.common.SemanticMerger
import org.tenshu.sync.common.resolveListField

/**
 * Converges two devices' versions of the same [Moment] (I6, `docs/proposals/timeline-ingestion.md`
 * §7.1). Cross-source reconciliation elects a **canonical** moment keyed *deterministically* off the
 * cluster invariant (place cell + time bucket, `MomentReconciler`), so two devices that reconcile
 * independently land the *same* canonical id — ordinary sync collides them on that key, and under
 * `Moment`'s `ConflictStrategy.Merge` this merger fires and **unions the `sources` provenance set**.
 * The canonical thus accumulates every source's confirmation as device data converges, exactly as
 * `TripMerger` unions a trip's membership.
 *
 * The same seam and shape as `TripMerger`/`FolderMerger`: read both collection fields with
 * `resolveListField` (which resolves a lazy or materialised list), union by a stable key
 * (the source tag), and keep the newer state's scalar metadata. Pure — no IO beyond `resolveList` —
 * so it is safe inside `SemanticMerger.merge`.
 *
 * ⚠ The merge only fires when the incoming HLC is strictly newer than the local row
 * (`SyncProcessor.resolveConflictAndSave`), so incoming's scalars (`title`, `supersededBy`, …) are the
 * latest — `sources` is the only field unioned rather than overwritten. Because the reconciliation pass
 * stamps `supersededBy` deterministically off the same invariant, both devices agree on it and
 * last-writer-wins is consistent.
 */
class MomentMerger : SemanticMerger {
    override val typeId: String = Moment.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingSources = sourceTags(executionContext, existing)
        val incomingSources = sourceTags(executionContext, incoming)

        // Union, preserving order: existing sources first, then incoming sources not already present.
        val merged = LinkedHashSet<String>().apply {
            addAll(existingSources)
            addAll(incomingSources)
        }.toList()

        return Entity.create<Moment>(
            context = executionContext.context,
            composite = Moment.create(
                context = executionContext.context,
                momentId = stringField(incoming, Moment.Fields.MOMENT_ID),
                timestamp = timestampField(incoming),
                kind = stringField(incoming, Moment.Fields.KIND),
                title = stringField(incoming, Moment.Fields.TITLE),
                subject = subjectField(incoming),
                durationSec = intField(incoming, Moment.Fields.DURATION_SEC),
                sources = merged,
                supersededBy = stringField(incoming, Moment.Fields.SUPERSEDED_BY),
            ),
        )
    }

    private fun sourceTags(executionContext: ExecutionContext, data: Data): List<String> =
        executionContext.resolveListField(data, Moment.Fields.SOURCES)
            .filterIsInstance<MomentSource>()
            .map { it.source.get() }

    private fun stringField(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }

    private fun intField(data: Data, fieldId: String): Long = when (data) {
        is Entity<*> -> data.getFieldValue<TInteger>(fieldId).get()
        is Composite -> data.getField<TInteger>(fieldId).get()
        else -> 0L
    }

    private fun timestampField(data: Data): org.tenshu.core.time.UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(Moment.Fields.TIMESTAMP)
        is Composite -> data.getField(Moment.Fields.TIMESTAMP)
        else -> error("Moment has no timestamp")
    }

    private fun subjectField(data: Data): SubjectRef = when (data) {
        is Entity<*> -> data.getFieldValue(Moment.Fields.SUBJECT)
        is Composite -> data.getField(Moment.Fields.SUBJECT)
        else -> error("Moment has no subject")
    }
}
