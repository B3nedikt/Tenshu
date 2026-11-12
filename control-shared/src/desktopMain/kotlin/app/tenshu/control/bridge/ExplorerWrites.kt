package app.tenshu.control.bridge

import java.util.Locale

/**
 * What the folder holds that the realm does not have yet (desktop-windows-port.md §7.10, X3): pure, so the rules are
 * tested without Windows. The I/O, and the one rule that matters most there, is `ExplorerSyncRoot`'s.
 *
 * Two steps, because the second needs the bytes: [candidates] picks the entries worth a look from the scan; once the
 * file is held unshared and hashed, [decide] says what to do with it.
 */
internal object ExplorerWrites {

    enum class Kind {
        /** An ordinary file where the realm has nothing. */
        NEW_FILE,

        /** An ordinary folder where the realm has nothing. */
        NEW_FOLDER,

        /** An ordinary file where the realm has a file: a leftover, or an editor's save-by-rename. */
        ORDINARY_OVER_ROW,

        /** One of our placeholders, saved into in place (no longer in sync). */
        EDITED_PLACEHOLDER,
    }

    /**
     * [base]: the realm version the local file was last known to be — the edited placeholder's identity, or for an
     * ordinary file the journal's entry for its path. `null` when nothing is known.
     */
    data class Candidate(
        val relative: String,
        val kind: Kind,
        val row: ExplorerMirror.Remote?,
        val base: String?,
        /** An edited placeholder with no local copy: only its metadata changed, so there are no bytes to look at. */
        val online: Boolean = false,
    )

    enum class Decision {
        /** The bytes are the realm's already: make it ours (ordinary) or mark it in sync again (edited). */
        ALREADY_THERE,

        /** Upload at its path as a new file. */
        UPLOAD_NEW,

        /** Upload over the realm's copy: it is still the version this one was made from. */
        REPLACE,

        /** The realm's copy moved on meanwhile: upload beside it (D-X3-2), then let the realm's take the path. */
        CONFLICT_COPY,
    }

    /**
     * The entries of [local] that may need writing, shallowest first so folders exist before their contents.
     * Skipped: anything [ignored]; anything modified less than [settleMillis] ago (still being written — a folder
     * gets longer, as Explorer names a new one only after creating it); and a file where the realm has a folder or the
     * reverse, which no write here can settle.
     */
    fun candidates(
        remote: List<ExplorerMirror.Remote>,
        local: List<ExplorerMirror.Local>,
        journal: Map<String, String>,
        nowMillis: Long,
        settleMillis: Long = FILE_SETTLE_MILLIS,
        folderSettleMillis: Long = FOLDER_SETTLE_MILLIS,
    ): List<Candidate> {
        val rows = remote.associateBy { key(it.path.trim('/')) }
        val out = ArrayList<Candidate>()
        for (entry in local) {
            if (ignored(entry)) continue
            val settle = if (entry.isFolder) folderSettleMillis else settleMillis
            if (nowMillis - entry.modifiedAtMillis < settle) continue
            val key = key(entry.relative)
            val row = rows[key]
            when {
                entry.isFolder -> if (!entry.ours && row == null) out += Candidate(entry.relative, Kind.NEW_FOLDER, null, null)
                row != null && row.isFolder -> continue
                !entry.ours -> out += if (row == null) {
                    Candidate(entry.relative, Kind.NEW_FILE, null, null)
                } else {
                    Candidate(entry.relative, Kind.ORDINARY_OVER_ROW, row, journal[key])
                }
                !entry.isPlaceholder -> out += Candidate(entry.relative, Kind.EDITED_PLACEHOLDER, row, entry.contentVersion, entry.online)
            }
        }
        return out.sortedBy { it.relative.count { c -> c == '/' } }
    }

    /**
     * How long until the freshest entry that may need writing has settled, or `null` when none is waiting.
     * ⚠ The folder watch triggers a pass at once, inside the settle window, where the entry is skipped; without a
     * pass scheduled for when it has settled, an edit waited for the 60 s repair tick (measured).
     */
    fun untilSettled(local: List<ExplorerMirror.Local>, nowMillis: Long): Long? =
        local.asSequence()
            .filter { !ignored(it) && !(it.ours && it.isPlaceholder) }
            .map { (if (it.isFolder) FOLDER_SETTLE_MILLIS else FILE_SETTLE_MILLIS) - (nowMillis - it.modifiedAtMillis) }
            .filter { it > 0 }
            .minOrNull()

    /**
     * What to do once the bytes are known. [sameBytes]: they hash to the row's content hash.
     * ⚠⚠ Only [Decision.REPLACE] overwrites the realm, and only when the realm still holds exactly the version the
     * local file was made from; without that proof the edit goes beside it ([Decision.CONFLICT_COPY]).
     */
    fun decide(candidate: Candidate, sameBytes: Boolean): Decision {
        val row = candidate.row ?: return Decision.UPLOAD_NEW
        if (sameBytes) return Decision.ALREADY_THERE
        return if (candidate.base != null && candidate.base == row.contentVersion) Decision.REPLACE else Decision.CONFLICT_COPY
    }

    /**
     * `name (conflict, <computer>).ext`, numbered when that is taken too. [taken] holds the names in the folder,
     * compared as NTFS does (case-insensitively).
     */
    fun conflictName(name: String, computer: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$stem (conflict, $computer)$ext" else "$stem (conflict, $computer) $n$ext"
            if (candidate.lowercase(Locale.ROOT) !in lower) return candidate
            n++
        }
    }

    /** Never uploaded: hidden or system entries, and the temp and lock files editors and browsers leave about. */
    fun ignored(entry: ExplorerMirror.Local): Boolean {
        if (entry.hidden) return true
        val name = entry.relative.substringAfterLast('/').lowercase(Locale.ROOT)
        if (name == "desktop.ini" || name == "thumbs.db") return true
        if (name.startsWith("~$") || name.startsWith(".~lock.") || name.startsWith("~wr")) return true
        return IGNORED_SUFFIXES.any { name.endsWith(it) }
    }

    private val IGNORED_SUFFIXES = listOf(".tmp", ".temp", ".crdownload", ".partial", ".part", ".download")

    /** A file still being written is left for a later pass. */
    const val FILE_SETTLE_MILLIS = 3_000L

    /** Explorer creates "New folder" and names it a moment later: wait for the name. */
    const val FOLDER_SETTLE_MILLIS = 15_000L

    private fun key(relative: String) = relative.lowercase(Locale.ROOT)
}
