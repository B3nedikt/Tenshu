package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **discovered-but-not-yet-accepted** shared-realm grant — the quarantined
 * pending record D4 describes (`docs/proposals/shared-realm-membership-grant.md`
 * §12, GR-6).
 *
 * The grant mailbox `GET /sync/grant/{address}` is single-pickup (destructive), so
 * the inbound poll (`pollPendingGrants`) — which sweeps every grantable contact's
 * derivable address on Shared-screen init — must **store** the sealed blob it picks
 * up rather than accept it inline, so the user can explicitly accept later (D4:
 * contact ≠ consent to receive bytes). This row *is* that store: the still-sealed
 * [sealedGrant] blob plus the [grantorFingerprint] the poll resolved from the
 * address (the quarantine label — a grant is always rendered by contact petname +
 * fingerprint). Accept unseals + joins and tombstones this row; decline just
 * tombstones it (the grant is lost — the owner can re-grant).
 *
 * `Syncable(Eager)`, keyed by [grantorFingerprint] (one pending grant per contact —
 * the address is per-(grantor, grantee) pair, so the mailbox holds at most one at a
 * time): the pending record propagates to the user's other devices, so whichever
 * device the user reaches for can accept, even though only the first to poll
 * actually picked the blob up. The blob is sealed to this user's A13 and stored in
 * personal-realm state like [MemberAuthorityKey]'s A26 private key, so syncing it is
 * House-Rule-#1-safe (the relay only ever saw ciphertext).
 */
interface PendingGrant : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The grantor's `TenshuIdentity` fingerprint — the quarantine label + accept key. */
    val grantorFingerprint: TString
        get() = getField(Fields.GRANTOR_FINGERPRINT)

    /** The still-sealed grant blob picked up from the mailbox (sealed to our A13). */
    val sealedGrant: TByteArray
        get() = getField(Fields.SEALED_GRANT)

    /** The grant address the blob was picked up from (hex) — the AAD accept re-binds. */
    val address: TString
        get() = getField(Fields.ADDRESS)

    /** When the poll discovered this grant (ms since epoch). */
    val discoveredAt: UnixInstant
        get() = getField(Fields.DISCOVERED_AT)

    companion object {
        const val ID = "tenshu/identity/pendingGrant"

        /** Keys by [grantorFingerprint] — one pending grant per contact. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.GRANTOR_FINGERPRINT,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.GRANTOR_FINGERPRINT)
                byteArray(Fields.SEALED_GRANT)
                string(Fields.ADDRESS)
                timestamp(Fields.DISCOVERED_AT)
            },
        )

        fun create(
            context: TContext,
            grantorFingerprint: String,
            sealedGrant: ByteArray,
            address: String,
            discoveredAt: Long,
        ): PendingGrant = context.create(
            type = ID,
            fields = listOf(
                grantorFingerprint,
                sealedGrant,
                address,
                discoveredAt,
            ),
        )
    }

    object Fields {
        const val GRANTOR_FINGERPRINT = "$ID/grantorFingerprint"
        const val SEALED_GRANT = "$ID/sealedGrant"
        const val ADDRESS = "$ID/address"
        const val DISCOVERED_AT = "$ID/discoveredAt"
    }
}

// Entity-level accessors mirroring Member.
val Entity<out PendingGrant>.grantorFingerprint: TString
    get() = getFieldValue(PendingGrant.Fields.GRANTOR_FINGERPRINT)

val Entity<out PendingGrant>.sealedGrant: TByteArray
    get() = getFieldValue(PendingGrant.Fields.SEALED_GRANT)

val Entity<out PendingGrant>.address: TString
    get() = getFieldValue(PendingGrant.Fields.ADDRESS)

val Entity<out PendingGrant>.discoveredAt: UnixInstant
    get() = getFieldValue(PendingGrant.Fields.DISCOVERED_AT)
