package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.sync.proxy.impl.OnDemandResolutionImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resolver's pull observer (`drive-synced-thumbnails.md` §15.2 — *the pull is the declaration*):
 * it sees every pull's property id before anything is fetched, and it can never fail the fetch.
 */
class OnDemandPullObserverTest {

    @Test
    fun everyPullIsObservedWithItsPropertyId_evenWithNoProcessorYet() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val seen = mutableListOf<Pair<String, String>>()
        val resolver = OnDemandResolutionImpl(
            relay = MockSyncRelay(proxy.tContext),
            syncProcessorHolder = SyncProcessorHolder(),
            onPull = { _, entityId, propertyId ->
                seen += (entityId as DataId.StringDataId).keyValue.get() to propertyId
            },
        )
        val entity = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "row-1")

        resolver.resolve(proxy.executionContext, entity, "test/onDemandProperty", null)
        resolver.resolve(proxy.executionContext, entity, "test/otherProperty", null)

        assertEquals(listOf("row-1" to "test/onDemandProperty", "row-1" to "test/otherProperty"), seen)
    }

    @Test
    fun aThrowingObserverNeverFailsThePull() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val resolver = OnDemandResolutionImpl(
            relay = MockSyncRelay(proxy.tContext),
            syncProcessorHolder = SyncProcessorHolder(),
            onPull = { _, _, _ -> error("bookkeeping exploded") },
        )
        val entity = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "row-1")

        val result = resolver.resolve(proxy.executionContext, entity, "test/onDemandProperty", null)

        assertTrue(result is TResult.Success, "the fetch's outcome is the fetch's, not the hook's")
    }
}
