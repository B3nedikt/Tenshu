package org.tenshu.core.common.util

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

/**
 * Default number of chunk transfers kept in flight by [mapChunksBounded].
 *
 * **Why 4, and why a small number at all.** Every content-plane chunk transfer is one
 * request/response against a *single-region* relay (Paris today — `deploy/scaleway-relay/`),
 * so the per-chunk cost is `RTT + transfer`.
 * Run those strictly one at a time and throughput is bounded by round trips rather than by
 * bandwidth: a European device barely notices at ~20 ms RTT, while the same code from
 * US-West or Asia (~150–250 ms) spends most of a large upload idle, waiting. Overlapping N
 * transfers amortises the RTT term ~N-fold without touching the relay.
 *
 * 4 is picked to sit **under every platform's default per-host connection cap** — OkHttp's
 * `maxRequestsPerHost` is 5 and Darwin's `HTTPMaximumConnectionsPerHost` is 6 — so the fan-out
 * is genuinely concurrent on the wire instead of silently queueing inside the HTTP client, and
 * no shared `HttpClient` needs reconfiguring to benefit. Raising it past those caps buys
 * nothing until they are raised too.
 *
 * ⚠ It also sets the memory bound, which is the reason this is not simply "as many as
 * possible". The emit path's resident-set guarantee was `O(CHUNK)` — one 2 MiB plaintext plus
 * its ciphertext — and that bound is load-bearing: whole-file buffering here is what OOM-killed
 * Control on multi-megabyte photos. The guarantee is now `O(concurrency × CHUNK)`, which at 4
 * is ~16 MiB peak and still independent of file size and of upload backlog. That independence,
 * not the absolute number, is the invariant worth protecting — see [mapChunksBounded] for how
 * the permit ordering enforces it.
 */
const val DefaultChunkTransferConcurrency: Int = 4

/**
 * Runs [transfer] over a sequentially-produced stream of chunks with at most [concurrency]
 * transfers in flight, returning their results **in production order**.
 *
 * The shape exists because chunked upload paths have two halves with opposite needs. Producing
 * a chunk (a positional read, an incremental hash fold, an offset advance) is order-dependent
 * and cheap; transferring it is order-*independent* and dominated by network latency. So
 * [produceNext] runs sequentially on the calling coroutine — preserving read order, hash order
 * and offset chaining exactly as a plain `while` loop would — while only [transfer] fans out.
 * Callers that need in-order side effects (folding [IncrementalSha256], say) put them in
 * [produceNext] and get them for free.
 *
 * **The permit is taken before producing, not before transferring**, and that ordering is the
 * whole memory bound: a chunk buffer is only allocated once there is a free slot to carry it,
 * so at most [concurrency] chunks are ever resident. Acquiring after production would let the
 * producer race ahead and read the entire file into heap while transfers drained — the exact
 * unbounded-buffering failure this helper is shaped to prevent.
 *
 * **Failure aborts the batch.** A [transfer] that throws cancels the sibling transfers and
 * propagates out of the enclosing `coroutineScope`, so callers keep the "first failure ends the
 * upload" semantics a sequential loop gave them, without waiting for the rest to finish. Chunk
 * PUTs are content-addressed and idempotent, so a retry re-sends the whole set harmlessly.
 *
 * @param concurrency maximum transfers in flight; must be >= 1. `1` degrades to the sequential
 *   behaviour exactly, which is what makes it a safe kill-switch.
 * @param produceNext called with the next 0-based chunk index; returns the chunk, or `null` to
 *   end the stream. Runs on the calling coroutine, never concurrently with itself.
 * @param transfer the per-chunk work to overlap. Receives the index it was produced with, so
 *   index-bound AAD stays correct under reordering.
 */
suspend fun <T, R> mapChunksBounded(
    concurrency: Int = DefaultChunkTransferConcurrency,
    produceNext: suspend (index: Int) -> T?,
    transfer: suspend (index: Int, chunk: T) -> R,
): List<R> {
    require(concurrency >= 1) { "concurrency must be >= 1, was $concurrency" }
    return coroutineScope {
        val permits = Semaphore(concurrency)
        val transfers = mutableListOf<Deferred<R>>()
        var index = 0
        while (true) {
            // Bound residency *before* the buffer exists (see the doc comment). This also
            // yields when saturated, which is what lets the already-launched transfers run
            // on a single-threaded dispatcher — the producer loop never starves them.
            permits.acquire()
            val chunk = try {
                produceNext(index)
            } catch (t: Throwable) {
                permits.release()
                throw t
            }
            if (chunk == null) {
                permits.release()
                break
            }
            val chunkIndex = index
            transfers += async {
                try {
                    transfer(chunkIndex, chunk)
                } finally {
                    permits.release()
                }
            }
            index++
        }
        transfers.awaitAll()
    }
}
