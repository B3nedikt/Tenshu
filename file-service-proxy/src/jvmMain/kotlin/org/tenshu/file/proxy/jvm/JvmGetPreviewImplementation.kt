package org.tenshu.file.proxy.jvm

import org.tenshu.file.proxy.StoredThumbnailLadder
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.sha256
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.readMediaMetadata
import org.tenshu.file.proxy.ExifUpright
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.path
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.StaticPropertyRelation
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage

internal class JvmGetPreviewImplementation(
    private val previewCache: PreviewCache = NoOpPreviewCache,
    /** The full-size decode; a seam so P1a's decode-count test can see how many this path does. */
    private val decoder: JvmImageDecoder = JvmImageDecoder.Default,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val width: TInteger = params.read(GetPreview.Params.WIDTH)
        val height: TInteger = params.read(GetPreview.Params.HEIGHT)
        // FORMAT is optional (drive-synced-thumbnails.md Q1) — absent ⇒ PNG up to the hero rung's
        // edge and JPEG above it (§17.6), so a viewer asking for a retina-sized render is not
        // shipped megabytes of PNG. `read` would NPE on absence, so look it up null-safely.
        val format: String =
            (params?.get(GetPreview.Params.FORMAT) as? TString)?.get()
                ?: GetPreview.Params.defaultFormatFor(maxOf(width.get(), height.get()).toInt())

        val file = caller as? Composite
            ?: error("Caller must be a Composite")

        // Absent ⇒ read semantics. Looked up null-safely for the same reason FORMAT is:
        // `params.read` NPEs on an absent optional.
        val generateFresh: Boolean =
            (params?.get(GetPreview.Params.GENERATE_FRESH) as? TBoolean)?.get() ?: false
        // D-17: this caller holds the original and would rather have it rendered than a rung stretched
        // to it (`drive-xxlarge-thumbnail-rung.md` §4.2). A *covering* rung still wins.
        val preferOriginal: Boolean =
            (params?.get(GetPreview.Params.PREFER_ORIGINAL) as? TBoolean)?.get() ?: false

        // Absent ⇒ ImageIO's own default. Null-safe for the same reason again.
        val quality: Int? = (params?.get(GetPreview.Params.QUALITY) as? TInteger)?.get()?.toInt()

        return getPreview(
            executionContext = executionContext,
            file = file,
            generateFresh = generateFresh,
            preferOriginal = preferOriginal,
            width = width.get().toInt(),
            height = height.get().toInt(),
            format = format,
            quality = quality,
        ) as T
    }

    private fun getPreview(
        executionContext: ExecutionContext,
        file: Composite,
        generateFresh: Boolean,
        preferOriginal: Boolean,
        width: Int,
        height: Int,
        format: String,
        quality: Int?,
    ): TByteArray? {
        // The caller is a plain `File` composite at upload (File.create) and an
        // `Entity<File>` on a File.Preview read — read the path from whichever shape
        // applies (mirrors AndroidGetPreviewImplementation). A bare `File` previously
        // fell through to "" here, which silently disabled the video/PDF branches at
        // upload (the path-derived category was always Other).
        val fileName = when (file) {
            is Entity<*> -> @Suppress("UNCHECKED_CAST") (file as Entity<out File>).path.get()
            is File -> file.path.get()
            else -> ""
        }.substringAfterLast('/')
        val typeId = if (file is Entity<*>) file.type.get() else file.id
        if (typeId == Folder.ID) {
            return null
        }

        // A LIST-class read (≤ 100 px — a Files-row render) on a stored entity is
        // served straight from the eager-synced stored thumbnail when one exists:
        // the compute path below reads + decrypts the ENTIRE file via `GetData`
        // and sha256s the bytes just to build the preview-cache key — ~15 ms/row,
        // which made a 60-row Files page cost ~900 ms proxy-side (the 2k-folder
        // slowness). ⚠⚠ This branch was previously gated on `file is Entity<*>`, which is
        // NEVER true — `ReferenceResolver` unwraps entities before invoking an operation — so
        // the optimisation described above has never actually run. It is gated on the explicit
        // `generateFresh` param now, which is what the guard always meant; larger requests (details
        // view) keep computing sharp previews from local bytes. Mirrors
        // AndroidGetPreviewImplementation.
        val requestedMaxEdge = maxOf(width, height)

        // §12.5 D-2: a stored class that covers this request to within `GetStoredThumbnail.COVER_TOLERANCE` beats
        // reading the whole file — which below means decrypting every byte and sha256-ing it just
        // to build a cache key, and on a byte-less device means the caller fetching the original
        // first. ⚠ `Entity` callers only: the upload-time generation path (a bare `File` via
        // `renderThumbnail`) must always compute fresh, or a class could be minted from an older
        // class and the ladder would quietly re-encode itself downhill.
        if (!generateFresh && requestedMaxEdge > LIST_THUMBNAIL_PX) {
            coveringStoredThumbnail(executionContext, file, requestedMaxEdge, allowStretch = !preferOriginal)?.let { return it }
        }

        if (!generateFresh && requestedMaxEdge <= LIST_THUMBNAIL_PX) {
            storedThumbnail(executionContext, file, requestedMaxEdge)?.let { return it }
            // No stored class and no compute path that could succeed (not an
            // image/PDF — the video branch below only serves stored posters,
            // which we just missed): skip the full-byte read, show the icon.
            if (!isPdf(fileName) && fileCategoryForName(fileName) != FileCategory.Image) {
                return null
            }
        }

        // Desktop/JVM has no built-in video frame decoder (FFmpeg is out of scope —
        // drive-content-preview.md §5), so it can't generate a poster. For a video,
        // serve any stored poster thumbnail synced from a device that can (Android)
        // instead of attempting an image decode that would just fail to null — on a read.
        // A generation call gets null: this device has nothing to render (`storedFallback`).
        if (fileCategoryForName(fileName) == FileCategory.Video) {
            return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))
        }

        // `GetData` returns null when this file's bytes aren't on the device yet
        // (`File.Data` is `Syncable(OnDemand)` — synced metadata, no bytes until
        // opened). No bytes → we can't *compute* a preview, so a read falls back to the
        // smallest stored thumbnail class that satisfies the request (LIST/LARGE).
        // This makes `File.Preview` the single "best available preview" API — the
        // byte-less / stored-thumbnail-class selection lives here in the resolver,
        // not in the consumer (drive-synced-thumbnails.md §10.1 / §10.2).
        val bytes = executionContext.execute<TByteArray?>(
            operationId = GetData.ID,
            caller = file
        )?.get()

        if (bytes == null || bytes.isEmpty()) {
            return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))
        }

        // Cache lookup keyed by content + requested dimensions (+ format and quality, since the
        // encoded bytes differ by both). Hit → cheap byte read, no
        // decode/scale/encode work; miss → fall through to the generate path below,
        // persist on success. See `PreviewCache` KDoc + tech-debt entry "File
        // Previews Are Regenerated From the Full File on Every Access".
        // ⚠ The orientation is part of the key: renders are upright now (`ExifUpright`), and the
        // unturned ones cached before 2026-11-01 would otherwise be served for good.
        val orientation = if (isPdf(fileName)) null else readMediaMetadata(bytes).orientation
        val contentHash = cacheKey(sha256(bytes).toHexString(), format, quality, orientation)
        previewCache.get(contentHash, width, height)?.let { cached ->
            println("JVM Proxy -> Preview cache hit for $fileName (${width}x${height}, $format)")
            return TByteArrayDto(cached)
        }
        println("JVM Proxy -> Preview cache miss, generating preview for $fileName (${width}x${height}, $format)")

        return try {
            // A PDF's "preview" is its first page rasterised to a bitmap
            // (drive-content-preview.md §4 Phase 2b); everything downstream (scale +
            // encode) is identical to an image. If the render fails (corrupt /
            // password-protected) a read falls back to any stored thumbnail, same as a
            // byte-less file, rather than failing the best-effort thumbnail render.
            val originalImage = if (isPdf(fileName)) {
                renderPdfFirstPage(bytes, maxEdge = maxOf(width, height))
                    ?: return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))
            } else {
                decoder.decode(bytes) ?: return null
            }

            val (outWidth, outHeight) = JvmThumbnailRaster.fitWithin(
                originalImage.width, originalImage.height, width, height,
            ) ?: return null
            val resizedImage = JvmThumbnailRaster.scale(originalImage, outWidth, outHeight)

            // `resizedImage` is TYPE_INT_RGB (no alpha), which JPEG requires — so a
            // JPEG encode is safe here without a colour-model conversion. JPEG keeps
            // a 256 px photo well under the LARGE cap where PNG would not (Q1).
            val previewBytes = JvmThumbnailRaster.encode(resizedImage, format, quality) ?: return null

            // Best-effort persist. `put` returns false (size cap, IO) silently —
            // the caller still serves the generated bytes; only future warm
            // reads lose the cache benefit.
            previewCache.put(contentHash, width, height, previewBytes)

            TByteArrayDto(previewBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The stored class to show when this device cannot render [file] itself: no bytes here, no
     * decoder for it, or a render that failed.
     *
     * ⚠⚠ **Never on a generation call** — [generateFresh] answers `null`. `ThumbnailClassMinter.renderStill`
     * stores whatever a generation call returns under the class it asked for, so a stored class
     * served here is re-published under that name: a desktop's backfill stored a video's 256 px
     * `LARGE` poster as its `XLARGE`, and a replace that could not render re-stored the previous
     * content's classes as the new content's (`GenerateFreshNeverServesStoredClassesTest`).
     */
    private fun storedFallback(
        executionContext: ExecutionContext,
        file: Composite,
        generateFresh: Boolean,
        requestedMaxEdge: Int,
    ): TByteArray? = if (generateFresh) null else storedThumbnail(executionContext, file, requestedMaxEdge)

    /**
     * Resolve the best stored thumbnail **class** for this byte-less file at the
     * given [requestedMaxEdge] (the larger of the requested width/height). Picks the
     * smallest class that covers the request, falling back to any available class
     * (drive-synced-thumbnails.md §10.2):
     *
     *  - request larger than the `LIST` size (100 px) **and** a `LARGE` is stored
     *    → return `LARGE` (sharp; an upscaled `LIST` would be blurry);
     *  - else if `LIST` is stored → `LIST`;
     *  - else if `LARGE` is stored → `LARGE`;
     *  - else `null` (→ category icon — non-image / pre-feature file).
     *
     * Stored value properties are **not** inline on the entity composite the caller
     * hands us (they materialise via `select`/`relations`, not `entity.get` —
     * drive-synced-thumbnails.md §9), so we resolve each via
     * [ExecutionContext.resolveProperty], the same read path `SelectImplementation`
     * drives for a projected `relations(...)`.
     */
    private fun storedThumbnail(
        executionContext: ExecutionContext,
        file: Composite,
        requestedMaxEdge: Int,
    ): TByteArray? = StoredThumbnailLadder.best(requestedMaxEdge) { property ->
        resolveStoredClass(executionContext, file, property)
    }

    /**
     * The stored class to serve **instead of reading the whole file**, or null if none is good
     * enough (`drive-synced-thumbnails.md` §12.5 D-2).
     *
     * ⚠⚠ Without this rule the class below is useless at exactly the size it exists for. The
     * ordinary rule is *smallest class that **satisfies** the request*, and `XLARGE` is deliberately
     * 1024 px while a retina desktop hero asks for ~1680 — so the resolver would step straight past
     * it to the compute-from-bytes path, which on a byte-less device means the client fetching the
     * entire original. The trade is therefore stated rather than implied: **never read a whole file
     * to satisfy a request a stored class covers to within [GetStoredThumbnail.COVER_TOLERANCE].**
     *
     * ⚠ A class capped at N px is smaller than N only when the *source* was — in which case the
     * original could not have done better either. So the only thing this rule can cost is one
     * JPEG re-encode's worth of quality, never resolution.
     *
     * ⚠⚠ The tolerance buys off a whole-file read; it does not license serving the hero class to
     * everyone. A 256 px tile served `XLARGE` costs ~139 KB against `LARGE`'s ~15 KB for a
     * difference it cannot render — so the smallest covering class wins, and only the band `LARGE`
     * cannot cover reaches the tolerance.
     */
    private fun coveringStoredThumbnail(
        executionContext: ExecutionContext,
        file: Composite,
        requestedMaxEdge: Int,
        allowStretch: Boolean,
    ): TByteArray? = StoredThumbnailLadder.covering(requestedMaxEdge, allowStretch = allowStretch) { property ->
        resolveStoredClass(executionContext, file, property)
    }

    /**
     * Read one stored thumbnail class for [file] — **whatever is local**, never a fetch.
     * `null` means "not on this device", which callers already treat as "no thumbnail".
     * A proxy-side resolver cannot fetch an OnDemand property; see [SyncStrategy.OnDemand].
     */
    private fun resolveStoredClass(
        executionContext: ExecutionContext,
        file: Composite,
        property: org.tenshu.core.relation.Property.Dynamic<TByteArray>,
    ): TByteArray? {
        // ⚠⚠ Never throws. Resolving a class reaches `GetStoredThumbnail`, which reads the file's
        // `contentId` through the property resolver — and that raises "Data of type tenshu/file …
        // not found" for a caller with no entity row, rather than answering null. `File.Preview`'s
        // whole contract is *best available*: a missing stored class must degrade to the next rung,
        // never fail the preview. This surfaced the moment the short-circuits above stopped being
        // dead code — they had been gated on a condition that was never true, so nothing had ever
        // resolved a class on this path before.
        return runCatching {
            val relation = property.selector() as org.tenshu.core.relation.DynamicPropertyRelation
            val resolved = executionContext.resolveDynamicProperty(file, relation.reference, relation.params)
            property.decode(resolved)
        }.getOrNull()
    }

    /**
     * Rasterise the **first page** of a PDF [bytes] to a [BufferedImage] via PDFBox,
     * for the thumbnail render. Renders at a width of at least [maxEdge] (so the
     * downstream downscale stays sharp) and at most [MAX_PDF_RENDER_PX] (to bound
     * memory), defaulting toward the page's natural point width. `ImageType.RGB`
     * paints a white background, which is what a PDF page expects. Returns `null` on
     * any failure (corrupt, encrypted, empty) — the caller then falls back to a
     * stored thumbnail. Best-effort: it must never throw out of here.
     */
    private fun renderPdfFirstPage(bytes: ByteArray, maxEdge: Int): BufferedImage? = try {
        Loader.loadPDF(bytes).use { document ->
            if (document.numberOfPages < 1) {
                null
            } else {
                val pageWidthPt = document.getPage(0).cropBox.width
                if (pageWidthPt <= 0f) {
                    null
                } else {
                    val lo = maxEdge.coerceIn(1, MAX_PDF_RENDER_PX)
                    val renderWidthPx = pageWidthPt.toInt().coerceIn(lo, MAX_PDF_RENDER_PX)
                    val scale = renderWidthPx / pageWidthPt
                    PDFRenderer(document).renderImage(0, scale, ImageType.RGB)
                }
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** ⚠ A turned render carries `:upright` — see the call site. An unturned picture keeps its key. */
    private fun cacheKey(contentHash: String, format: String, quality: Int?, orientation: Int?): String {
        val key = when {
            format == GetPreview.Params.FORMAT_PNG -> contentHash
            quality == null -> "$contentHash:$format"
            else -> "$contentHash:$format:q$quality"
        }
        return if (ExifUpright.isIdentity(orientation)) key else "$key:upright"
    }

    private companion object {
        /** Longest edge of the `LIST` thumbnail class — the smallest-class boundary. */
        const val LIST_THUMBNAIL_PX = GetStoredThumbnail.LIST_PX

        /** Upper bound on the PDF first-page raster width (px), to cap memory. */
        const val MAX_PDF_RENDER_PX = 2048
    }
}
