package org.tenshu.sync.proxy

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * ⭐ D-d (`sync-envelope-authentication.md` §3.4): what ingest does with an envelope that fails
 * verification — **drop it, never apply it**, and leave a trace.
 *
 * A failure is either an active relay / middlebox forging or re-stamping, or a block this realm's
 * keys genuinely cannot open (a realm this device left). Neither is the user's to act on, so v1
 * raises no banner: one log line per [LOG_EVERY] rejections (a forgery flood must not become a log
 * flood) and a process-wide count a health or diagnostics surface can read.
 *
 * ⚠ A body-dropped block (`EnvelopeOnlySyncBlock`, empty payload) is **not** a rejection: the relay
 * freed its body by design. It is skipped without being counted.
 */
@OptIn(ExperimentalAtomicApi::class)
object EnvelopeRejections {

    /** Log the first rejection, then one in this many. */
    const val LOG_EVERY: Long = 100

    enum class Kind { TOMBSTONE_TAG, BLOCK_ENVELOPE, RETIRED_KEY_AFTER_RETIREMENT }

    // ⚠ Atomic, not `@Volatile var` + `++`: every device in the process records here, and two devices
    // pulling the same batch record from different threads at once — a volatile `++` loses counts.
    private val tombstones = AtomicLong(0)
    private val blocks = AtomicLong(0)

    /** Rejected tombstones since process start. */
    val tombstoneCount: Long get() = tombstones.load()

    /** Rejected blocks (bad envelope, or a retired key used after its retirement) since process start. */
    val blockCount: Long get() = blocks.load()

    fun record(kind: Kind, blockId: String) {
        val n = when (kind) {
            Kind.TOMBSTONE_TAG -> tombstones.addAndFetch(1L)
            Kind.BLOCK_ENVELOPE, Kind.RETIRED_KEY_AFTER_RETIREMENT -> blocks.addAndFetch(1L)
        }
        if (n == 1L || n % LOG_EVERY == 0L) {
            println(
                "WARN SyncProcessor: dropped an unverifiable envelope ($kind, blockId=$blockId) — " +
                    "#$n of its kind this process. Not applied, clock not advanced " +
                    "(sync-envelope-authentication.md §3.4)."
            )
        }
    }
}
