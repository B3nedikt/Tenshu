package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A tombstone whose realm was superseded mid-call is **not emitted at all**.
 *
 * ⚠⚠ Two ways to get this wrong, and the test covers the second because the first is now
 * unreachable. The name is `HMAC(K_entity, identity)` under some realm's volume key (EI-1) and the
 * `ownerId` says which realm the block is addressed to; taken from two separate reads, an owner
 * adoption between them queues a block for realm B carrying a name only realm A can match. Naming
 * and stamping from one snapshot fixes that — but the snapshot itself can go stale before the block
 * is queued, and then an A-stamped block joins a queue pushed with a JWT minted for B. The relay
 * answers `403`; `emit` had already returned a block id, so the caller deleted the row and cleared
 * its retry, and A's peers keep it for ever.
 *
 * ⇒ the emit **refuses**. "Not emitted" is a state every caller on these paths already handles;
 * "emitted into the wrong realm" is not.
 *
 * ⚠ The two realms here stand in for "before and after the adoption"; the test cannot schedule a
 * real adoption mid-call, so it holds the resolver on one realm while the manager reports the other
 * — which is exactly what the window looks like from inside the emit.
 */
class TombstoneRealmSnapshotTest {

    @Test
    fun nothingIsQueuedWhenTheNamingRealmIsNoLongerTheCurrentOne() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()

        val namingRealm = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
            .also { it.initialize("naming realm phrase") }
        val stampingRealm = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
            .also { it.initialize("stamping realm phrase") }
        assertNotEquals(
            namingRealm.currentOwnerId,
            stampingRealm.currentOwnerId,
            "precondition: two distinct realms, or the assertion below proves nothing",
        )

        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = stampingRealm,
            cryptoManager = crypto,
            wireIdResolver = WireIdResolver(namingRealm),
        )
        val target = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "/a.txt",
        )

        assertNull(
            observer.emit(targetEntityId = target, targetEntityType = SyntheticTestData.ID),
            "a block id here tells the caller to delete the row, and this one would never land",
        )
        assertNull(
            observer.emitAll(
                listOf(TombstoneEmitter.Target(entityId = target, entityType = SyntheticTestData.ID))
            ),
            "the batch answers null for the same reason — and all-or-nothing, never a partial queue",
        )

        assertEquals(
            emptyList(),
            proxy.queuedTombstones().map { it.ownerId.get() },
            "nothing may reach the queue: it is drained with a JWT minted for the current realm, " +
                "so a block from the superseded one is a 403 the caller was told was a success",
        )
    }

    private fun TestProxy.queuedTombstones(): List<DeleteBlock> =
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = tContext,
                    type = DeleteBlock.ID,
                    relationId = SyncService.Collections.PENDING_TOMBSTONES,
                ),
                isEntity = false,
                elementSchemaId = DeleteBlock.ID,
            )
        )?.filterIsInstance<DeleteBlock>()?.toList() ?: emptyList()
}
