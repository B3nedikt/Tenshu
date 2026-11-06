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
 * A model could not be listed, downloaded or removed — thrown rather than answered with nothing.
 *
 * ⚠⚠ **An empty catalog is a lie in most of these cases**, and it is the lie a screen renders as
 * "no models are available", which reads as *we publish none* rather than *this device cannot see
 * them*. The same rule `SemanticSearchUnavailable` exists for, one layer out: not-configured is a
 * failure, never an empty success.
 *
 * ⚠ [reason] is a stable machine-readable token, not a sentence — the caller decides what to say
 * and in which language.
 */
interface ModelUnavailable : TError {

    override val schema: CompositeSchema get() = Schema

    /** One of [Reasons]. */
    val reason: TString
        get() = getField(Fields.REASON)

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/modelUnavailable"

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
        ): ModelUnavailable = context.create(
            type = ID,
            fields = listOf(
                reason.toTString(context),
                message.toTString(context),
            ),
        )
    }

    object Fields {
        const val REASON = "$ID/reason"
        const val MESSAGE = "$ID/message"
    }

    /**
     * ⚠ Seven reasons, because a screen owes a different sentence — and a different button — to each.
     * ⚠ Plain strings in a `string` field, not a `TConstant` family: a new one changes no shape.
     */
    object Reasons {
        /**
         * No catalog this client can trust: none published, or no signing key pinned in this build.
         *
         * ⚠⚠ **Today's state on every device**, and the one a screen must not render as an empty
         * list. There is nothing the *user* can do about it, which is exactly why saying so beats
         * showing them an empty page they will retry.
         */
        const val NO_CATALOG = "noCatalog"

        /** The catalog was fetched and verified, and names no such model. */
        const val UNKNOWN_MODEL = "unknownModel"

        /** The CDN refused or the connection failed — the retryable one. */
        const val TRANSPORT_FAILED = "transportFailed"

        /**
         * The bytes arrived and did not match the signed catalog's digest.
         *
         * ⚠ Not retryable in the way [TRANSPORT_FAILED] is: retrying fetches the same wrong bytes
         * from the same CDN. It is a supply-chain signal, and a screen should say so rather than
         * offering a button that will fail identically.
         */
        const val INTEGRITY_FAILED = "integrityFailed"

        /** Asked to remove a model this device does not hold. */
        const val NOT_INSTALLED = "notInstalled"

        /**
         * This proxy process does not own the model store: another one does (two desktop Controls
         * over one data directory), so it may read what is installed and change nothing.
         *
         * ⚠⚠ Not retryable, and not transient: it stays true for the life of this process. The only
         * thing that changes it is quitting the other process and restarting this one, so a screen
         * must say that and must not offer a retry.
         */
        const val STORE_NOT_OWNED = "storeNotOwned"

        /**
         * This proxy process could not lock the model store at all — permissions, an I/O failure,
         * a filesystem with no locks — so it cannot tell whether it is alone and changes nothing.
         *
         * ⚠⚠ Not [STORE_NOT_OWNED]: there may be no other process to quit. Equally not retryable.
         * The error's message names the lock path and the cause.
         */
        const val STORE_LOCK_UNAVAILABLE = "storeLockUnavailable"
    }
}
