package org.tenshu.core.file

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * The on-demand resolver behind [File.Thumbnail] / [File.ThumbnailLarge]
 * (`sync-device-catch-up.md` C7) — the file-service twin of [GetWorkoutStreams]
 * (`org.tenshu.core.health`), which is itself modelled on [GetData].
 *
 * Both thumbnail classes were **eagerly-synced stored values** until C7. The C6
 * census measured what that cost: thumbnails are **97.26 % of every byte a `File`
 * row occupies** (~18.4 KB of ~18.7 KB; a `File` without them is ~336 B), so every
 * one of them rode the eager entity block to every paired device whether or not
 * anything ever displayed it. They are now `dynamicProperty(Syncable(OnDemand))`
 * with their bytes on the content plane, fetched only when something asks.
 *
 * ⚠ **One op, two property ids.** The two classes must stay *separately addressable*
 * — `ExternalPropertyWriter` routes by `propertyId` to a distinct external path and
 * a distinct content id, and `drive-synced-thumbnails.md` §4 wants `LARGE`
 * independently evictable. So they remain two property handles; only the resolver is
 * shared, discriminated by [Params.THUMBNAIL_CLASS].
 *
 * Returns `null` when the blob is not present locally — a non-image file, a file
 * uploaded before thumbnails existed, or bytes not yet fetched from the relay. Every
 * caller already treats null as "no stored thumbnail", exactly as `File.Data`
 * returning null means "no local bytes".
 */
fun File.getStoredThumbnail(thumbnailClass: String): TByteArray =
    findOperation<GetStoredThumbnail>(GetStoredThumbnail.ID).getStoredThumbnail(this, thumbnailClass)

interface GetStoredThumbnail : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getStoredThumbnail(file: File, thumbnailClass: String): TByteArray

    companion object {
        const val ID = "tenshu/file/getStoredThumbnail"

        /** The small (`LIST`) class — [File.Thumbnail]. */
        const val CLASS_LIST = "list"

        /** The larger (`LARGE`) class — [File.ThumbnailLarge]. */
        const val CLASS_LARGE = "large"

        /**
         * The hero class (`XLARGE`, ~1024 px) — [File.ThumbnailXLarge].
         *
         * The rung between [CLASS_LARGE] and the file's whole original
         * (`drive-synced-thumbnails.md` §12). Measured on a 2304x4096 photo the ladder ran
         * 256 px/15 KB then straight to 3 344 KB — a 220x gap that a hero had no choice but
         * to jump. ⚠ Its weight is set by its own dimensions, not the source's: a 45 MP DSLR
         * frame and a phone snap both land near 139 KB here, which is the property that makes
         * a class the right answer where fetching originals is not.
         */
        const val CLASS_XLARGE = "xlarge"

        /**
         * The **motion** class (`MOTION`, ~3 s / 256 px H.264) — [File.ThumbnailMotion].
         *
         * ⚠⚠ **Not a rung on the same ladder as the three above, and not interchangeable with
         * them.** Those are stills of increasing size, and [GetPreview] picks the smallest that
         * covers a request. This one is a *clip*: it answers a different question ("show me this
         * video moving"), it is minted only for [FileCategory.Video], and nothing may substitute it
         * for a still or a still for it. It shares this operation because the storage and delivery
         * are identical — a blob on the content plane under a derived content id — not because it
         * is another size of the same thing.
         *
         * ⚠ Its weight is set by the encode settings alone: measured across seven real sources
         * spanning a 67x range of source bitrate, output varied by 1.11x
         * (`photos-video-support.md` §12.5). A motion preview is a fixed-size object.
         */
        const val CLASS_MOTION = "motion"

        /**
         * Longest edge, in px, of the [CLASS_LIST] rung.
         *
         * ⚠ Public because *clients* need them, not just the mint: these rungs are
         * `Syncable(OnDemand)`, so pulling one by name means knowing what each covers. The mint and
         * all three `GetPreview` actuals alias these rather than re-declaring the numbers.
         */
        const val LIST_PX = 100

        /** Longest edge, in px, of the [CLASS_LARGE] rung. See [LIST_PX] for why this is public. */
        const val LARGE_PX = 256

        /**
         * The class above the hero (`XXLARGE`, 2048 px) — [File.ThumbnailXXLarge].
         *
         * ⚠⚠ **A pull, never a tile.** It is ~3x `XLARGE`'s bytes, so anything that names it for a
         * list surface puts half a megabyte per row on the wire
         * (`drive-xxlarge-thumbnail-rung.md` §4.4).
         */
        const val CLASS_XXLARGE = "xxlarge"

        /**
         * Longest edge, in px, of the [CLASS_XXLARGE] rung.
         *
         * 2048 covers a phone's own screen outright and a retina desktop hero at 1.45x, where
         * [XLARGE_PX] is a 2.9x upscale. 2560 would cost ~45 % more bytes for a difference only a
         * retina desktop can show (§2).
         */
        const val XXLARGE_PX = 2048

        /**
         * Longest edge, in px, of the [CLASS_XLARGE] rung. See [LIST_PX] for why this is public.
         *
         * 1024 rather than "whatever the biggest hero asks for": a retina desktop hero wanted ~1680 px,
         * but the class is deliberately below that and [COVER_TOLERANCE] closes the gap (§12.5 D-2).
         * 1024 px q80 measured 139 KB against 295 KB at 1600 px on a real photo.
         */
        const val XLARGE_PX = 1024

        /**
         * How far `GetPreview` stretches a stored class before it reads the whole file instead
         * (`drive-synced-thumbnails.md` §12.5 D-2): a request up to `XLARGE_PX × COVER_TOLERANCE`
         * that no larger rung covers locally is served the hero rung **even on a device holding the
         * original**. ⚠ Public for the same reason as [LIST_PX]: a client that wants a render from
         * the original — the Photos viewer on the device that took the photograph (`photos-app.md`
         * §34) — has to ask past it, and a copy of the number in the client would drift from the
         * ladder that applies it (`StoredThumbnailLadder`, shared by the three actuals).
         *
         * ⚠ 2.0 was chosen against the real worst case, not as a round number: a retina desktop hero
         * was ~1680 px against the 1024 px class = **1.64x**, so a tidier-looking 1.5 would have left
         * that surface uncovered and sent it to the original — the cost the hero rung exists to avoid.
         */
        const val COVER_TOLERANCE = 2.0

        /**
         * Longest edge, in px, of the [CLASS_MOTION] clip — the `LARGE` rung's size, which is what a
         * grid tile renders at on a byte-less device (`photos-video-support.md` §12.5: 256 px
         * measured byte-free against 192 and visibly sharper). ⚠ Not a still rung — see
         * [CLASS_MOTION]; it is here beside the others only so the mint aliases one number.
         */
        const val MOTION_PX = 256

        val Schema = operation(
            id = ID,
            receiver = File.Schema.toType(),
            params = {
                composite(id = Params.FILE, type = File.Schema)
                string(id = Params.THUMBNAIL_CLASS)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val FILE = "$ID/file"
        const val THUMBNAIL_CLASS = "$ID/thumbnailClass"
    }
}
