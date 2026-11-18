package org.tenshu.mia

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * One choice offered by a clarification or as a confirmation's alternative
 * (`mia-query-layer.md` §25, CL-D5).
 *
 * ⭐ **A choice is a canned reply.** A tap sends [reply] to `replyToMia` exactly as if it had been
 * typed, so a tap, a typed answer and a spoken one meet the same parser. There is no second path
 * for chips.
 *
 * ⚠ [label] is for showing and [reply] is for parsing, and they differ on purpose: the label is
 * worded for the user ("Evening 19:00") and the reply is canonical ("2026-11-17T19:00"), so the
 * parser never has to understand its own UI copy.
 */
interface MiaChoice : Composite {

    override val schema: CompositeSchema get() = Schema

    /** What the chip shows. */
    val label: TString
        get() = getField(Fields.LABEL)

    /** What a tap sends back, verbatim. */
    val reply: TString
        get() = getField(Fields.REPLY)

    companion object {
        const val ID = "tenshu/mia/choice"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.LABEL)
                string(Fields.REPLY)
            },
        )

        fun create(context: TContext, label: String, reply: String): MiaChoice =
            context.create(type = ID, fields = listOf(label, reply))
    }

    object Fields {
        const val LABEL = "$ID/label"
        const val REPLY = "$ID/reply"
    }
}
