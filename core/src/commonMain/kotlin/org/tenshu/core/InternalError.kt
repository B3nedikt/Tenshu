package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Last-resort wrapper for an exception that escaped an operation impl body without
 * being a typed [TenshuException]. Phase 3 of `docs/proposals/core-error-channel.md`
 * — replaces the freetext `GenericError(message=e.message)` that the catch-alls used
 * to emit.
 *
 * `internalDetail` is **developer-only**: the proxy keeps it locally (in the Diagnostics
 * screen / `:logging-service` ring buffer) so a developer running Control desktop with
 * DevTools open can see the original `e.message`. It is **redacted at the upload seam**
 * before any payload reaches the App Console (proposal §5 / threat-model T-L-03) —
 * typed-error subtypes never carry freetext, so structural redaction works by
 * construction; this is the one exception.
 *
 * The field is optional because some exception classes don't carry a meaningful
 * message (e.g. a bare `NullPointerException`).
 */
interface InternalError : TError {
    override val schema: CompositeSchema
        get() = Schema

    val internalDetail: TString?
        get() = getOptionalField(Fields.INTERNAL_DETAIL)

    companion object {
        const val ID = "tenshu/core/error/internal"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.INTERNAL_DETAIL, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            internalDetail: String?
        ): InternalError = context.create(
            type = ID,
            fields = listOf(internalDetail?.toTString(context))
        )
    }

    object Fields {
        const val INTERNAL_DETAIL = "$ID/internal-detail"
    }
}
