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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.ui.theme.TenshuTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.tenshu.ui.test.NoMotion

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

    private fun render(name: String, width: Int, height: Int = 720) {
        val outDir = File("build/reports/adaptive-nav").apply { mkdirs() }
        runDesktopComposeUiTest(width = width, height = height, effectContext = NoMotion) {
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
            val file = File(outDir, "$name.png")
            ImageIO.write(
                onRoot().captureToImage().toAwtImage(),
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
        render("03-expanded-1024", 1024)
        // Large and up render the same collapsed rail as 1024 — there is no expanded state.
        render("04-large-1280", 1280)
        render("05-extralarge-1700", 1700)
    }
}
