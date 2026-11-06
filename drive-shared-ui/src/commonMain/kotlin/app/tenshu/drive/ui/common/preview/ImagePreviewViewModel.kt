package app.tenshu.drive.ui.common.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.DriveErrorKeys
import app.tenshu.drive.data.copyKey
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.ui.image.decodeToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the byte fetch + bitmap decode for the open-one-file detail screen.
 *
 * The byte fetch — including any OnDemand round-trip to the relay — moved
 * here from the listing chain. Reason (commit context): projecting
 * `File.Data` from the listing chain caused
 * `OnDemandMaterialiser.materialiseEntityPlaceholders` to resolve bytes for
 * *every* file in the listing before the list emit crossed Binder — 20×
 * (relay round-trip + full-file disk read) on a folder of photos blew through
 * Android's Binder watchdog and killed both Drive and Control. The listing
 * now projects only metadata + small preview thumbnails; this VM does the
 * single, bounded byte fetch for the one file the user actually opened.
 *
 * **Cache hit-first.** If [imageRepository] already has the bitmap for this
 * path (a prior open populated it), the initial UI state shows the cached
 * bitmap immediately and we skip the fetch loop entirely. On miss the VM
 * goes through Loading → (Success(bitmap) | Error(message)). Successful
 * decodes populate [imageRepository] so a subsequent open is a hit.
 */
class ImagePreviewViewModel(
    private val filePath: String,
    private val filesRepository: FilesRepository,
    private val imageRepository: ImageRepository,
) : ViewModel() {

    val uiState: StateFlow<ImagePreviewUiState> get() = _uiState
    private val _uiState: MutableStateFlow<ImagePreviewUiState> = MutableStateFlow(
        imageRepository.getImage(filePath)?.bitmap
            ?.let { ImagePreviewUiState(bitmap = it) }
            ?: ImagePreviewUiState(isLoading = true)
    )

    init {
        // Cache hit path: initial state already carries the bitmap, no fetch.
        if (_uiState.value.bitmap == null) {
            viewModelScope.launch {
                when (val result = filesRepository.getRemoteFile(filePath)) {
                    is RepositoryResult.Success -> {
                        val bitmap = withContext(Dispatchers.Default) {
                            decodeToImageBitmap(result.value)
                        }
                        if (bitmap != null) {
                            imageRepository.putImage(filePath, bitmap)
                            _uiState.value = ImagePreviewUiState(bitmap = bitmap)
                        } else {
                            _uiState.value = ImagePreviewUiState(
                                error = DriveErrorKeys.PREVIEW_UNDECODABLE
                            )
                        }
                    }
                    is RepositoryResult.Error -> {
                        _uiState.value = ImagePreviewUiState(
                            error = result.error.copyKey()
                        )
                    }
                }
            }
        }
    }
}
