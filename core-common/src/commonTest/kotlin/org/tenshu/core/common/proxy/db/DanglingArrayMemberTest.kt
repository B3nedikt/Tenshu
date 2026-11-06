package org.tenshu.core.common.proxy.db

import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticListHolder
import org.tenshu.core.common.SyntheticListOwner
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A relation member whose row is gone.
 *
 * ⚠⚠ **A read must not throw `IllegalStateException: Entry not found` for it**: the throw kills
 * whatever is reading. When that is `SyncScheduler.tickOnce`, sync is dead with **no user-visible
 * symptom** — `reportSyncHealth()` is in the same aborted region. So the *default* tolerates the
 * hole rather than each caller: a shorter relation is a recoverable answer, a throw from inside a
 * lazy sequence is not.
 *
 * Two halves, and they are deliberately independent — reads tolerate the damage, and a separate
 * repair removes it. Tolerating alone leaves the queue carrying dead refs forever; repairing alone
 * leaves every read between the damage and the next repair fatal.
 */
class DanglingArrayMemberTest {

    private fun arrayRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = TList.ID,
            relationId = "test/dangling"
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID
    )

    /** Adds three members and deletes the middle one's row, leaving the reference behind. */
    private fun proxyWithADanglingMiddleMember(): Pair<TestProxy, ArrayDatabaseReference> {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)
        listOf("alpha", "beta", "gamma").forEach { name ->
            proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, name, 1L))
        }
        val doomed = proxy.database.getArrayData(ref)!!.toList()[1]
        proxy.database.deleteData(proxy.executionContext.getDataId(doomed)!!)
        return proxy to ref
    }

    @Test
    fun aReadSkipsTheDanglingMemberInsteadOfThrowing() {
        val (proxy, ref) = proxyWithADanglingMiddleMember()

        val read = proxy.database.getArrayData(ref)!!.toList()

        assertEquals(2, read.size, "the read succeeds and is SHORT by the missing member")
        val names = read.map { (it as SyntheticTestData).name.get() }
        assertEquals(listOf("alpha", "gamma"), names, "order is preserved around the hole")
    }

    /**
     * The windowed read is the one paging and `TombstoneRegistry` use, and it resolves members
     * eagerly for its slice exactly like the unwindowed one — so it needs the same treatment.
     */
    @Test
    fun aWindowedReadAlsoSkipsIt() {
        val (proxy, ref) = proxyWithADanglingMiddleMember()

        val window = proxy.database.getArrayDataWindow(ref, projection = null, offset = 0, limit = 3)!!.toList()

        assertEquals(2, window.size)
    }

    @Test
    fun repairDropsOnlyTheDanglingReference() {
        val (proxy, ref) = proxyWithADanglingMiddleMember()
        assertEquals(3, proxy.database.getArrayMemberHashes(ref)?.size, "the dead ref is still there")

        val dropped = proxy.database.removeDanglingArrayMembers(ref)

        assertEquals(1, dropped)
        assertEquals(
            2,
            proxy.database.getArrayMemberHashes(ref)?.size,
            "the reference list itself is clean after the repair — readers stop paying for it",
        )
        val names = proxy.database.getArrayData(ref)!!.toList().map { (it as SyntheticTestData).name.get() }
        assertEquals(listOf("alpha", "gamma"), names, "the readable members are untouched")
    }

    /**
     * ⚠⚠ The property that makes the repair safe to run unattended on a tick: it removes a
     * reference only where the object is *provably absent*. It can never discard an unsent write —
     * the failure mode that loses real data.
     */
    @Test
    fun repairIsANoOpOnAHealthyRelation() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val ref = arrayRef(proxy)
        listOf("alpha", "beta", "gamma").forEach { name ->
            proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, name, 1L))
        }

        assertEquals(0, proxy.database.removeDanglingArrayMembers(ref), "nothing dangling ⇒ no rewrite")
        assertEquals(3, proxy.database.getArrayData(ref)!!.toList().size)
    }

    /**
     * ⚠⚠ **The same dangling row, one level down, must be tolerated too.**
     *
     * A member of a composite's *inline list field* resolves through whatever resolver
     * `deSerialize` passes on, and `deserializeArray`'s parameter defaults to the strict `getEntry`.
     * Without the tolerant resolver threaded through, a top-level read skips the hole and reports
     * it, while the identical hole inside a member throws `Entry not found` out of the whole read.
     *
     * In a real store: one dangling member of `tenshu/file/folder/files` on a subfolder makes the
     * **root** folder listing unreadable, and because the stream re-evaluates on every write it
     * re-throws about once a second — an endlessly reloading "Couldn't load this folder" with
     * nothing in the client's log.
     */
    @Test
    fun aDanglingMemberOfANestedListFieldIsSkippedToo() {
        val store = InMemoryObjectStore()
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), objectStore = store)
        val ownerRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                relationId = "test/dangling-nested"
            ),
            isEntity = false,
            elementSchemaId = SyntheticListOwner.ID
        )
        val items = listOf("alpha", "beta", "gamma")
            .map { SyntheticTestData.Factory.create(proxy.tContext, it, 1L) }
        proxy.database.addToArray(
            ownerRef,
            SyntheticListOwner.Factory.create(
                proxy.tContext,
                "owner",
                SyntheticListHolder.Factory.create(proxy.tContext, "holder", items),
            )
        )

        // Delete one inline member's row, leaving the reference behind. It has no DataId of its own
        // — which is why such a failure can only report `dataId=null`.
        val doomed = store.rowsOfTypes(setOf(SyntheticTestData.ID))
            .first { (it.fields.firstOrNull() as? ObjectStore.DbValue.StringValue)?.value == "beta" }
        store.removeObject(doomed.dataIdHash)

        val read = proxy.database.getArrayData(ownerRef)!!.toList()

        assertEquals(1, read.size, "the owner itself still reads")
        val nested = (read.single() as SyntheticListOwner).holder.items.toList()
        assertEquals(2, nested.size, "the NESTED list is short by the missing member, and did not throw")
        assertEquals(
            listOf("alpha", "gamma"),
            nested.map { (it as SyntheticTestData).name.get() },
            "order is preserved around the hole, one level down",
        )
    }

    @Test
    fun repairOfAnAbsentRelationIsHarmless() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        assertEquals(0, proxy.database.removeDanglingArrayMembers(arrayRef(proxy)))
    }

    /**
     * ⚠ The array *itself* being absent is a different case and answers empty, and a missing
     * **nested child** of a member still throws — only top-level members are skipped. Pinned so the
     * tolerance stays this narrow.
     */
    @Test
    fun anAbsentRelationStillReadsEmpty() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        assertTrue(proxy.database.getArrayData(arrayRef(proxy))!!.toList().isEmpty())
    }
}
