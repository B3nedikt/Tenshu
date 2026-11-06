package app.tenshu.ui.storage

/**
 * Where a downloaded Android copy sends a user to get Control: the website's page for the APK, signed
 * with the same side-channel key (`docs/how-to/android-release.md` "The side-channel key").
 *
 * ⚠ A fixed address on our own domain, not a store id, so it is not configured per build the way
 * [ControlStoreConfig] is (ADR 0025 §Decision 6's empty default exists because a store id is unknown
 * until the listing exists). ⚠⚠ The page must be live before the first downloadable APK ships.
 */
internal const val CONTROL_ANDROID_DOWNLOAD_URL = "https://tenshu.app/apps/control/download/android"

/** The package every first-party Android app reaches Control by (its `<queries>` entry names it). */
internal const val CONTROL_ANDROID_PACKAGE = "app.tenshu.control.android"

/**
 * Android's install-route decision, kept free of Android types so it runs in `commonTest`.
 * The Android actual of [rememberControlInstallRoute] supplies the package manager's answers.
 *
 * - **Installed by Play** → Play's page, or *not in the store yet* while no link is configured.
 * - **Installed by another store client** (F-Droid, Aurora, Obtainium, …) → *the same way you
 *   installed this app*. That client is how this user gets and updates apps, and an F-Droid build
 *   from F-Droid's main repository is signed by F-Droid, so the website's Control would not even
 *   admit it.
 * - **A debuggable build** → as before this route existed: a developer's `adb install` has no
 *   installer either, and the website's Control is signed by a different key than their build.
 * - **Anything else** (`null`, the system package installer, a browser or a file manager): a
 *   downloaded APK → the website's download page, as a [ControlInstallRoute.Store], since its words
 *   (*"Control isn't installed"* + **Get Control**) name no store.
 *
 * - **The lookup failed** ([InstallerRecord.Unknown]) → *the same way*, as before this route
 *   existed. Never the website: the copy may well be from Play.
 *
 * ⚠ A local release build a developer sideloads (non-debuggable, debug- or upload-signed, no
 * installer) lands on the website branch too, and the website's Control will not admit it.
 *
 * ⚠ A Play-signed copy that was sideloaded (copied off another phone) lands on the last branch too.
 * The website's Control admits it, and it admits that Control, only once the side-channel digest is
 * pinned in `FirstPartySigning` in the release that copy came from.
 */
internal fun androidControlInstallRoute(
    installer: InstallerRecord,
    debuggable: Boolean,
    playUrl: String,
    downloadUrl: String,
): ControlInstallRoute = when (androidChannel(installer, debuggable)) {
    AndroidChannel.UNKNOWN, AndroidChannel.STORE_CLIENT -> ControlInstallRoute.SameChannelAsThisApp
    AndroidChannel.PLAY ->
        if (playUrl.isBlank()) ControlInstallRoute.NotPublishedYet else ControlInstallRoute.Store(playUrl)
    AndroidChannel.DEBUG ->
        if (playUrl.isBlank()) ControlInstallRoute.NotPublishedYet else ControlInstallRoute.SameChannelAsThisApp
    AndroidChannel.DOWNLOADED ->
        if (downloadUrl.isBlank()) ControlInstallRoute.SameChannelAsThisApp else ControlInstallRoute.Store(downloadUrl)
}

/**
 * How an installed Android copy reached the device — the one channel decision, which both the
 * install route above and the update page ([androidUpdatePage]) read. ⚠ Keep it one: a second
 * copy of these branches is how "Get Control" and "Update" would come to disagree about the same
 * copy.
 */
internal enum class AndroidChannel {
    /** Installed by Play. */
    PLAY,

    /** Installed by another store client ([STORE_CLIENT_INSTALLERS]); that client updates it. */
    STORE_CLIENT,

    /** A debuggable build: a developer's, whatever installed it. */
    DEBUG,

    /** No store installer and not debuggable: an APK downloaded from tenshu.app. */
    DOWNLOADED,

    /** The installer lookup threw ([InstallerRecord.Unknown]). Never read as [DOWNLOADED]. */
    UNKNOWN,
}

internal fun androidChannel(installer: InstallerRecord, debuggable: Boolean): AndroidChannel = when {
    installer !is InstallerRecord.Known -> AndroidChannel.UNKNOWN
    installer.packageName in PLAY_INSTALLERS -> AndroidChannel.PLAY
    installer.packageName in STORE_CLIENT_INSTALLERS -> AndroidChannel.STORE_CLIENT
    debuggable -> AndroidChannel.DEBUG
    else -> AndroidChannel.DOWNLOADED
}

/** What the package manager said about who installed this copy. */
internal sealed interface InstallerRecord {
    /** It answered; `null` means no installer was recorded (a browser download, `adb install`). */
    data class Known(val packageName: String?) : InstallerRecord

    /** It threw. Not the same as `Known(null)`: silence is not absence (ADR 0025). */
    data object Unknown : InstallerRecord
}

/**
 * ⚠ Two, not one: `com.android.vending` is the Play Store app and `com.google.android.feedback` is
 * the historical attribution for Play-delivered installs. Both mean "came from Play".
 */
internal val PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

/**
 * App stores other than Play that record themselves as the installer. Not exhaustive: one missing
 * here falls to the website branch, which still works for any copy signed by our side-channel key.
 */
internal val STORE_CLIENT_INSTALLERS = setOf(
    "org.fdroid.fdroid", // F-Droid
    "org.fdroid.basic", // F-Droid Basic
    "com.looker.droidify", // Droid-ify
    "com.machiav3lli.fdroid", // Neo Store
    "com.aurora.store", // Aurora Store: Play-signed copies, so the website's Control is wrong for them
    "dev.imranr.obtainium", // Obtainium
    "dev.imranr.obtainium.fdroid",
    "com.amazon.venezia", // Amazon Appstore
    "com.sec.android.app.samsungapps", // Galaxy Store
    "com.huawei.appmarket", // AppGallery
)
