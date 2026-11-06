package app.tenshu.calendar.data.external

import platform.Foundation.NSUserDefaults

/**
 * [MirrorDigestStore] over `NSUserDefaults` — the iOS twin of Android's
 * `SharedPreferencesMirrorDigestStore` (`calendar-external-sources.md` CX-2b).
 *
 * ⚠ Device-local by design: a digest says what *this* device last read from its own calendar
 * store, so it must never sync. Losing it costs one full diff, nothing more — which is why plain
 * defaults are enough and nothing here is secret.
 */
class UserDefaultsMirrorDigestStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : MirrorDigestStore {

    override fun get(mirrorId: String): String? = defaults.stringForKey(key(mirrorId))

    override fun put(mirrorId: String, digest: String) {
        defaults.setObject(digest, forKey = key(mirrorId))
    }

    private fun key(mirrorId: String) = "calendar.mirror.digest.$mirrorId"
}
