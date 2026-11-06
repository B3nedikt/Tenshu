package org.tenshu.sync.relay.storage

import kotlinx.serialization.Serializable
import org.tenshu.sync.relay.ContentSweepMode
import org.tenshu.sync.relay.logId
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry

/**
 * **G2, the relay's sweep** — CR-2 phase B5r (`content-plane-cr2-b5-spec.md` §1.2;
 * `content-plane-cr2-quorumless.md` §10.2 G2, §10.7's B5 row). The first and only production caller of
 * [ContentFreeing.free].
 *
 * Every [intervalMs] ([org.tenshu.sync.relay.Application] drives [runOnce]), in [mode]:
 * - [ContentSweepMode.OFF]: nothing. The clock is still kept by the edge stores.
 * - [ContentSweepMode.DRY_RUN]: select the clock rows older than [graceMs] and log, per owner, what
 *   would be freed (count, capped ids, refs, bytes). Frees nothing.
 * - [ContentSweepMode.ARMED]: on the first run ever, seed the clock (D4); then [ContentFreeing.free]
 *   each expired content, and drain the free queue of every owner it freed for.
 *
 * ⚠⚠ **The free re-checks, the selection does not decide.** Between step 1's select and the free, a
 * push may add an edge; `free()`'s own transaction sees it and answers `NOT_FREED` (§10.2 G2: the free
 * and a push's commit serialise). The bytes go only through the drain, after the logical free.
 *
 * ⚠⚠ **The drain is [ContentReclaimJob.drain], unchanged.** It holds the store lock from selecting a
 * batch of queued refs to deleting their queue rows, and a PUT or GET dequeues its ref under that same
 * lock before touching bytes — so free → revive → free cannot interleave inside a batch (§10.10's queue
 * ABA; `ContentSweepJobTest` pins it). If the drain ever released the lock mid-batch, acknowledgement
 * would have to become compare-and-delete on the observed `enqueued_at`.
 *
 * ⚠ [drainer] is `null` on the in-memory relay: there is no queue there, and the free leaves the bytes
 * (B4 spec §1.5). Such a relay frees logically and never physically.
 *
 * ⚠ Owners whose drain left work (a paused or failed batch, or more than one call's worth) are retried
 * on every later run until their queue is empty. That set is in memory: after a restart, an owner's
 * leftover is drained on its next free, or by the operator route.
 */
class ContentSweepJob(
    val mode: ContentSweepMode,
    val graceMs: Long,
    val intervalMs: Long,
    private val edges: ContentEdgeRegistry,
    private val freeing: ContentFreeing,
    private val refs: ContentRefRegistry,
    private val blobs: ContentBlobRegistry,
    private val drainer: ContentReclaimJob?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    init {
        require(graceMs > 0) { "graceMs must be positive, was $graceMs" }
        require(intervalMs > 0) { "intervalMs must be positive, was $intervalMs" }
    }

    /** One run's outcome, also the status route's `lastRun`. */
    @Serializable
    data class RunReport(
        val mode: String,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        /** Rows written by D4's seed on this run (armed, first run only); `null` when it did not run. */
        val seeded: Int? = null,
        /** Clock rows older than T at the start of the run. */
        val candidates: Int = 0,
        val owners: Int = 0,
        /** dry_run: what the candidates' refs add up to (refs and their indexed bytes). */
        val wouldFreeRefs: Int = 0,
        val wouldFreeBytes: Long = 0,
        /** dry_run: contents arming would seed onto the clock now (D4), freed T after arming. */
        val pendingSeed: Int = 0,
        val freed: Int = 0,
        /** A push won the race: the content has a live edge again (§1.2 step 3). */
        val notFreed: Int = 0,
        val drainedBlobs: Int = 0,
        val drainedBytes: Long = 0,
        /** Refs still queued for the owners this run drained, after it. */
        val remainingQueued: Int = 0,
        val error: String? = null,
    )

    /** `GET /ops/content-sweep/status`. Read-only. */
    @Serializable
    data class Status(
        val mode: String,
        val graceMs: Long,
        val intervalMs: Long,
        val durableDrain: Boolean,
        val seededAtMs: Long?,
        /** Clock rows right now, every owner. */
        val unreferenced: Int,
        val lastRun: RunReport?,
        val totals: Totals,
    )

    @Serializable
    data class Totals(
        val runs: Long = 0,
        val freed: Long = 0,
        val notFreed: Long = 0,
        val drainedBlobs: Long = 0,
        val drainedBytes: Long = 0,
        val failedRuns: Long = 0,
    )

    private val lock = Any()
    private var lastRun: RunReport? = null
    private var totals = Totals()
    private val pendingDrain = linkedSetOf<String>()

    /** One sweep, as the scheduled loop runs it. Serialised: a second caller waits for the first. */
    fun runOnce(): RunReport = synchronized(lock) {
        if (mode == ContentSweepMode.OFF) {
            return@synchronized RunReport(mode = mode.wire, startedAtMs = clock(), finishedAtMs = clock())
        }
        val started = clock()
        val report = try {
            when (mode) {
                ContentSweepMode.DRY_RUN -> dryRun(started)
                ContentSweepMode.ARMED -> armed(started)
                ContentSweepMode.OFF -> error("unreachable")
            }
        } catch (t: Throwable) {
            totals = totals.copy(runs = totals.runs + 1, failedRuns = totals.failedRuns + 1)
            lastRun = RunReport(mode = mode.wire, startedAtMs = started, finishedAtMs = clock(), error = t.toString())
            throw t
        }
        totals = totals.copy(
            runs = totals.runs + 1,
            freed = totals.freed + report.freed,
            notFreed = totals.notFreed + report.notFreed,
            drainedBlobs = totals.drainedBlobs + report.drainedBlobs,
            drainedBytes = totals.drainedBytes + report.drainedBytes,
        )
        lastRun = report
        report
    }

    fun status(): Status = synchronized(lock) {
        Status(
            mode = mode.wire,
            graceMs = graceMs,
            intervalMs = intervalMs,
            durableDrain = drainer != null,
            seededAtMs = freeing.seededAt(),
            unreferenced = edges.unreferencedCount(),
            lastRun = lastRun,
            totals = totals,
        )
    }

    private fun expiredByOwner(now: Long): Map<String, List<String>> =
        edges.expiredUnreferenced(cutoffMs = now - graceMs, limit = MAX_CANDIDATES_PER_RUN)
            .groupBy({ it.ownerId }, { it.contentId })

    private fun dryRun(started: Long): RunReport {
        val byOwner = expiredByOwner(started)
        var refsTotal = 0
        var bytesTotal = 0L
        for ((ownerId, contentIds) in byOwner) {
            val ownerRefs = refs.refsByContent(ownerId, contentIds).values.flatten().toSet()
            val index = blobs.blobIndex(ownerId)
            val bytes = ownerRefs.sumOf { index[it]?.byteLen ?: 0L }
            refsTotal += ownerRefs.size
            bytesTotal += bytes
            log(
                "content-sweep dry_run owner=${logId(ownerId)} wouldFree=${contentIds.size} refs=${ownerRefs.size} bytes=$bytes " +
                    "ids=${contentIds.take(MAX_LOGGED_IDS)}${if (contentIds.size > MAX_LOGGED_IDS) " (+${contentIds.size - MAX_LOGGED_IDS} more)" else ""}",
            )
        }
        val pendingSeed = if (freeing.seededAt() == null) freeing.pendingSeedCount() else 0
        if (pendingSeed > 0) {
            log("content-sweep dry_run arming would seed $pendingSeed described zero-edge content(s) onto the clock (D4)")
        }
        return RunReport(
            mode = mode.wire,
            startedAtMs = started,
            finishedAtMs = clock(),
            candidates = byOwner.values.sumOf { it.size },
            owners = byOwner.size,
            wouldFreeRefs = refsTotal,
            wouldFreeBytes = bytesTotal,
            pendingSeed = pendingSeed,
        )
    }

    private fun armed(started: Long): RunReport {
        // D4: before step 1, once per relay (durable marker), so the backlog enters T now.
        val seeded = freeing.seedClockOnce(started)
        if (seeded != null) log("content-sweep armed: clock seeded for $seeded described zero-edge content(s) (D4)")

        val byOwner = expiredByOwner(started)
        var freed = 0
        var notFreed = 0
        var drainedBlobs = 0
        var drainedBytes = 0L
        var remaining = 0
        for ((ownerId, contentIds) in byOwner) {
            var ownerFreed = 0
            var ownerNotFreed = 0
            for (contentId in contentIds) {
                when (freeing.free(ownerId, contentId, clock())) {
                    ContentFreeing.FreeResult.FREED -> ownerFreed++
                    ContentFreeing.FreeResult.NOT_FREED -> {
                        ownerNotFreed++
                        // The push that won already cleared the row; this only restores the invariant
                        // "a live edge has no clock row" should anything ever leave one behind.
                        edges.clearUnreferenced(ownerId, contentId)
                    }
                }
            }
            freed += ownerFreed
            notFreed += ownerNotFreed
            if (ownerFreed > 0) pendingDrain += ownerId
            log("content-sweep armed owner=${logId(ownerId)} candidates=${contentIds.size} freed=$ownerFreed notFreed=$ownerNotFreed")
        }
        // Step 4: the drain, for every owner with work left (this run's frees, and earlier leftovers).
        if (drainer != null) {
            for (ownerId in pendingDrain.toList()) {
                val result = drainer.drain(ownerId, maxBatches = MAX_DRAIN_BATCHES_PER_OWNER)
                drainedBlobs += result.deletedBlobs
                drainedBytes += result.freedBytes
                remaining += result.remaining
                if (result.remaining == 0) pendingDrain -= ownerId
                log(
                    "content-sweep armed drain owner=${logId(ownerId)} deleted=${result.deletedBlobs} bytes=${result.freedBytes} " +
                        "spared=${result.spared} remaining=${result.remaining} pausedOnEdgeOnly=${result.pausedOnEdgeOnly} " +
                        "failedDeletes=${result.failedDeletes}",
                )
            }
        } else {
            pendingDrain.clear()
        }
        return RunReport(
            mode = mode.wire,
            startedAtMs = started,
            finishedAtMs = clock(),
            seeded = seeded,
            candidates = byOwner.values.sumOf { it.size },
            owners = byOwner.size,
            freed = freed,
            notFreed = notFreed,
            drainedBlobs = drainedBlobs,
            drainedBytes = drainedBytes,
            remainingQueued = remaining,
        )
    }

    companion object {
        /** Frees per run. Each is its own short transaction; a larger backlog (the seed) spreads over runs. */
        const val MAX_CANDIDATES_PER_RUN = 5_000

        /** Drain batches per owner per run (each [ContentReclaimJob.DEFAULT_BATCH] refs, one lock hold each). */
        const val MAX_DRAIN_BATCHES_PER_OWNER = 50

        const val MAX_LOGGED_IDS = 20
    }
}
