package app.tenshu.ui.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's install route (ADR 0025 §Decision 6, amended 2026-10-31): which copy is sent where. */
class AndroidControlInstallRouteTest {

    private fun route(installer: String?, debuggable: Boolean = false, playUrl: String = PLAY) =
        androidControlInstallRoute(InstallerRecord.Known(installer), debuggable, playUrl, DOWNLOAD)

    /** ⚠⚠ A lookup that threw may be a Play copy: never the website (silence is not absence). */
    @Test
    fun aFailedInstallerLookupIsTheSameWayNeverTheWebsite() {
        for (playUrl in listOf(PLAY, "")) {
            for (debuggable in listOf(false, true)) {
                assertEquals(
                    ControlInstallRoute.SameChannelAsThisApp,
                    androidControlInstallRoute(InstallerRecord.Unknown, debuggable, playUrl, DOWNLOAD),
                )
            }
        }
    }

    @Test
    fun aPlayInstallGetsPlaysPage() {
        assertEquals(ControlInstallRoute.Store(PLAY), route("com.android.vending"))
        assertEquals(ControlInstallRoute.Store(PLAY), route("com.google.android.feedback"))
    }

    /** ⚠ Never the website: a Play user sent there would end up with two copies from two channels. */
    @Test
    fun aPlayInstallWithNoPlayLinkIsNotPublishedYetNeverTheWebsite() {
        assertEquals(ControlInstallRoute.NotPublishedYet, route("com.android.vending", playUrl = ""))
    }

    /**
     * ⚠⚠ The case this route exists for: an APK downloaded from tenshu.app has no store installer,
     * and before 2026-10-31 it was told "not in the store yet" or "install it the same way", with no
     * link either way.
     */
    @Test
    fun aDownloadedCopyGetsTheWebsitesDownloadPage() {
        for (installer in listOf(null, "com.google.android.packageinstaller", "com.android.chrome")) {
            assertEquals(ControlInstallRoute.Store(DOWNLOAD), route(installer), "installer=$installer")
            assertEquals(ControlInstallRoute.Store(DOWNLOAD), route(installer, playUrl = ""), "installer=$installer")
        }
    }

    /** ⚠ An F-Droid or Aurora user gets and updates apps through that client, and its build may not be ours. */
    @Test
    fun anotherStoreClientsInstallIsToldToUseThatClient() {
        for (installer in listOf("org.fdroid.fdroid", "com.aurora.store", "dev.imranr.obtainium")) {
            assertEquals(ControlInstallRoute.SameChannelAsThisApp, route(installer), installer)
            assertEquals(ControlInstallRoute.SameChannelAsThisApp, route(installer, playUrl = ""), installer)
        }
    }

    /**
     * ⚠ A developer's `adb install` has no installer either, and the website's Control is signed by
     * a different key than their build. Unchanged from before the route existed — the arrangement
     * ADR 0025's device pass verified ("installer=null" → the same way, no button).
     */
    @Test
    fun aDebuggableBuildNeverGetsTheWebsite() {
        assertEquals(ControlInstallRoute.SameChannelAsThisApp, route(null, debuggable = true))
        assertEquals(ControlInstallRoute.NotPublishedYet, route(null, debuggable = true, playUrl = ""))
        assertEquals(ControlInstallRoute.Store(PLAY), route("com.android.vending", debuggable = true))
    }

    @Test
    fun noDownloadPageMeansTheSameWay() {
        assertEquals(ControlInstallRoute.SameChannelAsThisApp, androidControlInstallRoute(InstallerRecord.Known(null), false, PLAY, ""))
    }

    @Test
    fun theDownloadPageIsTheWebsitesControlAndroidPage() {
        assertEquals("https://tenshu.app/apps/control/download/android", CONTROL_ANDROID_DOWNLOAD_URL)
    }

    private companion object {
        const val PLAY = "https://play.google.com/store/apps/details?id=app.tenshu.control.android"
        const val DOWNLOAD = "https://tenshu.app/apps/control/download/android"
    }
}
