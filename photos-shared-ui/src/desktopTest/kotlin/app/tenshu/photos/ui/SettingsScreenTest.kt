package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ingest.IngestSourceInfo
import app.tenshu.photos.ui.screens.settings.SettingsScreen
import app.tenshu.photos.ui.screens.settings.SettingsUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** What S7 renders (`photos-app.md` PH-4). */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    private fun source(
        id: String,
        enabled: Boolean = true,
        status: String = "UP_TO_DATE",
        pending: Long = 0,
        error: String = "",
    ) = IngestSourceInfo(
        sourceId = id,
        appId = "app.tenshu.drive",
        displayName = id,
        kind = "GALLERY",
        enabled = enabled,
        status = status,
        pendingCount = pending,
        lastRunAtMs = 0,
        lastError = error,
    )

    private fun screen(state: SettingsUiState, onToggle: (String, Boolean) -> Unit = { _, _ -> }) =
        @androidx.compose.runtime.Composable {
            TenshuTheme { SettingsScreen(state = state, onToggle = onToggle, onRetry = {}, onOpenTrash = {}) }
        }

    @Test
    fun theDuplicateSkipRowSaysWhatItCompares() = runComposeUiTest {
        // ⚠⚠ "Already in Tenshu" could mean the file name, the date, or this phone's own memory
        // of what it uploaded — and only one of those is what the drain does. The supporting line
        // is where that is said, so it is what the test holds.
        setContent(screen(SettingsUiState(sources = listOf(source("Camera")), loading = false)))

        waitUntil {
            onAllNodesWithText(
                "Don't upload a photo whose exact copy is already saved, even from another device"
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theHeaderSaysWhereBackupIsActuallyConfigured() = runComposeUiTest {
        setContent(screen(SettingsUiState(sources = listOf(source("Camera")), loading = false)))

        // ⚠⚠ The sentence that keeps the switch honest: off really stops a source, but on cannot
        // force backups while the owning app's own switch is off.
        waitUntil {
            onAllNodesWithText(
                "Turning a source off stops it backing up. Backup itself is set up in Tenshu Drive."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Camera").assertIsDisplayed()
    }

    @Test
    fun anUnreachableProxyIsNotAnEmptyBackupList() = runComposeUiTest {
        setContent(screen(SettingsUiState(loading = false, readFailed = true)))

        waitUntil { onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            onAllNodesWithText(
                "Nothing is being backed up from this device yet.\nSet up backup in Tenshu Drive — the photos it saves appear here."
            ).fetchSemanticsNodes().isEmpty(),
            "'nothing is backed up' for an unreachable proxy tells the user their backup is off",
        )
    }

    @Test
    fun aDeviceWithNoSourcesIsPointedAtDrive_notAtAPickerThatDoesNotExist() = runComposeUiTest {
        setContent(screen(SettingsUiState(sources = emptyList(), loading = false)))

        // ⚠ Photos does not own the drain yet (PH-4b), so it must not offer to create a source.
        waitUntil {
            onAllNodesWithText(
                "Nothing is being backed up from this device yet.\nSet up backup in Tenshu Drive — the photos it saves appear here."
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailingSourceReadsAsAProblem_notAsPaused() = runComposeUiTest {
        // ⚠ A source that is failing and a source the user paused look the same in a list and mean
        // opposite things.
        setContent(
            screen(
                SettingsUiState(
                    sources = listOf(source("Camera", error = "out of quota")),
                    loading = false,
                )
            )
        )

        waitUntil { onAllNodesWithText("Problem: out of quota").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anOffSourceReadsAsOff() = runComposeUiTest {
        setContent(
            screen(SettingsUiState(sources = listOf(source("Camera", enabled = false)), loading = false))
        )

        waitUntil { onAllNodesWithText("Off").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun progressSaysHowMuchIsLeft() = runComposeUiTest {
        setContent(
            screen(
                SettingsUiState(
                    sources = listOf(source("Camera", status = "RUNNING", pending = 42)),
                    loading = false,
                )
            )
        )

        waitUntil { onAllNodesWithText("Backing up — 42 left").fetchSemanticsNodes().isNotEmpty() }
    }
}
