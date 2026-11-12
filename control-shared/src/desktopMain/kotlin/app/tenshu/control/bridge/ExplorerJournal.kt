package app.tenshu.control.bridge

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which realm version Control last put at each path of the Explorer folder (desktop-windows-port.md §7.10).
 *
 * ⚠ Needed because editors save by writing a temp file and renaming it over the original: what is left is an
 * ordinary file that has lost the placeholder's identity, and with it any record of which version it was edited
 * from. With the journal, "the realm still holds the version this was made from" can be told from "it moved on
 * meanwhile" — an upload over it versus a conflict copy. Without an entry the answer is always the conflict copy.
 *
 * Kept on disk so a restart does not turn every pending save into a conflict. [file] `null`: memory only (tests).
 */
internal class ExplorerJournal(private val file: File?) {
    private val versions = ConcurrentHashMap<String, String>()
    @Volatile private var dirty = false

    init {
        runCatching {
            file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) versions[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
    }

    fun snapshot(): Map<String, String> = HashMap(versions)

    fun record(relative: String, contentVersion: String) {
        if (versions.put(key(relative), contentVersion) != contentVersion) dirty = true
    }

    fun forget(relative: String) {
        if (versions.remove(key(relative)) != null) dirty = true
    }

    /** Written whole, through a temp file renamed into place, so a crash leaves the old journal or the new one. */
    fun save() {
        val target = file ?: return
        if (!dirty) return
        dirty = false
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(versions.entries.joinToString("") { "${it.key}\t${it.value}\n" })
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { dirty = true }
    }

    private fun key(relative: String) = relative.lowercase(Locale.ROOT)
}
