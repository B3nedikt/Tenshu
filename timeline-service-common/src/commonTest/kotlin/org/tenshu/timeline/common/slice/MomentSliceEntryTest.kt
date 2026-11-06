package org.tenshu.timeline.common.slice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MomentSliceEntryTest {

    private fun entry(id: String, ts: Long = 1_754_000_000_000) = MomentSliceEntry(
        momentId = id,
        timestampMillis = ts,
        kind = "Visit",
        title = "Coffee",
        subjectKind = "Place",
        subjectId = "place-1",
        durationSec = 1800,
        supersededBy = "",
    )

    @Test
    fun roundTripsEveryField() {
        val decoded = MomentSliceEntry.decode(MomentSliceEntry.encode(listOf(entry("m1"))))!!
        val one = decoded.single()
        assertEquals("m1", one.momentId)
        assertEquals(1_754_000_000_000, one.timestampMillis)
        assertEquals("Visit", one.kind)
        assertEquals("Coffee", one.title)
        assertEquals("Place", one.subjectKind)
        assertEquals("place-1", one.subjectId)
        assertEquals(1800, one.durationSec)
        assertEquals("", one.supersededBy)
    }

    @Test
    fun anEmptySliceIsDistinctFromAnUnreadableOne() {
        // The reader depends on this: empty renders "nothing that day", null falls back to a
        // relation read.
        assertEquals(emptyList(), MomentSliceEntry.decode(MomentSliceEntry.encode(emptyList())))
        assertNull(MomentSliceEntry.decode(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun aNewerEntryVersionDecodesToNull_ratherThanGarbage() {
        val bytes = MomentSliceEntry.encode(listOf(entry("m1")))
        bytes[0] = (MomentSliceEntry.VERSION + 1).toByte()
        assertNull(
            MomentSliceEntry.decode(bytes),
            "an older build must fall back to a relation read, never misread a newer projection",
        )
    }

    @Test
    fun dayKeyIsUtcAndStableAcrossTheLocalDayBoundary() {
        // ⚠⚠ The key is an ADDRESS two devices must agree on, so it cannot depend on the writer's
        // zone. These are fixed UTC instants; the expected buckets are pure arithmetic.
        assertEquals("1970-01-01", MomentSliceEntry.dayKey(0))
        assertEquals("1970-01-02", MomentSliceEntry.dayKey(24L * 60 * 60 * 1000))
        assertEquals("2026-08-06", MomentSliceEntry.dayKey(1_785_974_400_000)) // 2026-08-06T00:00Z
        assertEquals("2026-08-06", MomentSliceEntry.dayKey(1_786_060_799_999)) // …T23:59:59.999Z
        assertEquals("2026-08-07", MomentSliceEntry.dayKey(1_786_060_800_000)) // the next boundary
    }

    @Test
    fun dayKeyHandlesLeapYearsAndPreEpochInstants() {
        assertEquals("2024-02-29", MomentSliceEntry.dayKey(1_709_164_800_000))
        assertEquals("2024-03-01", MomentSliceEntry.dayKey(1_709_251_200_000))
        // Pre-epoch is not expected in practice, but floorDiv must not round toward zero and
        // produce a key off by a day.
        assertEquals("1969-12-31", MomentSliceEntry.dayKey(-1))
    }

    @Test
    fun manyEntriesSurviveInOrder() {
        val many = (0 until 500).map { entry("m$it", ts = 1_785_974_400_000 + it * 1000L) }
        val decoded = MomentSliceEntry.decode(MomentSliceEntry.encode(many))!!
        assertEquals(500, decoded.size)
        assertTrue(decoded.map { it.momentId } == many.map { it.momentId }, "order must be stable")
    }
}
