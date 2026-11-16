package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The owner's note that a grant **awaits its acceptance** (`shared-realm-read-only-members.md` D7): which
 * member, in which realm, granted to which contact — what an owner device needs to derive the acceptance
 * address and poll it. Written at grant time, deleted once the acceptance is processed and the member's
 * `Member` row written.
 *
 * `Syncable(Eager)` in the owner's **personal** realm (like [GrantedMembership]), so any owner device can
 * pick the acceptance up. Holds a contact fingerprint and two ids — nothing secret.
 * ⚠ A separate type rather than a field on [GrantedMembership]: adding one there would reshape a shipped
 * composite (HR 9).
 */
interface AwaitedAcceptance : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `hash(memberAuthorityPub)` — matches the [GrantedMembership] row and the future [Member] row. */
    val memberId: TString get() = getField(Fields.MEMBER_ID)

    val realmId: TString get() = getField(Fields.REALM_ID)

    /** The grantee contact's A13 fingerprint — resolves the handshake context the address derives from. */
    val granteeFingerprint: TString get() = getField(Fields.GRANTEE_FINGERPRINT)

    /**
     * The key the grant carried, as `sha256(wrapped Kr)` hex — the same id a [RetainedRealmKey] is keyed by. On
     * acceptance the owner re-keys the member only if this key has since been retired: an exact, content-addressed
     * test that no late echo of a synced timestamp can defeat, and one that never sends a needless re-key (which
     * would overwrite whatever waits in the contact's one-slot grant mailbox).
     */
    val grantedKeyId: TString get() = getField(Fields.GRANTED_KEY_ID)

    companion object {
        const val ID = "tenshu/identity/awaitedAcceptance"

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
                string(Fields.GRANTEE_FINGERPRINT)
                string(Fields.GRANTED_KEY_ID)
            },
        )

        fun create(
            context: TContext,
            memberId: String,
            realmId: String,
            granteeFingerprint: String,
            grantedKeyId: String,
        ): AwaitedAcceptance =
            context.create(type = ID, fields = listOf(memberId, realmId, granteeFingerprint, grantedKeyId))
    }

    object Fields {
        const val MEMBER_ID = "$ID/memberId"
        const val REALM_ID = "$ID/realmId"
        const val GRANTEE_FINGERPRINT = "$ID/granteeFingerprint"
        const val GRANTED_KEY_ID = "$ID/grantedKeyId"
    }
}
