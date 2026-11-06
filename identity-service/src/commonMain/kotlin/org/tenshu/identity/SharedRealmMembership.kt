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
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A member realm's record of one **shared realm** it belongs to
 * (`tenshu-rei.md` §2.3 / §3.4): a shared folder is a realm with its own master
 * key (asset A12), and each member holds that master key **wrapped under their
 * own realm-identity X25519 encryption sub-key (A13)**. This composite is that
 * per-member record — `(sharedRealmId, wrappedMasterKey, role)` — held on the
 * member's `IdentityService`.
 *
 * **House Rule #1:** [wrappedMasterKey] is the *only* form of A12 that touches
 * storage or sync — the relay (and every other realm) sees only ciphertext that
 * solely this member's A13 private key can open. The plaintext A12 lives in
 * memory only while the member's realm is unlocked. Wrapping is once at
 * master-key level — never per file (§2.3).
 *
 * `Syncable(Eager)` + keyed by `sharedRealmId`, so a member's own devices
 * converge on the same membership row (one row per shared realm per member). The
 * row is written/read via [org.tenshu.identity.common.SharedRealmMembershipKeys]
 * (wrap on create, unwrap on read). The membership *collection* — listing the shared realms a
 * member is in — is the `SHARED_REALM_MEMBERSHIPS` relation, read via `GetSharedRealmMemberships`.
 */
interface SharedRealmMembership : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Opaque id of the shared realm this membership is for. Matches the realm's own id. */
    val sharedRealmId: TString
        get() = getField(Fields.SHARED_REALM_ID)

    /**
     * The shared realm's master key (A12) sealed to *this* member's A13 public
     * key — `SyncCryptoManager.wrapKeyForIdentity` output
     * (`ephemeralPub || aeadBlob`). Only the matching A13 private key unwraps it.
     */
    val wrappedMasterKey: TByteArray
        get() = getField(Fields.WRAPPED_MASTER_KEY)

    /** This member's role in the shared realm — see [Role]. Single-owner model (§5.C Q22). */
    val role: TString
        get() = getField(Fields.ROLE)

    companion object {
        const val ID = "tenshu/identity/sharedRealmMembership"

        /** Keys the membership row by `sharedRealmId` — one row per shared realm. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.SHARED_REALM_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.SHARED_REALM_ID)
                byteArray(Fields.WRAPPED_MASTER_KEY)
                string(Fields.ROLE)
            },
        )

        fun create(
            context: TContext,
            sharedRealmId: String,
            wrappedMasterKey: ByteArray,
            role: String,
        ): SharedRealmMembership = context.create(
            type = ID,
            fields = listOf(
                sharedRealmId.toTString(context),
                wrappedMasterKey.toTByteArray(context),
                role.toTString(context),
            ),
        )
    }

    object Fields {
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val WRAPPED_MASTER_KEY = "$ID/wrappedMasterKey"
        const val ROLE = "$ID/role"
    }

    /** Membership roles (§5.C Q22 — single-owner, transferable). Stored as the [role] string. */
    object Role {
        const val OWNER = "owner"
        const val MEMBER = "member"
    }
}

// Entity-level accessors mirroring Device / Share — so chain-DSL projections can
// read fields without materialising the composite.
val Entity<out SharedRealmMembership>.sharedRealmId: TString
    get() = getFieldValue(SharedRealmMembership.Fields.SHARED_REALM_ID)

val Entity<out SharedRealmMembership>.wrappedMasterKey: TByteArray
    get() = getFieldValue(SharedRealmMembership.Fields.WRAPPED_MASTER_KEY)

val Entity<out SharedRealmMembership>.role: TString
    get() = getFieldValue(SharedRealmMembership.Fields.ROLE)
