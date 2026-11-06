package app.tenshu.drive.ui.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the decrypt-to-temp-file step shared by the video viewer (D2a) and
 * "open in another app" (D3) — `docs/proposals/drive-content-preview.md` §5/§6:
 * bytes round-trip through a temp file with the right suffix, and deletion actually
 * removes it (threat-model A5 amendment — the plaintext must not linger). Runs on the
 * JVM (the `jvmMain` actual shared by android + desktop).
 */
class PreviewTempFileTest {

    @Test
    fun write_thenDelete_roundTripsAndRemovesThePlaintext() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val path = assertNotNull(writePreviewTempFile(bytes, "pdf"))
        val file = File(path)
        try {
            assertTrue(file.exists(), "temp file should exist after write")
            assertTrue(path.endsWith(".pdf"), "suffix should reflect the extension: $path")
            assertContentEquals(bytes, file.readBytes(), "bytes must round-trip verbatim")
        } finally {
            deletePreviewTempFile(path)
        }
        assertFalse(File(path).exists(), "the decrypted temp file must be gone after delete")
    }

    @Test
    fun blankExtension_fallsBackToTmpSuffix() {
        val path = assertNotNull(writePreviewTempFile(byteArrayOf(0), ""))
        try {
            assertTrue(path.endsWith(".tmp"), "blank extension → .tmp suffix: $path")
        } finally {
            deletePreviewTempFile(path)
        }
    }

    @Test
    fun deletingAMissingFile_isSilent() {
        // Best-effort cleanup must never throw (e.g. double-delete on re-clear).
        deletePreviewTempFile("/no/such/preview-.pdf")
    }
}
