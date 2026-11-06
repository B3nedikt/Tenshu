package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.BACKUP_ROOT
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isTrashedPath
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.isSubtypeOf

/**
 * [PhotosCapturedBetween] — walk the file tree, keep the images captured inside the window, rank
 * them and cap them (`docs/proposals/timeline-photos.md` T-P2).
 *
 * ⚠⚠ **A scan, on purpose.** The alternative shapes were a range predicate in the chain DSL (wire
 * work on the hottest shared path, for a filter that still scans) and a persisted day-bucketed
 * index (a new stored type, a producer, a backfill, and move/delete consistency, forever). What
 * this buys instead is the part that actually cost: the scan no longer crosses IPC. A caller asks
 * once for the span it is about to render — not once per row — so a feed load is one walk of the
 * proxy's own store, which is where the rows already are.
 *
 * ⚠ **The rows are read whole, deliberately.** `File.CreatedAt` is a stored *property*, and the
 * projection that would keep the walk thin (`SKELETON_PROJECTION`) drops exactly the property map
 * it lives in. Since C7 moved both thumbnail classes onto the content plane a `File` row is ~336 B
 * of metadata, so a whole read is no longer the ~18 KB it once was — the measurement that made the
 * skeleton projection necessary is the same one that makes this affordable.
 */
internal class PhotosCapturedBetweenImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val startMs = params?.get(PhotosCapturedBetween.Params.START_MS)?.let { (it as TInteger).get() } ?: 0L
        val endMs = params?.get(PhotosCapturedBetween.Params.END_MS)?.let { (it as TInteger).get() } ?: 0L
        val limit = params?.get(PhotosCapturedBetween.Params.LIMIT)?.let { (it as TInteger).get() } ?: 0L

        if (limit <= 0L || endMs <= startMs) return SimpleTList(emptyList<Data>()) as T

        val found = mutableListOf<Match>()
        val rootId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = "/",
        )
        collectPhotos(executionContext, rootId, startMs, endMs, found)

        val photos = found
            // A photograph you took beats an image that merely shares the minute, and newest wins
            // within each group — so a truncated result drops the least-wanted first rather than an
            // arbitrary slice.
            .sortedWith(compareByDescending<Match> { it.backedUp }.thenByDescending { it.capturedAtMs })
            .take(limit.toInt())
            .map {
                CapturedPhoto.create(
                    context = executionContext.context,
                    path = it.path,
                    capturedAtMs = it.capturedAtMs,
                    backedUp = it.backedUp,
                    latE7 = it.latE7,
                    lngE7 = it.lngE7,
                )
            }

        return SimpleTList(photos) as T
    }

    private data class Match(
        val path: String,
        val capturedAtMs: Long,
        val backedUp: Boolean,
        val latE7: Long,
        val lngE7: Long,
    )

    /**
     * Depth-first walk collecting every image leaf whose capture time falls in `[startMs, endMs)`.
     * Mirrors `OfflineKeeperImplementation.collectFilePaths` — a folder is a `File` subtype carrying
     * a `Folder.Fields.FILES` array.
     *
     * ⚠ "Is it a photo" is the **name**'s answer (`fileCategoryForName`), the same derivation Drive
     * uses for its row glyphs and preview routing. That admits screenshots and saved images by
     * design: [CapturedPhoto.backedUp] ranks those below a photograph the user took, rather than a
     * filter guessing which is which and being silently wrong.
     */
    private fun collectPhotos(
        executionContext: ExecutionContext,
        dataId: DataId,
        startMs: Long,
        endMs: Long,
        out: MutableList<Match>,
    ) {
        val data = executionContext.database.getData(dataId, asEntity = true) ?: return
        if (data !is Entity<*>) return
        val isFolder = executionContext.schemaProvider.getSchema(data.type.get())
            ?.isSubtypeOf(Folder.ID) == true
        if (isFolder) {
            val filesField = data.getFieldValue<Data>(Folder.Fields.FILES)
            val children = executionContext.resolveList(filesField) ?: emptySequence()
            children.forEach { child ->
                executionContext.getDataId(child)
                    ?.let { collectPhotos(executionContext, it, startMs, endMs, out) }
            }
            return
        }

        val path = runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }.getOrNull() ?: return
        // ⚠⚠ PH-G16 (`photos-app.md` PH-5): a photo the user moved to the trash must not keep
        // surfacing in Timeline's moments or as a Mia citation. These two ops are the realm-wide
        // reads OTHER apps make, so the exclusion has to live here — Photos filtering its own feed
        // would leave every other consumer showing deleted photographs.
        if (isTrashedPath(path)) return
        if (fileCategoryForName(path.substringAfterLast('/')) != FileCategory.Image) return
        // No timestamp means the file cannot be placed in time. Dropping it is the only honest
        // answer: reading absence as 0 would file every pre-`CreatedAt` upload under whichever
        // window reaches back that far.
        val capturedAtMs = data.get(File.CreatedAt)?.get() ?: return
        if (capturedAtMs < startMs || capturedAtMs >= endMs) return
        out += Match(
            path = path,
            capturedAtMs = capturedAtMs,
            backedUp = path.startsWith("$BACKUP_ROOT/"),
            // T-P4: absent reads as (0,0) — "no coordinate" — which is what every consumer of a
            // coordinate in this codebase already tests for.
            latE7 = data.get(File.CaptureLatE7)?.get() ?: 0L,
            lngE7 = data.get(File.CaptureLngE7)?.get() ?: 0L,
        )
    }
}
