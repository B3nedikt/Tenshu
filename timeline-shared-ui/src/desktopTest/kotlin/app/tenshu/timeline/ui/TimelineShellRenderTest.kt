package app.tenshu.timeline.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import app.tenshu.timeline.ui.screens.home.HomeTab
import app.tenshu.timeline.ui.screens.home.TimelineHomeScreen
import app.tenshu.timeline.ui.screens.home.TimelineHomeViewModel
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.theme.TenshuTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import app.tenshu.timeline.desktopComposeVmTest

/**
 * Renders the Timeline shell at a phone, a tablet and a desktop width, writing each to
 * `build/reports/timeline-shell/` so the layout can be **looked at** rather than only asserted
 * against.
 *
 * Why: the shell change exists precisely because nobody had looked at Timeline in a wide window —
 * the phone layout was correct at every size class the tests exercised (there were none), and the
 * defect was that a 16sp list row spanned 1440 px. Two Tenshu slices have now found real defects by
 * rendering a surface and reading it (`commerce-end-to-end-dry-run.md` CE-6, WC-3), and a
 * size-class layout is exactly the kind of thing string assertions cannot see.
 *
 * The images are build artifacts, not committed fixtures: there is no golden-image gate here,
 * because a pixel diff on a Compose surface is a maintenance cost this project has not taken on.
 * The assertions are deliberately weak — their job is to fail if rendering breaks outright.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineShellRenderTest {

    private val photo = Photo(dominantColor = "#7A5C3E")

    private val moments = listOf(
        Moment(
            id = "m1",
            time = "08:40",
            title = "Morning coffee",
            place = "Ditta Artigianale",
            kind = MomentKind.Visit,
            durationMin = 35,
            photos = listOf(photo),
        ),
        Moment(
            id = "m2",
            time = "09:20",
            title = "Walk to the studio",
            place = "",
            kind = MomentKind.Movement,
            durationMin = 18,
            distanceM = 1400,
            movementMode = "walk",
        ),
    )

    private val places = List(4) { i ->
        Place(
            id = "p$i",
            name = listOf("Ditta Artigianale", "The studio", "Mercato Centrale", "Home")[i],
            neighborhood = "Santo Spirito",
            city = "Firenze",
            visitsCount = 12 + i,
            photosCount = i,
            firstVisit = "Mar 2",
            lastVisit = "Today",
            cover = photo,
        )
    }

    private val activities = listOf(
        ActivityRollup(
            kind = "walk",
            label = "Walks",
            icon = "run",
            count = 128,
            suffix = "walks",
            secondary = "212 km total",
            lastDate = "Today",
            dominantColor = "#7A5C3E",
        ),
        ActivityRollup(
            kind = "coffee",
            label = "Coffee stops",
            icon = "coffee",
            count = 64,
            suffix = "visits",
            secondary = "9 places",
            lastDate = "Today",
            dominantColor = "#3E5C7A",
        ),
    )

    private inner class Repo : TimelineRepository {
        override fun homeDays(): Flow<List<Day>> = flowOf(
            listOf(
                Day(
                    date = LocalDate(2026, 8, 10),
                    dayLabel = "Today",
                    dateLong = "Monday, August 10",
                    place = "Firenze",
                    moments = moments,
                ),
            ),
        )

        override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
        override fun boundTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun places(): Flow<List<Place>> = flowOf(places)
        override fun activities(): Flow<List<ActivityRollup>> = flowOf(activities)
        override suspend fun acceptTripSuggestion() = Unit
        override suspend fun dismissTripSuggestion() = Unit
        override suspend fun trip(id: String): Trip? = null
        override suspend fun moment(id: String, fresh: Boolean): Moment? = null
        override suspend fun place(id: String): Place? = null
        override suspend fun movementRoute(traceId: String): List<GeoPoint> = emptyList()
    }

    /** The shell's nav items, mirroring `App` — the chrome is what these images are for. */
    private fun navItems(selected: HomeTab) = HomeTab.entries.map { entry ->
        AdaptiveNavItem(
            key = entry,
            icon = Icons.Outlined.Place,
            label = entry.name,
            selected = entry == selected,
            onClick = {},
        )
    } + AdaptiveNavItem(
        key = "settings",
        icon = Icons.Outlined.Settings,
        label = "Settings",
        selected = false,
        onClick = {},
    )

    /**
     * ⚠ The surface size is set on the **test surface**, not with a `Modifier.size`: the default
     * desktop test window is 1024×768 and silently clips anything larger, which would have made an
     * "expanded" fixture indistinguishable from a medium one — and the whole point of these images
     * is the widest case.
     */
    private fun render(name: String, width: Int, height: Int, tab: HomeTab) = desktopComposeVmTest(
        width = width,
        height = height,
    ) { vms ->
        setContent {
            Box(Modifier.fillMaxSize()) {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = navItems(tab)) {
                        TimelineHomeScreen(
                            viewModel = vms.own(TimelineHomeViewModel(Repo())),
                            tab = tab,
                            onOpenMoment = {},
                            onOpenTrip = {},
                            onOpenPlace = {},
                        )
                    }
                }
            }
        }
        waitForIdle()

        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/reports/timeline-shell").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)

        assertTrue(image.width > 100 && image.height > 100, "$name rendered at ${image.width}x${image.height}")
        val colours = buildSet {
            for (x in 0 until image.width step 16) {
                for (y in 0 until image.height step 16) add(image.getRGB(x, y))
            }
        }
        assertTrue(colours.size > 3, "$name looks blank — only ${colours.size} distinct colours")
    }

    @Test
    fun compactFeed() = render("1-compact-feed", 400, 800, HomeTab.Home)

    @Test
    fun mediumFeed() = render("2-medium-feed", 700, 800, HomeTab.Home)

    @Test
    fun expandedFeed() = render("3-expanded-feed", 1440, 900, HomeTab.Home)

    @Test
    fun expandedPlaces() = render("4-expanded-places", 1440, 900, HomeTab.Places)

    @Test
    fun expandedActivities() = render("5-expanded-activities", 1440, 900, HomeTab.Activities)
}
