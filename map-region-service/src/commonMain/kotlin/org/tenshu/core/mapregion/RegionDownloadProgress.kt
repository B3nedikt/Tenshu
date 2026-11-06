package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * A single progress tick for an in-flight [downloadRegion] — [writtenBytes] of [totalBytes] have
 * landed in the proxy's staging file (`maps-region-delivery.md` §7.1, the download-UX slice). The
 * value the [DownloadProgress] stream pushes to the app, once per streamed chunk.
 *
 * **Device-local, never synced (D5).** Region install state — and therefore its progress — is a
 * coarse-location signal deliberately kept off the synced realm; a download bar must not appear on
 * the user's other devices. So this rides a bespoke proxy→app push stream fed by an in-memory
 * progress holder, NOT a `select{}.flow()` over the object store.
 *
 * **This is not an estimate.** Since the download became streamed (track 3e) it appends to a staging
 * file, so [writtenBytes] is exactly the bytes on disk. [totalBytes] is the signed catalog's advertised
 * size, so `writtenBytes / totalBytes` is an honest determinate fraction. The stream *ends* when the
 * download finishes — success or failure is carried by [downloadRegion]'s own return, not a phase field.
 */
interface RegionDownloadProgress : Composite {

    override val schema: CompositeSchema get() = Schema

    val regionId: TString
        get() = getField(Fields.REGION_ID)

    /** Bytes written to the staging file so far (Long-backed). */
    val writtenBytes: TInteger
        get() = getField(Fields.WRITTEN_BYTES)

    /** The bundle's total size from the verified catalog (Long-backed); the progress denominator. */
    val totalBytes: TInteger
        get() = getField(Fields.TOTAL_BYTES)

    companion object {
        const val ID = "tenshu/mapRegion/regionDownloadProgress"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.REGION_ID)
                int(Fields.WRITTEN_BYTES)
                int(Fields.TOTAL_BYTES)
            }
        )

        fun create(
            context: TContext,
            regionId: String,
            writtenBytes: Long,
            totalBytes: Long,
        ): RegionDownloadProgress = context.create(
            type = ID,
            fields = listOf(
                regionId.toTString(context),
                writtenBytes.toTInteger(context),
                totalBytes.toTInteger(context),
            )
        )
    }

    object Fields {
        const val REGION_ID = "$ID/regionId"
        const val WRITTEN_BYTES = "$ID/writtenBytes"
        const val TOTAL_BYTES = "$ID/totalBytes"
    }
}
