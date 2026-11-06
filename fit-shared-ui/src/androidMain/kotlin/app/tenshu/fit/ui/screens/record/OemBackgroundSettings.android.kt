package app.tenshu.fit.ui.screens.record

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Candidate vendor auto-start screens, tried in order. Two shapes, because vendors ship both: an
 * exported action (which survives the activity being renamed) and a bare component.
 *
 * ⚠ Verified on hardware: the first entry only, read off a ColorOS CPH2581 that had just killed a
 * live recording (`dumpsys package com.oplus.battery`). ⚠⚠ And *verified to be unlaunchable* there —
 * see [OemBackgroundSettings]; it is kept because the same action is reported to work on older
 * ColorOS builds, and because trying costs one caught exception. The remaining rows are the
 * widely-circulated components for their skins, **unverified here**. Expect rot: these are private
 * vendor surfaces, renamed at will. A stale row costs nothing — it simply fails and we fall back.
 */
private val AUTO_START_TARGETS: List<Intent> = listOf(
    // ColorOS (OPPO / OnePlus / realme).
    Intent("com.oplus.battery.permission.startup.StartupAppListActivity"),
    Intent().setComponent(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    ),
    Intent().setComponent(
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    // MIUI / HyperOS (Xiaomi, Redmi, POCO).
    Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    // EMUI / HarmonyOS (Huawei, Honor).
    Intent().setComponent(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    ),
    // Funtouch / OriginOS (vivo, iQOO).
    Intent().setComponent(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    ),
)

@Composable
actual fun rememberOemBackgroundSettings(): OemBackgroundSettings {
    val context = LocalContext.current
    return remember(context) { AndroidOemBackgroundSettings(context) }
}

private class AndroidOemBackgroundSettings(private val context: Context) : OemBackgroundSettings {

    /** The app's own settings page — the guaranteed landing spot, and the reason this is never a
     *  dead end. On ColorOS it holds "Battery usage → Allow background activity". */
    private fun appSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun canOpen(): Boolean =
        runCatching { appSettings().resolveActivity(context.packageManager) != null }.getOrDefault(false)

    override fun openBackgroundSettings() {
        for (target in AUTO_START_TARGETS) {
            // resolveActivity first to skip the obvious misses cheaply; the try/catch is what
            // actually decides, because a vendor screen can resolve and still refuse to start.
            val intent = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching { context.startActivity(appSettings()) }
    }
}
