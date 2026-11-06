package app.tenshu.drive.ui.common.details

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileActionsBottomSheetTest {

    // FileActionsViewModel only touches the repository on delete; the
    // "Details & Activity" path under test never calls it.
    private val repo = FakeFilesRepository { _, _, _ -> error("unused") }

    private val offlinePrefs = object : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun detailsAndActivityRoutesPathToOnDetailsRequested() = composeVmTest { vms ->
        var detailsPath: String? = null
        var completed = false

        setContent {
            TenshuTheme {
                FileActionsBottomSheet(
                    viewModel = vms.own(FileActionsViewModel(repo, offlinePrefs, NoopSyncRulesPreferences, "/resume.pdf", isFolder = false)),
                    onActionCompleted = { completed = true },
                    onMoveRequested = {},
                    onRenameRequested = {},
                    onDetailsRequested = { detailsPath = it },
                )
            }
        }

        onNodeWithText("Details & Activity").performClick()

        assertEquals("/resume.pdf", detailsPath)
        assertTrue(completed, "sheet should dismiss after navigating to details")
    }

    // §10 restructure (drive-manage-access-ux.md): the folder-only row
    // *navigates* to the Manage access screen instead of nesting a sheet.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun manageAccessRoutesPathToOnManageAccessRequested() = composeVmTest { vms ->
        var managePath: String? = null
        var completed = false

        setContent {
            TenshuTheme {
                FileActionsBottomSheet(
                    viewModel = vms.own(FileActionsViewModel(repo, offlinePrefs, NoopSyncRulesPreferences, "/Photos", isFolder = true)),
                    onActionCompleted = { completed = true },
                    onMoveRequested = {},
                    onRenameRequested = {},
                    onDetailsRequested = {},
                    onManageAccessRequested = { managePath = it },
                )
            }
        }

        onNodeWithText("Manage access").performClick()

        assertEquals("/Photos", managePath)
        assertTrue(completed, "sheet should dismiss after navigating to Manage access")
    }
}
