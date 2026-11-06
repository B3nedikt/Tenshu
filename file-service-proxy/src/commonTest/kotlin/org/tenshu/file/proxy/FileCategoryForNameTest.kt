package org.tenshu.file.proxy

import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the extension → [FileCategory] mapping that drives both the list-row glyph
 * and the preview router (`docs/proposals/drive-content-preview.md` §3). The
 * helper lives in `:file-service`, which has no test source set of its own, so the
 * test rides here.
 */
class FileCategoryForNameTest {

    @Test
    fun mapsKnownExtensionsToCategories() {
        assertEquals(FileCategory.Image, fileCategoryForName("photo.jpg"))
        assertEquals(FileCategory.Image, fileCategoryForName("shot.PNG"))
        assertEquals(FileCategory.Image, fileCategoryForName("pic.heic"))
        assertEquals(FileCategory.Video, fileCategoryForName("clip.mp4"))
        assertEquals(FileCategory.Video, fileCategoryForName("movie.MOV"))
        assertEquals(FileCategory.Audio, fileCategoryForName("song.mp3"))
        assertEquals(FileCategory.Document, fileCategoryForName("report.pdf"))
        assertEquals(FileCategory.Document, fileCategoryForName("notes.txt"))
        assertEquals(FileCategory.Document, fileCategoryForName("deck.pptx"))
        assertEquals(FileCategory.Archive, fileCategoryForName("bundle.zip"))
    }

    @Test
    fun isCaseInsensitiveOnTheExtension() {
        assertEquals(fileCategoryForName("a.JpEg"), fileCategoryForName("a.jpeg"))
    }

    @Test
    fun usesOnlyTheFinalExtension() {
        // A dotted name resolves on its last segment, not an earlier one.
        assertEquals(FileCategory.Document, fileCategoryForName("archive.zip.pdf"))
        assertEquals(FileCategory.Archive, fileCategoryForName("report.pdf.zip"))
    }

    @Test
    fun unknownAndExtensionlessNamesFallBackToOther() {
        assertEquals(FileCategory.Other, fileCategoryForName("data.bin"))
        assertEquals(FileCategory.Other, fileCategoryForName("README"))
        assertEquals(FileCategory.Other, fileCategoryForName(""))
        // SVG is deliberately not Image — it would fail the bitmap decoders.
        assertEquals(FileCategory.Other, fileCategoryForName("logo.svg"))
    }

    @Test
    fun isPdf_singlesOutPdfFromOtherDocuments() {
        assertTrue(isPdf("report.pdf"))
        assertTrue(isPdf("REPORT.PDF"))
        assertTrue(isPdf("a.b.pdf"))
        // PDF is a Document, but the other Document types are not PDFs.
        assertFalse(isPdf("notes.txt"))
        assertFalse(isPdf("deck.pptx"))
        assertFalse(isPdf("pdf"))        // no extension, just a name
        assertFalse(isPdf("photo.jpg"))
        // Consistency: every isPdf name is also categorised Document.
        assertEquals(FileCategory.Document, fileCategoryForName("report.pdf"))
    }
}
