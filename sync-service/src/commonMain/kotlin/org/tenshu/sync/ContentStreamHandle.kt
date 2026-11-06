package org.tenshu.sync

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
 * Return value of [OpenContentStream] (D2b streaming, `drive-streaming-preview.md` §3.5):
 *
 * - [handle] — the opaque token addressing the proxy-side streaming session; passed back to
 *   [ReadContentRange] / [CloseContentStream].
 * - [totalSize] — the file's total plaintext size, so a client fd/`AVAssetResourceLoaderDelegate`
 *   knows the content length up front without a separate probe.
 */
interface ContentStreamHandle : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val handle: TString
        get() = getField(Fields.HANDLE)

    val totalSize: TInteger
        get() = getField(Fields.TOTAL_SIZE)

    companion object {
        const val ID = "tenshu/sync/contentStreamHandle"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.HANDLE)
                int(Fields.TOTAL_SIZE)
            }
        )

        fun create(
            context: TContext,
            handle: String,
            totalSize: Long
        ): ContentStreamHandle = context.create(
            type = ID,
            fields = listOf(
                handle.toTString(context),
                totalSize.toTInteger(context)
            )
        )
    }

    object Fields {
        const val HANDLE = "$ID/handle"
        const val TOTAL_SIZE = "$ID/totalSize"
    }
}
