package app.tenshu.diagnostics

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The file sink: round-trip, ordering, cap enforcement, tab/newline safety, reopen. */
class FileDiagnosticSinkTest {

    private val file = File.createTempFile("diag", ".log").also { it.deleteOnExit() }
    private var clock = 0L
    private fun sink(cap: Int = 500) = FileDiagnosticSink(file, capacity = cap, now = { clock++ })

    @AfterTest fun cleanup() { file.delete() }

    @Test
    fun appendAndReadRoundTrips() {
        val s = sink()
        s.append(LogLevel.INFO, "capture", "FGS started", code = "fgs_started")
        s.append(LogLevel.WARN, "watchdog", "restart #1", code = "watchdog_restart")
        val got = s.recent(10)
        assertEquals(2, got.size)
        assertEquals("FGS started", got[0].message)
        assertEquals("fgs_started", got[0].code)
        assertEquals(LogLevel.WARN, got[1].level)
    }

    @Test
    fun survivesTabsAndNewlinesInTheMessage() {
        val s = sink()
        s.append(LogLevel.ERROR, "crash", "line1\nline2\twith tab", code = "uncaught_exception")
        val got = s.recent(1).single()
        assertTrue(got.message.contains("line2"))
        assertEquals("uncaught_exception", got.code)
        assertEquals("crash", got.tag)
    }

    @Test
    fun enforcesTheCapAcrossReopen() {
        sink(cap = 3).apply { repeat(5) { append(LogLevel.INFO, "t", "m$it", null) } }
        val got = sink(cap = 3).recent(100)
        assertEquals(listOf("m2", "m3", "m4"), got.map { it.message })
    }

    @Test
    fun readsBackFromDiskInAFreshSink() {
        sink().append(LogLevel.INFO, "capture", "persisted", null)
        assertEquals("persisted", sink().recent(1).single().message)
    }

    @Test
    fun aMissingFileReadsAsEmptyNeverThrows() {
        file.delete()
        assertEquals(emptyList(), sink().recent(10))
    }
}
