package app.tenshu.timeline.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts capture after the device reboots or the app is updated, if the opt-in is on
 * (M3b resilience tail). Without this, a normal reboot leaves capture dead until the user next opens
 * the app — the foreground-service start path was otherwise only in `MainActivity`.
 *
 * `BOOT_COMPLETED` is one of the windows in which starting a `location` foreground service from the
 * background is *allowed* on Android 12+, so the re-arm here is not subject to the same refusal the
 * mid-session watchdog can hit.
 */
class CaptureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> CaptureRearm.rearmIfNeeded(context, trigger = intent.action ?: "boot")
        }
    }
}
