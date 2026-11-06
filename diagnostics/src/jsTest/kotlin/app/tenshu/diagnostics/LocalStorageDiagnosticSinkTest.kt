package app.tenshu.diagnostics

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalStorageDiagnosticSinkTest {

    private val key = "tenshu-diagnostics-test"

    @BeforeTest fun setUp() = window.localStorage.removeItem(key)
    @AfterTest fun tearDown() = window.localStorage.removeItem(key)

    private fun sink(capacity: Int = 500, now: () -> Long = { 1_000L }) =
        LocalStorageDiagnosticSink(key = key, capacity = capacity, now = now)

    @Test
    fun survivesAFreshSink_whichIsWhatSurvivingAReloadMeans() {
        sink().append(LogLevel.WARN, "Sync", "relay refused the push", "402")

        // A new instance reads nothing but localStorage — the same thing a reloaded page does.
        val afterReload = sink().recent(10)
        assertEquals(1, afterReload.size)
        assertEquals(LogLevel.WARN, afterReload[0].level)
        assertEquals("Sync", afterReload[0].tag)
        assertEquals("relay refused the push", afterReload[0].message)
        assertEquals("402", afterReload[0].code)
    }

    @Test
    fun ringDropsOldestPastCapacity() {
        val s = sink(capacity = 3)
        repeat(5) { s.append(LogLevel.INFO, "T", "line $it", null) }
        assertEquals(listOf("line 2", "line 3", "line 4"), s.recent(10).map { it.message })
    }

    // Tabs and newlines would split one item across lines or shift its fields; the shared
    // `DiagnosticLine` escapes them, and this pins that a multi-line message round-trips whole.
    @Test
    fun messagesWithTabsAndNewlinesRoundTripAsOneItem() {
        val s = sink()
        s.append(LogLevel.ERROR, "Crash", "line one\nline\ttwo", null)
        val items = s.recent(10)
        assertEquals(1, items.size, "an embedded newline must not become a second item")
        assertEquals("line one\nline two", items[0].message)
    }

    @Test
    fun clearEmptiesIt() {
        val s = sink()
        s.append(LogLevel.INFO, "T", "x", null)
        s.clear()
        assertTrue(s.recent(10).isEmpty())
    }
}
