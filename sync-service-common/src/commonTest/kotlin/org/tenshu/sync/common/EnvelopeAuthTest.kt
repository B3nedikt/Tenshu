package org.tenshu.sync.common

import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins `sync-envelope-authentication.md` §3. ⚠⚠ The vectors are a **wire contract**: this file runs
 * on JVM, JS and iOS, and every target must produce the same hex. A failing vector means the
 * encoding moved — that needs a new `/vN` label and a relay-protocol cutover, never a new constant.
 */
class EnvelopeAuthTest {

    private val registry = createSyntheticTestRegistry().also { it.registerConfig(syncServiceCommonModule) }
    private val context = createTestContext(registry)
    private val identityKey = ByteArray(32) { it.toByte() }

    private fun hlc(phys: Long = 1_700_000_000_000L, counter: Long = 7L, device: String = "device-a") =
        HybridLogicalClock.create(context, phys, counter, device)

    private fun tombstone(
        ownerId: String = "owner-1",
        blockId: String = "block-1",
        hlc: HybridLogicalClock = hlc(),
        hash: String = "wire-id-1",
        type: String = "tenshu/entity",
        revoked: String? = null,
    ) = DeleteBlock.create(context, blockId, ownerId, hlc, hash, type, revoked)

    // --- vectors ---------------------------------------------------------------------------------

    @Test
    fun syncBlockAad_vector() {
        val aad = EnvelopeAuth.syncBlockAad("owner-1", "block-1", hlc(), "wire-id-1")
        assertEquals(SYNC_BLOCK_AAD_HEX, aad.toHex())
    }

    @Test
    fun syncBlockAad_vector_noTarget() {
        val aad = EnvelopeAuth.syncBlockAad("owner-1", "block-1", hlc(), null)
        assertEquals(SYNC_BLOCK_AAD_NO_TARGET_HEX, aad.toHex())
    }

    @Test
    fun tombstoneTag_vector() {
        assertEquals(TOMBSTONE_TAG_HEX, EnvelopeAuth.tombstoneTag(tombstone(), identityKey).toHex())
    }

    @Test
    fun tombstoneTag_vector_revocation() {
        val block = tombstone(type = "tenshu/identity/device", revoked = "device-b")
        assertEquals(TOMBSTONE_REVOKE_TAG_HEX, EnvelopeAuth.tombstoneTag(block, identityKey).toHex())
    }

    // --- tombstone semantics ---------------------------------------------------------------------

    @Test
    fun taggedTombstone_verifies_andSurvivesTheCodec() {
        val tagged = EnvelopeAuth.tagTombstone(context, tombstone(), identityKey)
        assertTrue(EnvelopeAuth.verifyTombstone(tagged, identityKey))
        val parsed = BinaryParser(registry, registry).parse(BinarySerializer().serialize(tagged)) as DeleteBlock
        assertTrue(EnvelopeAuth.verifyTombstone(parsed, identityKey), "absent revokedDeviceId must round-trip")
    }

    @Test
    fun untaggedTombstone_isRejected() {
        assertFalse(EnvelopeAuth.verifyTombstone(tombstone(), identityKey))
    }

    @Test
    fun wrongKey_isRejected() {
        val tagged = EnvelopeAuth.tagTombstone(context, tombstone(), identityKey)
        assertFalse(EnvelopeAuth.verifyTombstone(tagged, ByteArray(32) { 9 }))
    }

    @Test
    fun everyField_isBound() {
        val tag = EnvelopeAuth.tombstoneTag(tombstone(), identityKey)
        val altered = listOf(
            tombstone(ownerId = "owner-2"),
            tombstone(blockId = "block-2"),
            tombstone(hlc = hlc(phys = 1_700_000_000_001L)),
            tombstone(hlc = hlc(counter = 8L)),
            tombstone(hlc = hlc(device = "device-z")),
            tombstone(hash = "wire-id-2"),
            tombstone(type = "tenshu/identity/device"),
            tombstone(revoked = "device-b"),
        )
        altered.forEach { forged ->
            val withStolenTag = DeleteBlock.create(
                context, forged.blockId.get(), forged.ownerId.get(), forged.hlc,
                forged.targetEntityIdHash.get(), forged.targetEntityType.get(),
                forged.revokedDeviceId?.get(), authTag = tag,
            )
            assertFalse(EnvelopeAuth.verifyTombstone(withStolenTag, identityKey), "unbound: $forged")
        }
    }

    @Test
    fun fieldBoundaries_cannotShift() {
        // Without length prefixes, ("ab","c") and ("a","bc") would concatenate identically.
        val a = EnvelopeAuth.tombstoneTag(tombstone(ownerId = "ab", blockId = "c"), identityKey)
        val b = EnvelopeAuth.tombstoneTag(tombstone(ownerId = "a", blockId = "bc"), identityKey)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun tagAndBlockEnvelope_areDomainSeparated() {
        val blockCanonical = EnvelopeAuth.syncBlockCanonical("owner-1", "block-1", hlc(), "wire-id-1")
        val tombCanonical = EnvelopeAuth.tombstoneCanonical(tombstone())
        assertFalse(blockCanonical.contentEquals(tombCanonical))
    }

    @Test
    fun tombstoneKey_isCachedButKeyed() {
        val k1 = EnvelopeAuth.tombstoneKey(identityKey)
        val k2 = EnvelopeAuth.tombstoneKey(ByteArray(32) { 1 })
        assertFalse(k1.contentEquals(k2))
        assertContentEquals(k1, EnvelopeAuth.tombstoneKey(identityKey))
    }

    // --- SyncBlock binding (real XChaCha20-Poly1305 backend) ------------------------------------

    private val crypto = createDefaultSyncCryptoManager()
    private val plaintext = "payload".encodeToByteArray()

    private fun sealed(target: String? = "wire-id-1") = EnvelopeAuth.sealSyncBlock(
        crypto, context, "block-1", "owner-1", hlc(), target, plaintext, identityKey,
    )

    private fun restamped(block: SyncBlock, blockId: String = block.blockId.get(), owner: String = block.ownerId.get(),
                          hlc: HybridLogicalClock = block.hlc, target: String? = block.targetEntityIdHash?.get()) =
        SyncBlock.create(context, blockId, owner, hlc, block.encryptedPayload.get(), target)

    @Test
    fun sealedBlock_opens() {
        assertContentEquals(plaintext, EnvelopeAuth.openSyncBlock(crypto, sealed(), identityKey))
        assertContentEquals(plaintext, EnvelopeAuth.openSyncBlock(crypto, sealed(target = null), identityKey))
    }

    @Test
    fun restampedEnvelope_doesNotOpen() {
        val block = sealed()
        listOf(
            restamped(block, hlc = hlc(phys = 1_800_000_000_000L)),
            restamped(block, hlc = hlc(device = "device-z")),
            restamped(block, blockId = "block-2"),
            restamped(block, owner = "owner-2"),
            restamped(block, target = "wire-id-2"),
            restamped(block, target = null),
        ).forEach { forged ->
            assertNull(EnvelopeAuth.openSyncBlock(crypto, forged, identityKey), "unbound: $forged")
        }
    }

    @Test
    fun legacyUnboundCiphertext_doesNotOpen() {
        // A pre-cutover payload (`encrypt` with no AAD) must not pass as bound.
        val legacy = SyncBlock.create(context, "block-1", "owner-1", hlc(), crypto.encrypt(plaintext, identityKey), "wire-id-1")
        assertNull(EnvelopeAuth.openSyncBlock(crypto, legacy, identityKey))
    }

    @Test
    fun bodyDroppedBlock_doesNotOpen() {
        val dropped = SyncBlock.create(context, "block-1", "owner-1", hlc(), ByteArray(0), "wire-id-1")
        assertNull(EnvelopeAuth.openSyncBlock(crypto, dropped, identityKey))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * ⚠ Cross-checked against an independent Python implementation of §3.3 (hashlib/hmac/struct),
     * not merely captured from this code: identity key = bytes 0..31, HLC = (1700000000000, 7,
     * "device-a"), owner "owner-1", block "block-1", target "wire-id-1".
     */
    private companion object {
        const val SYNC_BLOCK_AAD_HEX = "8bc94b1adbf824939c99c0d75c5acfc876245da041e209b7d4b3756dc0f6702e"
        const val SYNC_BLOCK_AAD_NO_TARGET_HEX = "579f8ddd52d11b1d4ebd76614924ce4492d3317d4c586bddb0ba07bc47e72eac"
        const val TOMBSTONE_TAG_HEX = "aff1755618230ca2d2a0633537abb0efb82c218a1d2fe8ac0c5948845266138a"
        const val TOMBSTONE_REVOKE_TAG_HEX = "f36b571d9ad561ca39a32bc8f12b588eb9a156f1026581d160d4536e4ca28c6f"
    }
}
