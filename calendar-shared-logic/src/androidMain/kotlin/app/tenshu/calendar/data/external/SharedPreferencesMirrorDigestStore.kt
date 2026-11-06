package app.tenshu.calendar.data.external

import android.content.Context

/**
 * [MirrorDigestStore] in the app's own preferences (CX-1b).
 *
 * ⚠ App-local by design: clearing Calendar's data only costs the next refresh a full diff.
 */
class SharedPreferencesMirrorDigestStore(context: Context) : MirrorDigestStore {

    private val prefs = context.getSharedPreferences("calendar-mirror-digests", Context.MODE_PRIVATE)

    override fun get(mirrorId: String): String? = prefs.getString(mirrorId, null)

    override fun put(mirrorId: String, digest: String) {
        prefs.edit().putString(mirrorId, digest).apply()
    }
}
