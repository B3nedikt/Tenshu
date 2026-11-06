package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins down `ExecutionContext.saveData`/`deleteData` against
 * `ConcurrentModificationException` when an observer's callback
 * transitively calls [ExecutionContext.addDataObserver]. The production
 * shape: stream emissions route through
 * [materialisePendingOnDemandProperties] → `OnDemandResolutionImpl.resolve`,
 * and a `SyncProcessor`/`TombstoneRegistry` constructed there calls
 * `addDataObserver(this)` from its init block mid-iteration. `saveData` /
 * `deleteData` snapshot `observerRegistry` before iterating; per-call
 * `TombstoneRegistry` construction is avoided separately by
 * `org.tenshu.sync.proxy.SyncProcessorHolder`.
 *
 * Reproduces the same shape directly: an observer whose `onDataSaved`
 * registers a new observer.
 */
class ExecutionContextObserverReentrancyTest {

    @Test
    fun saveData_toleratesObserverAddedDuringIteration() {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext

        val addedObservers = mutableListOf<DataObserver>()
        val outer = object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                val inner = object : DataObserver {
                    override fun onDataSaved(data: Data, origin: WriteOrigin) {}
                    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {}
                }
                addedObservers += inner
                testProxy.executionContext.addDataObserver(inner)
            }

            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {}
        }
        testProxy.executionContext.addDataObserver(outer)

        // Without the snapshot in `saveData` this throws CME on the
        // `observerRegistry.forEach`.
        testProxy.executionContext.saveData(
            SyntheticTestData.Factory.create(context, name = "x", value = 1L)
        )

        assertTrue(
            addedObservers.size == 1,
            "Outer observer should have added exactly one inner observer"
        )
    }

    @Test
    fun deleteData_toleratesObserverAddedDuringIteration() {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val context = testProxy.tContext

        val outer = object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {}
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
                testProxy.executionContext.addDataObserver(object : DataObserver {
                    override fun onDataSaved(data: Data, origin: WriteOrigin) {}
                    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {}
                })
            }
        }
        testProxy.executionContext.addDataObserver(outer)

        val dataId = DataId.StringDataId.create(
            context = context,
            type = SyntheticTestData.ID,
            keyValue = "x"
        )
        testProxy.executionContext.deleteData(dataId)
    }
}
