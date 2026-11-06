package app.tenshu.drive.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual class PdfDocument internal constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    actual val pageCount: Int get() = renderer.pageCount

    actual fun renderPage(pageIndex: Int, targetWidth: Int): ImageBitmap? {
        if (pageIndex !in 0 until renderer.pageCount) return null
        // PdfRenderer permits only one open page at a time and isn't thread-safe;
        // the VM's Mutex guarantees serialised access. Page dimensions are in PDF
        // points; scale to the requested pixel width preserving the aspect ratio.
        val page = renderer.openPage(pageIndex)
        return try {
            if (page.width <= 0 || page.height <= 0) return null
            val width = targetWidth.coerceAtLeast(1)
            val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // A PDF page is white; without this, transparent regions render black.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.asImageBitmap()
        } finally {
            page.close()
        }
    }

    actual fun close() {
        renderer.close()
        descriptor.close()
        file.delete()
    }
}

actual fun openPdfDocument(bytes: ByteArray): PdfDocument? = try {
    // PdfRenderer needs a seekable ParcelFileDescriptor; the bytes go through a
    // temp file in the app cache (java.io.tmpdir on Android), deleted on close.
    val file = File.createTempFile("preview", ".pdf")
    file.writeBytes(bytes)
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfDocument(file, descriptor, PdfRenderer(descriptor))
} catch (e: Throwable) {
    null
}
