package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One **member record** of a shared realm — the Q25 in-realm member list
 * (`docs/proposals/shared-realm-membership-grant.md` §5, D5; gap G4).
 *
 * Unlike [SharedRealmMembership], which lives in *each member's own* personal
 * realm and answers "which shared realms am I in?", a `Member` record lives
 * **inside the shared realm itself**, encrypted under the realm key (Kr) like
 * all realm state — the relay never sees it. The set of `Member` rows is the
 * authoritative "who's in this share?" roster that Drive's *Manage access*
 * Members section reads, and a member writing their own row is the **acceptance
 * signal** (D4): the owner learns a grant became real when the new member's
 * record syncs in, with no separate ack channel.
 *
 * Pseudonymous by default per Q25: [displayName] is self-chosen and
 * [contactFingerprint] is set only for members who hold each other as contacts
 * (the empty string otherwise — the `ContactInvite.resultFingerprint` idiom).
 *
 * `Syncable(Eager)` + keyed by [memberId] (`= hash(memberAuthorityPub)`), so the
 * realm's devices converge on one row per member. The owner writes their own
 * `Member` row at bootstrap (lazy backfill for pre-GR realms on first grant).
 */
interface Member : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The shared realm this member belongs to — its `ownerId`. */
    val realmId: TString
        get() = getField(Fields.REALM_ID)

    /**
     * Derived key `"$realmId::$memberId"` — the `realmId::` prefix routes this row
     * **into the shared realm** via `RealmRegistry.realmForDataId` (the [SharedFile]
     * precedent), so a `Member` lives inside the realm, not the personal realm.
     */
    val compositeKey: TString
        get() = getField(Fields.COMPOSITE_KEY)

    /** Stable id of this member within the realm — `hash(memberAuthorityPub)` (D2/D5). */
    val memberId: TString
        get() = getField(Fields.MEMBER_ID)

    /** Self-chosen display name (pseudonymous by default, Q25). */
    val displayName: TString
        get() = getField(Fields.DISPLAY_NAME)

    /**
     * The member's A13 fingerprint, for members who hold each other as
     * contacts — lets the viewer cross-reference the realm roster against their
     * contact list. Empty string when not shared.
     */
    val contactFingerprint: TString
        get() = getField(Fields.CONTACT_FINGERPRINT)

    /** This member's role in the realm — see [Role]. */
    val role: TString
        get() = getField(Fields.ROLE)

    /** When the member joined (ms since epoch). */
    val joinedAt: UnixInstant
        get() = getField(Fields.JOINED_AT)

    companion object {
        const val ID = "tenshu/identity/member"

        /** Realm-namespace separator — must equal `RealmRegistry.NAMESPACE_SEP`. */
        private const val NAMESPACE_SEP = "::"

        /**
         * Derived row key `"$realmId::$memberId"`. The `realmId::` prefix is
         * load-bearing: `RealmRegistry` routes the write into the shared realm by
         * `key.substringBefore("::")`, so the realm id MUST lead.
         */
        fun compositeKey(realmId: String, memberId: String): String =
            "$realmId$NAMESPACE_SEP$memberId"

        /** Keys the member row by [compositeKey] — one row per member, inside its realm. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.COMPOSITE_KEY,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.REALM_ID)
                string(Fields.COMPOSITE_KEY)
                string(Fields.MEMBER_ID)
                string(Fields.DISPLAY_NAME)
                string(Fields.CONTACT_FINGERPRINT)
                string(Fields.ROLE)
                timestamp(Fields.JOINED_AT)
            },
        )

        fun create(
            context: TContext,
            realmId: String,
            memberId: String,
            displayName: String,
            contactFingerprint: String,
            role: String,
            joinedAt: Long,
        ): Member = context.create(
            type = ID,
            fields = listOf(
                realmId,
                compositeKey(realmId, memberId),
                memberId,
                displayName,
                contactFingerprint,
                role,
                joinedAt,
            ),
        )
    }

    object Fields {
        const val REALM_ID = "$ID/realmId"
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val MEMBER_ID = "$ID/memberId"
        const val DISPLAY_NAME = "$ID/displayName"
        const val CONTACT_FINGERPRINT = "$ID/contactFingerprint"
        const val ROLE = "$ID/role"
        const val JOINED_AT = "$ID/joinedAt"
    }

    /** Member roles. Stored as the [role] string (single-owner model, §5.C Q22). */
    object Role {
        const val OWNER = "owner"
        const val MEMBER = "member"
    }
}

// Entity-level accessors mirroring SharedRealmMembership — so chain-DSL
// projections can read fields without materialising the composite.
val Entity<out Member>.realmId: TString
    get() = getFieldValue(Member.Fields.REALM_ID)

val Entity<out Member>.memberId: TString
    get() = getFieldValue(Member.Fields.MEMBER_ID)

val Entity<out Member>.displayName: TString
    get() = getFieldValue(Member.Fields.DISPLAY_NAME)

val Entity<out Member>.contactFingerprint: TString
    get() = getFieldValue(Member.Fields.CONTACT_FINGERPRINT)

val Entity<out Member>.role: TString
    get() = getFieldValue(Member.Fields.ROLE)

val Entity<out Member>.joinedAt: UnixInstant
    get() = getFieldValue(Member.Fields.JOINED_AT)
