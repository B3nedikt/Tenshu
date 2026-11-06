package app.tenshu.drive.ui.common.preview

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.image_preview_more_actions
import app.tenshu.drive.ui.error.driveLabel
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ui.chrome.AppTopAppBar
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

val LocalImagePreviewViewModelFactory =
    staticCompositionLocalOf<@Composable (String) -> ImagePreviewViewModel> {
        // `key = path` makes the ViewModel instance unique per file. Without
        // it, `koinViewModel` caches by class alone in the ambient
        // `ViewModelStore` — navigating to a second `FilePreview`
        // destination returns the cached VM initialized with the FIRST
        // file's path (since `parameters { … }` is only invoked on initial
        // construction). Symptom: clicking the second uploaded photo
        // displays the first photo's image from `ImageRepository`.
        // Mirrors `Destination.Folder` (`key = "folder_${path}"`) which
        // already uses the same pattern correctly.
        { path ->
            koinViewModel(
                key = "preview_$path",
                parameters = { parametersOf(path) }
            )
        }
    }

@Composable
fun ImagePreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    viewModel: ImagePreviewViewModel
) {

    val uiState by viewModel.uiState.collectAsState()

    ImagePreviewScreen(
        modifier = modifier,
        filePath = filePath,
        navigateUp = navigateUp,
        showFileActions = showFileActions,
        image = uiState.bitmap,
        isLoading = uiState.isLoading,
        error = uiState.error,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewScreen(
    modifier: Modifier = Modifier,
    filePath: String,
    navigateUp: () -> Unit,
    showFileActions: () -> Unit,
    image: ImageBitmap?,
    isLoading: Boolean,
    error: ErrorCopyKey?,
) {
    Column(
        modifier
            .fillMaxSize()
    ) {
        AppTopAppBar(
            title = getFileName(filePath),
            onBack = navigateUp,
            actions = {
                Icon(
                    modifier = Modifier.clickable { showFileActions() },
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.image_preview_more_actions)
                )
            }
        )

        // Branch order matters: bitmap wins (cache hit shows it immediately),
        // then error, then loading. The `isLoading` flag is the gating state
        // while the fetch + decode runs after a cache miss.
        when {
            image != null -> {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        // `testTag` derived from bitmap dimensions so UI tests can
                        // verify *which* bitmap is rendered (not just that the
                        // ImagePreviewScreen received the right `filePath`).
                        // Used by `FilesScreenIntegrationTest.testTwoUploadsShowCorrectDetailImage`
                        // to pin the desktop-only "second-photo shows first-photo"
                        // regression — see `KmpNavHost.desktop.kt` per-entry
                        // `ViewModelStore` decoration.
                        .testTag(previewImageTestTag(image.width, image.height))
                        .zoomable(rememberZoomState()),
                )
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = error.driveLabel(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private fun getFileName(filePath: String): String {
    return filePath.split("/").last().split(".").first()
}

/**
 * Stable, deterministic `testTag` for the preview `Image`. Encoding
 * dimensions lets tests assert which bitmap is on screen — sufficient
 * to distinguish images uploaded via the test fixture's
 * `mockPngBytesForUri(...)` which sizes per `?w=&h=` URI hints.
 */
internal fun previewImageTestTag(width: Int, height: Int): String =
    "previewImage_${width}x${height}"