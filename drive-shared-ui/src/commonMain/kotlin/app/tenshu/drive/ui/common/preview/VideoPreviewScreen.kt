package app.tenshu.drive.ui.common.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.DriveErrorKeys
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.image_preview_more_actions
import app.tenshu.drive.ui.common.VideoPlaybackState
import app.tenshu.drive.ui.common.VideoPlayer
import app.tenshu.drive.ui.error.driveLabel
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Per-file factory for [VideoPreviewViewModel], keyed by path (same reasoning as
 * [LocalImagePreviewViewModelFactory] — without the key `koinViewModel` caches by
 * class and a second open returns the first file's VM).
 */
val LocalVideoPreviewViewModelFactory =
    staticCompositionLocalOf<@Composable (String) -> VideoPreviewViewModel> {
        { path ->
            koinViewModel(
                key = "video_preview_$path",
                parameters = { parametersOf(path) }
            )
        }
    }

/**
 * Video viewer (`docs/proposals/drive-content-preview.md` §5): plays the decrypted file via the
 * platform [VideoPlayer]. Shares the image viewer's chrome + actions sheet.
 *
 * ⚠ Only reached where the platform *has* a player — `App.kt`'s router gates on
 * `isVideoPlaybackSupported()`, exactly as the image branch gates on `isImagePreviewSupported`, so
 * there is no unsupported state here.
 *
 * ⚠⚠ **Two waits, one spinner.** Getting the bytes reachable ([VideoPreviewUiState.isLoading]) and
 * getting a picture on screen ([VideoPlaybackState.Buffering]) are separate, and the second is the
 * long one on the streaming path — the player has to pull the container header, and for a
 * moov-at-tail MP4 the file's tail, through the proxy fd, each read a relay round trip. Showing a
 * spinner for only the first hands the user a black `VideoView` for the part that actually takes
 * time, which reads as a broken player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    viewModel: VideoPreviewViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    // ⚠ Keyed on the source: a new byte source is a new playback attempt, so a failure from the
    // previous one must not survive into it.
    var playback: VideoPlaybackState by remember(uiState.videoSource) {
        mutableStateOf(VideoPlaybackState.Buffering)
    }

    Column(modifier.fillMaxSize()) {
        AppTopAppBar(
            title = filePath.substringAfterLast('/'),
            onBack = navigateUp,
            actions = {
                Icon(
                    modifier = Modifier.clickable { showFileActions() },
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.image_preview_more_actions),
                )
            }
        )

        val source = uiState.videoSource
        val failure = playback as? VideoPlaybackState.Failed

        when {
            // A player that gave up: drop it rather than leave a dead black surface under the
            // message. The actions sheet above still reaches rename / move / details.
            //
            // ⚠⚠ The catalogue sentence, not `failure.diagnostic` (`core-error-copy-catalogue.md`
            // §5.0). The codes stay in the state for a bug report.
            failure != null -> Centered {
                Text(
                    text = DriveErrorKeys.PREVIEW_VIDEO_UNPLAYABLE.driveLabel(),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            source != null -> Box(Modifier.fillMaxSize()) {
                VideoPlayer(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                    onStateChanged = { playback = it },
                )
                if (playback == VideoPlaybackState.Buffering) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }

            uiState.error != null -> Centered {
                Text(text = uiState.error!!.driveLabel(), color = MaterialTheme.colorScheme.error)
            }

            uiState.isLoading -> Centered { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
