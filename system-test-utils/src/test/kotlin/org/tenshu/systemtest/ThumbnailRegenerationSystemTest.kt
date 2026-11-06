package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.Preview
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.core.relation.eager
import org.tenshu.file.client.fileService
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replacing a file's content must **regenerate** its thumbnail classes from the new bytes.
 *
 * ⚠⚠ This pins the one behaviour `GetPreview.Params.GENERATE_FRESH` exists for, and it was
 * completely unpinned: deleting that parameter from `UploadFileImplementation.renderThumbnail`
 * broke no test anywhere. The failure it guards is quiet and compounding — the resolver's
 * stored-class short-circuit would answer the *generation* call with the previous content's class,
 * which is then stored as the new one, so every replace re-encodes a JPEG of a JPEG and walks the
 * ladder downhill while the suite stays green.
 *
 * ⚠ It lives here rather than in `:file-service-proxy` because `createFileProxy` registers no
 * `GetPreview` implementation — thumbnail generation silently no-ops there (`renderThumbnail`
 * swallows the missing-operation failure), so a unit test could never observe this at all.
 *
 * Asserted on **pixels, not blob identity**: a class is keyed by contentId, so new content lands at
 * a new blob path either way. What betrays a stale render is that the picture is the old colour.
 */
class ThumbnailRegenerationSystemTest {

    private fun solidJpeg(colour: Color): ByteArray {
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = colour
            fillRect(0, 0, 1200, 900)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    /** Mean red-minus-blue over a sampled grid: strongly positive for red, negative for blue. */
    private fun redness(bytes: ByteArray): Int {
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "preview must decode")
        var total = 0L
        var n = 0
        for (x in 0 until image.width step 8) {
            for (y in 0 until image.height step 8) {
                val rgb = image.getRGB(x, y)
                total += ((rgb shr 16) and 0xFF) - (rgb and 0xFF)
                n++
            }
        }
        return (total / n).toInt()
    }

    @Test fun replacingContentRegeneratesTheThumbnailClasses() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Desktop")

            val fs = host.tenshuClient.fileService.defaultFileSystem
            fs.uploadFile("/", "photo.jpg", solidJpeg(Color.RED))
            fs.replaceFile("/", "photo.jpg", solidJpeg(Color.BLUE))

            // The ordinary read path, served by the stored class — so this shows exactly what the
            // replace stored.
            val resolved = fs.root.files
                .search<File> { this.path equalTo "/photo.jpg" }
                .select { relations(eager(File.Preview(width = 1024, height = 1024))) }
                .resolve()
            val preview = resolved.getOrNull()?.get(File.Preview)?.getOrNull()

            val bytes = assertNotNull(preview, "the replaced file must still have a preview")
            assertTrue(
                redness(bytes) < 0,
                "the stored class must show the NEW (blue) content — a positive redness means the " +
                    "replace re-stored the previous content's class instead of rendering fresh",
            )
        } finally {
            system.shutdown()
        }
    }
}
