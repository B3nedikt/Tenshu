package app.tenshu.drive.data

import org.tenshu.core.file.FileCategory

/**
 * Platform-neutral file glyph. The logic layer (models, mappers, view models)
 * carries this enum instead of a Compose `ImageVector` so it can compile on iOS
 * (proposal `ios-proxy-architecture.md` §9.3). Each UI maps it to its own icon
 * type — Compose `ImageVector` via `FileIcon.asImageVector()` (`:drive-shared`),
 * SF Symbols on SwiftUI.
 */
enum class FileIcon {
    /** Root folder. */
    Home,
    Folder,
    Audio,
    /** Text/document category (distinct from the generic [GenericFile] glyph). */
    Document,
    Image,
    Video,
    Archive,
    /** Uncategorised content. */
    Other,
    /** Generic file glyph used where no category is known. */
    GenericFile,
    Pdf,
}

/** Maps a [FileCategory] to the platform-neutral [FileIcon] glyph used in lists
 *  and in the fallback preview surface. Shared by [FileMapper] and the
 *  unsupported-preview screen so both pick the same glyph. */
fun FileCategory.toFileIcon(): FileIcon = when (this) {
    FileCategory.Audio -> FileIcon.Audio
    FileCategory.Document -> FileIcon.Document
    FileCategory.Image -> FileIcon.Image
    FileCategory.Video -> FileIcon.Video
    FileCategory.Archive -> FileIcon.Archive
    FileCategory.Other -> FileIcon.Other
}
