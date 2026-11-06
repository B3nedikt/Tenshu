package app.tenshu.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The facade over a fake sink — level routing, and the no-op-before-install safety. */
class DiagTest {

    private class RecordingSink : DiagnosticSink {
        val items = mutableListOf<LogItem>()
        override fun append(level: LogLevel, tag: String, message: String, code: String?) {
            items += LogItem(items.size.toLong(), level, tag, message, code)
        }
        override fun recent(limit: Int) = items.takeLast(limit)
        override fun clear() = items.clear()
    }

    @AfterTest fun reset() = Diagnostics.install(DiagnosticSink.NoOp)

    @Test
    fun routesLevels() {
        val sink = RecordingSink(); Diagnostics.install(sink)
        Diagnostics.d("a", "d"); Diagnostics.i("a", "i"); Diagnostics.w("a", "w"); Diagnostics.e("a", "e", code = "x")
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), sink.items.map { it.level })
        assertEquals("x", sink.items.last().code)
    }

    @Test
    fun isASilentNoOpBeforeInstall() {
        // Must never crash a call site just because the platform didn't wire a sink.
        Diagnostics.install(DiagnosticSink.NoOp)
        Diagnostics.i("a", "before install")
        assertTrue(Diagnostics.recent().isEmpty())
    }
}
