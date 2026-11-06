package app.tenshu.takeout

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A Takeout archive read **as a stream**, front to back — `java.util.zip`, no seeking, one entry in
 * memory at a time (B2).
 *
 * ⚠⚠ **This, not the seekable reader, is what Android can actually use.** The Storage Access
 * Framework hands out a `content://` stream that cannot seek, and a ZIP's index lives at the *end*
 * of the file — so the alternative was copying a multi-gigabyte export into cache before reading a
 * byte of it. Streaming skips that entirely.
 *
 * ⚠ [openStream] is a **factory**, not a stream: a zip is read strictly front-to-back, so each walk
 * opens its own and closes it after. That also makes the archive re-walkable.
 *
 * ⚠ The two caps below are Fit's, learned on a real 10-year export (`JvmArchiveReader`): a member's
 * declared size is `-1` in a streamed zip, so it cannot be the guard, and this runs in the app's
 * process on whatever file the user picked.
 */
class StreamingTakeoutArchive(
    private val openStream: () -> InputStream,
) : TakeoutArchive {

    override suspend fun walk(
        wanted: (String) -> Boolean,
        consume: suspend (String, ByteArray?) -> Unit,
    ) {
        var seen = 0
        ZipInputStream(openStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (seen >= MAX_ENTRIES) break
                seen++
                val name = entry.name
                if (entry.isDirectory || !wanted(name)) {
                    zip.closeEntry()
                    continue
                }
                // ⚠ Read with a hard ceiling rather than trusting the header: `entry.size` is -1
                // here, and an archive is free to claim anything anyway.
                val bytes = zip.readAtMost(MAX_ENTRY_BYTES)
                zip.closeEntry()
                // A member past the ceiling is reported as unreadable, not skipped in silence —
                // the summary counts it, which is the whole point of TB-D2's honest report.
                consume(name, bytes)
            }
        }
    }

    private companion object {
        /**
         * ⚠ A timeline export's monthly file is single-digit MB; this leaves an order of magnitude
         * of headroom and still bounds what one entry can do to the process.
         */
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        /** A photo export legitimately holds a few hundred thousand entries; a hostile one does not. */
        const val MAX_ENTRIES = 500_000
    }
}

/** Reads until the stream ends, or gives up with `null` past [ceiling] bytes. */
private fun InputStream.readAtMost(ceiling: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > ceiling) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
