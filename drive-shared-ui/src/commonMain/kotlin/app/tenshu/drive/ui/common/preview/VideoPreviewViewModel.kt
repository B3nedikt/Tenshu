package app.tenshu.drive.ui.common.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.DriveErrorKeys
import app.tenshu.drive.data.copyKey
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.StreamingContentHandle
import app.tenshu.drive.ui.common.VideoSource
import app.tenshu.drive.ui.common.deletePreviewTempFile
import app.tenshu.drive.ui.common.isStreamingPreviewSupported
import app.tenshu.drive.ui.common.writePreviewTempFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Prepares the byte source for the video viewer (`docs/proposals/drive-content-preview.md` §5 +
 * `drive-streaming-preview.md` §3.5). It picks the delivery path:
 *
 *  - **D2b streaming** ([isStreamingPreviewSupported], Android API 26+): opens a bounded-memory
 *    [StreamingContentHandle] via [FilesRepository.openStreamingContent] — the player decrypts only
 *    the chunks it reads, and the whole-file plaintext never lands on disk. Preferred.
 *  - **D2a temp file** (fallback): fetches the whole decrypted blob via
 *    [FilesRepository.getRemoteFile] and writes it to a private temp file. Used when streaming is
 *    unsupported (API < 26) or the stream can't be opened.
 *
 * ⚠ It does **not** ask whether the platform can play video: `App.kt`'s router already did, and a
 * platform without a player never constructs this — see
 * [app.tenshu.drive.ui.common.isVideoPlaybackSupported].
 *
 * ⚠⚠ **Reaching [VideoPreviewUiState.videoSource] is not "ready to watch".** It means one op
 * returned a handle; the player has not read a byte yet. Everything after this — the container
 * header, a moov box at the tail, the first frames, each read a relay round trip on the streaming
 * path — is the player's own buffering, reported out of `VideoPlayer` and rendered by the screen.
 * Treating this state as "done" is what left a black `VideoView` under no spinner.
 *
 * ⚠ The temp-file path is transient decrypted plaintext on disk (threat-model A5); it is deleted
 * in [onCleared]. The streaming session is released when the player closes its fd
 * (Android `onRelease`); [onCleared] closes it too as a backstop for the rare case where the
 * player never opened the fd (`CloseContentStream` is idempotent).
 *
 * [streamingSupported] defaults to the platform capability check and is injectable so the path
 * selection is unit-testable without a device.
 *
 * ⚠⚠ [fileWriteContext] is injectable for the same reason, and it is not cosmetic. The temp-file
 * write hops off [viewModelScope] onto a real dispatcher, and the resumption comes back onto
 * `Dispatchers.Main` — so a test that leaves the load running has work in flight across its own
 * `resetMain()`. Measured 2026-09-07 with a probe: on `Dispatchers.Default` the hop's resumption
 * **never lands at all** (the "hop finished" line prints after the tests are over and unattributed
 * to any of them; "resumed" never prints), which is how
 * `Dispatchers.Main is used concurrently with setting it` reached a gate. Passing a
 * `StandardTestDispatcher(testScheduler)` puts the write under the test's own scheduler, so
 * `advanceUntilIdle()` completes it inside the test body — 3 of 3 whole-file cases then resume and
 * finish before their teardown.
 */
class VideoPreviewViewModel(
    private val filePath: String,
    private val filesRepository: FilesRepository,
    private val streamingSupported: Boolean = isStreamingPreviewSupported(),
    private val fileWriteContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    val uiState: StateFlow<VideoPreviewUiState> get() = _uiState
    private val _uiState: MutableStateFlow<VideoPreviewUiState> =
        MutableStateFlow(VideoPreviewUiState(isLoading = true))

    private var streamHandle: StreamingContentHandle? = null
    private var tempFilePath: String? = null

    // Survives [onCleared] (viewModelScope is cancelled by then) to release a streaming session the
    // player never claimed. Not lifecycle-bound on purpose; the launched close is one-shot.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // Prefer streaming; fall back to the temp-file path if it's unsupported or can't open.
        if (streamingSupported) {
            when (val result = filesRepository.openStreamingContent(filePath)) {
                is RepositoryResult.Success -> {
                    streamHandle = result.value
                    _uiState.value = VideoPreviewUiState(
                        videoSource = VideoSource.Stream(
                            handle = result.value,
                            fileName = filePath.substringAfterLast('/'),
                        ),
                    )
                    return
                }

                is RepositoryResult.Error -> Unit // fall through to the temp-file path
            }
        }
        loadViaTempFile()
    }

    private suspend fun loadViaTempFile() {
        when (val result = filesRepository.getRemoteFile(filePath)) {
            is RepositoryResult.Success -> {
                val extension = filePath.substringAfterLast('.', "")
                val path = withContext(fileWriteContext) {
                    writePreviewTempFile(result.value, extension)
                }
                if (path != null) {
                    tempFilePath = path
                    _uiState.value = VideoPreviewUiState(videoSource = VideoSource.File(path))
                } else {
                    _uiState.value = VideoPreviewUiState(error = DriveErrorKeys.PREVIEW_VIDEO_UNPLAYABLE)
                }
            }

            is RepositoryResult.Error ->
                _uiState.value = VideoPreviewUiState(error = result.error.copyKey())
        }
    }

    override fun onCleared() {
        tempFilePath?.let { deletePreviewTempFile(it) }
        tempFilePath = null
        streamHandle?.let { handle ->
            streamHandle = null
            cleanupScope.launch { handle.close() }
        }
    }
}
