package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
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
 * `DerivedKeys`, and registers a joiner [RealmContext] (`masterKeyPair = null` — a
 * joining device is not the realm's Ed25519-identity holder and cannot admit further
 * devices). The per-realm relay + inbound processor are built by the registry's
 * factories.
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
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val membership = data as? SharedRealmMembership ?: return
        val sharedRealmId = membership.sharedRealmId.get()

        // Don't clobber an existing registration — notably the creating device's own
        // owner registration (masterKeyPair = the realm Ed25519 identity), which this
        // joiner path would otherwise overwrite with masterKeyPair = null.
        if (realmRegistry.isRegistered(sharedRealmId)) return

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

        val identity = syncManager.currentDeviceIdentity()
        val realmContext = RealmContext(
            ownerId = sharedRealmId,
            deviceId = identity.deviceId,
            derivedKeys = cryptoManager.deriveSharedRealmKeys(masterKey),
            deviceSigningKeyPair = identity.signingKeyPair,
            // Joiner: this device holds Kr (read/write the realm) but not the realm's
            // Ed25519 identity — device admission stays with the OWNER.
            masterKeyPair = null,
            // GR-7: any retired keys already synced to this device (a rotation that
            // happened before this device registered the realm) so it reads old content.
            retiredKeys = buildRealmKeyring(
                executionContext, cryptoManager, sharedRealmId, myEncKeyPair.privateKey,
            ),
        )
        realmRegistry.register(realmContext)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Leaving a shared realm (membership tombstone → unregister) is a later slice;
        // membership tombstones aren't emitted yet (SR-3c+).
    }
}
