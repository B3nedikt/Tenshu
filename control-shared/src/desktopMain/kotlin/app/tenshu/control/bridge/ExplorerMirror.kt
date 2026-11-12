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
        /** The realm's whole-file hash of the bytes (`File.ContentHash`); `null` for rows older than it. */
        val contentHash: String? = null,
    )

    /**
     * One entry found in the folder. [relative] uses `/`, no leading slash, the case found on disk.
     * [isPlaceholder]: one of ours that still holds what we put there — it carries a Tenshu identity and, for a file,
     * is in sync. ⚠ A link, a foreign placeholder, or one of ours the user saved an edit into is NOT: it is theirs.
     */
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
        /** The content version its identity was made for; `null` when unknown. */
        val contentVersion: String? = null,
        /** The realm path its identity names — what a fetch reads; `null` when unknown. */
        val identityPath: String? = null,
        /** It carries a Tenshu identity — in sync or not ([isPlaceholder] is the in-sync half). */
        val ours: Boolean = isPlaceholder,
        /** Hidden or system: never uploaded (X3). */
        val hidden: Boolean = false,
    )

    /** "Always keep on this device" ([PINNED]) and "Free up space" ([UNPINNED]). */
    enum class Pin { NONE, PINNED, UNPINNED }

    sealed interface Action {
        val relative: String

        /** Remove a placeholder; folders only once everything below them is gone. */
        data class Remove(override val relative: String, val isFolder: Boolean) : Action

        /**
         * Create a placeholder for [remote] at [relative] (its parent exists by then). [pin]: the mark of the file it
         * replaces, which the replacement keeps — "Always keep", or a free mark standing as an exception inside a kept
         * folder (lost, the new version would follow the folder and download).
         */
        data class Create(override val relative: String, val remote: Remote, val pin: Pin = Pin.NONE) : Action

        /**
         * Make the ordinary entry at [relative] one of ours again, bytes kept — what a reinstall leaves behind (§7.9):
         * Windows turns our downloaded copies and folders into ordinary ones, which then read "Sync pending" for good.
         * ⚠⚠ A **candidate** only: a file is adopted only once its bytes are proven to hash to [remote]'s content
         * hash. Anything else is the user's and stays exactly as it is.
         */
        data class Adopt(override val relative: String, val remote: Remote) : Action
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
        val adoptions = ArrayList<Action.Adopt>()
        val blocked = HashSet<String>() // keys whose row cannot be shown: something of the user's is there

        for ((key, entry) in wanted) {
            val (relative, row) = entry
            if (blocked.any { key.startsWith("$it/") }) continue
            val found = localByKey[key]
            when {
                found == null -> creations += Action.Create(relative, row)
                !found.isPlaceholder -> {
                    if (found.isFolder != row.isFolder || !row.isFolder) blocked += key
                    // An ordinary folder at a realm folder's path is ours to keep. A file needs its bytes looked at
                    // first, which is the write path's job (ExplorerWrites: adopt, upload or keep both).
                    if (found.isFolder && row.isFolder && !found.ours) adoptions += Action.Adopt(relative, row)
                }
                found.isFolder != row.isFolder -> {
                    removals[key] = Action.Remove(found.relative, found.isFolder)
                    creations += Action.Create(relative, row)
                }
                // ⚠ The version too: an overwrite can keep both the size and the content date, and a stale
                // placeholder fails every fetch (its version no longer matches the row's).
                // ⚠ And the path: NTFS matches a case-only rename (`photo.jpg` → `Photo.jpg`) to the old placeholder,
                // whose identity still names the old path, which no longer exists — every fetch would fail. Folders
                // are never fetched, so theirs does not matter.
                // ⚠ With a version in its identity, that version decides, not the date: an upload over a file (X3) stamps
                // the realm's date with the moment of the upload, and comparing dates replaced the just-uploaded copy with
                // an online-only placeholder (measured). The date is the test only for a placeholder without a version.
                !row.isFolder && (
                    found.size != row.size ||
                        (found.contentVersion == null && found.modifiedAtMillis != row.modifiedAtMillis) ||
                        (found.contentVersion != null && found.contentVersion != row.contentVersion) ||
                        (found.identityPath != null && found.identityPath != row.path)
                    ) -> {
                    removals[key] = Action.Remove(found.relative, isFolder = false)
                    creations += Action.Create(relative, row, pin = found.pin)
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
            (adoptions + creations.filter { key(it.relative) !in stuck }).sortedBy { depth(it.relative) }
    }

    /** What to do about one file's local copy. */
    enum class Copy { DOWNLOAD, DROP }

    /**
     * What a pin pass does. [cleared]: the entries whose own "Free up space" mark is carried out by this pass and is
     * then cleared — each with the files whose drop must succeed first (itself, or everything below a folder).
     */
    data class CopyPlan(val copies: Map<String, Copy>, val cleared: Map<String, Set<String>>)

    /**
     * The placeholder files whose local copy disagrees with their pin. A file without a mark of its own follows the
     * nearest folder above it that has one (Explorer marks the folder it was used on).
     * - **Pinned** and online-only → downloaded, whenever seen: "always keep" is a standing order.
     * - **Unpinned** with a local copy → dropped. "Free up space" is a one-off request, not a standing order, so once
     *   it is carried out the mark is **cleared** ([CopyPlan.cleared]). ⚠ Otherwise the mark outlives the request:
     *   opening the freed file downloads it with the mark still on, and the next pass would take the copy away half a
     *   second later — while a second "Free up space" would set nothing new to notice. A mark is cleared only once
     *   every drop it asked for succeeded, so a file that was open is tried again.
     * - No mark → left as it is: opened files stay until the user frees them.
     */
    fun copies(local: List<Local>, rootPin: Pin = Pin.NONE): CopyPlan {
        val folders = local.filter { it.isFolder }.associate { key(it.relative) to it.pin }
        /** The mark [entry] would follow without one of its own. */
        fun inherited(entry: Local): Pin {
            var parent = key(entry.relative).substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                folders[parent]?.takeIf { it != Pin.NONE }?.let { return it }
                parent = parent.substringBeforeLast('/', "")
            }
            // ⚠ The Tenshu Drive folder itself is not among [local]: its mark ("Always keep" on the whole folder) is
            // the last fallback, so files that arrive later follow it too.
            return rootPin
        }

        fun effective(entry: Local): Pin = if (entry.pin != Pin.NONE) entry.pin else inherited(entry)
        val files = local.filter { it.isPlaceholder && !it.isFolder }
        val copies = files.mapNotNull { entry ->
            when (effective(entry)) {
                Pin.PINNED -> if (entry.online) entry.relative to Copy.DOWNLOAD else null
                Pin.UNPINNED -> if (!entry.online) entry.relative to Copy.DROP else null
                Pin.NONE -> null
            }
        }.toMap()
        val drops = copies.filterValues { it == Copy.DROP }.keys
        // ⚠ Not under a kept folder: there the free mark is the only thing between the file and the folder's "always
        // keep", and clearing it would download the file straight back. It stays as a standing exception.
        val cleared: MutableMap<String, Set<String>> = local
            .filter { it.isPlaceholder && it.pin == Pin.UNPINNED && inherited(it) != Pin.PINNED }
            .associateTo(HashMap()) { marked ->
            val prefix = key(marked.relative) + "/"
            marked.relative to if (marked.isFolder) {
                drops.filterTo(HashSet()) { key(it).startsWith(prefix) }
            } else {
                drops.filterTo(HashSet()) { it == marked.relative }
            }
        }
        // The whole folder freed: its mark goes once every drop in it succeeded. "" names the folder itself.
        if (rootPin == Pin.UNPINNED) cleared[ROOT] = drops.toSet()
        return CopyPlan(copies, cleared)
    }

    /**
     * Whether [actions] would remove so much of what [local] shows that the listing is more likely wrong than the
     * realm emptied: more than [MASS_REMOVAL_FLOOR] placeholders and more than half of them, not replaced.
     */
    fun isMassRemoval(actions: List<Action>, local: List<Local>): Boolean {
        val created = actions.filterIsInstance<Action.Create>().mapTo(HashSet()) { key(it.relative) }
        val removed = actions.count { it is Action.Remove && key(it.relative) !in created }
        val shown = local.count { it.isPlaceholder }
        return removed > MASS_REMOVAL_FLOOR && removed * 2 > shown
    }

    private const val MASS_REMOVAL_FLOOR = 10

    /** How [CopyPlan.cleared] names the Tenshu Drive folder itself. */
    const val ROOT = ""

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
