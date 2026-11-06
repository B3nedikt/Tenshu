package app.tenshu.ui.storage

import app.tenshu.reachability.UpdateTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The skew fence's update route on Android (`core-schema-skew-surface.md` §8.5,
 * `android-direct-apk-distribution.md` D4 / Phase 1 item 11): a downloaded copy gets its tenshu.app
 * page, a Play copy its configured listing, an iOS copy its configured App Store page; nothing else.
 */
class AppUpdatePagesTest {

    private fun page(
        installer: String?,
        debuggable: Boolean = false,
        pkg: String = MAPS,
        playUrls: Map<String, String> = PLAY_URLS,
    ) = androidUpdatePage(InstallerRecord.Known(installer), debuggable, pkg, playUrls)

    // MARK: - one branch per channel

    /** ⚠⚠ The case the offer exists for: no store installer, not debuggable → the app's own page. */
    @Test
    fun aDownloadedCopyGetsItsOwnDownloadPage() {
        for (installer in listOf(null, "com.google.android.packageinstaller", "com.android.chrome")) {
            assertEquals(MAPS_PAGE, page(installer), "installer=$installer")
        }
    }

    /** A Play copy updates from its own Play listing, never the website (that would be two channels). */
    @Test
    fun aPlayCopyGetsItsConfiguredPlayListing() {
        for (installer in PLAY_INSTALLERS) assertEquals(MAPS_PLAY, page(installer), installer)
    }

    /** ⚠⚠ Unconfigured is no button, never a dead link: an unpublished listing is a 404. */
    @Test
    fun aPlayCopyWithNoConfiguredListingHasNoPage() {
        for (installer in PLAY_INSTALLERS) {
            assertNull(page(installer, playUrls = emptyMap()), installer)
            assertNull(page(installer, playUrls = mapOf("maps" to "  ")), installer)
            assertNull(page(installer, playUrls = mapOf("drive" to "https://example.invalid/drive")), installer)
        }
    }

    /** ⚠ The listing is the copy's own: Control's record reads Control's URL, not the banner app's. */
    @Test
    fun aPlayCopyGetsItsOwnAppsListing() {
        assertEquals(CONTROL_PLAY, page("com.android.vending", pkg = CONTROL_ANDROID_PACKAGE))
    }

    /** ⚠ F-Droid, Aurora, Obtainium… update the app themselves, and their build may not be ours. */
    @Test
    fun aStoreClientsCopyHasNoPage() {
        for (installer in STORE_CLIENT_INSTALLERS) assertNull(page(installer), installer)
    }

    /** ⚠ A developer's build, `adb install` included: the website's APK is signed by another key. */
    @Test
    fun aDebuggableCopyHasNoPage() {
        assertNull(page(null, debuggable = true))
        assertNull(page("com.google.android.packageinstaller", debuggable = true))
    }

    /** ⚠⚠ A lookup that threw may be a Play copy: silence is not absence (ADR 0025). */
    @Test
    fun aFailedInstallerLookupHasNoPage() {
        for (debuggable in listOf(false, true)) {
            assertNull(androidUpdatePage(InstallerRecord.Unknown, debuggable, MAPS, PLAY_URLS))
        }
    }

    /**
     * ⚠⚠ One decision, two readers: the update page and the *Get Control* route must agree about
     * which copies are downloaded, or the same copy would be offered the website for one and not
     * the other.
     */
    @Test
    fun theUpdatePageAndTheInstallRouteAgreeOnWhichCopiesAreDownloaded() {
        val records = listOf(InstallerRecord.Unknown) +
            (listOf(null, "com.android.chrome") + PLAY_INSTALLERS + STORE_CLIENT_INSTALLERS).map { InstallerRecord.Known(it) }
        for (record in records) {
            for (debuggable in listOf(false, true)) {
                val toWebsite = androidControlInstallRoute(record, debuggable, PLAY, CONTROL_ANDROID_DOWNLOAD_URL) ==
                    ControlInstallRoute.Store(CONTROL_ANDROID_DOWNLOAD_URL)
                val toWebsitePage = androidUpdatePage(record, debuggable, CONTROL_ANDROID_PACKAGE, PLAY_URLS) ==
                    CONTROL_ANDROID_DOWNLOAD_URL
                assertEquals(toWebsite, toWebsitePage, "record=$record debuggable=$debuggable")
            }
        }
    }

    // MARK: - both copies on Android: each from its own channel

    private fun copy(installer: String?, pkg: String, debuggable: Boolean = false) =
        AndroidCopy(InstallerRecord.Known(installer), debuggable, pkg)

    @Test
    fun bothDownloadedOpenBothWebsitePages() {
        val pages = androidAppUpdatePages(copy(null, MAPS), copy(null, CONTROL_ANDROID_PACKAGE), PLAY_URLS)
        assertEquals(AppUpdatePages(thisApp = MAPS_PAGE, control = CONTROL_ANDROID_DOWNLOAD_URL), pages)
    }

    @Test
    fun bothFromPlayOpenBothListings() {
        val pages = androidAppUpdatePages(
            copy("com.android.vending", MAPS),
            copy("com.android.vending", CONTROL_ANDROID_PACKAGE),
            PLAY_URLS,
        )
        assertEquals(AppUpdatePages(thisApp = MAPS_PLAY, control = CONTROL_PLAY), pages)
    }

    /** ⚠ A downloaded app beside a Play Control opens Control's Play listing, never the website's. */
    @Test
    fun aDownloadedAppBesideAPlayControlOpensControlsListing() {
        val pages = androidAppUpdatePages(copy(null, MAPS), copy("com.android.vending", CONTROL_ANDROID_PACKAGE), PLAY_URLS)
        assertEquals(AppUpdatePages(thisApp = MAPS_PAGE, control = CONTROL_PLAY), pages)
    }

    /** ⚠⚠ A Play build never links the APK (item 10): a downloaded Control gets no button from it. */
    @Test
    fun aPlayAppBesideADownloadedControlNeverLinksTheWebsite() {
        val pages = androidAppUpdatePages(copy("com.android.vending", MAPS), copy(null, CONTROL_ANDROID_PACKAGE), PLAY_URLS)
        assertEquals(AppUpdatePages(thisApp = MAPS_PLAY, control = null), pages)
    }

    /** ⚠ Control not installed, or unreadable: no Control page. */
    @Test
    fun noReadableControlHasNoControlPage() {
        assertNull(androidAppUpdatePages(copy(null, MAPS), null, PLAY_URLS).control)
        val unknown = AndroidCopy(InstallerRecord.Unknown, false, CONTROL_ANDROID_PACKAGE)
        assertNull(androidAppUpdatePages(copy(null, MAPS), unknown, PLAY_URLS).control)
    }

    /** ⚠ A debug Control is a developer's, whatever this app is. */
    @Test
    fun aDebugControlHasNoControlPage() {
        assertNull(androidAppUpdatePages(copy(null, MAPS), copy(null, CONTROL_ANDROID_PACKAGE, debuggable = true), PLAY_URLS).control)
    }

    /** Inside Control both answers are its own. */
    @Test
    fun controlsOwnPagesAreItsOwnCopys() {
        val pages = androidAppUpdatePages(copy(null, CONTROL_ANDROID_PACKAGE), null, PLAY_URLS)
        assertEquals(AppUpdatePages(CONTROL_ANDROID_DOWNLOAD_URL, CONTROL_ANDROID_DOWNLOAD_URL), pages)
    }

    // MARK: - the address

    @Test
    fun everyFirstPartyAppHasItsOwnPage() {
        for (app in FIRST_PARTY_ANDROID_APPS) {
            assertEquals(
                "https://tenshu.app/apps/$app/download/android",
                androidDownloadPageFor("app.tenshu.$app.android"),
            )
        }
        assertEquals(10, FIRST_PARTY_ANDROID_APPS.size)
    }

    /** ⚠ The address compiled into the *Get Control* route is the same page. */
    @Test
    fun controlsUpdatePageIsTheInstallRoutesPage() {
        assertEquals(CONTROL_ANDROID_DOWNLOAD_URL, androidDownloadPageFor(CONTROL_ANDROID_PACKAGE))
    }

    /** ⚠ A page we cannot name is no button, never a guessed one. */
    @Test
    fun aPackageThatIsNotOursHasNoPage() {
        for (pkg in listOf("com.example.app", "app.tenshu.unknown.android", "app.tenshu.drive.android.debug", "app.tenshu.drive")) {
            assertNull(androidDownloadPageFor(pkg), pkg)
            assertNull(page(null, pkg = pkg), pkg)
            assertNull(page("com.android.vending", pkg = pkg), pkg)
        }
    }

    // MARK: - iOS: the App Store id, both states

    /** ⚠⚠ The state every iOS build is in today: no id configured, so no button. */
    @Test
    fun anUnconfiguredAppStoreIdHasNoPage() {
        for (raw in listOf(null, "", "  ", "\$(TENSHU_APP_STORE_ID)", "0", "000000000", "id123", "12a3", "-5")) {
            assertNull(appStorePageFor(raw), "raw=$raw")
        }
        assertEquals(AppUpdatePages.NONE, iosAppUpdatePages(null, ""))
    }

    @Test
    fun aConfiguredAppStoreIdGetsItsPage() {
        assertEquals("https://apps.apple.com/app/id1234567890", appStorePageFor("1234567890"))
        assertEquals("https://apps.apple.com/app/id1234567890", appStorePageFor(" 1234567890 "))
    }

    /**
     * ⚠⚠ On route 1 the fence is the app's own, so a THIS_APP verdict opens the app's page and a
     * CONTROL verdict Control's — each from its own id, each absent until configured.
     */
    @Test
    fun iosReadsEachBinarysOwnId() {
        val both = iosAppUpdatePages(thisAppStoreId = "111", controlAppStoreId = "222")
        assertEquals("https://apps.apple.com/app/id111", both.pageFor(UpdateTarget.THIS_APP))
        assertEquals("https://apps.apple.com/app/id222", both.pageFor(UpdateTarget.CONTROL))

        val onlyThisApp = iosAppUpdatePages(thisAppStoreId = "111", controlAppStoreId = "")
        assertEquals("https://apps.apple.com/app/id111", onlyThisApp.pageFor(UpdateTarget.THIS_APP))
        assertNull(onlyThisApp.pageFor(UpdateTarget.CONTROL))
    }

    // MARK: - which page a verdict opens

    @Test
    fun aVerdictOpensThePageOfTheBinaryItNames() {
        val pages = AppUpdatePages(thisApp = MAPS_PAGE, control = CONTROL_ANDROID_DOWNLOAD_URL)
        assertEquals(MAPS_PAGE, pages.pageFor(UpdateTarget.THIS_APP))
        assertEquals(CONTROL_ANDROID_DOWNLOAD_URL, pages.pageFor(UpdateTarget.CONTROL))
        assertNull(pages.pageFor(UpdateTarget.BOTH))
        assertNull(pages.pageFor(null))
    }

    @Test
    fun noPagesOpenNothing() {
        for (target in UpdateTarget.entries + listOf(null)) assertNull(AppUpdatePages.NONE.pageFor(target))
    }

    private companion object {
        const val MAPS = "app.tenshu.maps.android"
        const val MAPS_PAGE = "https://tenshu.app/apps/maps/download/android"
        const val PLAY = "https://play.google.com/store/apps/details?id=app.tenshu.control.android"
        const val CONTROL_PLAY = PLAY
        const val MAPS_PLAY = "https://play.google.com/store/apps/details?id=app.tenshu.maps.android"
        val PLAY_URLS = mapOf("control" to CONTROL_PLAY, "maps" to MAPS_PLAY)
    }
}
