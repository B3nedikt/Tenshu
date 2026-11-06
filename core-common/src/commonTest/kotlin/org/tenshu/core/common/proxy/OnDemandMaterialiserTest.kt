package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins down [materialisePendingOnDemandProperties] — the shared helper
 * that `Get` and the `Stream`/`Flow` observer both call to resolve
 * [PendingOnDemandProperty] placeholders before the projected entity
 * leaves the proxy process.
 *
 * Resolution running only inside `MaybeGetImplementation` would let
 * `flow()` emissions carry unresolved placeholders across the IPC boundary,
 * where the SDK side receives an inert marker the UI cannot interpret. This
 * pins that the same resolution machinery runs for list emissions too.
 */
class OnDemandMaterialiserTest {

    private fun stubResolver(
        calls: MutableList<Triple<DataId, String, TList<Data>?>>
    ): OnDemandResolution = object : OnDemandResolution {
        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?
        ): TResult<Data?> {
            calls += Triple(entityId, propertyId, params)
            return TResult.Success.create<Data?>(executionContext.context, null)
        }
    }

    @Test
    fun resolves_pendingMarker_onSingleEntity() = runTest {
        val calls = mutableListOf<Triple<DataId, String, TList<Data>?>>()
        val testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            additionalComponents = mapOf(OnDemandResolution.ID to stubResolver(calls))
        )
        val context = testProxy.tContext

        val entityId = DataId.StringDataId.create(
            context = context,
            type = SyntheticTestData.ID,
            keyValue = "single"
        )
        val pending = PendingOnDemandProperty.create(
            context = context,
            entityId = entityId,
            propertyId = "test/onDemandProperty",
            params = null
        )
        val entity = context.dataFactory().createEntity<Composite>(
            type = SyntheticTestData.ID,
            fields = emptyMap<String, Data>(),
            relations = mapOf<String, Data>("test/onDemandProperty" to pending)
        )

        materialisePendingOnDemandProperties(testProxy.executionContext, entity)

        assertEquals(1, calls.size, "resolver should fire once for the pending marker")
        assertEquals(entityId, calls[0].first)
        assertEquals("test/onDemandProperty", calls[0].second)
    }

    @Test
    fun resolves_pendingMarkers_acrossListEmission() = runTest {
        val calls = mutableListOf<Triple<DataId, String, TList<Data>?>>()
        val testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            additionalComponents = mapOf(OnDemandResolution.ID to stubResolver(calls))
        )
        val context = testProxy.tContext

        val items = (0..2).map { i ->
            val entityId = DataId.StringDataId.create(
                context = context,
                type = SyntheticTestData.ID,
                keyValue = "item-$i"
            )
            val pending = PendingOnDemandProperty.create(
                context = context,
                entityId = entityId,
                propertyId = "test/onDemandProperty",
                params = null
            )
            context.dataFactory().createEntity<Composite>(
                type = SyntheticTestData.ID,
                fields = emptyMap<String, Data>(),
                relations = mapOf<String, Data>("test/onDemandProperty" to pending)
            ) as Data
        }

        val list = items.toTList(context)

        materialisePendingOnDemandProperties(testProxy.executionContext, list)

        assertEquals(
            3, calls.size,
            "resolver should fire once per list element carrying a pending marker"
        )
        val keys = calls.map { (it.first as DataId.StringDataId).keyValue.get() }.toSet()
        assertEquals(setOf("item-0", "item-1", "item-2"), keys)
    }

    @Test
    fun skipsDeferredMarkers_leavesThemInPlace() = runTest {
        // A PendingDeferredProperty is NOT an OnDemand marker: the one-shot
        // terminal must not await anything for it (`core-deferred-properties.md`
        // §4 — deferred reads are null on one-shot; the stream layer patches).
        val calls = mutableListOf<Triple<DataId, String, TList<Data>?>>()
        val testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            additionalComponents = mapOf(OnDemandResolution.ID to stubResolver(calls))
        )
        val context = testProxy.tContext

        val entityId = DataId.StringDataId.create(
            context = context,
            type = SyntheticTestData.ID,
            keyValue = "deferred"
        )
        val marker = PendingDeferredProperty.create(
            context = context,
            entityId = entityId,
            propertyId = "test/deferredProperty",
            params = null
        )
        val entity = context.dataFactory().createEntity<Composite>(
            type = SyntheticTestData.ID,
            fields = emptyMap<String, Data>(),
            relations = mapOf<String, Data>("test/deferredProperty" to marker)
        )

        val result = materialisePendingOnDemandProperties(testProxy.executionContext, entity)

        assertTrue(calls.isEmpty(), "the OnDemand resolver must never fire for a deferred marker")
        val survived = (result as org.tenshu.core.entity.Entity<*>).properties.values
            .filterIsInstance<PendingDeferredProperty>()
        assertEquals(1, survived.size, "the deferred marker must survive materialisation untouched")
    }

    @Test
    fun skipsItems_withoutPendingMarkers() = runTest {
        val calls = mutableListOf<Triple<DataId, String, TList<Data>?>>()
        val testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            additionalComponents = mapOf(OnDemandResolution.ID to stubResolver(calls))
        )
        val context = testProxy.tContext

        val plain = SyntheticTestData.Factory.create(context, "no-marker", 1L)

        materialisePendingOnDemandProperties(testProxy.executionContext, plain)

        assertTrue(calls.isEmpty(), "resolver must not be called for entities without markers")
    }
}
