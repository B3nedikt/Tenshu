package app.tenshu.control.bridge

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which realm version Control last put at each path of the Explorer folder (desktop-windows-port.md §7.10), the
 * content hash of that version when known, and the file's size and modification time as Control left it.
 *
 * ⚠ Needed because editors save by writing a temp file and renaming it over the original: what is left is an
 * ordinary file that has lost the placeholder's identity, and with it any record of which version it was edited
 * from. With the journal, "the realm still holds the version this was made from" can be told from "it moved on
 * meanwhile" — an upload over it versus a conflict copy. Without an entry the answer is always the conflict copy.
 * The hash lets a downloaded copy that is still what it was be told from one edited in place; the size and time
 * ([Stamp]) say which copies are worth hashing at all (§7.10).
 *
 * Kept on disk so a restart does not turn every pending save into a conflict. [file] `null`: memory only (tests).
 */
internal class ExplorerJournal(private val file: File?) {
    /** A file's size and modification time (epoch millis) as Control left it. */
    data class Stamp(val size: Long, val modifiedAtMillis: Long)

    private class Entry(val version: String, val hash: String?, val stamp: Stamp?)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var dirty = false

    init {
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val fields = line.split('\t')
                if (fields.size >= 2 && fields[0].isNotEmpty()) {
                    val size = fields.getOrNull(3)?.toLongOrNull()
                    val modified = fields.getOrNull(4)?.toLongOrNull()
                    entries[fields[0]] = Entry(
                        fields[1],
                        fields.getOrNull(2)?.takeIf { it.isNotEmpty() },
                        if (size != null && modified != null) Stamp(size, modified) else null,
                    )
                }
            }
        }
    }

    /** Path → content version. */
    fun snapshot(): Map<String, String> = entries.mapValuesTo(HashMap()) { it.value.version }

    /** The content hash of the version recorded at [relative], if it was known. */
    fun hashOf(relative: String): String? = entries[key(relative)]?.hash

    /** The file's size and time as Control left it at [relative], if recorded. */
    fun stampOf(relative: String): Stamp? = entries[key(relative)]?.stamp

    fun record(relative: String, contentVersion: String, contentHash: String?, stamp: Stamp? = null) {
        val previous = entries.put(key(relative), Entry(contentVersion, contentHash, stamp))
        if (previous == null || previous.version != contentVersion || previous.hash != contentHash || previous.stamp != stamp) dirty = true
    }

    /** The file at [relative] was checked and still holds its version: [stamp] is what it looks like now. */
    fun restamp(relative: String, stamp: Stamp) {
        val entry = entries[key(relative)] ?: return
        record(relative, entry.version, entry.hash, stamp)
    }

    fun forget(relative: String) {
        if (entries.remove(key(relative)) != null) dirty = true
    }

    /** Written whole, through a temp file renamed into place, so a crash leaves the old journal or the new one. */
    fun save() {
        val target = file ?: return
        if (!dirty) return
        dirty = false
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(
                entries.entries.joinToString("") { (k, e) ->
                    "$k\t${e.version}\t${e.hash.orEmpty()}\t${e.stamp?.size ?: ""}\t${e.stamp?.modifiedAtMillis ?: ""}\n"
                },
            )
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { dirty = true }
    }

    private fun key(relative: String) = relative.lowercase(Locale.ROOT)
}
