package org.tenshu.core.file.system

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Every image captured within [radiusM] of a coordinate, nearest first, capped at [limit].
 *
 * The sibling of [PhotosCapturedBetween]: that one answers *when*, this one answers *where*, and
 * both exist for the same reason — the chain DSL has `equalTo`, `contains` and `inRange`, and a
 * **radius is none of them**. Great-circle distance is not a range test on either coordinate
 * (a degree of longitude is not a fixed distance), so no predicate we could add would express it.
 *
 * ⚠⚠ **Deliberately takes a coordinate, not a place name.** Turning "Munich" into a coordinate
 * means reading `Place` rows, and `:file-service` must not learn what a place is — it is the
 * platform's most central service and a dependency on `:place-service` would be a layering
 * inversion for one feature. The join belongs to the caller, which in practice is **Mia**: the
 * one component whose entire purpose is reading across services (`mia-query-layer.md` MQ-D2).
 *
 * ⚠ **A scan, like its sibling** — O(files in the realm) per call, in-process. That is the honest
 * trade for adding no stored rows, no backfill and no invalidation. A caller asks once for the
 * place it is about to render rather than once per photo.
 *
 * ⚠ A file with **no capture coordinate is omitted**, not treated as `(0, 0)`: absent is not the
 * Atlantic, and reading it as one would file every location-less photo off the coast of Africa.
 * The same call `PhotosCapturedBetween` makes for absent timestamps.
 *
 * @param latE7 centre latitude in E7 degrees
 * @param lngE7 centre longitude in E7 degrees
 * @param radiusM inclusive radius in metres; `0` or less returns nothing rather than everything
 * @param limit maximum results; `0` or less returns nothing
 */
suspend fun FileSystem.photosNear(
    latE7: Long,
    lngE7: Long,
    radiusM: Long,
    limit: Int,
): TResult<TList<CapturedPhoto>> =
    findOperation<PhotosNear>(PhotosNear.ID)
        .photosNear(latE7, lngE7, radiusM, limit)
        .asTaskInteractor<TList<CapturedPhoto>>()
        .execute()

interface PhotosNear : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun photosNear(latE7: Long, lngE7: Long, radiusM: Long, limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/file/system/photosNear"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                int(id = Params.LAT_E7)
                int(id = Params.LNG_E7)
                int(id = Params.RADIUS_M)
                int(id = Params.LIMIT)
            },
            returnType = listType(CapturedPhoto.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
        const val RADIUS_M = "$ID/radiusM"
        const val LIMIT = "$ID/limit"
    }
}
