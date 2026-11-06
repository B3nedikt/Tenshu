package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Impl-raised semantic error: a value-level invariant the schema can't encode failed.
 * Phase 3 of `docs/proposals/core-error-channel.md` §3.0 (semantic class).
 *
 * The [name] is the offending parameter id (non-PII — developer-defined identifier).
 * The [reason] is a typed code drawn from [Reasons] — the proposal's "typed enum" form
 * (proposal §2.2). Reason values are wire-stable kebab-case constants; pattern-match
 * `reason.get()` against [Reasons.NEGATIVE], etc. on the rendering side.
 *
 * Unlike `MissingParameter` / `UnknownParameter` / `WrongParameter` (Phase 2 — STRUCTURAL,
 * raised by the dispatcher) this one is raised by **impl bodies** for domain constraints
 * the schema can't capture (`pageSize > 0`, `path` has no `..`, `email` looks well-formed,
 * etc.). Per-op `Errors` companions list which reasons each op may raise.
 */
interface InvalidParameter : TError {
    override val schema: CompositeSchema
        get() = Schema

    val name: TString
        get() = getField(Fields.NAME)

    val reason: TString
        get() = getField(Fields.REASON)

    companion object {
        const val ID = "tenshu/core/error/operation/invalid-parameter"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.NAME)
                string(Fields.REASON)
            }
        )

        fun create(
            context: TContext,
            name: String,
            reason: String
        ): InvalidParameter = context.create(
            type = ID,
            fields = listOf(name.toTString(context), reason.toTString(context))
        )
    }

    object Fields {
        const val NAME = "$ID/name"
        const val REASON = "$ID/reason"
    }

    /**
     * Closed set of [reason] values. Add a new constant — and document the semantic — when
     * a new domain-invariant class shows up. Render-time mappers `when`-match on these
     * (`when (e.reason.get()) { Reasons.NEGATIVE -> "must be positive"; ... }`).
     */
    object Reasons {
        /** Number was below zero where the op requires positive (e.g. `pageSize`, `count`). */
        const val NEGATIVE = "negative"

        /** Collection / string was empty where the op requires non-empty. */
        const val EMPTY = "empty"

        /** Value fell outside the op's declared range (above max or below min ≠ negative). */
        const val OUT_OF_RANGE = "out-of-range"

        /** Path contained `..` / absolute prefix / other escape — refused by file-service path validators. */
        const val PATH_TRAVERSAL = "path-traversal"

        /** Value didn't match the expected format (regex / scheme / declared encoding). */
        const val BAD_FORMAT = "bad-format"

        /**
         * The referent was valid but has already been used, and single use is the contract.
         * A contact invite whose one response slot is taken is the canonical case — and a
         * **loud** one: a redemption the user did not perform means somebody else answered
         * their link (threat model T-CI-01), so a renderer must not fold this into a generic
         * failure.
         */
        const val ALREADY_CONSUMED = "already-consumed"

        /** The referent no longer exists — expired, withdrawn, or never issued. */
        const val GONE = "gone"

        /**
         * The value asserts something the referent has not reached the state for — yet. The
         * canonical case: `sasConfirmed = true` on a contact invite no verification code has been
         * shown for (threat model T-CI-10). Unlike [GONE] it may become valid later, so a renderer
         * should say "not yet", not "no longer".
         */
        const val NOT_READY = "not-ready"
    }
}
