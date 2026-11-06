package app.tenshu.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiagnosticRingTest {

    private fun item(i: Int) = LogItem(timestampMs = i.toLong(), level = LogLevel.INFO, tag = "t", message = "m$i")

    @Test
    fun keepsUnderCapacity() {
        val ring = DiagnosticRing(3)
        var log = emptyList<LogItem>()
        log = ring.appended(log, item(1))
        log = ring.appended(log, item(2))
        assertEquals(listOf("m1", "m2"), log.map { it.message })
    }

    @Test
    fun dropsOldestOverCapacity() {
        val ring = DiagnosticRing(3)
        var log = emptyList<LogItem>()
        for (i in 1..5) log = ring.appended(log, item(i))
        // Newest three, oldest dropped.
        assertEquals(listOf("m3", "m4", "m5"), log.map { it.message })
    }

    @Test
    fun trimsAnOverlongLoad() {
        val loaded = (1..10).map(::item)
        assertEquals(listOf("m9", "m10"), DiagnosticRing(2).trimmed(loaded).map { it.message })
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { DiagnosticRing(0) }
    }
}
