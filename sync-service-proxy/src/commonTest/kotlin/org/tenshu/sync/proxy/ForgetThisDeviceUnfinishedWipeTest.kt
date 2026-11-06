package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.StoreHandleFenced
import org.tenshu.core.common.proxy.db.StoreWipeNotFinished
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ios-shared-store-sqlite.md` §13's rule, §5.1 steps 3 and 4 (Appendix B.14 (192)): a wipe that
 * **started and did not finish** is a third outcome of `ForgetThisDevice`, never folded into "not
 * dropped", and a store wiped in the flow takes its cursor rows with it.
 *
 * - ⚠⚠ unfinished: custody is **kept** (the store is still present behind its marker; D3 forbids
 *   custody gone over it) and the outcome says "not finished" with the `⚠` marker;
 * - ⚠⚠ finished: custody goes and the cursors are **still reset**, on every platform: a pull tick
 *   between the wipe and the purge can write a non-zero cursor into the fresh store, and the reset
 *   is what closes that window (the reason it is never skipped);
 * - finished on a store whose handle the wipe fenced (iOS's shared store): the reset's refusal,
 *   `StoreHandleFenced`, is told, never thrown past the purge;
 * - not wipeable: as before, the cursors are reset and custody goes.
 */
class ForgetThisDeviceUnfinishedWipeTest {

    /**
     * A store whose wipe ends as the test says. FINISHED: a pull tick lands a cursor in the fresh
     * store right after the wipe. FENCED: after the wipe every write is refused as a fenced handle's.
     */
    private class Store(private val wipe: Wipe) : InMemoryObjectStore() {
        enum class Wipe { UNFINISHED, FINISHED, FENCED, FAILS }

        class Fenced : StoreHandleFenced("made up: the wiped handle is fenced")

        var fenced = false
        var writesAfterWipe = 0

        override suspend fun wipeAllObjects() {
            when (wipe) {
                Wipe.UNFINISHED -> throw StoreWipeNotFinished("made up: stopped after its first chunk")
                Wipe.FAILS -> throw IllegalStateException("made up: the first chunk did not commit")
                Wipe.FINISHED -> {
                    super.wipeAllObjects()
                    storeSyncCursor(41L) // the tick in the window between the wipe and the purge
                }
                Wipe.FENCED -> {
                    super.wipeAllObjects()
                    fenced = true
                }
            }
        }

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            if (fenced) {
                writesAfterWipe++
                throw Fenced()
            }
            super.storeObject(dbEntry)
        }
    }

    private class Revoke : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T = null as T
    }

    private class Run(val outcome: String, val delegate: InMemorySecureStorageDelegate, val store: Store, val syncManager: SyncManagerImpl)

    private suspend fun forget(wipe: Store.Wipe): Run {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val delegate = InMemorySecureStorageDelegate()
        val store = Store(wipe)
        val syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = delegate,
            context = testProxy.tContext,
            objectStore = store,
        )
        syncManager.initialize("mock phrase")
        store.storeSyncCursor(41L)
        // The id is derived from the A4 key on first use (workstream B) — a device that syncs has used it.
        syncManager.currentDeviceId
        assertNotNull(delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "precondition: an identity at rest")
        var ticks = 0L
        val impl = ForgetThisDeviceImplementation(
            syncManager = syncManager,
            revokeDevice = { _ -> Revoke() },
            nowMs = { ticks += 150L; ticks },
        )
        val outcome = impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = null,
        ).get().orEmpty()
        return Run(outcome, delegate, store, syncManager)
    }

    @Test
    fun anUnfinishedWipeKeepsCustodyAndSaysNotFinished() = runTest {
        val run = forget(Store.Wipe.UNFINISHED)
        assertNotNull(run.delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "D3: custody kept over a store still present")
        assertTrue(run.outcome.contains("not finished"), run.outcome)
        assertTrue(run.outcome.contains('⚠'), "the Welcome notice's marker: ${run.outcome}")
        assertFalse(run.outcome.contains("forgotten"), run.outcome)
    }

    @Test
    fun aFinishedWipeStillResetsTheCursorsAndPurgesCustody() = runTest {
        val run = forget(Store.Wipe.FINISHED)
        assertNull(run.delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "custody purged")
        assertTrue(run.outcome.contains("local store dropped"), run.outcome)
        assertEquals(0L, run.store.loadSyncCursor(), "the cursor a tick wrote after the wipe was reset")
    }

    @Test
    fun aFencedHandleAfterItsWipeNeverThrowsPastThePurge() = runTest {
        val run = forget(Store.Wipe.FENCED)
        assertTrue(run.store.writesAfterWipe > 0, "the reset was attempted")
        assertNull(run.delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "custody purged")
        assertTrue(run.outcome.contains("local store dropped"), run.outcome)
    }

    @Test
    fun aWipeThatDidNotStartBehavesAsBeforeTheCursorsResetAndCustodyGoes() = runTest {
        val run = forget(Store.Wipe.FAILS)
        assertNull(run.delegate.getString(SyncManagerImpl.Keys.DEVICE_ID), "today's behaviour for a wipe that did not happen")
        assertTrue(run.outcome.contains("NOT dropped"), run.outcome)
        assertEquals(0L, run.store.loadSyncCursor(), "the cursor was reset")
    }

    @Test
    fun onlyAFencedHandlesRefusalIsTolerated() {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val store = object : InMemoryObjectStore() {
            override fun storeObject(dbEntry: ObjectStore.DbEntry) = throw IllegalStateException("made up: any other failure")
        }
        val syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = store,
        )
        assertFailsWith<IllegalStateException>("off iOS the reset fails exactly as before") { syncManager.forgetThisDevice() }
    }
}
