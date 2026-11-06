package org.tenshu.sync.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.SyncService
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.isControlHosted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-writer [RuntimeIdentity] row (`core-schema-skew-surface.md` §8.7), at the
 * relation and through the client chain a banner reads.
 *
 * ⚠⚠ The tests that matter here run **two runtimes over one store**, the second launched last.
 * That is iOS route 1: every first-party app runs its own proxy over one App-Group replica. A
 * single-runtime test would pass on a singleton row too, because with one writer the last writer is
 * always the reader's own.
 */
class RuntimeIdentityRelationTest {

    // ---- two runtimes over one store ---------------------------------------------------------

    @Test
    fun controlLaunchedLast_doesNotTellFitThatControlHostsIt() = runTest {
        val store = InMemoryObjectStore()
        val fit = proxy(store)
        fit.executionContext.initRuntimeIdentity(FIT, appVersion = "1.4")
        val control = proxy(store)
        control.executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID, appVersion = "2.0")

        val fitsOwn = fit.service().runtimeIdentity(FIT).first()
        assertEquals(false, fitsOwn.isControlHosted(), "Fit's own proxy hosts Fit, whoever launched last")
        assertEquals(FIT, fitsOwn?.appId?.get())
        assertEquals("1.4", fitsOwn?.appVersion?.get(), "and it names Fit's build, not Control's")

        assertEquals(true, control.service().runtimeIdentity(RuntimeIdentity.CONTROL_APP_ID).first().isControlHosted())
    }

    @Test
    fun fitLaunchedLast_doesNotTellControlThatFitHostsIt() = runTest {
        val store = InMemoryObjectStore()
        val control = proxy(store)
        control.executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID)
        val fit = proxy(store)
        fit.executionContext.initRuntimeIdentity(FIT)

        assertEquals(true, control.service().runtimeIdentity(RuntimeIdentity.CONTROL_APP_ID).first().isControlHosted())
        assertEquals(false, fit.service().runtimeIdentity(FIT).first().isControlHosted())
    }

    @Test
    fun threeRuntimesOverOneStore_eachRowIsKeptAndEnumerable() = runTest {
        val store = InMemoryObjectStore()
        val writers = listOf(FIT, MAPS, RuntimeIdentity.CONTROL_APP_ID)
        val proxies = writers.map { writer -> proxy(store).also { it.executionContext.initRuntimeIdentity(writer) } }

        assertEquals(writers.toSet(), proxies.last().rows().map { it.key.get() }.toSet())
        writers.zip(proxies).forEach { (writer, proxy) ->
            assertEquals(writer, proxy.service().runtimeIdentity(writer).first()?.appId?.get())
        }
    }

    @Test
    fun severalWriters_theUnkeyedReadSaysUnknown_ratherThanPickingOne() = runTest {
        val store = InMemoryObjectStore()
        proxy(store).executionContext.initRuntimeIdentity(FIT)
        val control = proxy(store)
        control.executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID)

        assertNull(control.service().runtimeIdentity().first(), "two rows: any one could be a sibling's")
    }

    // ---- one writer: Android and desktop -----------------------------------------------------

    @Test
    fun oneWriter_theUnkeyedReadGetsControlsRow() = runTest {
        // An Android or desktop app over Control's IPC does not know who hosts it, so it reads
        // without a key — and there is only Control's row to read.
        val control = proxy(InMemoryObjectStore())
        control.executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID, appVersion = "2.0")

        val identity = control.service().runtimeIdentity().first()
        assertEquals(true, identity.isControlHosted())
        assertEquals("2.0", identity?.appVersion?.get())
    }

    @Test
    fun aRelaunch_replacesItsOwnRow_ratherThanAppendingASecond() = runTest {
        val store = InMemoryObjectStore()
        proxy(store).executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID, appVersion = "2.0")
        val relaunched = proxy(store)
        relaunched.executionContext.initRuntimeIdentity(RuntimeIdentity.CONTROL_APP_ID, appVersion = "2.1")

        assertEquals(1, relaunched.rows().size)
        assertEquals("2.1", relaunched.service().runtimeIdentity().first()?.appVersion?.get())
    }

    @Test
    fun noAppId_publishesNothing() = runTest {
        val proxy = proxy(InMemoryObjectStore())

        proxy.executionContext.initRuntimeIdentity(appId = null)
        proxy.executionContext.initRuntimeIdentity(appId = " ")

        assertEquals(emptyList(), proxy.rows())
        assertNull(proxy.service().runtimeIdentity().first())
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun proxy(store: ObjectStore): TestProxy {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), objectStore = store)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)
        return proxy
    }

    private fun TestProxy.service(): SyncService {
        val executor = LocalTaskExecutor(executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = tContext,
        ).getService(SyncService.ID)
    }

    private fun TestProxy.relation() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = tContext,
            type = TList.ID,
            relationId = SyncService.Relations.RUNTIME_IDENTITY,
        ),
        isEntity = false,
        elementSchemaId = RuntimeIdentity.ID,
    )

    private fun TestProxy.rows(): List<RuntimeIdentity> =
        executionContext.database.getArrayData(relation())?.mapNotNull { it as? RuntimeIdentity }?.toList().orEmpty()

    private companion object {
        const val FIT = "app.tenshu.fit.ios"
        const val MAPS = "app.tenshu.maps.ios"
    }
}
