package org.tenshu.sync.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager

/**
 * Resolves an entity's **relay-visible** name (`sync-entity-identity.md` EI-1), which is per realm
 * because `K_entity` is derived from that realm's volume key.
 *
 * ## ⚠⚠ Why this is a seam rather than a function
 *
 * `WireEntityId.of` needs a key, and the three callers that need a wire id sit at different depths:
 * `SyncObserver` has realm keys in hand, `SyncProcessor` has them via the same context, and
 * `TombstoneRegistry` has **none** — it takes an `ExecutionContext` and an HLC ticker and nothing
 * else. Threading a key into it would put realm crypto in a class whose job is an index; threading
 * *this* in gives it the one answer it needs.
 *
 * ## ⚠⚠ The reverse index is wire-keyed, and that is not obvious
 *
 * `TombstoneRegistry.idHashToDataId` looks local — it maps a string to a `DataId` and never leaves
 * the device. But its *consumers* (`SyncProcessor.mergeData`, `FileContentManifestApplier`) resolve
 * hashes that **arrived from the relay**, so the index has to be keyed by the same name the wire
 * uses. Keying it locally would make every inbound tombstone miss, silently.
 *
 * ⚠ There is no local-hash fallback. A device with no realm context cannot name an entity for the
 * wire at all: [of] throws and [ofOrNull] answers null, and the index records nothing rather than
 * recording a name no peer can match.
 */
class WireIdResolver(
    private val syncManager: SyncManager,
    /**
     * ⚠⚠ A supplier, not the registry, because of construction order: `SyncProcessor` is built
     * before `RealmRegistry` exists, and a resolver that captured `null` there would be
     * realm-BLIND — it would name a shared-realm entity under the personal key while the emit side
     * named it under the shared one. Two names for one entity, and the only symptom was a
     * cross-device read that never completed.
     */
    private val realmRegistry: () -> RealmRegistry? = { null },
) {
    /**
     * `K_entity` per realm — one HKDF per realm, not per call.
     *
     * ⚠⚠ Keyed by the realm's **id**, never by a hash of its key material. The first version keyed
     * on `volumeKey.contentHashCode()`: 32 bits, over a secret, and a collision would have handed
     * one realm another's `K_entity` — every name silently wrong, for both of them.
     */
    private val keyCache = mutableMapOf<String, ByteArray>()

    /**
     * The relay-visible name of [dataId].
     *
     * ⚠⚠ **Throws when there is no realm key**, because a wire name has no honest local fallback:
     * naming a row locally before sync is enabled leaves it named that way for ever, and unlike the
     * `ResolvedDataId` index the `SyncMetadata` / `LocalWriteMetadata` rows never heal. The
     * tombstone guard then cannot see that entity's local write, and a later tombstone deletes
     * something the user had edited.
     *
     * ⚠ Use this only where a realm is genuinely required — the emit paths. **Indexing must use
     * [ofOrNull]**: `onDataSaved` fires on every save, including before sync is enabled, and an
     * earlier version of this threw there and turned ordinary local uploads into errors.
     */
    fun of(dataId: DataId): String = checkNotNull(ofOrNull(dataId)) {
        "No realm context for ${dataId.type.get()} — a wire id cannot be named without one, " +
            "and naming it locally would diverge silently (sync-entity-identity.md EI-1)"
    }

    /**
     * The name, or `null` when this device has no realm yet.
     *
     * ⚠⚠ For the **index**, not the wire. A save can happen before sync is enabled — `onDataSaved`
     * fires on every save — and there is no honest name for it yet. The first version threw, and it
     * turned ordinary local uploads into errors; the version before that named them *locally*,
     * which was worse: a wrongly-named metadata row looks like protection and is not, and unlike
     * the `ResolvedDataId` index it never heals. Recording nothing is the only option that cannot
     * lie — the row is re-recorded when it is next saved with keys present.
     */
    fun ofOrNull(dataId: DataId): String? = resolve(dataId)?.wireName

    /**
     * The name **and the realm it was named under**, from one read of the realm.
     *
     * ⚠⚠ Emitters need both, and taking them separately is a bug with no symptom at the seam. The
     * realm can change *between* two reads — not only a `LockRealm` clearing it, but an owner
     * adoption seating a different one — so a caller that resolves the name here and then asks
     * `currentRealmContext()` for the `ownerId` can stamp a block from realm B with a name derived
     * under realm A's key. Both reads succeed, nothing errors, and the tombstone is unmatchable in
     * the realm it was addressed to while the local row is already gone.
     */
    fun resolve(dataId: DataId): Resolved? {
        val realm = realmRegistry()?.realmForDataId(dataId) ?: syncManager.currentRealmContext()
        if (realm == null) return null
        val volumeKey = realm.derivedKeys.sharedVolumeKey
        if (volumeKey.isEmpty()) return null
        val entityKey = keyCache.getOrPut(realm.ownerId) { WireEntityId.keyFor(volumeKey) }
        return Resolved(realm = realm, wireName = WireEntityId.of(dataId, entityKey))
    }

    /** One entity's wire name together with the realm snapshot it was derived from. */
    data class Resolved(val realm: RealmContext, val wireName: String)
}
