package org.tenshu.sync.common

import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.common.util.hmacSha256
import org.tenshu.core.common.util.sha256
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import kotlin.concurrent.Volatile

/**
 * ⭐ Envelope authentication for the two things a relay stores and serves back
 * (`docs/proposals/sync-envelope-authentication.md`):
 *
 * - a [DeleteBlock] carries `authTag = HMAC-SHA256(K_tomb, tombstoneCanonical)` (§3.1);
 * - a [SyncBlock]'s payload is sealed with `SHA-256(syncBlockCanonical)` as associated data (§3.2),
 *   so `ownerId`, `blockId`, `hlc` and `targetEntityIdHash` cannot be re-stamped.
 *
 * ## ⚠⚠ The canonical bytes are a wire contract
 *
 * Every emitter and every verifier on every platform must produce the same bytes, which is why they
 * live here in `:sync-service-common` and are pinned by fixed vectors (`EnvelopeAuthTest`). They are
 * built field by field, length-prefixed, and **never** from the composite serialisation, whose shape
 * may evolve (the `HlcTipCanonicalMessage` precedent). Changing an encoding means a new `/vN` label
 * and a relay-protocol cutover.
 *
 * ## ⚠ Absent and empty are the same value
 *
 * The binary codec round-trips an absent optional `TString` / `TByteArray` as empty
 * (`DeleteBlockSerializationTest`), so every optional is encoded as absent when null **or** empty —
 * otherwise a tag computed before the wire would not match the one recomputed after it.
 */
object EnvelopeAuth {

    /** HMAC-SHA256 output length. */
    const val TAG_SIZE: Int = 32

    internal const val BLOCK_LABEL = "tenshu/sync/block-envelope/v1"
    internal const val TOMBSTONE_LABEL = "tenshu/sync/tombstone/v1"
    internal const val TOMBSTONE_KEY_INFO = "tenshu/sync/tombstone-auth/v1"

    // ---------------------------------------------------------------------------------------------
    // SyncBlock
    // ---------------------------------------------------------------------------------------------

    /** The canonical bytes of a [SyncBlock] envelope (§3.3). */
    fun syncBlockCanonical(
        ownerId: String,
        blockId: String,
        hlc: HybridLogicalClock,
        targetEntityIdHash: String?,
    ): ByteArray = CanonicalWriter()
        .str(BLOCK_LABEL)
        .str(ownerId)
        .str(blockId)
        .hlc(hlc)
        .optStr(targetEntityIdHash)
        .toByteArray()

    /** The associated data a [SyncBlock] payload is sealed under: `SHA-256(syncBlockCanonical)`. */
    fun syncBlockAad(
        ownerId: String,
        blockId: String,
        hlc: HybridLogicalClock,
        targetEntityIdHash: String?,
    ): ByteArray = sha256(syncBlockCanonical(ownerId, blockId, hlc, targetEntityIdHash))

    /** [syncBlockAad] of an existing block's envelope. */
    fun syncBlockAad(block: SyncBlock): ByteArray = syncBlockAad(
        ownerId = block.ownerId.get(),
        blockId = block.blockId.get(),
        hlc = block.hlc,
        targetEntityIdHash = block.targetEntityIdHash?.get(),
    )

    /**
     * Seal [plaintext] (a sealed `BlockPayloadEnvelope`) under [identityKey] with the envelope bound
     * as associated data, and build the block. ⚠ Mint [blockId] and [hlc] **before** calling: the
     * ciphertext is only valid for exactly this envelope.
     */
    fun sealSyncBlock(
        crypto: SyncCryptoManager,
        context: TContext,
        blockId: String,
        ownerId: String,
        hlc: HybridLogicalClock,
        targetEntityIdHash: String?,
        plaintext: ByteArray,
        identityKey: ByteArray,
    ): SyncBlock = SyncBlock.create(
        context = context,
        blockId = blockId,
        ownerId = ownerId,
        hlc = hlc,
        encryptedPayload = crypto.encryptWithAad(
            plaintext,
            identityKey,
            syncBlockAad(ownerId, blockId, hlc, targetEntityIdHash),
        ),
        targetEntityIdHash = targetEntityIdHash,
    )

    /**
     * Open [block]'s payload under [identityKey], verifying its envelope. `null` when the key is
     * wrong, the payload was tampered with, **or the envelope was altered** — the three are
     * indistinguishable by design, and every caller drops the block on any of them.
     */
    fun openSyncBlock(crypto: SyncCryptoManager, block: SyncBlock, identityKey: ByteArray): ByteArray? {
        val cipher = block.encryptedPayload.get()
        if (cipher.isEmpty()) return null // body-dropped (`EnvelopeOnlySyncBlock`): nothing to verify
        return try {
            crypto.decryptWithAad(cipher, identityKey, syncBlockAad(block))
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DeleteBlock
    // ---------------------------------------------------------------------------------------------

    /**
     * The canonical bytes of a tombstone (§3.3). ⚠ Over the block **as given** — call it on the wire
     * form (EI-4's narrowed `targetEntityType`), never on the emitter's local copy.
     */
    fun tombstoneCanonical(block: DeleteBlock): ByteArray = CanonicalWriter()
        .str(TOMBSTONE_LABEL)
        .str(block.ownerId.get())
        .str(block.blockId.get())
        .hlc(block.hlc)
        .str(block.targetEntityIdHash.get())
        .str(block.targetEntityType.get())
        .optStr(block.revokedDeviceId?.get())
        .toByteArray()

    /** `HMAC-SHA256(K_tomb, tombstoneCanonical(block))`, with `K_tomb` derived from [identityKey]. */
    fun tombstoneTag(block: DeleteBlock, identityKey: ByteArray): ByteArray =
        hmacSha256(tombstoneKey(identityKey), tombstoneCanonical(block))

    /** A copy of [block] carrying its [tombstoneTag]. */
    fun tagTombstone(context: TContext, block: DeleteBlock, identityKey: ByteArray): DeleteBlock =
        DeleteBlock.create(
            context = context,
            blockId = block.blockId.get(),
            ownerId = block.ownerId.get(),
            hlc = block.hlc,
            targetEntityIdHash = block.targetEntityIdHash.get(),
            targetEntityType = block.targetEntityType.get(),
            revokedDeviceId = block.revokedDeviceId?.get()?.takeIf { it.isNotEmpty() },
            authTag = tombstoneTag(block, identityKey),
        )

    /**
     * Whether [block]'s tag verifies under [identityKey]. An absent, empty or wrong-length tag is
     * `false` — a tombstone without a tag is a forgery, never "legacy".
     */
    fun verifyTombstone(block: DeleteBlock, identityKey: ByteArray): Boolean {
        val tag = block.authTag?.get() ?: return false
        if (tag.size != TAG_SIZE) return false
        return constantTimeEquals(tag, tombstoneTag(block, identityKey))
    }

    /**
     * `K_tomb = HKDF-SHA256(identityKey, info = TOMBSTONE_KEY_INFO)`.
     *
     * ⚠⚠ Cached for the last identity key seen. A tombstone backlog verifies thousands of tags under
     * one key, and re-deriving per row is the HKDF-per-row cost already documented on
     * `SyncProcessor`'s registry. The cache is one immutable pair swapped atomically: a race at
     * worst derives twice.
     */
    internal fun tombstoneKey(identityKey: ByteArray): ByteArray {
        val cached = keyCache
        if (cached != null && cached.first.contentEquals(identityKey)) return cached.second
        val derived = hkdfSha256(
            ikm = identityKey,
            salt = ByteArray(0),
            info = TOMBSTONE_KEY_INFO.encodeToByteArray(),
            length = 32,
        )
        keyCache = identityKey.copyOf() to derived
        return derived
    }

    @Volatile
    private var keyCache: Pair<ByteArray, ByteArray>? = null

    /** Length-independent-time comparison for equal-length inputs. */
    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Length-prefixed (4-byte BE) field writer for the canonical encodings. */
    private class CanonicalWriter {
        private val out = ArrayList<Byte>(256)

        fun str(value: String): CanonicalWriter = bytes(value.encodeToByteArray())

        /** `0x00` when null or empty, else `0x01 ‖ lp(value)` — see the class KDoc on absent/empty. */
        fun optStr(value: String?): CanonicalWriter {
            if (value.isNullOrEmpty()) {
                out.add(0)
            } else {
                out.add(1)
                str(value)
            }
            return this
        }

        fun hlc(hlc: HybridLogicalClock): CanonicalWriter {
            long(hlc.physicalTime.get())
            long(hlc.logicalCounter.get())
            return str(hlc.deviceId.get())
        }

        private fun bytes(value: ByteArray): CanonicalWriter {
            int(value.size)
            value.forEach { out.add(it) }
            return this
        }

        private fun int(value: Int) {
            for (i in 3 downTo 0) out.add(((value ushr (i * 8)) and 0xFF).toByte())
        }

        private fun long(value: Long) {
            for (i in 7 downTo 0) out.add(((value ushr (i * 8)) and 0xFFL).toByte())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
