package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSBundle

/**
 * iOS's install route, read from **the same Info.plist key the Swift side reads**
 * (`ios-onboarding/Sources/ControlLink.swift`, ADR 0022 §3): `TENSHU_CONTROL_APP_STORE_ID` →
 * `TenshuControlAppStoreId` → here.
 *
 * ⚠⚠ **One source of truth on this platform, deliberately.** Generating a Kotlin constant for iOS
 * alongside the Swift Info.plist read would let a build configure one and not the other, and the
 * disagreement would only ever show up in front of a user. `:ui-core`'s Gradle task therefore bakes
 * links for Android and desktop only.
 *
 * ⚠ There is no channel check here, unlike Android and desktop: on iOS there is only one channel.
 * A TestFlight build is the *pre-launch* state, which is exactly what an unconfigured id already
 * says.
 *
 * ⚠ The same guard as Swift's: an unset Xcode build setting substitutes to the **empty string**
 * rather than a missing key, and an unsubstituted `$(…)` literal or the old all-zeroes placeholder
 * must not pass a naive non-empty check. Digits only, greater than zero.
 */
@Composable
actual fun rememberControlInstallRoute(): ControlInstallRoute = remember {
    val page = appStorePageFor(NSBundle.mainBundle.objectForInfoDictionaryKey(APP_STORE_ID_KEY) as? String)
    if (page != null) ControlInstallRoute.Store(page) else ControlInstallRoute.NotPublishedYet
}

/** ⚠ Kept character-for-character in step with `ControlLink.swift`'s own lookup. */
private const val APP_STORE_ID_KEY = "TenshuControlAppStoreId"
