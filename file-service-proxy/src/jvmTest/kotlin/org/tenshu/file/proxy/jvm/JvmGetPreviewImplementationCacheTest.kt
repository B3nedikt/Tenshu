package org.tenshu.file.proxy.jvm

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.InMemoryPreviewCache
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.sha256
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Coverage for the Phase 1 server-side preview cache wired into
 * `JvmGetPreviewImplementation`.
 *
 * The user-visible failure cured here is the Binder watchdog kill on
 * Drive Android when a folder of ~20 multi-MB JPEGs is opened — every
 * `FileMapper.map` re-emission ran the full
 * `GetData → ImageIO.read → scale → encode` path per file (see
 * `docs/tech-debt.md` §"File Previews Are Regenerated From the Full
 * File on Every Access"). The cache turns warm reads into a cheap
 * `(sha256(bytes), w, h)` lookup against the persisted entry.
 *
 * Coverage:
 *  - cache miss invokes the generator once, then caches the result;
 *  - cache hit returns the stored bytes without re-decoding;
 *  - different `(width, height)` for the same content yield distinct
 *    cache entries (size-discriminator);
 *  - different content under the same path is a distinct entry
 *    (content-keyed, not path-keyed — the load-bearing property
 *    against overwrite).
 */
@OptIn(ExperimentalTime::class)
class JvmGetPreviewImplementationCacheTest {

    /**
     * `PreviewCache` decorator that counts the number of `put`s. A cache
     * `put` corresponds 1:1 with a generator invocation in
     * `JvmGetPreviewImplementation` (every successful decode/scale/encode
     * path ends in a `put` of the produced bytes), so counting puts is a
     * direct proxy for counting expensive generator runs without having
     * to instrument `ImageIO` itself.
     */
    private class CountingCache(
        private val delegate: PreviewCache = InMemoryPreviewCache()
    ) : PreviewCache {
        var putCount: Int = 0
            private set

        override fun get(contentHash: String, width: Int, height: Int): ByteArray? =
            delegate.get(contentHash, width, height)

        override fun put(
            contentHash: String,
            width: Int,
            height: Int,
            bytes: ByteArray
        ): Boolean {
            putCount += 1
            return delegate.put(contentHash, width, height, bytes)
        }
    }

    private val testProxy = createFileProxy()

    private fun encodeAsJpeg(seedColor: Int, size: Int = 32): ByteArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Vary slightly per-pixel so JPEG compression doesn't
                // collapse two distinct seeds to byte-identical output.
                val v = (seedColor + (x * 7) + (y * 13)) and 0xff
                img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    /**
     * Persist [sourceBytes] under [path] in the file database and return
     * a freshly-constructed `File` composite the impl can use as `caller`.
     *
     * We pass the composite directly rather than re-fetching as an
     * `Entity<File>` from the durable DB: `JvmGetPreviewImplementation`
     * dispatches `caller = file` into `GetData`, which expects either a
     * `File` (path resolved via the typed accessor) or an `Entity<*>`
     * with the path field present. The test-DB roundtrip can drop the
     * typed projection, so the unbranded `File` is the lower-friction
     * caller shape for unit tests.
     */
    private fun seedFile(path: String, sourceBytes: ByteArray): File {
        testProxy.fileDatabase.writeRawByteArray(path, sourceBytes)
        return File.create(
            context = testProxy.tContext,
            path = path,
        )
    }

    private fun invokePreview(
        impl: JvmGetPreviewImplementation,
        file: File,
        width: Int,
        height: Int,
        format: String? = null,
        quality: Int? = null,
    ): TByteArray? {
        val params: Map<String, Data> = buildMap {
            put(GetPreview.Params.WIDTH, width.toLong().toTInteger(testProxy.tContext))
            put(GetPreview.Params.HEIGHT, height.toLong().toTInteger(testProxy.tContext))
            if (format != null) {
                put(GetPreview.Params.FORMAT, format.toTString(testProxy.tContext))
            }
            if (quality != null) {
                put(GetPreview.Params.QUALITY, quality.toLong().toTInteger(testProxy.tContext))
            }
        }
        val noOpFactory = OperationImplementationFactory { _ -> null }
        return (impl as SyncOperationImplementation).execute<TByteArray>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = noOpFactory,
            caller = file,
            params = params
        )
    }

    @Test
    fun jpegFormat_producesJpegMagicBytes_underLargeCap() {
        // Q1 / Phase 3 (drive-synced-thumbnails.md §10.2): the LARGE render passes
        // FORMAT=jpeg. The bytes must be JPEG (FF D8 SOI magic) and stay under the
        // LARGE cap (48 KB) for a 256 px photo.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val src = encodeAsJpeg(seedColor = 0x33, size = 320)
        val file = seedFile("/large.jpg", src)

        val jpeg = invokePreview(impl, file, 256, 256, format = GetPreview.Params.FORMAT_JPEG)

        assertNotNull(jpeg, "JPEG-format preview must render")
        val bytes = jpeg.get()
        assertTrue(bytes.size >= 2, "JPEG must carry a header")
        assertEquals(
            0xFF, bytes[0].toInt() and 0xFF,
            "JPEG stream starts with the SOI marker byte 0xFF",
        )
        assertEquals(
            0xD8, bytes[1].toInt() and 0xFF,
            "JPEG stream's second byte is 0xD8 (SOI)",
        )
        assertTrue(
            bytes.size <= 48 * 1024,
            "A 256 px JPEG thumbnail must fit the LARGE cap; was ${bytes.size}",
        )
    }

    /**
     * §17.6: a caller that names no format and asks above the hero rung's edge gets JPEG. ⚠ Nothing
     * but a render from the original answers there, and as PNG a 3072 px render measured 7.6 MB — a
     * viewer asking for the size it draws would ship that per page. ⚠ The source has to be larger
     * than the request, or the render keeps the source's size and this asks nothing.
     */
    @Test
    fun anUnformattedPreviewAboveTheHeroEdgeDefaultsToJpeg_andOneAtItStaysPng() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedFile("/big.jpg", encodeAsJpeg(seedColor = 0x21, size = 1_400))

        val above = invokePreview(impl, file, 1_280, 1_280)!!.get()
        val at = invokePreview(impl, file, 1_024, 1_024)!!.get()

        assertEquals(listOf(0xFF, 0xD8), above.take(2).map { it.toInt() and 0xFF }, "above 1024 px: JPEG")
        assertEquals(0x89, at[0].toInt() and 0xFF, "at 1024 px: still PNG, as every existing caller got")
    }

    @Test
    fun pngDefault_producesPngMagicBytes() {
        // Absent FORMAT ⇒ PNG (default), preserving the pre-Phase-3 behaviour.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val src = encodeAsJpeg(seedColor = 0x44, size = 120)
        val file = seedFile("/list.jpg", src)

        val png = invokePreview(impl, file, 100, 100)

        assertNotNull(png, "Default (PNG) preview must render")
        val bytes = png.get()
        assertTrue(bytes.size >= 4, "PNG must carry a header")
        // PNG signature: 89 50 4E 47 ("\x89PNG").
        assertEquals(0x89, bytes[0].toInt() and 0xFF, "PNG byte 0 is 0x89")
        assertEquals('P'.code, bytes[1].toInt() and 0xFF, "PNG byte 1 is 'P'")
        assertEquals('N'.code, bytes[2].toInt() and 0xFF, "PNG byte 2 is 'N'")
        assertEquals('G'.code, bytes[3].toInt() and 0xFF, "PNG byte 3 is 'G'")
    }

    @Test
    fun cacheMiss_invokesGenerator_thenSubsequentCallHitsCache() {
        val cache = CountingCache()
        val impl = JvmGetPreviewImplementation(cache)
        val src = encodeAsJpeg(seedColor = 0x40)
        val file = seedFile("/photo.jpg", src)

        val first = invokePreview(impl, file, 100, 100)
        assertNotNull(first, "First call should produce a preview")
        assertEquals(1, cache.putCount, "Generator should run exactly once on miss")

        val second = invokePreview(impl, file, 100, 100)
        assertNotNull(second, "Second call should also produce a preview")
        assertContentEquals(
            first.get(), second.get(),
            "Cache hit must return the same bytes as the original generator"
        )
        assertEquals(
            1, cache.putCount,
            "Second call should NOT invoke the generator (cache hit)"
        )
    }

    @Test
    fun differentDimensions_areDistinctCacheEntries() {
        val cache = CountingCache()
        val impl = JvmGetPreviewImplementation(cache)
        val src = encodeAsJpeg(seedColor = 0x80)
        val file = seedFile("/photo2.jpg", src)

        val small = invokePreview(impl, file, 50, 50)
        val large = invokePreview(impl, file, 200, 200)

        assertNotNull(small)
        assertNotNull(large)
        assertEquals(
            2, cache.putCount,
            "Distinct (w,h) must each trigger a generator run"
        )

        // Repeat: both dimensions now hit the cache.
        invokePreview(impl, file, 50, 50)
        invokePreview(impl, file, 200, 200)
        assertEquals(
            2, cache.putCount,
            "Repeat calls at each dimension must hit the cache"
        )

        // The cache is content-keyed; the same hash entry exists at
        // two distinct (w,h) coordinates.
        val hash = sha256(src).toHexString()
        assertNotNull(cache.get(hash, 50, 50))
        assertNotNull(cache.get(hash, 200, 200))
    }

    @Test
    fun differentContentSamePath_isDistinctCacheEntry() {
        // Content-derived key (NOT path-derived) — the load-bearing
        // property against overwrite. Two distinct file bodies at the
        // same path must NEVER share a cache entry; the old preview
        // orphans (Phase 2 GC), and the new content gets a fresh entry.
        val cache = CountingCache()
        val impl = JvmGetPreviewImplementation(cache)

        val srcV1 = encodeAsJpeg(seedColor = 0x10, size = 32)
        val fileV1 = seedFile("/replace.jpg", srcV1)
        val previewV1 = invokePreview(impl, fileV1, 100, 100)
        assertNotNull(previewV1)
        assertEquals(1, cache.putCount)

        // Overwrite the bytes at the same path with NEW content. The
        // impl computes sha256(bytes) freshly on every call, so the
        // cache lookup keys off the new content and misses.
        val srcV2 = encodeAsJpeg(seedColor = 0xC0, size = 32)
        testProxy.fileDatabase.writeRawByteArray("/replace.jpg", srcV2)

        val previewV2 = invokePreview(impl, fileV1, 100, 100)
        assertNotNull(previewV2)
        assertEquals(
            2, cache.putCount,
            "New content under same path must miss the cache (content-keyed)"
        )
        // And the two previews are not byte-identical (different source
        // pixels → different scaled output).
        assertNotEquals(
            previewV1.get().toList(),
            previewV2.get().toList(),
            "Distinct sources should yield distinct previews"
        )

        // Both entries live in the cache, keyed by their respective
        // content hashes.
        val hashV1 = sha256(srcV1).toHexString()
        val hashV2 = sha256(srcV2).toHexString()
        assertNotNull(cache.get(hashV1, 100, 100))
        assertNotNull(cache.get(hashV2, 100, 100))
    }

    /**
     * `drive-synced-thumbnails.md` §17: the mint re-renders an over-cap JPEG at a lower quality.
     * ⚠⚠ Both halves matter — a quality the encoder ignored would re-decline the same bytes, and a
     * cache keyed without the quality would answer the step-down with the over-cap render it is
     * trying to replace.
     */
    @Test
    fun jpegQuality_isHonoured_andKeysItsOwnCacheEntry() {
        val cache = CountingCache()
        val impl = JvmGetPreviewImplementation(cache)
        // Per-pixel variation, so quality has something to throw away.
        val src = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB).also { img ->
            val random = java.util.Random(42)
            for (y in 0 until 512) for (x in 0 until 512) img.setRGB(x, y, random.nextInt(0xFFFFFF))
        }.let { img -> ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray() }
        val file = seedFile("/detailed.png", src)

        val default = invokePreview(impl, file, 256, 256, format = GetPreview.Params.FORMAT_JPEG)
        val lower = invokePreview(impl, file, 256, 256, format = GetPreview.Params.FORMAT_JPEG, quality = 30)
        assertNotNull(default)
        assertNotNull(lower)
        assertTrue(
            lower.get().size < default.get().size * 3 / 4,
            "q30 must encode materially smaller than the default: ${lower.get().size} vs ${default.get().size}",
        )
        assertEquals(2, cache.putCount, "a quality is its own cache entry, never the default render")

        invokePreview(impl, file, 256, 256, format = GetPreview.Params.FORMAT_JPEG, quality = 30)
        assertEquals(2, cache.putCount, "and a repeat of that quality is a hit")
    }

    @Test
    fun noOpCache_stillReturnsPreviews_butNeverCaches() {
        // With the NoOpPreviewCache (default for tests not opting in),
        // every call runs the generator — the cache wiring is purely
        // an optimisation, never load-bearing for correctness.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val src = encodeAsJpeg(seedColor = 0x55)
        val file = seedFile("/noop.jpg", src)

        val first = invokePreview(impl, file, 100, 100)
        val second = invokePreview(impl, file, 100, 100)
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first.get().isNotEmpty())
        assertTrue(second.get().isNotEmpty())
    }
}
