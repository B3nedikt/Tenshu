package app.tenshu.calendar.ui.external

import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.MirrorBackgroundScheduler
import app.tenshu.calendar.data.external.MirrorDigestStore
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.macos.MacEventKit
import java.util.prefs.Preferences
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The macOS calendar store for *Copy from this Mac* and *Keep in sync* (CX-2c) — the desktop twin of
 * Android's `deviceCalendarModule`.
 *
 * ⚠⚠ Empty unless [MacEventKit.isUsable]: with no reader bound, `ExternalCopyViewModel.supported` is
 * false and nothing on screen offers the flow — the right answer for `gradle run`, which has no
 * usage string, and for any non-macOS desktop.
 */
fun desktopDeviceCalendarModule(): Module = module {
    if (!macCalendarsUsable()) return@module
    single<ExternalCalendarReader> { MacCalendarReader() }
    single<MirrorDigestStore> { PreferencesMirrorDigestStore() }
    // ⚠ The clock enters at the composition root, like every other "now" in Calendar.
    single { MirrorRefresher(get(), get(), get()) { Clock.System.now().toEpochMilliseconds() } }
    single<MirrorBackgroundScheduler> { DesktopMirrorScheduler(get()) }
}

/**
 * [MacEventKit.isUsable], and `false` on a runtime without FFM.
 *
 * ⚠ Loading `MacEventKit` on a JVM older than 22 throws a `LinkageError` (no `java.lang.foreign`):
 * the shipped app bundles 25, but this module's own tests run on 17, and any JVM that is not the
 * bundled one must hide the flow rather than crash on it.
 */
internal fun macCalendarsUsable(): Boolean =
    try {
        MacEventKit.isUsable
    } catch (_: LinkageError) {
        false
    }

/** Device-local digests in the user's Java preferences — never synced, cheap to lose (a full diff). */
private class PreferencesMirrorDigestStore : MirrorDigestStore {
    private val node = Preferences.userRoot().node("app/tenshu/calendar/mirror-digests")
    override fun get(mirrorId: String): String? = node.get(mirrorId, null)
    override fun put(mirrorId: String, digest: String) {
        node.put(mirrorId, digest)
    }
}

/**
 * Refreshes this Mac's mirrors at launch and then every [INTERVAL_MS] while the app runs.
 *
 * ⚠⚠ That is the whole schedule: no launch agent, so nothing refreshes while Calendar is closed —
 * the copy says so (`DeviceCalendarAccess.syncCadence`) and the row shows when it last updated.
 * ⚠ Idempotent: `ensureScheduled` is called at launch and again whenever a mirror starts.
 */
private class DesktopMirrorScheduler(private val refresher: MirrorRefresher) : MirrorBackgroundScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Synchronized
    override fun ensureScheduled() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // ⚠ Swallowed: Control may not be reachable yet at launch, and access may have been
                // revoked — the refresher reports both per mirror; the loop must outlive them.
                runCatchingCancellable { refresher.refreshAll() }
                delay(INTERVAL_MS)
            }
        }
    }

    private companion object {
        /** Cheap when nothing changed: a read and a digest, no writes. */
        const val INTERVAL_MS = 15L * 60 * 1000
    }
}
