package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.dataFactory
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.CascadedTombstonePush
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.PushedEdge
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B3's shared wire types survive the binary round trip both halves use: the client serialises
 * (`RestSyncRelay`), the relay parses (`receiveParsed`). Landed with the types, before B3c and B3d
 * split, so a mismatch shows up here once rather than as two halves that each pass alone
 * (`content-plane-cr2-b3-wire-spec.md` v0.2).
 *
 * ⚠ The none case carries the weight: "none" is an **absent** optional content id, and a field that
 * came back as an empty string would read as an edge to content `""`.
 */
class B3WireTypesRoundTripTest {

    private val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
    }
    private val context = testProxy.tContext
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(testProxy.executionContext.schemaProvider, context.dataFactory())

    private val hlc = HybridLogicalClock.create(context, 1_000L, 0L, "device-a")

    @Test
    fun anEdgedPushRoundTrips_withANoneEdgeStillAbsent() {
        val block = SyncBlock.create(
            context = context,
            blockId = "block-1",
            ownerId = "owner-1",
            hlc = hlc,
            encryptedPayload = byteArrayOf(1, 2, 3),
            targetEntityIdHash = "holder-1",
        )
        val sent = EdgedPush.create(
            context = context,
            relayProtocolVersion = 4,
            block = block,
            edges = listOf(
                PushedEdge.create(context, "tenshu/file/data", "cid-1", regenerable = false),
                PushedEdge.create(context, "tenshu/file/thumbnail", null, regenerable = true),
            ),
        )

        val received = parser.parse<EdgedPush>(serializer.serialize(sent))

        assertEquals(4L, received.relayProtocolVersion.get())
        assertEquals("block-1", received.block.blockId.get())
        assertEquals("holder-1", received.block.targetEntityIdHash?.get())
        val edges = received.edges.toList()
        assertEquals(2, edges.size)
        assertEquals("cid-1", edges[0].contentId?.get())
        assertEquals(false, edges[0].regenerable.get())
        assertNull(edges[1].contentId, "none must stay ABSENT, never become an empty string")
        assertEquals(true, edges[1].regenerable.get())
        assertTrue(received.revive.toList().isEmpty(), "revive is reserved and empty in B3")
    }

    @Test
    fun aCascadedTombstonePushRoundTrips() {
        val sent = CascadedTombstonePush.create(
            context = context,
            relayProtocolVersion = 4,
            block = DeleteBlock.create(
                context = context,
                blockId = "delete-1",
                ownerId = "owner-1",
                hlc = hlc,
                targetEntityIdHash = "folder-1",
                targetEntityType = "tenshu/file",
            ),
            cascade = listOf("child-a", "child-b"),
        )

        val received = parser.parse<CascadedTombstonePush>(serializer.serialize(sent))

        assertEquals(4L, received.relayProtocolVersion.get())
        assertEquals("folder-1", received.block.targetEntityIdHash.get())
        assertEquals(listOf("child-a", "child-b"), received.cascade.toList().map { it.get() })
    }
}
