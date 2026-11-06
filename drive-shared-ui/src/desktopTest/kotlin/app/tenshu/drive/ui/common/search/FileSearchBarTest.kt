package app.tenshu.drive.ui.common.search

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import org.junit.Test
import kotlin.test.assertEquals

class FileSearchBarTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchBarRenders() = composeVmTest { vms ->
        setContent {
            TenshuTheme {
                FilesSearchBar(viewModel = vms.own(FilesSearchBarViewModel(FakeFilesRepository())))
            }
        }

        onNodeWithText("Search").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingAResultRow_invokesOnResultClickedWithThatFile() = runComposeUiTest {
        val folder = FileUiModel("/Reports", "Reports", "3 files", FileIcon.Folder, isFolder = true)
        val file = FileUiModel("/Reports/q3.pdf", "q3.pdf", "1 MB", FileIcon.GenericFile)
        var clicked: FileUiModel? = null

        setContent {
            TenshuTheme {
                FilesSearchBarContent(
                    uiState = FileSearchBarUiState(
                        files = listOf(folder, file),
                        query = "q3",
                        isActive = true, // expand the overlay so the results render
                    ),
                    onQueryChanged = {},
                    onActiveChanged = {},
                    onSearchClicked = {},
                    onResultClicked = { clicked = it },
                )
            }
        }

        onNodeWithText("q3.pdf").performClick()

        assertEquals("/Reports/q3.pdf", clicked?.path, "the tapped row's file is handed to the host")
    }
}
