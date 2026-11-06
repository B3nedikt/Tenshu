package org.tenshu.sync.relay.storage

import org.slf4j.LoggerFactory

/**
 * A relay-wide ceiling on stored bytes, summed over **every** owner: the operator's cost brake
 * (`docs/how-to/relay-storage-ceiling.md`).
 *
 * Per-owner quotas bound what one account can store. They do not bound how many accounts exist,
 * and signup is open, so without this the storage bill has no upper limit. Past the ceiling the
 * relay refuses **new** stored bytes with the same `507` a full owner gets. Reads, sync and
 * re-uploads of bytes already held are never refused.
 *
 * ## The figure is an estimate, deliberately
 *
 * [measure] is a full sum over the stores, so it runs at most once per [refreshMs]. In between,
 * [recordStored] adds each accepted write, so the estimate only ever runs high: a re-PUT counts
 * again, and nothing freed is subtracted until the next measurement. Erring high is the right side
 * for a cost brake. The worst overshoot is the writes already past [admits] when the ceiling is
 * crossed, each capped by its route's per-request limit.
 *
 * ⚠ The figure counts no user data and names no owner, so logging it is fine (House Rule #1;
 * `docs/how-to/server-logging.md`).
 */
class StorageCeiling(
    val ceilingBytes: Long,
    private val measure: () -> Long,
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(ceilingBytes > 0) { "ceilingBytes must be positive" }
        require(refreshMs > 0) { "refreshMs must be positive" }
    }

    private val lock = Any()
    private var measuredBytes = 0L
    private var measuredAtMs: Long? = null
    private var addedSinceMeasure = 0L
    private var reached = false

    /** The current estimate of stored bytes: the last measurement plus every write since. */
    fun estimatedBytes(): Long = synchronized(lock) {
        val now = nowMs()
        val last = measuredAtMs
        if (last == null || now - last >= refreshMs) {
            measuredBytes = measure()
            measuredAtMs = now
            addedSinceMeasure = 0L
        }
        saturatingAdd(measuredBytes, addedSinceMeasure)
    }

    /** True if [incomingBytes] more still fit under the ceiling. */
    fun admits(incomingBytes: Long): Boolean {
        val estimate = estimatedBytes()
        val fits = saturatingAdd(estimate, incomingBytes.coerceAtLeast(0L)) <= ceilingBytes
        noteTransition(full = !fits, estimate = estimate)
        return fits
    }

    /** Count an accepted write until the next measurement replaces the estimate. */
    fun recordStored(bytes: Long) {
        if (bytes <= 0) return
        synchronized(lock) { addedSinceMeasure = saturatingAdd(addedSinceMeasure, bytes) }
    }

    /** One log line per change of state, so a full relay is visible without flooding the log. */
    private fun noteTransition(full: Boolean, estimate: Long) {
        val changed = synchronized(lock) {
            if (reached == full) false else { reached = full; true }
        }
        if (!changed) return
        if (full) {
            log.warn(
                "Storage ceiling reached: ~{} of {} bytes stored; new uploads and shares are refused with 507",
                estimate, ceilingBytes,
            )
        } else {
            log.info("Storage ceiling cleared: ~{} of {} bytes stored; uploads accepted again", estimate, ceilingBytes)
        }
    }

    companion object {
        const val DEFAULT_REFRESH_MS: Long = 60_000L
        const val BYTES_PER_GIB: Long = 1024L * 1024L * 1024L

        private val log = LoggerFactory.getLogger(StorageCeiling::class.java)

        private fun saturatingAdd(a: Long, b: Long): Long =
            if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}
