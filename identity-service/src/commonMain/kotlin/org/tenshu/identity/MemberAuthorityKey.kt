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
 * A member's **member-authority keypair** (asset A26) for one shared realm —
 * the storage half of D2 (`docs/proposals/shared-realm-membership-grant.md` §11.1, which
 * supersedes D2's own prose in §3).
 *
 * ⚠ **The owner mints A26, not the member.** At *grant* time the owner generates the
 * per-realm Ed25519 keypair and ships the private half inside the A13-sealed grant payload;
 * the grantee persists it here, and the owner keeps only the public key so it can revoke.
 * The realm-master-signed capability binds that *public* key (so the relay cannot correlate
 * it with the member's personal `ownerId`), and the *private* key signs each of the member's
 * per-realm device bindings. Persisting it as a
 * `Syncable(Eager)` row in the member's **own personal realm** is what lets
 * every device of theirs — present and future — bind itself without the owner
 * being online: a new device syncs this row and self-signs a
 * [MemberDeviceBinding][org.tenshu.sync.common.MemberDeviceBinding].
 *
 * The private key is personal-realm state (like the contact A24 secret); it is
 * realm-local by construction, so compromise stays scoped to the single realm
 * and never yields Kr (§5 A26). Keyed by [sharedRealmId] — one authority key per
 * shared realm.
 */
interface MemberAuthorityKey : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The shared realm this authority key is for — matches the realm's `ownerId`. */
    val sharedRealmId: TString
        get() = getField(Fields.SHARED_REALM_ID)

    /** The Ed25519 public key the realm-master capability binds. */
    val publicKey: TByteArray
        get() = getField(Fields.PUBLIC_KEY)

    /** The Ed25519 private key that signs this member's per-realm device bindings. */
    val privateKey: TByteArray
        get() = getField(Fields.PRIVATE_KEY)

    /**
     * The realm-master-signed **membership capability** for [publicKey], as an
     * opaque blob (`epoch ‖ issuedAt ‖ signature`; `realmId`/authority pub are
     * already on this row). Synced with the keypair so **every** device of the
     * member can present `capability + deviceBinding` to
     * `POST /sync/realm-member/admit` and self-bind without the owner online
     * (D2/D9-b). Master-signed and secret-free, so syncing it is
     * House-Rule-#1-safe. Encoded/decoded by `MembershipCapability.toStoredBytes`
     * / `membershipCapabilityFromStored` (`:sync-service-common`), which this
     * module does not depend on.
     */
    val capability: TByteArray
        get() = getField(Fields.CAPABILITY)

    companion object {
        const val ID = "tenshu/identity/memberAuthorityKey"

        /** Keys by [sharedRealmId] — one authority key per shared realm per member. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.SHARED_REALM_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.SHARED_REALM_ID)
                byteArray(Fields.PUBLIC_KEY)
                byteArray(Fields.PRIVATE_KEY)
                byteArray(Fields.CAPABILITY)
            },
        )

        fun create(
            context: TContext,
            sharedRealmId: String,
            publicKey: ByteArray,
            privateKey: ByteArray,
            capability: ByteArray,
        ): MemberAuthorityKey = context.create(
            type = ID,
            fields = listOf(
                sharedRealmId,
                publicKey,
                privateKey,
                capability,
            ),
        )
    }

    object Fields {
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val PUBLIC_KEY = "$ID/publicKey"
        const val PRIVATE_KEY = "$ID/privateKey"
        const val CAPABILITY = "$ID/capability"
    }
}

// Entity-level accessors mirroring SharedRealmMembership.
val Entity<out MemberAuthorityKey>.sharedRealmId: TString
    get() = getFieldValue(MemberAuthorityKey.Fields.SHARED_REALM_ID)

val Entity<out MemberAuthorityKey>.publicKey: TByteArray
    get() = getFieldValue(MemberAuthorityKey.Fields.PUBLIC_KEY)

val Entity<out MemberAuthorityKey>.privateKey: TByteArray
    get() = getFieldValue(MemberAuthorityKey.Fields.PRIVATE_KEY)

val Entity<out MemberAuthorityKey>.capability: TByteArray
    get() = getFieldValue(MemberAuthorityKey.Fields.CAPABILITY)
