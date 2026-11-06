package org.tenshu.core.common.proxy.file

import android.graphics.Bitmap
import org.tenshu.file.proxy.StoredThumbnailLadder
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.scale
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.client.InMemoryByteArrayDto
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
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.readMediaMetadata
import org.tenshu.file.proxy.ExifUpright
import org.tenshu.core.file.path
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.file.proxy.motion.MotionClipPlan
import java.io.ByteArrayOutputStream

internal class AndroidGetPreviewImplementation(
    private val previewCache: PreviewCache = NoOpPreviewCache
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
        // FORMAT is optional (drive-synced-thumbnails.md Q1) — absent ⇒ PNG, so the
        // 2-param File.Preview callers are unaffected. `read` would NPE on absence,
        // so look it up null-safely.
        val format: String =
            (params?.get(GetPreview.Params.FORMAT) as? TString)?.get()
                ?: GetPreview.Params.FORMAT_PNG

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

        // Absent ⇒ the per-format default below. Null-safe for the same reason again.
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

        val typeId = if (file is Entity<*>) file.type.get() else file.id
        if (typeId == Folder.ID) {
            return null
        }

        // Detect video by filename so the decode step can extract a poster frame
        // instead of attempting a (futile) image decode. The caller is a plain
        // `File` composite at upload (File.create) and an `Entity<File>` on a
        // File.Preview read — read the path from whichever shape applies.
        val fileName = when (file) {
            is Entity<*> -> @Suppress("UNCHECKED_CAST") (file as Entity<out File>).path.get()
            is File -> file.path.get()
            else -> ""
        }.substringAfterLast('/')
        val isVideo = fileCategoryForName(fileName) == FileCategory.Video
        val isPdf = isPdf(fileName)

        // A LIST-class read (≤ 100 px — a Files-row render) on a stored entity is
        // served straight from the eager-synced stored thumbnail when one exists:
        // the compute path below reads + decrypts the ENTIRE file via `GetData`
        // and sha256s the bytes just to build the preview-cache key — ~15 ms/row,
        // which made a 60-row Files page cost ~900 ms proxy-side (the 2k-folder
        // slowness). ⚠⚠ This branch was previously gated on `file is Entity<*>`, which is
        // NEVER true — `ReferenceResolver` unwraps entities before invoking an operation — so
        // the optimisation described above has never actually run. It is gated on the explicit
        // `generateFresh` param now, which is what the guard always meant; larger requests (details
        // view) keep computing sharp previews from local bytes.
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
            // image/video/PDF): skip the full-byte read, show the category icon.
            if (!isVideo && !isPdf && fileCategoryForName(fileName) != FileCategory.Image) {
                return null
            }
        }

        // `GetData` returns null when this file's bytes aren't on the device yet
        // — `File.Data` is `Syncable(OnDemand)`, so a file synced from another
        // device has metadata but no bytes until it's opened. A preview can't be
        // *computed* without bytes, so a read falls back to the smallest stored thumbnail
        // class that satisfies the request (LIST/LARGE). This makes `File.Preview`
        // the single "best available preview" API: the stored-thumbnail-class
        // selection lives here in the resolver, not in the consumer
        // (drive-synced-thumbnails.md §10.1 / §10.2). `null` (→ category icon) when
        // neither bytes nor a stored thumbnail are present (non-image / pre-feature
        // file).
        val bytes = executionContext.execute<TByteArray?>(
            operationId = GetData.ID,
            caller = file
        )?.get()

        if (bytes == null || bytes.isEmpty()) {
            return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))
        }

        // Cache lookup keyed by content + dimensions (+ format and quality, since encoded bytes
        // differ by both). On hit, skip the decode + scale + encode work and
        // return the stored bytes — this is the Binder-watchdog cure (see
        // `PreviewCache` KDoc + tech-debt entry "File Previews Are
        // Regenerated From the Full File on Every Access").
        // ⚠⚠ Rungs and previews are stored UPRIGHT (`ExifUpright`), and `BitmapFactory` ignores EXIF
        // — for HEIC too — so the turn is ours to make. Before the cache read, because the key
        // depends on it.
        val orientation = if (!isVideo && !isPdf) readMediaMetadata(bytes).orientation else null
        val contentHash = cacheKey(sha256(bytes).toHexString(), format, quality, orientation)
        previewCache.get(contentHash, width, height)?.let { cached ->
            return InMemoryByteArrayDto(cached)
        }

        // A video's "preview" is a poster frame (drive-content-preview.md §3 Phase 3):
        // pull a representative frame out of the container, then run it through the
        // exact same scale + encode path as an image. If extraction fails (corrupt /
        // unsupported codec) a read falls back to any stored thumbnail, same as a byte-less
        // file, rather than crashing the best-effort thumbnail render (never a generation
        // call — `storedFallback`).
        // A PDF's "preview" is its first page rasterised to a bitmap
        // (drive-content-preview.md §4 Phase 2b); everything downstream (scale +
        // encode) is identical to an image. As with the video poster, a render
        // failure (corrupt / encrypted) falls back to a stored thumbnail rather
        // than crashing the best-effort thumbnail render.
        val originalBitmap = when {
            isVideo -> extractVideoPoster(bytes)
                ?: return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))

            isPdf -> renderPdfFirstPage(bytes, maxEdge = maxOf(width, height))
                ?: return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))

            else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Could not decode bitmap")
        }

        // The box is fitted to the picture as SHOWN; the decoded bitmap is scaled in its stored
        // orientation and the result turned, so a rung's turn costs the rung's size, not the frame's.
        val swapsAxes = ExifUpright.swapsAxes(orientation)
        val outWidth: Int
        val outHeight: Int
        val originalWidth = if (swapsAxes) originalBitmap.height else originalBitmap.width
        val originalHeight = if (swapsAxes) originalBitmap.width else originalBitmap.height

        if (originalWidth <= width && originalHeight <= height) {
            outWidth = originalWidth
            outHeight = originalHeight
        } else {
            val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
            if (width / aspectRatio <= height) {
                outWidth = width
                outHeight = (width / aspectRatio).toInt()
            } else {
                outHeight = height
                outWidth = (height * aspectRatio).toInt()
            }
        }

        if (outWidth <= 0 || outHeight <= 0) {
            originalBitmap.recycle()
            error("Invalid dimensions")
        }

        val storedOutWidth = if (swapsAxes) outHeight else outWidth
        val storedOutHeight = if (swapsAxes) outWidth else outHeight
        val scaledBitmap = if (storedOutWidth != originalBitmap.width || storedOutHeight != originalBitmap.height) {
            val newBitmap = originalBitmap.scale(storedOutWidth, storedOutHeight)
            originalBitmap.recycle()
            newBitmap
        } else {
            originalBitmap
        }.upright(orientation)

        val outputStream = ByteArrayOutputStream()
        // The LARGE thumbnail render asks for JPEG (Q1 — smaller for photos, fits the
        // LARGE cap). Every other caller keeps the existing WEBP default (the
        // compute-from-bytes preview path is unchanged).
        val compressFormat = if (format == GetPreview.Params.FORMAT_JPEG) {
            Bitmap.CompressFormat.JPEG
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        // ⚠ A caller's quality applies to JPEG only — the mint's step-down (`GetPreview.Params.QUALITY`).
        val encodeQuality = if (format == GetPreview.Params.FORMAT_JPEG) quality?.coerceIn(1, 100) ?: 80 else 85
        scaledBitmap.compress(compressFormat, encodeQuality, outputStream)
        scaledBitmap.recycle()

        val previewBytes = outputStream.toByteArray()

        // Best-effort persist; failure (cap or IO) is silent — caller still
        // serves the generated bytes for this call.
        previewCache.put(contentHash, width, height, previewBytes)

        return InMemoryByteArrayDto(previewBytes)
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
     * ⚠ A turned render carries `:upright` so it never shares an entry with the unturned ones this
     * cache held before 2026-11-01 — those would otherwise be served for good. An unturned picture
     * keeps its old key, and its cache.
     */
    private fun cacheKey(contentHash: String, format: String, quality: Int?, orientation: Int?): String {
        val key = when {
            format == GetPreview.Params.FORMAT_PNG -> contentHash
            quality == null -> "$contentHash:$format"
            else -> "$contentHash:$format:q$quality"
        }
        return if (ExifUpright.isIdentity(orientation)) key else "$key:upright"
    }

    /**
     * This bitmap turned upright for [orientation] (`ExifUpright`); itself when nothing changes.
     * ⚠ A `Matrix` copy, not a pixel loop: it keeps the bitmap's colour space — every iPhone photo
     * is Display P3, and `getPixels` would hand back sRGB.
     */
    private fun Bitmap.upright(orientation: Int?): Bitmap {
        val turn = ExifUpright.turnFor(orientation) ?: return this
        val matrix = Matrix().apply {
            if (turn.mirror) postScale(-1f, 1f)
            postRotate(turn.clockwiseDegrees.toFloat())
        }
        val result = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (result !== this) recycle()
        return result
    }

    /**
     * Extracts a poster-frame [Bitmap] from in-memory video [bytes] via
     * [MediaMetadataRetriever] over an [InMemoryMediaDataSource] (no temp file).
     * Targets a frame ~1 s in (or the midpoint of a shorter clip) so the poster
     * isn't a black leading frame, snapping to the nearest keyframe. ⚠ The offset is
     * [MotionClipPlan.posterOffsetUs], shared with the `MOTION` clip so the loop's first
     * frame is this one (`photos-video-support.md` D4). Returns `null`
     * on any failure (unsupported codec, corrupt container) — the caller then falls
     * back to a stored thumbnail. Best-effort: it must never throw out of here.
     */
    private fun extractVideoPoster(bytes: ByteArray): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(InMemoryMediaDataSource(bytes))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val targetUs = MotionClipPlan.posterOffsetUs(durationMs * 1_000L)
            retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime() // last resort: any representative frame
        } catch (e: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Rasterise the **first page** of a PDF [bytes] to a [Bitmap] via the framework
     * [PdfRenderer], for the thumbnail render. `PdfRenderer` needs a seekable
     * [ParcelFileDescriptor], so the bytes go through a temp file in the app cache,
     * deleted before returning. Renders at a width of at least [maxEdge] (so the
     * downstream downscale stays sharp) and at most [MAX_PDF_RENDER_PX] (to bound
     * memory), defaulting toward the page's natural point width; the page is erased
     * white first (transparent regions otherwise render black). Returns `null` on any
     * failure — the caller then falls back to a stored thumbnail. Best-effort: it
     * must never throw out of here.
     */
    private fun renderPdfFirstPage(bytes: ByteArray, maxEdge: Int): Bitmap? {
        var tempFile: java.io.File? = null
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            tempFile = java.io.File.createTempFile("preview", ".pdf")
            tempFile.writeBytes(bytes)
            descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount < 1) return null
            // PdfRenderer permits only one open page at a time and isn't thread-safe;
            // GetData/GetPreview runs on a single op executor so access is serialised.
            val page = renderer.openPage(0)
            try {
                if (page.width <= 0 || page.height <= 0) return null
                val lo = maxEdge.coerceIn(1, MAX_PDF_RENDER_PX)
                val renderWidth = page.width.coerceIn(lo, MAX_PDF_RENDER_PX)
                val renderHeight =
                    (renderWidth.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        } catch (e: Throwable) {
            null
        } finally {
            try {
                renderer?.close()
            } catch (_: Throwable) {
            }
            try {
                descriptor?.close()
            } catch (_: Throwable) {
            }
            try {
                tempFile?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        /** Longest edge of the `LIST` thumbnail class — the smallest-class boundary. */
        const val LIST_THUMBNAIL_PX = GetStoredThumbnail.LIST_PX

        /** Upper bound on the PDF first-page raster width (px), to cap memory. */
        const val MAX_PDF_RENDER_PX = 2048
    }
}
