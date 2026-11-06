package app.tenshu.control.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.inference.proxy.IndexProgressHolder

/**
 * [IndexProgressSource] over the proxy's [IndexProgressHolder] — the SAME instance `MediaIndexer`
 * ticks, which is the whole point: a second holder would be a bar that never moves.
 *
 * ⚠ Process-local, like [LastIndexCoverage]: Control's UI runs in the proxy's process, so this needs
 * no op and no schema change. ⚠ Unlike the `IndexProgress` op it never completes — it is collected in
 * the screen's own scope and dies with it, so there is no proxy-side collector to strand.
 */
class HolderIndexProgressSource(holder: IndexProgressHolder) : IndexProgressSource {
    override val progress: Flow<IndexPassPosition?> =
        holder.progress.map { snapshot -> snapshot?.let { IndexPassPosition(done = it.done, total = it.total) } }
}
