package app.tenshu.drive.ui.common.preview

import app.tenshu.error.ErrorCopyKey

/**
 * State for [PdfPreviewScreen]: spinner while [isLoading], error text when
 * [error] is non-null, otherwise the pager renders [pageCount] pages. Page
 * bitmaps are NOT held here — they're rendered lazily per page via
 * [PdfPreviewViewModel.renderPage] so a large document doesn't materialise every
 * page up front. Mirrors [ImagePreviewUiState]'s shape.
 */
data class PdfPreviewUiState(
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    /**
     * ⚠⚠ A catalogue **key**, never a sentence (`core-error-copy-catalogue.md` §5.0). This was a
     * `String?` holding `RepositoryError.toString()`, which rendered the sealed class's own name on
     * screen. Screens resolve it with `driveLabel()`.
     */
    val error: ErrorCopyKey? = null,
)
