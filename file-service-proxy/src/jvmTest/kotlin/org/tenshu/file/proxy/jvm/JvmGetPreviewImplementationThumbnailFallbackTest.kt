package org.tenshu.file.proxy.jvm

import app.tenshu.macos.MacImageDecoder
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.tenshu.file.test.createFileProxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1b of docs/proposals/drive-synced-thumbnails.md §10.1: `File.Preview` is the
 * single "best available preview" API, and the byte-less fallback to the stored,
 * eagerly-synced `File.Thumbnail` lives **inside** the `GetPreview` resolver.
 *
 * This unit test pins the load-bearing new branch directly: a file with a stored
 * `File.Thumbnail` but **no local bytes** (the byte-less / online-only device shape,
 * which `JvmGetPreviewImplementationCacheTest` can't reproduce because it always seeds
 * bytes) resolves to the stored thumbnail rather than `null`.
 *
 * The self-fetch wrinkle (a stored value property is not inline on the entity
 * composite — drive-synced-thumbnails.md §9) is exercised end-to-end here: the impl
 * resolves `File.Thumbnail` via `ExecutionContext.resolveProperty`, which re-fetches
 * the entity by id from the durable DB and reads the stored cell.
 */
@OptIn(ExperimentalEncodingApi::class)
class JvmGetPreviewImplementationThumbnailFallbackTest {

    private val testProxy = createFileProxy()

    /**
     * Persist a `File` entity at [path] whose thumbnail blob is present locally — but
     * with **no** local file bytes — and return the stored composite for use as
     * `caller`, mirroring the byte-less device shape (an entity synced from a peer whose
     * `OnDemand` `File.Data` was never fetched).
     *
     * ⚠ Since C7 this seeds the CONTENT PLANE, not the entity: the thumbnail is a
     * `dynamicProperty(Syncable(OnDemand))` whose bytes live in the `FileDatabase` under
     * a `contentId`-keyed path, so `set(File.Thumbnail, …)` no longer models anything
     * real. Mirrors `UploadFileImplementation.emitThumbnailClass`.
     */
    private fun seedThumbnailOnlyFile(path: String, thumbnail: ByteArray): File =
        seedClassesFile(path, list = thumbnail, large = null)

    /**
     * Persist a byte-less `File` carrying the requested thumbnail *classes*
     * (drive-synced-thumbnails.md §10.2). Pass `null` for a class to leave it unset.
     */
    private fun seedClassesFile(
        path: String,
        list: ByteArray?,
        large: ByteArray?,
        xlarge: ByteArray? = null,
        xxlarge: ByteArray? = null,
    ): File {
        val file = File.create(testProxy.tContext, path)
        // The blob path derives from the file's contentId, so the entity must carry one.
        val contentId = "content-" + path.trim('/').replace('/', '-')
        val entity = Entity.create<File>(
            context = testProxy.tContext,
            composite = file,
        ) {
            set(File.ContentId, contentId)
        }
        testProxy.executionContext.saveData(entity)
        if (list != null) {
            testProxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_LIST), list
            )
        }
        if (large != null) {
            testProxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_LARGE), large
            )
        }
        if (xlarge != null) {
            testProxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE), xlarge
            )
        }
        if (xxlarge != null) {
            testProxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XXLARGE), xxlarge
            )
        }
        return file
    }

    private fun previewParams(
        width: Int,
        height: Int,
        generateFresh: Boolean,
        preferOriginal: Boolean = false,
    ): Map<String, Data> = buildMap {
        put(GetPreview.Params.WIDTH, width.toLong().toTInteger(testProxy.tContext))
        put(GetPreview.Params.HEIGHT, height.toLong().toTInteger(testProxy.tContext))
        if (generateFresh) put(GetPreview.Params.GENERATE_FRESH, true.toTBoolean(testProxy.tContext))
        if (preferOriginal) put(GetPreview.Params.PREFER_ORIGINAL, true.toTBoolean(testProxy.tContext))
    }

    private fun invokePreview(
        impl: JvmGetPreviewImplementation,
        file: File,
        width: Int,
        height: Int,
        generateFresh: Boolean = false,
        preferOriginal: Boolean = false,
    ): TByteArray? {
        val params: Map<String, Data> = previewParams(width, height, generateFresh, preferOriginal)
        // ⚠ The factory below is NOT what makes this byte-less: `ExecutionContext.execute`
        // resolves `GetData` through the execution context's OWN factory, so this argument is
        // never consulted for it. What makes it byte-less is that nothing was written to
        // `fileDatabase` under this path.
        val noOpFactory = OperationImplementationFactory { _ -> null }
        return (impl as SyncOperationImplementation).execute<TByteArray>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = noOpFactory,
            caller = file,
            params = params,
        )
    }

    @Test
    fun byteLessFile_withStoredThumbnail_resolvesToStoredThumbnail() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val thumbnail = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = seedThumbnailOnlyFile("/synced.png", thumbnail)

        val preview = invokePreview(impl, file, 100, 100)

        assertNotNull(
            preview,
            "A byte-less file with a stored File.Thumbnail must resolve via the " +
                "GetPreview fallback rather than returning null",
        )
        assertContentEquals(
            thumbnail, preview.get(),
            "The resolved preview must be the stored thumbnail's bytes verbatim",
        )
    }

    @Test
    fun byteLessFile_withoutStoredThumbnail_resolvesToNull() {
        // No bytes AND no stored thumbnail (non-image / pre-feature file) → icon.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = File.create(testProxy.tContext, "/no-thumb.png")
        val entity = Entity.create<File>(context = testProxy.tContext, composite = file) {}
        testProxy.executionContext.saveData(entity)

        val preview = invokePreview(impl, file, 100, 100)

        assertNull(
            preview,
            "With neither local bytes nor a stored thumbnail, the resolver returns " +
                "null so the row falls back to its category icon",
        )
    }

    // ── Phase 3: class-aware selection (drive-synced-thumbnails.md §10.2) ──────────

    @Test
    fun byteLessFile_bothClasses_smallRequestResolvesList() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val list = byteArrayOf(1, 1, 1, 1)
        val large = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
        val file = seedClassesFile("/both.png", list = list, large = large)

        // A ≤100 request is covered by the smallest class (LIST).
        val preview = invokePreview(impl, file, 100, 100)

        assertNotNull(preview, "A small request with both classes stored must resolve")
        assertContentEquals(
            list, preview.get(),
            "A ≤100 px request resolves the smallest covering class (LIST)",
        )
    }

    @Test
    fun byteLessFile_bothClasses_largeRequestResolvesLarge() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val list = byteArrayOf(1, 1, 1, 1)
        val large = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
        val file = seedClassesFile("/both2.png", list = list, large = large)

        // A 480 request exceeds the LIST size (100) → prefer the sharper LARGE.
        val preview = invokePreview(impl, file, 480, 480)

        assertNotNull(preview, "A large request with both classes stored must resolve")
        assertContentEquals(
            large, preview.get(),
            "A request larger than the LIST size resolves the LARGE class",
        )
    }

    @Test
    fun byteLessFile_onlyList_largeRequestFallsBackToList() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val list = byteArrayOf(3, 3, 3, 3)
        val file = seedClassesFile("/list-only.png", list = list, large = null)

        // A large request with no LARGE stored falls back to the only available class.
        val preview = invokePreview(impl, file, 480, 480)

        assertNotNull(preview, "With only LIST stored, a large request still resolves")
        assertContentEquals(
            list, preview.get(),
            "No LARGE present → fall back to the available LIST class",
        )
    }

    @Test
    fun byteLessFile_noClasses_resolvesToNull() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/none.png", list = null, large = null)

        assertNull(
            invokePreview(impl, file, 480, 480),
            "No stored class of either kind → null (category icon)",
        )
    }

    // ── drive-content-preview.md §3 Phase 3: video poster on the desktop/JVM path ──

    /**
     * Resolves a preview with [bytes] available via a stubbed `GetData` — the
     * bytes-present shape the existing tests can't reach (they wire no `GetData`).
     */
    private fun invokePreviewWithBytes(
        impl: JvmGetPreviewImplementation,
        file: File,
        width: Int,
        height: Int,
        bytes: ByteArray,
        generateFresh: Boolean = false,
        preferOriginal: Boolean = false,
    ): TByteArray? {
        val params: Map<String, Data> = previewParams(width, height, generateFresh, preferOriginal)
        val getDataFactory = OperationImplementationFactory { id ->
            if (id == GetData.ID) {
                object : SyncOperationImplementation {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Data?> execute(
                        executionContext: ExecutionContext,
                        operationContext: OperationContext?,
                        implementationFactory: OperationImplementationFactory,
                        caller: Any?,
                        params: Map<String, Data>?,
                    ): T = TByteArrayDto(bytes) as T
                }
            } else {
                null
            }
        }
        return (impl as SyncOperationImplementation).execute<TByteArray>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = getDataFactory,
            caller = file,
            params = params,
        )
    }

    @Test
    fun videoFileWithLocalBytes_resolvesToStoredPosterNotADecode() {
        // A video whose bytes ARE local (the uploading-device shape) but the JVM has
        // no codec to decode a frame. The guard must short-circuit to the stored
        // poster (synced from a device that could render it) rather than running the
        // image decode — which on the (non-image) video bytes would yield null.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val poster = byteArrayOf(7, 7, 7, 7)
        val file = seedClassesFile("/clip.mp4", list = poster, large = null)

        val preview = invokePreviewWithBytes(impl, file, 100, 100, bytes = byteArrayOf(0, 1, 2, 3))

        assertNotNull(
            preview,
            "A video with local bytes must resolve to its stored poster, not attempt " +
                "an image decode that returns null",
        )
        assertContentEquals(
            poster, preview.get(),
            "The JVM video guard serves the stored poster thumbnail verbatim",
        )
    }

    // ── D-17: the device holding the original may refuse a stretched rung ──

    /** A real 2400 px JPEG, so the render path has something to decode. */
    private fun photoBytes(edge: Int = 2400): ByteArray {
        val image = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until edge step 4) for (x in 0 until edge step 4) {
            val rgb = ((x * 255 / edge) shl 16) or ((y * 255 / edge) shl 8) or 0x40
            for (dy in 0 until 4) for (dx in 0 until 4) image.setRGB(x + dx, y + dy, rgb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    /**
     * ⚠⚠ Without the flag a 1500 px request is answered by the 1024 px hero **stretched** within
     * `COVER_TOLERANCE` — even on the device that holds the picture, where rendering the real thing
     * at 1500 px is available (`drive-xxlarge-thumbnail-rung.md` D-17).
     */
    @Test
    fun preferOriginal_refusesAStretchedRung_onTheDeviceHoldingTheOriginal() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/held.jpg", list = listBytes, large = largeBytes, xlarge = xlargeBytes)
        // ⚠ Through the file database, not the factory argument: `GetData` resolves against the
        // execution context's own implementation (see `invokePreview`'s note).
        testProxy.fileDatabase.writeRawByteArray("/held.jpg", photoBytes())

        val stretched = assertNotNull(invokePreview(impl, file, 1500, 1500))
        assertContentEquals(xlargeBytes, stretched.get(), "precondition: the hero rung stretches to cover 1500 px")

        val rendered = assertNotNull(
            invokePreview(impl, file, 1500, 1500, preferOriginal = true),
            "the byte holder gets a render rather than the stretched rung",
        )
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(rendered.get())), "the answer is a real image")
        assertEquals(1500, maxOf(decoded.width, decoded.height), "rendered at the size asked for, not an inflated one")
    }

    /** ⚠ A rung that **covers** the request is a downscale of the same picture, so it still wins. */
    @Test
    fun preferOriginal_stillTakesACoveringRung() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile(
            "/covered.jpg",
            list = listBytes,
            large = largeBytes,
            xlarge = xlargeBytes,
            xxlarge = xxlargeBytes,
        )

        testProxy.fileDatabase.writeRawByteArray("/covered.jpg", photoBytes())
        val preview = invokePreview(impl, file, 1500, 1500, preferOriginal = true)

        assertContentEquals(
            xxlargeBytes, assertNotNull(preview).get(),
            "reading a whole file to re-render what a stored class already covers is the trade D-2 refuses",
        )
    }

    // ── A generation call never serves a stored class ──
    //
    // ⚠⚠ The three fallbacks above are right for a READ and wrong for a GENERATION call:
    // `ThumbnailClassMinter.renderStill` stores whatever `GENERATE_FRESH` returns under the class it
    // asked for, so a stored poster served here is published under a larger class's name
    // (`GenerateFreshNeverServesStoredClassesTest` has the two routes end to end).

    @Test
    fun videoFileWithLocalBytes_onAGenerationCall_resolvesToNull_notTheStoredPoster() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/clip-fresh.mp4", list = null, large = byteArrayOf(7, 7, 7, 7))

        val preview = invokePreviewWithBytes(impl, file, 1024, 1024, bytes = byteArrayOf(0, 1, 2, 3), generateFresh = true)

        assertNull(preview, "the JVM cannot render a video, so a generation call has nothing to return")
    }

    @Test
    fun byteLessFile_onAGenerationCall_resolvesToNull_notAStoredClass() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/byteless-fresh.jpg", list = listBytes, large = largeBytes, xlarge = xlargeBytes)

        assertNull(
            invokePreview(impl, file, 1024, 1024, generateFresh = true),
            "no bytes means nothing to render from; a stored class is not a render",
        )
    }

    @Test
    fun pdfFileWithUnrenderableBytes_onAGenerationCall_resolvesToNull_notTheStoredThumbnail() {
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/corrupt-fresh.pdf", list = byteArrayOf(9, 9, 9, 9), large = null)
        testProxy.fileDatabase.writeRawByteArray("/corrupt-fresh.pdf", byteArrayOf(0, 1, 2, 3))

        assertNull(
            invokePreview(impl, file, 100, 100, generateFresh = true),
            "a page that failed to render is not answered by the stored class",
        )
    }

    @Test
    fun videoFileWithLocalBytesButNoPoster_resolvesToNull() {
        // Video bytes present, no stored poster (e.g. uploaded on desktop, which can't
        // generate one) → null, so the row shows the video glyph.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/clip2.mp4", list = null, large = null)

        assertNull(
            invokePreviewWithBytes(impl, file, 100, 100, bytes = byteArrayOf(0, 1, 2, 3)),
            "No stored poster for a video → null (video glyph)",
        )
    }

    // ── drive-content-preview.md §4 Phase 2b: PDF first-page thumbnail (JVM) ────────

    /** Synthesise a real single-page PDF of the given point dimensions. */
    private fun pdfBytes(widthPt: Float, heightPt: Float): ByteArray {
        val document = PDDocument()
        document.addPage(PDPage(PDRectangle(widthPt, heightPt)))
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun pdfFileWithLocalBytes_rendersFirstPageThumbnail() {
        // Unlike video, the JVM *can* rasterise a PDF (PDFBox), so a PDF with local
        // bytes and NO stored thumbnail must resolve to a freshly rendered first-page
        // bitmap — not null. A non-null result here can only come from the render
        // path (nothing stored to fall back to). Seed the bytes through the file
        // database so the impl's real `GetData` returns them, the way upload does.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        testProxy.fileDatabase.writeRawByteArray("/doc.pdf", pdfBytes(200f, 300f))
        val file = File.create(testProxy.tContext, "/doc.pdf")

        val preview = invokePreview(impl, file, 256, 256)

        assertNotNull(
            preview,
            "A PDF with local bytes must render a first-page thumbnail, not return null",
        )
        // The rendered bytes must decode as a real image scaled into the request box,
        // preserving the page's 2:3 aspect ratio (200×300 pt → portrait).
        val image = assertNotNull(
            ImageIO.read(ByteArrayInputStream(preview.get())),
            "The PDF preview bytes must decode as an image",
        )
        assertTrue(image.width in 1..256, "preview width was ${image.width}")
        assertTrue(image.height in 1..256, "preview height was ${image.height}")
        assertTrue(image.height > image.width, "a 200×300 pt page stays portrait")
    }

    @Test
    fun pdfFileWithUnrenderableBytes_fallsBackToStoredThumbnail() {
        // Bytes that aren't a valid PDF (truncated / corrupt) must not crash the
        // best-effort render; with a stored class present, the resolver falls back to
        // it exactly like a byte-less file would. Seed both: corrupt local bytes (so
        // `GetData` returns them and the render is attempted) AND a stored thumbnail.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val stored = byteArrayOf(9, 9, 9, 9)
        val file = seedClassesFile("/corrupt.pdf", list = stored, large = null)
        testProxy.fileDatabase.writeRawByteArray("/corrupt.pdf", byteArrayOf(0, 1, 2, 3))

        val preview = invokePreview(impl, file, 100, 100)

        assertNotNull(preview, "A corrupt PDF with a stored thumbnail must fall back, not fail")
        assertContentEquals(
            stored, preview.get(),
            "An unrenderable PDF falls back to the stored thumbnail verbatim",
        )
    }

    @Test
    fun heicFileWithLocalBytes_rendersThumbnailThroughTheImageIoBridge() {
        // `javax.imageio.ImageIO.read` has no HEIF plugin, so a HEIC — the iOS camera
        // default — used to decode to null here and the file got NO thumbnail at all
        // (a HEIC uploaded from a Mac; iPhone uploads were fine because iOS transcodes
        // its own preview before syncing). The ImageIO-bridge fallback in `readImage`
        // must produce a real thumbnail instead. Same shape as the PDF test: local
        // bytes, nothing stored, so a non-null result can only come from a decode.
        if (!MacImageDecoder.isAvailable()) return // non-macOS build host
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        testProxy.fileDatabase.writeRawByteArray("/photo.heic", heicBytes)
        val file = File.create(testProxy.tContext, "/photo.heic")

        val preview = invokePreview(impl, file, 256, 256)

        assertNotNull(
            preview,
            "A HEIC with local bytes must decode via the ImageIO bridge, not return null",
        )
        val image = assertNotNull(
            ImageIO.read(ByteArrayInputStream(preview.get())),
            "The HEIC preview bytes must decode as an image",
        )
        // The 16x16 source already fits the 256x256 request box, so it passes through
        // at its own size — proof the decode really produced the source image.
        assertTrue(image.width == 16, "preview width was ${image.width}")
        assertTrue(image.height == 16, "preview height was ${image.height}")
    }

    @Test
    fun undecodableImageBytesStillResolveToNull() {
        // The bridge must not change the *failure* shape. Bytes no decoder can read
        // (not ImageIO, and on a Mac not the helper either) still resolve to null on
        // the generate path — the helper must neither throw out of `readImage` nor
        // hang waiting on a process that will never produce output.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        testProxy.fileDatabase.writeRawByteArray("/corrupt.png", byteArrayOf(0, 1, 2, 3))
        val file = File.create(testProxy.tContext, "/corrupt.png")

        assertNull(
            invokePreview(impl, file, 256, 256),
            "Undecodable image bytes resolve to null, exactly as before the bridge",
        )
    }

    /** A real 16x16 HEIC (`sips -s format heic`) — no JVM library can encode one. */
    private val heicBytes: ByteArray = Base64.decode(
    "AAAAJGZ0eXBoZWljAAAAAG1pZjFNaVBybWlhZk1pSEJoZWljAAABw21ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAHBp" +
    "Y3QAAAAAAAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAADnBpdG0AAAAAAAEA" +
    "AAA4aWluZgAAAAAAAgAAABVpbmZlAgAAAAABAABodmMxAAAAABVpbmZlAgAAAQACAABFeGlmAAAAABppcmVmAAAA" +
    "AAAAAA5jZHNjAAIAAQABAAAA5mlwcnAAAADFaXBjbwAAABNjb2xybmNseAACAAIABoAAAAAMY2xsaQDLAEAAAAAU" +
    "aXNwZQAAAAAAAAAQAAAAEAAAAAlpcm90AAAAABBwaXhpAAAAAAMICAgAAABxaHZjQwEDcAAAALAAAAAAAB7wAPz9" +
    "+PgAAAsDoAABABdAAQwB//8DcAAAAwCwAAADAAADAB5wJKEAAQAjQgEBA3AAAAMAsAAAAwAAAwAeoBQgQcCTDOIe" +
    "5FlU3AgIGAKiAAEACUQBwGFyyERTZAAAABlpcG1hAAAAAAAAAAEAAQaBAgMFhoQAAAAsaWxvYwAAAABEAAACAAEA" +
    "AAABAAACdQAAAOcAAgAAAAEAAAH3AAAAfgAAAAFtZGF0AAAAAAAAAXUAAAAGRXhpZgAATU0AKgAAAAgABQEGAAMA" +
    "AAABAAIAAAESAAMAAAABAAEAAAFCAAQAAAABAAACAAFDAAQAAAABAAACAIdpAAQAAAABAAAASgAAAAAAA6ABAAMA" +
    "AAABAAEAAKACAAQAAAABAAAAEKADAAQAAAABAAAAEAAAAAAAAADjKAGvoRYwGmG1UxXrw1rVoP4nlBgCHfR7QtW0" +
    "hF7TaMEsi2gXHEqr5SxnpS2CMKmPwWfTusglkmZQC4/h96+LY055nnnsEcf+HbYofT87VRG+tXit//f2aA1H8d9J" +
    "P2wTasEiUTfGLPEzDA6lzhw5Pouszy/644Byht2TX9dF9MI9V9MjBoIPbBF92tLRi5Ux391Hiq1PKxTArAwia7iC" +
    "J8yeiA7bznY9f+kH//5A3OdiZr9k++X+gDAZHBcWlIOahK58mmSbr2j6CjKxLOu7VU2ZoZVSFR436LP4w+dFfenV" +
    "5/g="
    )

    // ---- §12 T-1: the XLARGE (hero) class, and the tolerance that makes it usable ----

    private val listBytes = byteArrayOf(1, 1, 1)
    private val largeBytes = byteArrayOf(2, 2, 2)
    private val xlargeBytes = byteArrayOf(3, 3, 3)
    private val xxlargeBytes = byteArrayOf(4, 4, 4)

    @Test
    fun byteLessFile_atHeroSize_resolvesToXLarge_notLarge() {
        // The defect §12 exists for: before this class the largest stored answer was 256 px, so a
        // hero on a device that did not upload the photo was a 3-5x upscale of it.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/hero.jpg", list = listBytes, large = largeBytes, xlarge = xlargeBytes)

        val preview = invokePreview(impl, file, 1024, 1024)

        assertContentEquals(
            xlargeBytes, assertNotNull(preview).get(),
            "a hero-sized request must be served by XLARGE, not by the 256 px class",
        )
    }

    @Test
    fun byteLessFile_atHeroSize_withoutXLarge_stillFallsBackToLarge() {
        // Every photo uploaded before T-1 has no XLARGE and never will (nothing regenerates
        // thumbnails). Those must keep working exactly as before, not regress to null.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/legacy.jpg", list = listBytes, large = largeBytes)

        val preview = invokePreview(impl, file, 1024, 1024)

        assertContentEquals(
            largeBytes, assertNotNull(preview).get(),
            "a pre-T-1 file must still resolve to its best available class",
        )
    }

    @Test
    fun withLocalBytes_aCoveredRequest_servesTheStoredClassInsteadOfReadingTheWholeFile() {
        // §12.5 D-2. ⚠⚠ Two things are load-bearing and were wrong in the first draft of this test:
        // the caller must be an **Entity** (a bare `File` is the upload-time shape and skips every
        // stored-class short-circuit by design), and the bytes must be written to the file database
        // under the file's own path — a stubbed `GetData` factory passed to `execute` is never
        // consulted, because the impl resolves it through the execution context. Without both, this
        // test passes while proving nothing.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val entity = seedClassesEntity("/covered.jpg", xlarge = xlargeBytes)
        testProxy.fileDatabase.writeRawByteArray("/covered.jpg", realJpegBytes())

        val preview = invokePreviewOn(impl, entity, 1024, 1024)

        assertContentEquals(
            xlargeBytes, assertNotNull(preview).get(),
            "a request the stored class covers must be served from it, not by reading the whole file",
        )
    }

    @Test
    fun withLocalBytes_aRequestBeyondTolerance_computesFromTheBytes() {
        // The other side of the rule — and the test that could not be written until `GetData`
        // learned to read an `Entity`'s path. Past COVER_TOLERANCE the class really is too small,
        // so the resolver must go back to the bytes; asserted as "not the stored blob", because
        // what matters is which branch ran.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val entity = seedClassesEntity("/beyond.jpg", xlarge = xlargeBytes)
        testProxy.fileDatabase.writeRawByteArray("/beyond.jpg", realJpegBytes())

        val preview = invokePreviewOn(impl, entity, 4096, 4096)

        val bytes = assertNotNull(preview).get()
        assertTrue(
            !bytes.contentEquals(xlargeBytes),
            "beyond the tolerance the resolver must compute from the file's bytes, not stretch a " +
                "class — got ${bytes.size} bytes",
        )
    }

    @Test
    fun byteLessFile_atTileSize_resolvesToLarge_notXLarge() {
        // ⚠⚠ The other edge of D-2's tolerance, and the reason it needed one. The rule bought off
        // a whole-file read; it did not license answering every request above the LIST rung with
        // the hero class. A grid tile asks for 256 px, which LARGE covers exactly - serving XLARGE
        // there is ~139 KB in place of ~15 KB for a difference the tile cannot render, and enough
        // of it evicts a feed's preview cache out from under itself.
        //
        // ⚠ This was latent rather than harmless: it could not bite while nothing pulled XLARGE
        // at all, which was itself the bug this commit fixes. Fixing the pull without fixing this
        // would have traded a blurry viewer for a 9x heavier feed.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val file = seedClassesFile("/tile.jpg", list = listBytes, large = largeBytes, xlarge = xlargeBytes)

        val preview = invokePreview(impl, file, 256, 256)

        assertContentEquals(
            largeBytes, assertNotNull(preview).get(),
            "a tile-sized request must be served by the class that covers it, not by the hero class",
        )
    }

    @Test
    fun withLocalBytes_atTileSize_servesTheLargeClass_notTheHeroClass() {
        // The same rule on the path where `coveringStoredThumbnail` is the only thing that can
        // return a stored class at all - with bytes on the device, anything it declines to serve is
        // computed fresh. So this pins the branch itself rather than the byte-less ladder below it.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val entity = seedClassesEntity("/tile-local.jpg", xlarge = xlargeBytes, large = largeBytes)
        testProxy.fileDatabase.writeRawByteArray("/tile-local.jpg", realJpegBytes())

        val preview = invokePreviewOn(impl, entity, 256, 256)

        assertContentEquals(
            largeBytes, assertNotNull(preview).get(),
            "a covered tile-sized request must be served by LARGE, not by the hero class",
        )
    }

    @Test
    fun withLocalBytes_atTileSize_withoutLarge_stillFallsBackToTheHeroClass() {
        // ⚠ The `?:` in that first branch is not tidiness. A class that is too big still beats
        // reading and decrypting the entire original, which is the one cost the whole function
        // exists to avoid - so a missing LARGE must fall to XLARGE rather than to the bytes.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val entity = seedClassesEntity("/tile-no-large.jpg", xlarge = xlargeBytes)
        testProxy.fileDatabase.writeRawByteArray("/tile-no-large.jpg", realJpegBytes())

        val preview = invokePreviewOn(impl, entity, 256, 256)

        assertContentEquals(
            xlargeBytes, assertNotNull(preview).get(),
            "with no LARGE stored, a tile-sized request must still be served from a class rather " +
                "than by reading the whole file",
        )
    }

    @Test
    fun anEntityCallerWithLocalBytesCanComputeAPreviewAtAll() {
        // ⚠⚠ The regression this pins is bigger than the class ladder. `GetData` used to read a
        // caller's path with `getOptionalField(File.Field.PATH)` — but that field is *mandatory*,
        // and `getOptionalField` refuses mandatory fields, so it answered null and the op failed
        // with "Field tenshu/file/path not found in caller" for EVERY `Entity` caller. An `Entity`
        // is exactly the shape a `File.Preview` projection produces, so the whole
        // compute-from-local-bytes branch was unreachable through a projection: a device holding a
        // file's bytes still could not render a sharp preview from them.
        val impl = JvmGetPreviewImplementation(NoOpPreviewCache)
        val entity = seedClassesEntity("/computable.jpg", xlarge = xlargeBytes)
        testProxy.fileDatabase.writeRawByteArray("/computable.jpg", realJpegBytes())

        val preview = invokePreviewOn(impl, entity, 4096, 4096)

        assertNotNull(preview, "an Entity caller holding local bytes must be able to compute")
    }

    /**
     * A `File` entity carrying an `XLARGE` blob, **read back out of the store** so it is shaped
     * like the entity a real projection hands the resolver.
     *
     * ⚠ Read back, not the freshly-built one: a hand-constructed `Entity` carries no `path` field,
     * so `GetData` cannot address it and the compute path dies with "Field tenshu/file/path not
     * found in caller". Production entities come from the database with the composite's fields, so
     * a test that skips the round-trip is testing a shape that never occurs.
     */
    private fun seedClassesEntity(path: String, xlarge: ByteArray, large: ByteArray? = null): Any {
        val contentId = "content-" + path.trim('/').replace('/', '-')
        val entity = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(testProxy.tContext, path),
        ) {
            set(File.ContentId, contentId)
        }
        testProxy.executionContext.saveData(entity)
        testProxy.fileDatabase.writeRawByteArray(
            FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE), xlarge
        )
        if (large != null) {
            testProxy.fileDatabase.writeRawByteArray(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_LARGE), large
            )
        }
        val dataId = requireNotNull(testProxy.executionContext.getDataId(entity)) { "no data id" }
        return testProxy.executionContext.database.getData(dataId, asEntity = true, projection = null)
            ?: error("seeded entity did not read back")
    }

    private fun invokePreviewOn(
        impl: JvmGetPreviewImplementation,
        caller: Any,
        width: Int,
        height: Int,
    ): TByteArray? = (impl as SyncOperationImplementation).execute<TByteArray>(
        executionContext = testProxy.executionContext,
        operationContext = null,
        implementationFactory = OperationImplementationFactory { _ -> null },
        caller = caller,
        params = mapOf(
            GetPreview.Params.WIDTH to width.toLong().toTInteger(testProxy.tContext),
            GetPreview.Params.HEIGHT to height.toLong().toTInteger(testProxy.tContext),
        ),
    )

    /** A real, decodable JPEG — the covered-request test must fail for the right reason. */
    private fun realJpegBytes(): ByteArray {
        val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }
}
