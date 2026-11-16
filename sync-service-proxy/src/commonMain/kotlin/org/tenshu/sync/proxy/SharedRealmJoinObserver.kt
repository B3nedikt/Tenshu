package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.TResult
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager

/**
 * SR-3b.2 (`sync-multi-realm-runtime.md` §3): the **auto-join** mechanism. Watches
 * for `SharedRealmMembership` rows landing in the local DB — whether saved by this
 * device's own self-join ([SharedRealmBootstrapper]) or synced in from another of
 * the user's devices (the membership is `Syncable(Eager)` on the personal realm) —
 * and registers the corresponding shared [RealmContext] into the [RealmRegistry] so
 * this device starts emitting/pushing/pulling that realm.
 *
 * On each membership save it: unwraps the shared realm's master key (A12, `Kr`) with
 * **this device's own A13** (derived from the recovery phrase), derives the realm's
 * `DerivedKeys`, and registers the realm under a **per-realm device key** (D10, threat model
 * T-GR-05 — never the personal A4). When this user owns the realm, its synced `OwnedRealmMaster`
 * makes the device an OWNER and it admits its own per-realm key at the relay; until the master
 * arrives it is registered locally only.
 *
 * Distinct from [SyncObserver]: that one *skips* `WriteOrigin.SYNC_INGEST` (to avoid
 * echoing received blocks back out); this one **must** react to ingested saves —
 * that is exactly how a second device learns of, and joins, a shared realm the first
 * device created. It is idempotent and cheap: an already-registered realm is skipped
 * (so the creating device's own [SharedRealmBootstrapper] registration — which
 * carries the realm's Ed25519 master keypair — is never clobbered by this joiner
 * registration).
 *
 * **A13 source (SR-3b.4):** this device's A13 comes from [SyncManager.getRealmEncryptionKeyPair]
 * — derived from the recovery phrase on a host, or delivered via the pairing key-blob
 * on a paired joiner (option B). So a normal paired device **does** auto-join. It still
 * silently skips when A13 is unavailable (a locked host, or a pre-SR-3b.4 joiner whose
 * payload carries no A13). A membership wrapped to a *different* member's A13 fails the
 * AEAD/unwrap and is likewise skipped (not for us).
 *
 * Note: actually *syncing* the joined realm's stream additionally needs relay-side
 * device admission (SR-3b.4 part 2) — this observer only registers it locally.
 */
class SharedRealmJoinObserver(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val realmRegistry: RealmRegistry,
    /** Where an owner device's self-admission runs (the observer callback is non-suspend). Null = none. */
    private val coroutineScope: CoroutineScope? = null,
) : DataObserver {

    // Realms whose self-admission is done or in flight this session; a failure releases it.
    private val admitting = mutableSetOf<String>()

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val realmId = when (data) {
            is SharedRealmMembership -> data.sharedRealmId.get()
            // ⭐ D10: the realm master can arrive after the membership; that is when an owner
            // device can first admit its per-realm key.
            is OwnedRealmMaster -> data.sharedRealmId.get()
            else -> return
        }
        join(realmId)
    }

    private fun join(realmId: String) {
        val existing = realmRegistry.runtimeFor(realmId)?.context
        val owned = readOwnedRealmMaster(realmId)
        // Don't clobber an existing registration — the creating device's own OWNER registration, or
        // a member's self-bind. ⭐ One exception: a registration made before this device held the
        // realm master is upgraded once the master arrives, keeping its per-realm key.
        // ⚠ An owner registration whose self-admission failed must still be retried (the guard below releases on
        // failure for exactly this): re-run it, then stop — the registration itself is fine.
        // ⚠⚠ A newer realm key synced in (another of this user's devices rotated it after removing a member):
        // re-register on it now, keeping the per-realm device key. Left on the old runtime, this device would
        // go on SEALING new content under the retired key for the rest of the session — the very key the
        // removed member still holds, so under relay collusion they read it (T-GR-02).
        if (existing != null && reKeyIfTheMembershipMoved(realmId, existing, owned)) return
        if (existing != null && existing.masterKeyPair != null) {
            admitSelf(existing)
            return
        }
        if (existing != null && owned == null) return

        val membership = readMembership(realmId) ?: return

        // Resolve this device's A13 (SR-3b.4): a host derives it from the phrase, a
        // joiner uses the A13 delivered via the pairing key-blob. Null only when
        // neither is available (a locked host, or a pre-SR-3b.4 joiner whose payload
        // carries no A13) — then this device can't unwrap Kr and simply doesn't join.
        val myEncKeyPair = syncManager.getRealmEncryptionKeyPair() ?: return

        // Unwrap Kr with our A13. A membership wrapped to a different member fails
        // here (wrong recipient → AEAD tag failure) — it isn't for us, so skip.
        val masterKey = try {
            cryptoManager.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), myEncKeyPair.privateKey)
        } catch (_: Exception) {
            return
        }

        // ⚠⚠ D10 (threat model T-GR-05): a per-realm key and realm-local id, never the personal A4.
        // With the synced realm master (this user owns the realm) the device is an OWNER and admits
        // itself; without it, it registers locally and waits for the master (or for a member's
        // self-bind, which replaces this registration).
        val realmContext = OwnerRealmDevice.register(
            cryptoManager = cryptoManager,
            realmRegistry = realmRegistry,
            realmId = realmId,
            derivedKeys = cryptoManager.deriveSharedRealmKeys(masterKey),
            masterKeyPair = owned?.let { SyncCryptoManager.KeyPair(it.masterPublicKey.get(), it.masterPrivateKey.get()) },
            // GR-7: any retired keys already synced to this device (a rotation that
            // happened before this device registered the realm) so it reads old content.
            retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEncKeyPair.privateKey),
            personalPublicKey = syncManager.getDeviceSigningKeyPair().publicKey,
            reuse = existing,
        )
        if (realmContext.masterKeyPair != null) admitSelf(realmContext)
    }

    /**
     * True when the stored membership carries a different realm key than [existing] runs on, and the realm has
     * been re-registered on it — keyring rebuilt, per-realm device key and master (if any) kept. A rotation on
     * THIS device already re-registered itself, so its own save finds the keys equal and changes nothing.
     */
    private fun reKeyIfTheMembershipMoved(realmId: String, existing: RealmContext, owned: OwnedRealmMaster?): Boolean {
        val membership = readMembership(realmId) ?: return false
        val myEncKeyPair = syncManager.getRealmEncryptionKeyPair() ?: return false
        val masterKey = try {
            cryptoManager.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), myEncKeyPair.privateKey)
        } catch (_: Exception) {
            return false
        }
        val derivedKeys = cryptoManager.deriveSharedRealmKeys(masterKey)
        if (derivedKeys == existing.derivedKeys) return false
        println("SharedRealmJoinObserver -> $realmId: a newer realm key synced in — re-registering on it")
        OwnerRealmDevice.register(
            cryptoManager = cryptoManager,
            realmRegistry = realmRegistry,
            realmId = realmId,
            derivedKeys = derivedKeys,
            masterKeyPair = existing.masterKeyPair
                ?: owned?.let { SyncCryptoManager.KeyPair(it.masterPublicKey.get(), it.masterPrivateKey.get()) },
            retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEncKeyPair.privateKey),
            personalPublicKey = syncManager.getDeviceSigningKeyPair().publicKey,
            reuse = existing,
        )
        return true
    }

    private fun admitSelf(realm: RealmContext) {
        val scope = coroutineScope ?: return
        val relay = realmRegistry.runtimeFor(realm.ownerId)?.relay ?: return
        if (!admitting.add(realm.ownerId)) return
        scope.launch {
            val ok = try {
                OwnerRealmDevice.admitSelf(
                    cryptoManager, realm, relay,
                    admittedAtHlc = realmRegistry.admissionStampFor(realm.ownerId, executionContext.context),
                ) is TResult.Success<*>
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // A failure (offline) releases the guard, so the next membership or master save retries.
            if (!ok) admitting.remove(realm.ownerId)
            // MR-1 §3.5: an owner device registered this realm for the session — re-send its membership
            // records (relay body GC; members granted after a rotation hold only the current key).
            // ⚠ Once per session per realm, by the `admitting` guard above.
            if (ok) runCatching { MembershipRecords(executionContext, cryptoManager).reEmit(realm.ownerId) }
                .onFailure { println("SharedRealmJoinObserver -> membership-record re-emit failed: $it") }
        }
    }

    private fun readMembership(realmId: String): SharedRealmMembership? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, SharedRealmMembership.ID, realmId),
        ) as? SharedRealmMembership

    private fun readOwnedRealmMaster(realmId: String): OwnedRealmMaster? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, OwnedRealmMaster.ID, realmId),
        ) as? OwnedRealmMaster

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Leaving a shared realm (membership tombstone → unregister) is a later slice;
        // membership tombstones aren't emitted yet (SR-3c+).
    }
}
