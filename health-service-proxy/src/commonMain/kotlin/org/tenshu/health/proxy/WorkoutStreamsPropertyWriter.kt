package org.tenshu.health.proxy

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.content.contentEdgeOf
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.Streams
import org.tenshu.core.health.ContentId
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.health.Workout

/**
 * Joiner/receiver-side writer for a workout's on-demand stream blob —
 * the health twin of `FileBytesPropertyWriter`. When `SyncProcessor.attachPropertyAndSave` receives a
 * decrypted `DataBlockPayload(propertyId = Workout.Streams.id, value = TByteArray(bytes))`, this looks
 * up the `Workout` by its synced `StringDataId`, derives its external key `/.health/streams/{workoutId}`,
 * and writes the bytes to the local [FileDatabase] — the same store files use, namespaced by the
 * `/.health/…` prefix (proposal Q1).
 *
 * Registered in the writer list `SyncProcessor` consults, alongside `FileBytesPropertyWriter`
 * (proposal "Wiring findings"). No-ops if the parent `Workout` entity hasn't synced yet — the OnDemand
 * resolve retries once it lands (entity-before-bytes tolerance, as with files).
 */
/**
 * The external-store key for a workout's content-plane stream blob. `/.health/…`
 * namespaces it away from file paths (mirrors `/.shared/…`) — the one string the
 * writer, the resolver op, the record emit and the tests must all agree on.
 */
fun workoutStreamsPath(workoutId: String): String = "/.health/streams/$workoutId"

class WorkoutStreamsPropertyWriter(
    private val fileDatabase: FileDatabase,
) : ExternalPropertyWriter {

    override fun supports(propertyId: String): Boolean = propertyId == Workout.Streams.id

    override fun write(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        value: Data,
    ) {
        val bytes = value as? TByteArray ?: return
        val path = resolveExternalPath(executionContext, targetEntityId, propertyId) ?: return
        fileDatabase.writeByteArray(filePath = path, bytes = bytes)
    }

    override fun resolveExternalPath(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? {
        val workout = executionContext.database.getData(targetEntityId) as? Workout ?: return null
        val workoutId = runCatching { workout.getField<TString>(Workout.Fields.WORKOUT_ID).get() }.getOrNull()
            ?: return null
        return workoutStreamsPath(workoutId)
    }

    /**
     * The workout's content identity (`content-plane-refcount.md` §9.12) — an answer here routes
     * the manifest to the **content plane**; `null` routes it to an entity-id-keyed block on the
     * ordered drain.
     *
     * ⚠ `null` is a valid answer, not a failure: a summary-only workout owns no content, and a
     * workout whose streams were never emitted has no id yet. Both belong on the entity-id-keyed
     * branch, which resolves correctly.
     */
    override fun resolveContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? {
        if (!supports(propertyId)) return null
        // ⭐ CR-2 B3a: through the schema's declaration (`Workout.ContentId` bound to
        // `ContentReferencing.CONTENT_ID`, `PrimaryContent` on `Workout.Streams`), the same
        // declarations the backfill walk and B3's block builder use. ⚠ Still the stored
        // row, because this port takes a `DataId`; B3d builds edges from the payload instead.
        val registry = executionContext.schemaProvider as? SchemaRegistry
        val row = executionContext.database.getData(targetEntityId, asEntity = true) ?: return null
        // ⚠ The one-property path: building every declaration per call is a relation walk per row.
        registry?.let { contentEdgeOf(it, row, propertyId) }?.let { return it.contentId }
        println("WorkoutStreamsPropertyWriter -> no content declaration answered for $propertyId; reading Workout.ContentId directly")
        return (
            executionContext.resolveProperty(row, propertyRef(Workout.ContentId.id)) as? TString
            )?.get()?.takeIf { it.isNotBlank() }
    }
}
