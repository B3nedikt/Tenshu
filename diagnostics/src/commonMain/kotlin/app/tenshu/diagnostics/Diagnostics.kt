package app.tenshu.diagnostics

/**
 * The app-wide diagnostic facade — `Diagnostics.i("capture", "FGS started")`. A thin, always-safe front over
 * an installed [DiagnosticSink]; before [install] it is a silent no-op, so call sites never need to
 * null-check or know whether diagnostics are wired on this platform.
 *
 * Single global by intent: like `Timber`/`Log`, a logger you thread through DI is a logger nobody
 * uses. The sink *is* injected (installed once at app start); the façade is the ambient entry point.
 */
object Diagnostics {

    // Set once at app start (before any log call), then effectively immutable — no @Volatile needed.
    private var sink: DiagnosticSink = DiagnosticSink.NoOp

    /** Install the platform sink once at app start. */
    fun install(sink: DiagnosticSink) { this.sink = sink }

    fun d(tag: String, message: String, code: String? = null) = sink.append(LogLevel.DEBUG, tag, message, code)
    fun i(tag: String, message: String, code: String? = null) = sink.append(LogLevel.INFO, tag, message, code)
    fun w(tag: String, message: String, code: String? = null) = sink.append(LogLevel.WARN, tag, message, code)
    fun e(tag: String, message: String, code: String? = null) = sink.append(LogLevel.ERROR, tag, message, code)

    /** Read back (for the Diagnostics screen / upload). */
    fun recent(limit: Int = 500): List<LogItem> = sink.recent(limit)

    fun clear() = sink.clear()
}
