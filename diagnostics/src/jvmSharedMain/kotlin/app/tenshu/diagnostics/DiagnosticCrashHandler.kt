package app.tenshu.diagnostics

/**
 * Records an [LogLevel.ERROR] item for any **uncaught** exception before the process dies, then
 * chains to whatever handler was already installed (so the OS still shows its crash dialog / kills
 * the process as normal). This is the "app just crashed" capture that the operation error channel
 * cannot see — that one only catches exceptions thrown *out of operations*, not a main-thread NPE, an
 * OOM, or a UI crash.
 *
 * ⚠ Everything here is synchronous and defensive: the write goes straight to the [DiagnosticSink]'s
 * durable append (no coroutine/async flush would run), and a failure while logging must never mask
 * the original crash — so the chain-through happens regardless. The stack trace is truncated to keep
 * the record bounded and to avoid a giant line, and it is stored **device-local only**; nothing is
 * uploaded (a future opt-in toggle owns that).
 */
object DiagnosticCrashHandler {

    fun install(sink: DiagnosticSink) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                sink.append(
                    level = LogLevel.ERROR,
                    tag = "crash",
                    message = "${throwable::class.simpleName}: ${throwable.message} " +
                        "[${thread.name}] ${throwable.stackTraceToString().take(MAX_TRACE_CHARS)}",
                    code = "uncaught_exception",
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private const val MAX_TRACE_CHARS = 4000
}
