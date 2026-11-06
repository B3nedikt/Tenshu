package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSBundle

/**
 * iOS's update pages, from two Info.plist keys: `TenshuAppStoreId` (this app's own App Store id,
 * build setting `TENSHU_APP_STORE_ID`) and `TenshuControlAppStoreId` (Control's, the ADR 0022 §3 key
 * `ControlLink.swift` reads). Both are empty today, so this is [AppUpdatePages.NONE] until a listing
 * exists. The decision is [iosAppUpdatePages], host-tested.
 *
 * ⚠ No iOS screen renders a fence banner through this yet: the iOS apps are SwiftUI.
 */
@Composable
actual fun rememberAppUpdatePages(): AppUpdatePages = remember {
    iosAppUpdatePages(
        thisAppStoreId = NSBundle.mainBundle.objectForInfoDictionaryKey(THIS_APP_STORE_ID_KEY) as? String,
        controlAppStoreId = NSBundle.mainBundle.objectForInfoDictionaryKey(CONTROL_APP_STORE_ID_KEY) as? String,
    )
}

private const val THIS_APP_STORE_ID_KEY = "TenshuAppStoreId"

/** ⚠ The same key `ControlInstallRoute.ios.kt` and `ControlLink.swift` read. */
private const val CONTROL_APP_STORE_ID_KEY = "TenshuControlAppStoreId"
