package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.MemberAuthorityKey
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.membershipCapabilityFromStored

/**
 * GR-3 / D9-b cross-device self-bind: the sibling of [SharedRealmJoinObserver] for
 * **cross-user grants**. When a member accepts a grant on device 1, their
 * [MemberAuthorityKey] (A26, with the master-signed capability) + their
 * [SharedRealmMembership] (Kr) sync to device 2 via their personal realm. Device 2
 * must then **self-bind**: mint its own fresh per-realm device key, register the
 * realm with that key (D2 unlinkability — *not* the personal A4 the plain
 * [SharedRealmJoinObserver] would use), sign a [MemberDeviceBinding] with the synced
 * A26 private key, and `POST /sync/realm-member/admit`. Without this, device 2's JWT
 * for the shared `ownerId` is rejected and it cannot sync the shared realm at all.
 *
 * Acts **only on [WriteOrigin.SYNC_INGEST]** — the accepting device already bound
 * itself in `SharedRealmGrantManager.accept` (a `USER`-origin write), so it must not
 * re-bind. Fires on either the authority key or the membership row arriving and
 * proceeds once **both** are present (arrival order is non-deterministic). Registers
 * the realm with the per-realm key (overwriting any prior personal-key registration
 * the plain join observer made — `RealmRegistry.register` is idempotent-overwrite),
 * then admits this device. A once-per-realm in-memory guard avoids re-binding within
 * a session.
 */
@OptIn(ExperimentalStdlibApi::class)
class MemberSelfBindObserver(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val realmRegistry: RealmRegistry,
    private val grantedRelayFactory: (RealmContext) -> SyncRelay?,
    private val nowMs: () -> Long,
    /** Scope the suspend relay admission launches on (the observer callback is non-suspend). */
    private val coroutineScope: CoroutineScope,
) : DataObserver {

    private val boundRealms = mutableSetOf<String>()

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (origin != WriteOrigin.SYNC_INGEST) return
        val realmId = data.realmIdOrNull() ?: return
        selfBind(realmId)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

    private fun selfBind(realmId: String) {
        if (realmId in boundRealms) return

        val authority = readAuthority(realmId) ?: return
        val membership = readMembership(realmId) ?: return
        val myEnc = syncManager.getRealmEncryptionKeyPair() ?: return

        val kr = try {
            cryptoManager.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), myEnc.privateKey)
        } catch (_: Exception) {
            return // wrapped to a different member — not for us
        }
        boundRealms.add(realmId)

        val authorityPub = authority.publicKey.get()
        val capability = membershipCapabilityFromStored(realmId, authorityPub, authority.capability.get())

        // Register with a fresh per-realm device key (overwriting the personal-key
        // registration the plain join observer may have made for this realm).
        val deviceKey = cryptoManager.generateDeviceSigningKeyPair()
        val deviceId = deviceIdForPublicKey(deviceKey.publicKey)
        val realmContext = RealmContext(
            ownerId = realmId,
            deviceId = deviceId,
            derivedKeys = cryptoManager.deriveSharedRealmKeys(kr),
            deviceSigningKeyPair = deviceKey,
            masterKeyPair = null,
            retiredKeys = buildRealmKeyring(executionContext, cryptoManager, realmId, myEnc.privateKey),
        )
        val realmRelay = grantedRelayFactory(realmContext)
        realmRegistry.register(realmContext, realmRelay)

        val boundAt = nowMs()
        val binding = MemberDeviceBinding(
            realmId = realmId,
            deviceId = deviceId,
            perRealmDevicePublicKey = deviceKey.publicKey,
            boundAt = boundAt,
            signature = cryptoManager.signEd25519(
                authority.privateKey.get(),
                MemberDeviceBinding.message(realmId, deviceId, deviceKey.publicKey, boundAt),
            ),
        )
        // Admit over the per-realm relay (admitMember is unauthenticated — the capability
        // chain is the credential). Suspend, so launch it; a failure (offline) releases
        // the guard so the next synced save retries.
        coroutineScope.launch {
            try {
                realmRelay?.admitMember(realmId, capability, binding)
            } catch (_: Exception) {
                boundRealms.remove(realmId)
            }
        }
    }

    private fun Data.realmIdOrNull(): String? = when (this) {
        is MemberAuthorityKey -> sharedRealmId.get()
        is SharedRealmMembership -> sharedRealmId.get()
        is Entity<*> -> when (type.get()) {
            MemberAuthorityKey.ID -> getFieldValue<TString>(MemberAuthorityKey.Fields.SHARED_REALM_ID).get()
            SharedRealmMembership.ID -> getFieldValue<TString>(SharedRealmMembership.Fields.SHARED_REALM_ID).get()
            else -> null
        }
        else -> null
    }

    private fun readAuthority(realmId: String): MemberAuthorityKey? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, MemberAuthorityKey.ID, realmId),
        ) as? MemberAuthorityKey

    private fun readMembership(realmId: String): SharedRealmMembership? =
        executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, SharedRealmMembership.ID, realmId),
        ) as? SharedRealmMembership
}
