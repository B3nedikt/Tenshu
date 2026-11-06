package app.tenshu.drive.ui.common

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the desktop (PDFBox) actual of the [PdfDocument] seam:
 * synthesise a real PDF, open it through [openPdfDocument], and render a page to
 * a bitmap. Pins page count, the index guard, and that the rendered bitmap tracks
 * the requested pixel width / page aspect ratio.
 */
class PdfRenderDesktopTest {

    private fun pdfBytes(pageCount: Int, widthPt: Float, heightPt: Float): ByteArray {
        val document = PDDocument()
        repeat(pageCount) { document.addPage(PDPage(PDRectangle(widthPt, heightPt))) }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun opensAndReportsPageCount() {
        val pdf = assertNotNull(openPdfDocument(pdfBytes(3, 200f, 300f)))
        try {
            assertEquals(3, pdf.pageCount)
        } finally {
            pdf.close()
        }
    }

    @Test
    fun rendersPageAtRequestedWidthPreservingAspectRatio() {
        val pdf = assertNotNull(openPdfDocument(pdfBytes(1, 200f, 300f)))
        try {
            val bitmap = assertNotNull(pdf.renderPage(pageIndex = 0, targetWidth = 200))
            // Page is 200×300 pt; at 200 px wide the scale is ~1.0 → ~200×300 px.
            assertTrue(bitmap.width in 199..201, "width was ${bitmap.width}")
            assertTrue(bitmap.height in 298..302, "height was ${bitmap.height}")
        } finally {
            pdf.close()
        }
    }

    @Test
    fun outOfRangePageReturnsNull() {
        val pdf = assertNotNull(openPdfDocument(pdfBytes(1, 200f, 300f)))
        try {
            assertNull(pdf.renderPage(pageIndex = 5, targetWidth = 200))
            assertNull(pdf.renderPage(pageIndex = -1, targetWidth = 200))
        } finally {
            pdf.close()
        }
    }

    @Test
    fun nonPdfBytesReturnNull() {
        assertNull(openPdfDocument(byteArrayOf(1, 2, 3, 4)))
    }
}
