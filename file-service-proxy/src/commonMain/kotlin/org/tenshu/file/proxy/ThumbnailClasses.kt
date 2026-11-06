package org.tenshu.file.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.relation.Property
import org.tenshu.file.impl.UploadFileImplementation

/**
 * One stored thumbnail class (`drive-synced-thumbnails.md` §10.2, §12, §15).
 *
 * ⚠ Named for what it is rather than where it sits. The four [Still] classes form a size-ordered
 * ladder and are rungs of it — that is what [Still.stretches] and [STILLS]' ordering are about —
 * but [Motion] is not on that ladder at all, so "rung" was never true of this type as a whole. The
 * metaphor stays in the prose and in `StoredThumbnailLadder`, where it is accurate.
 *
 * @property thumbnailClass the `GetStoredThumbnail.CLASS_*` id: the blob's path segment, the
 *   wanted-set vocabulary and half of its derived content id.
 * @property property the `File.Thumbnail*` property the class is synced and pulled under.
 * @property maxBytes the cap a render must fit under or be declined (`UploadFileImplementation`).
 * @property backfilled whether the thumbnail backfill mints it for existing files once a device in
 *   the realm declares it (§15.2). ⚠ `LIST` and `LARGE` are not: they are wanted everywhere
 *   implicitly and minted at upload unconditionally, so they are never in a wanted set.
 */
internal sealed class ThumbnailClass(
    /** The class's own id — `GetStoredThumbnail.CLASS_*`, the string that crosses every boundary. */
    val id: String,
    val property: Property.Dynamic<TByteArray>,
    val maxBytes: Int,
    val backfilled: Boolean,
) {
    /**
     * A still image, rendered by `GetPreview` at [px] on its longest edge as JPEG — the ladder
     * `File.Preview` serves from.
     *
     * @property stretches whether the resolver may serve this rung for a request up to
     *   `GetStoredThumbnail.COVER_TOLERANCE` times [px] rather than read the whole original
     *   ([StoredThumbnailLadder.covering]). ⚠⚠ Only the hero rungs: a stretched `LARGE` is the blur
     *   this ladder exists to prevent (`drive-synced-thumbnails.md` §14.2).
     */
    class Still(
        id: String,
        property: Property.Dynamic<TByteArray>,
        val px: Int,
        maxBytes: Int,
        backfilled: Boolean,
        val stretches: Boolean,
    ) : ThumbnailClass(id, property, maxBytes, backfilled)

    /**
     * A short looping clip of a video (`photos-video-support.md` §4). ⚠⚠ Never part of the still
     * ladder: a clip is not a bitmap, and `GetPreview` is on every list row's path.
     */
    class Motion(
        id: String,
        property: Property.Dynamic<TByteArray>,
        maxBytes: Int,
        backfilled: Boolean,
    ) : ThumbnailClass(id, property, maxBytes, backfilled)

    /**
     * Whether a file of [category] at [path] can carry this class at all — what the source must be
     * for the mint to have something to render. ⚠ Not "will the render succeed": an undecodable
     * image passes here and is declined by the render.
     */
    fun canCarry(category: FileCategory, path: String, canRenderMotion: Boolean): Boolean = when (this) {
        // What `GetPreview` renders: an image, a video's poster frame, a PDF's first page.
        is Still -> category == FileCategory.Image ||
            category == FileCategory.Video ||
            path.endsWith(".pdf", ignoreCase = true)
        // Video-only by definition (§4.1 of the video doc), and only where a renderer exists.
        is Motion -> category == FileCategory.Video && canRenderMotion
    }
}

/**
 * ⚠⚠ **Every stored thumbnail class, in one table** (`drive-xxlarge-thumbnail-rung.md` X-0).
 *
 * The ladder used to be spelled out wherever it was used: the writer's property map, a second
 * hand-written list in `ProxyDeclarableContent`, `when` branches in the backfill whose `else` arms
 * silently declined, capped or skipped any class they did not name, the upload's render and emit
 * lines, and three identical copies of the resolver's ladder in the `GetPreview` actuals. A new rung
 * had to find all of them, and several would have compiled and quietly done nothing. Everything that
 * enumerates classes reads this instead.
 *
 * ⚠ [STILLS] is ordered by edge, smallest first — the resolver's ladder depends on that order.
 */
internal object ThumbnailClasses {

    val LIST = ThumbnailClass.Still(
        id = GetStoredThumbnail.CLASS_LIST,
        property = File.Thumbnail,
        px = GetStoredThumbnail.LIST_PX,
        maxBytes = UploadFileImplementation.MAX_THUMBNAIL_BYTES,
        backfilled = false,
        stretches = false,
    )

    val LARGE = ThumbnailClass.Still(
        id = GetStoredThumbnail.CLASS_LARGE,
        property = File.ThumbnailLarge,
        px = GetStoredThumbnail.LARGE_PX,
        maxBytes = UploadFileImplementation.MAX_LARGE_THUMBNAIL_BYTES,
        backfilled = false,
        stretches = false,
    )

    val XLARGE = ThumbnailClass.Still(
        id = GetStoredThumbnail.CLASS_XLARGE,
        property = File.ThumbnailXLarge,
        px = GetStoredThumbnail.XLARGE_PX,
        maxBytes = UploadFileImplementation.MAX_XLARGE_THUMBNAIL_BYTES,
        backfilled = true,
        stretches = true,
    )

    /**
     * ⚠⚠ **The top rung stretches only because `File.ViewerPreview` exists** — D-14's `≤ 4096` row,
     * turned on by X-3 (`drive-xxlarge-thumbnail-rung.md` §4.2, §9.5). A stretch of the *largest*
     * rung competes with the original itself rather than covering a gap to the rung above, so it is
     * only safe while a caller that wants the original can say so: `GetPreview.Params.PREFER_ORIGINAL`,
     * which `File.ViewerPreview` sets and `File.Preview` does not.
     *
     * ⚠⚠ **Never flip this back to `true` for a rung with no such escape, and never remove the
     * escape while it is `true`.** X-1 shipped the row with the flag `false` for exactly that
     * reason: `HeroPreviewSize.MAX_HERO_PX` is 4096, exactly `XXLARGE_PX × COVER_TOLERANCE`, so with
     * the band open and nothing able to decline it **no** hero request escapes — the device holding
     * the original is handed its own picture upscaled from 2048 px. `SyncedThumbnailSystemTest`'s
     * retina case is that regression written as an assertion.
     */
    val XXLARGE = ThumbnailClass.Still(
        id = GetStoredThumbnail.CLASS_XXLARGE,
        property = File.ThumbnailXXLarge,
        px = GetStoredThumbnail.XXLARGE_PX,
        maxBytes = UploadFileImplementation.MAX_XXLARGE_THUMBNAIL_BYTES,
        backfilled = true,
        stretches = true,
    )

    val MOTION = ThumbnailClass.Motion(
        id = GetStoredThumbnail.CLASS_MOTION,
        property = File.ThumbnailMotion,
        maxBytes = UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES,
        backfilled = true,
    )

    /** The still ladder, smallest edge first. */
    val STILLS: List<ThumbnailClass.Still> = listOf(LIST, LARGE, XLARGE, XXLARGE)

    /** Every class, stills first. */
    val ALL: List<ThumbnailClass> = STILLS + MOTION

    fun forClass(thumbnailClass: String): ThumbnailClass? = ALL.firstOrNull { it.id == thumbnailClass }

    fun forPropertyId(propertyId: String): ThumbnailClass? = ALL.firstOrNull { it.property.id == propertyId }
}
