package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * A serialized-transport call was refused because the session has no admitted
 * schema announce (`core-schema-evolution.md` §6.5, reject-if-silent). The
 * client connection layer must run `AnnounceSession` before any other call —
 * app code should never see this raw: the connection maps it onto the typed
 * error surface, and a *refused* announce already carried the full
 * `SessionAdmission` (proxy vector + floors + named mismatches), which is the
 * actionable payload.
 *
 * ⚠⚠ **Produce it only BEFORE an operation runs.** `RemoteBinaryTaskExecutor` treats this error on
 * an ordinary call as "the session changed under me": it re-announces and resends that call once.
 * That is safe only because nothing ran. Never relay or return it after an op has executed, least of
 * all after a write, or the resend runs the write twice.
 */
interface UnannouncedSessionError : TError {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/unannouncedSession"

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
        ): UnannouncedSessionError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
