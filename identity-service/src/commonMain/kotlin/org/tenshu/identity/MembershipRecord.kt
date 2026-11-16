package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One **signed membership record** of a shared realm — a capability (grant or role change) or a
 * revocation — synced **inside that realm** (`shared-realm-membership-records.md` §3.1; verified by
 * `MembershipRecordVerifier` in `:sync-service-common`).
 *
 * The set of these rows is the realm's authority for **who is a member, with which role**. It carries the
 * master-signed statement the relay verifies at `/sync/realm-member/admit` and `/revoke`, byte-for-byte —
 * one signature, two planes.
 *
 * ⚠⚠ **Append-only by its key** ([key] includes the kind, the member and the statement's own stamp), and
 * a row that fails verification is ignored: anyone holding the realm key can write a row, and only the
 * master's signature makes it a record.
 *
 * ⚠ [key] leads with `"$realmId::"` — `RealmRegistry` routes a write into the shared realm by that prefix,
 * exactly as `Member` and `SharedFile` do.
 */
interface MembershipRecord : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"$realmId::$kind|$memberId|$epoch|$at"` — this row's id, and its realm route. */
    val key: TString get() = getField(Fields.KEY)

    val realmId: TString get() = getField(Fields.REALM_ID)

    /** [Kind.CAPABILITY] or [Kind.REVOCATION]. */
    val kind: TString get() = getField(Fields.KIND)

    /** The member authority (A26) public key the statement is about; `memberId = sha256(this)`. */
    val memberAuthorityPublicKey: TByteArray get() = getField(Fields.MEMBER_AUTHORITY_PUBLIC_KEY)

    /** The signed `epoch` — pinned to 0 today (`SharedRealmGrantManager.CAP_EPOCH`). */
    val epoch: TInteger get() = getField(Fields.EPOCH)

    /** The signed `issuedAt` (capability) or `revokedAt` (revocation), epoch millis. */
    val at: TInteger get() = getField(Fields.AT)

    /** The key [signature] verifies under: the realm master's. */
    val signerPublicKey: TByteArray get() = getField(Fields.SIGNER_PUBLIC_KEY)

    val signature: TByteArray get() = getField(Fields.SIGNATURE)

    /**
     * ⚠ Reserved for delegation (`shared-realm-membership-records.md` §7.2): an encoded chain of
     * delegation certificates, leaf → root. **Always empty** today, and a verifier must treat a
     * non-empty chain as invalid until delegation is designed.
     */
    val authorityChain: TByteArray get() = getField(Fields.AUTHORITY_CHAIN)

    object Kind {
        const val CAPABILITY = "capability"
        const val REVOCATION = "revocation"
    }

    companion object {
        const val ID = "tenshu/identity/membershipRecord"

        /** Must equal `RealmRegistry.NAMESPACE_SEP` (see [Member.compositeKey]). */
        private const val NAMESPACE_SEP = "::"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.KEY,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.KEY)
                string(Fields.REALM_ID)
                string(Fields.KIND)
                byteArray(Fields.MEMBER_AUTHORITY_PUBLIC_KEY)
                int(Fields.EPOCH)
                int(Fields.AT)
                byteArray(Fields.SIGNER_PUBLIC_KEY)
                byteArray(Fields.SIGNATURE)
                byteArray(Fields.AUTHORITY_CHAIN)
            },
        )

        fun keyOf(realmId: String, kind: String, memberId: String, epoch: Long, at: Long): String =
            "$realmId$NAMESPACE_SEP$kind|$memberId|$epoch|$at"

        fun create(
            context: TContext,
            realmId: String,
            kind: String,
            memberId: String,
            memberAuthorityPublicKey: ByteArray,
            epoch: Long,
            at: Long,
            signerPublicKey: ByteArray,
            signature: ByteArray,
            authorityChain: ByteArray = ByteArray(0),
        ): MembershipRecord = context.create(
            type = ID,
            fields = listOf(
                keyOf(realmId, kind, memberId, epoch, at).toTString(context),
                realmId.toTString(context),
                kind.toTString(context),
                memberAuthorityPublicKey.toTByteArray(context),
                epoch.toTInteger(context),
                at.toTInteger(context),
                signerPublicKey.toTByteArray(context),
                signature.toTByteArray(context),
                authorityChain.toTByteArray(context),
            ),
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val REALM_ID = "$ID/realmId"
        const val KIND = "$ID/kind"
        const val MEMBER_AUTHORITY_PUBLIC_KEY = "$ID/memberAuthorityPublicKey"
        const val EPOCH = "$ID/epoch"
        const val AT = "$ID/at"
        const val SIGNER_PUBLIC_KEY = "$ID/signerPublicKey"
        const val SIGNATURE = "$ID/signature"
        const val AUTHORITY_CHAIN = "$ID/authorityChain"
    }
}
