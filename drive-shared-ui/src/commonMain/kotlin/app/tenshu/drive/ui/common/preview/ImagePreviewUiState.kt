package app.tenshu.drive.ui.common.preview

import app.tenshu.error.ErrorCopyKey

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Three legal shapes — the `when`-on-`bitmap`/`isLoading`/`error` triplet
 * is the screen's branch: spinner while [isLoading], error text when [error]
 * is non-null, bitmap when present, blank when none of the above (e.g.
 * fetch returned bytes that didn't decode).
 *
 * The fetch lifecycle is owned by [ImagePreviewViewModel], not the screen —
 * the screen is a pure projection.
 */
data class ImagePreviewUiState(
    val bitmap: ImageBitmap? = null,
    val isLoading: Boolean = false,
    /**
     * ⚠⚠ A catalogue **key**, never a sentence (`core-error-copy-catalogue.md` §5.0). This was a
     * `String?` holding `RepositoryError.toString()`, which rendered the sealed class's own name on
     * screen. Screens resolve it with `driveLabel()`.
     */
    val error: ErrorCopyKey? = null,
)
