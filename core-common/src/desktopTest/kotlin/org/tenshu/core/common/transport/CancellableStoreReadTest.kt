package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.entity.DataId
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ A cancellable read must stop **inside the store's materialisation**, not only after it
 * (`core-background-processing.md` §6.2.1).
 *
 * `DatabaseImpl.getArrayData` reads and deserialises every member of a stored array **eagerly, under
 * the database lock**, and only then hands back a `Sequence`. A checkpoint on that sequence fires
 * after the whole read — so for a real stored list it would stop only the cheap loop that
 * follows. `CancellableReadTest`'s rig reads an inline `TList`, which never takes that path;
 * this one reads a stored array on the **production store pair** — `SqliteObjectStore` behind
 * `ReentrantLockDatabaseLock` — with a per-row delay injected at the store, so "rows read after the
 * cancel" is the measurable thing.
 *
 * It also pins what a throw from inside the lock must leave behind: the lock released (a write
 * goes through at once) and nothing half-read (the next full read returns every row).
 */
class CancellableStoreReadTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanup.reversed().forEach { runCatching(it) }
        scope.cancel()
    }

    /** The real store, with a delay and a counter on every row read while [armed]. */
    private class SlowStore(private val delegate: ObjectStore, private val rowMs: Long) : ObjectStore by delegate {
        val armed = AtomicBoolean(false)
        val rowsRead = AtomicInteger()
        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            if (armed.get()) {
                rowsRead.incrementAndGet()
                Thread.sleep(rowMs)
            }
            return delegate.getObject(dataIdHash)
        }
    }

    private val rows = 600

    private suspend fun storeBackedRig(): Pair<CancellableReadRig, SlowStore> {
        val path = Files.createTempDirectory("cancel-store").resolve("store.db").toString()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanup += { storeScope.cancel() }
        val store = SlowStore(openSqliteObjectStore(path, storeScope), rowMs = 5)
        val rig = CancellableReadRig(
            schemaRegistry = createSyntheticTestRegistry(),
            objectStore = store,
            lock = ReentrantLockDatabaseLock(),
        )
        val ctx = rig.proxy.tContext
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(ctx, TList.ID, "test/cancellable-store-read/rows"),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        repeat(rows) { i ->
            rig.proxy.executionContext.database.addToArray(ref, SyntheticTestData.Factory.create(ctx, "row-$i", i.toLong()))
        }
        rig.storedArray = ref
        return rig to store
    }

    @Test
    fun aCancelledReadStopsInsideTheStoresMaterialisation() = runBlocking {
        val (rig, store) = storeBackedRig()
        val transport = BinderShapedTransport(rig.proxy)
        val executor = rig.executor(transport, scope)
        // Warm the session (the announce) before arming the counter.
        withTimeout(10_000) { executor.executeAsync(rig.steps(0)) }

        store.armed.set(true)
        val caller = scope.launch {
            try {
                executor.executeAsyncCancellableRead(rig.storedRead())
            } catch (_: CancellationException) {
            }
        }
        withTimeout(5_000) { while (store.rowsRead.get() < 20) delay(5) }
        assertNotNull(withTimeoutOrNull(3_000) { caller.cancelAndJoin() }, "the caller could not unwind")
        val atCancel = store.rowsRead.get()
        delay(500) // ~100 more rows if the store is still materialising
        val after = store.rowsRead.get() - atCancel
        assertTrue(
            after <= 10,
            "the store kept reading rows after the read was cancelled: $after more in 500 ms " +
                "(${store.rowsRead.get()} of $rows) — the checkpoint never reached the locked materialisation",
        )

        // A throw from inside the lock must leave nothing behind: the lock is free…
        store.armed.set(false)
        val ctx = rig.proxy.tContext
        val write = scope.launch {
            rig.proxy.executionContext.database.addToArray(
                rig.storedArray!!,
                SyntheticTestData.Factory.create(ctx, "after-cancel", -1),
            )
        }
        assertNotNull(withTimeoutOrNull(5_000) { write.join() }, "the database lock was left held by the cancelled read")
        // …and the next read sees every row, none of the abandoned read's state.
        assertEquals(rows + 1, rig.proxy.executionContext.database.getArrayData(rig.storedArray!!)!!.count())
    }

    /** The default stays: without a cancel, the stored read is served whole. */
    @Test
    fun anUncancelledStoredReadIsServedWhole() = runBlocking {
        val (rig, store) = storeBackedRig()
        val executor = rig.executor(BinderShapedTransport(rig.proxy), scope)
        withTimeout(10_000) { executor.executeAsync(rig.steps(0)) }
        store.armed.set(true)
        val result = withTimeout(20_000) { executor.executeAsyncCancellableRead(rig.storedRead()) }
        assertTrue(result is org.tenshu.core.TResult.Success<*>, "got $result")
        assertTrue(store.rowsRead.get() >= rows, "read ${store.rowsRead.get()} of $rows rows")
    }
}
