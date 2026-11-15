package app.tenshu.control.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last pass's [IndexCoverage], for Control's Models screen — process-local by design.
 *
 * ⚠⚠ Why it exists: on the maintainer's phone People showed 9 photographs of ~1,300, and nothing
 * anywhere said that 1,290 of them were not on the device. The pass counted them and printed the
 * count to a log line nobody reads. This is the smallest surface that says so.
 *
 * ⚠ Process-local on purpose: Control's UI runs in the proxy's process, so a Koin single needs no
 * op and no schema change. Photos cannot see it; its own version would be an additive op.
 * ⚠ Resets on process death. The next pass — scheduled or *Index now* — refills it.
 */
class LastIndexCoverage : IndexCoverageSource {
    private val state = MutableStateFlow<IndexCoverage?>(null)
    override val coverage: StateFlow<IndexCoverage?> = state.asStateFlow()

    /**
     * ⚠ Only a pass that ran: a deferred or paused one walked nothing, and recording it would
     * replace a real answer with "0 of 0".
     */
    fun record(total: Int, notOnDevice: Int, ran: Boolean) {
        if (!ran) return
        state.value = IndexCoverage(total = total, notOnDevice = notOnDevice)
    }
}

/**
 * [LastIndexCoverage.record] for one finished pass. ⚠⚠ Only a **library walk** that ran to its end
 * speaks for the library: a new-arrivals path batch of three photographs recorded here would read
 * "3 of 3" over "1,290 of 1,300 aren't on this device" (IN-D6a); a deferred or paused one walked
 * nothing; a capped one stopped part-way.
 */
fun LastIndexCoverage.recordPass(result: org.tenshu.inference.proxy.IndexPassResult) = record(
    total = result.imagesVisited,
    notOnDevice = result.notOnDevice,
    ran = result.libraryWalk && !result.deferred && !result.paused && !result.batchCapReached,
)
