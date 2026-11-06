package app.tenshu.drive.data

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.id
import org.tenshu.core.common.util.TimestampUtil
import org.tenshu.core.common.util.formatTimestamp
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCount
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.contentModifiedAtOrCreated
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.Preview
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.Size
import org.tenshu.core.file.path
import org.tenshu.core.time.Instant
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

class FileMapper {

    fun map(file: Entity<File>): FileModel {
        val typeId = file.type.get()
        val path = file.path.get()
        val name = path.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: "Root"

        return when (typeId) {
            Folder.ID -> {
                FileModel.Folder(
                    path = path,
                    name = name,
                    // Child count via the `Folder.FileCount` relation — resolved
                    // proxy-side from the array's member-hash list. Reading it
                    // off the projected `files` FIELD instead materialises and
                    // ships every child entity just for `.size` (a full-array
                    // deserialisation per folder row + the whole child list
                    // over IPC).
                    subLine = (file.get(Folder.FileCount)?.get() ?: 0L).toString() + " files",
                    previewIcon = FileIcon.Folder,
                )
            }

            else -> {
                // `File.Preview` is the single "best available preview" API: the
                // `GetPreview` resolver computes from local bytes where present and
                // otherwise falls back to the stored, eagerly-synced `File.Thumbnail`
                // (present on every device, including byte-less ones). The
                // thumbnail-vs-live selection moved DOWN into the resolver — the
                // consumer no longer reads `File.Thumbnail` directly
                // (drive-synced-thumbnails.md §10.1). Non-null only when the caller's
                // projection asked for `File.Preview` — today NO listing does:
                // `getFilesPaged` dropped it (per-row preview resolution inside the
                // projection blocked the page paint — see the note there), so every
                // list surface loads thumbnails via the per-row [HorizontalFileItem]
                // loader and this reads null → the row starts on its category icon.
                val previewBytes = file.get(File.Preview)?.getOrNull()
                FileModel.File(
                    path = path,
                    name = name,
                    // The file's own "Modified" date — the one the MODIFIED sort orders by
                    // and [mapDetails] shows, never the import moment: a photo backed up
                    // today reads as the day it was taken. `LastModifiedAt` ("last touched
                    // in the realm") is only the fallback for a row with neither date, and
                    // still keys the recent-activity feed.
                    subLine = formatFileSize(file.get(File.Size)?.get() ?: 0L)
                            + ((contentModifiedAtOrCreated(
                                file.get(File.ContentModifiedAt)?.get(), file.get(File.CreatedAt)?.get(),
                            ) ?: file.get(File.LastModifiedAt)?.get())
                                ?.let { " · " + TimestampUtil.formatTimestamp(it) } ?: ""),
                    // Category (hence the glyph) is derived from the filename
                    // extension rather than read from `File.Category` (a vestigial
                    // constant relation that never carries a per-file value — see
                    // `fileCategoryForName`). PDF gets its own glyph since it's the
                    // one Document type Drive previews in-app.
                    previewIcon = if (isPdf(name)) FileIcon.Pdf else fileCategoryForName(name).toFileIcon(),
                    previewBytes = previewBytes,
                )
            }
        }
    }

    /**
     * Projection for the FileDetailsScreen metadata section: the [map] header
     * plus separately-formatted size / created / last-modified (the listing's
     * [map] folds size + modified into one `subLine`; the details screen wants
     * them as discrete rows). Empty strings when a timestamp isn't recorded yet
     * (e.g. files created before `createdAt` existed).
     */
    fun mapDetails(file: Entity<File>): FileDetailsModel = FileDetailsModel(
        file = map(file),
        size = formatFileSize(file.get(File.Size)?.get() ?: 0L),
        created = file.get(File.CreatedAt)?.get()?.let { TimestampUtil.formatTimestamp(it) }.orEmpty(),
        // The file's own date, not the moment it was uploaded or moved: a photo backed up today
        // was not modified today. ⚠ A folder has no content date and keeps "last touched".
        lastModified = (
            if (file.type.get() == Folder.ID) file.get(File.LastModifiedAt)?.get()
            else contentModifiedAtOrCreated(
                file.get(File.ContentModifiedAt)?.get(), file.get(File.CreatedAt)?.get(),
            )
            )?.let { TimestampUtil.formatTimestamp(it) }.orEmpty(),
    )

    /**
     * Maps a [FileModification] to a [FileActivity] row for the activity section.
     * The subtype (the type id) drives the [FileActivityKind] + detail string;
     * `ContentChanged` (and any future subtype) renders as a generic "Modified".
     * The authoring device's name is resolved via the `FileModification.Device`
     * reference property (null → the row falls back to the raw `deviceId`).
     *
     * Takes [Data] (not `Entity`) because the filtered modification log
     * materialises items as either an `Entity` or a plain `Composite` — fields are
     * read through whichever shape applies (the `FileModificationEmitTest` pattern).
     */
    fun mapActivity(modification: Data): FileActivity {
        val typeId = (modification as? Entity<*>)?.type?.get() ?: modification.id
        fun field(id: String): String = when (modification) {
            is Entity<*> -> modification.getFieldValue<TString>(id).get()
            is Composite -> modification.getField<TString>(id).get()
            else -> error("Unexpected modification shape: $modification")
        }
        val timestampMillis = when (modification) {
            is Entity<*> -> modification.getFieldValue<Instant>(FileModification.Field.TIMESTAMP)
            is Composite -> modification.getField<Instant>(FileModification.Field.TIMESTAMP)
            else -> error("Unexpected modification shape: $modification")
        }.toEpochMilliseconds()

        return FileActivity(
            kind = when (typeId) {
                CreatedFileModification.ID -> FileActivityKind.Created
                RenamedFileModification.ID -> FileActivityKind.Renamed
                MovedFileModification.ID -> FileActivityKind.Moved
                else -> FileActivityKind.Modified
            },
            detail = when (typeId) {
                RenamedFileModification.ID ->
                    field(RenamedFileModification.Field.OLD_NAME) + " → " +
                        field(RenamedFileModification.Field.NEW_NAME)

                MovedFileModification.ID -> "to " + field(MovedFileModification.Field.NEW_PARENT_PATH)

                else -> null
            },
            timestampMillis = timestampMillis,
            deviceId = field(FileModification.Field.DEVICE_ID),
            deviceName = (modification as? Entity<*>)?.get(FileModification.Device)?.name?.get(),
        )
    }

    private fun formatFileSize(sizeInBytes: Long): String = formatByteSize(sizeInBytes)

    /**
     * KMP-friendly replacement for the JVM `DecimalFormat("#,##0.#")`: rounds to
     * at most one decimal place and drops a trailing `.0`. (No grouping
     * separators — file-size values are small enough that grouping never showed.)
     */
    private fun formatUpToOneDecimal(value: Double): String {
        val scaled = (value * 10).roundToLong()
        val whole = scaled / 10
        val frac = (scaled % 10).toInt()
        return if (frac == 0) whole.toString() else "$whole.$frac"
    }
}
