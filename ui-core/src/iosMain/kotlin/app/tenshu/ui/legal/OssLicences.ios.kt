package app.tenshu.ui.legal

import androidx.compose.runtime.Composable

/**
 * No Compose UI ships on iOS (`ui-agent.md` §1): the iOS apps show their notices in SwiftUI
 * (`ios-about/`), from the JSON the app bundle carries. This `actual` exists only so the module
 * compiles for the targets `:ui-map` needs.
 */
@Composable
internal actual fun bundledLicencesReader(): () -> String? = { null }
