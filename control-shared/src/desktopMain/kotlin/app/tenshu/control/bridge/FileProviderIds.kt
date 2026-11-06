package app.tenshu.control.bridge

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * The device-local `identifier ⇄ realm path` map behind the macOS File Provider
 * (docs/proposals/desktop-macos-integration.md §5a).
 *
 * Why it exists: Finder needs an identifier that **survives a rename or a move** — it keys
 * downloaded copies, open documents and its own database on it — and the personal realm has
 * none. A file's identity there *is* its path (`drive-shared-realm-file-model.md`), and a move
 * re-creates the row under the new path. So the identifier is minted here, on this Mac, and
 * re-pointed when the File Provider itself renames or moves something.
 *
 * ⚠ **Device-local, never synced.** Two Macs give the same file different identifiers, and
 * that is fine: nothing outside this machine's Finder ever sees one.
 *
 * ⚠ **A move made elsewhere is still a delete + create here.** A rename on the phone arrives
 * as a new path and a tombstone, with nothing tying the two together at this layer, so the new
 * path gets a fresh identifier. Only moves that go through [moved] keep theirs.
 *
 * ⚠ Losing the journal is survivable, not free: every item gets a new identifier, so Finder
 * drops what it had downloaded and re-lists.
 */
internal class FileProviderIds(private val journal: File? = null) {

    private val pathById = HashMap<String, String>()
    private val idByPath = HashMap<String, String>()
    private var journalLines = 0
    private val deferred = HashMap<String, DeferredMove>()

    init {
        val text = runCatching { journal?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        // ⚠ The newline is the record terminator. A final line without one was cut short by a
        // crash mid-append and is DROPPED even if it parses: `id\t%2Fdocs` is a valid record
        // and also a prefix of `id\t%2Fdocs%2Ffile.txt`, so accepting it could point a file's
        // identifier at its parent folder.
        val unterminated = text.isNotEmpty() && !text.endsWith("\n")
        val records = text.split('\n').let { if (unterminated) it.dropLast(1) else it }
        records.filter { it.isNotEmpty() }.forEach forEachLine@{ line ->
            journalLines++
            // Move intents: `>from<TAB>to<TAB>fingerprint` opens one, `<from` closes it.
            if (line.startsWith(INTENT_OPEN)) {
                val parts = line.substring(1).split('\t').map { decodeOrNull(it) ?: return@forEachLine }
                if (parts.size == 3) deferred[parts[0]] = DeferredMove(parts[1], parts[2])
                return@forEachLine
            }
            if (line.startsWith(INTENT_CLOSE)) {
                decodeOrNull(line.substring(1))?.let { deferred.remove(it) }
                return@forEachLine
            }
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val id = line.substring(0, tab)
            val encoded = line.substring(tab + 1)
            // ⚠ A record cut short by a crash mid-append can end in half a percent-escape,
            // which the decoder throws on. This map is built during Control's startup, so an
            // escaping exception would keep the daemon — and every app behind it — from
            // starting over what is only a cache. A record that does not parse is skipped.
            val path = if (encoded.isEmpty()) null else decodeOrNull(encoded) ?: return@forEachLine
            pathById.remove(id)?.let { idByPath.remove(it) }
            if (path != null) put(id, path)
        }
        // The journal only grows; rewrite it once it is mostly history — and at once after a
        // torn tail, or the next append would be glued onto the fragment.
        if (unterminated || journalLines > 2 * pathById.size + 64) compact()
    }

    /** The identifier for [path], minted on first sight. The realm root is always [ROOT_ID]. */
    @Synchronized
    fun idFor(path: String): String {
        val normalised = normalise(path)
        if (normalised == ROOT_PATH) return ROOT_ID
        idByPath[normalised]?.let { return it }
        val id = UUID.randomUUID().toString()
        put(id, normalised)
        append(id, normalised)
        return id
    }

    /** The path [id] currently names, or `null` for an identifier this map never issued. */
    @Synchronized
    fun pathFor(id: String): String? = if (id == ROOT_ID) ROOT_PATH else pathById[id]

    /** Re-points the item at [from] — and, for a folder, everything under it — to [to]. */
    @Synchronized
    fun moved(from: String, to: String) {
        val source = normalise(from)
        val target = normalise(to)
        if (source == target) return
        val affected = idByPath.keys.filter { it == source || it.startsWith("$source/") }
        if (affected.isNotEmpty()) {
            // Whatever sat at the destination is gone; its identifier must not linger.
            // ⚠ Only when something is actually moving in: a settle replayed after a crash finds
            // the identifier ALREADY at the destination, and forgetting that would retire it.
            forget(target)
            for (oldPath in affected) {
                val id = idByPath.remove(oldPath) ?: continue
                val newPath = target + oldPath.removePrefix(source)
                put(id, newPath)
                append(id, newPath)
            }
        }
        // ⚠ Closed LAST. The journal is append-only, so a crash between two appends leaves a
        // prefix: with the intent closed first, that prefix could be "intent closed, id still
        // at the old path" — unrecoverable. This order leaves "id re-pointed, intent open",
        // which the next settle finds already done and merely closes.
        forgetIntent(source)
    }

    /**
     * Records the intent to move [from] to [to] **before** the move is attempted, journalled.
     *
     * Two things can separate a move from the re-point that should follow it, and neither
     * calls back into this map:
     *  - the file-service may *defer* the move (an unsafe offline one) and replay it later;
     *  - Control may die after the move committed and before [moved] was journalled.
     * Either way the item ends up at [to] with its identifier still naming [from], and the
     * next refresh would retire that identifier and mint a new one — for a rename the user
     * made in Finder, the case identifiers exist for. The intent is what lets
     * [settleDeferredMoves] recognise the move afterwards, across a restart.
     *
     * [fingerprint] is what the item looked like, in terms a move preserves.
     */
    @Synchronized
    fun deferMove(from: String, to: String, fingerprint: String) {
        val source = normalise(from)
        val target = normalise(to)
        deferred[source] = DeferredMove(target, fingerprint)
        appendLine(INTENT_OPEN + listOf(source, target, fingerprint).joinToString("\t") { encode(it) })
    }

    /** Withdraws the intent for [from]: the move failed outright and will not be replayed. */
    @Synchronized
    fun cancelMove(from: String) = forgetIntent(normalise(from))

    private fun forgetIntent(source: String) {
        if (deferred.remove(source) != null) appendLine(INTENT_CLOSE + encode(source))
    }

    /** Every path a deferred move starts or ends at — what [settleDeferredMoves] will ask about. */
    @Synchronized
    fun deferredMovePaths(): Set<String> = deferred.keys + deferred.values.map { it.to }

    /**
     * Re-points every deferred move that has since been applied, and forgets it. Call before
     * anything that prunes or mints, so the destination is claimed by its original identifier.
     *
     * [present] holds every path that exists, mapped to its fingerprint (`null` for an item
     * that has none). A move counts as applied only when the source is gone **and the
     * destination holds the same item**.
     *
     * ⚠ Path existence alone is not enough: the source deleted by someone else while an
     * unrelated file appears at the destination looks identical, and re-pointing then would
     * hand this identifier — and whatever Finder had downloaded under it — to a stranger.
     */
    @Synchronized
    fun settleDeferredMoves(present: Map<String, String?>) {
        val applied = deferred.filter { (from, move) ->
            from !in present && present[move.to] == move.fingerprint
        }
        applied.forEach { (from, move) -> moved(from, move.to) }
        // A source that is gone with no matching destination was not this move; stop waiting.
        deferred.keys.filter { it !in present }.forEach { forgetIntent(it) }
    }

    private class DeferredMove(val to: String, val fingerprint: String)

    /** Drops the identifier for [path] and for everything under it. */
    @Synchronized
    fun removed(path: String) = forget(normalise(path))

    /** Drops every identifier whose path is not in [livePaths]; returns the dropped ids. */
    @Synchronized
    fun retainOnly(livePaths: Set<String>): Set<String> {
        val dead = idByPath.filterKeys { it !in livePaths }
        dead.forEach { (path, id) ->
            idByPath.remove(path)
            pathById.remove(id)
            append(id, null)
        }
        return dead.values.toSet()
    }

    private fun forget(path: String) {
        idByPath.keys.filter { it == path || it.startsWith("$path/") }.forEach { gone ->
            val id = idByPath.remove(gone) ?: return@forEach
            pathById.remove(id)
            append(id, null)
        }
    }

    private fun put(id: String, path: String) {
        pathById[id] = path
        idByPath[path] = id
    }

    private fun append(id: String, path: String?) = appendLine("$id\t${path?.let(::encode).orEmpty()}")

    private fun appendLine(line: String) {
        val file = journal ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            journalLines++
        }
    }

    private fun compact() {
        val file = journal ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            val ids = pathById.entries.joinToString("") { (id, path) -> "$id\t${encode(path)}\n" }
            val intents = deferred.entries.joinToString("") { (from, move) ->
                INTENT_OPEN + listOf(from, move.to, move.fingerprint).joinToString("\t") { encode(it) } + "\n"
            }
            tmp.writeText(ids + intents)
            if (tmp.renameTo(file)) journalLines = pathById.size + deferred.size
        }
    }

    companion object {
        /** The realm root: `NSFileProviderItemIdentifier.rootContainer` on the Swift side. */
        const val ROOT_ID = "root"
        const val ROOT_PATH = "/"

        // An identifier is a UUID, so neither marker can begin an id record.
        private const val INTENT_OPEN = ">"
        private const val INTENT_CLOSE = "<"

        fun normalise(path: String): String = path.trimEnd('/').ifEmpty { ROOT_PATH }

        // A file name may hold a tab or a newline; the journal is line- and tab-delimited.
        private fun encode(path: String): String = URLEncoder.encode(path, Charsets.UTF_8)
        private fun decodeOrNull(text: String): String? =
            try {
                URLDecoder.decode(text, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
