package app.tenshu.control.bridge

import java.util.Locale

/**
 * What it takes to make the Explorer folder show the realm (desktop-windows-port.md §7.8): pure, so the rules
 * are tested without Windows.
 *
 * The folder holds three kinds of entry: placeholders we made (online-only or hydrated), and anything else —
 * the user's own files, `desktop.ini`. Only placeholders are ever removed or replaced. ⚠⚠ Until the write path
 * (X3) exists nothing in the folder is uploaded, so removing a non-placeholder would delete the only copy.
 */
internal object ExplorerMirror {

    /** One realm row, as the folder should show it. [path] is the realm's (`/a/b.txt`). */
    data class Remote(
        val path: String,
        val isFolder: Boolean,
        val size: Long,
        val modifiedAtMillis: Long,
        val contentVersion: String,
    )

    /** One entry found in the folder. [relative] uses `/`, no leading slash, the case found on disk. */
    data class Local(
        val relative: String,
        val isFolder: Boolean,
        val isPlaceholder: Boolean,
        val size: Long,
        val modifiedAtMillis: Long,
        /** Its own pin mark, as Explorer set it; [Pin.NONE] inherits the nearest marked folder's. */
        val pin: Pin = Pin.NONE,
        /** No local copy: opening it downloads (`RECALL_ON_DATA_ACCESS`). */
        val online: Boolean = false,
    )

    /** "Always keep on this device" ([PINNED]) and "Free up space" ([UNPINNED]). */
    enum class Pin { NONE, PINNED, UNPINNED }

    sealed interface Action {
        val relative: String

        /** Remove a placeholder; folders only once everything below them is gone. */
        data class Remove(override val relative: String, val isFolder: Boolean) : Action

        /**
         * Create a placeholder for [remote] at [relative] (its parent exists by then). [pinned]: the file it
         * replaces was marked "Always keep on this device", and the replacement keeps that mark.
         */
        data class Create(override val relative: String, val remote: Remote, val pinned: Boolean = false) : Action
    }

    /**
     * Removals first, deepest first; then creations, shallowest first — so a replaced item is gone before its
     * successor arrives, and a parent exists before its children.
     *
     * - A realm row whose name Windows cannot hold (`:`, a reserved device name, a trailing dot…) is skipped,
     *   and so is everything below it.
     * - NTFS is case-insensitive: of two rows differing only in case, the first in path order is shown; the
     *   other and everything below it are skipped.
     * - A placeholder file whose size or modification time differs from its row is replaced. ⚠ Moves and
     *   renames made elsewhere are therefore delete + create, as on macOS (§7.3); a hydrated copy is dropped.
     * - A row whose path holds a non-placeholder (the user's own file) is not shown: theirs wins.
     */
    fun plan(remote: List<Remote>, local: List<Local>): List<Action> {
        val wanted = LinkedHashMap<String, Pair<String, Remote>>() // key → (relative, row)
        val skipped = HashSet<String>() // realm paths whose subtree is not shown
        for (row in withAncestors(remote).sortedBy { it.path }) {
            val relative = row.path.trim('/')
            if (relative.isEmpty()) continue
            if (skipped.any { row.path.startsWith("$it/") }) continue
            val key = key(relative)
            if (!relative.split('/').all(::isWindowsName) || key in wanted) {
                skipped += row.path
                continue
            }
            wanted[key] = relative to row
        }

        val localByKey = local.associateBy { key(it.relative) }
        val removals = HashMap<String, Action.Remove>()
        val creations = ArrayList<Action.Create>()
        val blocked = HashSet<String>() // keys whose row cannot be shown: something of the user's is there

        for ((key, entry) in wanted) {
            val (relative, row) = entry
            if (blocked.any { key.startsWith("$it/") }) continue
            val found = localByKey[key]
            when {
                found == null -> creations += Action.Create(relative, row)
                !found.isPlaceholder -> if (found.isFolder != row.isFolder || !row.isFolder) blocked += key
                found.isFolder != row.isFolder -> {
                    removals[key] = Action.Remove(found.relative, found.isFolder)
                    creations += Action.Create(relative, row)
                }
                !row.isFolder && (found.size != row.size || found.modifiedAtMillis != row.modifiedAtMillis) -> {
                    removals[key] = Action.Remove(found.relative, isFolder = false)
                    creations += Action.Create(relative, row, pinned = found.pin == Pin.PINNED)
                }
            }
        }

        // Placeholders the realm no longer has. Everything below a removed folder goes too, or it stays.
        for (entry in local) {
            val key = key(entry.relative)
            if (!entry.isPlaceholder || key in removals) continue
            if (key !in wanted || blocked.any { key == it || key.startsWith("$it/") }) {
                removals[key] = Action.Remove(entry.relative, entry.isFolder)
            }
        }
        // A folder stays while anything below it does: the user's files, or a row still shown.
        val kept = local.map { key(it.relative) }.filter { it !in removals }.toSet() +
            creations.map { key(it.relative) }
        val removable = removals.filter { (key, action) ->
            !action.isFolder || kept.none { it.startsWith("$key/") }
        }
        // A folder that must stay cannot be replaced by the file the realm now has there.
        val stuck = removals.keys - removable.keys
        return removable.values.sortedByDescending { depth(it.relative) } +
            creations.filter { key(it.relative) !in stuck }.sortedBy { depth(it.relative) }
    }

    /** What to do about one file's local copy. */
    enum class Copy { DOWNLOAD, DROP }

    /**
     * The placeholder files whose local copy disagrees with their pin: a pinned file still online-only is
     * downloaded, an unpinned one with a local copy has it dropped. A file without a mark of its own follows the
     * nearest folder above it that has one (Explorer marks the folder it was used on); with none, it is left as
     * it is — opened files stay until the user frees them.
     */
    fun copies(local: List<Local>): Map<String, Copy> {
        val folders = local.filter { it.isFolder }.associate { key(it.relative) to it.pin }
        fun effective(entry: Local): Pin {
            if (entry.pin != Pin.NONE) return entry.pin
            var parent = key(entry.relative).substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                folders[parent]?.takeIf { it != Pin.NONE }?.let { return it }
                parent = parent.substringBeforeLast('/', "")
            }
            return Pin.NONE
        }
        return local.filter { it.isPlaceholder && !it.isFolder }.mapNotNull { entry ->
            when (effective(entry)) {
                Pin.PINNED -> if (entry.online) entry.relative to Copy.DOWNLOAD else null
                Pin.UNPINNED -> if (!entry.online) entry.relative to Copy.DROP else null
                Pin.NONE -> null
            }
        }.toMap()
    }

    /** Folder rows for any ancestor the listing left out, so every file has a parent to be created in. */
    private fun withAncestors(remote: List<Remote>): List<Remote> {
        val paths = remote.mapTo(HashSet()) { it.path.trimEnd('/') }
        val extra = ArrayList<Remote>()
        for (row in remote) {
            var parent = row.path.trimEnd('/').substringBeforeLast('/', "")
            while (parent.isNotEmpty() && paths.add(parent)) {
                extra += Remote(parent, isFolder = true, size = 0, modifiedAtMillis = row.modifiedAtMillis, contentVersion = "")
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return remote + extra
    }

    private fun key(relative: String) = relative.lowercase(Locale.ROOT)

    private fun depth(relative: String) = relative.count { it == '/' }

    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /** Whether NTFS through the Win32 API can hold [name] as one path component. */
    fun isWindowsName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255 || name == "." || name == "..") return false
        if (name.endsWith('.') || name.endsWith(' ')) return false
        if (name.any { it < ' ' || it in "<>:\"/\\|?*" }) return false
        return name.substringBefore('.').lowercase(Locale.ROOT) !in RESERVED
    }
}
