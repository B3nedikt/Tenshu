package app.tenshu.timeline.capture

import kotlinx.coroutines.flow.StateFlow

/**
 * The **user's opt-in for live location capture** (`docs/proposals/timeline-ingestion.md` §4/§6) —
 * the persisted intent, decoupled from the platform machinery that enacts it. The shared Settings
 * screen renders a toggle bound to this **only when a platform binds one** (Koin `getOrNull`): the
 * Android app binds a real control (persists the flag + the foreground service acts on it), while
 * desktop/iOS bind nothing, so the toggle simply doesn't appear.
 *
 * Capture is **off by default** and this is the *only* switch that turns it on (threat-model A35):
 * flipping [enabled] to `true` is the explicit consent; the platform then handles the OS permission
 * grant and starts the capture service, and reflects a denied permission back by resetting the flag.
 */
interface TimelineCaptureControl {
    /** Whether the user has opted into capture. Survives process death (platform-persisted). */
    val enabled: StateFlow<Boolean>

    /** Record the user's choice. The platform enacts it (permission + foreground service). */
    fun setEnabled(enabled: Boolean)
}
