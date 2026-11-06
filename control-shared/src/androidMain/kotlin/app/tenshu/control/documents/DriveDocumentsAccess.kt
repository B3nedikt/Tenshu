package app.tenshu.control.documents

import android.content.Context
import android.provider.DocumentsContract

/**
 * The master switch "Let other apps open your Drive files" (`cross-app-file-access.md` D-D).
 *
 * Device-local and **on by default**, matching Google Drive and OneDrive: a provider nobody can
 * discover delivers no parity. Per-file access is the system picker's job — the requesting app
 * only ever receives a grant for what the user picked — so this is a kill switch, not an
 * allowlist.
 *
 * Read by [DriveDocumentsProvider] on **every** call, not only when listing roots: turning it off
 * must also stop a URI an app was granted earlier.
 */
object DriveDocumentsAccess {
    private const val PREFS = "drive_documents"
    private const val KEY_ENABLED = "enabled"

    /** Must match the `android:authorities` suffix declared in Control's manifest. */
    private const val AUTHORITY_SUFFIX = ".documents"

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // The system picker caches roots; tell it the Drive root appeared or went away.
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
    }
}
