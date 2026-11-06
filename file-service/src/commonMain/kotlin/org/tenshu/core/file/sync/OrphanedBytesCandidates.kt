package org.tenshu.core.file.sync

import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringListAdapter

/**
 * Paths whose row an **ingested** tombstone removed, and whose plaintext may therefore be stranded
 * on this device — `docs/tech-debt.md` *"A delete frees the deleting device's disk and no one
 * else's"*.
 *
 * A delete applied from another device removes the row and nothing else: the bytes stay on disk with
 * nothing pointing at them, and no pass collects them. ⚠ A **move** is the same event to a peer —
 * `DataIdMigrator` emits a real tombstone — so this list is fed by both, and every move made since
 * the feature existed has been stranding bytes this way.
 *
 * ⚠⚠ **Recording is not deleting.** An entry asserts only "the row at this path went away here". The
 * path may be re-created, the bytes may never have existed, an upload may be mid-flight at that very
 * path — [OrphanedBytesPolicy] decides, and it is written to answer *no* whenever it cannot tell.
 *
 * ⚠ Forward-only, and deliberately so (the maintainer's call, 2026-09-06): bytes stranded before
 * this existed have no candidate and never will. Collecting those needs an enumerable
 * `FileDatabase`, which no platform has — see the tech-debt entry.
 *
 * **Encoding follows [ContentReclamationCandidates]**: a `StringList` preference of
 * `path<U+001F>epochMillis`, so there is no new composite, no schema version bump and no migration.
 * ⚠ The unit separator cannot occur in a file path or in a decimal timestamp.
 */
object OrphanedBytesCandidates {

    /**
     * ⚠ Written as an ESCAPE, never as a literal control character in the source: an invisible
     * byte is invisible to review, to diffs, and to whatever tool copies the line next.
     * [ContentReclamationCandidates] carries the same warning because a first draft of *that*
     * file silently disagreed with its own test over one — and the first draft of this one
     * repeated it.
     */
    private const val SEPARATOR = '\u001F'

    /**
     * ⚠ DEVICE-scoped: which bytes are stranded is a fact about this disk, not about the realm.
     * Another device's ingested deletes strand its own bytes and keep their own list.
     */
    val Candidates = Preference(
        id = "file.orphanedBytes.candidates",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * ⚠⚠ Capped, and overflow drops the NEW entry rather than evicting an old one — the same trade
     * as [ContentReclamationCandidates]. Overflow then degrades to today's behaviour (those bytes
     * are simply never collected) instead of silently abandoning an entry that was about to become
     * collectable. Nothing here can delete something by being full.
     */
    const val MAX_CANDIDATES: Int = 2_000

    /** One recorded path, and the local wall-clock time its row went away. */
    data class Entry(val path: String, val removedAtMs: Long)

    fun encode(entries: List<Entry>): List<String> =
        entries.map { "${it.path}$SEPARATOR${it.removedAtMs}" }

    /** ⚠ Malformed rows are skipped, not thrown on: one bad row must not strand every other. */
    fun decode(encoded: List<String>): List<Entry> = encoded.mapNotNull { row ->
        val separator = row.lastIndexOf(SEPARATOR)
        if (separator <= 0) return@mapNotNull null
        val millis = row.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
        Entry(path = row.substring(0, separator), removedAtMs = millis)
    }

    /** ⚠ An existing entry keeps its original stamp, so a re-recorded path cannot postpone itself. */
    fun merge(
        existing: List<Entry>,
        newlyRemoved: List<String>,
        nowMs: Long,
        maxCandidates: Int = MAX_CANDIDATES,
    ): List<Entry> {
        val known = existing.mapTo(mutableSetOf()) { it.path }
        val room = maxCandidates - existing.size
        if (room <= 0) return existing
        return existing + newlyRemoved.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { known.add(it) }
            .take(room)
            .map { Entry(path = it, removedAtMs = nowMs) }
    }

    fun remove(existing: List<Entry>, paths: Collection<String>): List<Entry> {
        if (paths.isEmpty()) return existing
        val gone = paths.toSet()
        return existing.filterNot { it.path in gone }
    }
}
