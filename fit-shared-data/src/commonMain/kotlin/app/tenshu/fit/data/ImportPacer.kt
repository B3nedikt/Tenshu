package app.tenshu.fit.data

import kotlinx.coroutines.delay

/**
 * Adaptive backpressure for a bulk import.
 *
 * A Google Takeout import writes tens of thousands of health records — through IPC — into Control's
 * proxy. Fired as fast as the coroutine can, they outrun Control's ability to flush each to its
 * durable store, GC the transient objects, and recompute the reactive projections; the backlog piles
 * up in Control's heap and a real 10-year export (13,537 records, 3,353 with dense route/HR streams)
 * exhausted its 256 MB and the import failed. The write path itself is fine — it is the *rate* that
 * is the problem.
 *
 * So [onRecord], called after every persisted record, pauses every [batch] records to let Control
 * catch up — and pauses **longer the deeper the import runs**. That is the "more backpressure when we
 * know we'll likely OOM" property: pressure grows with the number of rows already in flight through
 * the proxy, so the pause grows with the running total, from [baseDelayMs] up to [maxDelayMs]. A
 * one-time import does not need to be fast; it needs to *finish*, so the pacing is deliberately
 * generous — headroom over speed.
 *
 * Pure and deterministic (the delay for a given count is a fixed function), so it is unit-testable
 * without a clock; only [pause] actually suspends.
 */
class ImportPacer(
    private val batch: Int = DEFAULT_BATCH,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val rampPerRecordMs: Double = DEFAULT_RAMP_PER_RECORD_MS,
) {

    /** Pauses if [total] lands on a batch boundary. Call once per persisted record. */
    suspend fun onRecord(total: Int) {
        if (total > 0 && total % batch == 0) delay(delayForTotal(total))
    }

    /**
     * The pause after a batch that ends at [total] records: [baseDelayMs] plus a ramp proportional to
     * how far in we are, capped at [maxDelayMs]. Exposed for the test; the shape is the whole point.
     */
    fun delayForTotal(total: Int): Long =
        (baseDelayMs + (total * rampPerRecordMs).toLong()).coerceAtMost(maxDelayMs)

    private companion object {
        // Pause every this-many records. Small enough that the heap never accumulates a big backlog.
        const val DEFAULT_BATCH = 50

        // Generous by design (completeness over speed). At the ramp below, a 13,500-record import
        // spends roughly a minute in pauses total — a fine price for a one-time import that finishes.
        const val DEFAULT_BASE_DELAY_MS = 40L
        const val DEFAULT_MAX_DELAY_MS = 400L

        // Extra pause per record already written: by ~10k records the batch pause is near the cap, so
        // the back half of a big import — where the proxy is most pressured — runs the most slack.
        const val DEFAULT_RAMP_PER_RECORD_MS = 0.03
    }
}
