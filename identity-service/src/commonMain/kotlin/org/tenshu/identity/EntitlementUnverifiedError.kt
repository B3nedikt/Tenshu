package org.tenshu.identity

import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * An `Entitlement` record's publisher signature did not verify against any key this client trusts
 * for its `publisherId` ([EntitlementSignature.publicKeysFor]).
 *
 * ## Why this is typed rather than a log line
 *
 * It used to be a bare `println`, which the packaged desktop app discards. On 2026-08-07 that cost
 * an evening: promoting the commerce issuer to a real `ISSUER_SEED` made every client reject every
 * record it minted, and the only user-visible consequence was a Services card stuck on "Setting up
 * your storage" — indistinguishable from "no entitlement yet", with a perfectly valid record in the
 * store. Nothing surfaced the actual reason.
 *
 * A typed error is durable (it lands in the `ErrorEvent` stream, visible under Settings → Errors)
 * and machine-readable, so the UI can say *"we could not verify your entitlement"* instead of
 * implying setup is in progress. See
 * [`entitlement-trust-anchor-distribution.md`](../../../../../../../docs/proposals/entitlement-trust-anchor-distribution.md)
 * D1.
 *
 * ⚠ This is **diagnostic, not enforcement**. Per that document's D1–D3 the record is retained,
 * excluded from figures, and still forwarded to the relay as a credential — the relay re-verifies
 * against its own configured key and is the authority on whether it is acceptable. A client that
 * raises this error is reporting "I could not confirm this myself", not "this is invalid".
 *
 * ⚠ [publisherId] is included because the two failure shapes need distinguishing: an *unknown*
 * publisher (nothing registered for that id) usually means a record from a newer issuer, while a
 * *mismatched signature* for a known publisher usually means a key rotation this client predates.
 * Both point at "this client is behind", but at different things.
 */
interface EntitlementUnverifiedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    /** The `publisherId` stamped on the record. Never user-facing; for the error log. */
    val publisherId: TString
        get() = getField(Fields.PUBLISHER_ID)

    companion object {
        const val ID = "tenshu/result/error/entitlementUnverified"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.PUBLISHER_ID)
            }
        )

        fun create(
            context: TContext,
            publisherId: String,
        ): EntitlementUnverifiedError = context.create(
            type = ID,
            fields = listOf(publisherId.toTString(context))
        )
    }

    object Fields {
        const val PUBLISHER_ID = "$ID/publisherId"
    }
}
