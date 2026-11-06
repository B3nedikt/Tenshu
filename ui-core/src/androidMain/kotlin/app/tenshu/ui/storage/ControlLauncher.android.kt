package app.tenshu.ui.storage

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Open Control on its **Services** screen via its launcher intent — the shared form of what
 * `AndroidStorageSetupLauncher` does for Drive, so the other six apps do not each grow a copy of
 * the same twenty lines.
 *
 * ⚠ Requires a `<queries>` entry for `app.tenshu.control.android` in the calling app's manifest.
 * Without it `getLaunchIntentForPackage` returns `null` under Android 11+ package visibility even
 * when Control is installed, and this returns `null` — the banner then states the situation without
 * offering a dead button.
 *
 * ⚠ No manifest filter is needed on Control's side: the target is its exported LAUNCHER activity,
 * and the destination rides as a plain string extra — the deep-link contract Control's
 * `MainActivity` already reads for Drive.
 */
@Composable
actual fun rememberControlServicesLauncher(): (() -> Unit)? {
    val context = LocalContext.current
    val intent = remember(context) {
        context.packageManager
            .getLaunchIntentForPackage(CONTROL_PACKAGE)
            ?.apply {
                putExtra(EXTRA_DEEPLINK, CONTROL_SERVICES_DEEPLINK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    } ?: return null
    return remember(intent, context) { { context.startActivity(intent) } }
}

private const val CONTROL_PACKAGE = CONTROL_ANDROID_PACKAGE

/** ⚠ Kept in sync with Control's `MainActivity.EXTRA_DEEPLINK` and Drive's launcher. */
private const val EXTRA_DEEPLINK = "app.tenshu.control.deeplink"
