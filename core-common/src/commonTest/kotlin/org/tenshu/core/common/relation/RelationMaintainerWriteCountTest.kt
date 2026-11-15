package org.tenshu.core.common.relation

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticGenericHolder
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.module.service as registerService
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.SaveOutcomeAwareDataObserver
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The evidence for R2b's write-path claim, and for the listener contract that
 * had to come with it (`core-relation-system.md` §10.4).
 *
 * **What is being counted, and at which layer.** One [ObjectStore.storeObject]
 * call for the relation's own array row — the layer at which the claim is
 * actually true. It is deliberately *not* a claim about bytes hitting SQLite: on
 * the JVM/Android store the bounded write mailbox
 * (`bounded-store-cache.md` P1) dedupes queued Puts by row hash, so the
 * remove-write and the add-write of one transaction may already collapse into a
 * single physical write. What the mailbox cannot do is turn two writes into
 * *zero*: a rewrite that produces byte-identical content is still a mailbox
 * entry, a serialisation and a pin. That is what probe-first removes.
 *
 * Measured on this fixture, before → after (array-row writes per save):
 *
 * | | before (`MostRecentLast`, ~30 relations) | before (`MembershipOrder.Stable`, `Folder.FILES`) | after (R2b, all) |
 * |---|---|---|---|
 * | re-save of an existing member | 2 | 0 | **0** |
 * | a genuinely new member | 2 | 2 | **1** |
 * | repair of a duplicated member | 2 | 2 | **2** |
 *
 * ⚠ The `MostRecentLast` "2" for a new member is the pointless half: an
 * `removeFromArray` for an id that is not there still reads the whole row,
 * filters nothing out and writes the whole row back.
 *
 * ⚠⚠ **The listener assertions are not a nicety.** The `remove`+`add` pair was
 * also the only thing that re-evaluated a reactive read of the relation
 * ([org.tenshu.core.common.proxy.StreamRegistry] listens for array adds/removes
 * and nothing else). Probe-first writes nothing in the steady state, so without
 * [org.tenshu.core.common.proxy.Database.notifyArrayMemberUpdated] a re-saved
 * `DeviceState` would update the store and never reach the Home UI. These tests
 * pin that a re-save **with a new value** still produces exactly one listener
 * event — and, since `core-deterministic-invalidation.md` §8 P1, that a
 * byte-identical one produces none.
 */
class RelationMaintainerWriteCountTest {

    private class CountingStore(
        private val delegate: ObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        val writtenRows = mutableListOf<Long>()
        var reads = 0

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads++
            return delegate.getObject(dataIdHash)
        }

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            writtenRows += dbEntry.dataIdHash
            delegate.storeObject(dbEntry)
        }
    }

    private lateinit var store: CountingStore
    private lateinit var proxy: TestProxy
    private lateinit var reference: ArrayDatabaseReference

    private var added = 0
    private var removed = 0

    @BeforeTest
    fun setup() {
        store = CountingStore()
        proxy = TestProxy(schemaRegistry = writeCountTestRegistry(), objectStore = store)
        proxy.executionContext.installRelationMaintainer()
        reference = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                relationId = COUNTED_RELATION,
            ),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        proxy.executionContext.database.setArrayListener(
            reference = reference,
            onAdded = { _, _ -> added++ },
            onRemoved = { _, _ -> removed++ },
        )
        // Seed, so the array row exists: the very first member of an array is a
        // special case (`removeFromArray` early-returns on a missing row) and
        // would flatter the before-numbers.
        save("seed")
    }

    @Test
    fun reSavingAnExistingMemberWithANewValue_writesNothing_andNotifiesOnce() {
        save("a")

        val writes = countingArrayWrites { save("a", value = 2) }

        assertEquals(
            0,
            writes,
            "the steady state — present exactly once — must not rewrite the array row " +
                "(it was 2 under MostRecentLast: one remove-write, one add-write)",
        )
        assertEquals(1, added + removed, "…but a reactive reader is still told exactly once")
    }

    /**
     * `core-deterministic-invalidation.md` §8, P1: a re-save whose every entry is already stored
     * byte-identically — the relay echoing this device's own write — is not news, so nothing is told.
     * ⚠ Until P1 this fired once; the new-value case above is the one the notification exists for.
     */
    @Test
    fun reSavingAnExistingMemberUnchanged_writesNothing_andNotifiesNobody() {
        save("a")

        val writes = countingArrayWrites { save("a") }

        assertEquals(0, writes)
        assertEquals(0, added + removed, "a byte-identical re-save changes nothing a reader can see")
    }

    /**
     * The store comparison costs a read per written entry, so the maintainer asks for it only for a
     * type some relation keys on. A row no relation names (here a `SyntheticGenericHolder`, standing
     * in for `SyncMetadata` and other bookkeeping) is saved as the plain write it always was — the
     * read count only rises once an observer that wants the bit for every type is added.
     */
    @Test
    fun aTypeNoRelationKeysOn_doesNotPayTheStoreComparison() {
        // A fresh key each time, so every save is a first sight and costs the same.
        var key = 0
        fun readsForOneSave(): Int {
            store.reads = 0
            proxy.executionContext.saveData(
                SyntheticGenericHolder.Factory.create(proxy.tContext, "g${key++}", 1L.toTInteger(proxy.tContext)),
            )
            return store.reads
        }
        val withMaintainerOnly = readsForOneSave()

        proxy.executionContext.addDataObserver(object : SaveOutcomeAwareDataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin, redundant: Boolean) = Unit
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })
        val compared = readsForOneSave()

        assertTrue(
            compared > withMaintainerOnly,
            "only the observer that wants every type makes this save pay ($compared vs $withMaintainerOnly)",
        )
    }

    @Test
    fun aNewMember_isOneWrite_notTwo() {
        val writes = countingArrayWrites { save("a") }

        assertEquals(
            1,
            writes,
            "one addToArray; the pre-R2b remove-then-add wrote the row twice to add one id",
        )
        assertEquals(1, added, "and it is an add, as it always was")
        assertEquals(0, removed)
    }

    @Test
    fun aDuplicatedMember_isRepaired_andThatStillCostsTwoWrites() {
        save("a")
        // The two-writer era's pollution: the same member twice in one array.
        proxy.executionContext.database.addToArray(reference, item("a"))
        assertEquals(
            listOf("seed", "a", "a"),
            namesInArray(),
            "array should be polluted by setup",
        )

        val writes = countingArrayWrites { save("a") }

        assertEquals(2, writes, "remove-then-add, which is what repair genuinely costs")
        assertEquals(listOf("seed", "a"), namesInArray(), "convergence to exactly-one is preserved")
    }

    @Test
    fun deletingAMember_stillRemovesAndNotifies() {
        save("a")
        added = 0
        removed = 0

        proxy.executionContext.deleteData(
            DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "a"),
        )

        assertEquals(listOf("seed"), namesInArray())
        assertEquals(1, removed, "the delete path is untouched by R2b")
    }

    // --- fixtures ---------------------------------------------------------

    private fun countingArrayWrites(block: () -> Unit): Int {
        val arrayHash = reference.arrayId.getDataIdHash()
        store.writtenRows.clear()
        added = 0
        removed = 0
        block()
        return store.writtenRows.count { it == arrayHash }
    }

    private fun save(name: String, value: Long = 1) = proxy.executionContext.saveData(item(name, value))

    private fun item(name: String, value: Long = 1): Data =
        SyntheticTestData.Factory.create(proxy.tContext, name, value)

    private fun namesInArray(): List<String> =
        proxy.executionContext.database.getArrayData(reference)
            ?.map { data ->
                (data as org.tenshu.core.Composite)
                    .getField<org.tenshu.core.TString>(SyntheticTestData.Companion.Field.NAME)
                    .get()
            }
            ?.toList()
            ?: emptyList()
}

private const val WRITE_COUNT_SERVICE = "test/relation/writeCount/service"
private const val COUNTED_RELATION = "$WRITE_COUNT_SERVICE/counted"

private object WriteCountTestService {
    val Schema: ServiceSchema = service(
        id = WRITE_COUNT_SERVICE,
        relations = listOf(
            serviceDataRelation(
                id = COUNTED_RELATION,
                type = TList.typeOf(SyntheticTestData.Schema),
                maintained = true,
            ),
        ),
    )
}

private fun writeCountTestRegistry(): SchemaRegistry {
    val registry = createSyntheticTestRegistry()
    registry.registerConfig(tModule { registerService(WriteCountTestService.Schema) })
    return registry
}
