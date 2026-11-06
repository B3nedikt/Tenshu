package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.IdentityService
import org.tenshu.identity.RetainedRealmKey
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference
import org.tenshu.sync.common.RetiredRealmKeys

/**
 * Builds the **retained keyring** for shared realm [realmId] (GR-7,
 * `shared-realm-membership-grant.md` §13.4): the `DerivedKeys` of every *retired*
 * `Kr` this member still holds, to populate `RealmContext.retiredKeys` at
 * every shared-realm registration site so the decrypt path can trial-decrypt content
 * written before a rotation.
 *
 * Reads the [IdentityService.Relations.RETAINED_REALM_KEYS] flat array (maintained by
 * the generic `RelationMaintainer`), keeps the rows for [realmId], unwraps each
 * row's `wrappedMasterKey` with this member's A13 private key ([myEncPrivateKey]), and
 * derives the realm keys. A row that fails to unwrap (wrapped to a different A13 — not
 * for us) is skipped, never fatal. Returns `emptyList()` before any rotation (the
 * relation array doesn't exist yet) and for the personal realm (which never rotates,
 * so no retired-key rows are ever written for it).
 *
 * Newest-retired first (the most likely match for recently-written-then-rotated
 * content), so the common single-rotation case settles on the first candidate.
 */
internal fun buildRealmKeyring(
    executionContext: ExecutionContext,
    cryptoManager: SyncCryptoManager,
    realmId: String,
    myEncPrivateKey: ByteArray,
): List<RetiredRealmKeys> {
    // Derived from the relation's declared spec (§9.2 / D4).
    val ref = executionContext.relationReference(IdentityService.Relations.RETAINED_REALM_KEYS)
    return (executionContext.database.getArrayData(ref)?.toList() ?: emptyList())
        .mapNotNull { it as? RetainedRealmKey }
        .filter { it.sharedRealmId.get() == realmId }
        .sortedByDescending { it.retiredAt.get() }
        .mapNotNull { row ->
            val kr = try {
                cryptoManager.unwrapKeyForIdentity(row.wrappedMasterKey.get(), myEncPrivateKey)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            // ⭐ sync-envelope-authentication.md §3.5: the retirement time travels with the key.
            RetiredRealmKeys(cryptoManager.deriveSharedRealmKeys(kr), row.retiredAt.get())
        }
}
