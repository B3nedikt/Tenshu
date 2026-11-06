package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.generateId
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.ContentId
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.Streams
import org.tenshu.core.health.Workout
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * Records (upserts) a [Workout] and, when present, routes its dense route/HR stream blob to the
 * **content plane** — the health twin of `UploadFileImplementation`'s entity-then-bytes recipe
 * (read-scaling option B, `docs/proposals/fit-workout-streams-content-plane.md`): the blob never
 * touches the entity row, so a full-history import keeps the entity cache and every receiving
 * device's ingest small.
 */
internal class RecordWorkoutImplementation(
    private val fileDatabase: FileDatabase,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val workout: Workout = params.read(RecordWorkout.Params.WORKOUT)
        // Optional dense route + HR blob — absent for a summary-only workout.
        val streams = params?.get(RecordWorkout.Params.STREAMS) as? TByteArray

        // The content identity the streams below are addressed by (`content-plane-refcount.md`
        // §9.12). Only minted when there are streams: it names content, not the workout.
        //
        // ⚠⚠ BEFORE the save, and the ordering is load-bearing twice over. `resolveContentId` reads
        // the **stored** workout, so an id written afterwards is invisible to the emit it exists
        // for — and adding it in a second save would emit a second entity block onto the ordered
        // drain, which is the plane this id exists to keep the manifest off.
        //
        // ⚠ Fresh per call. A re-record must mint a NEW id, so the superseded content is left
        // declared-with-no-live-edge — reclaimable (§9.5) rather than orphaned under an id that is
        // still declared.
        // ⚠ A summary-only record mints nothing. (B6 retired the reference root this block also
        // carried forward across a re-record; nothing but the content id is written here now.)
        val recorded = if (streams != null) {
            Entity.create<Workout>(context = executionContext.context, composite = workout) {
                set(Workout.ContentId, generateId())
            }
        } else {
            workout
        }

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `WorkoutRelationMaintainer` (adds to `HealthService.Relations.WORKOUTS`). Never
        // `Database.saveData` / `addDataToArray`, which bypass `SyncObserver`.
        executionContext.saveData(recorded)

        if (streams != null) {
            val workoutId = workout.getField<TString>(Workout.Fields.WORKOUT_ID).get()
            val streamPath = org.tenshu.health.proxy.workoutStreamsPath(workoutId)
            // Local write first: `GetWorkoutStreams` resolves from here, so the blob is
            // readable on this device the moment the op returns — sync or no sync.
            fileDatabase.writeByteArray(filePath = streamPath, bytes = streams)

            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = Workout.ID,
                keyValue = workoutId
            )
            // Entity-before-bytes (ADR 0015): `saveData` only *enqueues* the entity block on
            // the async emit consumer; drain it so the entity is HLC-ordered ahead of the
            // content — a receiver getting bytes first has no Workout to resolve the external
            // path against and drops them (the proactive push never retries).
            syncBlockEmitterHolder?.emitter?.awaitEmitIdle()

            val contentEmitter = syncBlockEmitterHolder?.fileContentEmitter
            if (contentEmitter != null) {
                // Chunked + CEK-sealed content emit off the FileDatabase path. ⚠ Content-addressed
                // when the workout carries a `contentId` — which routes the manifest to the content
                // plane instead of the ordered drain — and entity-id-keyed when it does not.
                contentEmitter.emitFileContent(
                    targetEntityId = dataId,
                    propertyId = Workout.Streams.id,
                    filePath = streamPath
                )
            } else {
                // No content emitter wired (test harnesses without a relay content store):
                // fall back to the single property block so the receiver-side
                // `WorkoutStreamsPropertyWriter` path keeps working. Null holder = no sync at
                // all → the blob stays device-local.
                syncBlockEmitterHolder?.emitter?.emitPropertyBlock(
                    targetEntityId = dataId,
                    propertyId = Workout.Streams.id,
                    value = streams
                )
            }
        }

        return null as T
    }
}
