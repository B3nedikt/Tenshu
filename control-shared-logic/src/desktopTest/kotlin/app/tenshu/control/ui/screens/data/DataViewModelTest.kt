package app.tenshu.control.ui.screens.data

import app.tenshu.control.data.MockRealmDataClient
import app.tenshu.control.data.RealmDataClient
import app.tenshu.control.data.RealmDataResetOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.file.File
import org.tenshu.core.health.Workout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * `DataViewModel` orders realm-data types by their consumer-side presentation `sortOrder`
 * (File=100, Workout=200; unknown types fall to last), independent of the registry's
 * enumeration order — and drives the destructive reset flow
 * (`docs/proposals/control-data-reset.md` R1): click → confirm → op → reported outcome.
 */
class DataViewModelTest {

    private class FakeRealmDataClient(private val ids: List<String>) : RealmDataClient {
        override fun realmDataTypeIds(): List<String> = ids
        override fun isResettable(typeId: String): Boolean = true
        override suspend fun hasData(typeId: String): Boolean? = true
        override suspend fun deleteAll(typeId: String) = RealmDataResetOutcome.Deleted(0)
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun ordersTypesByPresentationSortOrder() = vmTest { vms ->
        // Enumeration order puts the unknown type first; presentation sortOrder must reorder
        // File (100) and Workout (200) ahead of the unknown (Int.MAX_VALUE).
        val vm = vms.own(DataViewModel(
            FakeRealmDataClient(listOf("tenshu/zzz-unknown", Workout.ID, File.ID)),
        ))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(File.ID, Workout.ID, "tenshu/zzz-unknown"),
            vm.uiState.value.typeIds,
        )
    }

    @Test
    fun emptyRealm_yieldsNoTypes() = vmTest { vms ->
        val vm = vms.own(DataViewModel(FakeRealmDataClient(emptyList())))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), vm.uiState.value.typeIds)
        assertFalse(vm.uiState.value.loading)
    }

    // ---- only types that hold data -----------------------------------------

    /** A client whose presence answers a test can change between refreshes. */
    private class PresenceClient(
        private val ids: List<String>,
        var presence: Map<String, Boolean?>,
        private val onDelete: (String) -> Unit = {},
        /** Suspend in [deleteAll] until cancelled — a reset that is still running. */
        private val deleteBlocks: Boolean = false,
    ) : RealmDataClient {
        override fun realmDataTypeIds(): List<String> = ids
        override fun isResettable(typeId: String): Boolean = true
        override suspend fun hasData(typeId: String): Boolean? = presence[typeId]
        override suspend fun deleteAll(typeId: String): RealmDataResetOutcome {
            onDelete(typeId)
            if (deleteBlocks) awaitCancellation()
            return RealmDataResetOutcome.Deleted(1)
        }
    }

    @Test
    fun typesWithNoData_getNoCard() = vmTest { vms ->
        // Routine and its runs are registered in Control for every user,
        // but someone who only uses Drive has never stored one.
        val vm = vms.own(DataViewModel(PresenceClient(
            ids = listOf(File.ID, Workout.ID, "tenshu/routine", "tenshu/routineRun"),
            presence = mapOf(
                File.ID to true,
                Workout.ID to false,
                "tenshu/routine" to false,
                "tenshu/routineRun" to false,
            ),
        )))

        assertTrue(vm.uiState.value.loading, "nothing is known before the check runs")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(File.ID), vm.uiState.value.typeIds)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun aTypeWhosePresenceIsUnknown_isStillShown() = vmTest { vms ->
        // ⚠ Hiding a type the probe could not read would hide data the user has.
        val vm = vms.own(DataViewModel(PresenceClient(
            ids = listOf(File.ID, Workout.ID),
            presence = mapOf(File.ID to null, Workout.ID to false),
        )))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(File.ID), vm.uiState.value.typeIds)
    }

    @Test
    fun refresh_picksUpDataStoredSinceTheLastCheck() = vmTest { vms ->
        val client = PresenceClient(
            ids = listOf(File.ID, Workout.ID),
            presence = mapOf(File.ID to true, Workout.ID to false),
        )
        val vm = vms.own(DataViewModel(client))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(File.ID), vm.uiState.value.typeIds)

        client.presence = mapOf(File.ID to true, Workout.ID to true)
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(File.ID, Workout.ID), vm.uiState.value.typeIds)
    }

    @Test
    fun aRefreshNeverDropsTheCardBeingReset() = vmTest { vms ->
        // The type reads empty mid-reset (its rows are going), but the card carries the
        // progress and the Stop button, so it must stay until the reset finishes.
        val client = PresenceClient(
            ids = listOf(File.ID, Workout.ID),
            presence = mapOf(File.ID to true, Workout.ID to true),
            deleteBlocks = true,
        )
        val vm = vms.own(DataViewModel(client))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()
        client.presence = mapOf(File.ID to true, Workout.ID to false)
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Workout.ID, vm.uiState.value.resettingTypeId, "the reset is still running")
        assertEquals(listOf(File.ID, Workout.ID), vm.uiState.value.typeIds)

        // Once it ends, the refresh that follows drops the now-empty type.
        vm.onStopResetClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(File.ID), vm.uiState.value.typeIds)
    }

    @Test
    fun aResetThatEmptiesTheType_removesItsCard() = vmTest { vms ->
        lateinit var client: PresenceClient
        client = PresenceClient(
            ids = listOf(File.ID, Workout.ID),
            presence = mapOf(File.ID to true, Workout.ID to true),
            onDelete = { typeId -> client.presence = client.presence + (typeId to false) },
        )
        val vm = vms.own(DataViewModel(client))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(File.ID), vm.uiState.value.typeIds)
        // The result still names the type even though its card is gone.
        assertEquals(Workout.ID, vm.uiState.value.resetResult?.typeId)
    }

    // ---- the reset flow -----------------------------------------------------

    @Test
    fun onlyResettableTypesAreMarked() = vmTest { vms ->
        // Mirrors production: Workout's rows live in a service-data array, File's do not.
        val vm = vms.own(DataViewModel(
            MockRealmDataClient(
                typeIds = listOf(File.ID, Workout.ID),
                resettable = setOf(Workout.ID),
            ),
        ))

        assertEquals(setOf(Workout.ID), vm.uiState.value.resettableTypeIds)
    }

    @Test
    fun clickingDeleteArmsTheConfirmation_andDeletesNothingYet() = vmTest { vms ->
        val client = MockRealmDataClient(typeIds = listOf(Workout.ID))
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(Workout.ID)

        assertEquals(Workout.ID, vm.uiState.value.pendingResetTypeId)
        // ⚠ The confirmation IS the guard — there is no undo (§10 Q2), so a click must
        // never reach the op.
        assertTrue(client.deleted.isEmpty(), "nothing may be deleted before confirming")
    }

    @Test
    fun dismissingTheConfirmationDeletesNothing() = vmTest { vms ->
        val client = MockRealmDataClient(typeIds = listOf(Workout.ID))
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllDismissed()
        vm.onDeleteAllConfirmed()

        assertNull(vm.uiState.value.pendingResetTypeId)
        assertTrue(client.deleted.isEmpty(), "a dismissed confirmation must disarm the reset")
    }

    @Test
    fun anUnresettableTypeCannotEvenArm() = vmTest { vms ->
        val client = MockRealmDataClient(typeIds = listOf(File.ID), resettable = emptySet())
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(File.ID)

        assertNull(
            vm.uiState.value.pendingResetTypeId,
            "a dialog promising a reset the op cannot perform must not open",
        )
    }

    @Test
    fun confirmingRunsTheResetAndReportsTheCount() = vmTest { vms ->
        val client = MockRealmDataClient(
            typeIds = listOf(Workout.ID),
            outcome = { RealmDataResetOutcome.Deleted(12) },
        )
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(Workout.ID), client.deleted)
        assertEquals(
            DataResetResult(Workout.ID, RealmDataResetOutcome.Deleted(12)),
            vm.uiState.value.resetResult,
        )
        assertNull(vm.uiState.value.resettingTypeId, "the busy marker must clear")
    }

    @Test
    fun aFailureIsReported_notSwallowed() = vmTest { vms ->
        // A dropped TResult.Failure would leave the user believing their data is gone when
        // nothing was deleted.
        val client = MockRealmDataClient(
            typeIds = listOf(Workout.ID),
            outcome = { RealmDataResetOutcome.Failed },
        )
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DataResetResult(Workout.ID, RealmDataResetOutcome.Failed),
            vm.uiState.value.resetResult,
        )
    }

    @Test
    fun stoppingARunningResetReportsStopped_andClearsTheBusyMarker() = vmTest { vms ->
        // A cancelled reset never returns an outcome, so without the completion handler
        // the card would spin forever. ⚠ Stopped is NOT Failed: what was deleted before
        // the stop stays deleted on every device (the op tombstones each chunk first).
        val client = MockRealmDataClient(
            typeIds = listOf(Workout.ID),
            outcome = { awaitCancellation() },
        )
        val vm = vms.own(DataViewModel(client))

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(Workout.ID, vm.uiState.value.resettingTypeId, "the reset is running")

        vm.onStopResetClicked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DataResetResult(Workout.ID, RealmDataResetOutcome.Stopped),
            vm.uiState.value.resetResult,
        )
        assertNull(vm.uiState.value.resettingTypeId, "the busy marker must clear on a stop too")
    }

    @Test
    fun theResultClearsOnceShown() = vmTest { vms ->
        val vm = vms.own(DataViewModel(MockRealmDataClient(typeIds = listOf(Workout.ID))))

        vm.onDeleteAllClicked(Workout.ID)
        vm.onDeleteAllConfirmed()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onResetResultShown()

        assertNull(vm.uiState.value.resetResult)
    }
}
