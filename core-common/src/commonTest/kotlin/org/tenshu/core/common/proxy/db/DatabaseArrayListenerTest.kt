package org.tenshu.core.common.proxy.db

import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Facet B (loop/DB/stream unification): event-driven array listeners.
 * `addToArray`/`removeFromArray` fire the listener **directly** with the exact
 * value/dataId + [WriteOrigin] from the call site — not via the old per-write
 * O(n²) full-array diff + re-deserialise.
 *
 * Pins three properties:
 * - **direct fire** — `onAdded` receives the *same* instance passed to
 *   `addToArray` (the diff path re-deserialised a fresh copy);
 * - **no double-fire** — exactly one callback per targeted write (the snapshot
 *   pre-update suppresses the diff fallback; reintroducing it would make this
 *   assert see 2);
 * - **origin propagation** — the caller's [WriteOrigin] reaches the callback.
 *
 * The saveData-driven bulk-rewrite *fallback* path (a `saveData(folder)` that
 * rewrites an embedded list) is covered by `:file-service-proxy` `DatabaseTest`.
 */
class DatabaseArrayListenerTest {

    private fun arrayRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = TList.ID,
            relationId = "test/array"
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID
    )

    @Test
    fun addToArray_firesOnAddedExactlyOnce_withSameInstanceAndOrigin() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        val added = mutableListOf<Pair<Data, WriteOrigin>>()
        proxy.database.setArrayListener(
            reference = ref,
            onAdded = { value, origin -> added += value to origin },
            onRemoved = { _, _ -> }
        )

        val item = SyntheticTestData.Factory.create(proxy.tContext, "first", 1L)
        proxy.database.addToArray(ref, item, WriteOrigin.SYNC_INGEST)

        assertEquals(1, added.size, "exactly one onAdded per addToArray (no double-fire)")
        assertSame(item, added[0].first, "onAdded gets the exact value, not a re-deserialised copy")
        assertEquals(WriteOrigin.SYNC_INGEST, added[0].second, "origin propagates to onAdded")
    }

    @Test
    fun removeFromArray_firesOnRemovedOnce_andNoFireForNonMember() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        val item = SyntheticTestData.Factory.create(proxy.tContext, "first", 1L)
        proxy.database.addToArray(ref, item)
        val itemId = proxy.executionContext.getDataId(item)!!

        val removed = mutableListOf<Pair<DataId, WriteOrigin>>()
        proxy.database.setArrayListener(
            reference = ref,
            onAdded = { _, _ -> },
            onRemoved = { id, origin -> removed += id to origin }
        )

        proxy.database.removeFromArray(ref, itemId, WriteOrigin.USER)
        assertEquals(1, removed.size, "exactly one onRemoved for a present entry")
        assertEquals(WriteOrigin.USER, removed[0].second, "origin propagates to onRemoved")

        // Removing an entry that isn't in the array fires nothing (mirrors the
        // diff path's "no change ⇒ no event").
        val absentId = proxy.executionContext.getDataId(
            SyntheticTestData.Factory.create(proxy.tContext, "absent", 99L)
        )!!
        proxy.database.removeFromArray(ref, absentId, WriteOrigin.USER)
        assertEquals(1, removed.size, "removing a non-member must not fire onRemoved")
    }

    @Test
    fun listenerRemoved_stopsFiring() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        var fires = 0
        val handle = proxy.database.setArrayListener(ref, onAdded = { _, _ -> fires++ }, onRemoved = { _, _ -> })
        proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, "a", 1L))
        assertEquals(1, fires)

        proxy.database.removeArrayListener(handle)
        proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, "b", 2L))
        assertTrue(fires == 1, "no fire after removeArrayListener")
    }

    /**
     * Multi-listener (fix for the stale cross-session reactive read, e.g. Drive's storage
     * banner): two listeners registered on the SAME array — as two client sessions do over the
     * one shared proxy DB — must BOTH fire; a second registration must not silence the first, and
     * removing one handle must leave the other firing.
     */
    @Test
    fun multipleListeners_onSameArray_allFire_andRemoveOneKeepsTheOther() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        var firesA = 0
        var firesB = 0
        val handleA = proxy.database.setArrayListener(ref, onAdded = { _, _ -> firesA++ }, onRemoved = { _, _ -> })
        val handleB = proxy.database.setArrayListener(ref, onAdded = { _, _ -> firesB++ }, onRemoved = { _, _ -> })

        proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, "a", 1L))
        assertEquals(1, firesA, "the first listener must still fire after a second registers")
        assertEquals(1, firesB, "the second listener fires too")

        // Remove only A; B must keep firing.
        proxy.database.removeArrayListener(handleA)
        proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, "b", 2L))
        assertEquals(1, firesA, "removed listener A no longer fires")
        assertEquals(2, firesB, "listener B keeps firing after A is removed")

        // Handles are distinct.
        assertTrue(handleA != handleB, "each registration gets its own handle")
    }

    /**
     * The bulk primitives (`control-data-reset.md` §11) exist so a bulk producer
     * or consumer does not pay one whole-array rewrite per element. They must be
     * indistinguishable from the per-element calls at the observable level:
     * same membership afterwards, same listener events, non-members ignored.
     */
    @Test
    fun addAllToArray_appendsEveryValue_andFiresOncePerValue() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        val added = mutableListOf<Data>()
        proxy.database.setArrayListener(ref, onAdded = { v, _ -> added += v }, onRemoved = { _, _ -> })

        proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, "pre", 0L))
        val batch = (1..5).map { SyntheticTestData.Factory.create(proxy.tContext, "b$it", it.toLong()) }
        proxy.database.addAllToArray(ref, batch, WriteOrigin.SYNC_INGEST)

        assertEquals(6, proxy.database.getArrayData(ref)?.toList()?.size, "the batch appends to what was there")
        assertEquals(6, added.size, "one onAdded per value, exactly as N addToArray calls would fire")
    }

    @Test
    fun removeAllFromArray_dropsEveryMember_ignoresNonMembers_andFiresOncePerRemoval() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)

        val items = (1..5).map { SyntheticTestData.Factory.create(proxy.tContext, "b$it", it.toLong()) }
        items.forEach { proxy.database.addToArray(ref, it) }
        val ids = items.map { proxy.executionContext.getDataId(it)!! }
        val absentId = proxy.executionContext.getDataId(
            SyntheticTestData.Factory.create(proxy.tContext, "absent", 99L)
        )!!

        val removed = mutableListOf<DataId>()
        proxy.database.setArrayListener(ref, onAdded = { _, _ -> }, onRemoved = { id, _ -> removed += id })

        proxy.database.removeAllFromArray(ref, listOf(ids[0], ids[2], absentId), WriteOrigin.USER)

        val left = proxy.database.getArrayData(ref)?.toList().orEmpty()
        assertEquals(3, left.size, "only the named members are dropped")
        assertEquals(2, removed.size, "a non-member fires nothing, mirroring removeFromArray")
        assertEquals(setOf(ids[0], ids[2]), removed.toSet(), "each removed member fires exactly once")
    }
}
