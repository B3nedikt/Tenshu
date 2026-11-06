package app.tenshu.ui.storage

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android's install route: a Play link only to a copy that came from Play, the website's download
 * page to a copy that was downloaded (ADR 0025 §Decision 6, amended 2026-10-31).
 *
 * ⚠⚠ **The channel is a property of the installed copy**, so this is decided at run time from the
 * package manager's record of which installer wrote the app. A Play build and a downloaded one are
 * different artifacts now (different signers; Control also differs by billing rail), but a
 * Play-signed APK copied off a phone is still a Play artifact installed some other way.
 *
 * The decision itself is [androidControlInstallRoute], which is host-tested.
 */
@Composable
actual fun rememberControlInstallRoute(): ControlInstallRoute {
    val context = LocalContext.current
    return remember(context) {
        androidControlInstallRoute(
            installer = installerOf(context.packageManager, context.packageName),
            debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            playUrl = ControlStoreConfig.PLAY_URL,
            downloadUrl = CONTROL_ANDROID_DOWNLOAD_URL,
        )
    }
}

/**
 * ⚠ `getInstallSourceInfo` is API 30+; below that the deprecated call is the only answer there is.
 * ⚠⚠ A throw (the package genuinely not being there, which cannot happen for our own name, or an OEM
 * that refuses) is [InstallerRecord.Unknown], never "no installer": `null` routes to the website,
 * and a Play copy sent there ends up with two channels (ADR 0025: silence is not absence).
 */
internal fun installerOf(packageManager: PackageManager, packageName: String): InstallerRecord = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        packageManager.getInstallSourceInfo(packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(packageName)
    }
}.fold(onSuccess = { InstallerRecord.Known(it) }, onFailure = { InstallerRecord.Unknown })
