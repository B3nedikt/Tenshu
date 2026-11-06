package app.tenshu.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the five Material 3 breakpoints and — load-bearing — the **ordering** of the constants.
 *
 * The ordering is not cosmetic: `WindowWidthSizeClass` is compared with `>=` (e.g.
 * `Modifier.appContentWidth`), which works only because enums are `Comparable` by ordinal and the
 * constants are declared narrowest-first. Reordering them would silently invert every such check,
 * so [ordering_isAscendingByWidth] fails loudly instead.
 */
class WindowWidthSizeClassTest {

    @Test
    fun boundaries_matchMaterial3() {
        val cases = listOf(
            0.dp to WindowWidthSizeClass.Compact,
            599.dp to WindowWidthSizeClass.Compact,
            600.dp to WindowWidthSizeClass.Medium,
            839.dp to WindowWidthSizeClass.Medium,
            840.dp to WindowWidthSizeClass.Expanded,
            // The default desktop window — the size at which the drawer/rail choice is decided
            // for Drive, Control, Fit, Timeline and Mia (WindowStatePersistence defaults 1024x768).
            1024.dp to WindowWidthSizeClass.Expanded,
            1199.dp to WindowWidthSizeClass.Expanded,
            1200.dp to WindowWidthSizeClass.Large,
            1599.dp to WindowWidthSizeClass.Large,
            1600.dp to WindowWidthSizeClass.ExtraLarge,
            3840.dp to WindowWidthSizeClass.ExtraLarge,
        )
        cases.forEach { (width, expected) ->
            assertEquals(expected, WindowWidthSizeClass.fromWidth(width), "at $width")
        }
    }

    @Test
    fun ordering_isAscendingByWidth() {
        assertEquals(
            listOf(
                WindowWidthSizeClass.Compact,
                WindowWidthSizeClass.Medium,
                WindowWidthSizeClass.Expanded,
                WindowWidthSizeClass.Large,
                WindowWidthSizeClass.ExtraLarge,
            ),
            WindowWidthSizeClass.entries.toList(),
            "constants must stay declared narrowest-first — `>=` comparisons depend on it",
        )
    }

    @Test
    fun largeAndExtraLarge_countAsExpandedOrWider() {
        // The idiom that replaced `== Expanded`. Guards the class of bug where a new, wider
        // class is added above an equality check and quietly stops satisfying it.
        listOf(WindowWidthSizeClass.Expanded, WindowWidthSizeClass.Large, WindowWidthSizeClass.ExtraLarge)
            .forEach { assertTrue(it >= WindowWidthSizeClass.Expanded, "$it must count as expanded-or-wider") }

        listOf(WindowWidthSizeClass.Compact, WindowWidthSizeClass.Medium)
            .forEach { assertTrue(it < WindowWidthSizeClass.Expanded, "$it must not count as expanded-or-wider") }
    }
}
