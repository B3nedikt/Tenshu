package org.tenshu.sync.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for `sync-backend-relay-plan.md` §4.2: every per-owner list inside
 * `SyncMailbox` must use `ConcurrentHashMap.computeIfAbsent`, not the non-atomic
 * `getOrPut` extension. With `getOrPut`, two threads racing the first push for the
 * same `ownerId` could both observe `null`, both create a fresh `mutableListOf()`,
 * and only one ended up in the map — the loser's reference pointed at a list no
 * longer in the map, so its `add` was silently dropped.
 *
 * The test hammers `pushBlock` from many coroutines for a previously-unseen owner
 * (the worst case for the race). Every block must end up readable through `getChanges`.
 */
class SyncMailboxConcurrencyTest {

    private val mailbox = SyncMailbox()

    private fun makeContext(): TContext {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )
        return ctx
    }

    private fun makeBlock(
        ctx: TContext,
        ownerId: String,
        physicalTime: Long,
        deviceId: String
    ): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = "block-$ownerId-$deviceId-$physicalTime",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, deviceId),
        encryptedPayload = "payload-$physicalTime".encodeToByteArray()
    )

    @Test
    fun concurrentPushesForSameOwner_loseNoBlocks() = runBlocking {
        val ctx = makeContext()
        val ownerId = "concurrent-owner"
        val concurrency = 32
        val blocksPerCoroutine = 25

        withContext(Dispatchers.Default) {
            (0 until concurrency).map { workerId ->
                async {
                    repeat(blocksPerCoroutine) { i ->
                        mailbox.pushBlock(
                            ownerId,
                            makeBlock(
                                ctx = ctx,
                                ownerId = ownerId,
                                physicalTime = (workerId * 10_000L) + i,
                                deviceId = "device-$workerId"
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        val expected = concurrency * blocksPerCoroutine
        // The count is the load-bearing assertion: with the pre-fix `getOrPut` race, this
        // would intermittently come back short due to dropped inserts.
        //
        // Cursor is arrival seq, not HLC physicalTime — so sinceTimestamp = 0 starts at the
        // beginning (the counter starts at 1 for the first push). ⚠ Read by *paging*: a single
        // response is capped at SYNC_CHANGES_PAGE_LIMIT, and 800 blocks exceed it. Paging is
        // what preserves this test's actual invariant ("no block is lost"); asserting one
        // response returns all 800 would only re-assert the unbounded read that OOM'd the
        // relay on 2026-07-29.
        val received = mutableListOf<String>()
        var cursor = 0L
        while (true) {
            val page = mailbox.getChanges(ownerId, sinceTimestamp = cursor)
            if (page.blocks.isEmpty()) break
            received += page.blocks.map { it.blockId.get() }
            cursor = page.nextCursor
        }
        assertEquals(expected, received.size)
        // Paging must not duplicate either — an off-by-one in the cursor would re-serve a row.
        assertEquals(expected, received.distinct().size)
    }
}
