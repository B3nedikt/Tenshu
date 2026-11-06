package app.tenshu.reachability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retry a read stream that **threw**, instead of catching it into an empty emission.
 *
 * ## ⚠⚠ Why `catch { emit(empty) }` is worse than it looks
 *
 * `catch` **completes** the flow. So the familiar shape —
 *
 * ```kotlin
 * .catch { if (it is CancellationException) throw it; reach.markUnreachable(); emit(emptyList()) }
 * ```
 *
 * — does two damaging things in one line. It leaves the screen **permanently dead**: nothing will
 * ever emit again, whatever the proxy does, unless something external rebuilds the collector. And
 * on the way out it emits an empty list, telling the screen *"you have nothing"* at the one moment
 * it cannot know — which is the silent-blank failure the reachability banner exists to prevent,
 * arriving from inside the app rather than from the proxy.
 *
 * Retrying keeps the last good rows on screen and backs off, so a deterministic throw degrades to a
 * slow, named, logged loop rather than a blank.
 *
 * ## ⚠ It does not mark the engine unreachable, and that is the point
 *
 * A throw is not evidence the proxy is gone. A dead transport does not reach here at all:
 * `TList.flow()` retries a failed *subscribe* itself — with backoff, sleeping on the
 * reconnect edge rather than a timer — and reports the failure as a `TResult.Failure` **emission**,
 * which a fold already handles. What is left for this operator is a genuine throw: no context, a
 * decode failure, a projection function that threw on one bad row. Reporting those as an outage
 * parks the banner on what is almost always a client-side bug, and tells the user to check a
 * connection that is working. [log] names the read instead, which is the diagnosis the
 * "rendering empty" line could never give.
 *
 * ⚠ [CancellationException] propagates rather than retrying — `retryWhen` does **not** exempt it,
 * so swallowing it here would keep a cancelled screen's read alive forever.
 *
 * Shared by Fit, Timeline and Maps, so the copies cannot drift.
 *
 * @param read the read's name, for the log line — a caller-supplied label, since a stack trace at
 *   this point names the operator and not the query.
 * @param log where to say it. Each app keeps its own prefix.
 */
fun <T> Flow<T>.retryingOnThrow(
    read: String,
    log: (String, Throwable) -> Unit,
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    log("read '$read' THREW, retrying: ${cause::class.simpleName}", cause)
    val step = attempt.coerceAtMost((READ_RETRY_BACKOFF_MS.size - 1).toLong()).toInt()
    delay(READ_RETRY_BACKOFF_MS[step].milliseconds)
    true
}

/**
 * ⚠ Mirrors `Stream.kt`'s `resubscribeDelayMs` so a stream that is *retrying its subscribe* and one
 * that is *retrying after a throw* back off at the same rate — two different recoveries that a
 * reader watching a log should not have to tell apart by their timing.
 */
val READ_RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
