package org.tenshu.mia

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Something Mia will do **only if the user taps** (`mia-query-layer.md` MQ-D7).
 *
 * Answering a question is free and reversible; turning off a light is not. So a mutating
 * plan never executes as part of the turn that produced it — it comes back as this, and a
 * second call ([ConfirmMiaAction]) runs it.
 *
 * ⚠⚠ **The confirmation describes the RESOLVED action, not the utterance.** By the time this
 * exists the proxy has already found exactly one device and settled the verb, so [title] is
 * what will actually happen rather than what Mia thinks it heard. That distinction is the
 * whole control for a write (MQ-D4a) and it is cheap to get right here first, on an action
 * whose intent and effect are the same thing.
 *
 * ⚠⚠ **[token] is a capability, and a narrow one by construction.** It names one already-
 * resolved action held on the proxy — so `ConfirmMiaAction` cannot be used to command an
 * arbitrary device, only to run something Mia resolved *and showed the user*. Single-use, and
 * it expires: a confirmation left on screen must not fire an hour later.
 */
interface MiaAction : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Opaque handle for the pending action held on the proxy. */
    val token: TString
        get() = getField(Fields.TOKEN)

    /** What will happen, in the user's words — *"Turn off Kitchen light"*. */
    val title: TString
        get() = getField(Fields.TITLE)

    /** Where it will happen — the room, or whatever disambiguates it. May be empty. */
    val detail: TString
        get() = getField(Fields.DETAIL)

    companion object {
        const val ID = "tenshu/mia/action"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.TOKEN)
                string(Fields.TITLE)
                string(Fields.DETAIL)
            },
        )

        fun create(
            context: TContext,
            token: String,
            title: String,
            detail: String = "",
        ): MiaAction = context.create(
            type = ID,
            fields = listOf(token, title, detail),
        )
    }

    object Fields {
        const val TOKEN = "$ID/token"
        const val TITLE = "$ID/title"
        const val DETAIL = "$ID/detail"
    }
}
