package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.Instant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One keyring entry: a peer's realm-identity X25519 public key (A13-class)
 * plus its verification state
 * (`docs/proposals/contact-service-identity-model.md` §4.2).
 *
 * A keyring **per person, not a key**: one [Person] may hold multiple
 * identities (key rotation, future pairwise identities, Rei attestation
 * keys); [personId] is the aggregate back-link (`""` = unlinked candidate).
 *
 * **Verification lives on the key, never on the person.** The UI checkmark on
 * a `Person` is derived (≥1 linked identity at `VERIFIED`+); linking a key to
 * a contact card must never transfer trust (§6 of the contact doc — merge =
 * re-point [personId], a cosmetic act). [verification] is never auto-elevated
 * (threat-model A24):
 *
 *  - [Verification.UNVERIFIED] — TOFU: acquired via the remote-link handshake.
 *  - [Verification.VERIFIED] — SAS/QR confirmed out of band (`deriveContactSas`).
 *  - [Verification.ATTESTED] — carried by a Tenshu Rei attestation chain.
 *
 * [selfAssertedName] is what *they* claimed at handshake time — quarantined
 * here, never auto-copied to the `Person` petname. Realm associations are not
 * stored here: "which realms does this key access" is derivable from the
 * memberships wrapped to it.
 */
interface TenshuIdentity : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `hash(publicKey)` — the key of this row; stable across re-links. */
    val fingerprint: TString
        get() = getField(Field.FINGERPRINT)

    val publicKey: TByteArray
        get() = getField(Field.PUBLIC_KEY)

    /** Aggregate link to [Person.personId]; `""` = unlinked. */
    val personId: TString
        get() = getField(Field.PERSON_ID)

    val selfAssertedName: TString
        get() = getField(Field.SELF_ASSERTED_NAME)

    /** One of [Verification]. */
    val verification: TString
        get() = getField(Field.VERIFICATION)

    val verifiedAt: Instant
        get() = getField(Field.VERIFIED_AT)

    /** One of [Source]. */
    val source: TString
        get() = getField(Field.SOURCE)

    /**
     * The **grant seed** for this contact (`shared-realm-membership-grant.md`
     * §9.1, D8) — `HKDF(K_link)` (`deriveGrantSeed`), derived during the
     * handshake when this side transiently held `K_link` and persisted here so
     * the grant mailbox address stays secret-derivable post-handshake. `empty`
     * for identities acquired via a path that carries no `K_link` (e.g. Rei)
     * and for rows written before the field existed — such contacts are not
     * grantable until re-handshaked. Optional, so rows without it parse.
     */
    val grantSeed: TByteArray
        get() = getField(Field.GRANT_SEED)

    companion object {
        const val ID = "tenshu/contact/tenshuIdentity"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                TenshuIdentityDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.FINGERPRINT)
                byteArray(Field.PUBLIC_KEY)
                string(Field.PERSON_ID)
                string(Field.SELF_ASSERTED_NAME)
                string(Field.VERIFICATION)
                timestamp(Field.VERIFIED_AT)
                string(Field.SOURCE)
                byteArray(Field.GRANT_SEED, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            fingerprint: String,
            publicKey: ByteArray,
            personId: String,
            selfAssertedName: String,
            verification: String,
            verifiedAt: Long,
            source: String,
            grantSeed: ByteArray = ByteArray(0),
        ): TenshuIdentity = context.create(
            type = ID,
            fields = listOf(
                fingerprint,
                publicKey,
                personId,
                selfAssertedName,
                verification,
                verifiedAt,
                source,
                grantSeed,
            ),
        )
    }

    object Field {
        const val FINGERPRINT = "$ID/fingerprint"
        const val PUBLIC_KEY = "$ID/publicKey"
        const val PERSON_ID = "$ID/personId"
        const val SELF_ASSERTED_NAME = "$ID/selfAssertedName"
        const val VERIFICATION = "$ID/verification"
        const val VERIFIED_AT = "$ID/verifiedAt"
        const val SOURCE = "$ID/source"
        const val GRANT_SEED = "$ID/grantSeed"
    }

    /** The §4.2 verification ladder, low to high. Never auto-elevated. */
    object Verification {
        const val UNVERIFIED = "UNVERIFIED"
        const val VERIFIED = "VERIFIED"
        const val ATTESTED = "ATTESTED"
    }

    /** How the identity was acquired. */
    object Source {
        const val INVITE_LINK = "INVITE_LINK"
        const val QR_SAS = "QR_SAS"
        const val REI = "REI"
    }
}

val Entity<out TenshuIdentity>.fingerprint: TString
    get() = getFieldValue(TenshuIdentity.Field.FINGERPRINT)

val Entity<out TenshuIdentity>.personId: TString
    get() = getFieldValue(TenshuIdentity.Field.PERSON_ID)

val Entity<out TenshuIdentity>.verification: TString
    get() = getFieldValue(TenshuIdentity.Field.VERIFICATION)

val Entity<out TenshuIdentity>.grantSeed: TByteArray
    get() = getFieldValue(TenshuIdentity.Field.GRANT_SEED)

interface TenshuIdentityDataIdProvider : CompositeDataIdProvider<TenshuIdentity> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/contact/tenshuIdentity/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = TenshuIdentity.Field.FINGERPRINT,
        )
    }
}
