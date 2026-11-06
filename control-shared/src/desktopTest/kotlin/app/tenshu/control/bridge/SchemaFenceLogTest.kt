package app.tenshu.control.bridge

import app.tenshu.control.notifications.SchemaFenceAlerts
import app.tenshu.diagnostics.FileDiagnosticSink
import app.tenshu.diagnostics.LogLevel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that on desktop the fence's transitions reach a file, once per transition, naming the
 * fenced service — a packaged app's `println` goes nowhere.
 */
class SchemaFenceLogTest {

    @Test
    fun writesOneLinePerTransition_toTheFile() {
        val file = Files.createTempDirectory("fence-log").resolve("diagnostics/control.log").toFile()
        val sink = FileDiagnosticSink(file)
        val alerts = SchemaFenceAlerts(SchemaFenceLog(sink))

        alerts.onVerdict(null)
        repeat(3) { alerts.onVerdict(listOf("tenshu/file/service")) }
        repeat(3) { alerts.onVerdict(emptyList()) }

        val items = FileDiagnosticSink(file).recent(10)
        assertEquals(listOf("schema_fence_engaged", "schema_fence_cleared"), items.map { it.code })
        assertEquals(LogLevel.WARN, items.first().level)
        assertTrue("tenshu/file/service" in items.first().message, "the cause is named in the log")
    }
}
