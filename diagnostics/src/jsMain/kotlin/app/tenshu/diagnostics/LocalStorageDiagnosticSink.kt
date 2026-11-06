package app.tenshu.diagnostics

import kotlinx.browser.window

/**
 * The web's diagnostic sink (`drive-web-parity.md` W9). Until this existed the browser had no
 * durable log at all: the JS SDK's handful of `println`s went to a console only the user could
 * read and a reload erased — on the one platform where a maintainer *cannot* attach a debugger,
 * because Chrome refuses `--remote-debugging-port` on the default profile.
 *
 * ⚠⚠ `localStorage`, not IndexedDB, and that is forced by the contract: [DiagnosticSink.append]
 * must be **synchronous** ("the crash handler calls it while the process is dying"). Every
 * IndexedDB write is async, so an IDB-backed sink could not honour it — the same shape that
 * blocks a JS `GetPreview` (W2b). `localStorage` is the only synchronous durable store a browser
 * offers.
 *
 * ⚠ Cost of that choice, stated because it is real: `localStorage` is main-thread blocking and
 * capped near 5 MB per origin. [capacity] keeps the ring far inside that, and a diagnostic write
 * is rare relative to a frame.
 *
 * ⚠ Evictable, like everything else in a browser origin (§1.2d). Losing diagnostics is the least
 * bad thing an eviction can take.
 */
class LocalStorageDiagnosticSink(
    private val key: String = DEFAULT_KEY,
    capacity: Int = 500,
    private val now: () -> Long = { kotlin.js.Date.now().toLong() },
) : DiagnosticSink {

    private val ring = DiagnosticRing(capacity)

    override fun append(level: LogLevel, tag: String, message: String, code: String?) {
        // Must never throw: a logging failure must not worsen whatever is already going wrong.
        // A full or disabled localStorage (private mode, quota) simply drops the line.
        runCatching {
            val kept = ring.appended(readAll(), LogItem(now(), level, tag, message, code))
            window.localStorage.setItem(key, kept.joinToString("\n") { DiagnosticLine.encode(it) })
        }
    }

    override fun recent(limit: Int): List<LogItem> =
        readAll().let { it.subList((it.size - limit).coerceAtLeast(0), it.size) }

    override fun clear() {
        runCatching { window.localStorage.removeItem(key) }
    }

    private fun readAll(): List<LogItem> = runCatching {
        window.localStorage.getItem(key)
            ?.lineSequence()
            ?.mapNotNull(DiagnosticLine::decode)
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_KEY = "tenshu-diagnostics"
    }
}
