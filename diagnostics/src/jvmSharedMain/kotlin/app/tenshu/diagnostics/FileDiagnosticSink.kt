package app.tenshu.diagnostics

import java.io.File

/**
 * A synchronous, crash-safe [DiagnosticSink] backed by a newline-delimited file in the app's private
 * storage. Chosen over `SharedPreferences` (poor fit for a growing list) and over a synced schema row
 * (see the module KDoc — diagnostics are device-local): a plain append-file is durable, crash-safe by
 * nature (the OS flushes on close), and trivially readable over `adb` as a fallback.
 *
 * Each line is `timestampMs\tLEVEL\ttag\tcode\tmessage` (tabs stripped from fields so the split is
 * unambiguous; message newlines escaped). Low write volume (transitions and crashes, not every GPS
 * fix), so a full re-read + rewrite to enforce the ring cap on each append is fine.
 */
class FileDiagnosticSink(
    private val file: File,
    capacity: Int = 500,
    private val now: () -> Long = { System.currentTimeMillis() },
) : DiagnosticSink {

    private val ring = DiagnosticRing(capacity)
    private val lock = Any()

    override fun append(level: LogLevel, tag: String, message: String, code: String?) {
        // Must never throw — this runs on the crash path.
        runCatching {
            synchronized(lock) {
                val item = LogItem(now(), level, tag, message, code)
                val kept = ring.appended(readAll(), item)
                file.parentFile?.mkdirs()
                file.writeText(kept.joinToString("\n") { DiagnosticLine.encode(it) })
            }
        }
    }

    override fun recent(limit: Int): List<LogItem> =
        synchronized(lock) { readAll().let { it.subList((it.size - limit).coerceAtLeast(0), it.size) } }

    override fun clear() = synchronized(lock) { runCatching { file.delete() }.let { } }

    private fun readAll(): List<LogItem> = runCatching {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull(DiagnosticLine::decode)
    }.getOrDefault(emptyList())
}
