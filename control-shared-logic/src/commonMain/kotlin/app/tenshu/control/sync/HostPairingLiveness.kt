package app.tenshu.control.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.tenshu.sync.PairingRequest

/**
 * Idle ⇄ in-flight edge logic for Control's host-pairing foreground service
 * (`docs/proposals/control-pairing-ux.md` §3.5 / §3.6). The structural analogue of
 * `ForegroundTaskLiveness` / Drive's `NotificationLiveness`: it maps the
 * [HostPairingMonitor] session state to promote / refresh / demote transitions on a
 * platform [HostPairingNotificationHandle], and is deliberately free of Android imports so
 * the mapping is desktop-unit-testable.
 *
 * The Android effect (a `dataSync` foreground service whose ongoing notification keeps the
 * Control process — and the in-memory ephemeral pairing key — resident while pairing is
 * underway) lives behind the handle. There is no desktop effect (the tray/daemon process is
 * already long-lived) and no iOS effect beyond foreground-only.
 *
 * **Phase 3-Android folds in here (§3.6):** the *content* carries only [requestPending] +
 * [remainingSeconds] — a **PII-free** signal (no device name, no SAS). The Android layer
 * renders "Pairing in progress — m:ss" while waiting and "A device wants to join — tap to
 * review" once a request arrives; tapping routes to the in-app modal where the SAS
 * comparison actually happens (a notification can never approve — §6).
 */
class HostPairingLiveness(
    private val isPairing: StateFlow<Boolean>,
    private val remainingSeconds: StateFlow<Int?>,
    private val activeRequest: StateFlow<PairingRequest?>,
    private val handle: HostPairingNotificationHandle,
) {
    private var active = false

    /**
     * Collects the combined session state for the lifetime of [scope], driving [handle].
     * `distinctUntilChanged` (over the value-equal [HostPairingNotificationContent]) means
     * an unchanged tick is a no-op and only a real transition fires the handle.
     */
    fun launchIn(scope: CoroutineScope) {
        scope.launch {
            combine(isPairing, remainingSeconds, activeRequest) { pairing, remaining, request ->
                if (!pairing) {
                    null
                } else {
                    HostPairingNotificationContent(
                        requestPending = request != null,
                        remainingSeconds = remaining,
                    )
                }
            }.distinctUntilChanged().collect(::onContent)
        }
    }

    /**
     * Maps one combined session value to a liveness transition. Idempotent at the edges:
     * the service is promoted exactly once on the no-session → session edge, demoted exactly
     * once when the session clears, and only its notification text refreshes in between (the
     * countdown ticking, or the flip to "a device wants to join").
     */
    internal fun onContent(content: HostPairingNotificationContent?) {
        when (content) {
            null -> if (active) {
                active = false
                handle.onIdle()
            }

            else -> if (!active) {
                active = true
                handle.onActive(content)
            } else {
                handle.onContentChanged(content)
            }
        }
    }
}

/**
 * PII-free description of the host-pairing notification state (§3.6 / §6). Carries no device
 * name and no SAS — only whether a request is awaiting review and the seconds left in the
 * waiting window. The Android builder is a thin adapter that renders this to localised copy.
 */
data class HostPairingNotificationContent(
    /** True once a joiner's request has arrived and awaits the user's in-app SAS approval. */
    val requestPending: Boolean,
    /** Seconds left in the waiting window (null when paused/unknown). */
    val remainingSeconds: Int?,
)

/**
 * Platform effect of a host-pairing liveness transition. An interface so [HostPairingLiveness]
 * runs without an Android runtime (mirrors `TaskLivenessHandle`).
 */
interface HostPairingNotificationHandle {
    /** A host pairing session started — promote Control to a foreground service. */
    fun onActive(content: HostPairingNotificationContent)

    /** Still pairing, state changed (countdown tick, or a request arrived) — refresh. */
    fun onContentChanged(content: HostPairingNotificationContent)

    /** The session ended (approved / denied / expired) — remove the notification and demote. */
    fun onIdle()
}
