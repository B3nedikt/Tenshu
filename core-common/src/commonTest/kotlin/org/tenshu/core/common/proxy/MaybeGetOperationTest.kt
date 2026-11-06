package org.tenshu.core.common.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Get
import org.tenshu.core.Maybe
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.maybe.MaybeRo
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.dataFactory
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaybeGetOperationTest {

    @Test
    fun testGetReturnsSomeValue() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val value = "test-value".toTString(context)
        val some = Maybe.Some.create(context, value)

        val result = testProxy.executionContext.execute<Data>(
            operationId = Get.ID,
            caller = some,
            params = emptyMap()
        )

        assertEquals(value, result)
    }

    @Test
    fun testGetReturnsEmpty() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val empty = Maybe.Empty.create<Data>(context)

        val result: Data? = testProxy.executionContext.execute<Data>(
            operationId = Get.ID,
            caller = empty,
            params = emptyMap()
        )

        assertNull(result)
    }

    @Test
    fun getStaysSynchronousWhenOnlyDeferredMarkersPresent() = runTest {
        // `core-deferred-properties.md` §4: a deferred property never blocks a
        // one-shot read. An entity whose only pending marker is a
        // PendingDeferredProperty must take the SYNC path (no AsyncReference,
        // no OnDemand fetch) — the marker crosses IPC inert and the client's
        // `Property.decode` collapses it to null.
        val testProxy = TestProxy(
            schemaRegistry = org.tenshu.core.common.createSyntheticTestRegistry()
        )
        val context = testProxy.tContext

        val marker = PendingDeferredProperty.create(
            context = context,
            entityId = org.tenshu.core.entity.DataId.StringDataId.create(
                context = context,
                type = org.tenshu.core.common.SyntheticTestData.ID,
                keyValue = "deferred-row"
            ),
            propertyId = "test/deferredProperty",
            params = null
        )
        val entity = context.dataFactory().createEntity<org.tenshu.core.Composite>(
            type = org.tenshu.core.common.SyntheticTestData.ID,
            fields = emptyMap<String, Data>(),
            relations = mapOf<String, Data>("test/deferredProperty" to marker)
        )
        val some = Maybe.Some.create(context, entity)

        val result = testProxy.executionContext.execute<Data>(
            operationId = Get.ID,
            caller = some,
            params = emptyMap()
        )

        assertTrue(result !is AsyncReference, "deferred markers must not trigger the async OnDemand path")
        val survived = (result as org.tenshu.core.entity.Entity<*>).properties.values
            .filterIsInstance<PendingDeferredProperty>()
        assertEquals(1, survived.size, "the marker rides the sync return untouched")
    }

    @Test
    fun testEvaluateCallstackWithGet() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext

        val value = "test-value".toTString(context)
        val some = Maybe.Some.create(context, value)

        val callstack = listOf(
            OperationDto(params = emptyMap(), schema = Get.Schema)
        )

        val result = evaluateCallstack(
            executionContext = testProxy.executionContext,
            contextData = some,
            callstack = callstack
        )

        assertEquals(value, result)
    }

    @Test
    fun testMaybeRoGetIntegration() = runTest {
        val testProxy = TestProxy()
        val context = testProxy.tContext
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val value = "test-value".toTString(context)
        val some = Maybe.Some.create(context, value)

        val someRo = MaybeRo<TString>(
            requestChain = TaskRequestChain(executor, context).addArray(some as TArray),
            context = context,
            schema = Maybe.Some.Schema
        )

        val resultValue = someRo.resolve()
        assertEquals("test-value", resultValue.getOrNull()?.get())
    }

    /**
     * Pins down the OnDemand resolve-trigger-resolve loop:
     *
     * 1. An entity carries a [org.tenshu.core.common.proxy.PendingOnDemandProperty]
     *    in its `properties` map — what [org.tenshu.core.common.proxy.SelectImplementation]
     *    puts there when local resolve yields null for a `Syncable(OnDemand)` relation.
     * 2. The synchronous `execute(Get, …)` path detects the marker and returns an
     *    [org.tenshu.core.common.proxy.AsyncReference]. The sync interactor would
     *    surface this as `SynchronousViolationException`; the suspend
     *    [LocalTaskExecutor.executeAsync] path is what production uses, and it
     *    awaits the call and returns the materialised value.
     * 3. The stub [org.tenshu.core.common.proxy.OnDemandResolution] component
     *    records that `resolve(…)` was dispatched with the marker's
     *    `entityId` + `propertyId` + `params` — proving the wiring is right
     *    without requiring a real sync relay.
     */
    @Test
    fun getDispatchesOnDemandResolutionForPendingMarkers() = runTest {
        val resolveCalls = mutableListOf<Triple<org.tenshu.core.entity.DataId, String, org.tenshu.core.collection.list.TList<Data>?>>()
        val stubResolution = object : org.tenshu.core.common.proxy.OnDemandResolution {
            override suspend fun resolve(
                executionContext: org.tenshu.core.common.proxy.ExecutionContext,
                entityId: org.tenshu.core.entity.DataId,
                propertyId: String,
                params: org.tenshu.core.collection.list.TList<Data>?
            ): TResult<Data?> {
                resolveCalls += Triple(entityId, propertyId, params)
                return TResult.Success.create<Data?>(executionContext.context, null)
            }
        }
        val testProxy = TestProxy(
            schemaRegistry = org.tenshu.core.common.createSyntheticTestRegistry(),
            additionalComponents = mapOf(
                org.tenshu.core.common.proxy.OnDemandResolution.ID to stubResolution
            )
        )
        val context = testProxy.tContext

        val entityId = org.tenshu.core.entity.DataId.StringDataId.create(
            context = context,
            type = org.tenshu.core.common.SyntheticTestData.ID,
            keyValue = "test-key"
        )
        val pending = org.tenshu.core.common.proxy.PendingOnDemandProperty.create(
            context = context,
            entityId = entityId,
            propertyId = "test/onDemandProperty",
            params = null
        )
        val entityWithPending = context.dataFactory().createEntity<org.tenshu.core.Composite>(
            type = org.tenshu.core.common.SyntheticTestData.ID,
            fields = emptyMap<String, Data>(),
            relations = mapOf<String, Data>("test/onDemandProperty" to pending)
        )
        val some = Maybe.Some.create<Data>(context, entityWithPending)

        // Sync `execute` returns an AsyncReference because pending markers exist.
        val syncResult = testProxy.executionContext.execute<Data>(
            operationId = org.tenshu.core.Get.ID,
            caller = some,
            params = emptyMap()
        )
        val asyncRef = syncResult as? org.tenshu.core.common.proxy.AsyncReference
        assertTrue(asyncRef != null, "Get must return AsyncReference for pending markers")

        // Await the async call — confirms the stub was dispatched.
        asyncRef.asyncCall.await()
        assertEquals(
            1,
            resolveCalls.size,
            "OnDemandResolution.resolve should fire exactly once per pending marker"
        )
        assertEquals(entityId, resolveCalls[0].first)
        assertEquals("test/onDemandProperty", resolveCalls[0].second)
    }

    /**
     * Sibling assertion: when there are NO pending markers, `Get` stays
     * fully synchronous — no AsyncReference, no OnDemandResolution call.
     * The sync-interactor path depends on this.
     */
    @Test
    fun getStaysSyncWithoutPendingMarkers() = runTest {
        var resolveCalled = false
        val stubResolution = object : org.tenshu.core.common.proxy.OnDemandResolution {
            override suspend fun resolve(
                executionContext: org.tenshu.core.common.proxy.ExecutionContext,
                entityId: org.tenshu.core.entity.DataId,
                propertyId: String,
                params: org.tenshu.core.collection.list.TList<Data>?
            ): TResult<Data?> {
                resolveCalled = true
                return TResult.Success.create<Data?>(executionContext.context, null)
            }
        }
        val testProxy = TestProxy(
            additionalComponents = mapOf(
                org.tenshu.core.common.proxy.OnDemandResolution.ID to stubResolution
            )
        )
        val context = testProxy.tContext

        val value = "plain-value".toTString(context)
        val some = Maybe.Some.create(context, value)

        val result = testProxy.executionContext.execute<Data>(
            operationId = org.tenshu.core.Get.ID,
            caller = some,
            params = emptyMap()
        )
        assertEquals(value, result)
        assertEquals(false, resolveCalled, "No pending markers → no OnDemandResolution call")
    }
}
