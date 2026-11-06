package org.tenshu.systemtest

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId

/**
 * Names **who** deleted a row, for a flake where the row's disappearance is the whole symptom and
 * the caller is on some other coroutine by the time anyone notices.
 *
 * Built for `DeleteAndReuploadFolderSystemTest`, which fails ~35 % of full-gate runs with
 * `No folder at /photos to hold /photos/b.txt` — the folder vanishing between two successful
 * uploads (`docs/tech-debt.md`). Three candidate deleters have been eliminated by *reading* code,
 * at the cost of one wrong fix that shipped claiming to close it. This exists so the fourth answer
 * is measured instead of inferred.
 *
 * ⚠ A diagnostic, not a guard: it asserts nothing and changes no behaviour. Its only job is to be
 * attached when the flake next fires, and to make that one occurrence worth the gate run it cost —
 * the same bargain `PagingSettleProbe` makes in `:drive-shared-ui`.
 *
 * ⚠⚠ Records a **stack**, and that is the entire point. `WriteOrigin` distinguishes ingest from a
 * user op but not *which* ingest path, and the candidates here (`processDeleteBlock`,
 * `OrphanedFileSweeper`, `DeletedFolderSubtreeReaper` cascade, the delete op's own recursion) all
 * reach the same `deleteData`. Only the frames below it tell them apart.
 */
class DeletionWitness(
    /** Substring match on the row's key, so `/photos` also catches `/photos/a.txt`. */
    private val watching: String,
) : DataObserver {

    data class Sighting(
        val key: String,
        val origin: WriteOrigin,
        val atMillis: Long,
        val stack: String,
    )

    private val seen = mutableListOf<Sighting>()
    private var markLabel: String? = null
    private var markAt: Long = Long.MIN_VALUE

    /**
     * Draw a line under everything so far.
     *
     * ⚠⚠ The first capture drowned in its own output: the original delete legitimately cascades
     * over a dozen rows (children, and a `FileHistory` row per path keyed `path|uuid|millis`), and
     * at 8 KB of harvested failure detail that buried the only window anyone cares about — what
     * happens AFTER the folder is recreated. Deletions before the mark are counted, not printed.
     */
    fun mark(label: String) {
        markLabel = label
        markAt = System.currentTimeMillis()
    }

    /** Snapshot, so a caller can print it after the failure without racing the collector. */
    fun sightings(): List<Sighting> = synchronized(seen) { seen.toList() }

    override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val key = (dataId as? DataId.StringDataId)?.keyValue?.get() ?: return
        if (!key.contains(watching)) return
        val sighting = Sighting(
            key = key,
            origin = origin,
            atMillis = System.currentTimeMillis(),
            // ⚠ Captured HERE rather than formatted later: the frames that identify the deleter are
            // on this thread, right now, and are gone by the time the assertion fails.
            stack = Throwable("deleted $key").stackTraceToString(),
        )
        synchronized(seen) { seen += sighting }
    }

    /** Readable rendering, trimmed to the frames that discriminate between the candidates. */
    fun report(): String {
        val all = sightings()
        val (before, after) = all.partition { it.atMillis < markAt }
        val header = markLabel?.let {
            "  (${before.size} deletion(s) before '$it' suppressed — the original cascade)\n"
        }.orEmpty()
        val body = render(if (markLabel == null) all else after)
        return header + body.ifEmpty {
            "  (no deletion after '${markLabel.orEmpty()}' — so nothing removed the row through " +
                "ExecutionContext.deleteData, which rules out every candidate that does)"
        }
    }

    private fun render(list: List<Sighting>): String = list.joinToString("\n\n") { s ->
        buildString {
            appendLine("DELETED ${s.key}  origin=${s.origin}  at=${s.atMillis}")
            s.stack.lineSequence()
                .filter { it.contains("org.tenshu") || it.startsWith("java.lang.Throwable") }
                .take(18)
                .forEach { appendLine("    $it") }
        }
    }

    companion object {
        /** Attach to a client's context. Returns the witness so the test can read it back. */
        fun watch(executionContext: ExecutionContext, path: String): DeletionWitness =
            DeletionWitness(path).also { executionContext.addDataObserver(it) }
    }
}
