package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The relay does not implement a route this client knows about — it answered with an
 * unmatched-route 404 rather than a result.
 *
 * ## Why this is a typed error and not a generic failure
 *
 * "The relay is older than this client" and "the network is down" demand **opposite**
 * responses. A network failure is transient: retry next tick, hold the cursor, report
 * offline. An unimplemented route is permanent for the life of that relay deployment:
 * retrying it every tick is pure waste, and — worse — reporting it as *offline* would be a
 * lie that hides a working relay.
 *
 * `SyncScheduler` distinguishes exactly this: on [RelayRouteUnsupportedError] from
 * `GetSyncLog` it latches the merged lane off and falls back to the `GetChanges` +
 * `GetTombstones` pair, without ever flipping the device to offline
 * (`sync-lane-cursor-split.md` L3).
 *
 * The generic-error alternative was rejected on the same grounds the error-channel work
 * rejected it everywhere else: the caller would have to string-match a message to recover
 * a decision that changes control flow. Typed, the fallback cannot silently stop working
 * because someone reworded a failure string.
 *
 * ⚠ Only a **bare** 404 means this. A route that exists and answers 404 for its own
 * reasons (an empty mailbox, say) must not be mapped here — see the
 * `HUB_OFFER_NONE_MARKER` precedent in `RestSyncRelay`, where a supporting relay marks its
 * semantic 404 precisely so the two are distinguishable.
 */
interface RelayRouteUnsupportedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    /** The route that was not implemented, for the log line. Never user-facing. */
    val route: TString
        get() = getField(Fields.ROUTE)

    companion object {
        const val ID = "tenshu/result/error/relayRouteUnsupported"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.ROUTE)
            }
        )

        fun create(
            context: TContext,
            route: String
        ): RelayRouteUnsupportedError = context.create(
            type = ID,
            fields = listOf(route.toTString(context))
        )
    }

    object Fields {
        const val ROUTE = "$ID/route"
    }
}
