package org.tenshu.core.file.system

import org.tenshu.core.ExpectedOutcome
import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The bytes at this path are the **only copy** — an upload that has not drained to the relay yet —
 * so they cannot be evicted yet. The upload journal is its source.
 *
 * ⚠⚠ Why an answer rather than a silent no-op. `EvictFileBytesImplementation` is the *"Remove
 * download"* gesture; a no-op would leave the user believing the space was freed, refusing says why.
 *
 * ⚠ [ExpectedOutcome], like `AlreadyExistsError`: the ordinary answer while an upload is parked,
 * not a fault, so it stays a typed failure the caller receives without being recorded as a
 * diagnostic.
 */
interface ContentPinnedError : ExpectedOutcome {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/file/system/error/contentPinned"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.MESSAGE)
            }
        )

        fun create(
            context: TContext,
            message: String
        ): ContentPinnedError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
