package org.tenshu.systemtest

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * A stepped [TestSystem]'s sync clock ([TestSystem.syncClock]): the time every device's sync
 * runtime reads, frozen until the test moves it.
 *
 * What reads it: each device's `initSyncService(nowMs)`. That is the scheduler's three
 * `RejectionBackoff` windows (`402` entitlement, `401` registration, `507` storage), relay
 * reachability, the health row's stamps, the shared-realm runtimes and the HLC-divergence tracker.
 * So a test can stand a device inside a backoff window for as many ticks as it likes, then
 * [advance] across it and tick once more. The window's length is still the backoff's own; the test
 * decides only *when* it has passed.
 *
 * ⚠⚠ What does NOT read it: device JWTs and pairing tokens (`nowEpochSec`), HLCs, entitlement
 * proofs, the task registry, the relay itself. Those stay on real time on purpose. The relay
 * validates a token's issue and expiry times against its own clock, so a device whose token clock
 * ran ahead would be a clock-skew test, not a backoff test.
 *
 * Thread-safe: the test advances it on one thread while ticks read it on `Dispatchers.Default`.
 */
class TestSyncClock internal constructor(startMs: Long) {
    private val now = AtomicLong(startMs)

    fun nowMs(): Long = now.get()

    /** Move the sync clock forward by [by]. Never backward: a backoff window only ever elapses. */
    fun advance(by: Duration) {
        require(!by.isNegative()) { "the sync clock only moves forward; got $by" }
        now.addAndGet(by.inWholeMilliseconds)
    }
}
