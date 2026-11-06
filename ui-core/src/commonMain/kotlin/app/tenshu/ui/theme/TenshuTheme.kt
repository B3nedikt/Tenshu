package app.tenshu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.tenshu.ui.layout.ProvideWindowWidthSizeClass

/**
 * Root theme for every Tenshu app: each `*-shared-ui` module and
 * `control-shared` wraps its content in it, on Android and desktop.
 *
 * Wraps [MaterialTheme] with the Amethyst Neutral palette, the full M3
 * typography scale, the Material 3 shape scale, and layers `AppSpacing`
 * as a composition local so `MaterialTheme.spacing.*` works from any
 * composable nested inside.
 *
 * This is the ONLY composable an app should wrap its content in at the
 * top level. The legacy per-app `AppTheme` composables were retired in
 * Phase 3 of the design-system migration.
 *
 * ⚠ `:ui-core` compiles for iOS, but nothing wraps content in this theme
 * there: every iOS app in this repo is SwiftUI and no Compose
 * Multiplatform UI ships on iOS (`ui-map-agent.md`). The iOS targets
 * exist so `:ui-map`'s MapLibre renderer can read the same tokens.
 *
 * See `docs/adr/0001-design-system-direction.md` for the decisions
 * behind this module, and `design-system-improvement-plan.md` for the
 * migration roadmap.
 */
@Composable
fun TenshuTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) AppColorTokens.DarkScheme else AppColorTokens.LightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        CompositionLocalProvider(
            LocalAppSpacing provides AppSpacing(),
        ) {
            ProvideWindowWidthSizeClass {
                content()
            }
        }
    }
}
