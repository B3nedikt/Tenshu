package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * A serialized call named an operation this build does not have
 * (`core-schema-evolution.md` §9.3, E3). Two ways to arrive here, and the phase
 * exists to make both say so out loud:
 *
 *  - **cut-off** — the proxy deliberately deregistered the op (§9.3: an
 *    operation's wire contract is immutable, so a changed contract is a new id,
 *    and retiring the old one *is* the deprecation mechanism); or
 *  - **skew** — the caller is newer than the proxy and knows an op it does not.
 *
 * ⚠⚠ **[operationId] is usually empty, and that is not a defect.** Type ids
 * cross the wire as a 16-byte tag derived from the id string, and the reverse
 * mapping is built from the *registered* schemas — so the moment an op is
 * deregistered, the proxy loses the ability to name it and holds only the tag.
 * [typeTag] is therefore the field that is always populated, and the **caller**
 * is the side that can resolve it: it hashed the id to produce that tag, so it
 * can map the tag back to the op it sent. Never present this raw to a user.
 *
 * ⚖️ A well-behaved client should never see this at all: the `AnnounceSession`
 * reply (§6.3) already carried the proxy's vector and floors, so a session that
 * respects them never sends an op below the floor. This error is the backstop
 * for the clients that do not — and, before E3, the case that surfaced as a
 * cryptic `error/internal` from the parser with an empty callstack.
 */
interface UnsupportedOperationError : TError {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    /**
     * The operation id when the proxy can still name it — an id that is
     * registered but whose schema is missing. **Empty for the ordinary cut-off
     * case**; use [typeTag] there.
     */
    val operationId: TString
        get() = getField(Fields.OPERATION_ID)

    /** Lowercase hex of the 16-byte wire tag. Always populated. */
    val typeTag: TString
        get() = getField(Fields.TYPE_TAG)

    companion object {
        const val ID = "tenshu/result/error/unsupportedOperation"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.MESSAGE)
                string(Fields.OPERATION_ID)
                string(Fields.TYPE_TAG)
            }
        )

        fun create(
            context: TContext,
            message: String,
            operationId: String,
            typeTag: String,
        ): UnsupportedOperationError = context.create(
            type = ID,
            fields = listOf(
                message.toTString(context),
                operationId.toTString(context),
                typeTag.toTString(context),
            )
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
        const val OPERATION_ID = "$ID/operationId"
        const val TYPE_TAG = "$ID/typeTag"
    }
}
