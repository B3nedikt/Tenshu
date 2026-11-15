package app.tenshu.photos.data

/**
 * The paths an edited copy of [originalPath] may take, best first: `IMG_1.HEIC` →
 * `IMG_1-edited.jpg`, `IMG_1-edited-2.jpg`, … in the same folder.
 *
 * ⚠⚠ A **list of candidates**, never one name: the upload refuses a name that exists, and the
 * caller moves on to the next rather than overwrite (ED-D1). Always `.jpg`, whatever the original
 * was — the copy is re-encoded as JPEG (`photos-creation-features.md` §9).
 */
fun editedCopyNames(originalPath: String, limit: Int = MAX_EDITED_COPY_NAMES): Sequence<String> {
    val folder = originalPath.substringBeforeLast('/', missingDelimiterValue = "")
    val name = originalPath.substringAfterLast('/')
    val stem = name.substringBeforeLast('.').ifEmpty { name }
    return (1..limit).asSequence().map { n ->
        val suffix = if (n == 1) "-edited" else "-edited-$n"
        "$folder/$stem$suffix.jpg"
    }
}

/** How many names a save tries before it gives up. ⚠ A bound, so a refusing proxy cannot loop it. */
const val MAX_EDITED_COPY_NAMES = 100

/**
 * Where a non-destructive edit's rendition is uploaded (`photos-editing.md` §12.3, ED-D10):
 * `/Trash/.edits/<contentId>-<stamp>.jpg`, under the trash so every photo reader already skips it.
 *
 * ⚠⚠ **A name is never used twice** — [newRenditionStamp] is the save's time plus a random part.
 * Every preview cache is keyed by path (Compose's `PreviewCache`, iOS's tile cache, a peer's), so a
 * name freed by a deleted rendition and taken again would draw the OLD edit from cache — a third
 * edit that added a redaction shown as the first one without it. ⚠ And a fresh name per save rather
 * than a fixed one per photo, because a re-edit's rendition must exist before the old one is deleted
 * (§12.4's order).
 */
fun editRenditionPathFor(contentId: String, stamp: String): String =
    "${org.tenshu.core.file.EDIT_RENDITIONS_ROOT}/$contentId-$stamp.jpg"

/** A rendition name's never-reused part: epoch ms, then 24 random bits. */
fun newRenditionStamp(nowMs: Long): String =
    "$nowMs-" + kotlin.random.Random.nextInt(1 shl 24).toString(16).padStart(6, '0')
