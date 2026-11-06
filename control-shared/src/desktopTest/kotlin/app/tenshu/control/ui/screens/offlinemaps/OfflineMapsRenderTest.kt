package app.tenshu.control.ui.screens.offlinemaps

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isRoot
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Renders each Offline-maps state to a PNG under `build/reports/offline-maps/`, so this slice can be
 * **looked at** rather than only asserted against — the `ServicesCardRenderTest` precedent, and for the
 * same reason: the CE-6 pass found two defects (a `2048 GB` tier label, a 4.dp gauge that read as an
 * underline) by rendering a screen whose string assertions were all passing.
 *
 * That lesson applies directly here. The whole point of this change is that a multi-minute download
 * *looks* like it is progressing, and no state assertion can tell you whether it does.
 *
 * The images are build artifacts, not committed fixtures — there is no golden-image gate. The
 * assertions are deliberately weak (non-trivial, non-blank); their job is to fail if rendering breaks
 * outright, not to police layout.
 */
class OfflineMapsRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(name: String, state: OfflineMapsUiState) {
        composeTestRule.setContent {
            OfflineMapsScreenContent(
                state = state,
                onDownload = {},
                onDelete = {},
                onRetry = {},
                onBack = {},
            )
        }
        // ⚠ `onRoot()` is ambiguous once a dialog is on screen — a Compose dialog is its own root
        // window, so there are two. Take the LAST, which is the topmost surface: the dialog when one is
        // showing, and the only root otherwise.
        val roots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
        val image = composeTestRule.onAllNodes(isRoot())[roots.lastIndex]
            .captureToImage()
            .toAwtImage()
        val out = File("build/reports/offline-maps").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)

        assertTrue(image.width > 100 && image.height > 100, "$name rendered at ${image.width}x${image.height}")
        val colours = buildSet {
            for (x in 0 until image.width step 16) {
                for (y in 0 until image.height step 16) add(image.getRGB(x, y))
            }
        }
        assertTrue(colours.size > 3, "$name looks blank — only ${colours.size} distinct colours")
    }

    private val catalog = listOf(
        OfflineMapRegion("global", "World", 2_100_000_000L, installed = false, coverage = "-180,-90,180,90"),
        OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true, coverage = "9.53,46.37,17.16,49.02"),
        OfflineMapRegion(
            "ch", "Schweiz", 120_000_000L, installed = true, updateAvailable = true,
            coverage = "5.96,45.82,10.49,47.81",
        ),
        OfflineMapRegion("de-bayern", "Bayern", 420_000_000L, installed = false, coverage = "8.98,47.27,13.84,50.56"),
        OfflineMapRegion(
            "test-toscana", "Toscana (test)", 140_165_388L, installed = false,
            coverage = "10.6,43.3,12.0,44.3",
        ),
        OfflineMapRegion("jp-kanto", "Kantō", 780_000_000L, installed = false, coverage = "138.9,35.0,140.9,36.4"),
        OfflineMapRegion("us-ny", "New York", 310_000_000L, installed = false, coverage = "-75.0,40.4,-73.0,41.2"),
    )

    @Test
    fun idle() = render("1-idle", OfflineMapsUiState(loading = false, regions = catalog))

    /** The row this slice exists for: a determinate ring and a percentage, not a bare spinner. */
    @Test
    fun downloadInFlight() = render(
        "2-downloading",
        OfflineMapsUiState(
            loading = false,
            regions = catalog,
            busyRegionId = "de-bayern",
            downloadedBytes = 180_000_000L,
        ),
    )

    /** The first frame, before the proxy has written a byte — must still read as a download. */
    @Test
    fun downloadJustStarted() = render(
        "3-download-starting",
        OfflineMapsUiState(
            loading = false,
            regions = catalog,
            busyRegionId = "de-bayern",
            downloadedBytes = 0L,
        ),
    )

    /** Offline with downloads: the list is partial, and says so, rather than reading as the catalog. */
    @Test
    fun catalogUnavailableWithResidentRegions() = render(
        "4-catalog-unavailable-resident",
        OfflineMapsUiState(
            loading = false,
            regions = listOf(OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true)),
            catalogUnavailable = true,
        ),
    )

    /** Offline with nothing downloaded — the case that used to lie with "No map regions are available yet." */
    @Test
    fun catalogUnavailableWithNothingResident() = render(
        "5-catalog-unavailable-empty",
        OfflineMapsUiState(loading = false, regions = emptyList(), catalogUnavailable = true),
    )

    /**
     * The case the diacritic fold exists for: "oster" typed on a keyboard with no umlaut still finds
     * Österreich, and the count says what the filter is hiding.
     */
    @Test
    fun searchFiltered() = render(
        "6-search-filtered",
        OfflineMapsUiState(loading = false, regions = catalog, query = "oster"),
    )

    /** The metered prompt: names the region and the size, because the size is the reason to hesitate. */
    @Test
    fun meteredConsentPrompt() = render(
        "8-metered-consent",
        OfflineMapsUiState(
            loading = false,
            regions = catalog,
            meteredConsentRegion = catalog.first { it.regionId == "de-bayern" },
        ),
    )

    /** ⚠ Its own state: a filter that matched nothing is not "no regions available". */
    @Test
    fun searchMatchingNothing() = render(
        "7-search-no-match",
        OfflineMapsUiState(loading = false, regions = catalog, query = "atlantis"),
    )
}
