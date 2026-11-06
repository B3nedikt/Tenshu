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
import org.tenshu.core.file.system.PhotosNear
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.isSubtypeOf

/**
 * [PhotosNear] — walk the file tree, keep the images captured inside the radius, order them
 * nearest-first and cap them. The `where` sibling of `PhotosCapturedBetweenImplementation`,
 * deliberately the same shape so the two read alike.
 *
 * ⚠ **Distance uses `PlaceProximity`'s haversine, not a bounding box.** A degree of longitude
 * shrinks with latitude, so a naive box is wider than it looks near the poles and would quietly
 * include photos that are not in the place asked for.
 */
internal class PhotosNearImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val latE7 = params?.get(PhotosNear.Params.LAT_E7)?.let { (it as TInteger).get() } ?: 0L
        val lngE7 = params?.get(PhotosNear.Params.LNG_E7)?.let { (it as TInteger).get() } ?: 0L
        val radiusM = params?.get(PhotosNear.Params.RADIUS_M)?.let { (it as TInteger).get() } ?: 0L
        val limit = params?.get(PhotosNear.Params.LIMIT)?.let { (it as TInteger).get() } ?: 0L

        if (limit <= 0L || radiusM <= 0L) return SimpleTList(emptyList<Data>()) as T

        val found = mutableListOf<Match>()
        val rootId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = "/",
        )
        collect(executionContext, rootId, latE7, lngE7, radiusM.toDouble(), found)

        val photos = found
            // Nearest first: when the cap truncates, it drops the least-likely-to-be-meant.
            // ⚠ Ties break on newest, so a burst at one spot still comes back newest-first
            // rather than in store order.
            .sortedWith(compareBy<Match> { it.distanceM }.thenByDescending { it.capturedAtMs })
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
        val distanceM: Double,
    )

    private fun collect(
        executionContext: ExecutionContext,
        dataId: DataId,
        centreLatE7: Long,
        centreLngE7: Long,
        radiusM: Double,
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
                    ?.let { collect(executionContext, it, centreLatE7, centreLngE7, radiusM, out) }
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

        val latE7 = data.get(File.CaptureLatE7)?.get() ?: return
        val lngE7 = data.get(File.CaptureLngE7)?.get() ?: return
        // ⚠ (0, 0) is the Atlantic AND what a camera with no fix writes, so the rest of this
        // codebase already reads it as "no coordinate". Agreeing here keeps one meaning for one
        // value — and stops every location-less photo matching a search near Null Island.
        if (latE7 == 0L && lngE7 == 0L) return

        val distance = org.tenshu.core.place.PlaceProximity
            .distanceMeters(centreLatE7, centreLngE7, latE7, lngE7)
        if (distance > radiusM) return

        out += Match(
            path = path,
            capturedAtMs = data.get(File.CreatedAt)?.get() ?: 0L,
            backedUp = path.startsWith("$BACKUP_ROOT/"),
            latE7 = latE7,
            lngE7 = lngE7,
            distanceM = distance,
        )
    }
}
