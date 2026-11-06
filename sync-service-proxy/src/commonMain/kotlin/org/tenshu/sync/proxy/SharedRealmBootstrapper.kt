package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.core.collection.list.TList

/**
 * SR-3b (`sync-multi-realm-runtime.md` §3 / `tenshu-rei.md` §2.3): bootstraps a new
 * **shared realm** — a shared folder is a realm of its own, with its own master key
 * and change stream — and self-joins the creating device to it. This is the first
 * code that actually registers a second realm into the [RealmRegistry], lighting up
 * the SR-M multi-realm pipeline (emit-route → push → pull) built in SR-M.1.
 *
 * Three steps, mirroring the personal-realm [impl.CreateRealmImplementation] but for
 * a **key-wrapped, phraseless** realm:
 *
 *  1. **Bootstrap** — generate the realm's own Ed25519 master keypair + a random
 *     32-byte symmetric master key (asset A12, `Kr`); derive the realm `ownerId`
 *     from the master public key; sign the canonical bootstrap message and
 *     `POST /sync/realm`. The device's existing [relay] client is reused —
 *     `createRealm` is unauthenticated and takes `ownerId` as a param, so no
 *     per-realm relay is needed to bootstrap.
 *  2. **Self-join** — wrap `Kr` to this device's **own A13** (derived from the
 *     recovery phrase) and `saveData` a `SharedRealmMembership(OWNER)`. The
 *     membership is `Syncable(Eager)` and keyed by `sharedRealmId` with **no**
 *     namespace prefix, so [RealmRegistry.realmForDataId] routes it to the
 *     **personal** realm — i.e. it Eager-syncs to the user's other devices, the
 *     channel by which they will auto-join (the auto-join *observer* on the
 *     receiving side is SR-3b.2).
 *  3. **Register** — add the shared [RealmContext] to the [realmRegistry] (with the
 *     realm's Ed25519 identity as `masterKeyPair` — this device is the OWNER) so the
 *     emit path routes this realm's future entities (keyed `"$ownerId::…"`) to its
 *     keys + queue. The realm's per-realm relay client is built by the registry's
 *     relay factory (SR-3b.2); where no factory is configured the realm registers
 *     with no relay and its blocks queue without draining. Registration happens
 *     **before** the membership save (step 2) so the SR-3b.2 auto-join observer sees
 *     it already registered and doesn't clobber the OWNER registration.
 *
 * **Phrase dependency (known v1 limitation):** self-join derives this device's A13
 * via [SyncCryptoManager.deriveRealmEncryptionKeyPair], which needs the recovery
 * phrase. Only phrase-holding devices can create (or, in SR-3b.2, auto-join) a
 * shared realm in v1; a paired joiner — which holds only `DerivedKeys`, never the
 * phrase — cannot. Storing A13 at setup so it survives a lock, and admitting
 * non-phrase devices via a relay-side delegation, are SR-3b.2+.
 */
class SharedRealmBootstrapper(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
    private val realmRegistry: RealmRegistry,
) {

    /**
     * Create + self-join a new shared realm. Returns its [RealmContext] (now
     * registered). Throws [SharedRealmBootstrapException] if the recovery phrase is
     * unavailable (realm locked / paired-joiner device).
     */
    suspend fun createSharedRealm(): RealmContext {
        // Self-join wraps Kr to this device's own A13 — resolve it up front (host:
        // derived from the phrase; in principle a joiner could create too, using its
        // pairing-delivered A13). Gate with a typed error if unavailable (locked).
        val myEncKeyPair = syncManager.getRealmEncryptionKeyPair()
            ?: throw SharedRealmBootstrapException(
                "realm encryption key (A13) unavailable — unlock this device to create a shared realm"
            )

        // 1. Bootstrap material. The realm's Ed25519 identity (ownerId + bootstrap
        // self-signature, and later device admission) is distinct from the A12
        // symmetric master key (Kr) that members are key-wrapped. Use
        // generateDeviceSigningKeyPair() — it is the **Ed25519** generator; the bare
        // generateKeyPair() returns an **X25519** keypair (the pairing-handshake key)
        // in the real DefaultSyncCryptoManager, which would fail the relay's Ed25519
        // verification of the bootstrap self-signature (the stub crypto hid this — its
        // sign/verify round-trip is curve-agnostic, so only a real-relay test catches it).
        val realmMasterKeyPair = cryptoManager.generateDeviceSigningKeyPair()
        val masterKey = cryptoManager.generateSharedRealmMasterKey()
        val sharedOwnerId = ownerIdForMasterPublicKey(realmMasterKeyPair.publicKey)
        val derivedKeys = cryptoManager.deriveSharedRealmKeys(masterKey)

        val deviceKeyPair = syncManager.getDeviceSigningKeyPair() // A4 — reused across realms
        val deviceId = syncManager.currentDeviceId

        // Canonical bootstrap message — the shared builder the relay verifies.
        val message = org.tenshu.sync.common.realmBootstrapMessage(
            sharedOwnerId, realmMasterKeyPair.publicKey, deviceKeyPair.publicKey, deviceId,
        )
        val signature = cryptoManager.signEd25519(realmMasterKeyPair.privateKey, message)

        // Proceed even on relay failure (mirrors CreateRealmImplementation): local
        // state is consistent and the bootstrap is idempotent at the relay (a 409
        // means a prior attempt already registered the same master key).
        relay.createRealm(
            ownerId = sharedOwnerId,
            masterPublicKey = realmMasterKeyPair.publicKey,
            firstDevicePublicKey = deviceKeyPair.publicKey,
            firstDeviceId = deviceId,
            masterSelfSignature = signature,
        )

        // 2. Register the shared realm FIRST (before saving the membership).
        // masterKeyPair = the realm's Ed25519 identity (this device is the OWNER and
        // holds it; future device admission signs delegations with it). A relay, if a
        // factory is configured, is built by the registry. Registering first means the
        // SR-3b.2 auto-join observer — which fires on the membership save below — sees
        // this realm already registered and skips, so it never clobbers the OWNER
        // registration with a joiner one (masterKeyPair = null).
        val realmContext = RealmContext(
            ownerId = sharedOwnerId,
            deviceId = deviceId,
            derivedKeys = derivedKeys,
            deviceSigningKeyPair = deviceKeyPair,
            masterKeyPair = realmMasterKeyPair,
        )
        realmRegistry.register(realmContext)

        // 2b. Persist the realm master keypair (A1) durably (GR-6). Before this, the
        // master private key lived only in the in-memory RealmContext above, so the
        // owner could grant/remove members only in the creating session. As a
        // Syncable(Eager) row in the owner's own personal realm it survives a restart
        // and propagates to the owner's other devices, so any of them can grant/remove
        // (House Rule #1 holds — it's wrapped under the personal realm key like all
        // personal state; the relay only ever sees the realm's ciphertext).
        executionContext.saveData(
            OwnedRealmMaster.create(
                context = executionContext.context,
                sharedRealmId = sharedOwnerId,
                masterPublicKey = realmMasterKeyPair.publicKey,
                masterPrivateKey = realmMasterKeyPair.privateKey,
            ),
        )

        // 3. Self-join: wrap Kr to this device's own A13 (resolved above) and persist
        // the membership. House Rule #1: only the wrapped form of Kr ever touches
        // storage/sync. The membership is Syncable(Eager) on the personal realm ⇒ it
        // propagates to the user's other devices, where the auto-join observer (using
        // their pairing-delivered A13) registers the realm there.
        val membership = SharedRealmMembership.create(
            context = executionContext.context,
            sharedRealmId = sharedOwnerId,
            wrappedMasterKey = cryptoManager.wrapKeyForIdentity(masterKey, myEncKeyPair.publicKey),
            role = SharedRealmMembership.Role.OWNER,
        )
        executionContext.saveData(membership)

        // 4. Admit the user's other devices to the shared realm at the relay (SR-3b.4c),
        // so their JWTs verify on the shared stream once they auto-join. The creating
        // device is already registered (the `createRealm` first-device step above). Each
        // other device's A4 comes from its `Device` entity in the personal realm's
        // `IdentityService.Relations.DEVICES`. Best-effort: a failed admission (offline,
        // device not yet synced) is logged and retried on a future bootstrap/trigger —
        // it must not fail realm creation.
        admitKnownDevices(realmContext)

        return realmContext
    }

    private suspend fun admitKnownDevices(realm: RealmContext) {
        val devicesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES,
            ),
            isEntity = true,
            elementSchemaId = Device.ID,
        )
        val devices = executionContext.database.getArrayData(devicesRef)?.toList() ?: return
        val admitter = SharedRealmDeviceAdmitter(cryptoManager, relay)
        for (entry in devices) {
            val entity = entry as? Entity<*> ?: continue
            val deviceId = runCatching {
                entity.getFieldValue<org.tenshu.core.TString>(Device.Fields.DEVICE_ID).get()
            }.getOrNull() ?: continue
            if (deviceId == realm.deviceId) continue // the creating device is already registered
            val publicKey = runCatching {
                entity.getFieldValue<org.tenshu.core.TByteArray>(Device.Fields.PUBLIC_KEY).get()
            }.getOrNull() ?: continue
            // ⚠⚠ B1: the shared-realm master signs a delegation over this key, and any personal-realm
            // device can write the row it comes from — accept only a real Ed25519 key.
            if (publicKey.size != org.tenshu.sync.common.ED25519_PUBLIC_KEY_BYTES) {
                println("SharedRealmBootstrapper -> skipping $deviceId: its public key is not an Ed25519 key")
                continue
            }
            // Workstream B: the relay registers only a key's own id; a row pairing them otherwise is skipped.
            if (!org.tenshu.sync.common.deviceIdMatchesKey(deviceId, publicKey)) {
                println("SharedRealmBootstrapper -> skipping $deviceId: its id is not derived from its public key")
                continue
            }
            try {
                admitter.admit(realm, deviceId, publicKey)
            } catch (e: Exception) {
                println("SharedRealmBootstrapper -> failed to admit device $deviceId to ${realm.ownerId}: ${e.message}")
            }
        }
    }
}

/** Thrown by [SharedRealmBootstrapper.createSharedRealm] when bootstrap can't proceed. */
class SharedRealmBootstrapException(message: String) : Exception(message)
