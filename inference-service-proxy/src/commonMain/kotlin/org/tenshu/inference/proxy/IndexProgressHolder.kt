package org.tenshu.inference.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where one in-flight index pass has got to. */
data class IndexProgressSnapshot(
    /** Images visited so far — including the ones skipped as already current. */
    val done: Int,
    /** Images this pass was handed. ⚠ The library's size, not the count that will be indexed. */
    val total: Int,
)

/**
 * Device-local, in-memory progress for a running index pass — IN-D6's *visible* half (1e-2).
 *
 * ⚠⚠ **A first index of a large library is hours, and IN-D6 is explicit that doing it silently is
 * "the kind of thing this product exists to not do."** Until this existed the only evidence a pass
 * had run at all was a `println` after it finished, which is not visibility, it is a log line.
 *
 * ⚠ Not the synced realm, and not a `DynamicProperty`: which device is part-way through indexing is
 * device state (IN-D4/D5), and pushing it to the user's other devices would describe work none of
 * them is doing. The same reasoning as `ModelDownloadProgressHolder`, whose shape this mirrors.
 *
 * ⚠ **At most one pass at a time**, so this holds a single snapshot rather than a map. The pass is
 * driven by one scheduled op with a `dedupKey`, so a second concurrent pass is not a state the
 * scheduler can produce — and a holder keyed by nothing is a holder that cannot disagree with
 * itself about which pass is running.
 */
class IndexProgressHolder {

    private val _progress = MutableStateFlow<IndexProgressSnapshot?>(null)

    /** The running pass, or `null` when none is. The present→absent edge ends a subscriber's stream. */
    val progress: StateFlow<IndexProgressSnapshot?> = _progress.asStateFlow()

    /**
     * How many collectors are attached — `internal`, and it exists for exactly one test.
     *
     * ⚠⚠ `MediaIndexProgressImplementation` stops collecting once a pass ends, and that is a **leak
     * guard**, not an optimisation: without it every subscribe strands a collector for the life of
     * the session, pushing later passes' ticks at a streamId nobody is listening to.
     *
     * ⚠ It is invisible from the client, because the stream's `End` is sent from the collect body
     * and arrives whether or not the collector then stops — a mutant that made the collector run
     * forever passed every client-side assertion in `MediaIndexProgressTest`. `subscriptionCount`
     * is on `MutableSharedFlow` and so is not reachable through [progress]; this is the narrowest
     * way to make the claim checkable rather than merely written down.
     */
    internal val subscriberCount: StateFlow<Int> get() = _progress.subscriptionCount

    /** Begin tracking a pass over [total] images. */
    fun start(total: Int) {
        _progress.value = IndexProgressSnapshot(done = 0, total = total)
    }

    /** One image visited. ⚠ No-op when nothing is tracked, so a late tick cannot resurrect a bar. */
    fun advance(done: Int) {
        _progress.update { current -> current?.copy(done = done) }
    }

    /** The pass ended — successfully, refused, or paused. ⚠ Always called, or the bar never clears. */
    fun finish() {
        _progress.value = null
    }
}
