package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import app.tenshu.reachability.UpdateTarget

/**
 * Where each binary a skew or fence can name gets updated, for the copies installed here — or `null`
 * where there is no page to send anyone to (`core-schema-skew-surface.md` §8.5,
 * `android-direct-apk-distribution.md` D4 / Phase 1 item 11).
 *
 * Per channel: a downloaded Android copy → its download page on tenshu.app; a Play copy → its Play
 * listing, when one is configured; iOS → its App Store page, when its id is configured. Store
 * clients, an unknown installer, debug builds and desktop → none.
 *
 * ⚠⚠ **The app to name is not the app the banner is in.** On Android one Control-owned proxy serves
 * every app, so most skews are Control's to fix: Drive's banner then needs *Control's* page, and only
 * a [UpdateTarget.THIS_APP] verdict needs the banner's own app's page. So both are carried, and
 * [pageFor] picks by the verdict, never by where the banner is drawn.
 *
 * ⚠⚠ **Each page is decided from the channel of the copy it updates**, not of the app showing the
 * banner. A downloaded Drive beside a Play Control (both admit each other once the side-channel key
 * is pinned) must not send the user to the website's Control: that leaves Control on two channels.
 */
data class AppUpdatePages(
    /** Where the app rendering the banner updates, for the copy installed here. */
    val thisApp: String?,
    /** Where Control updates, for the copy installed here. */
    val control: String?,
) {
    /**
     * ⚠ [UpdateTarget.BOTH] gets no page: one button cannot carry two updates, and sending the user
     * to one of them reads as though that one were enough. The words already name both.
     * ⚠ `null` (a fence no host claimed) gets none either: silence is not absence (ADR 0025).
     */
    fun pageFor(target: UpdateTarget?): String? = when (target) {
        UpdateTarget.THIS_APP -> thisApp
        UpdateTarget.CONTROL -> control
        UpdateTarget.BOTH, null -> null
    }

    companion object {
        /** No update route at all: desktop, and every copy whose channel has no page. */
        val NONE = AppUpdatePages(thisApp = null, control = null)
    }
}

/**
 * This device's update pages: [androidUpdatePage] per copy on Android, [appStorePageFor] on iOS, and
 * [AppUpdatePages.NONE] on desktop (Developer-ID bundles, no store and no updater).
 */
@Composable
expect fun rememberAppUpdatePages(): AppUpdatePages

/**
 * An Android copy's update route, from its own installer record:
 *
 * - **Downloaded** → that app's page on tenshu.app: download the latest APK again (D4).
 * - **Play** → that app's Play listing from [playUrls] (by app name), or `null` while none is
 *   configured. ⚠ Never a guessed `details?id=` link: an unpublished listing is a 404.
 * - **Store client, debug build, failed lookup** → `null`. A store client updates the app itself,
 *   a debug build is a developer's, and a failed lookup may be either (silence is not absence).
 *
 * ⚠ `null` too for a package that is not one of ours ([androidAppFor]): a page we cannot name is no
 * button, never a guessed one.
 */
internal fun androidUpdatePage(
    installer: InstallerRecord,
    debuggable: Boolean,
    packageName: String,
    playUrls: Map<String, String>,
): String? {
    val app = androidAppFor(packageName) ?: return null
    return when (androidChannel(installer, debuggable)) {
        AndroidChannel.DOWNLOADED -> androidDownloadPage(app)
        AndroidChannel.PLAY -> playUrls[app]?.takeIf { it.isNotBlank() }
        AndroidChannel.STORE_CLIENT, AndroidChannel.DEBUG, AndroidChannel.UNKNOWN -> null
    }
}

/** One installed Android copy, as the package manager described it. */
internal data class AndroidCopy(val installer: InstallerRecord, val debuggable: Boolean, val packageName: String)

/**
 * Android's pages for the app showing the banner ([own]) and Control ([control], `null` when it is
 * not installed or could not be read), each from its own copy's channel.
 *
 * ⚠⚠ **A Play build never links the APK** (`android-direct-apk-distribution.md` Phase 1 item 10,
 * Play policy). Following Control's own channel would break that for a Play-installed app beside a
 * downloaded Control: its banner would open tenshu.app. So when [own] came from Play, a Control page
 * that is the website's is dropped — that mixed case has no button, only words.
 */
internal fun androidAppUpdatePages(own: AndroidCopy, control: AndroidCopy?, playUrls: Map<String, String>): AppUpdatePages {
    val ownPage = androidUpdatePage(own.installer, own.debuggable, own.packageName, playUrls)
    if (own.packageName == CONTROL_ANDROID_PACKAGE) return AppUpdatePages(thisApp = ownPage, control = ownPage)
    val controlPage = control?.let { androidUpdatePage(it.installer, it.debuggable, it.packageName, playUrls) }
    val ownFromPlay = androidChannel(own.installer, own.debuggable) == AndroidChannel.PLAY
    val controlFromWebsite = control != null && androidChannel(control.installer, control.debuggable) == AndroidChannel.DOWNLOADED
    return AppUpdatePages(
        thisApp = ownPage,
        control = if (ownFromPlay && controlFromWebsite) null else controlPage,
    )
}

/** `app.tenshu.<app>.android` → `<app>`, for the ten first-party apps only; anything else is `null`. */
internal fun androidAppFor(packageName: String): String? =
    ANDROID_PACKAGE.matchEntire(packageName)?.groupValues?.get(1)?.takeIf { it in FIRST_PARTY_ANDROID_APPS }

/**
 * The language-neutral address `store-presence-and-support-pages.md` §4.1 reserves; the website
 * redirects it to a language.
 */
internal fun androidDownloadPage(app: String): String = "https://tenshu.app/apps/$app/download/android"

/** Kept as the one-step lookup the tests read: a package's download page, or `null` if not ours. */
internal fun androidDownloadPageFor(packageName: String): String? = androidAppFor(packageName)?.let(::androidDownloadPage)

/**
 * An App Store product id from Info.plist → its page, or `null` while unconfigured (ADR 0022 §3,
 * the same guard as `ControlLink.swift`): an unset Xcode build setting substitutes to the empty
 * string, and an unsubstituted `$(…)` literal or an all-zeroes placeholder must not pass a naive
 * non-empty check. Digits only, greater than zero.
 */
internal fun appStorePageFor(rawId: String?): String? {
    val id = rawId?.trim().orEmpty()
    val valid = id.isNotEmpty() && id.all { it.isDigit() } && (id.toLongOrNull() ?: 0L) > 0L
    return if (valid) "https://apps.apple.com/app/id$id" else null
}

/**
 * iOS's pages. ⚠ On route 1 a fence is usually the app's own ([UpdateTarget.THIS_APP]), the reverse
 * of Android, which is why both ids are read: [AppUpdatePages.pageFor] still picks by the verdict.
 */
internal fun iosAppUpdatePages(thisAppStoreId: String?, controlAppStoreId: String?): AppUpdatePages =
    AppUpdatePages(thisApp = appStorePageFor(thisAppStoreId), control = appStorePageFor(controlAppStoreId))

private val ANDROID_PACKAGE = Regex("""app\.tenshu\.([a-z]+)\.android""")

/** The ten apps the website ships as APKs (`android-direct-apk-distribution.md`). */
internal val FIRST_PARTY_ANDROID_APPS = setOf(
    "control", "drive", "photos", "maps", "timeline", "fit", "calendar", "contacts", "home", "mia",
)
