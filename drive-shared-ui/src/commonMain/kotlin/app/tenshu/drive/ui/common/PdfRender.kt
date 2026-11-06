package app.tenshu.drive.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A PDF held open for paged bitmap rendering. Per
 * `docs/proposals/drive-content-preview.md` §2/§4 a PDF page reduces to an
 * [ImageBitmap], so the viewer reuses the same zoomable-bitmap surface as images
 * — this is the platform seam alongside `decodeToImageBitmap` (image) and the
 * future video poster extractor.
 *
 * Backed by `android.graphics.pdf.PdfRenderer` on Android and Apache PDFBox on
 * desktop (the JVM has no built-in PDF renderer). iOS is not a target of this
 * Compose module — a native PDFKit reader is the iOS path (proposal §4.1).
 *
 * **Not thread-safe.** Both platform renderers reject concurrent access;
 * [PdfPreviewViewModel] serialises every call with a `Mutex`. Call [close] when
 * done to release native resources (Android keeps a temp file + descriptor open;
 * PDFBox holds the parsed document in memory).
 */
expect class PdfDocument {
    /** Page count; `>= 1` for a valid document. */
    val pageCount: Int

    /**
     * Renders page [pageIndex] (0-based) to a bitmap roughly [targetWidth] px
     * wide (height follows the page aspect ratio). Returns `null` if the index is
     * out of range or the page can't be rendered.
     */
    fun renderPage(pageIndex: Int, targetWidth: Int): ImageBitmap?

    /** Releases native resources. Idempotent at the call site is not guaranteed. */
    fun close()
}

/** Opens [bytes] as a [PdfDocument], or `null` if they aren't a readable PDF. */
expect fun openPdfDocument(bytes: ByteArray): PdfDocument?
