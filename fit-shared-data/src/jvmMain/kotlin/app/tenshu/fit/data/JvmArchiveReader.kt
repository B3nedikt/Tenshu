package app.tenshu.fit.data

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads the usable members of a Takeout archive on the JVM (Android + desktop) — `java.util.zip`, no
 * dependency, and **truly streaming**: one member is in memory at a time.
 *
 * A Takeout export is whatever the user ticked; it can be tens of gigabytes of photos and mail with
 * the Fit folder somewhere inside. So entries are decided by name *before* their bytes are read
 * ([TakeoutParser.isRelevant]), and each surviving member is handed to the consumer and then dropped
 * before the next is read. The `List`-returning shape this replaced held all ~96 MB of a real
 * 10-year export's usable members at once — this holds one file.
 *
 * A per-member size cap guards the remaining hole: a `.csv` entry is normally a few hundred KB, but
 * nothing stops an archive from claiming otherwise, and this runs in the app's process.
 */
object JvmArchiveReader {

    /** A single member's uncompressed bytes are refused past this — see the class note. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    // Cap on how many members we read, so a pathological archive cannot exhaust the import by count.
    // Set well above a real export: a genuine 10-year daily Takeout is ~2,700 activities once the
    // per-day segment CSVs are filtered out ([TakeoutParser.isRelevant]), so this leaves generous
    // headroom for a heavier history while still bounding a malicious archive.
    private const val MAX_ENTRIES = 50_000

    /**
     * A streaming [ActivityFileSource] over the zip on [openStream]. Each `forEach` opens a fresh
     * stream (so the source is re-consumable) and yields one member at a time, closing the stream
     * after. A truncated or oversized member is skipped, not fatal — the rest of the import stands.
     *
     * [openStream] is a factory, not a stream, precisely so `forEach` controls the stream's lifetime:
     * a zip is read strictly front-to-back, so the caller must not touch it, and this owns the
     * open→consume→close cycle. `count` is null — a streamed zip's member count is unknown without
     * draining it, and draining twice to learn it defeats the point.
     */
    fun source(
        openStream: () -> InputStream,
        isRelevant: (String) -> Boolean,
        /** The archive as the user picked it — recorded on the import run (DS-2 / D6). */
        archiveName: String? = null,
        archiveBytes: Long? = null,
    ): ActivityFileSource = object : ActivityFileSource {

        override val displayName: String? get() = archiveName
        override val totalBytes: Long? get() = archiveBytes

        override suspend fun forEach(consume: suspend (ActivityFile) -> Unit) {
            var kept = 0
            ZipInputStream(openStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (kept >= MAX_ENTRIES) {
                        fitLog("archive: stopping at the $MAX_ENTRIES-member cap")
                        break
                    }
                    val name = entry.name
                    if (entry.isDirectory || !isRelevant(name)) {
                        zip.closeEntry()
                        continue
                    }
                    // entry.size is -1 for a streamed zip, so it cannot be trusted as the guard; read
                    // with a hard ceiling and drop the member if it runs past it.
                    val bytes = zip.readAtMost(MAX_ENTRY_BYTES)
                    zip.closeEntry()
                    if (bytes == null) {
                        fitLog("archive: $name exceeds ${MAX_ENTRY_BYTES / 1024 / 1024} MiB — skipped")
                        continue
                    }
                    kept++
                    // The member's bytes are collectable once consume returns and before the next read.
                    consume(ActivityFile(name = name, bytes = bytes))
                }
            }
            fitLog("archive: streamed $kept usable member(s)")
        }
    }

    /** Reads the current entry, or null if it runs past [limit]. */
    private fun InputStream.readAtMost(limit: Long): ByteArray? {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val acc = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            acc.write(buffer, 0, read)
        }
        return acc.toByteArray()
    }
}
