package org.tenshu.core.common.proxy.db

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestDatabaseLock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A relation read pages across store accesses (`core-ordered-window-fusion.md` §6.1).
 *
 * ⚠⚠ On the iOS SQLite replica every outermost `lock.withLock` is one write-locked transaction under
 * `HOLD_MAX` (1 s): a relation read whole in **one** access fenced the store `COMMIT_FAILED` past
 * ~4–5k members. So the property pinned here is per access: **no single `withLock` resolves more
 * than [ARRAY_READ_PAGE_SIZE] member rows**, counted at the store with the lock telling which
 * access each read belongs to. A read in one access fails [aWholeReadResolvesAtMostAPagePerAccess].
 *
 * The real-store twin, over `SharedReplicaStore`, is `WholeRelationReadSharedReplicaTest`
 * (`:tenshu-sdk-ios-core`, macOS only).
 */
class PagedArrayReadTest {

    /** A real reentrant lock that numbers its outermost entries and can run a write between two. */
    private class CountingLock : DatabaseLock {
        private val inner = TestDatabaseLock()
        private var depth = 0

        /** Outermost entries so far; the number of the open one while inside. */
        var accesses = 0
            private set

        /** Runs once, right after the next outermost access ends — i.e. between two pages. */
        var afterNextAccess: (() -> Unit)? = null

        override fun <R> withLock(block: () -> R): R {
            var outermost = false
            val result = inner.withLock {
                if (depth == 0) {
                    outermost = true
                    accesses++
                }
                depth++
                try {
                    block()
                } finally {
                    depth--
                }
            }
            if (outermost) {
                val hook = afterNextAccess
                afterNextAccess = null
                hook?.invoke()
            }
            return result
        }
    }

    /** Counts reads of the [watched] rows per outermost access of [lock]. */
    private class CountingStore(
        private val inner: InMemoryObjectStore,
        private val lock: CountingLock,
    ) : ObjectStore by inner {
        var watched: Set<Long> = emptySet()
        val readsByAccess = mutableMapOf<Int, Int>()

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            if (dataIdHash in watched) readsByAccess[lock.accesses] = (readsByAccess[lock.accesses] ?: 0) + 1
            return inner.getObject(dataIdHash)
        }
    }

    private class Rig(members: Int) {
        val lock = CountingLock()
        val store = CountingStore(InMemoryObjectStore(), lock)
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), objectStore = store, lock = lock)
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(context = proxy.tContext, type = TList.ID, relationId = "test/paged"),
            isEntity = false,
            elementSchemaId = SyntheticTestData.ID,
        )
        val names = List(members) { "m${it.toString().padStart(5, '0')}" }

        init {
            proxy.database.addAllToArray(ref, names.map { SyntheticTestData.Factory.create(proxy.tContext, it, 1L) })
            store.watched = proxy.database.getArrayMemberHashes(ref)!!.toSet()
            store.readsByAccess.clear()
        }

        fun namesOf(rows: Sequence<org.tenshu.core.Data>?): List<String> =
            rows!!.toList().map { (it as SyntheticTestData).name.get() }

        fun add(name: String) =
            proxy.database.addToArray(ref, SyntheticTestData.Factory.create(proxy.tContext, name, 1L))

        fun dataIdOf(name: String): DataId = proxy.database.getArrayData(ref)!!
            .first { (it as SyntheticTestData).name.get() == name }
            .let { proxy.executionContext.getDataId(it)!! }
    }

    private val page = ARRAY_READ_PAGE_SIZE

    @Test
    fun aWholeReadResolvesAtMostAPagePerAccess() {
        val rig = Rig(members = 3 * page + 7)

        val read = rig.namesOf(rig.proxy.database.getArrayData(rig.ref))

        assertEquals(rig.names, read, "every member, once, in insertion order")
        val perAccess = rig.store.readsByAccess.values
        assertEquals(rig.names.size, perAccess.sum(), "each member row read exactly once")
        assertTrue(
            perAccess.all { it <= page },
            "no single access may resolve more than a page ($page) of members: $perAccess",
        )
        assertEquals(4, perAccess.size, "⌈${rig.names.size} / $page⌉ accesses")
    }

    @Test
    fun aRelationOfOnePageIsStillOneAccess() {
        val rig = Rig(members = page)
        val before = rig.lock.accesses

        assertEquals(rig.names, rig.namesOf(rig.proxy.database.getArrayData(rig.ref)))

        assertEquals(1, rig.lock.accesses - before, "the snapshot and the only page share one access")
    }

    @Test
    fun aWindowedReadPagesToo() {
        val rig = Rig(members = 3 * page)

        val window = rig.namesOf(
            rig.proxy.database.getArrayDataWindow(rig.ref, projection = null, offset = 100, limit = 2 * page + 1),
        )

        assertEquals(rig.names.subList(100, 100 + 2 * page + 1), window)
        assertTrue(rig.store.readsByAccess.values.all { it <= page }, "${rig.store.readsByAccess}")
        assertEquals(2 * page + 1, rig.store.readsByAccess.values.sum(), "only the window's members are read")
    }

    @Test
    fun theCheckpointedReadPagesToo() {
        val rig = Rig(members = 2 * page + 1)
        var checks = 0

        val read = rig.namesOf(rig.proxy.database.getArrayData(rig.ref, projection = null, checkpoint = { checks++ }))

        assertEquals(rig.names, read)
        assertEquals(rig.names.size, checks, "one checkpoint per member")
        assertTrue(rig.store.readsByAccess.values.all { it <= page }, "${rig.store.readsByAccess}")
    }

    /** ⚠ Inside an outer lock the pages re-enter it: one access, atomic, exactly as before. */
    @Test
    fun insideATransactionTheReadIsTheTransactionsOneAccess() {
        val rig = Rig(members = 3 * page)
        val before = rig.lock.accesses

        val read = rig.proxy.database.transaction { rig.namesOf(rig.proxy.database.getArrayData(rig.ref)) }

        assertEquals(rig.names, read)
        assertEquals(1, rig.lock.accesses - before, "every page joined the transaction")
    }

    /**
     * ⚠⚠ What a write between two pages may do (`DatabaseImpl.readArrayWindow`): a member added
     * after the snapshot is absent, one removed and deleted is skipped, one removed but still stored
     * is returned — and **no member is read twice or lost** that stayed a member throughout. An
     * offset-resliced read would have done both here: the add at the front shifts every later
     * member by one.
     */
    @Test
    fun aWriteBetweenPagesNeitherDuplicatesNorLosesAStableMember() {
        val rig = Rig(members = 3 * page)
        val removedKept = rig.names[page + 1]
        val removedDeleted = rig.names[page + 2]
        val removedKeptId = rig.dataIdOf(removedKept)
        val removedDeletedId = rig.dataIdOf(removedDeleted)
        val firstAtStart = rig.names.first()
        val firstId = rig.dataIdOf(firstAtStart)
        rig.store.readsByAccess.clear()

        rig.lock.afterNextAccess = {
            // Between page 1 and page 2. Removing a page-1 member shifts every later one forward
            // by one, so a re-sliced offset would now skip a member.
            rig.proxy.database.removeFromArray(rig.ref, firstId)
            rig.add("added-between-pages")
            rig.proxy.database.removeFromArray(rig.ref, removedKeptId)
            rig.proxy.database.removeFromArray(rig.ref, removedDeletedId)
            rig.proxy.database.deleteData(removedDeletedId)
        }
        val read = rig.namesOf(rig.proxy.database.getArrayData(rig.ref))

        assertEquals(read.size, read.toSet().size, "no member read twice: $read")
        assertEquals(rig.names - removedDeleted, read, "the snapshot, less the member whose row went")
        assertTrue("added-between-pages" !in read, "an add after the snapshot is not returned")
        assertTrue(removedKept in read, "a removed member whose row exists is still returned")
        assertTrue(firstAtStart in read, "page 1 was read before its member was removed")
    }

    /** The relation's own row gone between pages: empty, never the first page passed off as all. */
    @Test
    fun theArrayGoneBetweenPagesReadsEmpty_notAPartialRelation() {
        val rig = Rig(members = 3 * page)
        val arrayHash = rig.ref.arrayId.getDataIdHash()

        rig.lock.afterNextAccess = { rig.lock.withLock { rig.store.removeObject(arrayHash) } }
        val read = rig.namesOf(rig.proxy.database.getArrayData(rig.ref))

        assertEquals(emptyList(), read, "a partial relation must not read as the whole")
    }
}
