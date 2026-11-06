package org.tenshu.core.app

import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * [OpenTarget] could not happen — thrown on the ordinary typed-error channel
 * (`core-error-channel.md`), in the shape `SemanticSearchUnavailable` established for MQ-4.
 *
 * ⚠⚠ **Every reason describes the HANDLER, never the row.** None of them reveals whether the
 * `DataId` names something that exists, which is what keeps `control-cross-app-open.md` CO-D6's
 * existence oracle shut while still giving a caller different sentences to say. A caller not
 * entitled to the type gets [Reasons.NOT_PERMITTED] whether or not the row is real.
 *
 * ⚠ Throwing rather than returning an empty success is the same rule MQ-5a §16.4 drew, applied in
 * the other direction: *throw when the user loses a capability, return empty when only the
 * implementation changes*. A tap that cannot be honoured is the user losing something.
 */
interface OpenTargetUnavailable : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons] — a stable token, never a sentence; the caller localises. */
    val reason: TString
        get() = getField(Fields.REASON)

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/openTargetUnavailable"

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
        ): OpenTargetUnavailable = context.create(
            type = ID,
            fields = listOf(
                reason.toTString(context),
                message.toTString(context),
            ),
        )
    }

    object Reasons {
        /** Nothing on this platform opens this type at all — a product gap, and it should show. */
        const val NO_HANDLER = "noHandler"

        /**
         * A handler exists but is not installed on **this** device.
         *
         * ⚠ Answered from `AppRegistration`, which is keyed `(appId, deviceId)` — a store read
         * rather than an OS probe, so it cannot disagree with Control's own Apps screen (CO-D2).
         */
        const val NOT_INSTALLED_HERE = "notInstalledHere"

        /** It was launched and never became reachable inside the caller's bound (CO-D8). */
        const val LAUNCH_FAILED = "launchFailed"

        /** The caller may not open this type (CO-D6). ⚠ Says nothing about the row. */
        const val NOT_PERMITTED = "notPermitted"

        /**
         * ⚠⚠ **Added in TT-1a, and not a transitional hack.** This build resolved a handler and has
         * no way to *reach* it — no delivery is bound for this platform. TT-1a is deliberately
         * inert (it ships the wire and the resolution, not the launcher), and desktop and iOS will
         * legitimately sit here until TT-3.
         *
         * ⚠ It is distinct from [LAUNCH_FAILED], which means something *was* attempted and did not
         * come up. Reporting "launch failed" when nothing can launch would send a reader to debug
         * the wrong half — the Control-on-iOS rule that not-connected is a failure and never an
         * empty success, one layer over.
         */
        const val NO_DELIVERY = "noDelivery"
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }
}
