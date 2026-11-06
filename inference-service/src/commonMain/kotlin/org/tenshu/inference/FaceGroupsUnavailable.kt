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
 * Face grouping could not run here — thrown by `faceGroups` instead of returning nothing (IN-6).
 *
 * ⚠⚠ **An empty list is a lie in every one of these cases**, for the reason
 * [SemanticSearchUnavailable] gives at length: a disabled feature and a working feature with no
 * results would become indistinguishable, so nobody would ever be told to switch it on. People is
 * the more dangerous instance of that mistake, because an empty People grid is *plausible* — a user
 * whose library genuinely holds no recognisable faces would see exactly the same screen, and would
 * have no reason to report it.
 *
 * ⚠ [reason] is a stable machine-readable token, not a sentence: the caller decides what to say and
 * in which language. `NO_CONSENT` is the one a UI turns into an action.
 */
interface FaceGroupsUnavailable : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons]. */
    val reason: TString
        get() = getField(Fields.REASON)

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/faceGroupsUnavailable"

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
        ): FaceGroupsUnavailable = context.create(
            type = ID,
            fields = listOf(
                reason.toTString(context),
                message.toTString(context),
            ),
        )
    }

    /** ⚠ Four reasons, because a UI owes four different responses. */
    object Reasons {
        /** The capability is off for this realm or this device (IN-D3). The actionable one. */
        const val NO_CONSENT = "noConsent"

        /**
         * Consent is given, nothing has been grouped, and no installed backend can detect a face —
         * so waiting will not help. Install a model.
         */
        const val NO_BACKEND = "noBackend"

        /** Consent and a backend, but no index pass has stored a template yet: ask again later. */
        const val NOT_INDEXED = "notIndexed"

        /**
         * ⚠⚠⚠ **Templates exist, but this build carries no calibrated threshold, so grouping them
         * would be arithmetic with a number nobody chose.**
         *
         * `FaceClustering.Uncalibrated` returns an empty list by construction, and
         * `photos-people-and-pets.md` §4 names the failure that produces: shipping F3 without
         * recorded numbers *"would put an empty People grid in front of the user —
         * `LabelVocabulary.Empty`'s posture, arriving as a product defect"*. This reason is that
         * sentence made loud. ⚠ **There is nothing a user can do about it**, and a UI should not
         * pretend otherwise by offering a button; it is a gap in the build, and the honest surface
         * is the one that says so to whoever is looking at logs.
         */
        const val UNCALIBRATED = "uncalibrated"
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }
}
