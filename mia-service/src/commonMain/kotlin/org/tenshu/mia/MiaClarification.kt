package org.tenshu.mia

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string

/**
 * Mia asking back for one value it is missing (`mia-query-layer.md` §25).
 *
 * ⚠⚠ **[token] is a capability over one param of one plan the proxy holds**, the same rule as
 * [MiaAction.token]. `replyToMia` with it can fill exactly that param and nothing else: never the
 * title, the target or the verb, unless that is the param asked for. Single-use, and it expires.
 *
 * ⚠ [param] and [reason] are for rendering and for tests. A client never sends them back; the
 * token already names them on the proxy.
 */
interface MiaClarification : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Opaque handle for the pending clarification held on the proxy. */
    val token: TString
        get() = getField(Fields.TOKEN)

    /** The write op param asked for, e.g. `tenshu/calendar/service/saveEvent/startLocal`. */
    val param: TString
        get() = getField(Fields.PARAM)

    /** [REASON_MISSING], [REASON_AMBIGUOUS] or [REASON_INVALID]. */
    val reason: TString
        get() = getField(Fields.REASON)

    /** The sentence asked: *"When tomorrow should it start?"* */
    val question: TString
        get() = getField(Fields.QUESTION)

    /** Canned replies; empty when only a free answer makes sense (a title). */
    val choices: TList<MiaChoice>
        get() = getField(Fields.CHOICES)

    companion object {
        const val ID = "tenshu/mia/clarification"

        const val REASON_MISSING = "missing"
        const val REASON_AMBIGUOUS = "ambiguous"
        const val REASON_INVALID = "invalid"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.TOKEN)
                string(Fields.PARAM)
                string(Fields.REASON)
                string(Fields.QUESTION)
                list(Fields.CHOICES, of = MiaChoice.Schema)
            },
        )

        @Suppress("UNCHECKED_CAST")
        fun create(
            context: TContext,
            token: String,
            param: String,
            reason: String,
            question: String,
            choices: List<MiaChoice>,
        ): MiaClarification = context.create(
            type = ID,
            fields = listOf(token, param, reason, question, choices as List<Data>),
        )
    }

    object Fields {
        const val TOKEN = "$ID/token"
        const val PARAM = "$ID/param"
        const val REASON = "$ID/reason"
        const val QUESTION = "$ID/question"
        const val CHOICES = "$ID/choices"
    }
}
