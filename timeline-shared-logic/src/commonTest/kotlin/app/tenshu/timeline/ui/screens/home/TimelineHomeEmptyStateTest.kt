package app.tenshu.timeline.ui.screens.home

import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * "Do you have anything at all?" — the question B3's empty state asks (TB-D6).
 *
 * ⚠⚠ **The load-bearing case is the first one.** Every list in the state starts empty, so a screen
 * that decides from `days.isEmpty()` alone tells a user with ten years of history that their
 * timeline is empty, for as long as the first read takes. An empty state is a claim about the
 * user's data; making it before the data has spoken is how a slow read becomes "Tenshu lost my
 * timeline".
 */
class TimelineHomeEmptyStateTest {

    @BeforeTest
    fun setUp() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    private class Repo(
        private val days: Flow<List<Day>>,
        private val trips: Flow<List<Trip>> = flowOf(emptyList()),
    ) : TimelineRepository {
        override fun homeDays(): Flow<List<Day>> = days
        override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
        override fun boundTrips(): Flow<List<Trip>> = trips
        override fun places(): Flow<List<Place>> = flowOf(emptyList())
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

    @Test
    fun aStoreThatHasNotAnsweredYetIsNotEmpty() = vmTest { vms ->
        // ⚠⚠ Never emits — the read is still in flight. Claiming "you have no history" here is the
        // bug this test exists for.
        val state = vms.own(TimelineHomeViewModel(Repo(days = emptyFlow()))).uiState.value

        assertFalse(state.daysLoaded)
        assertFalse(state.hasNothing)
    }

    @Test
    fun aStoreThatAnsweredWithNothingIsEmpty() = vmTest { vms ->
        val state = vms.own(TimelineHomeViewModel(Repo(days = flowOf(emptyList())))).uiState.value

        assertTrue(state.daysLoaded)
        assertTrue(state.hasNothing, "an answered, empty store is the import call to action")
    }

    @Test
    fun anyHistoryAtAllIsNotEmpty() = vmTest { vms ->
        val state = vms.own(TimelineHomeViewModel(Repo(days = flowOf(listOf(day()))))).uiState.value

        assertFalse(state.hasNothing)
    }

    @Test
    fun boundChaptersAloneCountAsHistory() = vmTest { vms ->
        // ⚠ A user whose days have aged out of the home window but who has bound chapters has a
        // timeline. Offering them an import as the whole screen would be answering the wrong
        // question.
        val trip = Trip(
            id = "t1",
            title = "Firenze",
            dateRange = "Mar 2 – Mar 9",
            daysSpan = 8,
            placesCount = 8,
            photosCount = 40,
            cover = app.tenshu.timeline.model.Photo(dominantColor = "#7A5C3E"),
            year = "2026",
        )
        val state = vms.own(TimelineHomeViewModel(
            Repo(days = flowOf(emptyList()), trips = flowOf(listOf(trip))),
        )).uiState.value

        assertFalse(state.hasNothing)
    }

    @Test
    fun becomingEmptyAfterAnAnswerIsStillEmpty() = vmTest { vms ->
        // A store that answers, then answers again with nothing (a data reset) — the state must
        // follow the data rather than latch on the first answer.
        val days = MutableStateFlow(listOf(day()))
        val viewModel = vms.own(TimelineHomeViewModel(Repo(days = days)))
        assertFalse(viewModel.uiState.value.hasNothing)

        days.value = emptyList()

        assertTrue(viewModel.uiState.value.hasNothing)
    }
}
