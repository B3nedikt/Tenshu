package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.deviceIdForPublicKey

/**
 * An **owner** device's identity inside one of its shared realms (`shared-realm-read-only-members.md`
 * D10; threat model T-GR-05): a fresh per-realm Ed25519 key and the realm-local device id derived
 * from it — never the personal A4 and personal device id.
 *
 * Members have always joined this way (GR D2, [MemberSelfBindObserver]). Owners used to register
 * with their personal A4, so every id inside a share (`peerTips`, the JWT, the block HLC) was the
 * owner's personal device id, linkable to their personal realm's pushes.
 *
 * ⚠ Like a member's, the key lives in memory only: shared realms are registered per session today,
 * and a re-registration mints a new key and admits it again. Persisting per-realm keys is a separate
 * change, for owners and members alike.
 */
internal object OwnerRealmDevice {

    /**
     * Register [realmId] on this device under a per-realm key, and return its context.
     *
     * [reuse] keeps an existing registration's key (a re-key or a late-arriving master), so the
     * device keeps its realm-local id — ⚠ unless that key is [personalPublicKey], which is exactly
     * the identity this exists to keep out of the share.
     *
     * The realm's relay comes from the registry's factory, which signs as the registered device.
     */
    fun register(
        cryptoManager: SyncCryptoManager,
        realmRegistry: RealmRegistry,
        realmId: String,
        derivedKeys: SyncCryptoManager.DerivedKeys,
        masterKeyPair: SyncCryptoManager.KeyPair?,
        retiredKeys: List<org.tenshu.sync.common.RetiredRealmKeys>,
        personalPublicKey: ByteArray,
        reuse: RealmContext? = null,
    ): RealmContext {
        val key = reuse?.deviceSigningKeyPair
            ?.takeUnless { it.publicKey.contentEquals(personalPublicKey) }
            ?: cryptoManager.generateDeviceSigningKeyPair()
        val context = RealmContext(
            ownerId = realmId,
            deviceId = deviceIdForPublicKey(key.publicKey),
            derivedKeys = derivedKeys,
            deviceSigningKeyPair = key,
            masterKeyPair = masterKeyPair,
            retiredKeys = retiredKeys,
        )
        realmRegistry.register(context)
        return context
    }

    /**
     * Admit [realm]'s own per-realm key at the relay (`POST /sync/admit-device`), signed by the
     * realm master — which every owner device holds (`OwnedRealmMaster`, synced). The route is
     * unauthenticated, so any relay client for the same base URL can carry it.
     */
    suspend fun admitSelf(
        cryptoManager: SyncCryptoManager,
        realm: RealmContext,
        relay: SyncRelay,
        /** §5b R3: from `RealmRegistry.admissionStampFor` — the realm's own clock. */
        admittedAtHlc: String?,
    ): TResult<Data?> =
        SharedRealmDeviceAdmitter(cryptoManager, relay)
            .admit(realm, realm.deviceId, realm.deviceSigningKeyPair.publicKey, admittedAtHlc = admittedAtHlc)
}
