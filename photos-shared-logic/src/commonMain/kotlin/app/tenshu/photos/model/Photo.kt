package app.tenshu.photos.model

import app.tenshu.photos.ui.screens.feed.FeedDensity

/**
 * One row of the Photos feed (`photos-app.md` S1).
 *
 * ⚠ Three scalars and a path, deliberately. The feed is the platform's widest read — every image
 * in the realm — and the grid shows a thumbnail, a date header and nothing else. Bytes are loaded
 * per tile, on demand, and never held here: a page of 60 originals is ~200 MB and a page of this
 * is a few kilobytes.
 *
 * ⚠⚠ **[kotlin.native.ObjCName] is load-bearing** (PIO-D6, parity doc D12): `:timeline-shared-logic`
 * exports an `app.tenshu.timeline.model.Photo` that `timeline-ios` already spells, and K/N renames
 * the later-sorting of two clashing names to `Photo_` — which would be Timeline's, since `photos`
 * sorts first. The newcomer takes the prefix. `scripts/check-objc-clashes.py` finds these.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("PhotosPhoto")
data class Photo(
    /** Absolute realm path, e.g. `/Backup/Phone/Camera/IMG_0421.jpg`. The row's identity. */
    val path: String,
    /**
     * Capture time in epoch millis — `File.CreatedAt`, which is the **capture** time for a
     * backed-up gallery item (Android seeds it from MediaStore `DATE_TAKEN`) and the source
     * file's mtime for anything that arrived another way.
     *
     * It is also the feed's **keyset cursor**: the next page asks for everything strictly older
     * than the last row's value.
     */
    val capturedAtMs: Long,
    /** Video or still. Both belong in the feed; only the tile decoration differs. */
    val isVideo: Boolean,
    /**
     * `File.DurationMs` — how long the video runs, for the tile's badge
     * (`photos-video-support.md` M1).
     *
     * ⚠ `null` means **unknown**, never zero, and the two are not interchangeable: a video
     * uploaded before the field was stamped, or in a container the metadata pass could not read,
     * has no duration to show. The tile renders no badge for `null` and would be lying if it
     * rendered `0:00` — the same rule [latE7] follows, where absent is not the Atlantic.
     *
     * ⚠ Also `null` for every still, so a reader must gate on [isVideo] rather than treat a
     * present duration as the thing that makes a row a video.
     */
    val durationMs: Long? = null,
    /**
     * `File.ContentId` — the identity every album and flag write is keyed on (album membership,
     * flags), because it is what survives a move or a rename. See `AlbumEntry`'s KDoc for the
     * argument.
     *
     * ⚠ Empty for a file uploaded before content addressing. Such a photo can be *shown* but not
     * filed: the album and flag ops refuse it rather than minting a row keyed on the empty string,
     * so the UI must disable those actions rather than let them fail.
     */
    val contentId: String = "",
    /**
     * Where the photograph was taken, in E7 degrees, or `null` when it carries no EXIF GPS.
     *
     * ⚠ `null`, never `(0, 0)` — the call `PhotosNear` makes for the same reason: absent is not
     * the Atlantic, and reading it as one files every location-less photo off the coast of Africa.
     *
     * ⚠ Most photos have none. Android strips GPS from `MediaStore` unless the *opening* process
     * holds `ACCESS_MEDIA_LOCATION`, and the parser is JPEG-only, so an iPhone HEIC has none at
     * all. The Places section is built to be small and honest rather than to pretend.
     */
    val latE7: Long? = null,
    val lngE7: Long? = null,
    /**
     * `File.ContentHash` — the whole-file plaintext hash two byte-identical copies share.
     *
     * ⚠ Populated only where a read actually asked for it: the Duplicates surface projects it, the
     * feed does not, and a photo's identity in this app remains [contentId]. Empty therefore means
     * **"not read"** as often as "not stored", which is precisely why nothing treats two empty
     * hashes as a match.
     */
    val contentHash: String = "",
    /**
     * `File.PixelWidth` / `File.PixelHeight` — the **stored** size, so a portrait photo may read as
     * landscape. ⚠ Here only for the ratio of the two, which a rotation does not change: the tile is
     * a square crop, and whether a thumbnail class fills it depends on the photo's short edge
     * (`tilePreviewRequestPx`). `null` when the metadata pass never read the file.
     */
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    /**
     * Where this photo's **edit** is baked (`photos-editing.md` §12, ED-3), or `null` for an unedited
     * photo. Set by the repository from the `PhotoEdit` rows it holds; the photo's own [path] and
     * [contentId] are the original's and never change.
     *
     * ⚠⚠ **Shown by the rendition, or not at all** (§12.4): a tile or viewer draws [previewPath], and
     * a rendition this device cannot read yet is a placeholder — never the original, which for a
     * redacted edit is the very thing the edit hides (§11.3). Anything that hands the photo OUT (a
     * share, a download, when they exist) hands out the rendition, and the original only as an
     * explicit choice.
     */
    val renditionPath: String? = null,
) {
    /** Last path segment — what the viewer's info sheet shows. Never persisted. */
    val name: String get() = path.substringAfterLast('/')

    /** Whether this photo carries a non-destructive edit (ED-3). */
    val isEdited: Boolean get() = renditionPath != null

    /**
     * The path whose thumbnails and hero rung draw this photo: the edit's rendition when it has one,
     * else the photo itself. ⚠ What every tile, viewer page and album cover asks for — never [path]
     * directly (§12.5).
     */
    val previewPath: String get() = renditionPath ?: path
}

/**
 * [Photo.durationMs] as a clock reading — `0:06`, `1:23`, `1:02:03` (`photos-video-support.md` M1).
 *
 * ⚠ In the Compose-free module for the same reason as [groupIntoDays]: this is arithmetic, not
 * drawing, so it is testable without a renderer and available to an iOS shell that will not use
 * Compose. It is deliberately **not** locale-aware — a duration is digits and colons in every
 * locale this app ships to, and `LC_NUMERIC` would only introduce a way for it to be wrong.
 *
 * ⚠ The hours field appears only when there are hours, so a six-second clip reads `0:06` rather
 * than `0:00:06`. Minutes stay unpadded at the front (`1:23`) and padded after an hour (`1:02:03`),
 * which is what every player and both platform galleries do.
 *
 * ⚠ A negative clamps to `0:00` rather than rendering a minus sign. The value is a stored `Long`
 * from another device's metadata pass, so "impossible" is a shape this can actually be handed, and
 * a badge reading `-1:-3` is worse than one reading zero.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** A day's worth of photos, newest first — the unit the feed's sticky headers section on. */
data class PhotoDay(
    /** Local midnight of the day, epoch millis — the header's key, stable across recomposition. */
    val dayStartMs: Long,
    val photos: List<Photo>,
)

/**
 * Group a newest-first photo list into newest-first days.
 *
 * ⚠ Pure, and in the Compose-free module on purpose: sectioning is the one piece of feed layout
 * that is arithmetic rather than drawing, so it is testable without a renderer and shared with an
 * iOS shell that will not use Compose (the `:calendar-shared-logic` `MonthGrid` precedent).
 *
 * ⚠ [dayStartOf] is injected rather than computed here because "which day is this instant in" is a
 * **timezone** question, and injection keeps this arithmetic testable with a fixed offset. The
 * shells pass `PhotoDates::dayStart`; tests pass a fixed-offset one.
 */
fun List<Photo>.groupIntoDays(dayStartOf: (Long) -> Long): List<PhotoDay> {
    if (isEmpty()) return emptyList()

    val days = mutableListOf<PhotoDay>()
    var currentStart = dayStartOf(first().capturedAtMs)
    var current = mutableListOf<Photo>()

    for (photo in this) {
        val start = dayStartOf(photo.capturedAtMs)
        if (start != currentStart) {
            days += PhotoDay(currentStart, current)
            currentStart = start
            current = mutableListOf()
        }
        current += photo
    }
    days += PhotoDay(currentStart, current)
    return days
}

/**
 * One row of the paged feed: a photograph, or the header that opens its day.
 *
 * ⚠⚠ Exists because a paged feed cannot group. [groupIntoDays] takes a whole list and returns days
 * holding photographs, which is the right shape for a list the view model accumulates and the wrong
 * one for a stream of pages — `LazyPagingItems` addresses a flat index, and the item at index *n*
 * has to be known without the rows after it. So the grouping becomes a *separator* rule between
 * adjacent rows (`PhotosFeedViewModel.items`), and the flat sequence carries both kinds.
 *
 * ⚠ [groupIntoDays] stays: the Places section and the album pickers still read whole lists.
 */
sealed interface FeedItem {
    /**
     * A photograph, and the grouping it was cut for.
     *
     * ⚠⚠ [density] is how a presented window names its own generation. A density
     * change rebuilds the paged stream, and the re-anchor must settle on the REBUILT generation —
     * not on a replay of the old one (`cachedIn` re-emits it on a re-collect), nor on an old page
     * landing late. Without it two groupings of the same photographs are EQUAL windows, which the
     * presenter's `StateFlow` swallows. `null` only for items built outside the feed (tests).
     */
    data class Tile(val photo: Photo, val density: FeedDensity? = null) : FeedItem
    data class DayHeader(val dayStartMs: Long) : FeedItem
}
