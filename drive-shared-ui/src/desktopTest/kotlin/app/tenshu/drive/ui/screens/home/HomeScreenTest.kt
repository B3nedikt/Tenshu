package app.tenshu.drive.ui.screens.home

import app.tenshu.error.ErrorCopyKey

import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.liststate.ListLoadState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.paging.PagingData
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.StreamingContentHandle
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import app.tenshu.drive.ui.common.search.FilesSearchBarViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.viewmodel.test.OwnedViewModels
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Compose-render coverage for the HomeScreen recent-files surface
 * (file-service-history Phase 6): the [FilesRepository.recentFiles] stream
 * flows through [HomeScreenViewModel] into the rendered "Recent Files" list, and
 * a live update to the stream is reflected in the UI. Mirrors Control's
 * `HomeScreenTest` + Drive's `FilesScreenTest` harness.
 */
class HomeScreenTest {

    private val recent = MutableStateFlow(
        listOf<FileModel>(
            FileModel.Folder("/Documents", "Documents", "2 days ago", FileIcon.Folder),
            FileModel.File("/resume.pdf", "resume.pdf", "1.2 MB", FileIcon.GenericFile),
        )
    )

    /**
     * Paths the UI asked a preview for. The Recent-Files rows wire a
     * per-row `previewLoader` (mirroring `FilesScreen`); if that wiring is
     * missing, `getPreview` is never called and this stays empty — which is
     * exactly the "preview broken on Home → Recent Files" regression.
     */
    private val previewRequests = MutableStateFlow<List<String>>(emptyList())

    private val repository = object : FilesRepository {
        override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> =
            recent.map { ListLoadState.Loaded(it) }
        override suspend fun getFiles(folderPath: String): Flow<List<FileModel>> = recent
        override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> =
            flowOf(PagingData.from(recent.value))
        override suspend fun uploadFile(uriString: String, parentPath: String, fileName: String) =
            RepositoryResult.Success(Unit)
        override suspend fun createFolder(path: String) = RepositoryResult.Success(Unit)
        override suspend fun uploadFolder(
            uriString: String,
            folderPath: String,
            onProgress: (UploadProgress) -> Unit,
            onConflict: suspend (FileConflict) -> ConflictResolution,
            onCloudPreflight: suspend (app.tenshu.drive.data.CloudPreflight) -> app.tenshu.drive.data.CloudPreflightDecision,
        ) = RepositoryResult.Success(FolderUploadSummary(0, 0, 0, 0))
        override suspend fun contentHashOf(uriString: String): String? = null

        override suspend fun uploadForBackup(parentPath: String, fileName: String, uriString: String) =
            app.tenshu.ingest.backup.BackupUploadOutcome.Uploaded
        override suspend fun deleteFile(path: String) = RepositoryResult.Success(Unit)
        override suspend fun downloadFile(path: String) = RepositoryResult.Success(Unit)
        override suspend fun evictFile(path: String) = RepositoryResult.Success(Unit)
        override suspend fun hasLocalData(path: String) = false
        override suspend fun renameFile(path: String, newName: String) =
            RepositoryResult.Success("${path.substringBeforeLast("/")}/$newName")
        override suspend fun moveFile(sourcePath: String, destinationFolderPath: String) = RepositoryResult.Success(Unit)
        override suspend fun getRemoteFile(path: String) = RepositoryResult.Success(ByteArray(0))
        override suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle> =
            RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        override suspend fun getPreview(path: String, width: Int, height: Int): ByteArray? {
            // Record the request, then return null: the decode path isn't under
            // test here — only that the Home surface actually asks for previews.
            previewRequests.update { it + path }
            return null
        }
        override fun fileDetails(path: String): Flow<FileDetailsModel?> = flowOf(null)
        override fun fileActivity(path: String): Flow<List<FileActivity>> = flowOf(emptyList())
        override fun getFileName(uriString: String): String? = null

        override suspend fun shareLink(
            path: String,
            expiresAtMillis: Long,
            maxOpens: Int,
        ): RepositoryResult<app.tenshu.drive.data.ShareLinkPayload> =
            error("shareLink not used in this test")

        override suspend fun revokeShareLink(shareId: String): RepositoryResult<Unit> =
            error("revokeShareLink not used in this test")

        override fun shares(): Flow<ListLoadState<app.tenshu.drive.data.ShareModel>> =
            flowOf(ListLoadState.Loaded(emptyList()))

        override fun storageStatus(): Flow<app.tenshu.ingest.backup.StorageStatusModel?> = flowOf(null)

        override suspend fun shareFolder(folderPath: String) =
            error("shareFolder not used in this test")

        override suspend fun sharedFolders(): RepositoryResult<List<app.tenshu.drive.data.SharedFolderModel>> =
            RepositoryResult.Success(emptyList())

        override suspend fun browseSharedFolder(
            realmId: String,
            parentFileId: String,
        ): RepositoryResult<List<app.tenshu.drive.data.SharedFolderEntry>> =
            error("browseSharedFolder not used in this test")

        override suspend fun readSharedFile(realmId: String, fileId: String): RepositoryResult<ByteArray> =
            error("readSharedFile not used in this test")

        override suspend fun sharedWithMe(): RepositoryResult<List<app.tenshu.drive.data.InboundSharedRealm>> =
            RepositoryResult.Success(emptyList())
    }

    private fun startKoinGraph() = startKoin {
        modules(
            module {
                single<FilesRepository> { repository }
                single { FolderUploadController(get()) }
                single<OfflinePreferences> {
                    object : OfflinePreferences {
                        override fun observe() = flowOf(emptyList<String>())
                        override suspend fun setOffline(paths: List<String>) {}
                    }
                }
                single<SyncRulesPreferences> { NoopSyncRulesPreferences }
                viewModel { SelectionViewModel(get(), get(), get()) }
                viewModel { FilesSearchBarViewModel(get()) }
                viewModel { AddFileActionsViewModel(get(), get()) }
                viewModel { HomeScreenViewModel(get(), get()) }
            }
        )
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recentFilesAreDisplayed() = composeVmTest { vms ->
        stopKoin(); startKoinGraph()
        renderHome(vms)

        onNode(androidx.compose.ui.test.hasText("Recent Files"), useUnmergedTree = true)
            .assertIsDisplayed()
        waitUntil {
            onAllNodesWithText("resume.pdf", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Documents", useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recentFilesUpdateIsReflected() = composeVmTest { vms ->
        stopKoin(); startKoinGraph()
        renderHome(vms)

        waitUntil {
            onAllNodesWithText("resume.pdf", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        recent.update { it + FileModel.File("/new.png", "new.png", "5 KB", FileIcon.GenericFile) }

        waitUntil {
            onAllNodesWithText("new.png", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Regression: Home → Recent Files must request per-row previews, just like
     * the folder listing on `FilesScreen`. Before the fix the `FilesList` call
     * in `HomeScreen` passed no `previewLoader`, so `HorizontalFileItem` fell
     * straight to the category-icon branch and `getPreview` was never invoked
     * for a recent file — previews silently never rendered on Home.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun recentFilePreviewsAreRequested() = composeVmTest { vms ->
        stopKoin(); startKoinGraph()
        renderHome(vms)

        // Wait for the rows to compose (reliable: the text does appear).
        waitUntil {
            onAllNodesWithText("resume.pdf", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Let the per-row `produceState` preview-load coroutines run to
        // completion. Deliberately NOT `waitUntil { previewRequests… }`: if the
        // wiring is missing the predicate never flips and the desktop render
        // loop spins instead of failing — so we settle, then assert.
        waitForIdle()

        assertTrue(
            "Recent Files rows must request a per-row preview (like the folder " +
                "listing does); the FilesList previewLoader wiring is missing.",
            previewRequests.value.contains("/resume.pdf"),
        )
    }

    /**
     * Regression: tapping a recent-files row must navigate. Before the fix the
     * row click set `uiState.showFile`, but nothing observed it in `HomeScreen`
     * (no `LaunchedEffect`, no `showFile`/`showFolder` callbacks wired) — so
     * Recent Files were rendered but unclickable. A folder row routes through
     * `showFolder`, a file row through `showFile`.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingRecentFileNavigates() = composeVmTest { vms ->
        stopKoin(); startKoinGraph()
        val openedFiles = mutableListOf<String>()
        val openedFolders = mutableListOf<String>()
        renderHome(
            vms,
            showFile = { openedFiles += it },
            showFolder = { openedFolders += it },
        )

        waitUntil {
            onAllNodesWithText("resume.pdf", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("resume.pdf", useUnmergedTree = true).performClick()
        waitUntil { openedFiles.isNotEmpty() }
        assertTrue("file row routes through showFile", openedFiles == listOf("/resume.pdf"))
        assertTrue("file row must not route through showFolder", openedFolders.isEmpty())

        onNodeWithText("Documents", useUnmergedTree = true).performClick()
        waitUntil { openedFolders.isNotEmpty() }
        assertTrue("folder row routes through showFolder", openedFolders == listOf("/Documents"))
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.renderHome(
        vms: OwnedViewModels,
        showFile: (String) -> Unit = {},
        showFolder: (String) -> Unit = {},
    ) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = vms.ownStore(ViewModelStore())
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                TenshuTheme {
                    HomeScreen(
                        viewModel = koinViewModel(),
                        fabViewModel = koinViewModel(),
                        showFile = showFile,
                        showFolder = showFolder,
                        showFileDetails = {},
                        showMovePicker = {},
                        showManageAccess = {},
                        onUploadFile = {},
                        onUploadFolder = {},
                        onUploadImages = {},
                    )
                }
            }
        }
    }
}
