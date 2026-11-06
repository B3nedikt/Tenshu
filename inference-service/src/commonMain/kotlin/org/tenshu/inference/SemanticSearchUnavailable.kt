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
 * Semantic search could not run here — thrown by `photosLike` instead of returning nothing.
 *
 * ⚠⚠ **An empty list is a lie in every one of these cases.** "No photos match your search" and
 * "this device cannot search your photos" are different sentences, and only the second is true when
 * the capability is off, no model is installed, or nothing has been indexed yet. Returning an empty
 * success would send the user to look for a photo they do have, and — worse — would make a
 * *disabled feature* indistinguishable from a *working feature with no results*, so nobody would
 * ever be told to switch it on. Control-on-iOS learned this the expensive way: not-connected is a
 * failure, never an empty success.
 *
 * ⚠ [reason] is a stable machine-readable token, not a sentence: the caller decides what to say and
 * in which language. `NO_CONSENT` is the one a UI turns into an action.
 */
interface SemanticSearchUnavailable : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons]. */
    val reason: TString
        get() = getField(Fields.REASON)

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/semanticSearchUnavailable"

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
        ): SemanticSearchUnavailable = context.create(
            type = ID,
            fields = listOf(
                reason.toTString(context),
                message.toTString(context),
            ),
        )
    }

    /**
     * ⚠ Four reasons, because they need four different responses from a UI: switch it on, download
     * a model, wait for indexing, or — the last one — nothing the user can do.
     */
    object Reasons {
        /** The capability is off for this realm or this device (IN-D3). The actionable one. */
        const val NO_CONSENT = "noConsent"

        /** Consent is given but nothing on this device can embed — no model installed yet. */
        const val NO_BACKEND = "noBackend"

        /** Consent and a model, but the library has not been indexed yet: ask again after a pass. */
        const val NOT_INDEXED = "notIndexed"

        /**
         * The backend refused to embed the query text.
         *
         * ⚠ Distinct from [NO_BACKEND]: a backend that tags images but has no joint image–text
         * space is *present and working*, and reporting it as missing would send someone to
         * re-download a model that is already there.
         */
        const val NO_TEXT_EMBEDDING = "noTextEmbedding"
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }
}
