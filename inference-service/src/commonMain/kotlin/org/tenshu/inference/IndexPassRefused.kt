package org.tenshu.inference

import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * A user-initiated index pass could not start — thrown by [StartMediaIndexPass].
 *
 * ⚠⚠ **A button that reports success and indexes nothing is worse than a refusal.** Every reason
 * below is a different sentence and two of them are different *actions*: install a model, or switch
 * the capability on. Reporting "started" and leaving the bar at zero would send the user to look for
 * a problem in their photos.
 *
 * ⚠ An already-running pass is **not** a refusal: the call joins it and succeeds, because the user
 * asked for indexing to be happening and it is.
 */
interface IndexPassRefused : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons]. */
    val reason: TString
        get() = getField(Fields.REASON)

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/indexPassRefused"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.REASON)
                string(Fields.MESSAGE)
            },
        )

        fun create(
            context: TContext,
            reason: String,
            message: String,
        ): IndexPassRefused = context.create(
            type = ID,
            fields = listOf(
                reason.toTString(context),
                message.toTString(context),
            ),
        )
    }

    object Reasons {
        /** Nothing on this device can index — no model installed (IN-2c's screen is the route). */
        const val NO_BACKEND = "noBackend"

        /** Neither capability is switched on for this realm and this device (IN-D3). */
        const val NO_CONSENT = "noConsent"

        /** The user paused indexing here; Resume is the action, not a second Index now. */
        const val PAUSED = "paused"

        /**
         * The device is in no state to run a neural network over every photograph — a low battery,
         * or a platform that cannot tell (`IndexingConditions`). ⚠ Being *in use* is deliberately
         * not one of these: the user pressed the button, so they are using the device by definition.
         */
        const val NOT_READY = "notReady"
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }
}
