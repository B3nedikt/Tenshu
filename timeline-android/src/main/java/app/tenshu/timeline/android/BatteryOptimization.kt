package app.tenshu.timeline.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption — the thing that lets background capture actually survive on
 * aggressive OEMs, and the thing no app-side code can substitute for.
 *
 * Even a correct foreground service is force-stopped by ColorOS/OPPO/Xiaomi/… unless the app is on
 * the battery allowlist (and often a *separate* OEM "auto-launch" list on top). Exemption also lifts
 * the Android 12+ restriction on starting a foreground service from the background, which is what
 * makes the boot receiver and watchdog restarts reliably *allowed*. So this is both a durability and
 * a correctness lever.
 */
internal object BatteryOptimization {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system prompt asking the user to exempt this app ("Allow Timeline to run in the
     * background?"). Appropriate for a genuine continuous-location app; this build is self-
     * distributed, not Play-listed, so the direct-request action is fine.
     */
    @SuppressWarnings("BatteryLife")
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
