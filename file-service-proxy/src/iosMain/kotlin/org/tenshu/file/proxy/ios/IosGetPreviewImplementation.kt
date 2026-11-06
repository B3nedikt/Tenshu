@file:OptIn(ExperimentalForeignApi::class, ExperimentalStdlibApi::class, BetaInteropApi::class)

package org.tenshu.file.proxy.ios

import kotlinx.cinterop.BetaInteropApi
import org.tenshu.file.proxy.StoredThumbnailLadder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
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
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.path
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.StaticPropertyRelation
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * iOS `GetPreview` — the K/N twin of [org.tenshu.file.proxy.jvm.JvmGetPreviewImplementation]
 * and `AndroidGetPreviewImplementation`, closing the P4e gap (docs/proposals/ios-drive-parity.md
 * §P4): before this, iOS wired only the *common* `fileServiceProxyImplementationFactory`, which
 * has no `GetPreview` arm, so the iPhone never rendered the eager-synced `File.Thumbnail`/
 * `File.ThumbnailLarge` at upload (→ thumbnails never synced *from* iOS) and couldn't resolve a
 * synced thumbnail for display (→ thumbnails never shown *on* iOS).
 *
 * Structurally mirrors the Android impl: image, PDF, and video previews are all **generated**
 * here (UIKit `UIImage` downscale / PDFKit page-0 / `AVAssetImageGenerator` poster frame). A
 * byte-less file (no local bytes yet — `File.Data` is `Syncable(OnDemand)`) falls back to the
 * best stored thumbnail class, so a poster/thumbnail synced from another device is still served
 * when generation isn't possible. Best-effort throughout: any decode/encode failure leaves the
 * property unset (→ category icon), never fails the upload.
 */
internal class IosGetPreviewImplementation(
    private val previewCache: PreviewCache = NoOpPreviewCache,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
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

        // Absent ⇒ [JPEG_QUALITY]. Null-safe for the same reason again.
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
        // applies (mirrors the JVM/Android impls). A bare `File` that fell through to
        // "" here would silently disable the PDF branch at upload.
        val fileName = when (file) {
            is Entity<*> -> @Suppress("UNCHECKED_CAST") (file as Entity<out File>).path.get()
            is File -> file.path.get()
            else -> ""
        }.substringAfterLast('/')

        val typeId = if (file is Entity<*>) file.type.get() else file.id
        if (typeId == Folder.ID) {
            return null
        }

        val isVideo = fileCategoryForName(fileName) == FileCategory.Video
        val isPdfFile = isPdf(fileName)

        // A LIST-class read (≤ 100 px — a Files-row render) on a stored entity is
        // served straight from the eager-synced stored thumbnail when one exists:
        // the compute path below reads + decrypts the ENTIRE file via `GetData` and
        // sha256s the bytes just to build the preview-cache key. Guarded to `Entity`
        // callers so the upload-time generation path (bare `File` caller via
        // `renderThumbnail`) always computes fresh bytes; larger requests (details
        // view) keep computing sharp previews from local bytes. Mirrors Android.
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
            if (!isVideo && !isPdfFile && fileCategoryForName(fileName) != FileCategory.Image) {
                return null
            }
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
            caller = file,
        )?.get()

        if (bytes == null || bytes.isEmpty()) {
            return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))
        }

        // Cache lookup keyed by content + requested dimensions (+ format and quality, since the
        // encoded bytes differ by both). Hit → cheap byte read; miss → generate and
        // persist on success (see `PreviewCache` KDoc).
        val contentHash = cacheKey(sha256(bytes).toHexString(), format, quality)
        previewCache.get(contentHash, width, height)?.let { cached ->
            return TByteArrayDto(cached)
        }

        return try {
            // A video's "preview" is a poster frame (drive-content-preview.md §3);
            // a PDF's is its first page rasterised (§4 Phase 2b). Both reduce to a
            // `UIImage`, so everything downstream (scale + encode) is identical to an
            // image. When the render fails, a read falls back to any stored thumbnail,
            // same as a byte-less file; a generation call gets null (`storedFallback`).
            val originalImage: UIImage = when {
                isVideo -> extractVideoPoster(bytes, fileName, maxEdge = maxOf(width, height))
                    ?: return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))

                isPdfFile -> renderPdfFirstPage(bytes, maxEdge = maxOf(width, height))
                    ?: return storedFallback(executionContext, file, generateFresh, requestedMaxEdge = maxOf(width, height))

                else -> UIImage.imageWithData(bytes.toNSData()) ?: return null
            }

            val scaled = downscale(originalImage, width, height) ?: return null
            val previewBytes = encode(scaled, format, quality) ?: return null
            if (previewBytes.isEmpty()) return null

            // Best-effort persist. `put` returns false (size cap, IO) silently — the
            // caller still serves the generated bytes; only future warm reads lose the
            // cache benefit.
            previewCache.put(contentHash, width, height, previewBytes)

            TByteArrayDto(previewBytes)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Downscale [image] to fit within the ([maxW], [maxH]) box, preserving aspect
     * ratio and **never upscaling** (matches the JVM/Android box-fit math). Renders
     * at scale 1 so the output pixel size equals the point size — otherwise
     * `UIGraphicsImageRenderer` uses the device scale (×2/×3) and blows the thumbnail
     * caps. Returns `null` on degenerate dimensions.
     */
    private fun downscale(image: UIImage, maxW: Int, maxH: Int): UIImage? {
        val originalWidth: Double
        val originalHeight: Double
        image.size.useContents {
            originalWidth = width
            originalHeight = height
        }
        if (originalWidth <= 0.0 || originalHeight <= 0.0) return null

        val outWidth: Double
        val outHeight: Double
        if (originalWidth <= maxW && originalHeight <= maxH) {
            outWidth = originalWidth
            outHeight = originalHeight
        } else {
            val aspectRatio = originalWidth / originalHeight
            if (maxW / aspectRatio <= maxH) {
                outWidth = maxW.toDouble()
                outHeight = maxW / aspectRatio
            } else {
                outHeight = maxH.toDouble()
                outWidth = maxH * aspectRatio
            }
        }

        val targetWidth = outWidth.toInt()
        val targetHeight = outHeight.toInt()
        if (targetWidth <= 0 || targetHeight <= 0) return null

        val rendererFormat = UIGraphicsImageRendererFormat.defaultFormat().apply {
            setScale(1.0)
            setOpaque(false)
        }
        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble()),
            format = rendererFormat,
        )
        return renderer.imageWithActions {
            image.drawInRect(CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()))
        }
    }

    /**
     * Encode [image] to PNG (default) or JPEG (LARGE class, Q1 — smaller for photos,
     * fits the LARGE cap), at [quality] (1–100) when given, else [JPEG_QUALITY]. Returns `null` if
     * the platform can't produce the encoding.
     */
    private fun encode(image: UIImage, format: String, quality: Int?): ByteArray? {
        val data: NSData? = if (format == GetPreview.Params.FORMAT_JPEG) {
            UIImageJPEGRepresentation(image, quality?.let { it.coerceIn(1, 100) / 100.0 } ?: JPEG_QUALITY)
        } else {
            UIImagePNGRepresentation(image)
        }
        return data?.toByteArray()
    }

    /**
     * Rasterise the **first page** of a PDF [bytes] to a [UIImage] via PDFKit, sized
     * to fit a square [maxEdge]-point box (the downstream [downscale] then fits it to
     * the requested dimensions). Returns `null` on any failure (corrupt, encrypted,
     * empty) — the caller then falls back to a stored thumbnail. Best-effort: never
     * throws out of here.
     */
    private fun renderPdfFirstPage(bytes: ByteArray, maxEdge: Int): UIImage? = try {
        val document = PDFDocument(data = bytes.toNSData())
        val page = document.pageAtIndex(0.convert())
        if (page == null) {
            null
        } else {
            val edge = maxEdge.coerceIn(1, MAX_PDF_RENDER_PX).toDouble()
            page.thumbnailOfSize(
                CGSizeMake(edge, edge),
                forBox = kPDFDisplayBoxCropBox,
            )
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * Extract a poster-frame [UIImage] from in-memory video [bytes] via
     * `AVAssetImageGenerator` (drive-content-preview.md §3 Phase 3 — the iOS twin of
     * Android's `MediaMetadataRetriever`). `AVAsset` needs a URL, so the bytes go
     * through an app-private temp file (leaf extension preserved from [fileName] so
     * AVFoundation infers the container type), deleted before returning. Targets a
     * frame ~1 s in (or the midpoint of a shorter clip) so the poster isn't a black
     * leading frame; `appliesPreferredTrackTransform` bakes in the rotation, and
     * `maximumSize` decodes straight to ~[maxEdge] to bound memory. Returns `null` on
     * any failure (unsupported codec, corrupt container) — the caller then falls back
     * to a stored thumbnail. Best-effort: it must never throw out of here.
     *
     * Uses the async `generateCGImageAsynchronouslyForTime` (iOS 16+; deployment target
     * is 16.0) rather than the deprecated synchronous `copyCGImageAtTime`. `GetPreview`
     * is a **synchronous** op (HR#3 — no `suspend` on the `Operation` interface), so the
     * async generator is bridged back to this thread with a dispatch semaphore.
     */
    private fun extractVideoPoster(bytes: ByteArray, fileName: String, maxEdge: Int): UIImage? {
        val extension = fileName.substringAfterLast('.', "mov")
        val tempPath = NSTemporaryDirectory() + "poster-" + NSUUID().UUIDString() + "." + extension
        val written = bytes.toNSData().writeToFile(tempPath, atomically = true)
        if (!written) return null
        return try {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(tempPath), options = null)
            val generator = AVAssetImageGenerator(asset = asset).apply {
                appliesPreferredTrackTransform = true
                val edge = maxEdge.coerceIn(1, MAX_POSTER_PX).toDouble()
                maximumSize = CGSizeMake(edge, edge)
            }
            val durationSec = CMTimeGetSeconds(asset.duration)
            val targetSec = if (durationSec > 0.0) minOf(1.0, durationSec / 2.0) else 0.0
            val requestedTime = CMTimeMakeWithSeconds(targetSec, POSTER_TIMESCALE)

            // AVFoundation invokes the completion handler on its own queue; block this
            // (synchronous op-executor) thread on a semaphore until it fires. The handler
            // CGImageRetains the frame so it outlives the callback (the passed CGImage is
            // owned by the framework); we release it after wrapping in a UIImage. The
            // semaphore wait is a happens-before barrier, so the handler's write to
            // `retainedImage` is visible here.
            val semaphore = dispatch_semaphore_create(0)
            var retainedImage: CGImageRef? = null
            generator.generateCGImageAsynchronouslyForTime(requestedTime) { cgImage, _, _ ->
                if (cgImage != null) retainedImage = CGImageRetain(cgImage)
                dispatch_semaphore_signal(semaphore)
            }
            dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

            val cgImage = retainedImage ?: return null
            val image = UIImage.imageWithCGImage(cgImage)
            CGImageRelease(cgImage)
            image
        } catch (e: Throwable) {
            null
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(tempPath, error = null)
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
     * Resolve the best stored thumbnail **class** for this byte-less file at the given
     * [requestedMaxEdge] (the larger of the requested width/height). Picks the smallest
     * class that covers the request, falling back to any available class
     * (drive-synced-thumbnails.md §10.2). Stored value properties are **not** inline on
     * the entity composite (they materialise via `select`/`relations`, not
     * `entity.get`), so each resolves via [ExecutionContext.resolveProperty].
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

    private fun cacheKey(contentHash: String, format: String, quality: Int?): String = when {
        format == GetPreview.Params.FORMAT_PNG -> contentHash
        quality == null -> "$contentHash:$format"
        else -> "$contentHash:$format:q$quality"
    }

    private companion object {
        /** Longest edge of the `LIST` thumbnail class — the smallest-class boundary. */
        const val LIST_THUMBNAIL_PX = GetStoredThumbnail.LIST_PX

        /** Upper bound on the PDF first-page raster edge (px), to bound memory. */
        const val MAX_PDF_RENDER_PX = 2048

        /** Upper bound on the decoded video-poster edge (px) — `maximumSize` cap. */
        const val MAX_POSTER_PX = 1024

        /** `CMTime` timescale for the poster seek (600 = the AVFoundation default). */
        const val POSTER_TIMESCALE = 600

        /** JPEG encode quality for the LARGE class (matches the JVM/Android ~80%). */
        const val JPEG_QUALITY = 0.8
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.convert())
        }
    }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, size.convert()) }
    return out
}
