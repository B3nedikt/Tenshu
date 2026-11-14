@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.failureResult
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TError
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.TRASH_ROOT
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.file.category
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.ifEmptyError
import org.tenshu.core.inRange

// ⚠⚠ A SECOND READER of the relations `TenshuPhotosRepository` (`photos-shared-logic`) reads — the
// `web-app-suite.md` §4.1 trap, taken on purpose because `*-shared-logic` must not compile to JS.
// The rules it copies are listed here once, and each is pinned by `PhotosLibraryRulesTest` (pure)
// or `PhotosLibraryContractTest` (the real runtime). Change one side and the other must follow:
//
//  1. the feed is every MEDIA file (image or video) with a capture time, outside `/Trash`, newest
//     first — [feedWindow], `TenshuPhotosRepository.feedWindow`;
//  2. the window is INCLUSIVE of the cursor (`inRange` is half-open, so `+ 1`), with `Long.MAX_VALUE`
//     the "from the head" sentinel that must not be widened — [exclusiveEndFor];
//  3. ⚠⚠ DIVERGES from native: the bridge's order is TOTAL, `(CreatedAt desc, path asc)`, and the
//     cursor is `(ms, last path served)` — [FeedCursor], [nextFeedCursor], [afterCursor]. Native's
//     `readLibrary` counts the rows served at the boundary millisecond (`skip`), which leans on
//     the order of equal-`CreatedAt` rows and so repeats or skips a row when one arrives, or is
//     trashed, at that millisecond. A cursor that names a row needs no count;
//  4. a row with no capture time is dropped, never read as 1970 — [photoOf];
//  5. a trashed photograph lives at `/Trash/<contentId>-<name>` — [trashPathFor];
//  6. a new album's id is `album-<clock>-<hex(name.hashCode())>` — [mintAlbumId];
//  7. a favourite write passes the row's `hidden` flag through, never defaults it (`setFavourite`).

/** Most rows one feed page may carry across the bridge — native's `LIBRARY_PAGE_SIZE` byte budget. */
internal const val MAX_FEED_PAGE_SIZE = 500

/** Content ids per `oneOf` predicate — native's `CONTENT_ID_CHUNK`, for the same linear-scan reason. */
internal const val CONTENT_ID_CHUNK = 200

/**
 * The keyset cursor, opaque to TypeScript (`v2.<beforeMs>.<afterPath>`): the position just after the
 * row `(beforeMs, afterPath)` in the total order `(CreatedAt desc, path asc)`.
 *
 * [afterPath] `null` (encoded empty) means "every row AT [beforeMs] is still to come" — the head, and
 * a caller-built window top. A real path is never empty (it starts with `/`), so the two cannot
 * collide; a path may itself contain `.`, so everything after the second separator is the path.
 */
internal data class FeedCursor(val beforeMs: Long, val afterPath: String?) {
    fun encode(): String = "$CURSOR_PREFIX$beforeMs.${afterPath.orEmpty()}"

    companion object {
        private const val CURSOR_PREFIX = "v2."
        val HEAD = FeedCursor(Long.MAX_VALUE, null)

        /** `null`/`""` is the head. A malformed token is `null` here and a typed failure upstream. */
        fun decode(token: String?): FeedCursor? {
            if (token.isNullOrEmpty()) return HEAD
            if (!token.startsWith(CURSOR_PREFIX)) return null
            val rest = token.removePrefix(CURSOR_PREFIX)
            val dot = rest.indexOf('.')
            if (dot < 0) return null
            val before = rest.substring(0, dot).toLongOrNull() ?: return null
            val path = rest.substring(dot + 1)
            if (path.isNotEmpty() && !path.startsWith("/")) return null
            return FeedCursor(before, path.ifEmpty { null })
        }
    }
}

/** One feed row's position in the total order: capture ms, then path. */
internal data class FeedKey(val ms: Long, val path: String)

/** Rule 3's order, for the rows the bridge orders itself. */
internal val FEED_ORDER: Comparator<FeedKey> = compareByDescending<FeedKey> { it.ms }.thenBy { it.path }

/**
 * The cursor for the page after one that answered [rows] (in [FEED_ORDER]), or `null` when that page
 * was the last.
 *
 * ⚠ Computed from the ROWS the walk returned, not from mapped photographs — rule 4 makes the mapping
 * partial, and a short mapped page must not end the feed while rows remain.
 * ⚠ It names the last row, so it counts nothing: a row arriving or leaving at the boundary
 * millisecond moves no other row's position relative to it.
 */
internal fun nextFeedCursor(rows: List<FeedKey>, pageSize: Int): FeedCursor? {
    if (rows.size < pageSize || rows.isEmpty()) return null
    val last = rows.last()
    return FeedCursor(last.ms, last.path)
}

/** Rule 3: is [key] strictly after [cursor] in [FEED_ORDER]? */
internal fun FeedKey.afterCursor(cursor: FeedCursor): Boolean = when {
    ms < cursor.beforeMs -> true
    ms > cursor.beforeMs -> false
    else -> cursor.afterPath == null || path > cursor.afterPath
}

/** Rule 2: the half-open end that makes the window closed at [beforeMs]. */
internal fun exclusiveEndFor(beforeMs: Long): Long =
    if (beforeMs == Long.MAX_VALUE) beforeMs else beforeMs + 1

/**
 * Which media a feed holds: the library (photos and videos), or the Videos collection
 * (web-apps-layout.md L5). A filter on the same walk — the order, the cursor and the window rules are
 * the library's, so a cursor means the same thing in both.
 */
internal enum class FeedKind {
    ALL,
    VIDEOS;

    companion object {
        /** `null`/`''`/`all` → [ALL], `videos` → [VIDEOS]; anything else is not a kind (null). */
        fun parse(raw: String?): FeedKind? = when (raw) {
            null, "", "all" -> ALL
            "videos" -> VIDEOS
            else -> null
        }
    }
}

/** Rule 1: the feed's membership, in rule 3's total order, over `[fromMs, beforeMs]`. */
internal fun TList<File>.feedWindow(beforeMs: Long, fromMs: Long = 0L, kind: FeedKind = FeedKind.ALL): TList<File> = this
    .filter<File> { if (kind == FeedKind.VIDEOS) category equalTo FileCategory.Video else isMedia() }
    .filter<File> { createdAt.inRange(fromMs, exclusiveEndFor(beforeMs)) }
    .filter<File> { notTrashed() }
    .sortByDescending(File.CreatedAt)
    .thenBy { path }

/** What a photo row needs — `TenshuPhotosRepository.feedRowProjection`, same properties. */
internal fun <T : Data> ListBuilder<Selector<T>>.photoRowProjection() {
    // ⚠ `fields(PATH)` is required: the terminal derives each row's DataId from it.
    fields(File.Field.PATH)
    relations(
        File.CreatedAt,
        File.Category,
        File.ContentId,
        File.DurationMs,
        File.CaptureLatE7,
        File.CaptureLngE7,
        File.PixelWidth,
        File.PixelHeight,
    )
}

/**
 * Rule 4: `null` for a row with no capture time. [renditions] — every `PhotoEdit`'s rendition by the
 * original's content id — is required, never defaulted: a caller that forgot it would show an edited
 * photo's original (`photos-editing.md` §12.4).
 */
internal fun Entity<File>.photoOf(renditions: Map<String, String>): PhotoJs? {
    val capturedAt = get(File.CreatedAt)?.get() ?: return null
    val path = this.path.get()
    return PhotoJs(
        path = path,
        name = path.substringAfterLast('/'),
        capturedAtMs = capturedAt.toDouble(),
        isVideo = get(File.Category) == FileCategory.Video,
        durationMs = get(File.DurationMs)?.get()?.toDouble(),
        contentId = get(File.ContentId)?.get().orEmpty(),
        latE7 = get(File.CaptureLatE7)?.get()?.toDouble(),
        lngE7 = get(File.CaptureLngE7)?.get()?.toDouble(),
        pixelWidth = get(File.PixelWidth)?.get()?.toInt(),
        pixelHeight = get(File.PixelHeight)?.get()?.toInt(),
        renditionPath = get(File.ContentId)?.get()?.takeIf { it.isNotEmpty() }?.let { renditions[it] },
    )
}

/**
 * Walk down to [parentPath] one folder at a time so a `search` for the file matches in the right
 * folder (native's private `navigateTo`, a fourth copy — `TenshuPhotosRepository` names the lift).
 * ⚠ A missing folder fails the chain with a `NotFoundError`; [isNotFound] reads that as absence.
 */
internal fun TList<File>.navigateTo(parentPath: String): TList<File> {
    val parent = parentPath.ifEmpty { "/" }
    if (parent == "/") return this
    var list = this
    var prefix = ""
    for (segment in parent.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}

/** A by-path read that failed because the path does not exist — an answer (`null`), not a fault. */
internal fun TError.isNotFound(): Boolean = this is NotFoundError

/** Rule 5. */
internal fun trashPathFor(contentId: String, name: String): String = "$TRASH_ROOT/$contentId-$name"

/** Rule 6 — `mintAlbumId`. ⚠ Pinned: two names in one millisecond must differ. */
internal fun mintAlbumId(clockMs: Long, name: String): String =
    "album-$clockMs-${name.hashCode().toUInt().toString(16)}"

/** The catalogue keys this surface mints itself; every other failure carries its derived key. */
internal object PhotosWebErrorKeys {
    /** A malformed feed cursor — a caller bug, said loudly. */
    val BAD_CURSOR = ErrorCopyKey("error_photos_bad_cursor")
    /** `pageSize` outside `1..MAX_FEED_PAGE_SIZE` — never ship the whole library in one message. */
    val BAD_PAGE_SIZE = ErrorCopyKey("error_photos_bad_page_size")
    /** A feed `kind` other than `all` / `videos` — a caller bug, said loudly (never "everything"). */
    val BAD_KIND = ErrorCopyKey("error_photos_bad_kind")
    /** An album id no `Album` row has. Writes would otherwise no-op (or orphan entries) silently. */
    val UNKNOWN_ALBUM = ErrorCopyKey("error_photos_unknown_album")
    /** A path with no media file at it, for an op that needs one (add to album, trash). */
    val NOT_FOUND = ErrorCopyKey("error_photos_not_found")
    /** A file predating content addressing cannot be filed (membership is keyed on the id). */
    val NO_CONTENT_ID = ErrorCopyKey("error_photos_no_content_id")
    /** A content id with no trash record — restore / delete-forever have nothing to act on. */
    val NOT_IN_TRASH = ErrorCopyKey("error_photos_not_in_trash")
    /** A path that is not a photograph (not image/video) or already in `/Trash`, for trash / add. */
    val NOT_A_PHOTO = ErrorCopyKey("error_photos_not_a_photo")
    /** A file-system step (move, delete, create `/Trash`) refused without a typed reason. */
    val FILE_OP_FAILED = ErrorCopyKey("error_photos_file_op_failed")
}

internal fun photosFailure(error: TError): dynamic = failureResult(error.errorCopyKey().value, error.toString())

internal fun photosFailure(key: ErrorCopyKey, message: String? = null): dynamic = failureResult(key.value, message)

@JsExport
class PhotoJs internal constructor(
    val path: String,
    val name: String,
    /** Epoch ms. */
    val capturedAtMs: Double,
    val isVideo: Boolean,
    val durationMs: Double?,
    /** `""` for a file predating content addressing — it can be shown, not filed. */
    val contentId: String,
    val latE7: Double?,
    val lngE7: Double?,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    /**
     * ED-3 (`photos-editing.md` §12): where this photo's non-destructive edit is baked, or `null`.
     * ⚠⚠ A tile or viewer draws THIS when it is set — never [path], which for a redacted edit is the
     * very picture the edit hides (§12.4).
     */
    val renditionPath: String?,
)

@JsExport
class PhotoAlbumJs internal constructor(
    val albumId: String,
    val name: String,
    val createdAtMs: Double,
    val photoCount: Int,
    /** The cover's path: the chosen cover, else the first entry; `null` for an empty album. */
    val coverPath: String?,
)

@JsExport
class TrashedPhotoJs internal constructor(
    val contentId: String,
    val photo: PhotoJs,
    val originalPath: String,
    val trashedAtMs: Double,
)

@JsExport
class PhotoDetailsJs internal constructor(
    val path: String,
    val capturedAtMs: Double?,
    val sizeBytes: Double?,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val orientation: Int?,
    val cameraMake: String?,
    val cameraModel: String?,
    val durationMs: Double?,
    val latE7: Double?,
    val lngE7: Double?,
)
