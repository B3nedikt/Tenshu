package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import app.tenshu.ui.theme.TenshuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.ui.test.NoMotion

/**
 * Pins that **no nav label is clipped**, in the rail or in the bottom bar, with the longest labels
 * the apps actually ship.
 *
 * ### Why this exists
 *
 * The rail is a fixed 96 dp and a [androidx.compose.material3.WideNavigationRailItem] lays its
 * label out under the icon with no shrink and no wrap rule of its own. A German word wider than
 * the item — Fit's "Aufzeichnen" — was broken mid-word onto a second line the item has no room
 * for, so the desktop store captures showed "Aufzeichne" (2026-11-20). English never reached the
 * edge, so no test or screenshot here had ever seen it.
 *
 * The labels are real `values-de` strings, not invented ones: an invented label exercises a width
 * no user sees, and misses the one they do.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveNavLabelFitTest {

    /** The longest shipped German top-level labels, across every app that uses the scaffold. */
    private val germanLabels = listOf(
        "Aufzeichnen", // Fit
        "Einstellungen", // Home, Mia, Control, Timeline
        "Aktivitäten", // Timeline
        "Automationen", // Home
        "Freigegeben", // Drive
    )

    /** Multi-word labels must wrap at a space before they shrink (Timeline's named entries). */
    private val multiWordLabels = listOf("Für mich freigegeben", "Coffee stops")

    @Test
    fun railLabels_neverClip() {
        (germanLabels + multiWordLabels).chunked(5).forEachIndexed { index, labels ->
            assertNoLabelClips(windowWidth = 1280, labels = labels, capture = "rail-$index")
        }
    }

    /**
     * Each app's real German bar at [barWidth]: 360 dp, the narrow end of real phones, off Linux.
     * The bar's items are a fifth of the width at five destinations, so the set matters, not just
     * the longest word.
     */
    private val germanBars = mapOf(
        "fit" to listOf("Start", "Verlauf", "Aufzeichnen", "Entdecken", "Profil"),
        "control" to listOf("Start", "Apps", "Daten", "Dienste", "Einstellungen"),
        "home" to listOf("Start", "Automationen", "Aktivität", "Einstellungen"),
        "timeline" to listOf("Start", "Orte", "Aktivitäten", "Einstellungen"),
        "drive" to listOf("Dateien", "Freigegeben", "Einstellungen"),
    )

    @Test
    fun bottomBarLabels_neverClip() {
        germanBars.forEach { (app, labels) ->
            assertNoLabelClips(windowWidth = barWidth(labels), labels = labels, capture = "bar-$app")
        }
    }

    @Test
    fun labelsInOneChrome_shareOneSize() {
        // A shrunk label beside full-size neighbours reads as a mistake: the whole bar steps down
        // to the size its longest label needs.
        germanBars.forEach { (app, labels) ->
            runDesktopComposeUiTest(width = barWidth(labels), height = 800, effectContext = NoMotion) {
                lateinit var measurer: TextMeasurer
                lateinit var style: TextStyle
                setContent {
                    TenshuTheme {
                        measurer = rememberTextMeasurer()
                        style = MaterialTheme.typography.labelMedium
                        AdaptiveNavScaffold(navItems = navItems(labels)) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
                waitForIdle()
                val sizes = labels.map { renderedSize(it, measurer, style) }
                assertTrue(
                    sizes.max() - sizes.min() < 0.3f,
                    "$app: labels rendered at different sizes: ${labels.zip(sizes)}",
                )
                // Not vacuous: Fit's "Aufzeichnen" does not fit its share of the bar at 12 sp, so its
                // bar must have stepped down as a whole.
                if (app == "fit") {
                    assertTrue(sizes.max() < 11.9f, "fit: expected the bar to shrink, got $sizes")
                }
            }
        }
    }

    /**
     * The window a bar is tested in: [PHONE_WIDTH], or on Linux as much wider as its font needs for
     * the bar's longest word to fit at [NavLabelMinFontSize].
     *
     * ⚠ The theme uses `FontFamily.Default`, so label widths belong to the machine: "Einstellungen"
     * fits a fifth of 360 dp at the floor in macOS's and Windows' fonts and not in Linux's wider
     * one, where the ladder's last step (the ellipsis) is the correct result.
     *
     * ⚠⚠ Off Linux a bar that needs more than 360 dp fails: without that, raising the floor or
     * adding a longer label would widen the window instead of going red.
     */
    private fun barWidth(labels: List<String>): Int {
        var widest = 0
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelMedium
                        .copy(fontSize = NavLabelMinFontSize)
                    widest = labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }
                }
            }
            waitForIdle()
        }
        val needed = labels.size * widest + (labels.size - 1) * BAR_ITEM_GAP
        if (!System.getProperty("os.name").startsWith("Linux")) {
            assertTrue(needed <= PHONE_WIDTH, "$labels need $needed dp at the floor, over $PHONE_WIDTH")
        }
        return maxOf(PHONE_WIDTH, needed)
    }

    /**
     * The size a label was *drawn* at. ⚠ Not `layoutInput.style.fontSize`: under `autoSize` that
     * still reports the style's 12 sp, which let a first draft of this test pass over a bar of
     * mixed sizes. Width is linear in font size (letter-spacing is the constant term), so two
     * reference measurements pin it.
     */
    private fun ComposeUiTest.renderedSize(
        label: String,
        measurer: TextMeasurer,
        style: TextStyle,
    ): Float {
        val drawn = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .textLayout().lineWidth()
        fun widthAt(size: Float) =
            measurer.measure(label, style.copy(fontSize = size.sp), maxLines = 1).lineWidth()
        val (lo, hi) = 8f to 12f
        return lo + (drawn - widthAt(lo)) / (widthAt(hi) - widthAt(lo)) * (hi - lo)
    }

    private fun TextLayoutResult.lineWidth() =
        (0 until lineCount).maxOf { getLineRight(it) - getLineLeft(it) }

    private fun assertNoLabelClips(windowWidth: Int, labels: List<String>, capture: String) {
        runDesktopComposeUiTest(width = windowWidth, height = 800, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = navItems(labels)) { Box(Modifier.fillMaxSize()) }
                }
            }
            waitForIdle()
            saveCapture(capture)
            labels.forEachIndexed { index, label ->
                assertLabelFits(label, slot(windowWidth, index, labels.size))
            }
        }
    }

    /**
     * The horizontal span a label may draw in: the rail's 96 dp column, or its item's equal share
     * of a compact window's bar. Density is 1.0 in `runDesktopComposeUiTest`, so dp == px.
     */
    private fun slot(windowWidth: Int, index: Int, count: Int): ClosedFloatingPointRange<Float> =
        if (windowWidth < COMPACT_LIMIT) {
            val share = windowWidth.toFloat() / count
            index * share..(index + 1) * share
        } else {
            0f..RAIL_WIDTH
        }

    private fun ComposeUiTest.assertLabelFits(label: String, slot: ClosedFloatingPointRange<Float>) {
        val node = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val layout = node.textLayout()
        val where = "label \"$label\""

        assertTrue(!layout.hasVisualOverflow, "$where overflows its own layout box")
        (0 until layout.lineCount).forEach { line ->
            assertTrue(!layout.isLineEllipsized(line), "$where is ellipsized on line $line")
        }
        assertTrue(
            !breaksInsideAWord(label, layout),
            "$where breaks mid-word: ${lines(label, layout)}",
        )
        // The text's own layout can be fine while the item sits off-centre and the rail's edge
        // clips it: Fit's emphasized pill pushed "Aufzeichnen" right until it read "Aufzeichne".
        // So check where the glyphs are actually drawn, against the slot they must stay in.
        val left = node.boundsInRoot.left
        val drawn = (0 until layout.lineCount).let { lines ->
            left + lines.minOf { layout.getLineLeft(it) }..left + lines.maxOf { layout.getLineRight(it) }
        }
        assertTrue(
            drawn.start >= slot.start - EPSILON && drawn.endInclusive <= slot.endInclusive + EPSILON,
            "$where is drawn at $drawn, outside its slot $slot",
        )
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun lines(label: String, layout: TextLayoutResult) =
        (0 until layout.lineCount).map {
            label.substring(layout.getLineStart(it), layout.getLineEnd(it))
        }

    private fun ComposeUiTest.saveCapture(name: String) {
        val outDir = File("build/reports/adaptive-nav").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "labels-$name.png"))
    }

    private fun navItems(labels: List<String>) = labels.mapIndexed { index, label ->
        AdaptiveNavItem(
            key = "key$index",
            icon = Icons.Outlined.Home,
            label = label,
            selected = index == 0,
            onClick = {},
            // Fit draws "Aufzeichnen" emphasized — a 56 dp pill as the icon — which is how the
            // label clipped on a real desktop run even after it fit its own line: the pill
            // pushed the whole item column right of the rail's edge.
            emphasized = label == "Aufzeichnen",
        )
    }

    private companion object {
        const val EPSILON = 0.5f
        const val RAIL_WIDTH = 96f
        const val COMPACT_LIMIT = 600
        const val PHONE_WIDTH = 360

        /** The gap Material's `NavigationBar` leaves between two items, in dp. */
        const val BAR_ITEM_GAP = 8
    }
}
