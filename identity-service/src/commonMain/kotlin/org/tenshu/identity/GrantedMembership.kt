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
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The **owner's record of a member it granted** — the durable roster GR-6 adds so
 * an owner can remove a member by their stable [memberId]
 * (`docs/proposals/shared-realm-membership-grant.md` §12, GR-6).
 *
 * Removal needs the member-authority public key (A26) to sign the revocation
 * ([org.tenshu.sync.common.MembershipRevocation]), but the owner only *minted* that
 * key transiently at grant time (Option A, §11.1) and the in-realm [Member] row
 * carries just its hash (`memberId = hash(A26pub)`). So the owner persists the A26
 * **public** key here, keyed by that same `memberId`, the moment it grants — the
 * `RemoveSharedRealmMember` op then resolves `memberId → A26pub` from this row.
 *
 * `Syncable(Eager)` in the owner's **own personal realm** (like [OwnedRealmMaster]),
 * so every owner device can remove a member, not just the one that granted. The A26
 * public key is master-signed-capability material, never a secret, so syncing it is
 * House-Rule-#1-safe. Keyed by [memberId] — globally unique (A26 is fresh per
 * (member, realm)).
 */
interface GrantedMembership : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable id of the granted member — `hash(memberAuthorityPub)`, matches the [Member] row. */
    val memberId: TString
        get() = getField(Fields.MEMBER_ID)

    /** The shared realm the member was granted into — its `ownerId`. */
    val realmId: TString
        get() = getField(Fields.REALM_ID)

    /** The member-authority (A26) public key the owner minted — what a revocation names. */
    val memberAuthorityPublicKey: TByteArray
        get() = getField(Fields.MEMBER_AUTHORITY_PUBLIC_KEY)

    companion object {
        const val ID = "tenshu/identity/grantedMembership"

        /** Keys by [memberId] — one roster row per granted member. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.MEMBER_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.MEMBER_ID)
                string(Fields.REALM_ID)
                byteArray(Fields.MEMBER_AUTHORITY_PUBLIC_KEY)
            },
        )

        fun create(
            context: TContext,
            memberId: String,
            realmId: String,
            memberAuthorityPublicKey: ByteArray,
        ): GrantedMembership = context.create(
            type = ID,
            fields = listOf(
                memberId,
                realmId,
                memberAuthorityPublicKey,
            ),
        )
    }

    object Fields {
        const val MEMBER_ID = "$ID/memberId"
        const val REALM_ID = "$ID/realmId"
        const val MEMBER_AUTHORITY_PUBLIC_KEY = "$ID/memberAuthorityPublicKey"
    }
}

// Entity-level accessors mirroring Member.
val Entity<out GrantedMembership>.memberId: TString
    get() = getFieldValue(GrantedMembership.Fields.MEMBER_ID)

val Entity<out GrantedMembership>.realmId: TString
    get() = getFieldValue(GrantedMembership.Fields.REALM_ID)

val Entity<out GrantedMembership>.memberAuthorityPublicKey: TByteArray
    get() = getFieldValue(GrantedMembership.Fields.MEMBER_AUTHORITY_PUBLIC_KEY)
