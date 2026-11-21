package app.tenshu.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import app.tenshu.fit.ui.navigation.Destination
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.layout.PRIMARY_ACTION_FAB_TAG
import app.tenshu.ui.theme.TenshuTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.ui.test.NoMotion

/**
 * Fit's real nav chrome at desktop width, in English and German: *Record* is the bottom-end FAB
 * (not a rail item), and its label — *Aufzeichnen* is the long one — is laid out unclipped
 * (`fit-app.md` §18.20). Also writes `build/reports/fit-record-fab/<lang>.png` to look at.
 */
@OptIn(ExperimentalTestApi::class)
class RecordFabRenderTest {

    private val topLevel = listOf(
        Destination.Home, Destination.History, Destination.Record, Destination.Browse, Destination.Profile,
    )

    private fun render(locale: Locale, expectedLabel: String) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            runDesktopComposeUiTest(width = 1280, height = 800, effectContext = NoMotion) {
                setContent {
                    TenshuTheme {
                        val items = topLevel.map { d ->
                            AdaptiveNavItem(
                                key = d, icon = navIcon(d), label = navLabel(d),
                                selected = d == Destination.Home,
                                primaryAction = d == Destination.Record, onClick = {},
                            )
                        }
                        AdaptiveNavScaffold(navItems = items) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                        }
                    }
                }
                waitForIdle()
                val out = File("build/reports/fit-record-fab").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "${locale.language}.png"))
                val label = onNode(
                    hasText(expectedLabel) and hasAnyAncestor(hasTestTag(PRIMARY_ACTION_FAB_TAG)),
                    useUnmergedTree = true,
                )
                val layouts = mutableListOf<TextLayoutResult>()
                label.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                assertEquals(1, layout.lineCount, "the FAB label must stay on one line")
                // ⚠ Not `hasVisualOverflow`: M3 lays the extended FAB's text out against the
                // window-wide constraint, so `didOverflowWidth` is true for every label. What clips
                // is a label wider than the box it was given, or a box outside the button.
                val natural = layout.multiParagraph.intrinsics.maxIntrinsicWidth
                assertTrue(natural <= layout.size.width + 0.5f, "label needs ${natural}px, got ${layout.size.width}px")
                val text = label.fetchSemanticsNode().boundsInRoot
                val fab = onNode(hasTestTag(PRIMARY_ACTION_FAB_TAG)).fetchSemanticsNode().boundsInRoot
                assertTrue(text.left >= fab.left && text.right <= fab.right, "label $text outside the FAB $fab")
                // Record is not a rail destination: its only text node is the FAB's.
                assertEquals(
                    1,
                    onAllNodes(hasText(expectedLabel), useUnmergedTree = true).fetchSemanticsNodes().size,
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun english() = render(Locale.US, "Record")

    /** A phone is unchanged: Record stays the centre bar item and no FAB is drawn. */
    @Test
    fun phone_keepsRecordInTheBar() {
        runDesktopComposeUiTest(width = 412, height = 900, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    val items = topLevel.map { d ->
                        AdaptiveNavItem(
                            key = d, icon = navIcon(d), label = navLabel(d),
                            selected = d == Destination.Home,
                            primaryAction = d == Destination.Record, onClick = {},
                        )
                    }
                    AdaptiveNavScaffold(navItems = items) { Box(Modifier.fillMaxSize()) }
                }
            }
            waitForIdle()
            assertEquals(0, onAllNodes(hasTestTag(PRIMARY_ACTION_FAB_TAG)).fetchSemanticsNodes().size)
            val record = onAllNodes(hasText("Record"), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, record.size)
            // The middle of five bar items: centred horizontally.
            val centre = record.single().boundsInRoot.center.x
            assertTrue(kotlin.math.abs(centre - 206f) < 10f, "Record centred in the bar, at x=$centre")
            val out = File("build/reports/fit-record-fab").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "phone.png"))
        }
    }

    @Test
    fun german() = render(Locale.GERMANY, "Aufzeichnen")
}
