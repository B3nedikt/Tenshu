package app.tenshu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.ui.theme.TenshuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the navigation chrome at each size class into `build/reports/adaptive-nav/` so the
 * layout can be **looked at** rather than only asserted against.
 *
 * [AdaptiveNavMetricsTest] pins the numbers; this pins nothing and is not a golden-image gate —
 * its job is to make the change reviewable by eye, and to fail if rendering breaks outright. The
 * precedent is `TimelineShellRenderTest`, whose KDoc records why: a layout defect can be correct
 * at every size class the tests exercise, because there were none.
 *
 * That is exactly what happened here. The defect this replaced — a 300 dp sidebar at every window
 * width and every label — survived because nothing in the repo rendered the chrome wide and looked.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveNavRenderTest {

    /**
     * Control's real destinations — the worst realistic case in the product, because
     * "Notifications" is the longest label any app actually ships. Rendering invented labels
     * ("Recently deleted items") would exercise a width no user will ever see.
     */
    private val realLabels = listOf(
        "Home" to Icons.Outlined.Home,
        "Notifications" to Icons.Outlined.People,
        "Apps" to Icons.Outlined.Folder,
        "Data" to Icons.Outlined.Delete,
        "Settings" to Icons.Outlined.Settings,
    )

    private fun items(labels: List<Pair<String, ImageVector>> = realLabels) =
        labels.mapIndexed { index, (label, icon) ->
            AdaptiveNavItem(
                key = "key$index",
                icon = icon,
                label = label,
                selected = index == 1,
                onClick = {},
            )
        }

    private fun render(name: String, width: Int, height: Int = 720, collapseRail: Boolean = false) {
        val outDir = File("build/reports/adaptive-nav").apply { mkdirs() }
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = items()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = "$name — ${width}x$height",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            if (collapseRail) {
                onNodeWithContentDescription("Collapse navigation").performClick()
                waitForIdle()
                // Let the collapse animation settle before capturing.
                repeat(20) { mainClock.advanceTimeBy(50) }
                waitForIdle()
            }
            val file = File(outDir, "$name.png")
            // ⚠ `onRoot()` is not usable here: clicking the toggle also hovers it, the header's
            // `PlainTooltip` opens in its own `Popup`, and a popup is a second semantics root —
            // so `onRoot()` fails with "found 2 nodes" rather than anything about rendering.
            // Roots come in creation order, so the scene is index 0 and the popup follows it.
            ImageIO.write(
                onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(),
                "png",
                file,
            )
            assertTrue(file.length() > 0, "expected a rendered PNG at ${file.absolutePath}")
        }
    }

    @Test
    fun rendersEverySizeClass() {
        render("01-compact-599", 599)
        render("02-medium-800", 800)
        render("03-expanded-1024-default", 1024)
        // The toggle now exists only from Large up, so the toggled render moved with it. 1024
        // has no menu button to click — and the point of looking at 03 and 05 side by side is
        // that 03 is not missing anything: the collapsed rail already draws every label.
        render("04-large-1280-toggled-closed", 1280, collapseRail = true)
        render("05-large-1280", 1280)
        render("06-extralarge-1700", 1700)
    }
}
