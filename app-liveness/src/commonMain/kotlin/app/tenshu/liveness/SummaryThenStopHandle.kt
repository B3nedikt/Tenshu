package app.tenshu.liveness

/**
 * The Android effects a foreground-service notification needs, as plain calls, so the *order* they
 * run in is unit-testable without an Android runtime.
 */
interface ForegroundNotificationOps {
    /** `startForegroundService` + `startForeground` with an ongoing card. False if the OS refused
     *  (the implementation unwinds its own started lifecycle — see [startAndPromoteForeground]). */
    fun promote(content: LivenessNotificationContent): Boolean

    /** Refresh the ongoing card in place. */
    fun updateOngoing(content: LivenessNotificationContent)

    /** `stopForeground(STOP_FOREGROUND_REMOVE)`: demote and take the ongoing card down with it. */
    fun removeOngoing()

    /** Post the dismissible summary under its **own** notification id. */
    fun postSummary(content: LivenessNotificationContent)

    /** Cancel a summary left over from an earlier run. */
    fun cancelSummary()

    /** `stopSelf()`. */
    fun stopService()
}

/**
 * A [LivenessNotificationHandle] for work whose state **never clears** after its terminal summary
 * (Fit's import: `Imported` / `Failed` stay as the screen's banner). There will be no `onIdle` to
 * finish the job, so the terminal edge does all of it: remove the ongoing card, post the summary
 * separately, stop the service.
 *
 * ⚠⚠ Why not Drive's `STOP_FOREGROUND_DETACH` + `notify(sameId, summary)`: Android rate-limits
 * notification *updates* (~5 enqueues/s per app) by dropping them, and the summary on the same id
 * is an update. When it is dropped, DETACH has already left the ongoing "Importing…" card posted
 * with no service behind it, and nobody ever removes it (observed 2026-10-30, after both a Takeout
 * and a Health Connect import). REMOVE is never rate-limited, and a summary on a fresh id is a new
 * post, not an update.
 */
class SummaryThenStopHandle(private val ops: ForegroundNotificationOps) : LivenessNotificationHandle {
    /** Whether the OS let us promote; a refused promotion must not post an ongoing notification. */
    private var promoted = false

    override fun onActive(content: LivenessNotificationContent) {
        // A previous run's summary would sit beside the new progress card, and a later summary
        // posted over it would be an update — the very thing that gets dropped.
        ops.cancelSummary()
        promoted = ops.promote(content)
    }

    override fun onContentChanged(content: LivenessNotificationContent) {
        // Retry a refused promotion: the app may have been foregrounded since.
        if (!promoted) {
            promoted = ops.promote(content)
            return
        }
        ops.updateOngoing(content)
    }

    override fun onTerminal(content: LivenessNotificationContent) {
        promoted = false
        ops.removeOngoing()
        ops.postSummary(content)
        ops.stopService()
    }

    override fun onIdle() {
        promoted = false
        ops.removeOngoing()
        ops.stopService()
    }
}
