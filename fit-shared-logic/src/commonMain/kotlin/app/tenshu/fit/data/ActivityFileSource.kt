package app.tenshu.fit.data

/**
 * A **lazily-read** stream of picked files for import.
 *
 * The point is memory. A Google Takeout export is thousands of files (one real one: 2,690 usable
 * members, ~96 MB of bytes); reading them all into a `List<ActivityFile>` before importing — the
 * obvious shape — holds every byte at once and OOMs on exactly the export a long-time user has. So
 * the shell yields one [ActivityFile] at a time: it reads a member's bytes just before calling
 * [forEach]'s consumer, and the consumer parses + persists + drops it before the next is read. Peak
 * memory is one file, not the whole archive.
 *
 * [count] is best-effort — the number of members if known up front (a directory scan), or null when
 * it cannot be known without draining the source (a streamed zip). It drives nothing but a nicer
 * progress read.
 *
 * [displayName] and [totalBytes] describe the **archive**, not its members, and exist so a batch
 * import can be recorded as a fact the user recognises — *"takeout-20260801.zip, 91 MB, 3 Aug"*
 * (`fit-data-sources-restructure.md` D6). Every shell has both at the pick; before DS-2 nothing
 * asked for them, so they were dropped at this seam. ⚠ Both are optional: a source assembled
 * from members that never were an archive (a multi-select of loose GPX files) honestly has
 * neither, and a run record with a null size is better than one with an invented one.
 */
fun interface ActivityFileSource {

    /** Yields each file to [consume] in turn, reading its bytes lazily. Suspends across the walk. */
    suspend fun forEach(consume: suspend (ActivityFile) -> Unit)

    /** Members up front, or null if unknown without consuming the source. */
    val count: Int? get() = null

    /** The picked archive's name as the user saw it, or null if this is not one archive. */
    val displayName: String? get() = null

    /** The picked archive's size in bytes, or null if unknown. */
    val totalBytes: Long? get() = null

    companion object {
        /** An already-materialised list as a source — for the single-file pick, where memory is a non-issue. */
        fun of(
            files: List<ActivityFile>,
            archiveName: String? = files.singleOrNull()?.name,
            archiveBytes: Long? = files.sumOf { it.bytes.size.toLong() }.takeIf { files.isNotEmpty() },
        ): ActivityFileSource = object : ActivityFileSource {
            override val count: Int get() = files.size
            override val displayName: String? get() = archiveName
            override val totalBytes: Long? get() = archiveBytes
            override suspend fun forEach(consume: suspend (ActivityFile) -> Unit) = files.forEach { consume(it) }
        }
    }
}
