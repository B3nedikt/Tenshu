package app.tenshu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tenshu spacing tokens — an 8pt-based scale with 2pt and 4pt steps at the
 * small end for icon-adjacent spacing.
 *
 * Usage:
 * ```
 * Modifier.padding(horizontal = MaterialTheme.spacing.l)
 * Arrangement.spacedBy(MaterialTheme.spacing.s)
 * Spacer(Modifier.width(MaterialTheme.spacing.m))
 * ```
 *
 * When to use a token vs. a literal:
 *  - Spacing between UI elements (padding, gaps, margins) → always a token.
 *  - Component dimensions (icon size, avatar size, stroke width, max
 *    content width) → literal is fine; these are component-specific
 *    geometry, not layout spacing.
 */
@Immutable
data class AppSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }

/**
 * Extension that mirrors `MaterialTheme.colorScheme` / `MaterialTheme.typography`
 * so spacing feels like a first-class citizen of the theme even though M3
 * doesn't have a native spacing concept.
 */
val MaterialTheme.spacing: AppSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSpacing.current
