package app.tenshu.timeline.ui.screens.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import app.tenshu.timeline.desktopComposeVmTest

/**
 * What a **Timeline-only user actually sees** on a first launch (B3, TB-D6).
 *
 * ⚠⚠ Rendered rather than asserted on state alone, because the defect this closes was visual: the
 * screen was a blank `LazyColumn` — no explanation and no route to the importer — and every state
 * assertion in the tree passed while it was.
 *
 * ⚠ Koin is started empty on purpose: with no `TimelineImportControl` bound, the empty state must
 * still render its explanation and simply omit the button. That is the platform-without-a-picker
 * case (iOS today), and a crash there would take the whole screen down.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineEmptyStateRenderTest {

    @AfterTest
    fun tearDown() = stopKoin()

    private class Repo(
        private val days: Flow<List<Day>>,
        private val places: Flow<List<Place>> = flowOf(emptyList()),
    ) : TimelineRepository {
        override fun homeDays(): Flow<List<Day>> = days
        override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
        override fun boundTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun places(): Flow<List<Place>> = places
        override fun activities(): Flow<List<ActivityRollup>> = flowOf(emptyList())
        override suspend fun acceptTripSuggestion() = Unit
        override suspend fun dismissTripSuggestion() = Unit
        override suspend fun trip(id: String): Trip? = null
        override suspend fun moment(id: String, fresh: Boolean): app.tenshu.timeline.model.Moment? = null
        override suspend fun place(id: String): Place? = null
    }

    private fun day() = Day(
        date = LocalDate(2026, 8, 10),
        dayLabel = "Today",
        dateLong = "Monday, August 10",
        place = "Firenze",
        moments = emptyList(),
    )

    private fun place() = Place(
        id = "p1",
        name = "Ditta Artigianale",
        neighborhood = "Santo Spirito",
        city = "Firenze",
        visitsCount = 3,
        photosCount = 0,
        firstVisit = "Mar 2",
        lastVisit = "Today",
        cover = Photo(dominantColor = "#7A5C3E"),
    )

    private fun render(
        repo: TimelineRepository,
        tab: HomeTab = HomeTab.Home,
        assertions: ComposeUiTest.() -> Unit,
    ) {
        startKoin { }
        desktopComposeVmTest(width = 420, height = 900) { vms ->
            setContent {
                TenshuTheme {
                    TimelineHomeScreen(
                        viewModel = vms.own(TimelineHomeViewModel(repo)),
                        tab = tab,
                        onOpenMoment = {},
                        onOpenTrip = {},
                        onOpenPlace = {},
                    )
                }
            }
            assertions()
        }
    }

    /** The Drive-side idiom: a node that must NOT be there is checked as an absence, not a node. */
    private fun ComposeUiTest.assertNeverShows(text: String) {
        waitForIdle()
        check(onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "\"$text\" must not be shown in this state"
        }
    }

    @Test
    fun anEmptyStoreShowsTheImportInvitation() = render(Repo(days = flowOf(emptyList()))) {
        onNodeWithText("Your timeline starts here").assertIsDisplayed()
        // ⚠ Capture is mentioned as the alternative it is — off by default is a legitimate way to
        // use the app, not a fault to correct.
        onNodeWithText("Or turn on capture in Settings to record where you go from now on.")
            .assertIsDisplayed()
    }

    @Test
    fun aStoreThatHasNotAnsweredShowsNothingAtAll() = render(Repo(days = emptyFlow())) {
        // ⚠⚠ The important negative: no "your timeline starts here" while the first read is still
        // in flight. A user with ten years of history must never be told they have none.
        assertNeverShows("Your timeline starts here")
    }

    @Test
    fun aStoreWithHistoryShowsTheFeed() = render(Repo(days = flowOf(listOf(day())))) {
        assertNeverShows("Your timeline starts here")
        // ⚠ The chapter header upper-cases its eyebrow, so the assertion is on the long date the
        // feed renders verbatim.
        onNodeWithText("Monday, August 10").assertIsDisplayed()
    }

    @Test
    fun anEmptyPlacesTabIsQuieterThanAnEmptyTimeline() =
        render(Repo(days = flowOf(listOf(day()))), tab = HomeTab.Places) {
            // ⚠⚠ This user HAS a timeline; they simply have no named places. Showing them the
            // import invitation would be answering a question they did not ask.
            assertNeverShows("Your timeline starts here")
            onNodeWithText("Places appear as your timeline fills in.").assertIsDisplayed()
        }

    @Test
    fun aPlacesTabWithPlacesRendersThem() =
        render(Repo(days = flowOf(listOf(day())), places = flowOf(listOf(place()))), tab = HomeTab.Places) {
            assertNeverShows("Places appear as your timeline fills in.")
            onNodeWithText("Ditta Artigianale").assertIsDisplayed()
        }
}
