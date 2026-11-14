package app.tenshu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.ui.theme.TenshuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CompactNavStyle.Floating]: the pill is drawn, it reports its clearance to the content, and it
 * leaves the rail and every non-floating caller alone. Also renders docked vs floating into
 * `build/reports/adaptive-nav/` for review by eye (not a golden gate — see [AdaptiveNavRenderTest]).
 */
@OptIn(ExperimentalTestApi::class)
class FloatingNavBarTest {

    private val items = listOf(
        AdaptiveNavItem("feed", Icons.Default.PhotoLibrary, "Photos", selected = true, onClick = {}),
        AdaptiveNavItem("collections", Icons.Outlined.Collections, "Collections", selected = false, onClick = {}),
    )

    private fun capture(name: String, style: CompactNavStyle, width: Int = 412, height: Int = 860): Dp {
        var seen = (-1).dp
        val outDir = File("build/reports/adaptive-nav").apply { mkdirs() }
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = items, compactNavStyle = style) {
                        val clearance = LocalNavBarClearance.current
                        seen = clearance
                        LazyColumn(contentPadding = PaddingValues(bottom = clearance)) {
                            items(40) { i ->
                                Box(
                                    Modifier.fillMaxWidth().height(96.dp)
                                        .background(Color.hsv((i * 37f) % 360f, 0.35f, 0.85f)),
                                ) { Text("Row $i", style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            if (style == CompactNavStyle.Floating && width < 600) onNodeWithTag(FLOATING_NAV_BAR_TAG).assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
        }
        return seen
    }

    @Test
    fun floatingPillReportsItsClearance_dockedBarReportsNone() {
        assertEquals(0.dp, capture("10-compact-docked-bar", CompactNavStyle.Bar))
        val clearance = capture("11-compact-floating-pill", CompactNavStyle.Floating)
        // Pill + bottom margin; desktop has no navigation-bar inset.
        assertEquals(FloatingNavBarHeight + FloatingNavBarBottomMargin, clearance)
    }

    @Test
    fun floatingIsIgnoredAboveCompact() {
        assertEquals(0.dp, capture("12-medium-floating-is-rail", CompactNavStyle.Floating, width = 800))
    }
}
