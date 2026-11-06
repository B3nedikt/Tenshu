package org.tenshu.inference

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
 * One tick of an in-flight [DownloadModel] (IN-2c, the 1c-3 slice).
 *
 * ⚠⚠ **Bytes over the whole checkpoint, not per artifact.** The pinned SigLIP is three files, and a
 * user installing it is waiting for one ~383 MB job — three bars that each run 0→100 describe work
 * nobody asked about. [downloadedBytes] therefore accumulates across artifacts and [totalBytes] is
 * the entry's sum.
 *
 * ⚠ Bytes rather than a fraction, matching the region twin: the total is already known from the
 * catalog, and bytes are what a caller totalling several downloads needs.
 *
 * ⚠ Device-local and never synced (IN-D4/D5): a download bar pushed to the user's other devices
 * would describe work none of them is doing.
 */
interface ModelDownloadProgress : Composite {

    override val schema: CompositeSchema get() = Schema

    val modelId: TString
        get() = getField(Fields.MODEL_ID)

    val downloadedBytes: TInteger
        get() = getField(Fields.DOWNLOADED_BYTES)

    /** The signed catalog's figure for the whole checkpoint. */
    val totalBytes: TInteger
        get() = getField(Fields.TOTAL_BYTES)

    companion object {
        const val ID = "tenshu/inference/modelDownloadProgress"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.MODEL_ID)
                int(Fields.DOWNLOADED_BYTES)
                int(Fields.TOTAL_BYTES)
            },
        )

        fun create(
            context: TContext,
            modelId: String,
            downloadedBytes: Long,
            totalBytes: Long,
        ): ModelDownloadProgress = context.create(
            type = ID,
            fields = listOf(
                modelId.toTString(context),
                downloadedBytes.toTInteger(context),
                totalBytes.toTInteger(context),
            ),
        )
    }

    object Fields {
        const val MODEL_ID = "$ID/modelId"
        const val DOWNLOADED_BYTES = "$ID/downloadedBytes"
        const val TOTAL_BYTES = "$ID/totalBytes"
    }
}
