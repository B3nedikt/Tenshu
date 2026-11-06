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
 * The **owner's custody of a shared realm's master keypair** (asset A1, the realm's
 * Ed25519 identity) — the durable half of GR-6
 * (`docs/proposals/shared-realm-membership-grant.md` §12, GR-6).
 *
 * Before GR-6 the realm master private key lived **only** in the in-memory
 * [org.tenshu.sync.common.RealmContext] that [SharedRealmBootstrapper] built at
 * creation, so the owner could grant/remove members only in the same session that
 * created the realm — across a restart the key was gone and the grant/remove ops
 * failed loudly. Persisting the keypair as a `Syncable(Eager)` row in the owner's
 * **own personal realm** closes that gap **and** lets *any* of the owner's paired
 * devices grant/remove (the row syncs to them), not just the creating device.
 *
 * The private key is personal-realm state like the contact A24 secret and the
 * member-authority A26 ([MemberAuthorityKey]) private key: the owner already holds
 * the personal master and is the realm's creator, so syncing A1 across the owner's
 * own devices is no escalation (every owner device is, by identity, the owner). It
 * is wrapped at rest/in transit by the personal realm key like all personal state;
 * House Rule #1 holds (the relay only ever sees the realm's ciphertext). Keyed by
 * [sharedRealmId] — one master keypair per realm this device owns.
 */
interface OwnedRealmMaster : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The shared realm this is the master keypair for — matches the realm's `ownerId`. */
    val sharedRealmId: TString
        get() = getField(Fields.SHARED_REALM_ID)

    /** The realm master Ed25519 public key (`ownerId = hash(this)`). */
    val masterPublicKey: TByteArray
        get() = getField(Fields.MASTER_PUBLIC_KEY)

    /** The realm master Ed25519 private key — signs member capabilities + revocations. */
    val masterPrivateKey: TByteArray
        get() = getField(Fields.MASTER_PRIVATE_KEY)

    companion object {
        const val ID = "tenshu/identity/ownedRealmMaster"

        /** Keys by [sharedRealmId] — one master keypair per realm this device owns. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.SHARED_REALM_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.SHARED_REALM_ID)
                byteArray(Fields.MASTER_PUBLIC_KEY)
                byteArray(Fields.MASTER_PRIVATE_KEY)
            },
        )

        fun create(
            context: TContext,
            sharedRealmId: String,
            masterPublicKey: ByteArray,
            masterPrivateKey: ByteArray,
        ): OwnedRealmMaster = context.create(
            type = ID,
            fields = listOf(
                sharedRealmId,
                masterPublicKey,
                masterPrivateKey,
            ),
        )
    }

    object Fields {
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val MASTER_PUBLIC_KEY = "$ID/masterPublicKey"
        const val MASTER_PRIVATE_KEY = "$ID/masterPrivateKey"
    }
}

// Entity-level accessors mirroring MemberAuthorityKey.
val Entity<out OwnedRealmMaster>.sharedRealmId: TString
    get() = getFieldValue(OwnedRealmMaster.Fields.SHARED_REALM_ID)

val Entity<out OwnedRealmMaster>.masterPublicKey: TByteArray
    get() = getFieldValue(OwnedRealmMaster.Fields.MASTER_PUBLIC_KEY)

val Entity<out OwnedRealmMaster>.masterPrivateKey: TByteArray
    get() = getFieldValue(OwnedRealmMaster.Fields.MASTER_PRIVATE_KEY)
