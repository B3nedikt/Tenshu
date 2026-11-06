package app.tenshu.control.bridge

import app.tenshu.control.notifications.SchemaFenceAlertHandle
import app.tenshu.control.notifications.SchemaFenceAlerts
import app.tenshu.diagnostics.DiagnosticSink
import app.tenshu.diagnostics.FileDiagnosticSink
import app.tenshu.diagnostics.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Writes schema pull fence transitions to a file on desktop.
 *
 * `SchemaPullFence.logTransition` is the one line that names the cause of a fenced device, and it
 * is a `println` — which a packaged `.app` sends nowhere; this keeps that line on the machine.
 * One entry per transition (the [SchemaFenceAlerts] cadence), so the file cannot fill at the poll
 * rate while a device stays behind.
 */
class SchemaFenceLog(
    private val sink: DiagnosticSink,
) : SchemaFenceAlertHandle {

    override fun onFenceEngaged(fencedServiceIds: List<String>) {
        sink.append(
            level = LogLevel.WARN,
            tag = TAG,
            message = "Schema pull fence ENGAGED: this device is behind a realm epoch for " +
                "${fencedServiceIds.joinToString(", ")}. Pulls are paused (pushes continue) " +
                "until Control is updated.",
            code = "schema_fence_engaged",
        )
    }

    override fun onFenceCleared() {
        sink.append(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Schema pull fence CLEARED: current with every realm epoch; pulls resume.",
            code = "schema_fence_cleared",
        )
    }

    companion object {
        const val TAG = "sync"

        /** Log [verdicts] to [file] for the life of [scope]. */
        fun start(scope: CoroutineScope, verdicts: Flow<List<String>?>, file: File) {
            SchemaFenceAlerts(SchemaFenceLog(FileDiagnosticSink(file))).launchIn(scope, verdicts)
        }
    }
}
