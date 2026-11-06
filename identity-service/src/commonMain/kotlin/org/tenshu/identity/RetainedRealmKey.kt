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
 * A **retired** shared-realm content key (a prior `Kr` / A12) this member still
 * needs to read content written before a rotation — the retained-keyring half of
 * GR-7 (`docs/proposals/shared-realm-membership-grant.md` §13.4 / §6 GR-7 row).
 *
 * Removing a member rotates the realm's content key to a fresh `Kr′` (Q26 / D6) so
 * the removed member's key buys no *new* content. Per ADR-0017 unchanged files are
 * **not** bulk re-encrypted — so every block + chunk written under the old `Kr`
 * stays encrypted under it. Without a record of the old key the rotation would
 * orphan all prior content. This row *is* that record: one per retired key per
 * realm, holding the old `Kr` **wrapped to this member's A13** (the exact form
 * [SharedRealmMembership.wrappedMasterKey] carries — unwrapped with the A13 private
 * key into a `DerivedKeys` the decrypt path trial-decrypts against on AEAD failure).
 *
 * `Syncable(Eager)` in the member's **own personal realm** (House Rule #1: only the
 * *wrapped* old `Kr` ever leaves the device, like [SharedRealmMembership] and the
 * A26 [MemberAuthorityKey] private key), so the keyring propagates to every device
 * of theirs — whichever device reads old content can decrypt it.
 *
 * Keyed by `"$sharedRealmId::$keyId"` where `keyId = sha256(wrappedMasterKey)` hex:
 * one row per (realm, retired key), idempotent on a re-rotation that re-retires the
 * same key. The flat [IdentityService.Relations.RETAINED_REALM_KEYS] array (derived
 * by `RetainedRealmKeyRelationMaintainer`) is the read surface; the keyring builder
 * filters it by [sharedRealmId].
 */
interface RetainedRealmKey : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Derived key `"$sharedRealmId::$keyId"` — one row per (realm, retired key). */
    val compositeKey: TString
        get() = getField(Fields.COMPOSITE_KEY)

    /** The shared realm this retired key belongs to — matches the realm's `ownerId`. */
    val sharedRealmId: TString
        get() = getField(Fields.SHARED_REALM_ID)

    /** The retired `Kr` (A12) wrapped to this member's A13 — same form as [SharedRealmMembership.wrappedMasterKey]. */
    val wrappedMasterKey: TByteArray
        get() = getField(Fields.WRAPPED_MASTER_KEY)

    /** When this key was retired (rotated away from), ms since epoch. */
    val retiredAt: UnixInstant
        get() = getField(Fields.RETIRED_AT)

    companion object {
        const val ID = "tenshu/identity/retainedRealmKey"

        /** Separator between the realm id and the per-key id in the composite key. */
        const val KEY_SEP = "::"

        /** The composite key for a (realm, retired key) pair — `"$sharedRealmId::$keyId"`. */
        fun compositeKey(sharedRealmId: String, keyId: String): String =
            "$sharedRealmId$KEY_SEP$keyId"

        /** Keys by [sharedRealmId]`::`keyId — one row per retired key per realm. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.COMPOSITE_KEY,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.COMPOSITE_KEY)
                string(Fields.SHARED_REALM_ID)
                byteArray(Fields.WRAPPED_MASTER_KEY)
                timestamp(Fields.RETIRED_AT)
            },
        )

        fun create(
            context: TContext,
            sharedRealmId: String,
            keyId: String,
            wrappedMasterKey: ByteArray,
            retiredAt: Long,
        ): RetainedRealmKey = context.create(
            type = ID,
            fields = listOf(
                compositeKey(sharedRealmId, keyId),
                sharedRealmId,
                wrappedMasterKey,
                retiredAt,
            ),
        )
    }

    object Fields {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val SHARED_REALM_ID = "$ID/sharedRealmId"
        const val WRAPPED_MASTER_KEY = "$ID/wrappedMasterKey"
        const val RETIRED_AT = "$ID/retiredAt"
    }
}

// Entity-level accessors mirroring OwnedRealmMaster.
val Entity<out RetainedRealmKey>.compositeKey: TString
    get() = getFieldValue(RetainedRealmKey.Fields.COMPOSITE_KEY)

val Entity<out RetainedRealmKey>.sharedRealmId: TString
    get() = getFieldValue(RetainedRealmKey.Fields.SHARED_REALM_ID)

val Entity<out RetainedRealmKey>.wrappedMasterKey: TByteArray
    get() = getFieldValue(RetainedRealmKey.Fields.WRAPPED_MASTER_KEY)

val Entity<out RetainedRealmKey>.retiredAt: UnixInstant
    get() = getFieldValue(RetainedRealmKey.Fields.RETIRED_AT)
