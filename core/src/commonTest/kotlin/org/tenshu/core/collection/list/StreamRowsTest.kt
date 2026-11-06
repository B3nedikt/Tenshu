package org.tenshu.core.collection.list

import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StreamRows] — the fold behind [flow]. The hash-count assertions are the point: the real
 * `hashOf` costs ~130 µs per call on a phone, and re-hashing the held rows on every arrival made
 * a 618-row folder snapshot take 25 s. The identity assertions pin that the caching changed
 * nothing about which row replaces which.
 */
class StreamRowsTest {

    private data class Row(val key: String?, val version: Int = 0)

    private var hashCalls = 0

    private fun idOf(key: String) = DataId.StringDataId.create(type = "test/row", keyValue = key)

    private fun rows() = StreamRows<Row> { row ->
        hashCalls++
        row.key?.let { idOf(it).getDataIdHash() }
    }

    private fun snapshot(size: Int) = (0 until size).map { Row("r$it") }

    @Test
    fun aSnapshotHashesEachRowOnce() {
        val list = rows()
        val snapshot = snapshot(2_000)

        list.merge(snapshot)

        // Re-hashing the held rows per arrival costs n + n(n-1)/2 ≈ 2 000 000 here.
        assertEquals(2_000, hashCalls)
        assertEquals(snapshot, list.rows)
    }

    @Test
    fun aChangedRowReplacesItselfInPlaceAndHashesOnlyItself() {
        val list = rows()
        list.merge(snapshot(1_000))
        hashCalls = 0

        list.merge(listOf(Row("r500", version = 1)))
        list.merge(listOf(Row("new")))

        assertEquals(2, hashCalls)
        assertEquals(1_001, list.rows.size)
        assertEquals(Row("r500", version = 1), list.rows[500])
        assertEquals(Row("new"), list.rows.last())
    }

    @Test
    fun removeNeverRehashesAndLaterRowsStayAddressable() {
        val list = rows()
        list.merge(snapshot(10))
        hashCalls = 0

        list.remove(idOf("r3"))
        list.remove(idOf("absent"))

        assertEquals(0, hashCalls)
        assertEquals((0 until 10).filter { it != 3 }.map { Row("r$it") }, list.rows)

        // Every row after the removed one shifted down; a change to one of them must still land on
        // it rather than on its old index or as a duplicate.
        list.merge(listOf(Row("r9", version = 1)))
        assertEquals(9, list.rows.size)
        assertEquals(Row("r9", version = 1), list.rows.last())
        assertEquals(Row("r8"), list.rows[7])

        // …and the removed identity is free again: re-adding it appends.
        list.merge(listOf(Row("r3", version = 2)))
        assertEquals(Row("r3", version = 2), list.rows.last())
        assertEquals(10, list.rows.size)
    }

    @Test
    fun aRowWithNoIdentityIsAlwaysAppended() {
        val list = rows()

        list.merge(listOf(Row(null, 0), Row("a"), Row(null, 0)))
        list.merge(listOf(Row(null, 0)))

        assertEquals(listOf(Row(null, 0), Row("a"), Row(null, 0), Row(null, 0)), list.rows)
    }

    @Test
    fun aRepeatedIdentityWithinOneBatchKeepsTheFirstPositionAndTheLastValue() {
        val list = rows()

        list.merge(listOf(Row("a", 1), Row("b"), Row("a", 2)))

        assertEquals(listOf(Row("a", 2), Row("b")), list.rows)
    }

    @Test
    fun clearForgetsEveryIdentity() {
        val list = rows()
        list.merge(snapshot(3))

        list.clear()
        list.merge(listOf(Row("r1", version = 1)))
        list.remove(idOf("r0"))

        assertEquals(listOf(Row("r1", version = 1)), list.rows)
    }
}
