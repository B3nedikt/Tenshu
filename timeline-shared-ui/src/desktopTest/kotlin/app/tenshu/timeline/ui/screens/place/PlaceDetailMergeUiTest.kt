package app.tenshu.timeline.ui.screens.place

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tenshu.timeline.composeVmTest

/**
 * The **manual merge + unmerge surface** on place detail (P4f tail,
 * `timeline-recognition-and-place-naming.md` §10.15) driven as a real Compose tree — the click-through,
 * as a test rather than a screenshot (HR #7). Mirrors `drive-shared-ui`'s `FilesScreenIntegrationTest`
 * harness.
 *
 * ⚠ Fixtures use a place with **no coordinates**, which takes the flat-hero branch instead of
 * `PlaceMap`: the map surface wants a real tile pipeline and is not what these assertions are about.
 */
@OptIn(ExperimentalTestApi::class)
class PlaceDetailMergeUiTest {

    private fun place(id: String, name: String) = Place(
        id = id,
        name = name,
        city = "",
        neighborhood = "",
        visitsCount = 2,
        photosCount = 0,
        firstVisit = "jul 2026",
        lastVisit = "jul 30",
        cover = Photo(dominantColor = "#8B7BB8"),
        tags = emptyList(),
        history = emptyList(),
        latE7 = 0,
        lngE7 = 0,
    )

    /** A repository with two places, one of which is a merge result. */
    private class Repo(
        private val self: Place,
        private val others: List<Place>,
        private val merged: Int,
    ) : TimelineRepository {
        var mergedWith: Pair<String, String>? = null
        var unmergedId: String? = null

        override suspend fun place(id: String): Place? = self
        override fun places(): Flow<List<Place>> = flowOf(listOf(self) + others)
        override suspend fun mergedPlaceCount(placeId: String): Int = merged
        override suspend fun mergePlaces(a: String, b: String): String {
            mergedWith = a to b
            return minOf(a, b)
        }
        override suspend fun unmergePlace(placeId: String) { unmergedId = placeId }

        // Unused by these screens; the interface's other members have no defaults.
        override fun homeDays(): Flow<List<Day>> = flowOf(emptyList())
        override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
        override fun boundTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun activities(): Flow<List<ActivityRollup>> = flowOf(emptyList())
        override suspend fun trip(id: String): Trip? = null
        override suspend fun moment(id: String, fresh: Boolean): Moment? = null
        override suspend fun dismissTripSuggestion() {}
        override suspend fun acceptTripSuggestion() {}
    }

    @Test
    fun theMergeDialogListsTheOtherPlacesAndReportsThePick() = composeVmTest { vms ->
        val repo = Repo(
            self = place("cap:b-second", "Office"),
            others = listOf(place("cap:a-first", "Street")),
            merged = 0,
        )
        setContent {
            PlaceDetailScreen(
                viewModel = vms.own(PlaceDetailViewModel(repo, "cap:b-second")),
                onBack = {},
            )
        }
        waitForIdle()

        // ⚠ The name renders twice by design — the scaffold title and the hero — so match the set,
        // not a unique node.
        assertTrue(onAllNodesWithText("Office").fetchSemanticsNodes().size >= 2)

        // The merge affordance sits beside rename, and opens the picker.
        onNodeWithText("Merge with another place").performClick()
        waitForIdle()

        onNodeWithText("Same place?").assertIsDisplayed()
        onNodeWithText("Street").assertIsDisplayed()

        onNodeWithText("Street").performClick()
        waitForIdle()

        assertEquals("cap:b-second" to "cap:a-first", repo.mergedWith)
    }

    @Test
    fun thereIsNothingToMergeWithWhenThisIsTheOnlyPlace() = composeVmTest { vms ->
        // ⚠ The affordance hides rather than opening an empty dialog — a picker with no options is a
        // dead end the user has to back out of.
        val repo = Repo(self = place("cap:only", "Home"), others = emptyList(), merged = 0)
        setContent { PlaceDetailScreen(vms.own(PlaceDetailViewModel(repo, "cap:only")), onBack = {}) }
        waitForIdle()

        assertTrue(onAllNodesWithText("Merge with another place").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theUndoOnlyAppearsWhenSomethingIsActuallyFoldedIn() = composeVmTest { vms ->
        val repo = Repo(self = place("cap:root", "Office"), others = emptyList(), merged = 1)
        setContent { PlaceDetailScreen(vms.own(PlaceDetailViewModel(repo, "cap:root")), onBack = {}) }
        waitForIdle()

        onNodeWithText("Separate merged places").assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals("cap:root", repo.unmergedId)
    }

    @Test
    fun aPlaceThatIsNotAMergeResultOffersNoUndo() = composeVmTest { vms ->
        // The folded rows are invisible by design, so "am I a merge result" is answered by
        // `mergedPlaceCount` — 0 here, and the button must not be there to press.
        val repo = Repo(self = place("cap:plain", "Ice cream shop"), others = emptyList(), merged = 0)
        setContent { PlaceDetailScreen(vms.own(PlaceDetailViewModel(repo, "cap:plain")), onBack = {}) }
        waitForIdle()

        assertTrue(onAllNodesWithText("Separate merged places").fetchSemanticsNodes().isEmpty())
    }
}
