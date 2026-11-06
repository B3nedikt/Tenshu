package app.tenshu.takeout

import app.tenshu.takeout.zip.ZipEntry

/**
 * What one entry in a Takeout export is.
 *
 * ⚠ [UNKNOWN] is a first-class outcome, not an error: an export holds Chrome history, YouTube
 * watch logs and a dozen other things Tenshu deliberately does not import (TB-D7). The point of
 * naming it is that it gets **counted** rather than dropped in silence.
 */
enum class TakeoutEntryKind {
    /** A Google Timeline / Location History export — either vintage. */
    TIMELINE,

    /** A photo or video the user exported. */
    PHOTO,

    /** A photo's `.json` metadata sidecar — ⚠ authoritative over the image's own EXIF (TB-D4). */
    PHOTO_SIDECAR,

    /** In the export, and not something we import. */
    UNKNOWN,
}

/** One classified entry. */
data class TakeoutEntry(val zip: ZipEntry, val kind: TakeoutEntryKind) {
    val name: String get() = zip.name
}

/**
 * What a scan of an archive found — the thing an import screen reports (TB-D2).
 *
 * ⚠⚠ [unmatched] exists because "understood 0 of 412 files" is a bug report a user can send us and
 * a blank timeline is not. It carries a handful of *names*, not a count alone: the first question
 * anyone asks about "32 files skipped" is *which ones*.
 */
data class TakeoutScan(
    val entries: List<TakeoutEntry>,
    val unmatched: List<String>,
) {
    val total: Int get() = entries.size

    fun of(kind: TakeoutEntryKind): List<TakeoutEntry> = entries.filter { it.kind == kind }

    val understood: Int get() = entries.count { it.kind != TakeoutEntryKind.UNKNOWN }
}

/**
 * Classifies a Takeout archive's entries (B1, TB-D2).
 *
 * ⚠⚠ **The path is a hint. It is never the decision.** Google localises the folder names to the
 * account's language — a German export says `Standortverlauf`, not `Location History` — so a
 * path-only router imports **nothing** from every non-English export *and reports success*. The
 * extension and, for JSON, the content are what actually decide.
 *
 * ⚠ Which is why [classify] takes a `peek` lambda rather than bytes: sniffing costs one read per
 * *candidate* JSON, and an export has few of those beside thousands of photos it never opens.
 */
object TakeoutRouter {

    /**
     * @param peek reads the first bytes of an entry (the caller decides how many); `null` when the
     *   entry cannot be read at all. Only called for entries whose name alone is ambiguous.
     */
    fun classify(entries: List<ZipEntry>, peek: (ZipEntry) -> ByteArray?): TakeoutScan {
        val classified = entries.map { entry ->
            val byName = kindOfName(entry.name)
            TakeoutEntry(entry, byName ?: kindOfContent(peek(entry)))
        }
        return TakeoutScan(
            entries = classified,
            unmatched = classified.filter { it.kind == TakeoutEntryKind.UNKNOWN }
                .map { it.name }
                .take(UNMATCHED_SAMPLE),
        )
    }

    /**
     * What the **name alone** settles, or `null` when only the content can say.
     *
     * ⚠⚠ `null` means "read this one", so it is the whole cost model of a streaming walk: an export
     * is thousands of photos beside a handful of JSONs, and only the JSONs come back `null` here.
     */
    fun kindOfName(path: String): TakeoutEntryKind? {
        val name = path.substringAfterLast('/').lowercase()
        val extension = name.substringAfterLast('.', "")

        if (extension in PHOTO_EXTENSIONS) return TakeoutEntryKind.PHOTO
        if (extension != "json") return TakeoutEntryKind.UNKNOWN

        // ⚠ A photo sidecar is named after the photo it describes — `IMG_1234.jpg.json`, and in
        // newer exports `IMG_1234.jpg.supplemental-metadata.json`. Deciding by the *inner*
        // extension costs nothing and needs no read.
        val inner = name.removeSuffix(".json").substringAfterLast('.', "")
        if (inner in PHOTO_EXTENSIONS) return TakeoutEntryKind.PHOTO_SIDECAR
        if (name.contains(SUPPLEMENTAL) &&
            name.substringBefore(SUPPLEMENTAL).trimEnd('.').substringAfterLast('.', "") in PHOTO_EXTENSIONS
        ) {
            return TakeoutEntryKind.PHOTO_SIDECAR
        }

        // ⚠⚠ Everything else that is JSON gets sniffed, never matched on its folder. This is the
        // localisation trap: `Standortverlauf/2023_JANUAR.json` is a timeline export and its path
        // says so in a language we do not read.
        return null
    }

    /**
     * What an entry's **first bytes** say. `null` bytes — unreadable — is [TakeoutEntryKind.UNKNOWN]
     * rather than a guess from the folder, which is exactly what TB-D2 forbids.
     */
    fun kindOfContent(head: ByteArray?): TakeoutEntryKind {
        val text = head?.decodeToString() ?: return TakeoutEntryKind.UNKNOWN
        return if (TIMELINE_MARKERS.any { it in text }) {
            TakeoutEntryKind.TIMELINE
        } else {
            TakeoutEntryKind.UNKNOWN
        }
    }

    /** ⚠ Enough names for a user to recognise a pattern; not enough to be a wall of text. */
    const val UNMATCHED_SAMPLE = 10

    /**
     * The two vintages' own top-level keys — the same pair `GoogleTimelineParser` sniffs for
     * (`timeline-ingestion.md` §3.1), so the router and the parser agree on what a timeline file is
     * by construction rather than by coincidence.
     */
    private val TIMELINE_MARKERS = listOf("\"semanticSegments\"", "\"timelineObjects\"")

    private const val SUPPLEMENTAL = "supplemental-meta"

    private val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "mp4", "mov", "avi",
    )

}
