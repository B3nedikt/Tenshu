package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.system.createFolder
import org.tenshu.file.client.fileService
import org.tenshu.sync.common.SyncRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ `POST /sync/fetch-entity-blocks` end to end (B3 wire spec §11.3): the device's real
 * `RestSyncRelay`, over HTTP, against the in-process relay, returns the blocks the device pushed for
 * an entity.
 *
 * ⚠⚠ The defect this pins: the route used to take a serialised `DataId` (a File's path, in the clear)
 * and hash it with the LOCAL `getDataIdHash()`, while every block since EI-1 is stored under the keyed
 * wire id. A decimal FNV never equals 32 hex chars, so the route found **nothing** for any real
 * block, and the on-demand manifest path and `EntitlementRecovery` silently got `[]`. The relay
 * test that pinned the route seeded its block under the local hash, so it could not notice; this one
 * lets the client stamp the block exactly as production does.
 */
class FetchEntityBlocksByWireIdSystemTest {

    @Test
    fun theRouteReturnsTheBlocksADevicePushedForAnEntity() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!

            val created = host.tenshuClient.fileService.defaultFileSystem.createFolder("/fetched")
            assertTrue(created is TResult.Success, "createFolder should succeed; got $created")

            // The names the device's own drain stamped — production naming, never a seeded hash.
            fun storedWireIds(): Set<String> = system.syncMailbox.getChanges(ownerId, 0L).blocks
                .mapNotNull { it.targetEntityIdHash?.get() }.toSet()
            system.advanceUntil(timeout = 15.seconds) { storedWireIds().size >= 2 }
            val wireIds = storedWireIds()
            assertTrue(wireIds.isNotEmpty(), "no entity block reached the relay")
            assertTrue(wireIds.all { Regex("^[0-9a-f]{32}$").matches(it) }, "blocks are named by EI-1 wire ids: $wireIds")

            val relay: SyncRelay = host.koinApp.koin.get()
            for (wireId in wireIds) {
                val stored = system.syncMailbox.fetchEntityBlocks(ownerId, wireId).map { it.blockId.get() }
                val fetched = relay.fetchEntityBlocks(wireId)

                assertTrue(fetched is TResult.Success, "fetch-entity-blocks failed for $wireId: $fetched")
                val blocks = (fetched as TResult.Success).value!!.toList()
                assertTrue(blocks.isNotEmpty(), "the route found nothing for $wireId — the pre-fix defect")
                assertEquals(stored, blocks.map { it.blockId.get() }, "exactly the entity's blocks, in push order")
                assertTrue(blocks.all { it.targetEntityIdHash?.get() == wireId }, "and only that entity's")
            }
        } finally {
            system.shutdown()
        }
    }
}
