package app.tenshu.control.ui.screens.data

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.RealmDataResetOutcome
import app.tenshu.control.data.RealmDataResetOutcome.Deleted
import org.junit.Rule
import org.junit.Test
import org.tenshu.core.file.File
import org.tenshu.core.health.Workout
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stateless `DataScreenContent` rendering + interaction (no VM / proxy). */
class DataScreenContentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersACardPerRealmDataType_viaPresentation() {
        rule.setContent {
            // File → "Files", Workout → "Workouts"; an unknown type id falls back to a
            // derived label ("Photo").
            DataScreenContent(
                DataUiState(typeIds = listOf(File.ID, Workout.ID, "tenshu/photo")),
            )
        }
        rule.onNodeWithText("Files").assertIsDisplayed()
        rule.onNodeWithText("Workouts").assertIsDisplayed()
        rule.onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test
    fun rendersSharesRow_evenWithNoTypes() {
        rule.setContent {
            DataScreenContent(DataUiState(typeIds = emptyList()))
        }
        rule.onNodeWithText("Shares").assertIsDisplayed()
    }

    @Test
    fun noStoredData_saysSo_butNotWhileStillChecking() {
        var state by mutableStateOf(DataUiState(typeIds = emptyList(), loading = true))
        rule.setContent { DataScreenContent(state) }
        rule.onNodeWithText("Nothing stored yet", substring = true).assertDoesNotExist()

        state = DataUiState(typeIds = emptyList(), loading = false)
        rule.onNodeWithText("Nothing stored yet", substring = true).assertIsDisplayed()

        state = DataUiState(typeIds = listOf(File.ID), loading = false)
        rule.onNodeWithText("Nothing stored yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingShares_invokesCallback() {
        var opened = false
        rule.setContent {
            DataScreenContent(
                DataUiState(typeIds = listOf(File.ID)),
                onOpenShares = { opened = true },
            )
        }
        rule.onNodeWithText("Shares").performClick()
        assertTrue(opened, "tapping the Shares row should invoke onOpenShares")
    }

    // ---- the reset action ---------------------------------------------------

    @Test
    fun theDeleteActionOnlyRendersForAResettableType() {
        rule.setContent {
            // Production shape: Workout's rows live in a service-data array, File's do not.
            DataScreenContent(
                DataUiState(
                    typeIds = listOf(File.ID, Workout.ID),
                    resettableTypeIds = setOf(Workout.ID),
                ),
            )
        }
        // Exactly one action — File's card must not offer a reset that deletes nothing.
        rule.onNodeWithText("Delete all…").assertIsDisplayed()
        assertEquals(
            1,
            rule.onAllNodesWithText("Delete all…").fetchSemanticsNodes().size,
            "only the resettable type may offer the action",
        )
    }

    @Test
    fun tappingDeleteArmsTheTypeItBelongsTo() {
        var armed: String? = null
        rule.setContent {
            DataScreenContent(
                DataUiState(
                    typeIds = listOf(Workout.ID),
                    resettableTypeIds = setOf(Workout.ID),
                ),
                onDeleteAllClicked = { armed = it },
            )
        }
        rule.onNodeWithText("Delete all…").performClick()
        assertEquals(Workout.ID, armed)
    }

    /**
     * ⚠ The confirmation copy is load-bearing, not decoration: it must say the data leaves
     * **all your devices** (§6 D3 — realm-wide, no per-device option) and must never claim
     * erasure *everywhere* (§5 — relay history survives until compaction).
     */
    @Test
    fun theConfirmationSaysAllYourDevices_andNeverPromisesErasureEverywhere() {
        rule.setContent {
            DataScreenContent(
                DataUiState(
                    typeIds = listOf(Workout.ID),
                    resettableTypeIds = setOf(Workout.ID),
                    pendingResetTypeId = Workout.ID,
                ),
            )
        }
        rule.onNodeWithText("Delete all Workouts?").assertIsDisplayed()
        rule.onNodeWithText(
            "This removes your Workouts from all your devices, and it cannot be undone.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun confirmingAndDismissingRouteToTheirCallbacks() {
        var confirmed = false
        var dismissed = false
        rule.setContent {
            DataScreenContent(
                DataUiState(
                    typeIds = listOf(Workout.ID),
                    resettableTypeIds = setOf(Workout.ID),
                    pendingResetTypeId = Workout.ID,
                ),
                onDeleteAllConfirmed = { confirmed = true },
                onDeleteAllDismissed = { dismissed = true },
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        rule.onNodeWithText("Delete all").performClick()
        assertTrue(confirmed)
    }

    /**
     * The reported outcome, asserted through [resetResultMessage] rather than the snackbar:
     * a snackbar auto-dismisses on the test clock, so asserting the rendered host would be a
     * timing race — while the message mapping is the part that can actually be wrong.
     *
     * ⚠ A failure must never read as a success: the user has to learn the data is still there.
     */
    @Test
    fun theOutcomeIsReported_countPluralsAndFailuresAlike() {
        rule.setContent {
            Column {
                Text(resetResultMessage(DataResetResult(Workout.ID, Deleted(12))))
                Text(resetResultMessage(DataResetResult(Workout.ID, Deleted(1))))
                Text(resetResultMessage(DataResetResult(Workout.ID, Deleted(0))))
                Text(resetResultMessage(DataResetResult(Workout.ID, RealmDataResetOutcome.Failed)))
            }
        }
        rule.onNodeWithText("Deleted 12 items from all your devices").assertIsDisplayed()
        rule.onNodeWithText("Deleted 1 item from all your devices").assertIsDisplayed()
        rule.onNodeWithText("There was nothing to delete").assertIsDisplayed()
        rule.onNodeWithText("Nothing was removed", substring = true).assertIsDisplayed()
    }
}
