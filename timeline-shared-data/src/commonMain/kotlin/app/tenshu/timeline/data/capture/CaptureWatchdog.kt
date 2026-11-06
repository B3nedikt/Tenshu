package app.tenshu.timeline.data.capture

/**
 * The pure decision behind Android capture resilience (`timeline-app.md` §7 / the M3b Doze tail):
 * **should the capture foreground service be (re)started right now?**
 *
 * Kept platform-free and tiny so the policy is unit-tested without an emulator; the Android glue
 * (boot receiver, WorkManager watchdog) supplies the observed state and enacts the verdict.
 *
 * ### Why this is needed at all
 *
 * On stock Android `START_STICKY` + the `MainActivity` opt-in observer keep capture alive. On
 * aggressive OEM ROMs (ColorOS/OPPO/OnePlus/Xiaomi/…) the battery manager **force-stops** the whole
 * app process, which `START_STICKY` does *not* survive, and nothing re-arms the service because the
 * only start path was foreground-only (in `MainActivity`). Observed directly: a captured device with
 * the opt-in on, its foreground-service notification still showing (orphaned in SystemUI), and
 * `pidof` returning nothing — capture silently dead until the user next opened the app.
 *
 * The watchdog closes that gap: while the opt-in is on, if the service is not actually running, it
 * is restarted — from a boot receiver (after a reboot) and a periodic worker (after a mid-session
 * kill). This decision function is the shared brain both call.
 */
data class CaptureWatchdogState(
    /** The user's persisted opt-in (`TimelineCaptureControl.enabled`). */
    val captureEnabled: Boolean,
    /** Whether the capture foreground service is currently running in this app's process. */
    val serviceRunning: Boolean,
)

/** The verdict, with a human reason for the on-device diagnostic trail. */
data class CaptureWatchdogVerdict(
    val restart: Boolean,
    val reason: String,
)

/**
 * Decide whether to (re)start capture. Restart iff the user wants capture **and** the service is not
 * running. Deliberately conservative — it never *stops* anything (that stays the opt-in's job), so a
 * spurious "running" reading at worst delays a restart to the next tick rather than tearing down a
 * healthy service.
 */
fun decideCaptureRestart(state: CaptureWatchdogState): CaptureWatchdogVerdict = when {
    !state.captureEnabled -> CaptureWatchdogVerdict(restart = false, reason = "capture opt-in is off")
    state.serviceRunning -> CaptureWatchdogVerdict(restart = false, reason = "service already running")
    else -> CaptureWatchdogVerdict(restart = true, reason = "opt-in on but service not running — restarting")
}
