package app.tenshu.drive.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

actual class PdfDocument internal constructor(
    private val document: PDDocument,
) {
    private val renderer = PDFRenderer(document)

    actual val pageCount: Int get() = document.numberOfPages

    actual fun renderPage(pageIndex: Int, targetWidth: Int): ImageBitmap? {
        if (pageIndex !in 0 until document.numberOfPages) return null
        return try {
            // The crop box is the visible page area, in PDF points; scale to the
            // requested pixel width. ImageType.RGB renders on a white background
            // (no alpha), which is what a PDF page expects.
            val pageWidthPoints = document.getPage(pageIndex).cropBox.width
            if (pageWidthPoints <= 0f) return null
            val scale = targetWidth.coerceAtLeast(1) / pageWidthPoints
            renderer.renderImage(pageIndex, scale, ImageType.RGB).toComposeImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    actual fun close() {
        document.close()
    }
}

actual fun openPdfDocument(bytes: ByteArray): PdfDocument? = try {
    PdfDocument(Loader.loadPDF(bytes))
} catch (e: Throwable) {
    null
}
