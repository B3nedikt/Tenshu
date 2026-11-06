package app.tenshu.drive.data

/**
 * Data-layer projection for the "Details & Activity" screen
 * (docs/proposals/drive-file-details-screen.md §4). [file] carries the header
 * (icon + name) via the same [FileMapper.map] the listing uses; the metadata is
 * pre-formatted for display, consistent with how [FileModel.subLine] is already
 * a formatted string.
 *
 * Phase 2 covers the metadata only; the activity history (Phase 3) is layered on
 * later once a consumer exists.
 */
data class FileDetailsModel(
    val file: FileModel,
    /** Human-readable byte size (e.g. "1.2 MB"); "0 B" for folders. */
    val size: String,
    /** Localised creation timestamp; empty when not yet recorded. */
    val created: String,
    /** Localised last-modified timestamp; empty when not yet recorded. */
    val lastModified: String,
)
