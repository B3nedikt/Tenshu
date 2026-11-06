package org.tenshu.sdk.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TByteArray
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.getOrNull
import org.tenshu.core.relation.Property
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.writeToFile
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An upload on iOS mints the stored thumbnail classes (`drive-synced-thumbnails.md` §12) — the
 * K/N half of what `UploadFileImplementation`'s JVM tests already pin.
 *
 * ⚠⚠ The JVM could not have caught the regression this pins. `ThumbnailClassMinter.renderStill`
 * rendered every rung on iOS and then threw a ClassCastException assigning `Render.Bytes` to a local
 * Kotlin/Native had typed as `Render.Declined`; the catch-all turned that into `Failed`, the upload
 * still succeeded, and every photograph an iPhone backed up reached other devices with no thumbnail
 * at all (`docs/tech-debt.md`). Only a K/N run of the real upload path sees it.
 */
class UploadMintsThumbnailClassesTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun writePng(path: String) {
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(1600.0, 1200.0))
        val image = renderer.imageWithActions {
            UIColor.orangeColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, 1600.0, 1200.0))
            UIColor.blueColor.setFill()
            UIRectFill(CGRectMake(200.0, 200.0, 600.0, 400.0))
        }
        assertTrue(assertNotNull(UIImagePNGRepresentation(image)).writeToFile(path, atomically = true))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun anUploadedImageCarriesItsStoredThumbnailClasses() = runBlocking {
        val runtime = DriveLocalRuntime.assemble(
            NSTemporaryDirectory() + "upload-mint-" + Random.nextLong().toString(16),
            InMemorySecureStorageDelegate(),
        )
        val srcDir = NSTemporaryDirectory() + "upload-mint-src-" + Random.nextLong().toString(16)
        NSFileManager.defaultManager.createDirectoryAtPath(srcDir, withIntermediateDirectories = true, attributes = null, error = null)
        writePng("$srcDir/photo.png")

        val uploaded = runtime.client.filesRepository.uploadFile(uriString = "$srcDir/photo.png", parentPath = "/", fileName = "photo.png")
        assertTrue(uploaded is app.tenshu.drive.data.RepositoryResult.Success, "upload failed: $uploaded")

        suspend fun stored(property: Property.Dynamic<TByteArray>): ByteArray? =
            runtime.client.tenshuClient.fileService.defaultFileSystem.root.files
                .search<File> { this.path equalTo "/photo.png" }
                .select { relations(property) }
                .resolve().getOrNull()?.get(property)?.getOrNull()

        // ⚠ The row resolving is the control: a null rung next to a resolved row is the defect, not
        // a query that could not see the file.
        for ((name, property) in listOf("LIST" to File.Thumbnail, "LARGE" to File.ThumbnailLarge, "XLARGE" to File.ThumbnailXLarge)) {
            val bytes = stored(property)
            assertTrue(bytes != null && bytes.isNotEmpty(), "$name was not minted at upload")
        }
    }
}
