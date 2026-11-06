package org.tenshu.sync.proxy

import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.common.util.hmacSha256
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.dataIdIdentityString

/**
 * ⭐ **The relay-visible name of an entity** (`sync-entity-identity.md` EI-1).
 *
 * ```
 * K_entity = HKDF-SHA256(sharedVolumeKey, info = "tenshu/realm/entity-id/v1")
 * wireId   = HMAC-SHA256(K_entity, "<type>_<identity>")[0..16)   // 128 bits, hex
 * ```
 *
 * ## Why it replaced a `String.hashCode`
 *
 * The previous wire key was `"${type}_${key}".hashCode()` — 32-bit, unsalted, and derived from
 * **plaintext**. Two things were wrong with that for something the relay stores:
 *
 * - ⚠⚠ **It was a confirmation oracle.** The relay could compute it. For the path-keyed and
 *   preference-keyed types a *guessed* path hashed to a value it could test against any owner's
 *   blocks and tombstones. The content plane already obeys the rule the ordered plane broke: refs
 *   derive from randomness, never from plaintext (ADR 0017 D3).
 * - ⚠⚠ **It collided at real sizes.** `p ≈ n²/2³³` — about **2.3 %** of realms at 14 208 rows and
 *   **29 %** at 50 000, against a 107 k-row replica in this repo.
 *
 * ## ⭐⭐ Why it stays a *hash* rather than becoming a random id
 *
 * Convergence without coordination. Two devices that create `/Photos` offline derive the **same**
 * wire id because they share `K_entity`, so their blocks arrive as `existing` + `incoming` for one
 * entity — which is `SemanticMerger.merge`'s ordinary case, and eleven mergers across six services
 * already handle it (ADR 0024). `FolderMerger` unions the folder's files, so a file added on each
 * device survives.
 *
 * Under a random id nothing merges: there is no conflict, just two entities holding half the state
 * each, indefinitely, with no code path that would notice. **A conflict gets resolved; a silent
 * duplicate only accumulates** — and an identity-merge protocol would have to match entities *by
 * name* anyway, arriving back here with more states to test.
 *
 * ## ⚠⚠ This is NOT the local hash
 *
 * `DataId.getDataIdHash()` remains the **local** identity — the object store's row key, `StreamMerge`
 * dedup, relation maintenance. It needs no key and must not have one: `:core` holds no realm key and
 * should not. The two functions are deliberately separate, and each caller belongs to exactly one of
 * them: **on the wire ⇒ keyed; in the store ⇒ local.**
 *
 * ⚠ Widening the local hash is EI-1b and is a *different* change with a different cost — it moves
 * every stored row's primary key, so it forces a storage-format bump.
 */
object WireEntityId {

    private val INFO = "tenshu/realm/entity-id/v1".encodeToByteArray()

    /** 128 bits: collision-free at any realm size this project will see. */
    internal const val WIRE_ID_BYTES = 16

    /**
     * `K_entity` for a realm, derived from its volume key.
     *
     * ⚠ Never stored and never sent — derived on demand exactly as the volume key's other children
     * are. A device with the volume key has it; a revoked device keeps only the ids it already
     * learned, which reveal nothing it did not already hold.
     */
    fun keyFor(sharedVolumeKey: ByteArray): ByteArray =
        hkdfSha256(ikm = sharedVolumeKey, salt = ByteArray(0), info = INFO, length = 32)

    /**
     * The wire id for [dataId] under [entityKey].
     *
     * ⚠ The input is the same identity string the local hash uses, so the two agree about *which*
     * entity they name and disagree only about how they name it — which is what lets a caller be
     * moved from one to the other without changing its meaning.
     */
    fun of(dataId: DataId, entityKey: ByteArray): String =
        hmacSha256(entityKey, dataId.dataIdIdentityString().encodeToByteArray())
            .copyOf(WIRE_ID_BYTES)
            .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
