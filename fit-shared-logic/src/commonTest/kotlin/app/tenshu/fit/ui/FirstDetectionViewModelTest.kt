package app.tenshu.fit.ui

import app.tenshu.fit.data.AutoDetectPreferences
import app.tenshu.fit.data.FitPreferences
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.WorkoutRead
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DetectedMovement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class FirstDetectionViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs(
        var answered: Boolean = false,
        enabled: Boolean = false,
    ) : AutoDetectPreferences {
        val enabledState = MutableStateFlow(enabled)
        var marked = 0
        // ⚠ Seeded with the default like the real implementation, so a reader taking the head
        // of this stream would read `false` even when stored `true` — the shipped bug.
        override fun enabled(): Flow<Boolean> =
            enabledState.onStart { emit(FitPreferences.AutoDetectActivities.default) }
        override suspend fun enabledOnce(): Boolean = enabledState.value
        override suspend fun setEnabled(value: Boolean) { enabledState.value = value }
        override suspend fun promptAnswered(): Boolean = answered
        override suspend fun markPromptAnswered() { marked++; answered = true }
    }

    /** A repository whose detections we control; everything else is the mock's. */
    private class DetectingRepo(
        private val movements: List<DetectedMovement>,
        delegate: FitRepository,
    ) : FitRepository by delegate {
        override fun detectedMovements(): Flow<List<DetectedMovement>> = flowOf(movements)
    }

    private fun movement(
        traceId: String = "cap-trace:1",
        mode: String = "walk",
        distanceM: Int = 2_400,
        durationSec: Int = 1_920,
        startMs: Long = 1_700_000_000_000,
    ) = DetectedMovement(traceId, mode, 80, distanceM, startMs, startMs + durationSec * 1_000L, 4)

    private fun vm(
        vms: OwnedViewModels,
        movements: List<DetectedMovement>,
        prefs: FakePrefs,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<FirstDetectionViewModel, FitRepository> {
        val repo = DetectingRepo(movements, MockFitRepository(scope))
        return vms.own(FirstDetectionViewModel(repo, prefs)) to repo
    }

    @Test
    fun aPromotableWalkRaisesThePrompt() = vmTest { vms ->
        val prefs = FakePrefs()
        val (model, _) = vm(vms, listOf(movement()), prefs, this)

        val c = model.candidate.first()
        assertEquals(ActivityType.Walking, c?.activityType)
        assertEquals(1_920, c?.durationSec)
        assertEquals(0, prefs.marked, "merely showing the prompt answers nothing")
    }

    /**
     * ⚠ Asking about a car journey would introduce the feature with an example of it doing the
     * wrong thing — and the answer would then be "no" for the wrong reason.
     */
    @Test
    fun anUnpromotableMovementRaisesNoPrompt() = vmTest { vms ->
        val prefs = FakePrefs()
        val (model, _) = vm(vms, listOf(movement(mode = "drive"), movement(traceId = "t2", durationSec = 30)), prefs, this)

        assertNull(model.candidate.first())
    }

    /**
     * ⚠⚠ And crucially it must NOT be recorded as answered — there was nothing to ask. Marking
     * here would burn the one-time prompt on a launch the user never saw.
     */
    @Test
    fun havingNothingToAskAboutDoesNotBurnThePrompt() = vmTest { vms ->
        val prefs = FakePrefs()
        vm(vms, emptyList(), prefs, this)
        assertEquals(0, prefs.marked)
        assertTrue(!prefs.answered)
    }

    @Test
    fun anAlreadyAnsweredDeviceIsNeverAskedAgain() = vmTest { vms ->
        val prefs = FakePrefs(answered = true)
        val (model, _) = vm(vms, listOf(movement()), prefs, this)
        assertNull(model.candidate.first())
    }

    /** Already on ⇒ the user answered somewhere; asking again is noise. */
    @Test
    fun anAlreadyEnabledRealmIsNotAsked() = vmTest { vms ->
        val prefs = FakePrefs(enabled = true)
        val (model, _) = vm(vms, listOf(movement()), prefs, this)
        assertNull(model.candidate.first())
    }

    @Test
    fun logAutomaticallyEnablesAndPromotes() = vmTest { vms ->
        val prefs = FakePrefs()
        val (model, repo) = vm(vms, listOf(movement()), prefs, this)

        model.logAutomatically()

        assertTrue(prefs.enabledState.value)
        assertEquals(1, prefs.marked)
        assertNull(model.candidate.first(), "the dialog closes")
        assertEquals("detected:cap-trace:1", (repo.workout("detected:cap-trace:1") as? WorkoutRead.Found)?.workout?.workoutId)
    }

    @Test
    fun justThisOneLogsItWithoutTurningTheSettingOn() = vmTest { vms ->
        val prefs = FakePrefs()
        val (model, repo) = vm(vms, listOf(movement()), prefs, this)

        model.justThisOne()

        assertTrue(!prefs.enabledState.value, "one-off must not enable the feature")
        assertEquals(1, prefs.marked)
        assertEquals("detected:cap-trace:1", (repo.workout("detected:cap-trace:1") as? WorkoutRead.Found)?.workout?.workoutId)
    }

    /** ⚠ The promise is *asked once*, not *asked until yes* — so a refusal is still an answer. */
    @Test
    fun noThanksRecordsTheAnswerAndWritesNothing() = vmTest { vms ->
        val prefs = FakePrefs()
        val (model, repo) = vm(vms, listOf(movement()), prefs, this)

        model.notNow()

        assertTrue(!prefs.enabledState.value)
        assertEquals(1, prefs.marked)
        // ⚠ `NotFound`, not null: "not now" must leave the row genuinely absent, and asserting the
        // absence by identity is what distinguishes that from a read that merely failed.
        assertEquals(WorkoutRead.NotFound, repo.workout("detected:cap-trace:1"))
        assertNull(model.candidate.first())
    }
}
