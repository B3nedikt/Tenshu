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
 * ⚠ [ExpectedOutcome]: a collision is this operation's ordinary answer, not a fault. Both callers
 * are designed around it — the user-initiated upload shows a conflict dialog, and the Synced-Folders
 * background drain resolves it silently to `Skipped` as its documented dedup backstop. It stays a
 * typed failure the caller receives exactly as before; it simply stops being recorded as a
 * *diagnostic*, where 732 of them were burying the log.
 */
interface AlreadyExistsError : ExpectedOutcome {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/file/system/error/alreadyExists"

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
        ): AlreadyExistsError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
