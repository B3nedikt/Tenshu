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
 * A model present on **this device** (IN-2c / IN-D10), as a screen sees it.
 *
 * ⚠⚠ A record exists only when every artifact of the checkpoint landed and passed its digest
 * against the signed catalog (`inference-service.md` §22.2). "Installed" therefore means complete
 * and verified, never "some of it arrived" — which is why a screen may treat its presence as
 * permission to say the model works.
 *
 * ⚠ **No local paths.** The region twin carries one because an app has to open the bundle; nothing
 * outside the proxy opens a model, so exposing where the weights live would widen the IPC surface
 * for no consumer.
 *
 * ⚠ Device-local and never synced (IN-D4): a model is a public artifact any device can re-fetch, so
 * this says nothing about the user's other devices.
 */
interface InstalledModel : Composite {

    override val schema: CompositeSchema get() = Schema

    val modelId: TString
        get() = getField(Fields.MODEL_ID)

    val modelVersion: TInteger
        get() = getField(Fields.MODEL_VERSION)

    /** What it occupies on disk — the sum across the checkpoint's artifacts. */
    val byteSize: TInteger
        get() = getField(Fields.BYTE_SIZE)

    /**
     * The vocabulary revision this copy was built against (IN-D9).
     *
     * ⚠ Carried so a screen can tell "a newer *model*" from "the same model, newer *labels*" — the
     * second re-derives rows and does not re-download weights.
     */
    val labelSetVersion: TInteger
        get() = getField(Fields.LABEL_SET_VERSION)

    /** ⚖️ Travels with the installed copy, so the licence question is answerable from the device. */
    val licence: TString
        get() = getField(Fields.LICENCE)

    companion object {
        const val ID = "tenshu/inference/installedModel"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.MODEL_ID)
                int(Fields.MODEL_VERSION)
                int(Fields.BYTE_SIZE)
                int(Fields.LABEL_SET_VERSION)
                string(Fields.LICENCE)
            },
        )

        fun create(
            context: TContext,
            modelId: String,
            modelVersion: Int,
            byteSize: Long,
            labelSetVersion: Int,
            licence: String,
        ): InstalledModel = context.create(
            type = ID,
            fields = listOf(
                modelId.toTString(context),
                modelVersion.toLong().toTInteger(context),
                byteSize.toTInteger(context),
                labelSetVersion.toLong().toTInteger(context),
                licence.toTString(context),
            ),
        )
    }

    object Fields {
        const val MODEL_ID = "$ID/modelId"
        const val MODEL_VERSION = "$ID/modelVersion"
        const val BYTE_SIZE = "$ID/byteSize"
        const val LABEL_SET_VERSION = "$ID/labelSetVersion"
        const val LICENCE = "$ID/licence"
    }
}
