package org.tenshu.sync.relay.storage

import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2b-ii: the durable mailbox. Pins the behaviours the in-memory `SyncMailboxGcTest`
 * pins, plus the persistence-specific ones: a shared seq counter that **continues across
 * restart**, and `body_dropped` surviving a reopen. The GC sweep itself is exercised
 * through the shared `MailboxStore` default running over the SQLite primitives.
 */
class SqliteSyncMailboxTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-mailbox-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    @Test
    fun pushBlock_getChanges_cursorSemantics() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushBlock("alice", block(ctx, "alice", "b1", 100L, "H1"))
            mb.pushBlock("alice", block(ctx, "alice", "b2", 200L, "H2"))

            val all = mb.getChanges("alice", sinceTimestamp = 0)
            assertEquals(listOf("b1", "b2"), all.blocks.map { it.blockId.get() })
            assertEquals(2L, all.nextCursor)

            // Past the end: empty, cursor stays at `since` (load-bearing — see ChangesResult KDoc).
            val empty = mb.getChanges("alice", sinceTimestamp = all.nextCursor)
            assertTrue(empty.blocks.isEmpty())
            assertEquals(2L, empty.nextCursor)

            // Partial.
            val tail = mb.getChanges("alice", sinceTimestamp = 1)
            assertEquals(listOf("b2"), tail.blocks.map { it.blockId.get() })
        }
    }

    @Test
    fun getChanges_capsTheResponseAtThePageLimit_andPagesLosslessly() {
        // The OOM guard. Before the cap, a from-zero cursor asked for the entire history in
        // one response and the relay materialised all of it — 49 070 blocks / 244 MB against a
        // 242 MB heap on 2026-07-29, so every pull 500'd and the device never advanced past 0.
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            val total = 25
            repeat(total) { i -> mb.pushBlock("alice", block(ctx, "alice", "b$i", 100L + i, "H$i")) }

            val firstPage = mb.getChanges("alice", sinceTimestamp = 0, limit = 10)
            assertEquals(10, firstPage.blocks.size, "response must be capped at the limit")
            // The cap yields a *prefix*, and the cursor is the prefix's end — not the log tip —
            // so a caller that advances to it has provably missed nothing before it.
            assertEquals(listOf("b0", "b1", "b2"), firstPage.blocks.take(3).map { it.blockId.get() })
            assertEquals(10L, firstPage.nextCursor)

            // Walking the pages must reconstruct the whole log exactly once, in order.
            val walked = mutableListOf<String>()
            var cursor = 0L
            var pages = 0
            while (true) {
                val page = mb.getChanges("alice", sinceTimestamp = cursor, limit = 10)
                if (page.blocks.isEmpty()) break
                walked += page.blocks.map { it.blockId.get() }
                cursor = page.nextCursor
                pages++
                check(pages < 100) { "paging did not terminate — cursor stuck at $cursor" }
            }
            assertEquals((0 until total).map { "b$it" }, walked)
            assertEquals(3, pages)
        }
    }

    @Test
    fun getTombstones_capsTheResponseAtThePageLimit() {
        // The tombstone lane shares the block lane's seq counter, so it has to share the cap
        // too: the client bounds one lane's cursor advance by the other's truncation point.
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            repeat(12) { i -> mb.pushTombstone("alice", tombstone(ctx, "alice", 100L + i, "H$i")) }

            val page = mb.getTombstones("alice", sinceTimestamp = 0, limit = 5)
            assertEquals(5, page.tombstones.size)
            assertEquals(5L, page.nextCursor)

            val rest = mb.getTombstones("alice", sinceTimestamp = page.nextCursor, limit = 100)
            assertEquals(7, rest.tombstones.size)
            assertEquals(12L, rest.nextCursor)
        }
    }

    @Test
    fun seqCounter_isSharedAcrossBlocksAndTombstones() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushBlock("alice", block(ctx, "alice", "b1", 100L, "H1"))      // seq 1
            mb.pushTombstone("alice", tombstone(ctx, "alice", 150L, "H1"))    // seq 2
            mb.pushBlock("alice", block(ctx, "alice", "b2", 200L, "H2"))      // seq 3

            assertEquals(3L, mb.getChanges("alice", 0).nextCursor)            // max block seq
            assertEquals(2L, mb.getTombstones("alice", 0).nextCursor)         // tombstone seq
        }
    }

    @Test
    fun blocks_and_seqCounter_surviveReopen() {
        RelayDatabase(url).use { db ->
            SqliteSyncMailbox(db, RelayBinaryCodec()).pushBlock("alice", block(ctx(), "alice", "b1", 100L, "H1"))
        }
        // New process: the second block must get seq 2 (counter continued), not reuse seq 1.
        RelayDatabase(url).use { db ->
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushBlock("alice", block(ctx(), "alice", "b2", 200L, "H2"))
            val res = mb.getChanges("alice", 0)
            assertEquals(listOf("b1", "b2"), res.blocks.map { it.blockId.get() })
            assertEquals(2L, res.nextCursor)
        }
    }

    @Test
    fun tombstoneIndex_keepsLatestHlc_and_deviceCount() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushTombstone("alice", tombstone(ctx, "alice", 100L, "H1"))
            mb.pushTombstone("alice", tombstone(ctx, "alice", 300L, "H1")) // newer for same target
            mb.pushTombstone("alice", tombstone(ctx, "alice", 200L, "H2", targetType = Device.ID))

            val idx = mb.tombstoneIndex("alice")
            assertEquals(setOf("H1", "H2"), idx.keys)
            assertEquals(300L, idx.getValue("H1").hlc.physicalTime.get())
            assertEquals(setOf("alice"), mb.tombstoneOwnerIds())
            assertEquals(1L, mb.deviceTombstoneCount("alice")) // only the Device.ID one
        }
    }

    @Test
    fun gcSweep_dropsBodies_afterQuorumAndRetention_andSurvivesReopen() {
        val knownDevices = KnownDevicesStore().apply {
            register("alice", "deviceA", byteArrayOf(1))
            register("alice", "deviceB", byteArrayOf(2))
        }
        val tips = HlcTipStore()

        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushBlock("alice", block(ctx, "alice", "b1", 100L, "H1"))
            mb.pushTombstone("alice", tombstone(ctx, "alice", 200L, "H1"))
            // Both realm devices ack past the tombstone HLC.
            tips.record("alice", tip(ctx, "alice", "deviceA", 200L))
            tips.record("alice", tip(ctx, "alice", "deviceB", 200L))
            mb.setRetentionWindowMs("alice", 0) // eager GC once quorum reached

            val dropped = mb.gcSweep(knownDevices, tips, nowMs = 10_000L)
            assertEquals(1, dropped)
            assertEquals(setOf("b1"), mb.droppedBlockIds("alice"))
            // Envelope preserved, payload emptied.
            val block = mb.getChanges("alice", 0).blocks.single()
            assertEquals("b1", block.blockId.get())
            assertContentEquals(byteArrayOf(), block.encryptedPayload.get())
            // Idempotent.
            assertEquals(0, mb.gcSweep(knownDevices, tips, nowMs = 20_000L))
            // Tombstone itself is preserved.
            assertEquals(1, mb.getTombstones("alice", 0).tombstones.size)
        }

        // body_dropped persists across restart.
        RelayDatabase(url).use { db ->
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            assertEquals(setOf("b1"), mb.droppedBlockIds("alice"))
            assertContentEquals(byteArrayOf(), mb.getChanges("alice", 0).blocks.single().encryptedPayload.get())
        }
    }

    @Test
    fun fetchEntityBlocks_filtersByHash() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val mb = SqliteSyncMailbox(db, RelayBinaryCodec())
            mb.pushBlock("alice", block(ctx, "alice", "b1", 100L, "H1"))
            mb.pushBlock("alice", block(ctx, "alice", "b2", 200L, "H2"))
            mb.pushBlock("alice", block(ctx, "alice", "b3", 300L, "H1"))
            assertEquals(listOf("b1", "b3"), mb.fetchEntityBlocks("alice", "H1").map { it.blockId.get() })
        }
    }

    // --- builders (mirror SyncMailboxGcTest) ---

    private fun ctx(): TContext {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry,
        )
        return ctx
    }

    private fun block(ctx: TContext, owner: String, blockId: String, hlc: Long, hash: String): SyncBlock =
        SyncBlock.create(
            context = ctx,
            blockId = blockId,
            ownerId = owner,
            hlc = HybridLogicalClock.create(ctx, hlc, 0, "deviceA"),
            encryptedPayload = "payload-$blockId".encodeToByteArray(),
            targetEntityIdHash = hash,
        )

    private fun tombstone(
        ctx: TContext,
        owner: String,
        hlc: Long,
        hash: String,
        targetType: String = "tenshu/test/entity",
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "tomb-$owner-$hlc",
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, hlc, 0, "deviceA"),
        targetEntityIdHash = hash,
        targetEntityType = targetType,
    )

    private fun tip(ctx: TContext, owner: String, deviceId: String, hlc: Long): SignedHlcTip =
        SignedHlcTip.create(
            context = ctx,
            ownerId = owner,
            deviceId = deviceId,
            hlc = HybridLogicalClock.create(ctx, hlc, 0, deviceId),
            signature = byteArrayOf(0x01),
        )
}
