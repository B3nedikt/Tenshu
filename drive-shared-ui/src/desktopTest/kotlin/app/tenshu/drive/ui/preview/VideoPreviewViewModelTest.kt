package app.tenshu.drive.ui.preview

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.StreamingContentHandle
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.common.VideoSource
import app.tenshu.drive.ui.common.preview.VideoPreviewViewModel
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Path selection for the video viewer (drive-streaming-preview.md §3.5, S4): streaming when
 * supported, temp-file fallback otherwise. The proxy fd + ContentProvider + VideoView are Android
 * framework paths (on-device); these tests cover the VM's platform-independent branching by
 * injecting the streaming capability + a fake repository.
 *
 * ⚠ Whether the platform can PLAY video is no longer among them: that test moved to the
 * router (`App.kt`), which is the only place that can act on the answer by choosing a different
 * screen. This VM is constructed only where a player exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPreviewViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun handle() = StreamingContentHandle(
        handle = "content-stream-1",
        totalSize = 1234L,
        readRangeFn = { _, _, _ -> ByteArray(0) },
        closeFn = { },
    )

    @Test
    fun beforeAnySourceArrives_theStateIsLoading_soTheScreenCanSpin() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Deliberately NOT advanced before the assertions: this is the state the screen sees
        // between construction and the first op returning. It has to be `isLoading`, never an empty
        // ready-looking state — an empty one renders as nothing at all.
        // ⚠ The repository is stubbed even though nothing should read it yet: `runTest` advances
        // the scheduler when the body ends, so the load DOES run — after the assertions.
        val vm = vms.own(VideoPreviewViewModel(
            filePath = "/movies/clip.mp4",
            filesRepository = FakeFilesRepository(remoteFileBytes = byteArrayOf(1, 2, 3)),
            streamingSupported = false,
            fileWriteContext = StandardTestDispatcher(testScheduler),
        ))

        assertTrue(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.videoSource)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun streamingSupported_opensStream_asVideoSource() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val streamHandle = handle()
        val repo = FakeFilesRepository(streamingResult = RepositoryResult.Success(streamHandle))
        val vm = vms.own(VideoPreviewViewModel(
            filePath = "/movies/clip.mp4",
            filesRepository = repo,
            streamingSupported = true,
            fileWriteContext = StandardTestDispatcher(testScheduler),
        ))
        advanceUntilIdle()

        val source = assertIs<VideoSource.Stream>(vm.uiState.value.videoSource)
        assertEquals(streamHandle, source.handle)
        assertEquals("clip.mp4", source.fileName)
        assertEquals(listOf("/movies/clip.mp4"), repo.openStreamingCalls)
        // Streaming worked: the whole-file path is never touched.
        assertTrue(repo.getRemoteFileCalls.isEmpty())
    }

    @Test
    fun streamingOpenFails_fallsBackToWholeFilePath() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            streamingResult = RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)),
            remoteFileBytes = byteArrayOf(1, 2, 3),
        )
        val vm = vms.own(VideoPreviewViewModel(
            filePath = "/movies/clip.mp4",
            filesRepository = repo,
            streamingSupported = true,
            fileWriteContext = StandardTestDispatcher(testScheduler),
        ))
        advanceUntilIdle()

        // Tried to stream, then fell back to the whole-file (D2a) fetch.
        assertEquals(listOf("/movies/clip.mp4"), repo.openStreamingCalls)
        assertEquals(listOf("/movies/clip.mp4"), repo.getRemoteFileCalls)
    }

    @Test
    fun streamingUnsupported_usesWholeFilePathDirectly() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(remoteFileBytes = byteArrayOf(1, 2, 3))
        val vm = vms.own(VideoPreviewViewModel(
            filePath = "/movies/clip.mp4",
            filesRepository = repo,
            streamingSupported = false,
            fileWriteContext = StandardTestDispatcher(testScheduler),
        ))
        advanceUntilIdle()

        // No stream attempt when the platform can't stream; straight to the whole-file fetch.
        assertTrue(repo.openStreamingCalls.isEmpty())
        assertEquals(listOf("/movies/clip.mp4"), repo.getRemoteFileCalls)
    }
}
