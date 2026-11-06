package app.tenshu.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Generic idle ⇄ in-flight ⇄ terminal edge logic for a foreground service driven by a controller's
 * `StateFlow`.
 *
 * Several kinds of long-running, user-visible background work need the *identical* liveness anchor:
 * keep the process alive while work is in flight, carry a notification, demote on a terminal summary,
 * remove on clear. Drive's folder upload and Synced-Folders auto-backup were the first two; Fit's
 * Google Takeout import is the third (it writes tens of thousands of records over minutes and must
 * survive the user backgrounding the app). The only thing that differs between them is the state type
 * and its copy mapping — so that becomes a type parameter [T] + a [toContent] mapper, and the edge
 * logic lives here once, in its own app-neutral module rather than forked per app.
 *
 * Free of Android imports so it is desktop-unit-testable; the Android effect (foreground service +
 * notification) lives behind [LivenessNotificationHandle].
 *
 * ⚠⚠ [minContentChangeInterval] exists because Android **drops** notification updates past ~5
 * enqueues/s per app ("Package enqueue rate is … Shedding"). A state that ticks per record (Fit's
 * import) froze its card at the first few counts and — worse — had its final summary update shed
 * too, leaving an un-dismissable "Importing…" behind (2026-10-30). Only in-flight refreshes are
 * throttled; the active, terminal and idle edges always go through.
 */
class NotificationLiveness<T>(
    private val state: StateFlow<T>,
    private val toContent: (T) -> LivenessNotificationContent?,
    private val handle: LivenessNotificationHandle,
    private val minContentChangeInterval: Duration = Duration.ZERO,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    // `active` = FGS promoted (held while in flight); `shown` = a notification is currently posted
    // (stays true through the terminal-summary window after demotion, until the controller clears
    // state). They differ precisely because terminal demotes the FGS but keeps the notification
    // visible until the controller clears state — without the second flag the final clear→idle edge
    // would be swallowed once `active` was already reset by the terminal edge.
    private var active = false
    private var shown = false

    /** Whether the current idle period already delivered its onIdle (incl. the initial one). */
    private var idleDelivered = false

    /** When the ongoing card was last posted (promotion or refresh), for [minContentChangeInterval]. */
    private var lastPosted: TimeMark? = null

    /**
     * Collects [state] for the lifetime of [scope], driving [handle]. `StateFlow` conflates and only
     * emits distinct consecutive values, so no explicit `distinctUntilChanged` is needed.
     */
    fun launchIn(scope: CoroutineScope) {
        scope.launch { state.collect(::onState) }
    }

    /** Maps one observed state value to a liveness transition. Idempotent at the edges: in flight
     *  only the notification text refreshes; the service is promoted exactly once on
     *  idle→in-flight and demoted exactly once on in-flight→terminal/idle. */
    fun onState(value: T) {
        when (val content = toContent(value)) {
            null -> {
                // Fires on INITIAL idle too (was gated on `shown`): a service started for work
                // that finished before this collector attached — e.g. a permissions-missing
                // import that ABORTs in ~1 ms — must still stop itself, or Android kills the
                // app with ForegroundServiceDidNotStartInTimeException (observed as a Fit
                // launch crashloop on grant-less devices). At most one onIdle per idle period:
                // repeated idles stay silent.
                active = false
                val hadShown = shown
                shown = false
                if (hadShown || !idleDelivered) {
                    idleDelivered = true
                    handle.onIdle()
                }
            }

            else -> {
                idleDelivered = false
                when (content.phase) {
                    LivenessPhase.IN_FLIGHT ->
                        if (!active) {
                            active = true
                            shown = true
                            lastPosted = timeSource.markNow()
                            handle.onActive(content)
                        } else if (lastPosted.let { it == null || it.elapsedNow() >= minContentChangeInterval }) {
                            lastPosted = timeSource.markNow()
                            handle.onContentChanged(content)
                        }

                    LivenessPhase.TERMINAL -> {
                        active = false
                        shown = true
                        handle.onTerminal(content)
                    }
                }
            }
        }
    }
}

/** Whether work is still running (ongoing notification + FGS held) or has produced a terminal
 *  summary (notification stays briefly, then the FGS is demoted so it auto-clears). */
enum class LivenessPhase { IN_FLIGHT, TERMINAL }

/** Fully-rendered notification content. Android-free so it can be asserted directly in a desktop
 *  unit test; the Android builder is a thin adapter. */
data class LivenessNotificationContent(
    val title: String,
    val text: String,
    val phase: LivenessPhase,
)

/** Android-side effect of a liveness transition. An interface so the edge logic runs without an
 *  Android runtime. */
interface LivenessNotificationHandle {
    /** Work started — promote the process to a foreground service with [content]. */
    fun onActive(content: LivenessNotificationContent)

    /** Still in flight, progress changed — refresh the ongoing notification. */
    fun onContentChanged(content: LivenessNotificationContent)

    /** Reached a terminal summary — show [content] but demote the FGS so it is dismissible and
     *  auto-clears (the controller clears state shortly after). */
    fun onTerminal(content: LivenessNotificationContent)

    /** State cleared — remove the notification and demote the service. */
    fun onIdle()
}
