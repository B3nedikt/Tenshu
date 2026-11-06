package app.tenshu.fit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.tenshu.fit.model.ActivityType

/**
 * The by-activity accent seam (docs/proposals/fit-app.md D9, fit-agent.md §3).
 *
 * v1 ships **pure Amethyst** — every accessor ignores the [ActivityType] and returns a
 * theme role, so the whole surface is a clean no-op at the default. When the optional
 * by-activity tint lands (behind a single setting), only this object changes: it would
 * branch on `type` to shift *marks only* (glyph tints, ring/route/HR stroke, the Start
 * button) to a calm per-sport hue — never text, outlines, or structural fills, so
 * contrast stays safe. No hex literals live outside `:ui-core` (D9).
 */
object ActivityAccents {

    /** The soft wash behind an activity glyph (list rows, hero band). */
    val container: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondaryContainer

    /** The glyph tint on [container]. */
    val onContainer: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSecondaryContainer

    /** The stroke/fill for marks that read as "this activity" — rings, routes, HR. */
    val stroke: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    // The `type` overloads are the D9 hook: identical to the flat accessors today, they
    // become the branch point when per-activity tinting is switched on.
    @Composable @ReadOnlyComposable
    fun container(@Suppress("UNUSED_PARAMETER") type: ActivityType): Color = container

    @Composable @ReadOnlyComposable
    fun onContainer(@Suppress("UNUSED_PARAMETER") type: ActivityType): Color = onContainer

    @Composable @ReadOnlyComposable
    fun stroke(@Suppress("UNUSED_PARAMETER") type: ActivityType): Color = stroke
}
