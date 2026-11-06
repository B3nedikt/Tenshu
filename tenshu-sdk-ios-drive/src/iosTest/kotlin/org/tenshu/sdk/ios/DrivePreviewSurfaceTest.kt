@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4 (ios-drive-parity.md): the preview surface — shared-classifier routing, and the
 * decrypted byte path over the route-1 runtime (upload → encrypt/store → `getRemoteFile` →
 * byte-for-byte plaintext back, then the D2a temp-file lifecycle the video/share renderers use).
 */
class DrivePreviewSurfaceTest {

    @AfterTest
    fun restoreFakes() = DriveReadSurface.installFakes()

    @Test
    fun routesByTheSharedClassifier() {
        assertEquals("image", DrivePreviewSurface.previewKindFor("photo.JPG"))
        assertEquals("image", DrivePreviewSurface.previewKindFor("scan.heic"))
        assertEquals("pdf", DrivePreviewSurface.previewKindFor("report.pdf"))
        assertEquals("video", DrivePreviewSurface.previewKindFor("clip.mov"))
        assertEquals("other", DrivePreviewSurface.previewKindFor("notes.txt"))
        assertEquals("other", DrivePreviewSurface.previewKindFor("archive.zip"))
        assertEquals("other", DrivePreviewSurface.previewKindFor("no-extension"))
    }

    @Test
    fun loadsUploadedBytesBackByteForByteAndRunsTheTempFileLifecycle() = runBlocking {
        val dir = NSTemporaryDirectory() + "preview-test-" + Random.nextLong().toString(16)
        val runtime = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        DriveReadSurface.installRepository(runtime.client.filesRepository)

        // A real payload through the real ingest path (the fixture PDF doubles as the
        // "known bytes" — strict-parser-valid, deterministic).
        val payload = DriveLocalRuntime.tinyPdfBytes()
        val srcDir = NSTemporaryDirectory() + "preview-src-" + Random.nextLong().toString(16)
        NSFileManager.defaultManager.createDirectoryAtPath(
            srcDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        val srcPath = "$srcDir/fixture.pdf"
        assertTrue(
            memScoped {
                platform.Foundation.NSData.create(
                    bytes = allocArrayOf(payload),
                    length = payload.size.toULong(),
                ).writeToFile(srcPath, atomically = true)
            },
        )
        val uploaded = runtime.client.filesRepository.uploadFile(
            uriString = srcPath, parentPath = "/", fileName = "fixture.pdf",
        )
        assertTrue(uploaded is app.tenshu.drive.data.RepositoryResult.Success, "upload failed: $uploaded")

        // Decrypted read-back: the full store/crypto round-trip preserves the plaintext.
        val loaded = DrivePreviewSurface.loadBytes("/fixture.pdf")
        assertContentEquals(payload, loaded, "getRemoteFile round-trips the exact plaintext")

        // D2a temp-file lifecycle: written with the leaf name, then fully removed.
        val temp = DrivePreviewSurface.writePreviewTempFile("/fixture.pdf", "fixture.pdf")
        assertTrue(temp != null && temp.endsWith("/fixture.pdf"), "temp file written, got $temp")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(temp!!))
        DrivePreviewSurface.deletePreviewTempFile(temp)
        assertTrue(!NSFileManager.defaultManager.fileExistsAtPath(temp), "temp file removed")
    }

    @Test
    fun loadBytesIsNullForAMissingFileOverTheRealRuntime() = runBlocking {
        val dir = NSTemporaryDirectory() + "preview-missing-" + Random.nextLong().toString(16)
        val runtime = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        DriveReadSurface.installRepository(runtime.client.filesRepository)
        assertNull(DrivePreviewSurface.loadBytes("/nope.png"), "missing file → null, never throws")
    }
}
