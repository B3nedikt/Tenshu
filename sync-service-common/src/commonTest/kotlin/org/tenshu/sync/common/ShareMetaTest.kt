package org.tenshu.sync.common

import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the sealed Mode-A share metadata (`relay-metadata-minimisation.md` F3): the relay-stored
 * blob carries no clear name / MIME, opens only under the right `K_share` and the right
 * `(shareId, chunkCount)`, and an empty or malformed blob is a distinct, visible failure.
 */
class ShareMetaTest {

    private val crypto = createDefaultSyncCryptoManager()
    private val kShare = ByteArray(32) { (it * 7 + 1).toByte() }
    private val meta = ShareMeta("Quarterly report — Q3.pdf", "application/pdf", 5_242_881L)

    @Test
    fun roundTrip() {
        val sealed = sealShareMeta(crypto, kShare, "share-1", 3, meta)
        assertEquals(meta, openShareMeta(crypto, kShare, "share-1", 3, sealed))
    }

    @Test
    fun sealedBlobCarriesNoClearMetadata() {
        val sealed = sealShareMeta(crypto, kShare, "share-1", 3, meta)
        val asText = sealed.decodeToString()
        assertFalse(asText.contains("Quarterly"), "file name must not appear in the clear")
        assertFalse(asText.contains("application/pdf"), "MIME must not appear in the clear")
    }

    @Test
    fun plaintextIsPaddedToABucket() {
        val short = encodeShareMeta(ShareMeta("a", "text/plain", 1))
        val longer = encodeShareMeta(ShareMeta("a".repeat(150), "text/plain", 1))
        assertEquals(SHARE_META_PAD_BUCKET, short.size)
        assertEquals(short.size, longer.size, "name length hidden within one bucket")
        val overflow = encodeShareMeta(ShareMeta("a".repeat(300), "text/plain", 1))
        assertEquals(2 * SHARE_META_PAD_BUCKET, overflow.size)
    }

    @Test
    fun wrongKeyFails() {
        val sealed = sealShareMeta(crypto, kShare, "share-1", 3, meta)
        val wrong = kShare.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFails { openShareMeta(crypto, wrong, "share-1", 3, sealed) }
    }

    @Test
    fun splicedAcrossSharesOrChunkCountFails() {
        val sealed = sealShareMeta(crypto, kShare, "share-1", 3, meta)
        assertFails { openShareMeta(crypto, kShare, "share-2", 3, sealed) }
        assertFails { openShareMeta(crypto, kShare, "share-1", 2, sealed) }
    }

    @Test
    fun chunkCiphertextNeverOpensAsMetadata() {
        val chunk = crypto.encryptWithAad(encodeShareMeta(meta), kShare, shareChunkAad("share-1", 0, 3))
        assertFails { openShareMeta(crypto, kShare, "share-1", 3, chunk) }
    }

    @Test
    fun emptyBlobIsMalformedNotBlank() {
        assertFailsWith<ShareMetaMalformedException> {
            openShareMeta(crypto, kShare, "share-1", 3, ByteArray(0))
        }
    }

    @Test
    fun malformedRecordsAreRejected() {
        val good = encodeShareMeta(meta)
        // Unknown version.
        assertFailsWith<ShareMetaMalformedException> { decodeShareMeta(good.copyOf().also { it[0] = 9 }) }
        // Non-zero padding.
        assertFailsWith<ShareMetaMalformedException> {
            decodeShareMeta(good.copyOf().also { it[it.size - 1] = 1 })
        }
        // Truncated.
        assertFailsWith<ShareMetaMalformedException> { decodeShareMeta(good.copyOf(10)) }
        // Empty name: version 1, nameLen 0, mimeLen 0, size 0, zero padding — never a blank name.
        assertFailsWith<ShareMetaMalformedException> {
            decodeShareMeta(ByteArray(SHARE_META_PAD_BUCKET).also { it[0] = 1 })
        }
    }

    @Test
    fun aadVectorIsStable() {
        assertEquals("tenshu/file-share-meta/v1|abc|4", shareMetaAad("abc", 4).decodeToString())
        assertTrue(shareMetaAad("abc", 4).isNotEmpty())
    }

    private fun assertFails(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(thrown != null, "expected the open to fail")
    }
}
