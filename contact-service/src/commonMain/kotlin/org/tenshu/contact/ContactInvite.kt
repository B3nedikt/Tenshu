package org.tenshu.contact

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
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.Instant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Inviter-side record of an issued contact invitation
 * (`docs/proposals/contact-invitation-handshake.md` §7.1; threat-model A25).
 *
 * A personal-realm `Syncable(Eager)` so **every** inviter device sees the
 * pending invite and any of them can complete the handshake (the inviter's
 * A13 key is on all of them) — the multi-device half of the §4 flow.
 *
 * Stores only [secretHash] (`SHA-256(K_link)` hex), never the raw link
 * secret: a compromise of the inviter's synced data cannot impersonate an
 * invitee, and `K_link` rides back inside the sealed response, so the hash is
 * all completion needs. The raw link exists only in the issuing session's
 * memory — "show the QR again" later = revoke + re-issue.
 */
interface ContactInvite : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Hex form of the 16-byte invite id — the key and relay mailbox address. */
    val inviteId: TString
        get() = getField(Field.INVITE_ID)

    /** `SHA-256(K_link)` hex; the raw secret is never persisted. */
    val secretHash: TString
        get() = getField(Field.SECRET_HASH)

    /** One of [Status]. */
    val status: TString
        get() = getField(Field.STATUS)

    val createdAt: Instant
        get() = getField(Field.CREATED_AT)

    val expiresAt: Instant
        get() = getField(Field.EXPIRES_AT)

    /** `""` or [IntentKind.SHARE_FOLDER] — advisory share intent (§2). */
    val intentKind: TString
        get() = getField(Field.INTENT_KIND)

    val intentLabel: TString
        get() = getField(Field.INTENT_LABEL)

    /** [TenshuIdentity.fingerprint] link once [Status.COMPLETED]; `""` before. */
    val resultFingerprint: TString
        get() = getField(Field.RESULT_FINGERPRINT)

    /**
     * The responder's **self-asserted** name from the verified response, set
     * when the row flips to [Status.REDEEMED]; `""` before. Strictly a claim
     * (contact doc §4.1 quarantine) — UIs must render it as such (quoted,
     * never auto-promoted to a petname). Synced so *any* inviter device can
     * show the completion prompt (`drive-manage-access-ux.md` §2.3) without a
     * relay round trip.
     */
    val claimedName: TString
        get() = getField(Field.CLAIMED_NAME)

    /** Fingerprint of the responder's claimed key, set with [claimedName]. */
    val claimedFingerprint: TString
        get() = getField(Field.CLAIMED_FINGERPRINT)

    /**
     * `deriveContactSas(ownA13Pub, claimedKey)` — the 6-digit §5.3 code, set
     * with [claimedName] (`""` before `REDEEMED`). Derived **proxy-side**
     * because the raw keys never cross the client boundary; display-ready and
     * public-derivation by design (key-fingerprint comparison, not secrecy).
     * Same on every inviter device (A13 is realm-wide), so it syncs.
     */
    val claimedSas: TString
        get() = getField(Field.CLAIMED_SAS)

    companion object {
        const val ID = "tenshu/contact/contactInvite"

        /** §3: 72-hour default validity; the relay caps any request at 7 days. */
        const val DEFAULT_TTL_MILLIS: Long = 72 * 60 * 60 * 1000L

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                ContactInviteDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.INVITE_ID)
                string(Field.SECRET_HASH)
                string(Field.STATUS)
                timestamp(Field.CREATED_AT)
                timestamp(Field.EXPIRES_AT)
                string(Field.INTENT_KIND)
                string(Field.INTENT_LABEL)
                string(Field.RESULT_FINGERPRINT)
                string(Field.CLAIMED_NAME)
                string(Field.CLAIMED_FINGERPRINT)
                string(Field.CLAIMED_SAS)
            }
        )

        fun create(
            context: TContext,
            inviteId: String,
            secretHash: String,
            status: String,
            createdAt: Long,
            expiresAt: Long,
            intentKind: String = "",
            intentLabel: String = "",
            resultFingerprint: String = "",
            claimedName: String = "",
            claimedFingerprint: String = "",
            claimedSas: String = "",
        ): ContactInvite = context.create(
            type = ID,
            fields = listOf(
                inviteId,
                secretHash,
                status,
                createdAt,
                expiresAt,
                intentKind,
                intentLabel,
                resultFingerprint,
                claimedName,
                claimedFingerprint,
                claimedSas,
            ),
        )
    }

    object Field {
        const val INVITE_ID = "$ID/inviteId"
        const val SECRET_HASH = "$ID/secretHash"
        const val STATUS = "$ID/status"
        const val CREATED_AT = "$ID/createdAt"
        const val EXPIRES_AT = "$ID/expiresAt"
        const val INTENT_KIND = "$ID/intentKind"
        const val INTENT_LABEL = "$ID/intentLabel"
        const val RESULT_FINGERPRINT = "$ID/resultFingerprint"
        const val CLAIMED_NAME = "$ID/claimedName"
        const val CLAIMED_FINGERPRINT = "$ID/claimedFingerprint"
        const val CLAIMED_SAS = "$ID/claimedSas"
    }

    /**
     * Invite lifecycle (§4/§7.1). `COMPROMISED` is the burn state: a response
     * arrived whose secret failed the [secretHash] check — evidence the link
     * channel leaked; the client auto-rejects and warns the user to re-issue
     * over a better channel.
     */
    object Status {
        const val PENDING = "PENDING"
        const val REDEEMED = "REDEEMED"
        const val COMPLETED = "COMPLETED"
        const val REJECTED = "REJECTED"
        const val REVOKED = "REVOKED"
        const val EXPIRED = "EXPIRED"
        const val COMPROMISED = "COMPROMISED"
    }

    /** Advisory share intents riding the sealed ack — never the link. */
    object IntentKind {
        const val SHARE_FOLDER = "SHARE_FOLDER"
    }
}

val Entity<out ContactInvite>.inviteId: TString
    get() = getFieldValue(ContactInvite.Field.INVITE_ID)

val Entity<out ContactInvite>.status: TString
    get() = getFieldValue(ContactInvite.Field.STATUS)

val Entity<out ContactInvite>.createdAt: Instant
    get() = getFieldValue(ContactInvite.Field.CREATED_AT)

val Entity<out ContactInvite>.expiresAt: Instant
    get() = getFieldValue(ContactInvite.Field.EXPIRES_AT)

val Entity<out ContactInvite>.intentKind: TString
    get() = getFieldValue(ContactInvite.Field.INTENT_KIND)

val Entity<out ContactInvite>.intentLabel: TString
    get() = getFieldValue(ContactInvite.Field.INTENT_LABEL)

val Entity<out ContactInvite>.claimedName: TString
    get() = getFieldValue(ContactInvite.Field.CLAIMED_NAME)

val Entity<out ContactInvite>.claimedFingerprint: TString
    get() = getFieldValue(ContactInvite.Field.CLAIMED_FINGERPRINT)

/**
 * The fingerprint of the key the completed handshake produced — `""` until
 * [Status.COMPLETED]. It is the join key from a finished invite back to the
 * contact it created, which is what lets a *deferred* grant offer be derived
 * from synced rows rather than chained onto the completion prompt.
 */
val Entity<out ContactInvite>.resultFingerprint: TString
    get() = getFieldValue(ContactInvite.Field.RESULT_FINGERPRINT)

val Entity<out ContactInvite>.claimedSas: TString
    get() = getFieldValue(ContactInvite.Field.CLAIMED_SAS)

interface ContactInviteDataIdProvider : CompositeDataIdProvider<ContactInvite> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/contact/contactInvite/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ContactInvite.Field.INVITE_ID,
        )
    }
}
