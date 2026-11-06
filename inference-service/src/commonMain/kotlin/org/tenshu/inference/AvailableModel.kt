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
 * A model this device could install — one row of the **verified** catalog, as a screen sees it
 * (IN-2c / IN-D10).
 *
 * ⚠⚠ Only entries that survived the client-side filters appear here: an artifact format this build
 * cannot read, a capability it has never heard of, or a **runtime it cannot execute** are all
 * dropped proxy-side (`usableModels`). So a row on this list is one this device could actually run
 * — offering a `coreml` artifact on Android would spend the user's bytes on something that can
 * never work.
 *
 * ⚠ **Not an install state.** Whether this device *has* the model is [InstalledModel]'s question and
 * whether the user has *enabled* the capability is `permittedInference`'s — IN-D10 requires a screen
 * to say which of the three an action changes, and keeping them apart is what makes that hard to get
 * wrong.
 */
interface AvailableModel : Composite {

    override val schema: CompositeSchema get() = Schema

    val modelId: TString
        get() = getField(Fields.MODEL_ID)

    /** ⚠ Falls back to [modelId] at the catalog read; never blank here. */
    val name: TString
        get() = getField(Fields.NAME)

    val modelVersion: TInteger
        get() = getField(Fields.MODEL_VERSION)

    /**
     * The [InferenceCapability] ids this model serves, [CAPABILITY_SEPARATOR]-delimited. Decode with
     * [capabilityIds].
     *
     * ⚠ Ids rather than an enum, because the wire outlives this build's vocabulary: a catalog naming
     * a capability a client has never heard of must not make the whole row unreadable.
     *
     * ⚠⚠ **A delimited string, not a `TList<TString>` field, and deliberately so.** A list field's
     * members are persisted as their own rows, so each needs a `DataIdProvider` — a constraint a
     * bare capability id has no business carrying. `Event.exceptionDates` and
     * `SchemaFenceStatus.fencedServiceIds` are the same workaround; capability ids are
     * `inference.<kind>.<name>` and can never contain a comma, so the encoding is lossless without
     * escaping.
     */
    val capabilities: TString
        get() = getField(Fields.CAPABILITIES)

    /**
     * What installing this costs, in bytes — the **sum of every artifact**.
     *
     * ⚠⚠ The whole checkpoint, not its largest file. A user deciding whether to spend 383 MB must
     * be told 383 MB; showing the vision tower's 100 MB and then downloading four times that is the
     * kind of number that ends up in a complaint rather than a decision.
     */
    val byteSize: TInteger
        get() = getField(Fields.BYTE_SIZE)

    /** Verbatim from the catalog — ⚖️ a model whose licence we cannot state is one we must not ship. */
    val licence: TString
        get() = getField(Fields.LICENCE)

    companion object {
        const val ID = "tenshu/inference/availableModel"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.MODEL_ID)
                string(Fields.NAME)
                int(Fields.MODEL_VERSION)
                string(Fields.CAPABILITIES)
                int(Fields.BYTE_SIZE)
                string(Fields.LICENCE)
            },
        )

        fun create(
            context: TContext,
            modelId: String,
            name: String,
            modelVersion: Int,
            capabilities: List<String>,
            byteSize: Long,
            licence: String,
        ): AvailableModel = context.create(
            type = ID,
            fields = listOf(
                modelId.toTString(context),
                name.toTString(context),
                modelVersion.toLong().toTInteger(context),
                capabilities.joinToString(CAPABILITY_SEPARATOR).toTString(context),
                byteSize.toTInteger(context),
                licence.toTString(context),
            ),
        )

        /** ⚠ Never occurs inside an `InferenceCapability.id`, so no escaping is needed. */
        const val CAPABILITY_SEPARATOR = ","
    }

    object Fields {
        const val MODEL_ID = "$ID/modelId"
        const val NAME = "$ID/name"
        const val MODEL_VERSION = "$ID/modelVersion"
        const val CAPABILITIES = "$ID/capabilities"
        const val BYTE_SIZE = "$ID/byteSize"
        const val LICENCE = "$ID/licence"
    }
}

/**
 * The capability ids as a list — the read side of [AvailableModel.capabilities].
 *
 * ⚠ Ids this build does not recognise are **kept**, not dropped: a screen listing what a model
 * serves should not silently shorten the list because one entry is newer than the app.
 */
fun AvailableModel.capabilityIds(): List<String> =
    capabilities.get()
        .split(AvailableModel.CAPABILITY_SEPARATOR)
        .filter { it.isNotBlank() }
