package org.tenshu.core.notifications

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **synced "seen" marker** for one inbound actionable request
 * (`docs/proposals/control-central-notifications.md` Phase 4 — synced dismissal).
 *
 * When the user dismisses a pending request (a membership grant or a redeemed
 * contact invite) in Control's central-notifications surface, Control records one
 * of these keyed by the request's **content-derived, device-stable** id
 * (`grant:<grantorFingerprint>` / `invite:<inviteId>` — see
 * `app.tenshu.control.notifications.PendingRequest.id`). Its presence means
 * "this request has been seen on this realm" — it clears the unread badge and the
 * pinned Home card on **every** paired device, while the request itself stays
 * listed + actionable in the notification center until it is actually resolved.
 *
 * `Syncable(Eager)` so a dismissal on one device converges to all the others
 * (the whole point of Phase 4). Keyed by [requestId] via [DismissedRequestDataIdProvider]
 * so re-dismissing the same request — including concurrently on two devices — folds
 * to a single entity (union merge), rather than the last-writer-wins clobber a
 * single synced "dismissed set" blob would suffer. The marker is **monotonic**:
 * once seen, it never un-sets, so a `LastWriterWins` conflict resolution has
 * nothing to lose.
 */
interface DismissedRequest : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The dismissed request's stable id (`PendingRequest.id`). */
    val requestId: TString
        get() = getField(Fields.REQUEST_ID)

    companion object {
        const val ID = "tenshu/notifications/dismissedRequest"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(DismissedRequestDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.REQUEST_ID)
            }
        )

        fun create(context: TContext, requestId: String): DismissedRequest = context.create(
            type = ID,
            fields = listOf(requestId)
        )
    }

    object Fields {
        const val REQUEST_ID = "$ID/requestId"
    }
}

val Entity<out DismissedRequest>.requestId: TString
    get() = getFieldValue(DismissedRequest.Fields.REQUEST_ID)

interface DismissedRequestDataIdProvider : CompositeDataIdProvider<DismissedRequest> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/notifications/dismissedRequest/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = DismissedRequest.Fields.REQUEST_ID
        )
    }
}
