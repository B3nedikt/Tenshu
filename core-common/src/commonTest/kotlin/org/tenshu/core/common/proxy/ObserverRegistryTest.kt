package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the [ObserverRegistry] contract — in particular the snapshot-then-iterate
 * invariant: an observer's `onDataSaved` / `onDataDeleted`
 * callback can transitively call [ObserverRegistry.add] (e.g. via a stream
 * emission whose materialiser dispatches an `OnDemandResolution.resolve`,
 * which constructs a `SyncProcessor`/`TombstoneRegistry` that registers
 * itself). Without the snapshot the mutation lands on the underlying
 * `ArrayList` mid-iteration and throws `ConcurrentModificationException`.
 *
 * The load-bearing path is `SyncObserver`, `DeviceRelationMaintainer`,
 * `ParentFilesRelationMaintainer` registration during stream-driven
 * `Database.saveData`. Integration tests exercise the same paths end-to-end;
 * the cases here pin the invariant in isolation so a future refactor that
 * removes `observers.toList()` doesn't silently break it.
 */
class ObserverRegistryTest {

    @Test
    fun add_then_onDataSaved_firesObserver() {
        val registry = ObserverRegistry()
        val seen = mutableListOf<Data>()
        registry.add(recordingObserver(onSave = { seen.add(it) }))

        val data = "hello".toTString()
        registry.onDataSaved(data, WriteOrigin.USER)

        assertEquals(listOf<Data>(data), seen)
    }

    @Test
    fun add_then_onDataDeleted_firesObserver() {
        val registry = ObserverRegistry()
        val seen = mutableListOf<DataId>()
        registry.add(recordingObserver(onDelete = { seen.add(it) }))

        val dataId = DataId.StringDataId.create(type = "tenshu/file", keyValue = "/x")
        registry.onDataDeleted(dataId, WriteOrigin.USER)

        assertEquals(1, seen.size)
        assertEquals("/x", (seen.single() as DataId.StringDataId).keyValue.get())
    }

    @Test
    fun observer_registeringAnotherObserverDuringOnDataSaved_doesNotCME() {
        // An observer's `onDataSaved` calling `add(...)` must not mutate the
        // list being iterated. Snapshot-then-iterate is the
        // documented mitigation.
        val registry = ObserverRegistry()
        val firstFires = mutableListOf<Data>()
        val secondFires = mutableListOf<Data>()

        registry.add(recordingObserver(onSave = { data ->
            firstFires.add(data)
            // Reentrant add — would CME without the snapshot.
            registry.add(recordingObserver(onSave = { secondFires.add(it) }))
        }))

        val first = "first".toTString()
        registry.onDataSaved(first, WriteOrigin.USER)

        // The first observer fired (no CME).
        assertEquals(listOf<Data>(first), firstFires)
        // The newly-registered observer was not retroactively fired for the
        // current dispatch (it was added *during* iteration of the snapshot).
        assertEquals(emptyList<Data>(), secondFires)

        // A subsequent dispatch sees the new observer.
        val second = "second".toTString()
        registry.onDataSaved(second, WriteOrigin.USER)
        assertEquals(listOf<Data>(first, second), firstFires)
        assertEquals(listOf<Data>(second), secondFires)
    }

    @Test
    fun observer_registeringAnotherObserverDuringOnDataDeleted_doesNotCME() {
        // Same invariant as the save path, on the delete path.
        val registry = ObserverRegistry()
        val firstFires = mutableListOf<DataId>()
        val secondFires = mutableListOf<DataId>()

        registry.add(recordingObserver(onDelete = { id ->
            firstFires.add(id)
            registry.add(recordingObserver(onDelete = { secondFires.add(it) }))
        }))

        val id1 = DataId.StringDataId.create(type = "tenshu/file", keyValue = "/a")
        registry.onDataDeleted(id1, WriteOrigin.USER)

        assertEquals(1, firstFires.size)
        assertEquals(emptyList<DataId>(), secondFires)

        val id2 = DataId.StringDataId.create(type = "tenshu/file", keyValue = "/b")
        registry.onDataDeleted(id2, WriteOrigin.USER)
        assertEquals(2, firstFires.size)
        assertEquals(1, secondFires.size)
    }

    @Test
    fun clear_dropsAllObservers() {
        val registry = ObserverRegistry()
        var fires = 0
        registry.add(recordingObserver(onSave = { fires++ }))
        registry.add(recordingObserver(onSave = { fires++ }))

        registry.clear()
        registry.onDataSaved("after-clear".toTString(), WriteOrigin.USER)

        assertEquals(0, fires, "cleared observers must not be called")
    }

    private fun recordingObserver(
        onSave: (Data) -> Unit = {},
        onDelete: (DataId) -> Unit = {},
    ): DataObserver = object : DataObserver {
        override fun onDataSaved(data: Data, origin: WriteOrigin) = onSave(data)
        override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = onDelete(dataId)
    }
}
