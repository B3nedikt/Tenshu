package app.tenshu.drive.ui.screens.files

import app.tenshu.error.ErrorCopyKey

import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.liststate.ListLoadState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileDetailsModel
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
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialogViewModel
import app.tenshu.drive.ui.common.details.FileActionsViewModel
import app.tenshu.drive.ui.common.rename.RenameFileDialogViewModel
import app.tenshu.drive.ui.common.search.FilesSearchBarViewModel
import app.tenshu.drive.ui.screens.home.HomeScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import androidx.paging.PagingData
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.request.AsyncTaskExecutor

class FilesScreenTest {

    private lateinit var mockFilesRepository: FilesRepository

    private val mockRegistry = createCoreTestRegistry()
    private val mockTenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = object : AsyncTaskExecutor {
                override fun execute(task: TList<Data>): TResult<Data> = error("Not implemented")
                override suspend fun executeAsync(task: TList<Data>): TResult<Data> =
                    error("Not implemented")
            },
            schemaRegistry = mockRegistry,
            tContext = createTestContext(mockRegistry),
            requestChainFactory = { error("Not implemented") }
        )
    )

    @Before
    fun setup() {
        mockFilesRepository = object : FilesRepository {
            val allFiles = MutableStateFlow(
                listOf(
                    FileModel.Folder(
                        path = "/Documents",
                        name = "Documents",
                        subLine = "2 days ago",
                        previewIcon = FileIcon.Folder
                    ),
                    FileModel.File(
                        path = "/resume.pdf",
                        name = "resume.pdf",
                        subLine = "1.2 MB",
                        previewIcon = FileIcon.GenericFile
                    )
                )
            )

            override suspend fun uploadFile(
                uriString: String,
                parentPath: String,
                fileName: String
            ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

            override suspend fun getFiles(folderPath: String): Flow<List<FileModel>> = allFiles
            override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> =
                allFiles.map { ListLoadState.Loaded(it) }
            override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> =
                flowOf(PagingData.from(allFiles.value))
            override suspend fun createFolder(path: String): RepositoryResult<Unit> =
                RepositoryResult.Success(Unit)
            override suspend fun uploadFolder(
                uriString: String,
                folderPath: String,
                onProgress: (UploadProgress) -> Unit,
                onConflict: suspend (FileConflict) -> ConflictResolution,
            onCloudPreflight: suspend (app.tenshu.drive.data.CloudPreflight) -> app.tenshu.drive.data.CloudPreflightDecision,
            ): RepositoryResult<FolderUploadSummary> =
                RepositoryResult.Success(FolderUploadSummary(0, 0, 0, 0))
            override suspend fun contentHashOf(uriString: String): String? = null

            override suspend fun uploadForBackup(parentPath: String, fileName: String, uriString: String) =
                app.tenshu.ingest.backup.BackupUploadOutcome.Uploaded
            override suspend fun deleteFile(path: String): RepositoryResult<Unit> =
                RepositoryResult.Success(Unit)

            override suspend fun downloadFile(path: String): RepositoryResult<Unit> =
                RepositoryResult.Success(Unit)

            override suspend fun evictFile(path: String): RepositoryResult<Unit> =
                RepositoryResult.Success(Unit)

            override suspend fun hasLocalData(path: String): Boolean = false

            override suspend fun renameFile(path: String, newName: String): RepositoryResult<String> =
                RepositoryResult.Success("${path.substringBeforeLast("/")}/$newName")

            override suspend fun moveFile(
                sourcePath: String,
                destinationFolderPath: String,
            ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

            override suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray> =
                RepositoryResult.Success(ByteArray(0))

            override suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle> =
                RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))

            override suspend fun getPreview(path: String, width: Int, height: Int): ByteArray? = null

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

            override fun storageStatus(): Flow<app.tenshu.ingest.backup.StorageStatusModel?> =
                flowOf(null)

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

        startKoin {
            modules(
                module {
                    single { mockFilesRepository }
                    single { FolderUploadController(get()) }
                    single<TenshuClient> { mockTenshuClient }
                    single<OfflinePreferences> {
                        object : OfflinePreferences {
                            override fun observe() = flowOf(emptyList<String>())
                            override suspend fun setOffline(paths: List<String>) {}
                        }
                    }
                    single<SyncRulesPreferences> { NoopSyncRulesPreferences }
                    viewModel { SelectionViewModel(get(), get(), get()) }
                    viewModel { (path: String) -> FilesScreenViewModel(get(), get(), path) }
                    viewModel { FilesSearchBarViewModel(get()) }
                    viewModel { HomeScreenViewModel(get(), get()) }
                    viewModel { (path: String) -> CreateFolderDialogViewModel(get(), path) }
                    viewModel { params -> FileActionsViewModel(get(), get(), get(), params.get(), params.get()) }
                    viewModel { AddFileActionsViewModel(get(), get()) }
                    viewModel { (path: String) -> RenameFileDialogViewModel(get(), path) }
                }
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun filesAreDisplayed() = composeVmTest { vms ->
        val viewModelStore = vms.ownStore(ViewModelStore())
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = viewModelStore
        }

        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                TenshuTheme {
                    FilesScreen(
                        showFolder = { },
                        showFile = { },
                        showFileDetails = { },
                        showMovePicker = { },
                        showManageAccess = { },
                        viewModel = koinViewModel { parametersOf("/") },
                        fabViewModel = koinViewModel(),
                        onUploadFile = { },
                        onUploadFolder = { },
                        onUploadImages = { }
                    )
                }
            }
        }

        onNodeWithText("Documents").assertIsDisplayed()
        onNodeWithText("resume.pdf").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingFolderNavigates() = composeVmTest { vms ->
        val viewModelStore = vms.ownStore(ViewModelStore())
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = viewModelStore
        }

        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                TenshuTheme {
                    FilesScreen(
                        showFolder = { },
                        showFile = { },
                        showFileDetails = { },
                        showMovePicker = { },
                        showManageAccess = { },
                        viewModel = koinViewModel { parametersOf("/") },
                        fabViewModel = koinViewModel(),
                        onUploadFile = { },
                        onUploadFolder = { },
                        onUploadImages = { }
                    )
                }
            }
        }

        onNodeWithText("Documents").performClick()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun createFolderDialogWorks() = composeVmTest { vms ->
        val viewModelStore = vms.ownStore(ViewModelStore())
        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = viewModelStore
        }

        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                TenshuTheme {
                    FilesScreen(
                        showFolder = { },
                        showFile = { },
                        showFileDetails = { },
                        showMovePicker = { },
                        showManageAccess = { },
                        viewModel = koinViewModel { parametersOf("/") },
                        fabViewModel = koinViewModel(),
                        onUploadFile = { },
                        onUploadFolder = { },
                        onUploadImages = { }
                    )
                }
            }
        }

        onNodeWithContentDescription("Add").performClick()
        onNodeWithText("Folder").performClick()

        onNodeWithText("Create new folder").assertIsDisplayed()
        onNodeWithTag("folder_name_field").performTextReplacement("New Test Folder")
        onNodeWithText("Confirm").performClick()
    }
}
