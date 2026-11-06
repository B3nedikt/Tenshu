package app.tenshu.ingest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus

/**
 * This device's sync identity, resolved from the proxy and cached — the key every per-device row
 * is stored under: Drive's display / offline / sync-rules / backup preferences and its ingest
 * registry, and (since PH-4) Photos' view of that registry.
 *
 * ⚠ It lives here rather than in an app so there is **one** copy of the retry below. It was
 * Drive-private until Photos needed the same answer, and a startup-race fix duplicated is a
 * startup-race fix that gets repaired in one place only.
 *
 * ⚠ **Resolution is retried, not one-shot.** Each of those seams used to resolve the id
 * once at subscribe time and, when it came back blank, emit a default and let the read
 * flow **complete** — permanently, because a screen holds its subscription for its whole
 * lifetime, so `stateIn` never restarts the upstream. A screen that opened before sync
 * identity was up therefore stayed on the default for the rest of the session: its writes
 * no-op'd (`?: return`) and its reads never re-emitted. That is a race the caller cannot
 * see or recover from, and it bites iOS route-1 hardest — `DriveLocalRuntime.install()`
 * and the Files screen come up in the same breath (the "Sort by does nothing" report).
 *
 * [await] instead suspends until the id is available, so a flow built on it emits its seed
 * immediately and switches to the real rows the moment identity lands. Cancellation is the
 * exit: when the collector goes away, so does the retry.
 */
class DeviceIdentity(private val resolve: suspend () -> String?) {

    constructor(client: TenshuClient) : this({
        client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()?.takeIf { it.isNotBlank() }
    })

    private val mutex = Mutex()
    private var cached: String? = null

    /**
     * Suspends until this device's id is known, polling the proxy on a backoff. Never
     * returns blank. Callers that must not hang (a write behind a user gesture) use
     * [awaitOrNull] instead.
     */
    suspend fun await(): String {
        var delayMs = INITIAL_RETRY_MS
        while (true) {
            resolveOnce()?.let { return it }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    /**
     * [await], bounded by [timeoutMs] — `null` when identity is still unresolved by then.
     * The shape writes want: retry across the startup race, but give up rather than leave a
     * coroutine parked on a tap that will never complete.
     */
    suspend fun awaitOrNull(timeoutMs: Long = WRITE_TIMEOUT_MS): String? =
        withTimeoutOrNull(timeoutMs) { await() }

    /**
     * One resolution attempt. A throw means the proxy is unreachable *right now* (no Control
     * paired yet, IPC not up) — which is the state to retry through, not to propagate into the
     * collector's flow, so it counts as "unresolved".
     * ⚠ [CancellationException] must not be swallowed: it is how the retry loop exits.
     */
    private suspend fun resolveOnce(): String? = mutex.withLock {
        cached ?: try {
            resolve()?.also { cached = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val INITIAL_RETRY_MS = 100L
        const val MAX_RETRY_MS = 2_000L

        /**
         * Long enough to ride out the startup race, short enough that a caller which genuinely
         * has no proxy (Drive with no Control paired) degrades to the old no-op instead of
         * stalling a suspending read behind it.
         */
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
