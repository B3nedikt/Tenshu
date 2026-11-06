package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.Instant

/**
 * One row of [ListPendingRedemptions] (`drive-manage-access-ux.md` §4.2): an
 * invitee-side redemption whose ack wait is resumable. Deliberately carries
 * **neither** `K_link` nor the peer key itself — only the fingerprint; the
 * client needs nothing more to re-invoke `awaitInviteAck` and label the
 * waiting row. Transient return shape, never persisted (the durable record
 * is the proxy-side `PendingRedemptionStore` entry).
 */
interface PendingRedemptionInfo : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Hex invite id — the handle `awaitInviteAck` resumes with. */
    val inviteId: TString
        get() = getField(Field.INVITE_ID)

    val redeemedAt: Instant
        get() = getField(Field.REDEEMED_AT)

    /** Fingerprint of the link-pinned inviter key. */
    val peerFingerprint: TString
        get() = getField(Field.PEER_FINGERPRINT)

    /**
     * `deriveContactSas(ownA13Pub, linkPinnedInviterKey)` — derivable as soon
     * as the link is redeemed, so the §5.3 waiting state can display it.
     * Proxy-derived (keys never cross the boundary); `""` if the realm is
     * locked at read time.
     */
    val sas: TString
        get() = getField(Field.SAS)

    companion object {
        const val ID = "tenshu/contact/pendingRedemptionInfo"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Field.INVITE_ID)
                timestamp(Field.REDEEMED_AT)
                string(Field.PEER_FINGERPRINT)
                string(Field.SAS)
            }
        )

        fun create(
            context: TContext,
            inviteId: String,
            redeemedAt: Long,
            peerFingerprint: String,
            sas: String = "",
        ): PendingRedemptionInfo = context.create(
            type = ID,
            fields = listOf(inviteId, redeemedAt, peerFingerprint, sas),
        )
    }

    object Field {
        const val INVITE_ID = "$ID/inviteId"
        const val REDEEMED_AT = "$ID/redeemedAt"
        const val PEER_FINGERPRINT = "$ID/peerFingerprint"
        const val SAS = "$ID/sas"
    }
}

val Entity<out PendingRedemptionInfo>.inviteId: TString
    get() = getFieldValue(PendingRedemptionInfo.Field.INVITE_ID)

val Entity<out PendingRedemptionInfo>.redeemedAt: Instant
    get() = getFieldValue(PendingRedemptionInfo.Field.REDEEMED_AT)

val Entity<out PendingRedemptionInfo>.peerFingerprint: TString
    get() = getFieldValue(PendingRedemptionInfo.Field.PEER_FINGERPRINT)
