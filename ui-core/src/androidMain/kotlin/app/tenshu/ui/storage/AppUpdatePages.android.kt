package app.tenshu.ui.storage

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android's update pages: each one decided from the installer record of **the copy it updates**
 * ([AppUpdatePages]). The decision itself is [androidAppUpdatePages], which is host-tested.
 *
 * ⚠ Remembered per context: a Control installed while this screen stays composed is seen on the next
 * recreate. Cheap enough to accept — a skew is a durable state, not a moment.
 *
 * ⚠ Reading Control's record needs Control to be visible to this app, which every first-party app's
 * `<queries>` entry already grants (the launcher and the Binder bind need it too). A Control that
 * is not installed, or a lookup that throws, is no page — never the website.
 */
@Composable
actual fun rememberAppUpdatePages(): AppUpdatePages {
    val context = LocalContext.current
    return remember(context) {
        val pm = context.packageManager
        val own = context.packageName
        val control = runCatching { pm.getApplicationInfo(CONTROL_ANDROID_PACKAGE, 0) }.getOrNull()?.let { info ->
            AndroidCopy(
                installer = installerOf(pm, CONTROL_ANDROID_PACKAGE),
                debuggable = (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                packageName = CONTROL_ANDROID_PACKAGE,
            )
        }
        androidAppUpdatePages(
            own = AndroidCopy(
                installer = installerOf(pm, own),
                debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                packageName = own,
            ),
            control = control,
            playUrls = ControlStoreConfig.APP_PLAY_URLS,
        )
    }
}
