package app.tenshu.drive.ui.common.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.DriveErrorKeys
import app.tenshu.drive.data.copyKey
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.common.PdfDocument
import app.tenshu.drive.ui.common.openPdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the byte fetch + PDF open + per-page rendering for the open-one-file PDF
 * viewer — the PDF analogue of [ImagePreviewViewModel]. The single bounded byte
 * fetch (incl. any OnDemand relay round-trip) goes through
 * [FilesRepository.getRemoteFile], same as the image path; the bytes are parsed
 * once into a [PdfDocument] held for the screen's lifetime and closed in
 * [onCleared].
 *
 * Pages render lazily ([renderPage]) so a large document doesn't rasterise every
 * page up front, and serially behind [renderMutex] because neither platform
 * renderer (Android `PdfRenderer`, PDFBox) tolerates concurrent access.
 */
class PdfPreviewViewModel(
    private val filePath: String,
    private val filesRepository: FilesRepository,
) : ViewModel() {

    val uiState: StateFlow<PdfPreviewUiState> get() = _uiState
    private val _uiState: MutableStateFlow<PdfPreviewUiState> =
        MutableStateFlow(PdfPreviewUiState(isLoading = true))

    private var document: PdfDocument? = null
    private val renderMutex = Mutex()
    // Keyed by page only: the screen renders at the container width and the
    // zoomable modifier scales the result, so one bitmap per page is enough. A
    // window resize keeps the prior bitmap (scaled) rather than churning renders.
    private val pageCache = mutableMapOf<Int, ImageBitmap>()

    init {
        viewModelScope.launch {
            when (val result = filesRepository.getRemoteFile(filePath)) {
                is RepositoryResult.Success -> {
                    val opened = withContext(Dispatchers.Default) {
                        openPdfDocument(result.value)
                    }
                    if (opened != null && opened.pageCount > 0) {
                        document = opened
                        _uiState.value = PdfPreviewUiState(pageCount = opened.pageCount)
                    } else {
                        opened?.close()
                        _uiState.value = PdfPreviewUiState(error = DriveErrorKeys.PREVIEW_PDF_UNREADABLE)
                    }
                }

                is RepositoryResult.Error ->
                    _uiState.value = PdfPreviewUiState(error = result.error.copyKey())
            }
        }
    }

    /**
     * Renders page [pageIndex] at [targetWidth] px, cached per page. Returns
     * `null` before the document has opened or if the page can't be rendered.
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int): ImageBitmap? {
        pageCache[pageIndex]?.let { return it }
        val doc = document ?: return null
        return renderMutex.withLock {
            pageCache[pageIndex]?.let { return@withLock it }
            val bitmap = withContext(Dispatchers.Default) {
                doc.renderPage(pageIndex, targetWidth)
            }
            if (bitmap != null) pageCache[pageIndex] = bitmap
            bitmap
        }
    }

    override fun onCleared() {
        document?.close()
        document = null
        pageCache.clear()
    }
}
