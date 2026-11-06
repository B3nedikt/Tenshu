package app.tenshu.fit.ui.screens

import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.AppSharingState
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import app.tenshu.fit.ui.screens.sharing.SharingStep
import app.tenshu.fit.ui.screens.sharing.SharingWizardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The wizard's whole design is the order of its steps and what each one offers (`fit-app.md`
 * §15.6.3). Getting that order wrong is not cosmetic: it asks the user to fix something that
 * cannot yet work, or — worse — routes them to an export when a switch would have done, which is
 * the §15.5 mistake this flow exists to stop repeating.
 */
class SharingWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ── the order is dependency order, and each step hides the ones behind it ──────────

    @Test
    fun anAbsentStoreOutranksEverythingElse() = vmTest(dispatcher) { vms ->
        val vm = wizard(
            vms,
            SharingReadiness(
                storePresent = false,
                canRead = false,
                historyAccess = HistoryAccess.Grantable,
                googleFit = AppSharingState.NotPermitted,
            ),
        )
        // Every later condition is also unmet — and none of them can be acted on until there is a
        // store to act on. A checklist would have shown the user four problems and no first move.
        assertEquals(SharingStep.InstallStore, vm.uiState.value.step)
        assertFalse(
            vm.uiState.value.actionable,
            "there is no store page to send anyone to: Health Connect is a system module on 14+, " +
                "and an Install button would be a dead end on exactly the devices that need it",
        )
    }

    @Test
    fun readingComesBeforeDepth() = vmTest(dispatcher) { vms ->
        val vm = wizard(
            vms,
            SharingReadiness(storePresent = true, canRead = false, historyAccess = HistoryAccess.Grantable),
        )
        assertEquals(SharingStep.AllowReading, vm.uiState.value.step, "deep history is meaningless while we cannot read at all")
    }

    @Test
    fun depthComesBeforeAskingAnotherAppToShare() = vmTest(dispatcher) { vms ->
        val vm = wizard(
            vms,
            SharingReadiness(
                storePresent = true,
                canRead = true,
                historyAccess = HistoryAccess.Grantable,
                googleFit = AppSharingState.NotPermitted,
            ),
        )
        assertEquals(SharingStep.AllowHistory, vm.uiState.value.step)
    }

    @Test
    fun aBlockedGoogleFitIsTheStepAndItOpensSettings() = vmTest(dispatcher) { vms ->
        val requester = RecordingRequester()
        val vm = wizard(vms, readiness(googleFit = AppSharingState.NotPermitted), requester)

        assertEquals(SharingStep.AllowGoogleFit, vm.uiState.value.step)
        assertTrue(vm.uiState.value.actionable)
        vm.act()
        assertEquals(1, requester.settingsOpened, "the grant belongs to Google Fit — navigation is the only move we have")
        assertEquals(0, requester.imports, "and it is NOT a permission we can prompt for")
    }

    @Test
    fun aPhoneWithoutGoogleFitHasNothingToFix() = vmTest(dispatcher) { vms ->
        val vm = wizard(vms, readiness(googleFit = AppSharingState.NotInstalled))
        assertEquals(
            SharingStep.Done,
            vm.uiState.value.step,
            "an app the user does not have is not a problem to solve — the same reason §15.5's notice stays silent",
        )
    }

    // ── the tail: where permissions stop helping ──────────────────────────────────────

    @Test
    fun grantingGoogleFitAsksForAConfirmingImportRatherThanClaimingSuccess() = vmTest(dispatcher) { vms ->
        val repo = FakeRepository(readiness(googleFit = AppSharingState.NotPermitted))
        val vm = vms.own(SharingWizardViewModel(repo))
        testScheduler.advanceUntilIdle()
        assertEquals(SharingStep.AllowGoogleFit, vm.uiState.value.step)

        // The user goes to Health Connect, flips the switch, comes back: the resume probe sees it.
        repo.readinessState.value = readiness(googleFit = AppSharingState.Permitted)
        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(
            SharingStep.ConfirmSharing,
            vm.uiState.value.step,
            "everything we know about Google Fit's data predates the grant — claiming Done here " +
                "would be a guess, and claiming NeedsExport would send them off to export for nothing",
        )
    }

    @Test
    fun theConfirmingImportIsExplicitAndNotFiredByTheResumeProbe() = vmTest(dispatcher) { vms ->
        val repo = FakeRepository(readiness(googleFit = AppSharingState.NotPermitted))
        val vm = vms.own(SharingWizardViewModel(repo))
        testScheduler.advanceUntilIdle()

        repo.readinessState.value = readiness(googleFit = AppSharingState.Permitted)
        vm.refresh()
        testScheduler.advanceUntilIdle()
        // ⚠⚠ The loop this guards: HealthImportBootstrap already imports every launch, and an
        // import fired by every return-from-settings would compound with it while the wizard is open.
        assertEquals(0, repo.imports, "a resume must not start an import")

        vm.act()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.imports)
        assertEquals(ImportWindow.FullHistory, repo.lastWindow)
    }

    @Test
    fun aPermittedButSilentGoogleFitEndsAtTheExport() = vmTest(dispatcher) { vms ->
        val repo = FakeRepository(readiness(googleFit = AppSharingState.Permitted))
        repo.statusState.value =
            ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 30, notice = ImportNotice.GoogleFitNotSharing)
        val vm = vms.own(SharingWizardViewModel(repo))
        testScheduler.advanceUntilIdle()

        assertEquals(
            SharingStep.NeedsExport,
            vm.uiState.value.step,
            "permission is not the obstacle any more, so the wizard must stop offering permissions",
        )
        assertFalse(vm.uiState.value.actionable, "the export lives on Profile, which owns the picker")
    }

    @Test
    fun anImportClearsTheConfirmationAndTheWizardStopsAskingForOne() = vmTest(dispatcher) { vms ->
        val repo = FakeRepository(readiness(googleFit = AppSharingState.NotPermitted))
        val vm = vms.own(SharingWizardViewModel(repo))
        testScheduler.advanceUntilIdle()
        repo.readinessState.value = readiness(googleFit = AppSharingState.Permitted)
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(SharingStep.ConfirmSharing, vm.uiState.value.step)

        // An import having *run* is what makes the wizard's knowledge current again — so it goes
        // through Running, exactly as the repository drives it.
        repo.statusState.value = ImportStatus.Running()
        testScheduler.advanceUntilIdle()
        repo.statusState.value = ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 42)
        testScheduler.advanceUntilIdle()

        assertEquals(SharingStep.Done, vm.uiState.value.step)
    }

    /**
     * ⚠ The subtle one. When the grant lands, the last import is already a *completed* one from
     * before it — so a wizard that cleared the confirmation on "the status is completed" would
     * cancel it in the same breath as raising it, and jump straight to a verdict about data it had
     * never re-read.
     */
    @Test
    fun aCompletedImportFromBeforeTheGrantDoesNotCountAsConfirmation() = vmTest(dispatcher) { vms ->
        val repo = FakeRepository(readiness(googleFit = AppSharingState.NotPermitted))
        repo.statusState.value =
            ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 30, notice = ImportNotice.GoogleFitNotPermitted)
        val vm = vms.own(SharingWizardViewModel(repo))
        testScheduler.advanceUntilIdle()

        repo.readinessState.value = readiness(googleFit = AppSharingState.Permitted)
        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(SharingStep.ConfirmSharing, vm.uiState.value.step)
    }

    @Test
    fun withoutARequesterThePermissionStepsAreInformationalRatherThanDeadButtons() = vmTest(dispatcher) { vms ->
        val vm = wizard(vms, readiness(canRead = false), permissionRequester = null)
        assertEquals(SharingStep.AllowReading, vm.uiState.value.step)
        assertFalse(vm.uiState.value.actionable, "a shell that cannot prompt must not be given a button that does nothing")
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private fun readiness(
        canRead: Boolean = true,
        googleFit: AppSharingState = AppSharingState.Permitted,
    ) = SharingReadiness(
        storePresent = true,
        canRead = canRead,
        historyAccess = HistoryAccess.Granted,
        googleFit = googleFit,
    )

    private fun kotlinx.coroutines.test.TestScope.wizard(
        vms: OwnedViewModels,
        readiness: SharingReadiness,
        permissionRequester: HealthPermissionRequester? = RecordingRequester(),
    ): SharingWizardViewModel =
        vms.own(SharingWizardViewModel(FakeRepository(readiness), permissionRequester)).also { testScheduler.advanceUntilIdle() }

    private class RecordingRequester : HealthPermissionRequester {
        var settingsOpened = 0
            private set
        var imports = 0
            private set

        override fun request() { imports++ }
        override fun openHealthSettings() { settingsOpened++ }
    }

    /** [MockFitRepository] with the two flows the wizard reads made settable. */
    private class FakeRepository(
        initial: SharingReadiness,
        private val delegate: MockFitRepository = MockFitRepository(),
    ) : FitRepository by delegate {
        val readinessState = MutableStateFlow(initial)
        val statusState = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
        var imports = 0
            private set
        var lastWindow: ImportWindow? = null
            private set

        override fun sharingReadiness(): StateFlow<SharingReadiness> = readinessState
        override fun importStatus(): StateFlow<ImportStatus> = statusState
        override suspend fun refreshSharingReadiness() = Unit
        override suspend fun refreshHistoryAccess() = Unit
        override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
            imports++
            lastWindow = window
        }
    }
}
