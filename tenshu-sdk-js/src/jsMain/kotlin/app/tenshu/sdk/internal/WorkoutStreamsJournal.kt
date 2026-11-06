package app.tenshu.sdk.internal

import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.health.ContentId
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.core.schema.propertyRef
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingContentEmit

/**
 * Whether a workout's streams upload is still waiting in the content journal: what a GPX / TCX
 * import reports as `streamsPending` (`Fit.importActivityFile`).
 *
 * `RecordWorkoutImplementation` saves the row, writes the streams blob locally, and then emits it.
 * The emit journals the intent first (`SyncObserver.emitFileContentOwned`, "JOURNAL FIRST, ALWAYS"),
 * so a refused chunk `PUT` leaves the entry for the scheduler's content drain and the op succeeds.
 * ⚠ On web that holds only because a refused `fetch` reaches the relay clients as an `Exception`
 * (`TransportFailureMapping`); unmapped, it escapes the op as a failure after all three writes.
 *
 * The JS runtime's `ExternalPropertyWriter` resolves the workout's content id, so the intent is
 * journalled under it (`WorkoutStreamsOnWebTest`). Entries are matched here by holder and property,
 * not by key family, so an entity-keyed entry is found too.
 */
internal object WorkoutStreamsJournal {

    /**
     * The `Workout.ContentId` the stored row names now, or `null` (no row, or a summary-only one).
     * `RecordWorkout` mints a fresh one per streams-carrying call, so a changed value shows a call
     * re-recorded the row.
     */
    fun storedContentId(ec: ExecutionContext, workoutId: String): String? {
        val row = ec.database.getData(holder(ec, workoutId), asEntity = true) ?: return null
        return (ec.resolveProperty(row, propertyRef(Workout.ContentId.id)) as? TString)
            ?.get()?.takeIf { it.isNotBlank() }
    }

    /** Is an upload of [workoutId]'s `Workout.Streams` waiting in the content journal? */
    fun uploadJournalled(ec: ExecutionContext, workoutId: String): Boolean = journalled(ec, workoutId) > 0

    /**
     * How many journal entries name [workoutId]'s streams. ⚠ One at most: the entity-keyed intent
     * is replaced, not added to, by a re-record (`PendingContentEmit.emitKey`), so a retried import
     * queues nothing extra.
     */
    fun journalled(ec: ExecutionContext, workoutId: String): Int {
        val hash = holder(ec, workoutId).getDataIdHash()
        return entries(ec).count {
            it.propertyId.get() == Workout.Streams.id && it.targetEntityId.getDataIdHash() == hash
        }
    }

    /**
     * The content id each journal entry for [workoutId]'s streams is keyed under, in journal order.
     * An empty string is an entry on the entity-keyed branch, which a workout whose row names
     * a content id must never be on (`WorkoutStreamsOnWebTest`).
     */
    fun journalledContentIds(ec: ExecutionContext, workoutId: String): List<String> {
        val hash = holder(ec, workoutId).getDataIdHash()
        return entries(ec)
            .filter { it.propertyId.get() == Workout.Streams.id && it.targetEntityId.getDataIdHash() == hash }
            .map { it.contentId.get() }
    }

    /**
     * Retires the upload intent for the streams content a re-record superseded — `UploadFile`'s own
     * seam for an overwrite (`discardSupersededContent`). ⚠⚠ The journal is keyed by content, and
     * `RecordWorkout` mints a fresh `contentId` per call, so a retried import's old intent stays
     * beside the new one. The
     * drain would retire it eventually, or, with the same bytes at the same path, PUBLISH it a second
     * time under a dead id. Retired only while the row names other content (the seam checks).
     * Best-effort: the drain is the backstop.
     */
    suspend fun discardSuperseded(ec: ExecutionContext, workoutId: String, supersededContentId: String) {
        val emitter = JsRuntime.syncBlockEmitterHolder.fileContentEmitter ?: return
        runCatching {
            emitter.discardSupersededContent(holder(ec, workoutId), listOf(supersededContentId to Workout.Streams.id))
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private fun holder(ec: ExecutionContext, workoutId: String): DataId =
        DataId.StringDataId.create(context = ec.context, type = Workout.ID, keyValue = workoutId)

    private fun entries(ec: ExecutionContext): List<PendingContentEmit> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = ec.context,
                type = PendingContentEmit.ID,
                relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
            ),
            isEntity = false,
            elementSchemaId = PendingContentEmit.ID,
        )
        return ec.database.getArrayData(ref)?.filterIsInstance<PendingContentEmit>()?.toList().orEmpty()
    }
}
