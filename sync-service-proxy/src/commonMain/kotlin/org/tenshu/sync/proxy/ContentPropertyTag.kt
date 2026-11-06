package org.tenshu.sync.proxy

import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.common.util.hmacSha256
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager

/**
 * ⭐ **The relay-visible name of a content slot** (`content-plane-cr2-b3-wire-spec.md` §11, the
 * keyed property tag). What `PushedEdge.propertyId` carries on the wire instead of the schema
 * property id.
 *
 * ```
 * K_tag = HKDF-SHA256(sharedVolumeKey, info = "tenshu/realm/content-property-tag/v1")
 * tag   = hex(HMAC-SHA256(K_tag, holderWireName ‖ propertyId))      // 64 lowercase hex chars
 * ```
 *
 * ## Why a tag rather than the property id
 *
 * The relay uses `content_edge.property_id` only as a key component: one register per (holder,
 * slot). A plaintext id told it *which* slots each row has — file data versus thumbnail rungs
 * versus a workout stream, even the empty ones — on every write, which is the schema-vocabulary
 * profiling T-R-08 closed for `SyncBlock` payloads. A keyed tag keeps every relay query working
 * and tells it nothing but "a distinct slot".
 *
 * ## ⚠⚠ Why per holder, not `HMAC(K, propertyId)`
 *
 * A per-realm tag of the property alone is a constant per realm: the relay would still see that
 * every row with tag X also has tag Y, and could cluster rows into types from that. Folding the
 * holder's wire name in makes every row's tags unrelated to every other row's.
 *
 * ## ⚠⚠ Convergence
 *
 * Every device of the realm must name the same register with the same tag, or the relay holds two
 * edges for one slot — the refcount confusion CR-2 exists to remove. Both inputs are realm-wide:
 * `K_tag` derives from the realm's volume key exactly as EI-1's `K_entity` does, and the holder is
 * the EI-1 wire id, which is the same on every device for the same entity.
 *
 * ⚠ The concatenation needs no separator: the holder is always a fixed-width EI-1 wire id
 * ([WireEntityId.WIRE_ID_BYTES] × 2 hex chars), so the boundary is fixed.
 *
 * ⚠ A **separate** HKDF child, never `K_entity`: the same domain-separation rule EI-1 follows, so a
 * tag can never equal a wire id computed over the same bytes.
 */
object ContentPropertyTag {

    private val INFO = "tenshu/realm/content-property-tag/v1".encodeToByteArray()

    /** 32 bytes of HMAC, hex: 64 chars, inside the relay's `EDGE_ID_PATTERN` (max 256). */
    internal const val TAG_HEX_LENGTH = 64

    private val TAG_PATTERN = Regex("^[0-9a-f]{$TAG_HEX_LENGTH}$")

    /** `K_tag` for a realm. ⚠ Never stored and never sent — derived on demand like `K_entity`. */
    fun keyFor(sharedVolumeKey: ByteArray): ByteArray =
        hkdfSha256(ikm = sharedVolumeKey, salt = ByteArray(0), info = INFO, length = 32)

    /** The tag of [propertyId] on the row named [holderWireName] on the wire, under [tagKey]. */
    fun of(holderWireName: String, propertyId: String, tagKey: ByteArray): String =
        hmacSha256(tagKey, (holderWireName + propertyId).encodeToByteArray())
            .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * Whether [value] is shaped like a tag. ⚠ The send-side guard: `RestSyncRelay` refuses to put
     * anything else on the wire, so a caller that forgot to tag fails loudly instead of leaking.
     */
    fun looksLikeTag(value: String): Boolean = TAG_PATTERN.matches(value)
}

/**
 * Tags a queued block's edges for the wire, under the realm the block is **addressed to** (its
 * `ownerId`): the same realm whose key named its holder, because the emit stamps both from one
 * realm snapshot ([WireIdResolver.resolve]).
 *
 * ⚠⚠ Applied at **drain** time, by every drain ([org.tenshu.sync.proxy.impl.PushBlockImplementation],
 * [flushPendingBlocks], `SyncScheduler.drainSharedRealms`). `PendingBlockEdgeStore` keeps plaintext
 * property ids on purpose: it never leaves the device, and `FreedContentResolver` reads the property
 * back from it to decide repairability. Only the copy that crosses to the relay is tagged.
 */
class ContentPropertyTagger(
    private val syncManager: SyncManager,
    /** ⚠ A supplier for the same construction-order reason as [WireIdResolver]'s. */
    private val realmRegistry: () -> RealmRegistry? = { null },
) {

    /**
     * [edges] with each `propertyId` replaced by its tag, or `null` when [block] carries edges but
     * names no realm this device holds a key for — the push must then not go out (it stays queued),
     * since there is no honest name to send.
     *
     * ⚠ An empty list in is an empty list out, with no realm needed: most blocks carry no edges.
     */
    fun wireEdges(block: SyncBlock, edges: List<ContentEdge>): List<ContentEdge>? {
        if (edges.isEmpty()) return edges
        val holder = block.targetEntityIdHash?.get() ?: return null
        val ownerId = block.ownerId.get()
        val realm = realmFor(ownerId) ?: return null
        val volumeKey = realm.derivedKeys.sharedVolumeKey
        if (volumeKey.isEmpty()) return null
        // ⚠ Derived per push, never cached: one HKDF is cheap, a cache is shared by three push paths on
        // different threads, and one keyed on the realm alone would keep tagging under a rotated-away
        // volume key while every other device (and [WireIdResolver]'s holder names) had moved on.
        val tagKey = ContentPropertyTag.keyFor(volumeKey)
        return edges.map { it.copy(propertyId = ContentPropertyTag.of(holder, it.propertyId, tagKey)) }
    }

    private fun realmFor(ownerId: String): RealmContext? =
        realmRegistry()?.runtimeFor(ownerId)?.context
            ?: syncManager.currentRealmContext()?.takeIf { it.ownerId == ownerId }
}
