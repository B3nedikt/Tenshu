package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailClass
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.file.test.createFileProxy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.assertNotNull

/**
 * Fixtures for the ladder renderer's JVM tests: a decoder that counts, an upload through the real minter with or
 * without the ladder, and the stored rungs read back.
 */
internal object LadderFixtures {

    /** [JvmImageDecoder.Default], counting every decode either path asks for. */
    class CountingDecoder : JvmImageDecoder {
        val decodes = AtomicInteger()

        override fun decode(bytes: ByteArray): BufferedImage? {
            decodes.incrementAndGet()
            return JvmImageDecoder.Default.decode(bytes)
        }

    }

    /**
     * Uploads [bytes] as [fileName] through the real `UploadFile` op and the real JVM `GetPreview`,
     * with the ladder renderer wired when [ladder] — production desktop — and without it otherwise,
     * which is Android's and iOS's path. Returns each still rung's
     * stored bytes, `null` where none was stored.
     */
    suspend fun upload(
        scope: CoroutineScope,
        fileName: String,
        bytes: ByteArray,
        ladder: Boolean,
        decoder: JvmImageDecoder = JvmImageDecoder.Default,
    ): Map<ThumbnailClass.Still, ByteArray?> {
        val proxy = createFileProxy(
            scope,
            thumbnailLadderRenderer = if (ladder) JvmThumbnailLadderRenderer(decoder) else null,
        )
        proxy.additionalImplementations[GetPreview.ID] = JvmGetPreviewImplementation(NoOpPreviewCache, decoder)
        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to fileName.toTString(),
                UploadFile.Params.DATA to bytes.toTByteArray(proxy.tContext),
            ),
        )
        val contentId = contentIdOf(proxy, "/$fileName")
        return ThumbnailClasses.STILLS.associateWith { rung ->
            proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath(contentId, rung.id))?.get()
        }
    }

    private suspend fun contentIdOf(proxy: TestProxy, path: String): String {
        val id = DataId.StringDataId.create(proxy.tContext, File.ID, path)
        val row = assertNotNull(proxy.executionContext.database.getData(id), "the row for $path")
        val contentId = proxy.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString
        return assertNotNull(contentId?.get()?.takeIf { it.isNotEmpty() }, "the content id of $path")
    }

    /**
     * A photograph-like frame: smooth gradients, a few hard edges and mild deterministic grain — so
     * the JPEG sizes are ordinary and scaling differences show up as edges, not as noise.
     */
    fun photo(width: Int, height: Int, format: String = "jpeg", seed: Int = 1): ByteArray {
        val random = Random(seed)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val grain = random.nextInt(-6, 7)
            val band = if ((x / (width / 8 + 1) + y / (height / 6 + 1)) % 2 == 0) 30 else 0
            val r = (x * 220 / width + band + grain).coerceIn(0, 255)
            val g = (y * 200 / height + grain).coerceIn(0, 255)
            val b = (((x + y) * 160) / (width + height) + 40 + grain).coerceIn(0, 255)
            image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return encode(image, format)
    }

    /** Per-pixel noise: the frame the caps exist to refuse, and the one that walks the step-down. */
    fun noise(width: Int, height: Int, seed: Int = 7): ByteArray {
        val random = Random(seed)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0x1000000))
        return encode(image, "png")
    }

    /**
     * Alternating 1 px black and white columns: mean 127.5, and exactly the detail an unfiltered 2×
     * subsampled read keeps one phase of — a solid black picture.
     */
    fun stripes(edge: Int): ByteArray {
        val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until edge) for (x in 0 until edge) image.setRGB(x, y, if (x % 2 == 0) 0 else 0xFFFFFF)
        return encode(image, "png")
    }

    /** Mean channel value, 0–255. */
    fun meanBrightness(image: BufferedImage): Double {
        var total = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            total += (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
        }
        return total.toDouble() / (image.width.toLong() * image.height * 3)
    }

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    fun decode(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(bytes.inputStream()))

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /** Mean absolute difference per channel, 0–255, of two same-sized images. */
    fun meanAbsoluteDifference(a: BufferedImage, b: BufferedImage): Double {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            total += kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
                kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
                kotlin.math.abs((p and 0xFF) - (q and 0xFF))
        }
        return total.toDouble() / (a.width.toLong() * a.height * 3)
    }
}
