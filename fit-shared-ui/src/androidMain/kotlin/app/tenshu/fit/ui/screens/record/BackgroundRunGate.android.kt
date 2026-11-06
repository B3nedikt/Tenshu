package app.tenshu.fit.ui.screens.record

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** One-time-ask bookkeeping, mirroring how Timeline's `MainActivity` guards its own prompt. */
private const val PREFS = "fit_recorder"
private const val KEY_ASKED_BATTERY = "asked_battery_exemption"

@Composable
actual fun rememberBackgroundRunGate(): BackgroundRunGate {
    val context = LocalContext.current
    return remember(context) { AndroidBackgroundRunGate(context) }
}

private class AndroidBackgroundRunGate(private val context: Context) : BackgroundRunGate {

    override fun isExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — the direct request, which is appropriate here
     * (a workout recorder is precisely the case the exemption exists for) and fine for a
     * self-distributed build; the lint suppression is the same one Timeline carries.
     *
     * `FLAG_ACTIVITY_NEW_TASK` because the composition's context may not be an Activity.
     *
     * ⚠ Exemption is necessary, not sufficient, on OEM skins: ColorOS/OPPO, MIUI and friends keep a
     * *separate* auto-launch / "sleep standby optimisation" list that this intent does not touch.
     */
    @SuppressLint("BatteryLife")
    override fun requestExemption(force: Boolean) {
        if (isExempt()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) return
            prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
