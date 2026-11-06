package org.tenshu.core.file

/**
 * Derives a [FileCategory] from a file's name by its extension.
 *
 * Drive computes the category from the name at read time rather than persisting
 * it on the entity. The mapping is pure, needs no backfill for already-uploaded
 * files, and adds nothing to the wire — see
 * `docs/proposals/drive-content-preview.md` §3 / Q1. (`File.Category` remains a
 * vestigial *constant* relation: it is registered without a value factory, so it
 * cannot carry a per-file value without a schema/wire change; that change isn't
 * worth its cost while the extension mapping is sufficient.)
 *
 * Used both for the list-row glyph ([FileMapper]) and to route a tapped file to
 * the right preview surface (the image viewer today; PDF / video later). The
 * extensions deliberately cover only what a renderer can actually handle:
 * `Image` lists raster formats the platform bitmap decoders accept (SVG is
 * excluded — it would fail to decode and is better routed to the fallback).
 */
fun fileCategoryForName(name: String): FileCategory {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" ->
            FileCategory.Image

        "mp4", "mov", "webm", "mkv", "avi", "m4v", "3gp" ->
            FileCategory.Video

        "mp3", "wav", "flac", "aac", "ogg", "oga", "m4a" ->
            FileCategory.Audio

        "pdf", "txt", "md", "rtf",
        "doc", "docx", "ppt", "pptx", "xls", "xlsx",
        "odt", "ods", "odp" ->
            FileCategory.Document

        "zip", "tar", "gz", "tgz", "rar", "7z", "bz2", "xz" ->
            FileCategory.Archive

        else -> FileCategory.Other
    }
}

/**
 * Whether [name] is a PDF, by extension. PDF is a [FileCategory.Document] (along
 * with txt/docx/…), but it's the one document type Drive renders in-app
 * (`drive-content-preview.md` §4), so the router needs to single it out from the
 * rest of the Document category.
 */
fun isPdf(name: String): Boolean =
    name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)
