package org.tenshu.sync.proxy

import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyed property tag (`content-plane-cr2-b3-wire-spec.md` §11): what `PushedEdge.propertyId`
 * carries instead of a schema id. Pins convergence (every device names a register alike), the
 * per-holder unlinkability, the wire shape, and the drain-side tagger's realm choice.
 */
class ContentPropertyTagTest {

    private val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) })
    private val context = proxy.tContext
    private val crypto = context.syncCryptoManager()

    private val volumeKeyA = ByteArray(32) { it.toByte() }
    private val volumeKeyB = ByteArray(32) { (it + 7).toByte() }
    private val holder1 = "0".repeat(32)
    private val holder2 = "1".repeat(32)

    /** ⭐⭐ Two devices of one realm must name one register alike, or the relay holds two edges for it. */
    @Test
    fun twoDevicesOfOneRealmComputeTheSameTag() {
        assertEquals(
            ContentPropertyTag.of(holder1, "tenshu/file/file/data", ContentPropertyTag.keyFor(volumeKeyA)),
            ContentPropertyTag.of(holder1, "tenshu/file/file/data", ContentPropertyTag.keyFor(volumeKeyA.copyOf())),
        )
    }

    /** ⚠⚠ Per holder: a per-realm constant would let the relay cluster rows into types by tag co-occurrence. */
    @Test
    fun theSamePropertyOnTwoRowsGetsUnrelatedTags() {
        val key = ContentPropertyTag.keyFor(volumeKeyA)
        assertNotEquals(ContentPropertyTag.of(holder1, "p", key), ContentPropertyTag.of(holder2, "p", key))
        assertNotEquals(ContentPropertyTag.of(holder1, "p", key), ContentPropertyTag.of(holder1, "q", key))
        assertNotEquals(
            ContentPropertyTag.of(holder1, "p", key),
            ContentPropertyTag.of(holder1, "p", ContentPropertyTag.keyFor(volumeKeyB)),
            "and per realm",
        )
    }

    /** The relay's `EDGE_ID_PATTERN` admits it, and the send-side guard recognises it. */
    @Test
    fun aTagIsSixtyFourLowercaseHexChars_andNoSchemaIdLooksLikeOne() {
        val tag = ContentPropertyTag.of(holder1, "tenshu/file/file/data", ContentPropertyTag.keyFor(volumeKeyA))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(tag), tag)
        assertTrue(ContentPropertyTag.looksLikeTag(tag))
        assertTrue(!ContentPropertyTag.looksLikeTag("tenshu/file/file/data"))
    }

    /** ⚠ Domain separation: a separate HKDF child, so a tag key is never `K_entity`. */
    @Test
    fun theTagKeyIsNotTheEntityKey() {
        assertTrue(!ContentPropertyTag.keyFor(volumeKeyA).contentEquals(WireEntityId.keyFor(volumeKeyA)))
    }

    @Test
    fun theTaggerTagsUnderTheBlocksRealm_andKeepsContentAndRegenerable() {
        val manager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), context).also { it.initialize("tag phrase") }
        val realm = manager.currentRealmContext()!!
        val holder = WireIdResolver(manager).of(DataId.StringDataId.create(context, SyntheticTestData.ID, "/a.txt"))
        val edges = listOf(ContentEdge("p/data", "cid-1", regenerable = false), ContentEdge("p/thumb", null, regenerable = true))

        val wire = ContentPropertyTagger(manager).wireEdges(block(realm.ownerId, holder), edges)!!

        val key = ContentPropertyTag.keyFor(realm.derivedKeys.sharedVolumeKey)
        assertEquals(
            listOf(
                ContentEdge(ContentPropertyTag.of(holder, "p/data", key), "cid-1", regenerable = false),
                ContentEdge(ContentPropertyTag.of(holder, "p/thumb", key), null, regenerable = true),
            ),
            wire,
        )
    }

    /** ⚠ No realm key for the block's owner ⇒ no honest name: the push must not go out. */
    @Test
    fun aBlockOfAnUnknownRealmCannotBeTagged_butAnEdgelessOneNeedsNoRealm() {
        val manager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), context).also { it.initialize("tag phrase") }
        val tagger = ContentPropertyTagger(manager)
        val edges = listOf(ContentEdge("p", "c", regenerable = false))

        assertNull(tagger.wireEdges(block("someone-else", holder1), edges))
        assertEquals(emptyList(), tagger.wireEdges(block("someone-else", holder1), emptyList()))
    }

    private fun block(ownerId: String, holder: String) = SyncBlock.create(
        context = context,
        blockId = "b",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(context, 1_000, 0, "d"),
        encryptedPayload = byteArrayOf(1),
        targetEntityIdHash = holder,
    )
}
