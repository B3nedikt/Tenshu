package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * The typed return of [AwaitInviteAck] (`drive-manage-access-ux.md` §3.2):
 * the [AwaitInviteAck.Result] status plus — on `ACCEPTED` — what the sealed
 * ack carried: the inviter's self-asserted name (a *claim*, contact doc §4.1
 * quarantine applies), their key fingerprint, and the advisory share intent.
 *
 * **Why a composite, not the bare status `TString`** — the success card and
 * the "<name> wants to share '<label>'" line need the unsealed ack's
 * contents, and the proxy is the only place that can unseal; nothing else
 * client-readable carries the intent (the invitee holds no `ContactInvite`
 * row). Mirrors `ShareLinkResult` as a transient, never-persisted return
 * shape. All extras are `""` for non-`ACCEPTED` statuses.
 */
interface InviteAckResult : Composite {

    override val schema: CompositeSchema get() = Schema

    /** One of [AwaitInviteAck.Result]. */
    val status: TString
        get() = getField(Field.STATUS)

    /** Inviter's self-asserted name from the ack — a claim; `""` unless ACCEPTED. */
    val peerName: TString
        get() = getField(Field.PEER_NAME)

    /** Fingerprint of the (link-pinned, verified) inviter key; `""` unless ACCEPTED. */
    val peerFingerprint: TString
        get() = getField(Field.PEER_FINGERPRINT)

    /** Advisory share intent riding the ack (`""` or [ContactInvite.IntentKind]). */
    val intentKind: TString
        get() = getField(Field.INTENT_KIND)

    val intentLabel: TString
        get() = getField(Field.INTENT_LABEL)

    /**
     * `deriveContactSas(ownA13Pub, inviterKey)` — the §5.3 code, derived
     * proxy-side (the keys never cross the boundary); `""` unless ACCEPTED.
     */
    val sas: TString
        get() = getField(Field.SAS)

    companion object {
        const val ID = "tenshu/contact/inviteAckResult"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Field.STATUS)
                string(Field.PEER_NAME)
                string(Field.PEER_FINGERPRINT)
                string(Field.INTENT_KIND)
                string(Field.INTENT_LABEL)
                string(Field.SAS)
            }
        )

        fun create(
            context: TContext,
            status: String,
            peerName: String = "",
            peerFingerprint: String = "",
            intentKind: String = "",
            intentLabel: String = "",
            sas: String = "",
        ): InviteAckResult = context.create(
            type = ID,
            fields = listOf(status, peerName, peerFingerprint, intentKind, intentLabel, sas),
        )
    }

    object Field {
        const val STATUS = "$ID/status"
        const val PEER_NAME = "$ID/peerName"
        const val PEER_FINGERPRINT = "$ID/peerFingerprint"
        const val INTENT_KIND = "$ID/intentKind"
        const val INTENT_LABEL = "$ID/intentLabel"
        const val SAS = "$ID/sas"
    }
}

val Entity<out InviteAckResult>.status: TString
    get() = getFieldValue(InviteAckResult.Field.STATUS)

val Entity<out InviteAckResult>.peerName: TString
    get() = getFieldValue(InviteAckResult.Field.PEER_NAME)

val Entity<out InviteAckResult>.peerFingerprint: TString
    get() = getFieldValue(InviteAckResult.Field.PEER_FINGERPRINT)
