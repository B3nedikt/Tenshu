package app.tenshu.control.inference

import kotlinx.coroutines.flow.Flow

/**
 * How much of the library the last finished index pass could actually look at.
 *
 * @param total photographs the pass walked
 * @param notOnDevice those whose bytes are not stored here — the pass cannot read them, so neither
 *   search nor People covers them on this device
 */
data class IndexCoverage(val total: Int, val notOnDevice: Int) {
    /**
     * Photographs this device CAN index — an original or a preview is here.
     *
     * ⚠ Reach, not success: a photograph skipped for consent, already current, or
     * left behind by a latched backend is in here too. The screen must say "can be indexed", never
     * "indexed".
     */
    val reachable: Int get() = (total - notOnDevice).coerceAtLeast(0)
}

/**
 * The read side of the proxy's `LastIndexCoverage` holder, which is all the Models screen needs.
 *
 * ⚠ An interface because the holder lives in `:control-shared-proxy`, which this module must not
 * see: the proxy depends on this module, never the reverse. The holder implements it and the
 * proxy's Koin module binds it under this type.
 */
interface IndexCoverageSource {
    /** `null` until a pass has finished in this process. ⚠ Never completes. */
    val coverage: Flow<IndexCoverage?>
}
