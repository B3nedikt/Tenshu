package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable

/**
 * The "will the OS let this recording finish?" seam — battery-optimisation exemption, the platform
 * lever no app-side code substitutes for.
 *
 * A correct foreground service is **not** enough on an aggressive OEM. Observed on a ColorOS device
 * (2026-08-15): a 21-minute run recording was killed mid-stride at 13:54:40 with
 * `reason=13 (OTHER KILLS BY SYSTEM) … description=o-kill(6)`, `importance=125` — i.e. the system
 * killed a process that was holding a foreground service, 1.4 s after the last GPS fix. Nothing was
 * dropped by the capture path; the rest of the run simply had no app to record it. Exemption is what
 * prevents that, so the recorder asks for it the way Timeline's capture does
 * (`timeline-android/.../BatteryOptimization.kt`).
 *
 * `:fit-shared-ui` is android + desktop only, so this is a plain `expect`/`actual` — the desktop
 * actual reports exempt and does nothing, exactly as [RecorderPermissionGate] collapses there.
 */
interface BackgroundRunGate {

    /**
     * Whether this app is exempt from battery optimisation. `true` on a platform with no such
     * notion — the caller then shows no cue and asks for nothing.
     */
    fun isExempt(): Boolean

    /**
     * Shows the system exemption prompt.
     *
     * [force] `false` is the Start-time nudge: it fires **at most once per install**, so a user who
     * declines is not re-asked on every workout. [force] `true` is the explicit tap on the pre-start
     * cue, which must always open the prompt — that cue is the way back for anyone who dismissed
     * the one-time ask, and a silently-ignored tap would be worse than no cue at all.
     */
    fun requestExemption(force: Boolean = false)
}

@Composable
expect fun rememberBackgroundRunGate(): BackgroundRunGate
