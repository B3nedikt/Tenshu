package app.tenshu.control.bridge

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which realm version Control last put at each path of the Explorer folder (desktop-windows-port.md §7.10), and the
 * content hash of that version when known.
 *
 * ⚠ Needed because editors save by writing a temp file and renaming it over the original: what is left is an
 * ordinary file that has lost the placeholder's identity, and with it any record of which version it was edited
 * from. With the journal, "the realm still holds the version this was made from" can be told from "it moved on
 * meanwhile" — an upload over it versus a conflict copy. Without an entry the answer is always the conflict copy.
 * The hash lets the mirror tell a downloaded copy that is still what it was from one edited in place (§7.10).
 *
 * Kept on disk so a restart does not turn every pending save into a conflict. [file] `null`: memory only (tests).
 */
internal class ExplorerJournal(private val file: File?) {
    private class Entry(val version: String, val hash: String?)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var dirty = false

    init {
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val fields = line.split('\t')
                if (fields.size >= 2 && fields[0].isNotEmpty()) {
                    entries[fields[0]] = Entry(fields[1], fields.getOrNull(2)?.takeIf { it.isNotEmpty() })
                }
            }
        }
    }

    /** Path → content version. */
    fun snapshot(): Map<String, String> = entries.mapValuesTo(HashMap()) { it.value.version }

    /** The content hash of the version recorded at [relative], if it was known. */
    fun hashOf(relative: String): String? = entries[key(relative)]?.hash

    fun record(relative: String, contentVersion: String, contentHash: String?) {
        val previous = entries.put(key(relative), Entry(contentVersion, contentHash))
        if (previous == null || previous.version != contentVersion || previous.hash != contentHash) dirty = true
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
            temp.writeText(entries.entries.joinToString("") { "${it.key}\t${it.value.version}\t${it.value.hash.orEmpty()}\n" })
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { dirty = true }
    }

    private fun key(relative: String) = relative.lowercase(Locale.ROOT)
}
