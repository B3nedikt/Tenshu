package app.tenshu.diagnostics

/**
 * One entry in the device-local diagnostic log — a breadcrumb or a crash, on the same stream.
 *
 * Single-stream by design (`core-logging-facade.md`, single-stream variant): a crash is just an
 * [LogLevel.ERROR] item, usually with a [code], so its surrounding breadcrumbs sit right next to it —
 * which is exactly the context you want when reading one. The trust distinction that mattered in the
 * two-stream debate (anonymous-uploadable vs review-before-upload) is a **per-item property** — an
 * item with a [code] and no free-text [message] is structured/anonymous; a free-text item is not — so
 * it lives on the item, not on a separate stream, and is applied only at an upload seam.
 */
data class LogItem(
    val timestampMs: Long,
    val level: LogLevel,
    /** A short area tag — `"capture"`, `"watchdog"`, `"crash"`, `"sweep"`. */
    val tag: String,
    /** Human-readable detail. ⚠ May carry PII (coordinates, paths) — see the module KDoc. */
    val message: String,
    /**
     * An optional stable, PII-free identifier for aggregation (`"fgs_started"`,
     * `"watchdog_restart"`, `"uncaught_exception"`). Present ⇒ the item is structured enough to
     * upload without exposing [message] — the hook a future anonymous-upload path filters on.
     */
    val code: String? = null,
)

/** Standard severity ladder. A crash is [ERROR] with a `code`. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
