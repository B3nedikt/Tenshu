package org.tenshu.sync.proxy

import org.tenshu.contact.ContactService
import org.tenshu.contact.TenshuIdentity
import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference

/**
 * A grantable contact resolved from the local keyring — the cross-service read
 * the membership-grant ops need (`shared-realm-membership-grant.md` §9.1, D8).
 *
 * - [fingerprint] / [a13PublicKey] are the contact's `TenshuIdentity` key + A13
 *   encryption public key (the seal target + a grant-address input).
 * - [grantSeed] is the persisted `HKDF(K_link)` (`deriveGrantSeed`) that makes
 *   the grant mailbox address secret-derivable post-handshake. **Empty** for a
 *   contact handshaked before GR-3 — such a contact is not grantable (the op
 *   fails loudly; the UI gates the action on a non-empty seed).
 */
class GrantContact(
    val personId: String,
    val fingerprint: String,
    val a13PublicKey: ByteArray,
    val grantSeed: ByteArray,
) {
    val isGrantable: Boolean get() = grantSeed.isNotEmpty()
}

/**
 * Reads the local `ContactService` keyring (the
 * [ContactService.Relations.TENSHU_IDENTITIES] relation the
 * the generic `RelationMaintainer` derives) without `:contact-service-proxy` — the
 * membership-grant ops live in `:sync-service-proxy` (they need the realm master
 * key) but resolve the grantee/grantor identity here. The relation stores
 * `TenshuIdentity` **composites** (not entities), so reads materialise the typed
 * DTO and pull the optional [TenshuIdentity.Field.GRANT_SEED] via
 * `getOptionalField` (it throws through the mandatory accessor).
 */
class GrantContactDirectory(
    private val executionContext: ExecutionContext,
) {

    // Derived from the relation's declared spec (§9.2 / D4). Lazy because this
    // class is constructed during proxy assembly, before the module graph the
    // spec lives in is necessarily complete.
    private val identitiesRef by lazy {
        executionContext.relationReference(ContactService.Relations.TENSHU_IDENTITIES)
    }

    /** Every keyring identity, mapped to a [GrantContact] (seed may be empty). */
    fun all(): List<GrantContact> =
        (executionContext.database.getArrayData(identitiesRef)?.toList() ?: emptyList())
            .mapNotNull { it.toGrantContact() }

    /** The contact for [personId] that is grantable (has a seed), or null. */
    fun grantableByPersonId(personId: String): GrantContact? =
        all().firstOrNull { it.personId == personId && it.isGrantable }

    /** The contact whose `TenshuIdentity` key is [fingerprint], or null. */
    fun byFingerprint(fingerprint: String): GrantContact? =
        all().firstOrNull { it.fingerprint == fingerprint }

    private fun Any?.toGrantContact(): GrantContact? {
        val identity = this as? TenshuIdentity ?: return null
        val seed = (identity as? Composite)
            ?.getOptionalField<TByteArray>(TenshuIdentity.Field.GRANT_SEED)?.get()
            ?: ByteArray(0)
        return GrantContact(
            personId = identity.personId.get(),
            fingerprint = identity.fingerprint.get(),
            a13PublicKey = identity.publicKey.get(),
            grantSeed = seed,
        )
    }
}
