package app.tenshu.calendar.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * This device's sync id — the key every per-device calendar preference row is
 * stored under.
 *
 * ⚠⚠ **Resolution is retried, not one-shot**, and that is the whole reason this
 * type exists rather than a `String` parameter. Resolving the id once at
 * construction and falling back to a default when it comes back blank leaves the
 * screen on that default *for the rest of the session*, because a screen holds its
 * subscription for its whole lifetime: its writes no-op and its reads never
 * re-emit, and the user sees a setting that silently does nothing (Drive's
 * `DeviceIdentity` guards the same case).
 *
 * ⭐ Drive's `DeviceIdentity` is the other copy of this; promoting one into a shared
 * module, rather than each app owning a copy, is the open refactor.
 *
 * ## Two kinds of "no id", and why they cannot share a policy
 *
 * - **Not yet** — the resolver returns null/blank because sync identity is still
 *   coming up. Retrying is exactly right, and is the case above.
 * - **Never** — the resolver *throws*, because the id cannot be obtained by
 *   construction: the resolver reads `client.syncService`, and in a client graph
 *   that registers no sync service `TenshuEnvironment.getService` throws — straight
 *   out of a coroutine on the main thread, a crash on every launch, if uncaught.
 *
 * Retrying a permanent failure is not a fix — it converts a visible crash into
 * an invisible hang, with the calendar list suspended forever on a screen that
 * looks merely slow. So a throw **latches** [unavailable] and every subsequent
 * wait answers `null` immediately.
 *
 * ⚠ `null` is not a value callers may substitute a default id for. A
 * per-device preference read under the wrong key is the silent no-op described
 * above. Read paths degrade to the preference's declared default (for
 * `HiddenCalendars`: nothing hidden, i.e. show everything); write paths must
 * refuse rather than write under a key that is not this device.
 */
class CalendarDeviceIdentity(private val resolve: suspend () -> String?) {

    private val mutex = Mutex()
    private var cached: String? = null

    /**
     * Latched when [resolve] throws — the id is unobtainable in this graph, not
     * merely late. Never reset: nothing that could fix it happens within a
     * process (the service set is fixed at DI construction).
     */
    private var unavailable = false

    /**
     * Suspends until the id is known, polling on a backoff; `null` once the id is
     * known to be **unobtainable**. Never returns blank.
     */
    suspend fun awaitOrNull(): String? {
        var delayMs = INITIAL_RETRY_MS
        while (true) {
            if (unavailable) return null
            resolveOnce()?.let { return it }
            if (unavailable) return null
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private suspend fun resolveOnce(): String? = mutex.withLock {
        cached ?: runCatchingCancellable { resolve() }
            .onFailure { failure ->
                // ⚠ One line, once: this latches, so it cannot become a per-retry log spam.
                // It names the cause because the symptom downstream ("no calendars are
                // hidden", "the toggle did nothing") looks nothing like its origin.
                unavailable = true
                println(
                    "WARN CalendarDeviceIdentity: this device's sync id is unobtainable — " +
                        "per-device calendar preferences are disabled for this session " +
                        "(${failure::class.simpleName}: ${failure.message})"
                )
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { cached = it }
    }

    private companion object {
        const val INITIAL_RETRY_MS = 50L
        const val MAX_RETRY_MS = 2_000L
    }
}
