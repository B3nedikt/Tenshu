package app.tenshu.timeline.ui.screens

import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.ui.screens.moment.MomentDetailUiState
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.place.PlaceDetailUiState
import app.tenshu.timeline.ui.screens.place.PlaceDetailViewModel
import app.tenshu.timeline.ui.screens.trip.TripDetailUiState
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Place detail against a **failing or stalled Control** (2026-08-03 on-device investigation).
 *
 * Every read here crosses IPC, and on Android the proxy really does die — OEM kills, and an
 * OOMing Control restarting every ~25 s. Two distinct failure shapes were observed, and each had
 * its own bad ending:
 *
 * - a read that **throws** escaped the bare `viewModelScope.launch` and killed the whole app
 *   (`FATAL EXCEPTION … android.os.DeadObjectException`);
 * - a read that **stalls** parked the screen on `Loading` forever — the bare "…" the user reported.
 *
 * These pin both, plus the recovery path, at the seam where "the read failed" must become
 * "the screen says so".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailResilienceTest {

    private val mock = MockTimelineRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** A repository whose single-place read fails, as a dead proxy's does. */
    private class DeadProxy(
        private val base: TimelineRepository,
        private val fail: () -> Boolean,
    ) : TimelineRepository by base {
        override suspend fun place(id: String): Place? =
            if (fail()) throw IllegalStateException("Proxy died mid-call") else base.place(id)
    }

    /** A repository that answers the place but never completes the merge-candidate join. */
    private class StalledJoin(base: TimelineRepository) : TimelineRepository by base {
        override fun places(): Flow<List<Place>> = flow { awaitCancellation() }
    }

    @Test
    fun aThrownReadBecomesAnUnavailableVerdict_notAnEscapedException() = vmTest { vms ->
        val placeId = mock.places().first().first().id

        val vm = vms.own(PlaceDetailViewModel(DeadProxy(mock) { true }, placeId))

        // The point is as much that runTest completes at all: an escaping throw fails the test.
        assertIs<PlaceDetailUiState.Unavailable>(
            vm.uiState.value,
            "a failed read must report Unavailable, not crash and not masquerade as NotFound",
        )
    }

    @Test
    fun retryRecoversOnceControlAnswersAgain() = vmTest { vms ->
        val placeId = mock.places().first().first().id
        var down = true
        val vm = vms.own(PlaceDetailViewModel(DeadProxy(mock) { down }, placeId))
        assertIs<PlaceDetailUiState.Unavailable>(vm.uiState.value)

        down = false
        vm.retry()

        val loaded = assertIs<PlaceDetailUiState.Loaded>(
            vm.uiState.value,
            "retry must re-run the read, not leave the screen on its old verdict",
        )
        assertEquals(placeId, loaded.place.id)
    }

    /**
     * ⚠ The regression that produced the bare "…": the merge-candidate join was gathered *before*
     * the first state write, so one stalled stream (`places()` combines three) held the whole
     * screen hostage even though the place itself had already been read successfully.
     */
    @Test
    fun thePlaceRendersEvenWhenTheCandidateJoinNeverCompletes() = vmTest { vms ->
        val placeId = mock.places().first().first().id

        val vm = vms.own(PlaceDetailViewModel(StalledJoin(mock), placeId))

        val loaded = assertIs<PlaceDetailUiState.Loaded>(
            vm.uiState.value,
            "a stalled merge-candidate join must not hold the place itself off the screen",
        )
        assertEquals(placeId, loaded.place.id)
        assertTrue(
            loaded.mergeCandidates.isEmpty(),
            "the optional enrichment is simply absent — it must not be invented",
        )
    }

    @Test
    fun aFailedWriteReloadsInsteadOfCrashing() = vmTest { vms ->
        val placeId = mock.places().first().first().id
        val failing = object : TimelineRepository by mock {
            override suspend fun renamePlace(id: String, label: String) =
                throw IllegalStateException("Proxy died mid-call")
        }
        val vm = vms.own(PlaceDetailViewModel(failing, placeId))

        vm.rename("Somewhere new")

        // The write never landed, so the screen shows what the store really holds — not the
        // optimistic name, and not a crash.
        val loaded = assertIs<PlaceDetailUiState.Loaded>(vm.uiState.value)
        assertTrue(loaded.place.name != "Somewhere new")
    }

    /**
     * ⚠⚠ The regression that made the first fix look like it had not landed: only the PLACE screen
     * was repaired on 2026-08-03, while a feed row opens **moment** detail and a journey opens
     * **trip** detail — both of which kept the bare `Center("…")` and the bare `launch`. Same two
     * failure shapes, so the same two assertions, per screen.
     */
    @Test
    fun momentDetail_reportsUnavailableRatherThanCrashingOrShowingNotFound() = vmTest { vms ->
        val id = mock.homeDays().first().first().moments.first().id
        val failing = object : TimelineRepository by mock {
            override suspend fun moment(id: String, fresh: Boolean) = throw IllegalStateException("Proxy died mid-call")
        }
        val vm = vms.own(MomentDetailViewModel(failing, id))
        assertIs<MomentDetailUiState.Unavailable>(
            vm.uiState.value,
            "a failed moment read must not masquerade as NotFound, and must not escape the launch",
        )
    }

    @Test
    fun momentDetail_retryRecoversOnceControlAnswersAgain() = vmTest { vms ->
        val id = mock.homeDays().first().first().moments.first().id
        var down = true
        val flaky = object : TimelineRepository by mock {
            override suspend fun moment(id: String, fresh: Boolean) =
                if (down) throw IllegalStateException("Proxy died mid-call") else mock.moment(id)
        }
        val vm = vms.own(MomentDetailViewModel(flaky, id))
        assertIs<MomentDetailUiState.Unavailable>(vm.uiState.value)
        down = false
        vm.retry()
        assertIs<MomentDetailUiState.Loaded>(vm.uiState.value, "retry must re-run the read")
    }

    @Test
    fun tripDetail_reportsUnavailableRatherThanCrashingOrShowingNotFound() = vmTest { vms ->
        val failing = object : TimelineRepository by mock {
            override suspend fun trip(id: String) = throw IllegalStateException("Proxy died mid-call")
        }
        val vm = vms.own(TripDetailViewModel(failing, null, "any-trip"))
        assertIs<TripDetailUiState.Unavailable>(
            vm.uiState.value,
            "a failed trip read must not masquerade as NotFound, and must not escape the launch",
        )
    }

    /**
     * ⚠⚠ [MomentDetailViewModel.rename] must reload with `fresh = true`.
     *
     * `moment(id)` may answer from the feed's last projection, which by definition predates the
     * call — and the reload is the *very next statement* after the write, so it sits exactly on the
     * race rather than being rarely exposed to it. Reusing that projection here would render the
     * rename as having silently failed, which is the worst failure shape available: the user sees
     * their edit vanish and the store is actually correct.
     *
     * Pinned by recording the flag the VM asks for, not by timing.
     */
    @Test
    fun renameReloadsWithAFreshRead_neverTheCachedProjection() = vmTest { vms ->
        val id = mock.homeDays().first().first().moments.first().id
        val asked = mutableListOf<Boolean>()
        val recording = object : TimelineRepository by mock {
            // ⚠ The mock never sets `placeId`, and `rename` early-returns without one — so the
            // fixture has to supply it or this test silently exercises nothing.
            override suspend fun moment(id: String, fresh: Boolean) =
                mock.moment(id, fresh)?.copy(placeId = "place-1").also { asked += fresh }
        }
        val vm = vms.own(MomentDetailViewModel(recording, id))
        assertEquals(listOf(false), asked, "entry must reuse the feed's projection")

        vm.rename("Somewhere else")

        assertEquals(
            true,
            asked.last(),
            "the reload after a write must be fresh, or the rename appears to have failed",
        )
    }
}
