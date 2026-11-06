package app.tenshu.drive.data

import android.content.Context
import android.content.Intent

/**
 * Open the Control app on its **Services** screen
 * (`docs/proposals/storage-capacity-gating.md` §4 / Phase 5). Reuses Control's
 * launcher intent (Drive declares a `<queries>` entry for the package, so
 * resolution succeeds when Control is installed) and rides a plain string extra
 * — the deep-link contract Control's `MainActivity` reads. No manifest filter
 * is needed: the target is Control's exported LAUNCHER activity. If Control is
 * absent, this is a no-op.
 */
class AndroidStorageSetupLauncher(
    private val context: Context,
) : StorageSetupLauncher {

    override fun openStorageSetup() {
        val intent: Intent = context.packageManager
            .getLaunchIntentForPackage(CONTROL_PACKAGE)
            ?.apply {
                putExtra(EXTRA_DEEPLINK, DEEPLINK_SERVICES)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ?: return
        context.startActivity(intent)
    }

    private companion object {
        const val CONTROL_PACKAGE = "app.tenshu.control.android"

        // Cross-app deep-link contract — kept in sync with Control's
        // `MainActivity.EXTRA_DEEPLINK` / `DEEPLINK_SERVICES`.
        const val EXTRA_DEEPLINK = "app.tenshu.control.deeplink"
        const val DEEPLINK_SERVICES = "services"
    }
}
